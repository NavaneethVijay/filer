package com.navaneeth.filer.data.repository

import android.content.Context
import android.net.Uri
import com.navaneeth.filer.data.crawler.DiscoveredFile
import com.navaneeth.filer.data.crawler.LocalFolderCrawler
import com.navaneeth.filer.data.storage.IndexRules
import com.navaneeth.filer.data.db.FilerDatabase
import com.navaneeth.filer.data.db.entity.ActivityLogEntity
import com.navaneeth.filer.data.db.entity.ChunkEntity
import com.navaneeth.filer.data.extractor.TextChunker
import com.navaneeth.filer.data.db.entity.FileEntity
import com.navaneeth.filer.data.db.entity.IndexRunEntity
import com.navaneeth.filer.data.db.entity.SourceEntity
import com.navaneeth.filer.data.extractor.OfficeTextExtractor
import com.navaneeth.filer.data.extractor.PdfExtractor
import com.navaneeth.filer.data.extractor.PlainTextExtractor
import com.navaneeth.filer.data.inference.EmbedderEngine
import com.navaneeth.filer.data.inference.EngineTag
import com.navaneeth.filer.data.inference.FileVectors
import com.navaneeth.filer.data.inference.RemoteEmbedder
import com.navaneeth.filer.data.model.FileTypeFilter
import com.navaneeth.filer.data.model.FileTypeStats
import com.navaneeth.filer.data.model.IndexStatus
import com.navaneeth.filer.data.model.SourceType
import com.navaneeth.filer.data.model.StorageBreakdown
import com.navaneeth.filer.data.vector.VectorIndex
import com.navaneeth.filer.data.db.entity.EmbeddingEntity
import com.navaneeth.filer.data.db.entity.EmbeddingKind
import androidx.room.withTransaction
import android.graphics.Bitmap
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import com.navaneeth.filer.data.extractor.UnreadableFileException
import com.navaneeth.filer.data.worker.ThermalGovernor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import com.navaneeth.filer.data.storage.ContentHasher
import com.navaneeth.filer.data.preferences.PreferencesManager
import com.navaneeth.filer.data.telemetry.SystemResourceMonitor
import com.navaneeth.filer.data.telemetry.SystemResourceUsage
import android.app.usage.StorageStatsManager
import android.os.Process
import android.os.SystemClock
import android.os.storage.StorageManager
import com.navaneeth.filer.data.files.FileManager
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import android.util.Log
import com.navaneeth.filer.data.model.DEVICE_SOURCE_URI
import com.navaneeth.filer.data.storage.Storage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "IndexRepository"

/** Detailed timing profile for a single indexed file. */
data class FileProfile(
    val fileName: String = "",
    val isImage: Boolean = false,
    val totalMs: Long = 0L,
    val prepMs: Long = 0L,      // image decode or document text extraction
    val thermalMs: Long = 0L,   // thermal capacity wait / duty cycle rest
    val inferMs: Long = 0L,     // neural model embedding inference
    val dbMs: Long = 0L         // SQLite transaction & hash calculation
)

/** Real-time profiling metrics and averages across the current indexing pass. */
data class IndexProfiling(
    val lastFile: FileProfile? = null,
    val avgPhotoMs: Long = 0L,
    val avgDocMs: Long = 0L,
    val photoCount: Int = 0,
    val docCount: Int = 0,
    val recentProfiles: List<FileProfile> = emptyList()
)

class IndexProfilingTracker {
    private var photoTotalMs = 0L
    private var photoCount = 0
    private var docTotalMs = 0L
    private var docCount = 0
    private var lastFileProfile: FileProfile? = null
    private val recent = ArrayDeque<FileProfile>()

    fun record(profile: FileProfile) {
        lastFileProfile = profile
        if (profile.isImage) {
            photoTotalMs += profile.totalMs
            photoCount++
        } else {
            docTotalMs += profile.totalMs
            docCount++
        }
        if (recent.size >= 8) {
            recent.removeFirst()
        }
        recent.addLast(profile)
    }

    fun snapshot(): IndexProfiling = IndexProfiling(
        lastFile = lastFileProfile,
        avgPhotoMs = if (photoCount > 0) photoTotalMs / photoCount else 0L,
        avgDocMs = if (docCount > 0) docTotalMs / docCount else 0L,
        photoCount = photoCount,
        docCount = docCount,
        recentProfiles = recent.reversed()
    )
}

data class IndexProgress(
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,
    val isThermalThrottled: Boolean = false,
    val currentFileName: String = "",
    val processedCount: Int = 0,
    val totalCount: Int = 0,
    val throughputFilesPerSec: Float = 0f,
    val etaSeconds: Int = 0,
    /** Developer mode: the model is running on the connected Mac for this pass. */
    val viaMac: Boolean = false,
    /** Passages and photos embedded per second. */
    val itemsPerSec: Float = 0f,
    /** Developer mode: the Mac stopped answering; the pass waits for it (see [IndexingPolicy]). */
    val waitingForMac: Boolean = false,
    /** Real-time profiling measurements for each stage per file. */
    val profiling: IndexProfiling = IndexProfiling()
)

class IndexRepository(private val context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: IndexRepository? = null

        /**
         * Pass 2: Maximum photos to embed in a single on-phone CPU pass while running on battery.
         * Keeps phone cool and battery fresh; remaining archive photos are deferred to Pass 3 (charging).
         */
        const val MAX_BATTERY_PHOTOS_PER_PASS = 10

        fun getInstance(context: Context): IndexRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: IndexRepository(context.applicationContext).also { INSTANCE = it }
            }
        }

        private val _globalProgress = MutableStateFlow(IndexProgress())
        val globalProgress: StateFlow<IndexProgress> = _globalProgress.asStateFlow()

        private val _passFinished = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

        /** Emits when an indexing pass ends normally, even one that had nothing to embed. */
        val passFinished: SharedFlow<Unit> = _passFinished.asSharedFlow()

        private val isCancelled = AtomicBoolean(false)
        private val isPaused = AtomicBoolean(false)

        /**
         * One indexing pass at a time. A new pass (e.g. a rebuild) waits for a cancelled one to
         * finish its current file instead of running beside it and embedding the same files twice.
         */
        private val passLock = Mutex()

        /** Discovery reads then writes the files table; two at once would race on new files. */
        private val discoveryLock = Mutex()

        /** Rows per transaction when recording discovered files. */
        private const val DISCOVERY_BATCH = 500

        /** Problems that reading the file again won't fix (see [UnreadableFileException]). */
        private val PERMANENT_PROBLEMS = setOf(UnreadableFileException.PROTECTED, UnreadableFileException.UNREADABLE)

        /** Files to process before showing a time-left estimate. */
        private const val MIN_FILES_FOR_ESTIMATE = 3

    }

    private val db = FilerDatabase.getInstance(context)
    private val sourceDao = db.sourceDao()
    private val fileDao = db.fileDao()
    private val chunkDao = db.chunkDao()
    private val telemetryDao = db.telemetryDao()

    private val crawler = LocalFolderCrawler()
    private val plainTextExtractor = PlainTextExtractor(context)
    private val pdfExtractor = PdfExtractor(context)
    private val officeExtractor = OfficeTextExtractor(context)
    private val embedder = EmbedderEngine(context)
    private val pluginDao = com.navaneeth.filer.data.files.FileManagerDatabase.getInstance(context).plugins()
    private val thermal = ThermalGovernor(context)
    private val embeddingDao = db.embeddingDao()
    private val prefs = PreferencesManager(context)

    private val resourceMonitor = SystemResourceMonitor(context, thermal, embedder)
    val resourceUsage: StateFlow<SystemResourceUsage> = resourceMonitor.usage

    val progress: StateFlow<IndexProgress> = _globalProgress.asStateFlow()

    /**
     * Adds a folder to smart search; returns its id (existing id if already added). The path is
     * canonicalised so /sdcard/Documents and /storage/emulated/0/Documents are the same folder.
     */
    suspend fun addLocalFolder(path: String, displayName: String): Long = withContext(Dispatchers.IO) {
        val canonical = runCatching { File(path).canonicalPath }.getOrDefault(path)
        sourceDao.getSourceByUri(canonical)?.let { return@withContext it.id }
        sourceDao.insertSource(
            SourceEntity(type = SourceType.LOCAL_FOLDER, uri = canonical, displayName = displayName, granted = true)
        )
    }

    /** Turns "Whole phone" (every photo, PDF and text file) on or off. */
    suspend fun setWholePhone(enabled: Boolean, displayName: String) = withContext(Dispatchers.IO) {
        val existing = sourceDao.getSourceByUri(DEVICE_SOURCE_URI)
        if (enabled && existing == null) {
            sourceDao.insertSource(SourceEntity(type = SourceType.DEVICE, uri = DEVICE_SOURCE_URI, displayName = displayName))
            syncAllSources()
        } else if (!enabled && existing != null) {
            removeSource(existing.id)
        }
    }

    /**
     * Removes a source. Its files that another source still covers (a folder inside "Whole phone",
     * or the other way round) are handed over with their vectors intact; the rest are deleted.
     */
    suspend fun removeSource(sourceId: Long) = withContext(Dispatchers.IO) {
        val source = sourceDao.getActiveSources().find { it.id == sourceId } ?: return@withContext
        discoveryLock.withLock {
            discoverAll(excludeSourceId = sourceId)
            // Whatever is left belongs only to this source; passages and vectors cascade.
            fileDao.deleteFilesBySourceId(sourceId)
            sourceDao.deleteSource(source)
        }
        VectorIndex.invalidate()
        compactDatabase()
        telemetryDao.logActivity(ActivityLogEntity(action = "REMOVE_SOURCE", details = "Removed a smart-search source"))
    }

    /**
     * Lists the smart-search files and records new or changed ones as waiting. Cheap (no model work),
     * so a newly added folder shows up in Browse and Recent right away, even mid-way through a pass.
     */
    suspend fun syncSource(@Suppress("UNUSED_PARAMETER") sourceId: Long) {
        // Sources can overlap (a folder inside "Whole phone"), so every source is reconciled together.
        syncAllSources()
    }

    /** [syncSource] for every source; returns true when something is waiting to be embedded. */
    suspend fun syncAllSources(): Boolean = withContext(Dispatchers.IO) {
        discoveryLock.withLock { discoverAll() }
        fileDao.getPendingFiles(limit = 1).isNotEmpty()
    }

    /**
     * Follows file operations made in Filer without re-scanning: renamed/moved files keep their
     * passages and vectors under the new path, copies reuse the original's, removed files are
     * dropped, and only files new to smart search are queued. Returns true if something is waiting
     * to be embedded.
     */
    suspend fun applyFileChanges(changes: List<FileChange>): Boolean = withContext(Dispatchers.IO) {
        if (changes.isEmpty()) return@withContext false
        var vectorsChanged = false
        discoveryLock.withLock {
            // A smart folder that was itself renamed or moved keeps its files under the new path.
            changes.filterIsInstance<FileChange.Moved>().forEach { moveSources(it.from, it.to) }
            val coverage = Coverage(
                sources = sourceDao.getActiveSources().sortedWith(compareBy<SourceEntity> { it.type == SourceType.DEVICE }.thenBy { it.id }),
                volumeRoots = Storage.volumes(context).map { it.root.path },
                kinds = prefs.indexedKinds
            )
            db.withTransaction {
                for (change in changes) {
                    vectorsChanged = when (change) {
                        is FileChange.Moved -> moveRows(change.from, change.to, coverage).also { addTree(change.to, coverage) }
                        is FileChange.Copied -> cloneRows(change.from, change.to, coverage).also { addTree(change.to, coverage) }
                        is FileChange.Removed -> deleteRows(change.path)
                        is FileChange.Parked -> parkRows(change.from, change.to)
                        is FileChange.Added -> addTree(change.path, coverage).let { false }
                    } || vectorsChanged
                }
            }
        }
        if (vectorsChanged) VectorIndex.invalidate()
        fileDao.getPendingFiles(limit = 1).isNotEmpty()
    }

    /** Which source would own a path, using the same rules as the crawler. */
    private class Coverage(val sources: List<SourceEntity>, val volumeRoots: List<String>, val kinds: Set<com.navaneeth.filer.data.storage.IndexKind>) {
        fun ownerOf(file: File): Long? {
            val path = file.path
            if (file.name.startsWith(".")) return null
            for (source in sources) {
                when (source.type) {
                    SourceType.LOCAL_FOLDER -> if (isInside(path, source.uri) && visibleBelow(source.uri, path)) return source.id
                    SourceType.DEVICE -> {
                        val root = volumeRoots.firstOrNull { isInside(path, it) } ?: continue
                        if (path.startsWith("$root/Android/")) continue
                        val size = file.length()
                        if (visibleBelow(root, path) && IndexRules.isIndexable(file.name, Storage.mimeTypeOf(file.name), size, kinds)) return source.id
                    }
                }
            }
            return null
        }

        private fun isInside(path: String, root: String) = path.startsWith("$root/")

        /** No hidden, cache or build folder between [root] and the file (the crawler never enters those). */
        private fun visibleBelow(root: String, path: String): Boolean =
            path.removePrefix("$root/").split('/').dropLast(1).none { IndexRules.isSkippedDirectory(File(it)) }
    }

    private suspend fun moveSources(from: String, to: String) {
        sourceDao.getActiveSources()
            .filter { it.type == SourceType.LOCAL_FOLDER && (it.uri == from || it.uri.startsWith("$from/")) }
            .forEach { source ->
                val newPath = to + source.uri.removePrefix(from)
                sourceDao.updateSource(
                    source.copy(uri = newPath, displayName = if (source.uri == from) File(to).name else source.displayName)
                )
            }
    }

    private suspend fun rowsAtOrUnder(path: String): List<FileEntity> {
        val file = File(path)
        val uri = Uri.fromFile(file).toString()
        if (!file.isDirectory) {
            val single = fileDao.getFileByUri(uri)
            return if (single != null) listOf(single) else emptyList()
        }
        return fileDao.getFilesAtOrUnder(uri, "$uri/")
    }

    /** Re-points rows to their new path; returns true if any search data was dropped. */
    private suspend fun moveRows(from: String, to: String, coverage: Coverage): Boolean {
        var dropped = false
        for (row in rowsAtOrUnder(from)) {
            val oldPath = Uri.parse(row.uri).path ?: continue
            val file = File(to + oldPath.removePrefix(from))
            val owner = coverage.ownerOf(file)
            val newUri = Uri.fromFile(file).toString()
            if (owner == null) {
                // Moved out of smart search.
                fileDao.pruneFile(row.id)
                dropped = true
                continue
            }
            fileDao.getFileByUri(newUri)?.takeIf { it.id != row.id }?.let { fileDao.pruneFile(it.id); dropped = true }
            val mime = Storage.mimeTypeOf(file.name)
            // Back from the Recycle bin: only indexed files were kept there.
            val status = if (row.status == IndexStatus.GONE) IndexStatus.INDEXED else row.status
            val moved = row.copy(uri = newUri, displayName = file.name, sourceId = owner, sizeBytes = file.length(), modifiedAt = file.lastModified(), status = status)
            if (mime == row.mimeType) {
                fileDao.updateFile(moved)
            } else {
                // New extension: what's readable (and how) may differ, so read it again.
                embeddingDao.deleteForFiles(listOf(row.id))
                chunkDao.deleteChunksForFile(row.id)
                val status = if (IndexRules.isIndexable(file.name, mime, file.length(), coverage.kinds)) IndexStatus.PENDING else IndexStatus.SKIPPED
                fileDao.updateFile(moved.copy(mimeType = mime, status = status, indexedAt = null, contentHash = null, errorMessage = null))
                dropped = true
            }
        }
        return dropped
    }

    /** Gives each copy the original's passages and vectors; returns true if vectors were added. */
    private suspend fun cloneRows(from: String, to: String, coverage: Coverage): Boolean {
        var added = false
        for (row in rowsAtOrUnder(from)) {
            if (row.status != IndexStatus.INDEXED) continue
            val oldPath = Uri.parse(row.uri).path ?: continue
            val file = File(to + oldPath.removePrefix(from))
            val owner = coverage.ownerOf(file) ?: continue
            val newUri = Uri.fromFile(file).toString()
            if (fileDao.getFileByUri(newUri) != null) continue
            val newId = fileDao.insertFile(
                row.copy(id = 0, uri = newUri, displayName = file.name, sourceId = owner, sizeBytes = file.length(), modifiedAt = file.lastModified())
            )
            if (newId <= 0) continue
            val chunks = chunkDao.getChunksForFile(row.id)
            val newChunkIds = chunkDao.insertChunks(chunks.map { it.copy(id = 0, fileId = newId) })
            val chunkMap = chunks.map { it.id }.zip(newChunkIds).toMap()
            val vectors = embeddingDao.forFile(row.id).map { it.copy(id = 0, fileId = newId, chunkId = it.chunkId?.let(chunkMap::get)) }
            if (vectors.isNotEmpty()) {
                embeddingDao.insertAll(vectors)
                added = true
            }
        }
        return added
    }

    /** Keeps indexed rows (hidden as [IndexStatus.GONE]) under their bin path; drops the rest. */
    private suspend fun parkRows(from: String, to: String): Boolean {
        var dropped = false
        for (row in rowsAtOrUnder(from)) {
            val oldPath = Uri.parse(row.uri).path ?: continue
            if (row.status != IndexStatus.INDEXED) {
                fileDao.pruneFile(row.id)
                dropped = true
                continue
            }
            val newUri = Uri.fromFile(File(to + oldPath.removePrefix(from))).toString()
            fileDao.getFileByUri(newUri)?.takeIf { it.id != row.id }?.let { fileDao.pruneFile(it.id) }
            fileDao.updateFile(row.copy(uri = newUri, status = IndexStatus.GONE))
        }
        return dropped
    }

    private suspend fun deleteRows(path: String): Boolean {
        val ids = rowsAtOrUnder(path).map { it.id }
        ids.chunked(DISCOVERY_BATCH).forEach { fileDao.deleteFilesByIds(it) }
        return ids.isNotEmpty()
    }

    /** Records files at or under [path] that smart search covers but the index doesn't know yet. */
    private suspend fun addTree(path: String, coverage: Coverage) {
        val root = File(path)
        val files = if (root.isDirectory) {
            root.walkTopDown().onEnter { it == root || !IndexRules.isSkippedDirectory(it) }.filter { it.isFile }
        } else {
            sequenceOf(root).filter { it.isFile }
        }
        val inserts = ArrayList<FileEntity>()
        for (file in files) {
            val owner = coverage.ownerOf(file) ?: continue
            val uri = Uri.fromFile(file).toString()
            if (fileDao.getIdByUri(uri) != null) continue
            val mime = Storage.mimeTypeOf(file.name)
            val size = file.length()
            inserts += FileEntity(
                sourceId = owner,
                uri = uri,
                displayName = file.name,
                mimeType = mime,
                sizeBytes = size,
                modifiedAt = file.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis(),
                status = if (IndexRules.isIndexable(file.name, mime, size, coverage.kinds)) IndexStatus.PENDING else IndexStatus.SKIPPED
            )
        }
        inserts.chunked(DISCOVERY_BATCH).forEach { fileDao.insertFiles(it) }
    }

    /**
     * Reconciles the files table with what's on disk, across all sources at once. Each path is one
     * row (unique uri) owned by the first source that covers it: folders before "Whole phone".
     * Unchanged files keep their passages and vectors; nothing is ever inserted twice.
     */
    private suspend fun discoverAll(excludeSourceId: Long? = null) {
        val sources = sourceDao.getActiveSources()
            .filter { it.id != excludeSourceId }
            .sortedWith(compareBy<SourceEntity> { it.type == SourceType.DEVICE }.thenBy { it.id })
        val kinds = prefs.indexedKinds
        val wanted = LinkedHashMap<String, DiscoveredFile>()
        for (source in sources) {
            val found = when (source.type) {
                SourceType.LOCAL_FOLDER -> crawler.crawl(source.id, File(source.uri))
                SourceType.DEVICE -> crawler.crawlVolumes(source.id, Storage.volumes(context).map { it.root }, kinds)
            }
            for (item in found) wanted.putIfAbsent(item.uriString, item)
        }

        // One query for everything we know, instead of one per file.
        val known = fileDao.getAllFilesOnce().associateBy { it.uri }
        val activeModel = embedder.activeModelVersion
        // Files embedded with stand-in vectors (the model couldn't run) get another go once it can.
        val withFallbackVectors = if (embedder.isModelLoaded()) embeddingDao.fileIdsWithEngine(EngineTag.FALLBACK).toHashSet() else emptySet()
        val inserts = ArrayList<FileEntity>()
        val updates = ArrayList<FileEntity>()
        // Indexed files whose kind was turned off: their passages and vectors are deleted.
        val stripped = ArrayList<Long>()

        for (item in wanted.values) {
            val existing = known[item.uriString]
            val initialStatus = if (IndexRules.isIndexable(item.displayName, item.mimeType, item.sizeBytes, kinds)) IndexStatus.PENDING else IndexStatus.SKIPPED
            if (existing == null) {
                inserts += FileEntity(
                    sourceId = item.sourceId,
                    uri = item.uriString,
                    displayName = item.displayName,
                    mimeType = item.mimeType,
                    sizeBytes = item.sizeBytes,
                    modifiedAt = item.modifiedAt,
                    status = initialStatus
                )
                continue
            }
            val changedOnDisk = existing.modifiedAt != item.modifiedAt || existing.sizeBytes != item.sizeBytes
            val updated = when {
                existing.status == IndexStatus.INDEXED && initialStatus == IndexStatus.SKIPPED -> {
                    stripped += existing.id
                    existing.copy(sizeBytes = item.sizeBytes, modifiedAt = item.modifiedAt, status = IndexStatus.SKIPPED, indexedAt = null, contentHash = null)
                }
                existing.status == IndexStatus.INDEXED && changedOnDisk -> {
                    // Touched but maybe not edited: re-embed only if the content or model changed.
                    val sameContent = existing.sizeBytes == item.sizeBytes &&
                        existing.contentHash != null &&
                        existing.contentHash == ContentHasher.computeFastHash(File(Uri.parse(item.uriString).path!!), item.sizeBytes) &&
                        existing.modelVersion == activeModel
                    if (sameContent) existing.copy(modifiedAt = item.modifiedAt)
                    else existing.copy(sizeBytes = item.sizeBytes, modifiedAt = item.modifiedAt, status = initialStatus, errorMessage = null)
                }
                existing.status == IndexStatus.INDEXED && existing.id in withFallbackVectors -> existing.copy(status = IndexStatus.PENDING)
                existing.status == IndexStatus.INDEXED -> existing
                // Password-protected or unreadable: same result until the file itself changes.
                !changedOnDisk && existing.errorMessage in PERMANENT_PROBLEMS -> existing
                // Waiting, failed or skipped: retry with the current rules.
                else -> existing.copy(sizeBytes = item.sizeBytes, modifiedAt = item.modifiedAt, status = initialStatus, errorMessage = null)
            }.copy(sourceId = item.sourceId)
            if (updated != existing) updates += updated
        }

        // Files deleted, moved away, or no longer covered by any source: drop them and their search data.
        // Files waiting in the Recycle bin keep theirs until they're restored or purged.
        val gone = known.values
            .filter { it.uri !in wanted && !(it.status == IndexStatus.GONE && File(Uri.parse(it.uri).path.orEmpty()).exists()) }
            .map { it.id }

        inserts.chunked(DISCOVERY_BATCH).forEach { batch -> db.withTransaction { fileDao.insertFiles(batch) } }
        updates.chunked(DISCOVERY_BATCH).forEach { batch -> db.withTransaction { fileDao.updateFiles(batch) } }
        stripped.chunked(DISCOVERY_BATCH).forEach { batch ->
            db.withTransaction {
                embeddingDao.deleteForFiles(batch)
                chunkDao.deleteChunksForFiles(batch)
            }
        }
        gone.chunked(DISCOVERY_BATCH).forEach { batch -> db.withTransaction { fileDao.deleteFilesByIds(batch) } }
        if (gone.isNotEmpty() || stripped.isNotEmpty()) VectorIndex.invalidate()
        if (stripped.isNotEmpty()) compactDatabase()

        // Pass 1: Catalog EXIF places & dates in background without requiring neural model
        com.navaneeth.filer.data.places.PlaceScanWorker.enqueue(context)
    }

    /**
     * Runs one indexing pass. With [reindexAll], every vector is dropped first and rebuilt (then
     * [onReset] runs). Passes are serialised by [passLock], so a rebuild never overlaps a pass.
     */
    suspend fun runFullIndex(
        reindexAll: Boolean = false,
        isUserRequested: Boolean = false,
        onReset: () -> Unit = {},
        onProgress: ((IndexProgress) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        passLock.withLock { runPass(reindexAll, isUserRequested, onReset, onProgress) }
    }

    private suspend fun runPass(
        reindexAll: Boolean,
        isUserRequested: Boolean,
        onReset: () -> Unit,
        onProgress: ((IndexProgress) -> Unit)?
    ): Unit = coroutineScope {
        isCancelled.set(false)
        if (reindexAll) {
            resetForReindex()
            onReset()
        }
        // Safety net: a file's passages and vectors are always replaced, never added to.
        val removed = chunkDao.deleteOrphans() + embeddingDao.deleteDuplicatesAndOrphans()
        if (removed > 0) VectorIndex.invalidate()
        isPaused.set(prefs.isIndexingPausedByUser)

        val runId = telemetryDao.insertIndexRun(IndexRunEntity(startedAt = System.currentTimeMillis()))
        telemetryDao.logActivity(
            ActivityLogEntity(
                action = "SCAN",
                details = "Started incremental scan across active sources"
            )
        )

        // Steps 1–2: discover files and record them (no model work)
        discoveryLock.withLock { discoverAll() }

        // Step 3: Process pending files in small, cooperative batches
        val total = fileDao.getPendingFilesCount()
        var processed = 0
        val startTime = System.currentTimeMillis()
        var pausedMs = 0L
        val batchSize = 15
        var batchHasChanges = false
        var embeddedItems = 0
        var photosEmbeddedOnBattery = 0
        val profilingTracker = IndexProfilingTracker()

        if (total == 0) {
            syncPluginText()
            // Nothing new to embed, but the Storage tab's kind words may still be missing.
            prepareContentLabels()
            _globalProgress.value = IndexProgress(isRunning = false, isPaused = false, processedCount = 0, totalCount = 0)
            telemetryDao.finishIndexRun(id = runId, finishedAt = System.currentTimeMillis(), processed = 0, status = "COMPLETED")
            if (reindexAll) compactDatabase()
            _passFinished.tryEmit(Unit)
            return@coroutineScope
        }

        _globalProgress.value = IndexProgress(
            isRunning = true,
            isPaused = isPaused.get() || prefs.isIndexingPausedByUser,
            totalCount = total
        )

        // Why the pass ended early, if it did (telemetry only).
        var endReason: String? = null
        // Files left waiting for a later pass: the model gave no vector, or the Mac kept failing on them.
        val leftForLater = HashSet<Long>()
        // Mac failures in a row for one file.
        var macAttemptsFileId = -1L
        var macAttempts = 0
        // Time spent waiting for the Mac since it last finished a file (paused time not counted).
        var macWaitedMs = 0L
        // Whether the Mac has run the model in this pass; if not, a missing Mac means "use the phone".
        var macUsedThisPass = false

        /**
         * Developer mode: the Mac stopped answering. Waits for it without holding the model or
         * spinning, asking every [IndexingPolicy.MAC_RECHECK_MS]. Null when paused or stopped meanwhile.
         */
        suspend fun waitForMac(): MacWaitStep? {
            val waiting = IndexProgress(isRunning = true, processedCount = processed, totalCount = total, waitingForMac = true)
            _globalProgress.value = waiting
            onProgress?.invoke(waiting)
            var last = SystemClock.elapsedRealtime()
            var lastCheck = last
            while (true) {
                if (isCancelled.get() || isPaused.get() || prefs.isIndexingPausedByUser) return null
                delay(500L)
                val now = SystemClock.elapsedRealtime()
                macWaitedMs += now - last
                last = now
                val reachable = if (now - lastCheck >= IndexingPolicy.MAC_RECHECK_MS) {
                    RemoteEmbedder.available(force = true) != null
                } else {
                    false
                }
                if (now - lastCheck >= IndexingPolicy.MAC_RECHECK_MS) lastCheck = SystemClock.elapsedRealtime()
                val step = IndexingPolicy.macWaitStep(prefs.wantsMacIndexing, reachable, macWaitedMs)
                if (step != MacWaitStep.KEEP_WAITING) return step
            }
        }

        outer@ while (!isCancelled.get()) {
            val pauseStart = System.currentTimeMillis()
            while ((isPaused.get() || prefs.isIndexingPausedByUser) && !isCancelled.get()) {
                val pausedProg = _globalProgress.value.copy(isPaused = true)
                _globalProgress.value = pausedProg
                onProgress?.invoke(pausedProg)
                delay(300)
            }
            pausedMs += System.currentTimeMillis() - pauseStart
            if (isCancelled.get()) break

            val batch = if (leftForLater.isEmpty()) {
                fileDao.getPendingFiles(limit = batchSize)
            } else {
                fileDao.getPendingFilesExcluding(leftForLater.toList(), limit = batchSize)
            }
            if (batch.isEmpty()) break

            // Decoding a photo is slow I/O; overlap the next one's decode with the current model run.
            var prefetch: Pair<Long, Deferred<Bitmap?>>? = null

            var index = 0
            while (index < batch.size) {
                val file = batch[index]
                if (isCancelled.get() || isPaused.get() || prefs.isIndexingPausedByUser) break

                if (!IndexRules.isIndexable(file.displayName, file.mimeType, file.sizeBytes, prefs.indexedKinds)) {
                    fileDao.updateFileStatus(file.id, IndexStatus.SKIPPED)
                    processed++
                    index++
                    continue
                }

                // Only real vectors are stored: without the phone's model (also needed for every
                // search) nothing is embedded, and the files stay waiting for a later pass.
                if (!embedder.isModelLoaded()) {
                    endReason = "NO_MODEL"
                    break@outer
                }

                // Developer mode: with "Index with Mac" on, the Mac runs the model whenever it answers.
                // A pass that never reached the Mac (unplugged; everyday new files) runs on the phone.
                // Only when the Mac drops out mid-pass does the pass wait for it, rather than quietly
                // moving a big run onto the phone.
                var useMac = false
                if (prefs.wantsMacIndexing) {
                    if (RemoteEmbedder.available() != null) {
                        useMac = true
                        macUsedThisPass = true
                    } else if (macUsedThisPass) {
                        when (waitForMac()) {
                            MacWaitStep.USE_MAC -> useMac = true
                            MacWaitStep.USE_PHONE -> macWaitedMs = 0L
                            MacWaitStep.GIVE_UP -> {
                                endReason = "MAC_UNAVAILABLE"
                                break@outer
                            }
                            // Paused or stopped while waiting: the outer loop handles it.
                            else -> break
                        }
                    }
                }

                // Pass 2 budget: on battery without Mac, process up to MAX_BATTERY_PHOTOS_PER_PASS
                // for automatic background passes so the phone doesn't heat up or drain battery.
                // If explicitly requested by user ("Index now"), allow indexing all files with thermal pacing.
                val isPhoto = isImage(file)
                val onBatteryPhone = !useMac && !thermal.isCharging()
                if (isPhoto && onBatteryPhone && !isUserRequested && photosEmbeddedOnBattery >= MAX_BATTERY_PHOTOS_PER_PASS) {
                    Log.i("FilerProfiler", "[3-Pass] Pass 2 session limit reached ($photosEmbeddedOnBattery photos on battery). Deferring photo to Pass 3 (charging).")
                    leftForLater += file.id
                    index++
                    continue
                }

                // Time spent paused doesn't count; a few files in, the rate is steady enough to estimate.
                val elapsedSec = ((System.currentTimeMillis() - startTime - pausedMs) / 1000f).coerceAtLeast(0.1f)
                val throughput = if (processed > 0) processed / elapsedSec else 0f
                val remaining = (total - processed).coerceAtLeast(0)
                val etaSec = if (processed >= MIN_FILES_FOR_ESTIMATE && throughput > 0f) (remaining / throughput).toInt() else 0
                val isThrottled = thermal.sampleMetrics().isThrottled

                val curProgress = IndexProgress(
                    isRunning = true,
                    isPaused = false,
                    isThermalThrottled = isThrottled,
                    currentFileName = file.displayName,
                    processedCount = processed,
                    totalCount = total,
                    throughputFilesPerSec = throughput,
                    etaSeconds = etaSec,
                    viaMac = useMac,
                    itemsPerSec = embeddedItems / elapsedSec,
                    profiling = profilingTracker.snapshot()
                )
                _globalProgress.value = curProgress
                onProgress?.invoke(curProgress)

                // Null when the file was settled another way (unreadable or failed).
                var outcome: FileVectors? = null
                try {
                    val tPrepStart = SystemClock.elapsedRealtime()
                    val decoded = if (isImage(file)) {
                        prefetch?.takeIf { it.first == file.id }?.second?.await()
                            ?: embedder.decodeForEmbedding(Uri.parse(file.uri))
                    } else {
                        null
                    }
                    val prepMs = if (isImage(file)) SystemClock.elapsedRealtime() - tPrepStart else 0L

                    val next = batch.getOrNull(index + 1)?.takeIf(::isImage)
                    if (prefetch?.first != next?.id) {
                        prefetch?.second?.cancel()
                        prefetch = next?.let { it.id to async { embedder.decodeForEmbedding(Uri.parse(it.uri)) } }
                    }

                    val (fileOutcome, profile) = processSingleFile(file, decoded, prepMs, useMac)
                    outcome = fileOutcome
                    if (outcome is FileVectors.Ready) {
                        embeddedItems += outcome.embeddings.size
                        if (isPhoto && onBatteryPhone) {
                            photosEmbeddedOnBattery++
                        }
                        profile?.let { prof ->
                            profilingTracker.record(prof)
                            val tag = if (prof.isImage) "Photo" else "Doc"
                            Log.i("FilerProfiler", "[$tag] total=${prof.totalMs}ms | prep=${prof.prepMs}ms | thermal=${prof.thermalMs}ms | model=${prof.inferMs}ms | db=${prof.dbMs}ms")
                        }
                        // Re-embedding replaced all its passages: the plugins' text goes back in below.
                        Uri.parse(file.uri).path?.let { pluginDao.markUnindexed(it) }
                        batchHasChanges = true
                        if (useMac) macWaitedMs = 0L
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: UnreadableFileException) {
                    // Not retried until the file changes; drop anything left from an older readable version.
                    val status = if (e.reason == UnreadableFileException.PROTECTED) IndexStatus.SKIPPED else IndexStatus.FAILED
                    db.withTransaction {
                        embeddingDao.deleteForFile(file.id)
                        chunkDao.deleteChunksForFile(file.id)
                        fileDao.updateFileStatus(file.id, status, error = e.reason)
                    }
                    batchHasChanges = true
                } catch (t: Throwable) {
                    fileDao.updateFileStatus(file.id, IndexStatus.FAILED, error = t.localizedMessage)
                    telemetryDao.logActivity(
                        ActivityLogEntity(
                            action = "ERROR",
                            fileUri = file.uri,
                            fileName = file.displayName,
                            details = t.localizedMessage ?: "Failed during extraction/embedding",
                            isError = true
                        )
                    )
                }

                // Nothing was written for these: the file keeps its old state (waiting) and is retried.
                when (outcome) {
                    FileVectors.MacFailed -> {
                        if (file.id == macAttemptsFileId) macAttempts++ else { macAttemptsFileId = file.id; macAttempts = 1 }
                        // Same file again (after waiting for the Mac if it's gone), unless the Mac keeps
                        // answering yet failing on this one file: then it's left for a later pass.
                        if (macAttempts >= IndexingPolicy.MAX_MAC_ATTEMPTS_PER_FILE) {
                            leftForLater += file.id
                            index++
                        }
                        continue
                    }
                    FileVectors.ModelUnavailable -> {
                        endReason = "NO_MODEL"
                        break@outer
                    }
                    FileVectors.NoVector -> {
                        leftForLater += file.id
                        index++
                        continue
                    }
                    else -> Unit
                }
                processed++
                index++
                // Brief cooperative yield between files to keep UI responsive (the phone's model
                // isn't running when the Mac is, so there's nothing to cool down between files).
                yield()
                if (!useMac) delay(10L)
            }
            prefetch?.second?.cancel()

            // Invalidate in-memory vector index once per batch
            if (batchHasChanges) {
                VectorIndex.invalidate()
                batchHasChanges = false
            }

            // Yield and small pacing rest between batches
            yield()
            if (!_globalProgress.value.viaMac) delay(100L)
        }
        if (batchHasChanges) VectorIndex.invalidate()
        if (endReason == null && !isCancelled.get()) syncPluginText()
        if (endReason == null && !isCancelled.get()) prepareContentLabels()

        telemetryDao.finishIndexRun(
            id = runId,
            finishedAt = System.currentTimeMillis(),
            processed = processed,
            status = endReason ?: if (isCancelled.get()) "CANCELLED" else "COMPLETED"
        )

        _globalProgress.value = IndexProgress(
            isRunning = false,
            isPaused = false,
            processedCount = processed,
            totalCount = total,
            profiling = profilingTracker.snapshot()
        )
        // A rebuild rewrites every vector; give the freed pages back so the index doesn't look doubled.
        if (reindexAll) compactDatabase()
        if (endReason == null && !isCancelled.get()) _passFinished.tryEmit(Unit)

        // Pass 3: If pending files remain (e.g. archive photos deferred on battery), queue background charging pass
        if (!isCancelled.get() && fileDao.getPendingFiles(limit = 1).isNotEmpty()) {
            com.navaneeth.filer.data.worker.IndexingWorker.enqueue(context, userRequested = false, requireCharging = true)
        }
    }

    /**
     * Adds text the AI plugins read (text in photos, transcripts) to the index as passages of their
     * files, for files smart search covers and has indexed. Only those passages are embedded; a
     * photo's own vector stays. Runs at the end of a pass, so it's paced like the rest.
     */
    /**
     * Embeds the Storage tab's content words (photos of documents, greetings, IDs…) once, so its
     * insights can sort indexed files into kinds without running the model themselves.
     */
    private suspend fun prepareContentLabels() {
        try {
            com.navaneeth.filer.data.ai.TagSuggester(context)
                .prepareLabels(com.navaneeth.filer.data.analysis.ContentKind.allLabels) { thermal.awaitCapacity() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Content labels not prepared: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun syncPluginText() {
        if (!embedder.isModelLoaded()) return
        var changed = false
        while (!isCancelled.get()) {
            val rows = pluginDao.textToIndex(limit = 20)
            if (rows.isEmpty()) break
            for (row in rows) {
                if (isCancelled.get()) break
                val entity = fileDao.getFileByUri(Uri.fromFile(File(row.path)).toString())
                val page = com.navaneeth.filer.data.ai.PluginIndex.pageFor(row.source)
                if (entity == null || entity.status != IndexStatus.INDEXED || entity.modifiedAt != row.modifiedAt) {
                    // Not in smart search (or not indexed yet): nothing to add; checked again when it is.
                    pluginDao.markIndexed(row.id, row.createdAt)
                    continue
                }
                val chunks = TextChunker().chunk(row.text).map { it.copy(pageNumber = page) }
                val toEmbed = chunks.withIndex().filter { it.value.embed }
                val result = embedder.embedDocuments(
                    texts = toEmbed.map { it.value.text },
                    useMac = false,
                    beforeEachOnPhone = { thermal.awaitCapacity() }
                )
                if (result !is FileVectors.Ready || !IndexingPolicy.canSave(expected = toEmbed.size, result.embeddings)) return
                val byChunk = toEmbed.map { it.index }.zip(result.embeddings).toMap()
                db.withTransaction {
                    chunkDao.deletePluginVectors(entity.id, page)
                    chunkDao.deletePluginChunks(entity.id, page)
                    val ids = chunkDao.insertChunks(
                        chunks.map {
                            ChunkEntity(
                                fileId = entity.id,
                                pageNumber = page,
                                charStart = it.charStart,
                                charEnd = it.charEnd,
                                textSnippet = it.text,
                                embedded = it.embed
                            )
                        }
                    )
                    embeddingDao.insertAll(
                        byChunk.map { (i, embedding) ->
                            EmbeddingEntity(
                                fileId = entity.id,
                                chunkId = ids[i],
                                kind = EmbeddingKind.TEXT.ordinal,
                                vector = VectorIndex.encode(embedding.vector),
                                engine = embedding.engine
                            )
                        }
                    )
                }
                pluginDao.markIndexed(row.id, row.createdAt)
                changed = true
            }
        }
        if (changed) VectorIndex.invalidate()
    }

    /** A plugin was removed: its passages and their vectors leave the index. */
    suspend fun removePluginText(source: String) = withContext(Dispatchers.IO) {
        val page = com.navaneeth.filer.data.ai.PluginIndex.pageFor(source)
        db.withTransaction {
            chunkDao.deleteAllPluginVectors(page)
            chunkDao.deleteAllPluginChunks(page)
        }
        VectorIndex.invalidate()
    }

    private fun isImage(file: FileEntity): Boolean {
        val ext = file.displayName.substringAfterLast('.', "").lowercase()
        return file.mimeType.startsWith("image/") || ext in listOf("jpg", "jpeg", "png", "webp", "gif", "heic")
    }

    /**
     * Reads and embeds one file, then replaces its passages and vectors in a single transaction:
     * a file is either fully indexed or untouched, even if the process dies midway.
     * Model runs happen before the transaction so the database is never locked during inference.
     * Writes only when every vector is real and from one engine ([IndexingPolicy.canSave]);
     * any other result leaves the file exactly as it was.
     */
    private suspend fun processSingleFile(
        file: FileEntity,
        decodedImage: Bitmap?,
        prepMs: Long,
        useMac: Boolean
    ): Pair<FileVectors, FileProfile?> {
        val uri = Uri.parse(file.uri)
        if (isImage(file)) {
            if (decodedImage == null) {
                // With the model ready, a photo that can't be decoded would get no vector; record it
                // as unreadable (retried when the file changes) instead.
                if (embedder.isModelLoaded()) throw UnreadableFileException(UnreadableFileException.UNREADABLE)
                return FileVectors.ModelUnavailable to null
            }
            val tThermStart = SystemClock.elapsedRealtime()
            thermal.awaitCapacity(routineRests = !useMac)
            val thermalMs = SystemClock.elapsedRealtime() - tThermStart

            val tInferStart = SystemClock.elapsedRealtime()
            val result = embedder.embedPhoto(decodedImage, useMac)
            val inferMs = SystemClock.elapsedRealtime() - tInferStart

            if (result !is FileVectors.Ready) return result to null
            if (!IndexingPolicy.canSave(expected = 1, result.embeddings)) return FileVectors.NoVector to null
            val embedding = result.embeddings.single()

            val tDbStart = SystemClock.elapsedRealtime()
            val contentHash = uri.path?.let { ContentHasher.computeFastHash(File(it), file.sizeBytes).ifEmpty { null } }
            db.withTransaction {
                chunkDao.deleteChunksForFile(file.id)
                embeddingDao.deleteForFile(file.id)
                embeddingDao.insertAll(
                    listOf(
                        EmbeddingEntity(
                            fileId = file.id,
                            chunkId = null,
                            kind = EmbeddingKind.IMAGE.ordinal,
                            vector = VectorIndex.encode(embedding.vector),
                            engine = embedding.engine
                        )
                    )
                )
                fileDao.updateFileStatus(
                    fileId = file.id,
                    status = IndexStatus.INDEXED,
                    timestamp = System.currentTimeMillis(),
                    modelVersion = embedder.activeModelVersion,
                    contentHash = contentHash
                )
            }
            val dbMs = SystemClock.elapsedRealtime() - tDbStart
            val totalMs = prepMs + thermalMs + inferMs + dbMs
            val profile = FileProfile(
                fileName = file.displayName,
                isImage = true,
                totalMs = totalMs,
                prepMs = prepMs,
                thermalMs = thermalMs,
                inferMs = inferMs,
                dbMs = dbMs
            )
            return result to profile
        } else {
            val ext = file.displayName.substringAfterLast('.', "").lowercase()
            val tExtractStart = SystemClock.elapsedRealtime()
            val chunks = when (ext) {
                "pdf" -> pdfExtractor.extract(uri)
                in OfficeTextExtractor.EXTENSIONS -> officeExtractor.extract(uri, ext)
                else -> plainTextExtractor.extract(uri, file.sizeBytes)
            }
            val extractMs = SystemClock.elapsedRealtime() - tExtractStart

            // All of a file's passages in one go (one request when the Mac runs the model).
            val toEmbed = chunks.withIndex().filter { it.value.embed }
            var thermalMs = 0L
            val tThermStart = SystemClock.elapsedRealtime()
            if (toEmbed.isNotEmpty()) {
                thermal.awaitCapacity(routineRests = !useMac)
                thermalMs += SystemClock.elapsedRealtime() - tThermStart
            }
            var chunkIdx = 0
            var chunkThermalMs = 0L
            val tInferStart = SystemClock.elapsedRealtime()
            val result = embedder.embedDocuments(
                texts = toEmbed.map { it.value.text },
                useMac = useMac,
                beforeEachOnPhone = {
                    val t0 = SystemClock.elapsedRealtime()
                    if (++chunkIdx > 1 && chunkIdx % 4 == 0) thermal.awaitCapacity(routineRests = !useMac)
                    chunkThermalMs += SystemClock.elapsedRealtime() - t0
                }
            )
            val inferBlockElapsed = SystemClock.elapsedRealtime() - tInferStart
            val inferMs = (inferBlockElapsed - chunkThermalMs).coerceAtLeast(0L)
            thermalMs += chunkThermalMs

            if (result !is FileVectors.Ready) return result to null
            if (!IndexingPolicy.canSave(expected = toEmbed.size, result.embeddings)) return FileVectors.NoVector to null
            val byChunk = toEmbed.map { it.index }.zip(result.embeddings).toMap()

            val tDbStart = SystemClock.elapsedRealtime()
            val contentHash = uri.path?.let { ContentHasher.computeFastHash(File(it), file.sizeBytes).ifEmpty { null } }
            db.withTransaction {
                chunkDao.deleteChunksForFile(file.id)
                embeddingDao.deleteForFile(file.id)
                val ids = chunkDao.insertChunks(
                    chunks.map {
                        ChunkEntity(
                            fileId = file.id,
                            pageNumber = it.pageNumber,
                            charStart = it.charStart,
                            charEnd = it.charEnd,
                            textSnippet = it.text,
                            embedded = it.embed
                        )
                    }
                )
                embeddingDao.insertAll(
                    byChunk.map { (i, embedding) ->
                        EmbeddingEntity(
                            fileId = file.id,
                            chunkId = ids[i],
                            kind = EmbeddingKind.TEXT.ordinal,
                            vector = VectorIndex.encode(embedding.vector),
                            engine = embedding.engine
                        )
                    }
                )
                fileDao.updateFileStatus(
                    fileId = file.id,
                    status = IndexStatus.INDEXED,
                    timestamp = System.currentTimeMillis(),
                    modelVersion = embedder.activeModelVersion,
                    contentHash = contentHash
                )
            }
            val dbMs = SystemClock.elapsedRealtime() - tDbStart
            val totalMs = extractMs + thermalMs + inferMs + dbMs
            val profile = FileProfile(
                fileName = file.displayName,
                isImage = false,
                totalMs = totalMs,
                prepMs = extractMs,
                thermalMs = thermalMs,
                inferMs = inferMs,
                dbMs = dbMs
            )
            return result to profile
        }
    }

    fun pause() {
        prefs.isIndexingPausedByUser = true
        isPaused.set(true)
        _globalProgress.value = _globalProgress.value.copy(isPaused = true)
    }

    fun resume() {
        prefs.isIndexingPausedByUser = false
        isPaused.set(false)
        _globalProgress.value = _globalProgress.value.copy(isPaused = false)
    }

    fun cancel() {
        prefs.isIndexingPausedByUser = false
        isCancelled.set(true)
        _globalProgress.value = _globalProgress.value.copy(isRunning = false, isPaused = false)
    }

    suspend fun hasPendingFiles(): Boolean = withContext(Dispatchers.IO) {
        fileDao.getPendingFilesCount() > 0
    }

    suspend fun pruneFile(fileId: Long) = withContext(Dispatchers.IO) {
        fileDao.pruneFile(fileId)
        VectorIndex.invalidate()
        telemetryDao.logActivity(
            ActivityLogEntity(
                action = "PRUNE",
                details = "Pruned file ID $fileId from index"
            )
        )
    }

    /** Drops every vector and marks all files for re-embedding; the rest of the pass rebuilds them. */
    private suspend fun resetForReindex() {
        db.withTransaction {
            embeddingDao.deleteAll()
            chunkDao.deleteAllChunks()
            fileDao.resetAllToPending()
        }
        VectorIndex.invalidate()
        compactDatabase()
        com.navaneeth.filer.data.preferences.PreferencesManager(context).vectorIndexVersion =
            com.navaneeth.filer.data.preferences.PreferencesManager.CURRENT_INDEX_VERSION
    }

    suspend fun clearEntireIndex() = withContext(Dispatchers.IO) {
        cancel()
        com.navaneeth.filer.data.worker.IndexingWorker.cancel(context)
        passLock.withLock {
            discoveryLock.withLock {
                db.withTransaction {
                    embeddingDao.deleteAll()
                    chunkDao.deleteAllChunks()
                    fileDao.deleteAllFiles()
                    val sqlite = db.openHelper.writableDatabase
                    sqlite.execSQL("DELETE FROM chunks_fts")
                    sqlite.execSQL("DELETE FROM files_fts")
                    sqlite.execSQL("DELETE FROM activity_logs")
                    sqlite.execSQL("DELETE FROM index_runs")
                }
            }
            VectorIndex.release()
            VectorIndex.invalidate()
            compactDatabase()
            _globalProgress.value = IndexProgress(isRunning = false, isPaused = false, processedCount = 0, totalCount = 0)
        }
    }

    /**
     * SQLite keeps deleted pages inside the file, so after a rebuild the database would otherwise
     * stay as big as old + new vectors together. VACUUM rewrites it at its real size.
     */
    private fun compactDatabase() {
        runCatching {
            val sqlite = db.openHelper.writableDatabase
            // Checkpoint and truncate the WAL journal to 0 bytes (cursor must be stepped via moveToFirst)
            sqlite.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
            if (!sqlite.inTransaction()) {
                var vacuumed = false
                for (attempt in 1..3) {
                    try {
                        sqlite.execSQL("VACUUM")
                        vacuumed = true
                        break
                    } catch (e: Exception) {
                        Log.d(TAG, "VACUUM attempt $attempt delayed: ${e.message}")
                        Thread.sleep(50L * attempt)
                    }
                }
                if (vacuumed) {
                    sqlite.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
                }
            }
        }.onFailure { Log.w(TAG, "Database compaction failed", it) }
    }

    suspend fun getStorageBreakdown(): StorageBreakdown = withContext(Dispatchers.IO) {
        fun sizeOf(file: File): Long = if (file.isDirectory) file.walkTopDown().filter { it.isFile }.sumOf { it.length() } else file.length()
        // The database with its journal files (the write-ahead log can be large between checkpoints).
        fun database(name: String): Long = context.getDatabasePath(name).let { db ->
            listOf(db, File(db.path + "-wal"), File(db.path + "-shm")).sumOf { if (it.exists()) it.length() else 0L }
        }
        val model = sizeOf(File(context.filesDir, "models"))
        val index = database("filer.db")
        val plugins = sizeOf(File(context.filesDir, "plugins"))
        val locked = sizeOf(File(context.filesDir, FileManager.LOCKED_DIR))
        // The same numbers as Settings › Apps; reading our own app's stats needs no permission.
        val stats = runCatching {
            context.getSystemService(StorageStatsManager::class.java)
                .queryStatsForPackage(StorageManager.UUID_DEFAULT, context.packageName, Process.myUserHandle())
        }.onFailure { Log.w(TAG, "App storage stats unavailable: ${it.javaClass.simpleName}") }.getOrNull()
        val cache = stats?.cacheBytes ?: sizeOf(context.cacheDir)
        val app = stats?.appBytes ?: File(context.applicationInfo.sourceDir).length()
        val measured = model + index + plugins + locked + cache
        // Data includes the cache.
        val data = stats?.dataBytes?.coerceAtLeast(measured) ?: (measured + database("files.db"))
        StorageBreakdown(
            totalBytes = app + data,
            appBytes = app,
            modelBytes = model,
            indexBytes = index,
            pluginBytes = plugins,
            lockedBytes = locked,
            cacheBytes = cache,
            otherBytes = data - measured,
            freeSpaceBytes = context.filesDir.freeSpace
        )
    }
}
