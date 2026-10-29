package com.navaneeth.filer.data.inference

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedder
import com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedderOptions
import com.navaneeth.filer.data.model.ModelDownloadManager
import com.navaneeth.filer.data.preferences.PreferencesManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.math.sqrt

/** Which engine produced a vector (stored with it in `embeddings.engine`). */
object EngineTag {
    const val PHONE_GPU = "phone-gpu"
    const val PHONE_CPU = "phone-cpu"
    /**
     * Hash-based stand-in from older versions, when the model couldn't run; files that still have
     * such vectors are re-queued. Indexing no longer writes it (queries may still use one).
     */
    const val FALLBACK = "fallback"
    const val MAC_GPU = "mac-gpu"
    const val MAC_CPU = "mac-cpu"
}

/** A vector and the engine that made it. */
class Embedding(val vector: FloatArray, val engine: String)

/** One file's vectors for indexing: all real and from one engine, or a reason there are none. */
sealed interface FileVectors {
    class Ready(val embeddings: List<Embedding>) : FileVectors
    /** The Mac didn't return every vector; none of its results are kept. */
    data object MacFailed : FileVectors
    /** The phone's model can't run at all (missing or failed to load). */
    data object ModelUnavailable : FileVectors
    /** The model runs but gave no vector for this file's input. */
    data object NoVector : FileVectors
}

/**
 * On-Device Multimodal Embedding Engine
 *
 * Implements Google DeepMind's EmbeddingGemma 2 model via MediaPipe UniversalEmbedder.
 * Maps text documents, PDF page chunks, code, and photos into one unified 768-dimensional vector space.
 */
class EmbedderEngine(private val context: Context) {

    companion object {
        const val TAG = "EmbedderEngine"
        const val EMBEDDING_DIM = 768

        // EmbeddingGemma 2 task prompts (developers.google.com/edge/litert-lm/embedding_models).
        const val QUERY_PREFIX = "task: search query | text: "
        const val DOC_PREFIX = "task: search result | text: "
        private const val MAX_DOC_CHARS = 2000
        private const val MAX_QUERY_CHARS = 500

        /** The exact model input for a passage (also what the Mac is sent). */
        fun formatDocument(text: String): String = DOC_PREFIX + text.trim().take(MAX_DOC_CHARS)

        /** The exact model input for a search query. */
        fun formatQuery(query: String): String = QUERY_PREFIX + query.trim().take(MAX_QUERY_CHARS)

        // V2: EmbeddingGemma 2 prompts (no title in the document prompt).
        const val MODEL_VERSION = "EMBEDDING_GEMMA_2_V2"
        const val FALLBACK_MODEL_VERSION = "FALLBACK_HASH_V1"

        init {
            try {
                System.loadLibrary("mediapipe_tasks_jni")
            } catch (t: Throwable) {
                Log.w(TAG, "mediapipe_tasks_jni preload: ${t.message}")
            }
            try {
                System.loadLibrary("litertlm_jni")
            } catch (t: Throwable) {
                Log.w(TAG, "litertlm_jni preload: ${t.message}")
            }
        }

        @Volatile
        private var sharedEmbedderInstance: UniversalEmbedder? = null
        private var initializationAttempted = false

        /**
         * Global inference lock. Ensures strictly ONE model inference runs at a time
         * across indexing and user interactive search to prevent JNI crashes and thermal spikes.
         */
        private val inferenceMutex = Mutex()

        /**
         * Runs [block] while no other model runs on the phone. The AI plugins take this around each
         * unit of work (one photo, one 30-second window, one answer), so smart search and plugins
         * never run models at the same time.
         */
        suspend fun <T> exclusive(block: suspend () -> T): T = inferenceMutex.withLock {
            _isInferring.value = true
            try {
                block()
            } finally {
                _isInferring.value = false
            }
        }

        private val _isInferring = MutableStateFlow(false)
        val isInferring: StateFlow<Boolean> = _isInferring.asStateFlow()

        @Volatile
        var activeDelegateName: String = "Auto"
            private set

        /** Whether the loaded model runs text on the GPU (vision always runs on the CPU). */
        @Volatile
        private var textOnGpu: Boolean = false
    }

    private val modelManager = ModelDownloadManager(context)

    val isInferring: StateFlow<Boolean> get() = Companion.isInferring
    val activeDelegateName: String get() = Companion.activeDelegateName

    fun isModelLoaded(): Boolean {
        return modelManager.isModelReady()
    }

    val activeModelVersion: String
        get() = if (isModelLoaded()) MODEL_VERSION else FALLBACK_MODEL_VERSION

    /**
     * Lazily loads and initializes Google's MediaPipe UniversalEmbedder with EmbeddingGemma 2.
     * Negotiates GPU acceleration with graceful CPU fallback.
     */
    @Synchronized
    private fun getOrInitEmbedder(): UniversalEmbedder? {
        if (sharedEmbedderInstance != null) return sharedEmbedderInstance
        if (initializationAttempted && sharedEmbedderInstance == null) return null

        val modelFile = modelManager.modelFile
        if (!modelFile.exists() || modelFile.length() < 100_000_000L) {
            return null
        }

        initializationAttempted = true
        return try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(modelFile.absolutePath)
                .build()

            val cacheDir = File(context.cacheDir, "mediapipe").apply { mkdirs() }.absolutePath

            val cpuOnly = PreferencesManager(context).acceleratorMode == PreferencesManager.ACCELERATOR_CPU
            if (!cpuOnly) {
                // Tier 1: Text on GPU, Vision on CPU.
                // EmbeddingGemma 2's vision sub-graph fails to compile on mobile GPU delegates,
                // but text embeddings run smoothly, fast and cool on the GPU/NPU.
                try {
                    val options = UniversalEmbedderOptions.builder()
                        .setBaseOptions(baseOptions)
                        .setTextDelegate(Delegate.GPU)
                        .setVisionDelegate(Delegate.CPU)
                        .setL2Normalize(true)
                        .setCacheDir(cacheDir)
                        .build()

                    val embedder = UniversalEmbedder.createFromOptions(context, options)
                    Companion.activeDelegateName = "GPU (Text) + CPU (Vision)"
                    Companion.textOnGpu = true
                    sharedEmbedderInstance = embedder
                    return embedder
                } catch (gpuError: Throwable) {
                    Log.w(TAG, "GPU text delegate initialization failed, falling back to CPU: ${gpuError.message}")
                }
            }

            // Tier 2: Full CPU Fallback (or if user selected CPU only in settings)
            val optionsCpu = UniversalEmbedderOptions.builder()
                .setBaseOptions(baseOptions)
                .setTextDelegate(Delegate.CPU)
                .setVisionDelegate(Delegate.CPU)
                .setL2Normalize(true)
                .setCacheDir(cacheDir)
                .build()

            val embedder = UniversalEmbedder.createFromOptions(context, optionsCpu)
            val modeLabel = if (cpuOnly) "CPU (Manual)" else "CPU (Fallback)"
            Companion.activeDelegateName = modeLabel
            Companion.textOnGpu = false
            sharedEmbedderInstance = embedder
            embedder
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load EmbeddingGemma 2 model: ${t.message}", t)
            null
        }
    }

    /** The local engine that produces text vectors: GPU unless it fell back (or the user chose CPU). */
    private val localTextEngine: String get() = if (Companion.textOnGpu) EngineTag.PHONE_GPU else EngineTag.PHONE_CPU

    /** Runs [block] on the phone's model, strictly one inference at a time. */
    private suspend fun <T> onPhone(block: () -> T): T = exclusive { block() }

    private fun localText(formattedInput: String): FloatArray? {
        val embedder = getOrInitEmbedder() ?: return null
        return try {
            embedder.embedText(formattedInput).embeddings().firstOrNull()?.floatEmbedding()?.takeIf { it.size == EMBEDDING_DIM }
        } catch (t: Throwable) {
            Log.w(TAG, "UniversalEmbedder text inference error: ${t.message}")
            null
        }
    }

    private fun localImage(bitmap: Bitmap): FloatArray? {
        val embedder = getOrInitEmbedder() ?: return null
        return try {
            embedder.embedImage(BitmapImageBuilder(bitmap).build()).embeddings().firstOrNull()?.floatEmbedding()?.takeIf { it.size == EMBEDDING_DIM }
        } catch (t: Throwable) {
            Log.w(TAG, "UniversalEmbedder image inference error: ${t.message}")
            null
        }
    }

    /** One document prompt on the phone (null if the model can't run or gives no vector). */
    private suspend fun embedFormattedOnPhone(formattedInput: String): Embedding? = onPhone {
        localText(formattedInput)?.let { Embedding(it, localTextEngine) }
    }

    /** Whether the phone's model is loaded (or can be): false means no real vector can be made. */
    private suspend fun phoneModelUsable(): Boolean = onPhone { getOrInitEmbedder() != null }

    /**
     * Embeds all passages of one file for indexing, all-or-nothing on one engine. With [useMac],
     * every passage goes to the Mac (as few requests as possible, same prompt strings as on the
     * phone); if any request fails, nothing from the Mac is kept ([FileVectors.MacFailed]) and the
     * caller retries the file later. Otherwise they run on the phone, with [beforeEachOnPhone]
     * (thermal pacing) before each run. Never returns stand-in vectors.
     */
    suspend fun embedDocuments(
        texts: List<String>,
        useMac: Boolean,
        beforeEachOnPhone: suspend () -> Unit = {}
    ): FileVectors {
        val inputs = texts.map(::formatDocument)
        val results = ArrayList<Embedding>(inputs.size)
        if (useMac) {
            for (batch in inputs.chunked(RemoteEmbedder.MAX_BATCH)) {
                results += tryMac(batch.map { RemoteItem.Text(it) }) ?: return FileVectors.MacFailed
            }
            return FileVectors.Ready(results)
        }
        for (input in inputs) {
            beforeEachOnPhone()
            results += embedFormattedOnPhone(input) ?: return phoneFailure()
        }
        return FileVectors.Ready(results)
    }

    private suspend fun phoneFailure(): FileVectors =
        if (phoneModelUsable()) FileVectors.NoVector else FileVectors.ModelUnavailable

    private suspend fun tryMac(items: List<RemoteItem>): List<Embedding>? = try {
        val result = RemoteEmbedder.embed(items)
        result.vectors.map { Embedding(it, result.engine) }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /**
     * Embeds a natural language search query. Always on the phone, so search works unplugged:
     * "task: search query | text: {query}"
     */
    suspend fun embedQuery(query: String): FloatArray {
        val formattedInput = formatQuery(query)
        return onPhone { localText(formattedInput) ?: generateFallbackVector(formattedInput) }
    }

    /**
     * Decodes and downsizes a photo for the model. Separate so the next photo can decode while one
     * embeds. Doesn't load the model (when indexing via the Mac the phone's model may never be needed).
     */
    fun decodeForEmbedding(uri: Uri): Bitmap? {
        if (!isModelLoaded()) return null
        val bitmap = loadDownscaledBitmap(uri, targetSize = 512) ?: return null
        return if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: bitmap
    }

    /**
     * Embeds an already-decoded photo for indexing. With [useMac], the exact same bitmap goes to
     * the Mac as a lossless PNG and only the Mac's vector is accepted ([FileVectors.MacFailed] on
     * any problem, so the caller retries later); otherwise it runs on the phone (vision always on
     * the CPU). Never returns a stand-in vector.
     */
    suspend fun embedPhoto(bitmap: Bitmap, useMac: Boolean): FileVectors {
        if (useMac) {
            val png = withContext(Dispatchers.Default) { RemoteEmbedder.encodePng(bitmap) }
            val remote = tryMac(listOf(RemoteItem.Image(png)))?.singleOrNull() ?: return FileVectors.MacFailed
            return FileVectors.Ready(listOf(remote))
        }
        val local = onPhone { localImage(bitmap) } ?: return phoneFailure()
        return FileVectors.Ready(listOf(Embedding(local, EngineTag.PHONE_CPU)))
    }

    /** Developer comparison: a formatted prompt on the phone's model only (null if it can't run). */
    suspend fun embedFormattedOnPhoneOnly(formattedInput: String): FloatArray? = onPhone { localText(formattedInput) }

    /** Developer comparison: a decoded photo on the phone's model only (null if it can't run). */
    suspend fun embedPhotoOnPhoneOnly(bitmap: Bitmap): FloatArray? = onPhone { localImage(bitmap) }

    private fun loadDownscaledBitmap(uri: Uri, targetSize: Int): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
            var scale = 1
            while (options.outWidth / scale / 2 >= targetSize && options.outHeight / scale / 2 >= targetSize) {
                scale *= 2
            }
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = scale
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * High-fidelity 768-dimensional fallback tokenizer.
     * Generates normalized, deterministic vectors when the model is not yet downloaded,
     * ensuring the app remains stable and testable in all environments.
     */
    private fun generateFallbackVector(text: String): FloatArray {
        val vector = FloatArray(EMBEDDING_DIM)
        val tokens = text.lowercase().split(Regex("[^a-zA-Z0-9]+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return vector

        for (token in tokens) {
            val hash = token.hashCode()
            for (i in 0 until 8) {
                val idx = Math.floorMod((hash * (i + 1) * 31) xor (i * 101), EMBEDDING_DIM)
                val sign = if ((hash shr i) and 1 == 0) 1.0f else -1.0f
                vector[idx] += sign * (1.0f / token.length.coerceAtLeast(3))
            }
        }

        // L2 normalize
        var sumSquares = 0f
        for (v in vector) sumSquares += v * v
        val norm = sqrt(sumSquares)
        if (norm > 0f) {
            for (i in vector.indices) vector[i] /= norm
        }

        return vector
    }

    fun cosineSimilarity(v1: FloatArray, v2: FloatArray): Float {
        if (v1.size != v2.size || v1.isEmpty()) return 0f
        var dot = 0f
        for (i in v1.indices) {
            dot += v1[i] * v2[i]
        }
        return dot.coerceIn(-1.0f, 1.0f)
    }
}
