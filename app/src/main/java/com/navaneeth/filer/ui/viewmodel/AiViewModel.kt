package com.navaneeth.filer.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.navaneeth.filer.data.ai.AiFeatures
import com.navaneeth.filer.data.ai.AiPlugin
import com.navaneeth.filer.data.ai.AiPluginManager
import com.navaneeth.filer.data.ai.DeviceCapability
import com.navaneeth.filer.data.ai.FileTagEntity
import com.navaneeth.filer.data.ai.FileTextEntity
import com.navaneeth.filer.data.ai.OcrScript
import com.navaneeth.filer.data.ai.PackState
import com.navaneeth.filer.data.ai.PluginData
import com.navaneeth.filer.data.ai.RenameSuggestionEntity
import com.navaneeth.filer.data.ai.TagCount
import com.navaneeth.filer.data.ai.TextSource
import com.navaneeth.filer.data.ai.Unsupported
import com.navaneeth.filer.data.preferences.PreferencesManager
import com.navaneeth.filer.data.storage.Storage
import com.navaneeth.filer.data.worker.PhotoTextScan
import com.navaneeth.filer.data.worker.PhotoTextWorker
import com.navaneeth.filer.ui.model.FileUiItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/** One plugin as Settings shows it. */
data class PluginRow(
    val plugin: AiPlugin,
    val state: PackState,
    val enabled: Boolean,
    /** Null when this phone can run it. */
    val unsupported: Unsupported?
)

/**
 * Activity-scoped state for the AI plugins: Settings › AI plugins, the cards in a file's details,
 * tags in Browse and the Tag screen. Model work happens in [AiFeatures], never here.
 */
class AiViewModel(application: Application) : AndroidViewModel(application) {

    private val manager = AiPluginManager.getInstance(application)
    private val features = AiFeatures.getInstance(application)
    private val data = PluginData.getInstance(application)
    private val prefs = PreferencesManager(application)

    val plugins: StateFlow<List<PluginRow>> = combine(
        manager.states,
        combine(AiPlugin.entries.map { prefs.pluginEnabledFlow(it.name) }) { it.toList() }
    ) { states, enabled ->
        AiPlugin.entries.mapIndexed { i, plugin ->
            PluginRow(plugin, states[plugin.folder] ?: PackState.NotInstalled, enabled[i], DeviceCapability.check(getApplication(), plugin))
        }
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val scripts: StateFlow<Map<OcrScript, PackState>> = manager.states
        .map { states -> OcrScript.entries.associateWith { states[it.folder] ?: PackState.NotInstalled } }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val readPhotosInBackground: StateFlow<Boolean> = prefs.readPhotosInBackgroundFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, prefs.readPhotosInBackground)
    val suggestNames: StateFlow<Boolean> = prefs.suggestNamesFlow.stateIn(viewModelScope, SharingStarted.Eagerly, prefs.suggestNames)
    val speechLanguage: StateFlow<String> = prefs.speechLanguageFlow.stateIn(viewModelScope, SharingStarted.Eagerly, prefs.speechLanguage)
    val photoTextScan: StateFlow<PhotoTextScan> = PhotoTextWorker.progress
    val photosRead: StateFlow<Int> = data.countFlow(TextSource.OCR).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val transcriptCount: StateFlow<Int> = data.countFlow(TextSource.TRANSCRIPT).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val tagCounts: StateFlow<List<TagCount>> = data.tagCounts()
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val working = features.working
    val progress = features.progress
    val problems = features.problems
    val states = features.states

    fun freeSpace(): Long = DeviceCapability.freeSpace(getApplication())

    // Plugins

    fun download(plugin: AiPlugin) {
        prefs.setPluginEnabled(plugin.name, true)
        manager.download(plugin)
    }

    fun download(script: OcrScript) = manager.download(script)
    fun pause(folder: String) = manager.pause(folder)
    fun cancel(folder: String) = manager.cancel(folder)
    /** Clears what [plugin] made (read text, transcripts, or suggested tags and names); keeps the plugin. */
    fun clearResults(plugin: AiPlugin) = features.clearResults(plugin)
    fun clearSuggestions() = features.clearSuggestions()

    fun remove(plugin: AiPlugin) {
        if (plugin == AiPlugin.PHOTO_TEXT) setReadPhotosInBackground(false)
        features.remove(plugin)
    }

    fun removeScript(script: OcrScript) = manager.deleteFiles(script.folder)

    fun setEnabled(plugin: AiPlugin, enabled: Boolean) {
        prefs.setPluginEnabled(plugin.name, enabled)
        if (plugin == AiPlugin.PHOTO_TEXT && !enabled) setReadPhotosInBackground(false)
    }

    fun setReadPhotosInBackground(on: Boolean) {
        prefs.readPhotosInBackground = on
        if (on) PhotoTextWorker.schedule(getApplication()) else PhotoTextWorker.cancel(getApplication())
    }

    fun setSuggestNames(on: Boolean) {
        prefs.suggestNames = on
    }

    fun setSpeechLanguage(code: String) {
        prefs.speechLanguage = code
    }


    fun isReady(plugin: AiPlugin): Boolean = manager.isReady(plugin)

    // One file

    fun suggestTags(path: String) = features.suggestTags(File(path))
    fun readText(path: String) = features.readText(File(path))
    fun textFlow(path: String, source: String): Flow<FileTextEntity?> = data.textFlow(path, source)
    fun tagsFlow(path: String): Flow<List<FileTagEntity>> = data.tagsFlow(path)
    fun suggestionFlow(path: String): Flow<RenameSuggestionEntity?> = data.suggestionFlow(path)
    fun transcribe(path: String) = features.transcribe(File(path))
    fun cancel(path: String, task: com.navaneeth.filer.data.ai.AiTask) = features.cancel(File(path), task)
    fun clearProblem(path: String, task: com.navaneeth.filer.data.ai.AiTask) = features.clearProblem(path, task)
    fun addTag(path: String, tag: String) = viewModelScope.launch { data.addTag(path, tag) }
    fun removeTag(path: String, tag: String) = viewModelScope.launch { data.removeTag(path, tag) }
    fun dismissSuggestion(path: String) = viewModelScope.launch { data.dismissSuggestion(path) }

    // All tags (Settings › AI plugins › Tags, Browse › Tags › Manage)

    val suggestedTagCount: StateFlow<Int> = data.suggestedTagCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    fun renameTag(tag: String, newName: String) = viewModelScope.launch { data.renameTag(tag, newName) }
    fun deleteTag(tag: String) = viewModelScope.launch { data.deleteTag(tag) }
    fun removeSuggestedTags() = viewModelScope.launch { data.removeSuggestedTags() }

    /** Files with [tag], newest first; files deleted outside Filer are left out. */
    fun taggedFiles(tag: String): Flow<List<FileUiItem>> = data.pathsTaggedFlow(tag)
        .map { paths -> paths.map(::File).filter { it.exists() }.map(::toItem).sortedByDescending { it.modifiedAt } }
        .flowOn(Dispatchers.IO)
        .distinctUntilChanged()

    private fun toItem(file: File) = FileUiItem(
        uri = Uri.fromFile(file).toString(),
        name = file.name,
        mimeType = if (file.isDirectory) "" else Storage.mimeTypeOf(file.name),
        sizeBytes = file.length(),
        modifiedAt = file.lastModified(),
        location = file.parentFile?.name,
        isDirectory = file.isDirectory,
        documentId = file.path
    )
}
