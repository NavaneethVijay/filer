package com.navaneeth.filer.ui.viewmodel

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.navaneeth.filer.R
import com.navaneeth.filer.data.db.FilerDatabase
import com.navaneeth.filer.data.db.entity.ChunkEntity
import com.navaneeth.filer.data.db.entity.FileEntity
import com.navaneeth.filer.data.model.IndexStatus
import com.navaneeth.filer.data.model.StorageBreakdown
import com.navaneeth.filer.data.preferences.PreferencesManager
import com.navaneeth.filer.data.repository.IndexProgress
import com.navaneeth.filer.data.repository.IndexRepository
import com.navaneeth.filer.data.vector.VectorIndex
import com.navaneeth.filer.data.worker.IndexingWorker
import com.navaneeth.filer.ui.model.FileKind
import com.navaneeth.filer.ui.model.FileUiItem
import com.navaneeth.filer.ui.model.toUiItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.net.Uri
import com.navaneeth.filer.data.storage.Storage
import java.io.File

@Immutable
data class IndexCounts(val ready: Int = 0, val waiting: Int = 0, val failed: Int = 0, val skipped: Int = 0) {
    val total: Int get() = ready + waiting + failed + skipped
}

/** A file as the indexing screens show it: the file plus where it is in the pipeline. */
@Immutable
data class IndexedFile(
    val item: FileUiItem,
    val status: IndexStatus,
    val error: String?,
    val indexedAt: Long?
)

@Immutable
data class KindStat(val kind: FileKind, val count: Int, val bytes: Long)

/** Everything the file details screen shows: the file, where it lives, and its place in the index. */
@Immutable
data class FileIndexDetails(
    val item: FileUiItem,
    /** Absolute path of the file's folder, for "Show in folder". */
    val folderPath: String?,
    /** "Internal storage › Documents" for the folder. */
    val folderLabel: String?,
    /** Null when the file isn't in smart search (found by name only). */
    val file: IndexedFile?,
    val passages: List<ChunkEntity>,
    /** Vectors stored for this file (number-heavy passages are keyword-only and have none). */
    val embeddedCount: Int,
    val embeddingDimensions: Int?
)

/**
 * State for the indexing-detail screens. Indexing itself always runs in [IndexingWorker],
 * so this never competes with a background pass.
 */
class IndexViewModel(application: Application) : AndroidViewModel(application) {

    private val indexRepo = IndexRepository.getInstance(application)
    private val db = FilerDatabase.getInstance(application)

    val progress: StateFlow<IndexProgress> = indexRepo.progress
    val resourceUsage: StateFlow<com.navaneeth.filer.data.telemetry.SystemResourceUsage> = indexRepo.resourceUsage

    val counts: StateFlow<IndexCounts> = combine(
        db.fileDao().getCountByStatus(IndexStatus.INDEXED),
        db.fileDao().getCountByStatus(IndexStatus.PENDING),
        db.fileDao().getCountByStatus(IndexStatus.FAILED),
        db.fileDao().getCountByStatus(IndexStatus.SKIPPED)
    ) { ready, waiting, failed, skipped -> IndexCounts(ready, waiting, failed, skipped) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), IndexCounts())

    val files: StateFlow<List<IndexedFile>?> = combine(
        db.fileDao().getAllFiles(),
        db.sourceDao().getAllSources()
    ) { files, sources ->
        val names = sources.associate { source ->
            source.id to source.displayName
        }
        files.map { it.toIndexedFile(names[it.sourceId]) }
    }.distinctUntilChanged().flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val kindStats: StateFlow<List<KindStat>> = files.map { list ->
        list.orEmpty().groupBy { it.item.kind }
            .map { (kind, items) -> KindStat(kind, items.size, items.sumOf { it.item.sizeBytes }) }
            .sortedByDescending { it.count }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** How many vectors each engine made (phone GPU/CPU, Mac, stand-in), for the debug section. */
    val engineCounts: StateFlow<List<com.navaneeth.filer.data.db.dao.EngineCount>> = db.embeddingDao().engineCounts()
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** When the last scan finished, for the status card. */
    val lastScanAt: StateFlow<Long?> = db.telemetryDao().getLatestFinishedRun().map { it?.finishedAt }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** A full rebuild is queued and will start once the phone is charging. */
    val rebuildWaitingForCharger: StateFlow<Boolean> = PreferencesManager(application).fullReindexPendingFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _storage = MutableStateFlow<StorageBreakdown?>(null)
    val storage: StateFlow<StorageBreakdown?> = _storage.asStateFlow()

    init {
        refreshStorage()
        viewModelScope.launch {
            // Bring an index built with an older model up to date, or finish files left waiting.
            val prefs = PreferencesManager(application)
            if (db.sourceDao().getActiveSources().isEmpty()) return@launch
            when {
                prefs.vectorIndexVersion < PreferencesManager.CURRENT_INDEX_VERSION ->
                    IndexingWorker.enqueue(application, reindexAll = true)
                db.fileDao().getPendingFiles(limit = 1).isNotEmpty() ->
                    IndexingWorker.enqueue(application)
            }
        }
    }

    fun scanNow() = IndexingWorker.enqueue(getApplication(), userRequested = true)

    /** Stops any pass in progress so the rebuild starts now rather than after it. */
    fun rebuildIndex() {
        indexRepo.cancel()
        IndexingWorker.cancel(getApplication())
        IndexingWorker.enqueue(getApplication(), reindexAll = true, userRequested = true)
    }

    fun pause() = indexRepo.pause()

    fun resume() = indexRepo.resume()

    fun stop() {
        indexRepo.cancel()
        IndexingWorker.cancel(getApplication())
    }

    fun removeFromIndex(fileId: Long) {
        viewModelScope.launch {
            indexRepo.pruneFile(fileId)
            refreshStorage()
        }
    }

    fun refreshStorage() {
        viewModelScope.launch { _storage.value = indexRepo.getStorageBreakdown() }
    }

    /**
     * Empties the app's cache: mostly the models' compiled GPU/CPU copies, rebuilt (slowly, once) the
     * next time a model loads. Models in use keep working: their open files stay until closed. Only
     * files are deleted, not folders, so a model loading right now can still write its copy.
     */
    fun clearTemporaryFiles() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                getApplication<Application>().cacheDir.walkBottomUp().filter { it.isFile }.forEach { it.delete() }
            }
            refreshStorage()
        }
    }

    /** Details for an indexed file ([fileId]) or any file on the phone ([uri]); null if it's gone. */
    suspend fun loadDetails(fileId: Long?, uri: String?): FileIndexDetails? = withContext(Dispatchers.IO) {
        val entity = fileId?.let { db.fileDao().getFileById(it) } ?: uri?.let { db.fileDao().getFileByUri(it) }
        val path = (entity?.uri ?: uri)?.let { Uri.parse(it).path } ?: return@withContext null
        val onDisk = File(path)
        val folder = onDisk.parentFile?.path
        val folderLabel = folder?.let { Storage.displayPath(getApplication(), it) }
        if (entity == null) {
            if (!onDisk.isFile) return@withContext null
            val item = FileUiItem(
                uri = uri!!,
                name = onDisk.name,
                mimeType = Storage.mimeTypeOf(onDisk.name),
                sizeBytes = onDisk.length(),
                modifiedAt = onDisk.lastModified(),
                location = onDisk.parentFile?.name
            )
            return@withContext FileIndexDetails(item, folder, folderLabel, null, emptyList(), 0, null)
        }
        val passages = db.chunkDao().getChunksForFile(entity.id)
            .sortedWith(compareBy<ChunkEntity>({ it.pageNumber ?: 0 }, { it.charStart }))
        val file = entity.toIndexedFile(onDisk.parentFile?.name)
        val embedded = db.embeddingDao().countForFile(entity.id)
        FileIndexDetails(file.item, folder, folderLabel, file, passages, embedded, if (embedded > 0) VectorIndex.DIM else null)
    }

    private fun FileEntity.toIndexedFile(location: String?) = IndexedFile(
        item = toUiItem(location),
        status = status,
        error = errorMessage,
        indexedAt = indexedAt
    )
}
