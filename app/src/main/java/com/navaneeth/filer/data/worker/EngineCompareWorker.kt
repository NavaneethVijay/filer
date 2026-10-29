package com.navaneeth.filer.data.worker

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.navaneeth.filer.data.db.FilerDatabase
import com.navaneeth.filer.data.db.entity.EmbeddingKind
import com.navaneeth.filer.data.inference.EmbedderEngine
import com.navaneeth.filer.data.inference.RemoteEmbedder
import com.navaneeth.filer.data.inference.RemoteItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.sqrt

/** How close the Mac's vectors are to the phone's for the same inputs, and how fast each side is. */
data class EngineComparison(
    val macEngine: String,
    val textCount: Int,
    val textMean: Float,
    val textMin: Float,
    val phoneTextMs: Float,
    val macTextMs: Float,
    val imageCount: Int,
    val imageMean: Float,
    val imageMin: Float,
    val phoneImageMs: Float,
    val macImageMs: Float
)

/**
 * Developer mode, "Compare engines": embeds ~20 indexed passages (their exact prompts) and ~10
 * indexed photos on the phone and on the Mac, and reports cosine similarity and speed per kind.
 * Runs as its own one-off worker so model work never happens in a ViewModel.
 */
class EngineCompareWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    companion object {
        private const val WORK_NAME = "filer_engine_compare"
        private const val PASSAGES = 20
        private const val PHOTOS = 10

        const val KEY_ERROR = "error"
        const val ERROR_NO_MODEL = "no_model"
        const val ERROR_NO_MAC = "no_mac"
        const val ERROR_NOTHING_INDEXED = "nothing_indexed"
        const val ERROR_MAC_FAILED = "mac_failed"

        private const val KEY_ENGINE = "engine"
        private const val KEY_TEXT_COUNT = "text_count"
        private const val KEY_TEXT_MEAN = "text_mean"
        private const val KEY_TEXT_MIN = "text_min"
        private const val KEY_PHONE_TEXT_MS = "phone_text_ms"
        private const val KEY_MAC_TEXT_MS = "mac_text_ms"
        private const val KEY_IMAGE_COUNT = "image_count"
        private const val KEY_IMAGE_MEAN = "image_mean"
        private const val KEY_IMAGE_MIN = "image_min"
        private const val KEY_PHONE_IMAGE_MS = "phone_image_ms"
        private const val KEY_MAC_IMAGE_MS = "mac_image_ms"

        /** Starts a comparison now; returns its id to follow with WorkManager. */
        fun enqueue(context: Context): UUID {
            val request = OneTimeWorkRequestBuilder<EngineCompareWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
            return request.id
        }

        fun read(data: Data) = EngineComparison(
            macEngine = data.getString(KEY_ENGINE).orEmpty(),
            textCount = data.getInt(KEY_TEXT_COUNT, 0),
            textMean = data.getFloat(KEY_TEXT_MEAN, 0f),
            textMin = data.getFloat(KEY_TEXT_MIN, 0f),
            phoneTextMs = data.getFloat(KEY_PHONE_TEXT_MS, 0f),
            macTextMs = data.getFloat(KEY_MAC_TEXT_MS, 0f),
            imageCount = data.getInt(KEY_IMAGE_COUNT, 0),
            imageMean = data.getFloat(KEY_IMAGE_MEAN, 0f),
            imageMin = data.getFloat(KEY_IMAGE_MIN, 0f),
            phoneImageMs = data.getFloat(KEY_PHONE_IMAGE_MS, 0f),
            macImageMs = data.getFloat(KEY_MAC_IMAGE_MS, 0f)
        )

        fun cosine(a: FloatArray, b: FloatArray): Float {
            if (a.size != b.size || a.isEmpty()) return 0f
            var dot = 0.0
            var na = 0.0
            var nb = 0.0
            for (i in a.indices) {
                dot += a[i] * b[i]
                na += a[i] * a[i]
                nb += b[i] * b[i]
            }
            return if (na == 0.0 || nb == 0.0) 0f else (dot / (sqrt(na) * sqrt(nb))).toFloat()
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val embedder = EmbedderEngine(applicationContext)
        if (!embedder.isModelLoaded()) return@withContext failure(ERROR_NO_MODEL)
        val engine = RemoteEmbedder.available(force = true) ?: return@withContext failure(ERROR_NO_MAC)
        val dao = FilerDatabase.getInstance(applicationContext).embeddingDao()
        val thermal = ThermalGovernor(applicationContext)

        val prompts = dao.samplePassages(EmbeddingKind.TEXT.ordinal, PASSAGES).map { EmbedderEngine.formatDocument(it.text) }
        val photoUris = dao.samplePhotoUris(EmbeddingKind.IMAGE.ordinal, PHOTOS)
        if (prompts.isEmpty() && photoUris.isEmpty()) return@withContext failure(ERROR_NOTHING_INDEXED)

        try {
            // Passages: the phone one by one (after a warm-up run), the Mac in one request.
            val textSims = ArrayList<Float>()
            var phoneTextMs = 0L
            var macTextMs = 0f
            if (prompts.isNotEmpty()) {
                thermal.awaitCapacity()
                embedder.embedFormattedOnPhoneOnly(prompts.first()) ?: return@withContext failure(ERROR_NO_MODEL)
                val phone = prompts.map { prompt ->
                    thermal.awaitCapacity()
                    val start = SystemClock.elapsedRealtime()
                    embedder.embedFormattedOnPhoneOnly(prompt).also { phoneTextMs += SystemClock.elapsedRealtime() - start }
                }
                val mac = RemoteEmbedder.embed(prompts.map { RemoteItem.Text(it) })
                macTextMs = mac.roundTripMs / prompts.size.toFloat()
                phone.zip(mac.vectors).forEach { (p, m) -> if (p != null) textSims += cosine(p, m) }
            }

            // Photos: the same downscaled bitmap on both sides (PNG to the Mac).
            val imageSims = ArrayList<Float>()
            var phoneImageMs = 0L
            var macImageMs = 0L
            var warmedUp = false
            for (uri in photoUris) {
                val bitmap = embedder.decodeForEmbedding(Uri.parse(uri)) ?: continue
                thermal.awaitCapacity()
                if (!warmedUp) {
                    embedder.embedPhotoOnPhoneOnly(bitmap)
                    warmedUp = true
                }
                val start = SystemClock.elapsedRealtime()
                val phone = embedder.embedPhotoOnPhoneOnly(bitmap) ?: continue
                phoneImageMs += SystemClock.elapsedRealtime() - start
                val mac = RemoteEmbedder.embed(listOf(RemoteItem.Image(RemoteEmbedder.encodePng(bitmap))))
                macImageMs += mac.roundTripMs
                imageSims += cosine(phone, mac.vectors.first())
            }

            Result.success(
                workDataOf(
                    KEY_ENGINE to engine,
                    KEY_TEXT_COUNT to textSims.size,
                    KEY_TEXT_MEAN to textSims.averageOrZero(),
                    KEY_TEXT_MIN to (textSims.minOrNull() ?: 0f),
                    KEY_PHONE_TEXT_MS to if (textSims.isEmpty()) 0f else phoneTextMs / prompts.size.toFloat(),
                    KEY_MAC_TEXT_MS to macTextMs,
                    KEY_IMAGE_COUNT to imageSims.size,
                    KEY_IMAGE_MEAN to imageSims.averageOrZero(),
                    KEY_IMAGE_MIN to (imageSims.minOrNull() ?: 0f),
                    KEY_PHONE_IMAGE_MS to if (imageSims.isEmpty()) 0f else phoneImageMs / imageSims.size.toFloat(),
                    KEY_MAC_IMAGE_MS to if (imageSims.isEmpty()) 0f else macImageMs / imageSims.size.toFloat()
                )
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            failure(ERROR_MAC_FAILED)
        }
    }

    private fun failure(reason: String) = Result.failure(workDataOf(KEY_ERROR to reason))

    private fun List<Float>.averageOrZero(): Float = if (isEmpty()) 0f else average().toFloat()
}
