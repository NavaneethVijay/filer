package com.navaneeth.filer.ui.viewmodel

import com.navaneeth.filer.data.search.DateField
import com.navaneeth.filer.data.media.MediaFilter
import java.util.Calendar
import android.app.Application
import android.net.ConnectivityManager
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.navaneeth.filer.R
import com.navaneeth.filer.data.db.FilerDatabase
import com.navaneeth.filer.data.model.SourceType
import com.navaneeth.filer.data.media.MediaCategory
import com.navaneeth.filer.data.media.MediaFile
import com.navaneeth.filer.data.media.MediaLibrary
import com.navaneeth.filer.data.model.DownloadState
import com.navaneeth.filer.data.model.ModelDownloadManager
import com.navaneeth.filer.data.preferences.PreferencesManager
import com.navaneeth.filer.data.repository.IndexRepository
import com.navaneeth.filer.data.storage.Storage
import com.navaneeth.filer.data.storage.StorageVolumeInfo
import com.navaneeth.filer.data.places.PlaceScanWorker
import com.navaneeth.filer.data.worker.IndexingWorker
import com.navaneeth.filer.ui.model.FileUiItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/** A folder chosen for smart search, with how far its indexing has got. */
@Immutable
data class SmartFolder(
    val id: Long,
    val name: String,
    val path: String,
    val ready: Int,
    val total: Int,
    /** Couldn't be read; finished as far as progress goes. */
    val failed: Int = 0,
    /** Whether an indexing pass is running right now. */
    val passRunning: Boolean = false
) {
    /** Files still to embed. */
    val pending: Int get() = (total - ready - failed).coerceAtLeast(0)

    /**
     * Show progress only while a pass is actually working through this folder. Files left pending
     * after a pass (waiting for charging, or ones it couldn't take) aren't "indexing".
     */
    val indexing: Boolean get() = passRunning && pending > 0
}

sealed interface AddFolderResult {
    data object Added : AddFolderResult
    data class AlreadyIncluded(val parentName: String) : AddFolderResult
}

/**
 * Activity-scoped state for the file manager. Recent and categories come straight from Android's
 * media index (whole device, always current); smart-search folders and their progress come from
 * Filer's own index.
 */
@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val db = FilerDatabase.getInstance(application)
    private val prefs = PreferencesManager(application)
    private val indexRepo = IndexRepository.getInstance(application)
    private val modelManager = ModelDownloadManager.getInstance(application)
    private val media = MediaLibrary(application)

    /** Pull to refresh: re-reads every list even if MediaStore sent no change. */
    private val manualRefresh = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /**
     * Fires once at start and then whenever files on the device change (bursts coalesced). The first
     * emission skips the debounce, so a screen's first load starts immediately.
     */
    private val deviceChanges: Flow<Unit> = merge(media.changes().debounce(400), manualRefresh).onStart { emit(Unit) }

    /** One cached, auto-updating list per category, so reopening a category is instant. */
    private val collections = mutableMapOf<MediaCategory, StateFlow<List<FileUiItem>?>>()

    private val _hasAccess = MutableStateFlow(Storage.hasAllFilesAccess())
    val hasAccess: StateFlow<Boolean> = _hasAccess.asStateFlow()

    private val _volumes = MutableStateFlow(Storage.volumes(application))
    val volumes: StateFlow<List<StorageVolumeInfo>> = _volumes.asStateFlow()

    /** Every smart-search source with its progress; "Whole phone" is split out below. */
    private val sources: StateFlow<List<Pair<SourceType, SmartFolder>>> = combine(
        db.sourceDao().getAllSources(),
        db.fileDao().getProgressBySource(),
        indexRepo.progress.map { it.isRunning }.distinctUntilChanged()
    ) { sources, progress, running ->
        val bySource = progress.associateBy { it.sourceId }
        sources.map { source ->
            val p = bySource[source.id]
            source.type to SmartFolder(source.id, source.displayName, source.uri, p?.ready ?: 0, p?.total ?: 0, p?.failed ?: 0, running)
        }.sortedBy { it.second.name.lowercase() }
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val smartFolders: StateFlow<List<SmartFolder>> = sources
        .map { list -> list.filter { it.first == SourceType.LOCAL_FOLDER }.map { it.second } }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** "Whole phone" (all photos, PDFs and text) with its progress, or null when off. */
    val wholePhone: StateFlow<SmartFolder?> = sources
        .map { list -> list.firstOrNull { it.first == SourceType.DEVICE }?.second }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val recentFiles: StateFlow<List<FileUiItem>> = deviceChanges
        .mapLatest { media.recent(RECENT_LIMIT).map { it.toUiItem() } }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Files last changed on the day starting at [dayStart] (local midnight), newest first; live. */
    fun filesOn(dayStart: Long): Flow<List<FileUiItem>> {
        val dayEnd = Calendar.getInstance().apply { timeInMillis = dayStart; add(Calendar.DAY_OF_YEAR, 1) }.timeInMillis - 1
        return deviceChanges
            .mapLatest {
                media.filtered(MediaFilter(dateRange = dayStart to dayEnd, dateField = DateField.MODIFIED), DAY_LIMIT)
                    .map { it.toUiItem() }
                    .sortedByDescending { it.modifiedAt }
            }
            .distinctUntilChanged()
    }

    val carouselFiles: StateFlow<List<FileUiItem>> = recentFiles.map { it.take(CAROUSEL_LIMIT) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val categoryCounts: StateFlow<Map<MediaCategory, Int>> = deviceChanges
        .mapLatest { MediaCategory.entries.associateWith { media.count(it) } }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val downloadState: StateFlow<DownloadState> = modelManager.downloadState

    val bannerDismissed: StateFlow<Boolean> = prefs.smartSearchBannerDismissedFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, prefs.isSmartSearchBannerDismissed)

    val showAdvanced: StateFlow<Boolean> = prefs.showAdvancedFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, prefs.showAdvanced)

    val recentGridView: StateFlow<Boolean> = prefs.recentGridViewFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, prefs.isRecentGridView)

    val collectionGridView: StateFlow<Boolean> = prefs.collectionGridViewFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, prefs.isCollectionGridView)

    init {
        // Pick up files added or changed since the last launch; only queue model work if needed.
        viewModelScope.launch {
            if (indexRepo.syncAllSources()) IndexingWorker.enqueue(getApplication())
        }
        // Where new photos were taken (only if the user allowed photo locations).
        PlaceScanWorker.enqueue(application)
    }

    fun setRecentGridView(grid: Boolean) {
        prefs.isRecentGridView = grid
    }

    fun setCollectionGridView(grid: Boolean) {
        prefs.isCollectionGridView = grid
    }

    /** Null until the first load finishes. Kept warm for a minute after the screen closes. */
    fun collection(category: MediaCategory): StateFlow<List<FileUiItem>?> = collections.getOrPut(category) {
        deviceChanges
            .mapLatest { media.category(category).map { it.toUiItem() } }
            .flowOn(Dispatchers.Default)
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(60_000), null)
    }

    /**
     * Pull to refresh on Browse: catches the media index up with files other apps added, edited or
     * deleted without telling it, picks up new files for smart search, then reloads the lists.
     */
    fun refresh() {
        if (_refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch {
            try {
                refreshAccess()
                media.resync(_volumes.value.map { it.root })
                manualRefresh.emit(Unit)
            } finally {
                _refreshing.value = false
            }
            // Can walk the whole phone, so it runs after the spinner has gone.
            if (indexRepo.syncAllSources()) IndexingWorker.enqueue(getApplication())
        }
    }

    /** Call when returning to the app: the user may have granted or revoked access in Settings. */
    fun refreshAccess() {
        _hasAccess.value = Storage.hasAllFilesAccess()
        _volumes.value = Storage.volumes(getApplication())
    }

    // Smart-search folders

    /** The smart-search folder that covers [path], if any. */
    fun smartFolderFor(path: String): SmartFolder? =
        smartFolders.value.firstOrNull { path == it.path || path.startsWith(it.path + "/") }

    /**
     * Adds a folder to smart search. A folder inside one that's already included is a no-op; adding
     * a parent absorbs any of its subfolders that were added separately.
     */
    suspend fun addSmartFolder(path: String): AddFolderResult {
        smartFolderFor(path)?.let { return AddFolderResult.AlreadyIncluded(it.name) }
        val subfolders = smartFolders.value.filter { it.path.startsWith("$path/") }
        val id = indexRepo.addLocalFolder(path, displayNameFor(path))
        // Add the parent first, so the subfolders' files are handed over with their vectors kept.
        subfolders.forEach { indexRepo.removeSource(it.id) }
        indexRepo.syncSource(id)
        IndexingWorker.enqueue(getApplication())
        return AddFolderResult.Added
    }

    fun removeSmartFolder(folder: SmartFolder) {
        viewModelScope.launch { indexRepo.removeSource(folder.id) }
    }

    /** Turns "Whole phone" on (queues indexing) or off (keeps files that a folder still covers). */
    fun setWholePhone(enabled: Boolean) {
        viewModelScope.launch {
            indexRepo.setWholePhone(enabled, getApplication<Application>().getString(R.string.whole_phone_title))
            if (enabled) IndexingWorker.enqueue(getApplication())
        }
    }

    /** Folders suggested during setup that exist on this phone, with whether they start ticked. */
    fun suggestedFolders(): List<Pair<String, Boolean>> {
        val root = Storage.volumes(getApplication()).firstOrNull { it.isPrimary }?.root ?: return emptyList()
        return listOf(
            "Documents" to true,
            "Download" to true,
            "DCIM/Camera" to false,
            "Pictures/Screenshots" to false
        ).map { (rel, on) -> File(root, rel).path to on }.filter { File(it.first).isDirectory }
    }

    fun completeSetup(paths: List<String>, wholePhone: Boolean = false) {
        viewModelScope.launch {
            paths.forEach { addSmartFolder(it) }
            if (wholePhone) setWholePhone(true)
            prefs.isOnboardingCompleted = true
        }
    }

    fun rescan() = IndexingWorker.enqueue(getApplication(), userRequested = true)

    /** "Internal storage › Documents › Work" for a path. */
    fun displayPath(path: String): String = Storage.displayPath(getApplication(), path)

    // Smart search model

    /** True when the download should first ask before using mobile data. */
    fun needsMeteredConfirmation(): Boolean {
        if (!prefs.isWifiOnly) return false
        val cm = getApplication<Application>().getSystemService(ConnectivityManager::class.java)
        return cm?.isActiveNetworkMetered ?: false
    }

    fun enableSmartSearch() = modelManager.launchDownload()

    fun pauseSmartSearchDownload() = modelManager.pauseDownload()

    fun cancelSmartSearchDownload() = modelManager.cancelDownload()

    fun dismissSmartSearchBanner() {
        prefs.isSmartSearchBannerDismissed = true
    }

    private fun displayNameFor(path: String): String {
        val volume = Storage.volumeFor(getApplication(), path)
        return if (volume != null && volume.root.path == path) volume.label else File(path).name
    }

    private fun MediaFile.toUiItem() = FileUiItem(
        uri = Uri.fromFile(File(path)).toString(),
        name = name,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        modifiedAt = modifiedAt,
        location = File(path).parentFile?.name
    )

    private companion object {
        const val RECENT_LIMIT = 300
        const val CAROUSEL_LIMIT = 12
        const val DAY_LIMIT = 2_000
    }
}
