package com.navaneeth.filer.data.files

import android.content.Context
import android.media.MediaScannerConnection
import android.util.Log
import com.navaneeth.filer.data.ai.PluginData
import com.navaneeth.filer.data.repository.FileChange
import com.navaneeth.filer.data.repository.IndexRepository
import com.navaneeth.filer.data.storage.Storage
import com.navaneeth.filer.data.worker.IndexingWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

sealed interface NameResult {
    data class Done(val path: String) : NameResult
    data object Invalid : NameResult
    data object Exists : NameResult
    data object Failed : NameResult
}

/** How a batch operation went: [done] items succeeded, [failed] didn't; [ids] are new bin/locked rows. */
data class OpOutcome(val done: Int, val failed: Int, val ids: List<Long> = emptyList())

/**
 * Every change Filer makes to files: rename, new folder, copy/move, Recycle bin, Locked folder and
 * favourites. All operations are serialised and run on IO. Afterwards Android's media index is told
 * (so Recent and categories update) and smart search re-checks its folders.
 */
class FileManager private constructor(private val context: Context) {

    private val dao = FileManagerDatabase.getInstance(context).dao()
    private val pluginData = PluginData.getInstance(context)
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _changes = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** Emits after any operation that changed files on disk. */
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    fun favorites(): Flow<List<FavoriteEntity>> = dao.favorites()
    fun trash(): Flow<List<TrashEntity>> = dao.trash()
    fun locked(): Flow<List<LockedEntity>> = dao.locked()

    // Names

    suspend fun rename(path: String, newName: String): NameResult = op {
        val name = newName.trim()
        if (!isValidName(name)) return@op NameResult.Invalid
        val src = File(path)
        val dst = File(src.parentFile, name)
        if (dst.path == src.path) return@op NameResult.Done(path)
        // A case-only rename is the same file on Android's case-insensitive storage.
        if (dst.exists() && !dst.path.equals(src.path, ignoreCase = true)) return@op NameResult.Exists
        if (!src.renameTo(dst)) return@op NameResult.Failed
        movedFavorites(src.path, dst.path)
        pluginData.moved(src.path, dst.path)
        changed(listOf(FileChange.Moved(src.path, dst.path)), src.path, dst.path)
        NameResult.Done(dst.path)
    }

    suspend fun createFolder(parent: String, name: String): NameResult = op {
        val clean = name.trim()
        if (!isValidName(clean)) return@op NameResult.Invalid
        val dir = File(parent, clean)
        when {
            dir.exists() -> NameResult.Exists
            dir.mkdir() -> NameResult.Done(dir.path).also { changed(emptyList(), dir.path) }
            else -> NameResult.Failed
        }
    }

    // Copy and move

    /** Copies or moves [paths] into [destination]. Name clashes get " (1)", " (2)"… like the Files app. */
    suspend fun transfer(paths: List<String>, destination: String, move: Boolean): OpOutcome = op {
        val destDir = File(destination)
        var done = 0
        var failed = 0
        val touched = mutableListOf<String>()
        val index = mutableListOf<FileChange>()
        for (path in paths) {
            val src = File(path)
            // Never into itself or one of its own subfolders.
            if (!src.exists() || destDir.path == src.path || destDir.path.startsWith(src.path + "/")) {
                failed++
                continue
            }
            if (move && src.parentFile?.path == destDir.path) {
                done++
                continue
            }
            val dst = uniqueTarget(destDir, src.name)
            val ok = if (move) moveTo(src, dst) else copyTo(src, dst)
            if (ok) {
                done++
                touched += dst.path
                if (move) {
                    touched += src.path
                    movedFavorites(src.path, dst.path)
                    pluginData.moved(src.path, dst.path)
                    index += FileChange.Moved(src.path, dst.path)
                } else {
                    index += FileChange.Copied(src.path, dst.path)
                }
            } else {
                failed++
            }
        }
        changed(index, *touched.toTypedArray())
        OpOutcome(done, failed)
    }

    // Recycle bin

    /** Moves [paths] into a hidden bin on their own volume (instant: no copying). */
    suspend fun moveToTrash(paths: List<String>): OpOutcome = op {
        val ids = mutableListOf<Long>()
        var failed = 0
        val parked = mutableListOf<FileChange>()
        for (path in paths) {
            val src = File(path)
            val root = trashRootFor(path)
            if (!src.exists() || root == null) {
                failed++
                continue
            }
            val slot = File(root, slotName()).apply { mkdirs() }
            val dst = File(slot, src.name)
            val size = sizeOf(src)
            if (moveTo(src, dst)) {
                ids += dao.insertTrash(
                    TrashEntity(
                        name = src.name,
                        originalPath = src.path,
                        storedPath = dst.path,
                        isDirectory = dst.isDirectory,
                        sizeBytes = size,
                        mimeType = if (dst.isDirectory) "" else Storage.mimeTypeOf(src.name),
                        deletedAt = System.currentTimeMillis()
                    )
                )
                dao.deleteFavorite(src.path)
                // Tags and text wait in the bin with the file, so Restore brings them back.
                pluginData.moved(src.path, dst.path)
                parked += FileChange.Parked(src.path, dst.path)
            } else {
                slot.delete()
                failed++
            }
        }
        changed(parked, *paths.toTypedArray())
        OpOutcome(ids.size, failed, ids)
    }

    /** Puts bin items back where they were (recreating the folder if needed, renaming on a clash). */
    suspend fun restore(ids: List<Long>): OpOutcome = op {
        var done = 0
        var failed = 0
        val touched = mutableListOf<String>()
        val index = mutableListOf<FileChange>()
        for (item in dao.trashByIds(ids)) {
            val stored = File(item.storedPath)
            val original = File(item.originalPath)
            val parent = original.parentFile
            if (!stored.exists()) {
                dao.deleteTrash(item.id)
                failed++
                continue
            }
            if (parent == null || !(parent.isDirectory || parent.mkdirs())) {
                failed++
                continue
            }
            val dst = uniqueTarget(parent, item.name)
            if (moveTo(stored, dst)) {
                stored.parentFile?.delete()
                dao.deleteTrash(item.id)
                pluginData.moved(stored.path, dst.path)
                touched += dst.path
                index += FileChange.Moved(stored.path, dst.path)
                done++
            } else {
                failed++
            }
        }
        changed(index, *touched.toTypedArray())
        OpOutcome(done, failed)
    }

    suspend fun deleteForever(ids: List<Long>): OpOutcome = op {
        var done = 0
        for (item in dao.trashByIds(ids)) {
            if (purge(item)) done++
        }
        OpOutcome(done, ids.size - done)
    }

    /** Deletes [paths] for good, skipping the Recycle bin, so their space is freed straight away. */
    suspend fun deleteNow(paths: List<String>): OpOutcome = op {
        val removed = mutableListOf<FileChange>()
        for (path in paths) {
            val file = File(path)
            if (file.exists() && file.deleteRecursively()) {
                dao.deleteFavorite(path)
                pluginData.forget(path)
                removed += FileChange.Removed(path)
            }
        }
        changed(removed, *paths.toTypedArray())
        OpOutcome(removed.size, paths.size - removed.size)
    }

    suspend fun emptyTrash(): OpOutcome = op {
        val all = dao.allTrash()
        val done = all.count { purge(it) }
        OpOutcome(done, all.size - done)
    }

    /** Items stay in the bin for [TRASH_DAYS] days. */
    suspend fun purgeExpiredTrash() = op {
        dao.trashOlderThan(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(TRASH_DAYS)).forEach { purge(it) }
    }

    private suspend fun purge(item: TrashEntity): Boolean {
        val stored = File(item.storedPath)
        val ok = !stored.exists() || stored.deleteRecursively()
        if (ok) {
            stored.parentFile?.delete()
            dao.deleteTrash(item.id)
            pluginData.forget(item.storedPath)
            // Its parked search data goes with it.
            changed(listOf(FileChange.Removed(item.storedPath)))
        }
        return ok
    }

    // Locked folder

    /**
     * Moves [paths] into Filer's private storage: other apps, the media index and smart search can
     * no longer see them. They are deleted if Filer is uninstalled or its data is cleared.
     */
    suspend fun lock(paths: List<String>): OpOutcome = op {
        val ids = mutableListOf<Long>()
        var failed = 0
        val removed = mutableListOf<String>()
        for (path in paths) {
            val src = File(path)
            if (!src.exists()) {
                failed++
                continue
            }
            val slot = File(lockedRoot(), slotName()).apply { mkdirs() }
            val dst = File(slot, src.name)
            val size = sizeOf(src)
            if (moveTo(src, dst)) {
                ids += dao.insertLocked(
                    LockedEntity(
                        name = src.name,
                        originalPath = src.path,
                        storedPath = dst.path,
                        isDirectory = dst.isDirectory,
                        sizeBytes = size,
                        mimeType = if (dst.isDirectory) "" else Storage.mimeTypeOf(src.name),
                        lockedAt = System.currentTimeMillis()
                    )
                )
                dao.deleteFavorite(src.path)
                // Locked files are private: nothing read from them stays outside.
                pluginData.forget(src.path)
                removed += src.path
            } else {
                slot.deleteRecursively()
                failed++
            }
        }
        changed(removed.map { FileChange.Removed(it) }, *removed.toTypedArray())
        OpOutcome(ids.size, failed, ids)
    }

    /** Moves locked items back to where they came from (or Download if that volume is gone). */
    suspend fun unlock(ids: List<Long>): OpOutcome = op {
        var done = 0
        var failed = 0
        val touched = mutableListOf<String>()
        for (item in dao.lockedByIds(ids)) {
            val stored = File(item.storedPath)
            if (!stored.exists()) {
                dao.deleteLocked(item.id)
                failed++
                continue
            }
            val parent = File(item.originalPath).parentFile
                ?.takeIf { it.isDirectory || it.mkdirs() }
                ?: fallbackFolder()
            if (parent == null) {
                failed++
                continue
            }
            val dst = uniqueTarget(parent, item.name)
            if (moveTo(stored, dst)) {
                stored.parentFile?.deleteRecursively()
                dao.deleteLocked(item.id)
                touched += dst.path
                done++
            } else {
                failed++
            }
        }
        changed(touched.map { FileChange.Added(it) }, *touched.toTypedArray())
        OpOutcome(done, failed)
    }

    suspend fun deleteLocked(ids: List<Long>): OpOutcome = op {
        var done = 0
        for (item in dao.lockedByIds(ids)) {
            val stored = File(item.storedPath)
            if (!stored.exists() || stored.deleteRecursively()) {
                stored.parentFile?.delete()
                dao.deleteLocked(item.id)
                done++
            }
        }
        OpOutcome(done, ids.size - done)
    }

    // Favourites

    suspend fun setFavorite(path: String, favorite: Boolean) = withContext(Dispatchers.IO) {
        if (favorite) dao.insertFavorite(FavoriteEntity(path, System.currentTimeMillis())) else dao.deleteFavorite(path)
    }

    /** Drops favourites whose file is gone (deleted outside Filer). */
    suspend fun pruneFavorites() = withContext(Dispatchers.IO) {
        dao.allFavorites().filterNot { File(it.path).exists() }.forEach { dao.deleteFavorite(it.path) }
    }

    // Helpers

    private suspend fun <T> op(block: suspend () -> T): T = withContext(Dispatchers.IO) { lock.withLock { block() } }

    private fun isValidName(name: String) = name.isNotEmpty() && name != "." && name != ".." && '/' !in name

    /** Same-volume moves are a rename; across volumes (or into private storage) copy, then delete. */
    private fun moveTo(src: File, dst: File): Boolean {
        if (src.renameTo(dst)) return true
        if (!copyTo(src, dst)) return false
        if (src.deleteRecursively()) return true
        // Couldn't remove the original: keep it and undo the copy rather than leave two.
        dst.deleteRecursively()
        return false
    }

    private fun copyTo(src: File, dst: File): Boolean = try {
        src.copyRecursively(dst, overwrite = false)
    } catch (e: Exception) {
        Log.w(TAG, "Copy failed: ${e.javaClass.simpleName}")
        dst.deleteRecursively()
        false
    }

    private fun uniqueTarget(dir: File, name: String): File {
        val first = File(dir, name)
        if (!first.exists()) return first
        val dot = name.lastIndexOf('.').takeIf { it > 0 && !first.isDirectory } ?: name.length
        val base = name.substring(0, dot)
        val ext = name.substring(dot)
        var n = 1
        while (true) {
            val candidate = File(dir, "$base ($n)$ext")
            if (!candidate.exists()) return candidate
            n++
        }
    }

    private fun sizeOf(file: File): Long =
        if (file.isDirectory) file.walkTopDown().filter { it.isFile }.sumOf { it.length() } else file.length()

    private fun slotName() = "${System.currentTimeMillis()}_${(0..0xFFFF).random().toString(16)}"

    private fun trashRootFor(path: String): File? {
        val volume = Storage.volumeFor(context, path) ?: return null
        val root = File(volume.root, TRASH_DIR)
        if (!root.isDirectory && !root.mkdirs()) return null
        // Keeps the gallery and media index out of the bin.
        File(root, ".nomedia").takeIf { !it.exists() }?.createNewFile()
        return root
    }

    private fun lockedRoot() = File(context.filesDir, LOCKED_DIR).apply { mkdirs() }

    private fun fallbackFolder(): File? =
        Storage.volumes(context).firstOrNull { it.isPrimary }?.root?.let { File(it, "Download") }?.takeIf { it.isDirectory || it.mkdirs() }

    private suspend fun movedFavorites(oldPath: String, newPath: String) {
        dao.allFavorites()
            .filter { it.path == oldPath || it.path.startsWith("$oldPath/") }
            .forEach { dao.renameFavorite(it.path, newPath + it.path.removePrefix(oldPath)) }
    }

    /**
     * Tells the media index about [paths], and smart search exactly what changed ([index]), so it
     * updates those rows instead of re-scanning; the model only runs for files new to it.
     */
    private fun changed(index: List<FileChange>, vararg paths: String) {
        if (paths.isNotEmpty()) MediaScannerConnection.scanFile(context, paths, null, null)
        if (!_changes.tryEmit(Unit)) {
            scope.launch { _changes.emit(Unit) }
        }
        if (index.isEmpty()) return
        scope.launch {
            runCatching {
                if (IndexRepository.getInstance(context).applyFileChanges(index)) IndexingWorker.enqueue(context)
            }.onFailure { Log.w(TAG, "Index update after file change failed: ${it.javaClass.simpleName}") }
        }
    }

    companion object {
        private const val TAG = "FileManager"
        const val TRASH_DAYS = 30L
        const val TRASH_DIR = ".FilerTrash"
        const val LOCKED_DIR = "locked"

        @Volatile
        private var INSTANCE: FileManager? = null

        fun getInstance(context: Context): FileManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: FileManager(context.applicationContext).also { INSTANCE = it }
            }
    }
}
