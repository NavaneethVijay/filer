package com.navaneeth.filer.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.navaneeth.filer.R
import com.navaneeth.filer.data.analysis.StorageAnalyzer
import com.navaneeth.filer.data.files.FileManager
import com.navaneeth.filer.data.files.LockedEntity
import com.navaneeth.filer.data.files.NameResult
import com.navaneeth.filer.data.files.OpOutcome
import com.navaneeth.filer.data.files.TrashEntity
import com.navaneeth.filer.data.storage.Storage
import com.navaneeth.filer.ui.model.FileUiItem
import com.navaneeth.filer.ui.model.pathOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/** Files waiting to be pasted: the user picks a folder, then taps "Move here" / "Copy here". */
@Immutable
data class Transfer(val paths: List<String>, val move: Boolean)

/** A dialog the file operations need; shown once, app-wide, by `FileOpsHost`. */
sealed interface FileDialog {
    data class Rename(val path: String, val name: String, val isDirectory: Boolean) : FileDialog
    data class NewFolder(val parent: String) : FileDialog
    data class ConfirmLock(val paths: List<String>) : FileDialog
    data class ConfirmTrash(val paths: List<String>) : FileDialog
}

/** A snackbar message, optionally with Undo. */
class FileOpMessage(val text: String, val undo: (() -> Unit)? = null)

val LocalFileOps = staticCompositionLocalOf<FileOpsViewModel> { error("FileOpsViewModel not provided") }

/**
 * Activity-scoped file management: rename, new folder, copy/move (pick a folder, then paste),
 * Recycle bin, Locked folder and favourites. Screens refresh themselves from [FileManager.changes].
 */
class FileOpsViewModel(application: Application) : AndroidViewModel(application) {

    private val files = FileManager.getInstance(application)
    private val res get() = getApplication<Application>().resources

    val favoritePaths: StateFlow<Set<String>> = files.favorites()
        .map { list -> list.mapTo(HashSet()) { it.path } }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** Favourites that still exist, newest first; null while loading. */
    val favoriteItems: StateFlow<List<FileUiItem>?> = combine(files.favorites(), files.changes.onStart { emit(Unit) }) { list, _ ->
        list.mapNotNull { fav -> File(fav.path).takeIf { it.exists() }?.toUiItem() }
    }.flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val trash: StateFlow<List<TrashEntity>?> = files.trash()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val locked: StateFlow<List<LockedEntity>?> = files.locked()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _transfer = MutableStateFlow<Transfer?>(null)
    val transfer: StateFlow<Transfer?> = _transfer.asStateFlow()

    private val _dialog = MutableStateFlow<FileDialog?>(null)
    val dialog: StateFlow<FileDialog?> = _dialog.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _messages = MutableSharedFlow<FileOpMessage>(extraBufferCapacity = 4)
    val messages: SharedFlow<FileOpMessage> = _messages.asSharedFlow()

    init {
        viewModelScope.launch {
            files.purgeExpiredTrash()
            files.pruneFavorites()
            com.navaneeth.filer.data.ai.PluginData.getInstance(getApplication()).prune()
        }
    }

    // Dialogs

    fun requestRename(item: FileUiItem) {
        val path = item.pathOrNull() ?: return
        _dialog.value = FileDialog.Rename(path, item.name, item.isDirectory)
    }

    fun requestNewFolder(parent: String) {
        _dialog.value = FileDialog.NewFolder(parent)
    }

    /** Asks before moving to the Recycle bin (it can be undone, but shouldn't happen by a stray tap). */
    fun requestTrash(paths: List<String>) {
        if (paths.isNotEmpty()) _dialog.value = FileDialog.ConfirmTrash(paths)
    }

    fun requestLock(paths: List<String>) {
        if (paths.isNotEmpty()) _dialog.value = FileDialog.ConfirmLock(paths)
    }

    fun dismissDialog() {
        _dialog.value = null
    }

    suspend fun rename(path: String, name: String): NameResult = files.rename(path, name)

    suspend fun createFolder(parent: String, name: String): NameResult = files.createFolder(parent, name)

    // Copy / move

    fun startTransfer(paths: List<String>, move: Boolean) {
        if (paths.isNotEmpty()) _transfer.value = Transfer(paths, move)
    }

    fun cancelTransfer() {
        _transfer.value = null
    }

    fun pasteInto(folder: String) {
        val pending = _transfer.value ?: return
        _transfer.value = null
        run {
            val outcome = files.transfer(pending.paths, folder, pending.move)
            report(outcome, if (pending.move) R.plurals.op_moved else R.plurals.op_copied)
        }
    }

    // Recycle bin

    fun moveToTrash(paths: List<String>) {
        if (paths.isEmpty()) return
        run {
            val outcome = files.moveToTrash(paths)
            if (outcome.done > 0) {
                StorageAnalyzer.getInstance(getApplication()).dropMissingFiles(paths.toSet())
                val ids = outcome.ids
                _messages.emit(FileOpMessage(res.getQuantityString(R.plurals.op_trashed, outcome.done, outcome.done)) { restore(ids, quiet = true) })
            }
            if (outcome.failed > 0) failed(outcome.failed)
        }
    }

    fun restore(ids: List<Long>, quiet: Boolean = false) = run {
        val outcome = files.restore(ids)
        StorageAnalyzer.getInstance(getApplication()).dropMissingFiles()
        if (quiet) {
            if (outcome.failed > 0) failed(outcome.failed)
        } else {
            report(outcome, R.plurals.op_restored)
        }
    }

    fun deleteForever(ids: List<Long>) = run {
        val outcome = files.deleteForever(ids)
        StorageAnalyzer.getInstance(getApplication()).dropMissingFiles()
        report(outcome, R.plurals.op_deleted)
    }

    /** Deletes files without the bin (cleanup with "Skip the Recycle bin"). */
    fun deleteNow(paths: List<String>) {
        if (paths.isNotEmpty()) run {
            val outcome = files.deleteNow(paths)
            if (outcome.done > 0) {
                StorageAnalyzer.getInstance(getApplication()).dropMissingFiles(paths.toSet())
            }
            report(outcome, R.plurals.op_deleted)
        }
    }

    fun emptyTrash() = run {
        val outcome = files.emptyTrash()
        StorageAnalyzer.getInstance(getApplication()).dropMissingFiles()
        report(outcome, R.plurals.op_deleted)
    }

    // Locked folder

    fun lock(paths: List<String>) {
        _dialog.value = null
        run { report(files.lock(paths), R.plurals.op_locked) }
    }

    fun unlock(ids: List<Long>) = run { report(files.unlock(ids), R.plurals.op_unlocked) }

    fun deleteLocked(ids: List<Long>) = run { report(files.deleteLocked(ids), R.plurals.op_deleted) }

    // Favourites

    fun setFavorite(paths: List<String>, favorite: Boolean) {
        viewModelScope.launch {
            paths.forEach { files.setFavorite(it, favorite) }
            val text = if (favorite) res.getQuantityString(R.plurals.op_favorited, paths.size, paths.size)
            else res.getQuantityString(R.plurals.op_unfavorited, paths.size, paths.size)
            _messages.emit(FileOpMessage(text))
        }
    }

    private fun run(block: suspend () -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            try {
                block()
            } finally {
                _busy.value = false
            }
        }
    }

    private suspend fun report(outcome: OpOutcome, done: Int) {
        if (outcome.done > 0) _messages.emit(FileOpMessage(res.getQuantityString(done, outcome.done, outcome.done)))
        if (outcome.failed > 0) failed(outcome.failed)
    }

    private suspend fun failed(count: Int) {
        _messages.emit(FileOpMessage(res.getQuantityString(R.plurals.op_failed, count, count)))
    }
}

/** A local file as a row, for favourites and the Locked folder. */
fun File.toUiItem(name: String = this.name, location: String? = parentFile?.name) = FileUiItem(
    uri = Uri.fromFile(this).toString(),
    name = name,
    mimeType = if (isDirectory) "" else Storage.mimeTypeOf(name),
    sizeBytes = if (isDirectory) 0L else length(),
    modifiedAt = lastModified(),
    location = location,
    isDirectory = isDirectory,
    documentId = path,
    childCount = if (isDirectory) list()?.count { !it.startsWith(".") } ?: 0 else 0
)
