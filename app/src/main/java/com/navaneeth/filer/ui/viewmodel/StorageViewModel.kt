package com.navaneeth.filer.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.navaneeth.filer.data.analysis.AnalyzerState
import com.navaneeth.filer.data.analysis.InsightRange
import com.navaneeth.filer.data.analysis.StorageAnalyzer
import com.navaneeth.filer.data.analysis.VolumeReport
import com.navaneeth.filer.data.files.FileManager
import com.navaneeth.filer.ui.model.FileUiItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import android.net.Uri
import com.navaneeth.filer.data.storage.Storage
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

/**
 * Activity-scoped state for the Storage tab and its screens: the analyser's results, which volume
 * is being looked at, and the Insights time range.
 */
class StorageViewModel(application: Application) : AndroidViewModel(application) {

    private val analyzer = StorageAnalyzer.getInstance(application)
    private val files = FileManager.getInstance(application)

    val state: StateFlow<AnalyzerState> = analyzer.state

    private val _volumeIndex = MutableStateFlow(0)
    val volumeIndex: StateFlow<Int> = _volumeIndex.asStateFlow()

    /** The report for the chosen volume; null before the first scan has walked it. */
    val report: StateFlow<VolumeReport?> = combine(analyzer.state, _volumeIndex) { state, index ->
        state.reports.getOrNull(index) ?: state.reports.firstOrNull()
    }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _range = MutableStateFlow(InsightRange.ONE_YEAR)
    val range: StateFlow<InsightRange> = _range.asStateFlow()

    /** Space the Recycle bin holds; it's only freed when the bin is emptied. */
    val binBytes: StateFlow<Long> = files.trash()
        .map { items -> items.sumOf { it.sizeBytes } }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    /**
     * Files directly inside [path] (hidden ones too, as the scan counts them), largest first, for
     * the space map. Read live rather than kept from the scan, and re-read after file operations.
     */
    fun filesIn(path: String): Flow<List<FileUiItem>> = files.changes
        .onStart { emit(Unit) }
        .map { listFilesBySize(File(path)) }
        .flowOn(Dispatchers.IO)
        .distinctUntilChanged()

    /** Scans if nothing has been scanned yet (opening the tab). */
    fun ensureScanned() {
        if (analyzer.state.value.reports.isEmpty()) analyzer.scan()
    }

    fun rescan() = analyzer.scan(restart = true)

    fun selectVolume(index: Int) {
        _volumeIndex.value = index
    }

    fun setRange(range: InsightRange) {
        _range.value = range
    }
}

/**
 * One attribute read per entry (size, date and type together): on shared storage every separate
 * stat is a round trip, which made folders like Camera slow to list.
 */
private fun listFilesBySize(dir: File): List<FileUiItem> {
    val found = ArrayList<FileUiItem>()
    runCatching {
        Files.newDirectoryStream(dir.toPath()).use { stream ->
            for (entry in stream) {
                val attrs = runCatching { Files.readAttributes(entry, BasicFileAttributes::class.java) }.getOrNull() ?: continue
                if (!attrs.isRegularFile || attrs.size() <= 0L) continue
                val name = entry.fileName.toString()
                found += FileUiItem(
                    uri = Uri.fromFile(entry.toFile()).toString(),
                    name = name,
                    mimeType = Storage.mimeTypeOf(name),
                    sizeBytes = attrs.size(),
                    modifiedAt = attrs.lastModifiedTime().toMillis(),
                    location = dir.name,
                    isDirectory = false,
                    documentId = entry.toString()
                )
            }
        }
    }
    found.sortByDescending { it.sizeBytes }
    return found
}
