package com.navaneeth.filer.data.model

import android.content.Context
import android.os.Environment
import android.os.StatFs
import com.navaneeth.filer.data.worker.IndexingWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

sealed interface DownloadState {
    data object Idle : DownloadState
    data class Checking(val message: String) : DownloadState
    data class Downloading(
        val bytesRead: Long,
        val totalBytes: Long,
        val percent: Float,
        val speedMbSec: Float
    ) : DownloadState
    data object Paused : DownloadState
    data class Verifying(val percent: Float) : DownloadState
    data object Ready : DownloadState
    data class Error(val message: String) : DownloadState
}

class ModelDownloadManager(private val context: Context) {

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private val isPaused = AtomicBoolean(false)
    private val isCancelled = AtomicBoolean(false)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val modelsDir: File by lazy {
        File(context.filesDir, "models").apply { mkdirs() }
    }

    val modelFile: File by lazy {
        File(modelsDir, MODEL_FILENAME)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        if (isModelReady()) _downloadState.value = DownloadState.Ready
    }

    /**
     * Starts (or resumes) the download outside any screen's lifecycle. Once the model is in place,
     * every file is re-embedded with it, replacing the lightweight fallback vectors.
     */
    fun launchDownload() {
        val state = _downloadState.value
        if (state is DownloadState.Checking || state is DownloadState.Downloading || state is DownloadState.Verifying) return
        scope.launch {
            startDownload()
            if (_downloadState.value is DownloadState.Ready) {
                IndexingWorker.enqueue(context, reindexAll = true)
            }
        }
    }

    companion object {
        const val MODEL_FILENAME = "embeddinggemma-2-740m.litertlm"
        // Official Google EmbeddingGemma 2 LiteRT-LM release on Hugging Face
        const val DOWNLOAD_URL = "https://huggingface.co/litert-community/embeddinggemma-2-740m-litert-lm/resolve/main/embeddinggemma-2-740m.litertlm"
        const val EXPECTED_SIZE_BYTES = 484_622_336L // ~485 MB
        const val REQUIRED_STORAGE_BYTES = 1_000_000_000L // 1 GB free safety buffer

        @Volatile
        private var INSTANCE: ModelDownloadManager? = null

        fun getInstance(context: Context): ModelDownloadManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ModelDownloadManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    fun isModelReady(): Boolean {
        return modelFile.exists() && modelFile.length() > 10_000_000L
    }

    fun checkFreeStorage(): Long {
        val stat = StatFs(context.filesDir.path)
        return stat.availableBytes
    }

    suspend fun startDownload(): Unit = withContext(Dispatchers.IO) {
        if (isModelReady()) {
            _downloadState.value = DownloadState.Ready
            return@withContext
        }

        val freeSpace = checkFreeStorage()
        if (freeSpace < REQUIRED_STORAGE_BYTES) {
            _downloadState.value = DownloadState.Error("Insufficient storage. At least 1.0 GB free space is required.")
            return@withContext
        }

        isPaused.set(false)
        isCancelled.set(false)
        _downloadState.value = DownloadState.Checking("Contacting model server...")

        val partialFile = File(modelsDir, "$MODEL_FILENAME.part")
        val existingBytes = if (partialFile.exists()) partialFile.length() else 0L

        try {
            val requestBuilder = Request.Builder().url(DOWNLOAD_URL)
            if (existingBytes > 0) {
                requestBuilder.header("Range", "bytes=$existingBytes-")
            }

            val response = client.newCall(requestBuilder.build()).execute()
            if (!response.isSuccessful && response.code != 206) {
                // If Range not satisfiable, restart download
                if (response.code == 416) {
                    partialFile.delete()
                    startDownload()
                    return@withContext
                }
                _downloadState.value = DownloadState.Error("HTTP Error ${response.code}: ${response.message}")
                return@withContext
            }

            val body = response.body ?: throw IllegalStateException("Empty server response")
            val totalBytes = if (response.code == 206) {
                existingBytes + body.contentLength()
            } else {
                body.contentLength()
            }

            val inputStream = body.byteStream()
            val raf = RandomAccessFile(partialFile, "rw")
            if (response.code == 206) {
                raf.seek(existingBytes)
            } else {
                raf.setLength(0)
            }

            val buffer = ByteArray(64 * 1024)
            var bytesReadTotal = if (response.code == 206) existingBytes else 0L
            var lastTime = System.currentTimeMillis()
            var bytesSinceLastTime = 0L

            var bytes = inputStream.read(buffer)
            while (bytes != -1) {
                if (isCancelled.get()) {
                    raf.close()
                    partialFile.delete()
                    _downloadState.value = DownloadState.Idle
                    return@withContext
                }
                if (isPaused.get()) {
                    raf.close()
                    _downloadState.value = DownloadState.Paused
                    return@withContext
                }

                raf.write(buffer, 0, bytes)
                bytesReadTotal += bytes
                bytesSinceLastTime += bytes

                val currentTime = System.currentTimeMillis()
                val elapsed = currentTime - lastTime
                if (elapsed >= 500) {
                    val speed = (bytesSinceLastTime.toFloat() / (1024 * 1024)) / (elapsed.toFloat() / 1000f)
                    val percent = if (totalBytes > 0) bytesReadTotal.toFloat() / totalBytes else 0f
                    _downloadState.value = DownloadState.Downloading(
                        bytesRead = bytesReadTotal,
                        totalBytes = totalBytes,
                        percent = percent,
                        speedMbSec = speed
                    )
                    lastTime = currentTime
                    bytesSinceLastTime = 0
                }

                bytes = inputStream.read(buffer)
            }

            raf.close()

            // Download finished, rename to final file
            if (partialFile.renameTo(modelFile)) {
                _downloadState.value = DownloadState.Ready
            } else {
                _downloadState.value = DownloadState.Error("Failed to finalize model file on storage.")
            }

        } catch (e: Exception) {
            if (isCancelled.get()) {
                _downloadState.value = DownloadState.Idle
            } else if (isPaused.get()) {
                _downloadState.value = DownloadState.Paused
            } else {
                _downloadState.value = DownloadState.Error(e.localizedMessage ?: "Unknown download failure")
            }
        }
    }

    fun pauseDownload() {
        isPaused.set(true)
    }

    fun resumeDownload() {
        isPaused.set(false)
    }

    fun cancelDownload() {
        isCancelled.set(true)
        // Nothing is running to notice the flag when paused or failed, so discard the partial file here.
        val state = _downloadState.value
        if (state is DownloadState.Paused || state is DownloadState.Error || state is DownloadState.Idle) {
            File(modelsDir, "$MODEL_FILENAME.part").delete()
            _downloadState.value = DownloadState.Idle
        }
    }

    fun purgeModel() {
        modelFile.delete()
        File(modelsDir, "$MODEL_FILENAME.part").delete()
        _downloadState.value = DownloadState.Idle
    }
}
