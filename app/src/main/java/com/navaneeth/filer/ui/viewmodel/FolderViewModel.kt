package com.navaneeth.filer.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.navaneeth.filer.data.browser.BrowserItem
import com.navaneeth.filer.data.browser.FolderBrowser
import com.navaneeth.filer.data.files.FileManager
import com.navaneeth.filer.data.preferences.PreferencesManager
import com.navaneeth.filer.ui.model.FileUiItem
import com.navaneeth.filer.ui.navigation.Screen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

enum class FolderSort { NAME, DATE, SIZE }

sealed interface FolderContent {
    data object Loading : FolderContent
    data class Loaded(val items: List<FileUiItem>) : FolderContent
    data object Inaccessible : FolderContent
}

/** One folder on local storage. Scoped to its back-stack entry, so each level keeps its own state. */
class FolderViewModel(application: Application, savedStateHandle: SavedStateHandle) : AndroidViewModel(application) {

    val path: String = savedStateHandle.toRoute<Screen.Folder>().path
    private val browser = FolderBrowser()
    private val prefs = PreferencesManager(application)

    private val raw = MutableStateFlow<List<BrowserItem>?>(null)
    private val inaccessible = MutableStateFlow(false)

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    val sort: StateFlow<FolderSort> = prefs.folderSortFlow.map(::parseSort)
        .stateIn(viewModelScope, SharingStarted.Eagerly, parseSort(prefs.folderSort))

    val gridView: StateFlow<Boolean> = prefs.folderGridViewFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, prefs.isFolderGridView)

    val content: StateFlow<FolderContent> = combine(raw, inaccessible, sort) { items, denied, sort ->
        when {
            denied -> FolderContent.Inaccessible
            items == null -> FolderContent.Loading
            else -> FolderContent.Loaded(sorted(items, sort).map { it.toUiItem() })
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FolderContent.Loading)

    init {
        load()
        // Rename, move, delete… anywhere in the app: re-list so this folder is never stale.
        viewModelScope.launch { FileManager.getInstance(application).changes.collect { load() } }
    }

    fun refresh() {
        _isRefreshing.value = true
        load()
    }

    fun setSort(value: FolderSort) {
        prefs.folderSort = value.name
    }

    fun setGridView(grid: Boolean) {
        prefs.isFolderGridView = grid
    }

    private fun load() {
        viewModelScope.launch {
            val items = browser.list(path)
            inaccessible.value = items == null
            if (items != null) raw.value = items
            _isRefreshing.value = false
        }
    }

    private fun sorted(items: List<BrowserItem>, sort: FolderSort): List<BrowserItem> {
        val order = when (sort) {
            FolderSort.NAME -> compareBy<BrowserItem> { it.name.lowercase() }
            FolderSort.DATE -> compareByDescending { it.modifiedAt }
            FolderSort.SIZE -> compareByDescending { it.sizeBytes }
        }
        // Folders always come first, like every file manager.
        return items.sortedWith(compareByDescending<BrowserItem> { it.isDirectory }.then(order))
    }

    private fun parseSort(value: String) = runCatching { FolderSort.valueOf(value) }.getOrDefault(FolderSort.NAME)

    private fun BrowserItem.toUiItem() = FileUiItem(
        uri = uri.toString(),
        name = name,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        modifiedAt = modifiedAt,
        location = File(path).parentFile?.name,
        isDirectory = isDirectory,
        documentId = path,
        childCount = childCount
    )
}
