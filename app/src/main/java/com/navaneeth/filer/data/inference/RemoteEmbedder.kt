package com.navaneeth.filer.data.inference

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.navaneeth.filer.data.model.ModelDownloadManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.concurrent.TimeUnit

/** What the Mac embedder says about itself (`GET /health`). */
@Serializable
data class RemoteHealth(val model: String = "", val modelBytes: Long = 0, val engine: String = "", val dim: Int = 0)

/** Connection to the Mac, for the Developer settings status line. */
sealed interface RemoteStatus {
    data object Unknown : RemoteStatus
    data object Unreachable : RemoteStatus
    /** Answers, but runs a different model file (or dimension): its vectors wouldn't match. */
    data class WrongModel(val health: RemoteHealth) : RemoteStatus
    data class Connected(val health: RemoteHealth) : RemoteStatus
}

/** One model input sent to the Mac: the exact prompt string, or an image's PNG bytes. */
sealed interface RemoteItem {
    data class Text(val text: String) : RemoteItem
    class Image(val png: ByteArray) : RemoteItem
}

/** Vectors in request order, plus how long the Mac spent and the whole round trip took. */
class RemoteResult(val vectors: List<FloatArray>, val engine: String, val serverMs: Double, val roundTripMs: Long)

/**
 * Developer mode: runs indexing inference on a USB-connected Mac (`tools/mac_embedder`, reached at
 * 127.0.0.1 through `adb reverse`). Only used when the Mac runs the same model file as the phone;
 * any failure marks it unavailable for a while so indexing quietly continues on the phone.
 * Search queries never come here.
 */
object RemoteEmbedder {
    private const val TAG = "RemoteEmbedder"
    const val BASE_URL = "http://127.0.0.1:9379"
    const val MAX_BATCH = 64
    private const val HEALTH_CACHE_MS = 10_000L
    private const val BACKOFF_MS = 5_000L
    /** Health checks that must fail in a row before the Mac counts as gone (the USB tunnel hiccups). */
    private const val MISSES_BEFORE_UNREACHABLE = 3
    val MAC_ENGINES = setOf(EngineTag.MAC_GPU, EngineTag.MAC_CPU)

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonType = "application/json".toMediaType()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** One request at a time, so batches come back in the order they were sent. */
    private val requestLock = Mutex()

    @Volatile private var checkedAt = 0L
    @Volatile private var unavailableUntil = 0L
    @Volatile private var misses = 0

    private val _status = MutableStateFlow<RemoteStatus>(RemoteStatus.Unknown)
    val status: StateFlow<RemoteStatus> = _status.asStateFlow()

    /** The Mac's vectors are only comparable if it runs the very same model file. */
    fun isAcceptable(health: RemoteHealth, expectedModelBytes: Long = ModelDownloadManager.EXPECTED_SIZE_BYTES): Boolean =
        health.dim == EmbedderEngine.EMBEDDING_DIM && health.modelBytes == expectedModelBytes && health.engine in MAC_ENGINES

    /** Decodes one vector: base64 of little-endian float32 values. */
    fun decodeVector(base64: String): FloatArray {
        val bytes = Base64.getDecoder().decode(base64)
        require(bytes.size % 4 == 0) { "vector byte length" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(buffer.remaining()).also { buffer.get(it) }
    }

    /** Lossless, so the Mac sees exactly the pixels the phone's model would. */
    fun encodePng(bitmap: Bitmap): ByteArray =
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }

    /**
     * The Mac's engine name if it's usable right now. Cached for ~10 s; after a failure it isn't
     * asked again for ~30 s unless [force] (the settings screen polling while it's open).
     */
    suspend fun available(force: Boolean = false): String? {
        val now = SystemClock.elapsedRealtime()
        if (!force) {
            if (now < unavailableUntil) return null
            if (now - checkedAt < HEALTH_CACHE_MS) return (status.value as? RemoteStatus.Connected)?.health?.engine
        }
        val health = fetchHealth()
        checkedAt = SystemClock.elapsedRealtime()
        misses = if (health == null) misses + 1 else 0
        // A single missed check keeps the last good state; only a run of misses means the Mac is gone.
        if (health == null && misses < MISSES_BEFORE_UNREACHABLE && _status.value is RemoteStatus.Connected) {
            return (_status.value as RemoteStatus.Connected).health.engine
        }
        _status.value = when {
            health == null -> RemoteStatus.Unreachable
            isAcceptable(health) -> RemoteStatus.Connected(health)
            else -> RemoteStatus.WrongModel(health)
        }
        val connected = _status.value as? RemoteStatus.Connected
        unavailableUntil = if (connected == null) checkedAt + BACKOFF_MS else 0L
        return connected?.health?.engine
    }

    private suspend fun fetchHealth(): RemoteHealth? = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url("$BASE_URL/health").get().build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                json.decodeFromString(RemoteHealth.serializer(), response.body?.string().orEmpty())
            }
        } catch (_: Exception) {
            null
        }
    }

    @Serializable
    private data class WireItem(val text: String? = null, val image: String? = null)

    @Serializable
    private data class WireRequest(val items: List<WireItem>)

    @Serializable
    private data class WireResponse(val vectors: List<String> = emptyList(), val ms: Double = 0.0)

    /**
     * Embeds up to [MAX_BATCH] items on the Mac. Throws on any problem (and marks the Mac
     * unavailable for a while); the caller then embeds on the phone.
     */
    suspend fun embed(items: List<RemoteItem>): RemoteResult = withContext(Dispatchers.IO) {
        require(items.size in 1..MAX_BATCH)
        val engine = (status.value as? RemoteStatus.Connected)?.health?.engine ?: error("Mac not connected")
        val body = json.encodeToString(
            WireRequest.serializer(),
            WireRequest(items.map {
                when (it) {
                    is RemoteItem.Text -> WireItem(text = it.text)
                    is RemoteItem.Image -> WireItem(image = Base64.getEncoder().encodeToString(it.png))
                }
            })
        )
        // ~56 ms per passage and ~200 ms per photo on the Mac; leave room for a slow first call.
        val images = items.count { it is RemoteItem.Image }
        val readTimeoutMs = 5_000L + (items.size - images) * 250L + images * 1_500L
        val call = client.newBuilder().readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS).build()
            .newCall(Request.Builder().url("$BASE_URL/embed").post(body.toRequestBody(jsonType)).build())
        try {
            requestLock.withLock {
                val start = SystemClock.elapsedRealtime()
                // One retry: a dropped tunnel connection usually works straight away on a new one.
                val response = try {
                    call.execute()
                } catch (e: java.io.IOException) {
                    call.clone().execute()
                }
                response.use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val parsed = json.decodeFromString(WireResponse.serializer(), response.body?.string().orEmpty())
                    val vectors = parsed.vectors.map(::decodeVector)
                    check(vectors.size == items.size && vectors.all { it.size == EmbedderEngine.EMBEDDING_DIM }) { "bad vectors" }
                    RemoteResult(vectors, engine, parsed.ms, SystemClock.elapsedRealtime() - start)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            markFailed()
            // Never the inputs: they're file content.
            Log.w(TAG, "Mac embedding failed, using the phone: ${e.javaClass.simpleName}")
            throw e
        }
    }

    private fun markFailed() {
        unavailableUntil = SystemClock.elapsedRealtime() + BACKOFF_MS
        checkedAt = 0L
        _status.value = RemoteStatus.Unreachable
    }
}
