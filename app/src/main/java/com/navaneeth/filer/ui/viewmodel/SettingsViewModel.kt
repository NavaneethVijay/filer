package com.navaneeth.filer.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.navaneeth.filer.data.db.FilerDatabase
import com.navaneeth.filer.data.model.IndexStatus
import com.navaneeth.filer.data.model.ModelDownloadManager
import com.navaneeth.filer.data.preferences.PreferencesManager
import com.navaneeth.filer.data.repository.IndexProgress
import com.navaneeth.filer.data.repository.IndexRepository
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.navaneeth.filer.data.inference.RemoteEmbedder
import com.navaneeth.filer.data.inference.RemoteStatus
import com.navaneeth.filer.data.worker.EngineCompareWorker
import com.navaneeth.filer.data.worker.EngineComparison
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.util.UUID
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.navaneeth.filer.data.worker.IndexingWorker
import com.navaneeth.filer.data.storage.IndexKind
import com.navaneeth.filer.data.update.AppRelease
import com.navaneeth.filer.data.update.UpdateManager
import com.navaneeth.filer.data.update.UpdateStatus

data class IndexSummary(val ready: Int, val total: Int, val failed: Int)

/** Developer mode's "Compare engines", as the settings screen shows it. */
sealed interface CompareState {
    data object Idle : CompareState
    data object Running : CompareState
    data class Done(val result: EngineComparison) : CompareState
    /** [reason] is one of the `EngineCompareWorker.ERROR_*` codes. */
    data class Failed(val reason: String) : CompareState
}

/** Settings that only appear with "Show indexing details" on, persisted across launches. */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val db = FilerDatabase.getInstance(application)
    private val prefs = PreferencesManager(application)
    private val modelManager = ModelDownloadManager.getInstance(application)
    private val indexRepo = IndexRepository.getInstance(application)

    val accelerator: StateFlow<String> = prefs.acceleratorModeFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, prefs.acceleratorMode)

    val chargingOnly: StateFlow<Boolean> = prefs.chargingOnlyFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, prefs.isChargingOnly)

    /** File kinds smart search reads. */
    val indexedKinds: StateFlow<Set<IndexKind>> = prefs.indexedKindsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, prefs.indexedKinds)

    val wifiOnly: StateFlow<Boolean> = prefs.wifiOnlyFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, prefs.isWifiOnly)

    val indexSummary: StateFlow<IndexSummary> = combine(
        db.fileDao().getCountByStatus(IndexStatus.INDEXED),
        db.fileDao().getTotalFileCount(),
        db.fileDao().getCountByStatus(IndexStatus.FAILED)
    ) { ready, total, failed -> IndexSummary(ready, total, failed) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), IndexSummary(0, 0, 0))

    val builtInViewer: StateFlow<Boolean> = prefs.builtInViewerFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, prefs.isBuiltInViewer)

    val phoneColors: StateFlow<Boolean> = prefs.phoneColorsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, prefs.usePhoneColors)

    val developerMode: StateFlow<Boolean> = prefs.developerModeFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, prefs.isDeveloperMode)

    val updateManager = UpdateManager.getInstance(application)
    val updateStatus: StateFlow<UpdateStatus> = updateManager.status
    val autoCheckUpdates: StateFlow<Boolean> = prefs.autoCheckUpdatesFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, prefs.isAutoCheckUpdates)

    val indexWithMac: StateFlow<Boolean> = prefs.indexWithMacFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, prefs.isIndexWithMac)

    /** Whether the Mac embedder answers with the same model; refreshed by [refreshMac]. */
    val macStatus: StateFlow<RemoteStatus> = RemoteEmbedder.status

    private val compareId = MutableStateFlow<UUID?>(null)

    val compare: StateFlow<CompareState> = compareId.flatMapLatest { id ->
        if (id == null) flowOf(CompareState.Idle)
        else WorkManager.getInstance(getApplication()).getWorkInfoByIdFlow(id).map { it.toCompareState() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CompareState.Idle)

    /** Turns developer mode on; false if it already was. Also shows the advanced sections it lives in. */
    fun unlockDeveloperMode(): Boolean {
        if (prefs.isDeveloperMode) return false
        prefs.isDeveloperMode = true
        prefs.showAdvanced = true
        return true
    }

    fun turnOffDeveloperMode() {
        prefs.isIndexWithMac = false
        prefs.isDeveloperMode = false
        compareId.value = null
    }

    fun setIndexWithMac(enabled: Boolean) {
        prefs.isIndexWithMac = enabled
    }

    /** Asks the Mac embedder whether it's there (a cheap local request; no model work). */
    fun refreshMac() {
        viewModelScope.launch { RemoteEmbedder.available(force = true) }
    }

    /** Starts a full pass right away with the Mac running the model (in [IndexingWorker]). */
    /** True when a new pass was started, false when the running one just switches to the Mac. */
    fun indexNowViaMac(): Boolean = IndexingWorker.enqueueViaMac(getApplication())

    val indexProgress: StateFlow<IndexProgress> = indexRepo.progress

    /** Model work runs in [EngineCompareWorker], never here. */
    fun startCompare() {
        compareId.value = EngineCompareWorker.enqueue(getApplication())
    }

    fun dismissCompare() {
        compareId.value = null
    }

    private fun WorkInfo?.toCompareState(): CompareState = when (this?.state) {
        null, WorkInfo.State.CANCELLED -> CompareState.Idle
        WorkInfo.State.SUCCEEDED -> CompareState.Done(EngineCompareWorker.read(outputData))
        WorkInfo.State.FAILED -> CompareState.Failed(outputData.getString(EngineCompareWorker.KEY_ERROR) ?: EngineCompareWorker.ERROR_MAC_FAILED)
        else -> CompareState.Running
    }

    fun setBuiltInViewer(enabled: Boolean) {
        prefs.isBuiltInViewer = enabled
    }

    fun setPhoneColors(enabled: Boolean) {
        prefs.usePhoneColors = enabled
    }

    fun setShowAdvanced(enabled: Boolean) {
        prefs.showAdvanced = enabled
    }

    fun setAccelerator(mode: String) {
        prefs.acceleratorMode = mode
    }

    fun setChargingOnly(enabled: Boolean) {
        prefs.isChargingOnly = enabled
    }

    /**
     * Turning a kind off deletes its search data (its files stay findable by name); turning it on
     * queues those files for indexing.
     */
    fun setKindIndexed(kind: IndexKind, enabled: Boolean) {
        prefs.indexedKinds = if (enabled) prefs.indexedKinds + kind else prefs.indexedKinds - kind
        viewModelScope.launch {
            if (indexRepo.syncAllSources()) IndexingWorker.enqueue(getApplication())
        }
    }

    fun setWifiOnly(enabled: Boolean) {
        prefs.isWifiOnly = enabled
    }

    fun purgeModel() {
        modelManager.purgeModel()
    }

    fun clearIndex() {
        viewModelScope.launch {
            indexRepo.clearEntireIndex()
        }
    }

    fun checkForUpdates() {
        viewModelScope.launch {
            updateManager.checkForUpdate(isManual = true)
        }
    }

    fun downloadUpdate(release: AppRelease) {
        viewModelScope.launch {
            updateManager.downloadUpdate(release)
        }
    }

    fun installUpdate(context: android.content.Context, apkFile: java.io.File) {
        updateManager.installUpdate(context, apkFile)
    }

    fun setAutoCheckUpdates(enabled: Boolean) {
        prefs.isAutoCheckUpdates = enabled
    }
}
