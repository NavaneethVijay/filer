package com.navaneeth.filer.data.ai

import android.content.Context
import android.media.ExifInterface
import android.os.PowerManager
import android.util.Log
import com.navaneeth.filer.data.extractor.DocumentText
import com.navaneeth.filer.data.inference.EmbedderEngine
import com.navaneeth.filer.data.model.ModelDownloadManager
import com.navaneeth.filer.data.preferences.PreferencesManager
import com.navaneeth.filer.data.repository.IndexRepository
import com.navaneeth.filer.data.worker.IndexingWorker
import com.navaneeth.filer.data.worker.ThermalGovernor
import com.navaneeth.filer.ui.model.FileKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Something a plugin is doing for a file. */
enum class AiTask { PHOTO_TEXT, INSIGHTS, TRANSCRIPT }

/** Why a plugin couldn't do it. */
enum class AiProblem {
    TOO_HOT,
    /** The file has no text to work from. */
    NO_TEXT,
    /** A photo or scan: its text has to be read first, and Text in photos isn't installed or is off. */
    NEEDS_PHOTO_TEXT,
    /** A recording: it has to be transcribed first. */
    NEEDS_TRANSCRIPT,
    /** Tags come from the smart-search model, which isn't downloaded. */
    NEEDS_SMART_SEARCH,
    /** Nothing stood out enough to be a tag. */
    NO_TAGS,
    FAILED
}

/** Where a running task is. */
enum class AiPhase {
    /** Queued behind another model run (another AI task, or smart search indexing). */
    WAITING,
    RUNNING
}

/** A running task's state, for the status line in its card. */
data class AiTaskState(val phase: AiPhase, val startedAt: Long)

/**
 * Runs the AI plugins for one file at a time, only when the user taps (read text, suggest tags,
 * transcript); opening a file runs nothing. Results are saved in
 * [PluginData]; with smart search on, plugin text is then added to the index by [IndexingWorker].
 *
 * Every model run takes [EmbedderEngine.exclusive] (tags use the smart-search model, whose runs
 * are serialised the same way), so plugins and smart search never run models at the same moment,
 * and none starts while the phone is very hot.
 */
class AiFeatures private constructor(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val manager = AiPluginManager.getInstance(context)
    private val data = PluginData.getInstance(context)
    private val prefs = PreferencesManager(context)
    private val thermal = ThermalGovernor(context)

    private val ocr by lazy { OcrEngine(context) }
    private val whisper by lazy { WhisperEngine(context) }
    private val tagger by lazy { TagSuggester(context) }
    /** Engines' own use is serialised here (they aren't thread-safe); the model lock is per run. */
    private val ocrLock = Mutex()
    private val speechLock = Mutex()
    private var idleCloseJob: Job? = null

    private val jobs = java.util.concurrent.ConcurrentHashMap<Pair<String, AiTask>, Job>()

    private val _working = MutableStateFlow<Map<String, Set<AiTask>>>(emptyMap())
    /** Tasks running per file path. */
    val working: StateFlow<Map<String, Set<AiTask>>> = _working.asStateFlow()

    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    /** Transcription progress per path, 0..1. */
    val progress: StateFlow<Map<String, Float>> = _progress.asStateFlow()

    init {
        scope.launch {
            // Earlier versions saved summaries in files.db; the feature is gone, so is what it kept.
            data.clearSummaries()
            // The language model plugin (Qwen) is gone: free its download. Its suggestions stay.
            PluginFiles.dir(context, RETIRED_LANGUAGE_FOLDER).takeIf { it.exists() }?.deleteRecursively()
        }
    }

    private val _states = MutableStateFlow<Map<String, Map<AiTask, AiTaskState>>>(emptyMap())
    /** Running tasks per path, with their phase. */
    val states: StateFlow<Map<String, Map<AiTask, AiTaskState>>> = _states.asStateFlow()

    private val _problems = MutableStateFlow<Map<String, Map<AiTask, AiProblem>>>(emptyMap())
    /** The last failure of each task per path, until it's tried again or dismissed. */
    val problems: StateFlow<Map<String, Map<AiTask, AiProblem>>> = _problems.asStateFlow()

    // region Entry points

    // Nothing here runs by itself: opening a file only shows it. Each entry point is a tap.

    /** Read text: the words in a photo or scanned PDF. */
    fun readText(file: File) {
        launch(file, AiTask.PHOTO_TEXT) {
            if (!canReadPhotoText(file)) return@launch problem(file, AiTask.PHOTO_TEXT, AiProblem.NEEDS_PHOTO_TEXT)
            readPhotoText(file, AiTask.PHOTO_TEXT)
        }
    }

    /**
     * Suggest tags: the words a search would find this file by (see [TagSuggester]), and a clearer
     * name if its name says nothing. A scanned PDF's text is read first; photos need no reading,
     * the search model understands the picture.
     */
    fun suggestTags(file: File) {
        launch(file, AiTask.INSIGHTS) {
            if (!ModelDownloadManager.getInstance(context).isModelReady()) {
                return@launch problem(file, AiTask.INSIGHTS, AiProblem.NEEDS_SMART_SEARCH)
            }
            if (FileKind.of(file.name, "") == FileKind.PDF && textOf(file) == null && canReadPhotoText(file)) {
                launch(file, AiTask.PHOTO_TEXT) { readPhotoText(file, AiTask.PHOTO_TEXT) }.join()
            }
            insights(file)
        }
    }

    fun transcribe(file: File) = launch(file, AiTask.TRANSCRIPT) {
        _progress.update { it + (file.path to 0f) }
        try {
            val segments = speechLock.withLock {
                whisper.transcribe(
                    file = file,
                    language = prefs.speechLanguage.takeIf { it.isNotEmpty() },
                    exclusive = { window -> withModel(file, AiTask.TRANSCRIPT) { window() } },
                    onProgress = { p -> _progress.update { it + (file.path to p) } }
                )
            }
            data.saveText(file, TextSource.TRANSCRIPT, segments.joinToString(" ") { it.text }, segments)
            afterTextSaved()
        } finally {
            _progress.update { it - file.path }
            scheduleIdleClose()
        }
    }

    fun cancel(file: File, task: AiTask) {
        jobs.remove(file.path to task)?.cancel()
    }

    fun clearProblem(path: String, task: AiTask) = _problems.update { all ->
        val rest = all[path].orEmpty() - task
        if (rest.isEmpty()) all - path else all + (path to rest)
    }

    /**
     * Clears what a plugin made, keeping the plugin: text read from photos and scans, transcripts, or
     * suggested tags and names. Their passages leave the search index too; tags the user added stay.
     */
    fun clearResults(plugin: AiPlugin) = scope.launch {
        when (plugin) {
            AiPlugin.PHOTO_TEXT, AiPlugin.SPEECH -> {
                val source = if (plugin == AiPlugin.PHOTO_TEXT) TextSource.OCR else TextSource.TRANSCRIPT
                data.forgetSource(source)
                IndexRepository.getInstance(context).removePluginText(source)
            }
        }
    }

    /** Clears suggested tags and names (Tags settings); tags the user added stay. */
    fun clearSuggestions() = scope.launch { data.forgetLanguageResults() }

    /** Removes a plugin: its model files, everything it produced, and its passages in the index. */
    fun remove(plugin: AiPlugin) = scope.launch {
        when (plugin) {
            AiPlugin.PHOTO_TEXT -> {
                ocrLock.withLock { ocr.close() }
                OcrScript.entries.forEach { manager.deleteFiles(it.folder) }
                data.forgetSource(TextSource.OCR)
                IndexRepository.getInstance(context).removePluginText(TextSource.OCR)
            }
            AiPlugin.SPEECH -> {
                speechLock.withLock { whisper.close() }
                data.forgetSource(TextSource.TRANSCRIPT)
                IndexRepository.getInstance(context).removePluginText(TextSource.TRANSCRIPT)
            }
        }
        manager.deleteFiles(plugin.folder)
    }

    // endregion

    // region Work

    fun canReadPhotoText(file: File): Boolean {
        if (!manager.isReady(AiPlugin.PHOTO_TEXT)) return false
        val kind = FileKind.of(file.name, "")
        return kind == FileKind.IMAGE && !file.name.endsWith(".svg", true) || kind == FileKind.PDF
    }

    /**
     * Reads the text in a photo (or a scanned PDF) unless it was already read in this version.
     * [task] is the task to show as running (null from the background reader).
     */
    suspend fun readPhotoText(file: File, task: AiTask? = null) {
        if (data.freshText(file, TextSource.OCR) != null) return
        val isPdf = FileKind.of(file.name, "") == FileKind.PDF
        // A PDF with its own text needs no OCR: show that text (smart search already has it).
        if (isPdf) {
            extractDocumentText(file)?.takeIf { it.isNotBlank() }?.let { layer ->
                data.saveText(file, TextSource.OCR, layer, searchable = false)
                return
            }
        }
        val text = withModel(file, task) {
            ocrLock.withLock { if (isPdf) ocr.readPdf(file) else ocr.readImage(file) }
        }
        data.saveText(file, TextSource.OCR, text)
        if (text.isNotBlank()) afterTextSaved()
        scheduleIdleClose()
    }

    /** Tags and (for unclear names) a clearer name; asked for by the user, so always run. */
    private suspend fun insights(file: File) {
        val text = textOf(file)
        val suggestion = tagger.suggest(file, text) {
            // Before each model run: never while the phone is very hot.
            if (thermal.status() >= PowerManager.THERMAL_STATUS_SEVERE) throw TooHot()
        } ?: return problem(file, AiTask.INSIGHTS, AiProblem.NEEDS_SMART_SEARCH)
        if (suggestion.tags.isEmpty()) problem(file, AiTask.INSIGHTS, AiProblem.NO_TAGS)
        data.setSuggestedTags(file.path, suggestion.tags)
        if (prefs.suggestNames && AiText.isUnclearName(file.name)) suggestedName(file, text, suggestion)?.let { data.suggestName(file, it) }
        data.markInsightsDone(file)
    }

    /** Date + what it is: the document type and its title line, or for a photo its subject and place. */
    private fun suggestedName(file: File, text: String?, suggestion: TagSuggester.Suggestion): String? {
        val photo = FileKind.of(file.name, "") == FileKind.IMAGE
        val date = text?.let { AiText.dateIn(it) } ?: takenOn(file)
        return if (photo) {
            AiText.ruleName(file.name, date, suggestion.topLabel, title = null, place = suggestion.place)
        } else {
            AiText.ruleName(file.name, date, suggestion.documentType, title = text?.let(AiText::titleLine), place = null)
        }
    }

    /** When a photo was taken (its camera date), else when the file was last changed. */
    private fun takenOn(file: File): LocalDate {
        val exif = runCatching { ExifInterface(file.path).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) }.getOrNull()
        exif?.let { runCatching { return LocalDate.parse(it.take(10).replace(':', '-')) } }
        return Instant.ofEpochMilli(file.lastModified()).atZone(ZoneId.systemDefault()).toLocalDate()
    }

    /** The file's text from the best source: what a plugin read, else the document itself. */
    suspend fun textOf(file: File): String? {
        data.freshText(file, TextSource.TRANSCRIPT)?.text?.takeIf { it.isNotBlank() }?.let { return it }
        data.freshText(file, TextSource.OCR)?.text?.takeIf { it.isNotBlank() }?.let { return it }
        return extractDocumentText(file)?.takeIf { it.isNotBlank() }
    }

    private fun extractDocumentText(file: File): String? = runCatching {
        if (!DocumentText.canRead(file.name)) return null
        DocumentText.extract(context, file).joinToString("\n") { it.text }
    }.getOrNull()

    /**
     * One model run: waits for any other model (shown as WAITING on [task]), then RUNNING; doesn't
     * start while the phone is very hot.
     */
    private suspend fun <T> withModel(file: File, task: AiTask?, block: suspend () -> T): T {
        if (thermal.status() >= PowerManager.THERMAL_STATUS_SEVERE) throw TooHot()
        task?.let { setPhase(file.path, it, AiPhase.WAITING) }
        return EmbedderEngine.exclusive {
            task?.let { setPhase(file.path, it, AiPhase.RUNNING) }
            block()
        }
    }

    private fun setPhase(path: String, task: AiTask, phase: AiPhase) = _states.update { all ->
        val current = all[path].orEmpty()
        val started = current[task]?.startedAt ?: System.currentTimeMillis()
        all + (path to (current + (task to AiTaskState(phase, started))))
    }

    private fun afterTextSaved() {
        // Smart search takes the text in on its next pass (paced, never from here).
        if (ModelDownloadManager.getInstance(context).isModelReady()) IndexingWorker.enqueue(context)
    }

    private fun launch(file: File, task: AiTask, block: suspend () -> Unit): Job {
        val key = file.path to task
        jobs[key]?.takeIf { it.isActive }?.let { return it }
        clearProblem(file.path, task)
        _working.update { it + (file.path to (it[file.path].orEmpty() + task)) }
        _states.update { all -> all + (file.path to (all[file.path].orEmpty() + (task to AiTaskState(AiPhase.RUNNING, System.currentTimeMillis())))) }
        val job = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: TooHot) {
                problem(file, task, AiProblem.TOO_HOT)
            } catch (t: Throwable) {
                Log.w(TAG, "AI task $task failed: ${t.javaClass.simpleName}")
                problem(file, task, AiProblem.FAILED)
            } finally {
                _working.update { all ->
                    val rest = all[file.path].orEmpty() - task
                    if (rest.isEmpty()) all - file.path else all + (file.path to rest)
                }
                _states.update { all ->
                    val rest = all[file.path].orEmpty() - task
                    if (rest.isEmpty()) all - file.path else all + (file.path to rest)
                }
                jobs.remove(key)
            }
        }
        jobs[key] = job
        return job
    }

    private fun problem(file: File, task: AiTask, problem: AiProblem) =
        _problems.update { all -> all + (file.path to (all[file.path].orEmpty() + (task to problem))) }

    /** The ONNX models hold tens of MB: let go of them when nobody has used them for a while. */
    private fun scheduleIdleClose() {
        idleCloseJob?.cancel()
        idleCloseJob = scope.launch {
            delay(IDLE_CLOSE_MS)
            ocrLock.withLock { ocr.close() }
            speechLock.withLock { whisper.close() }
        }
    }

    private class TooHot : Exception()

    // endregion

    companion object {
        private const val TAG = "AiFeatures"
        /** Where the retired language model plugin kept its download. */
        private const val RETIRED_LANGUAGE_FOLDER = "language"
        private const val IDLE_CLOSE_MS = 120_000L

        @Volatile
        private var INSTANCE: AiFeatures? = null

        fun getInstance(context: Context): AiFeatures =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: AiFeatures(context.applicationContext).also { INSTANCE = it }
            }
    }
}
