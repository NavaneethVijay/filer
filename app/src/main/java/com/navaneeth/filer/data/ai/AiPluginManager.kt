package com.navaneeth.filer.data.ai

import android.content.Context
import android.util.Log
import com.navaneeth.filer.data.preferences.PreferencesManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** Where a plugin (or script pack) is. */
sealed interface PackState {
    data object NotInstalled : PackState
    data class Downloading(val bytes: Long, val total: Long) : PackState {
        val fraction: Float get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f
    }
    data class Paused(val bytes: Long, val total: Long) : PackState
    data object Installed : PackState
    data class Failed(val reason: Reason) : PackState

    enum class Reason { NO_SPACE, NETWORK, BAD_FILE }
}

/**
 * Downloads, pauses and removes the AI plugins' model files. Downloads start only from a tap in
 * Settings › AI plugins; they resume where they stopped and every file's size is checked before
 * the plugin is marked installed.
 */
class AiPluginManager private constructor(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = PreferencesManager(context)
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jobs = HashMap<String, Job>()

    private val _states = MutableStateFlow(
        AiPlugin.entries.associate { it.folder to initial(it.folder, it.files) } +
            OcrScript.entries.associate { it.folder to initial(it.folder, it.files) }
    )
    /** Keyed by [AiPlugin.folder] / [OcrScript.folder]. */
    val states: StateFlow<Map<String, PackState>> = _states.asStateFlow()

    fun state(plugin: AiPlugin): PackState = _states.value[plugin.folder] ?: PackState.NotInstalled

    /** Installed, switched on, and this phone can run it. */
    fun isReady(plugin: AiPlugin): Boolean =
        state(plugin) == PackState.Installed && prefs.isPluginEnabled(plugin.name) && DeviceCapability.check(context, plugin) == null

    fun installedScripts(): List<OcrScript> = OcrScript.entries.filter { _states.value[it.folder] == PackState.Installed }

    fun download(plugin: AiPlugin) = download(plugin.folder, plugin.files)
    fun download(script: OcrScript) = download(script.folder, script.files)

    fun pause(folder: String) {
        jobs.remove(folder)?.cancel()
        set(folder, partialState(folder) ?: PackState.NotInstalled)
    }

    /** Stops a download and throws away what it fetched. */
    fun cancel(folder: String) {
        jobs.remove(folder)?.cancel()
        PluginFiles.dir(context, folder).deleteRecursively()
        set(folder, PackState.NotInstalled)
    }

    /** Deletes the model files; the caller removes what the plugin produced (see AiFeatures.remove). */
    fun deleteFiles(folder: String) {
        jobs.remove(folder)?.cancel()
        PluginFiles.dir(context, folder).deleteRecursively()
        set(folder, PackState.NotInstalled)
    }

    private fun download(folder: String, files: List<ModelFile>) {
        if (jobs[folder]?.isActive == true) return
        val total = files.sumOf { it.sizeBytes }
        val dir = PluginFiles.dir(context, folder).apply { mkdirs() }
        val have = files.sumOf { File(dir, it.name).length().coerceAtMost(it.sizeBytes) + partFile(dir, it).length() }
        if (!DeviceCapability.hasRoomFor(context, total - have)) {
            set(folder, PackState.Failed(PackState.Reason.NO_SPACE))
            return
        }
        jobs[folder] = scope.launch {
            try {
                var done = files.sumOf { done(dir, it) }
                set(folder, PackState.Downloading(done, total))
                for (file in files) {
                    if (File(dir, file.name).length() == file.sizeBytes) continue
                    fetch(dir, file) { bytes ->
                        set(folder, PackState.Downloading(done + bytes, total))
                    }
                    done += file.sizeBytes
                }
                set(folder, if (PluginFiles.isInstalled(context, folder, files)) PackState.Installed else PackState.Failed(PackState.Reason.BAD_FILE))
            } catch (e: CancellationException) {
                throw e
            } catch (e: BadFileException) {
                Log.w(TAG, "Plugin download had the wrong size")
                set(folder, PackState.Failed(PackState.Reason.BAD_FILE))
            } catch (e: Exception) {
                Log.w(TAG, "Plugin download failed: ${e.javaClass.simpleName}")
                set(folder, PackState.Failed(PackState.Reason.NETWORK))
            } finally {
                jobs.remove(folder)
            }
        }
    }

    /** Downloads one file into `name.part`, resuming it, then renames it once the size is right. */
    private suspend fun fetch(dir: File, file: ModelFile, onBytes: (Long) -> Unit) {
        val part = partFile(dir, file)
        var have = part.length()
        if (have > file.sizeBytes) {
            part.delete()
            have = 0
        }
        val request = Request.Builder().url(file.url).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
        client.newCall(request).execute().use { response ->
            if (response.code == 416) {
                part.delete()
                throw BadFileException()
            }
            if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
            // A server that ignores the range sends the whole file again.
            if (response.code != 206) have = 0
            val body = response.body ?: throw java.io.IOException("Empty response")
            RandomAccessFile(part, "rw").use { out ->
                out.setLength(have)
                out.seek(have)
                val buffer = ByteArray(64 * 1024)
                body.byteStream().use { input ->
                    var lastReport = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        have += n
                        if (have - lastReport >= REPORT_EVERY) {
                            lastReport = have
                            onBytes(have)
                        }
                    }
                }
            }
        }
        if (part.length() != file.sizeBytes) {
            part.delete()
            throw BadFileException()
        }
        if (!part.renameTo(File(dir, file.name))) throw java.io.IOException("Rename failed")
        onBytes(file.sizeBytes)
    }

    private fun done(dir: File, file: ModelFile): Long =
        if (File(dir, file.name).length() == file.sizeBytes) file.sizeBytes else partFile(dir, file).length().coerceAtMost(file.sizeBytes)

    private fun partFile(dir: File, file: ModelFile) = File(dir, "${file.name}.part")

    private fun initial(folder: String, files: List<ModelFile>): PackState =
        if (PluginFiles.isInstalled(context, folder, files)) PackState.Installed else partialState(folder, files) ?: PackState.NotInstalled

    private fun partialState(folder: String): PackState? {
        val files = AiPlugin.entries.firstOrNull { it.folder == folder }?.files
            ?: OcrScript.entries.firstOrNull { it.folder == folder }?.files
            ?: return null
        return partialState(folder, files)
    }

    /** Something downloaded but not finished: shown as paused so it can be resumed. */
    private fun partialState(folder: String, files: List<ModelFile>): PackState? {
        val dir = PluginFiles.dir(context, folder)
        val bytes = files.sumOf { done(dir, it) }
        return if (bytes > 0) PackState.Paused(bytes, files.sumOf { it.sizeBytes }) else null
    }

    private fun set(folder: String, state: PackState) = _states.update { it + (folder to state) }

    private class BadFileException : Exception()

    companion object {
        private const val TAG = "AiPluginManager"
        private const val REPORT_EVERY = 256L * 1024

        @Volatile
        private var INSTANCE: AiPluginManager? = null

        fun getInstance(context: Context): AiPluginManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: AiPluginManager(context.applicationContext).also { INSTANCE = it }
            }
    }
}
