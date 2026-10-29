package com.navaneeth.filer.data.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.navaneeth.filer.data.ai.AiFeatures
import com.navaneeth.filer.data.ai.AiPlugin
import com.navaneeth.filer.data.ai.AiPluginManager
import com.navaneeth.filer.data.ai.PluginData
import com.navaneeth.filer.data.ai.TextSource
import com.navaneeth.filer.data.media.MediaLibrary
import com.navaneeth.filer.data.preferences.PreferencesManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.TimeUnit

/** Progress of reading text in photos already on the phone. */
data class PhotoTextScan(val running: Boolean = false, val done: Int = 0, val total: Int = 0)

/**
 * Text in photos, optional background part ("Read photos you already have", off by default):
 * reads every photo not yet read, newest first, only while the phone charges, resting whenever
 * [ThermalGovernor] says so. Viewing a photo still reads it straight away without this.
 */
class PhotoTextWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val prefs = PreferencesManager(context)
        val manager = AiPluginManager.getInstance(context)
        if (!prefs.readPhotosInBackground || !manager.isReady(AiPlugin.PHOTO_TEXT)) return Result.success()
        val features = AiFeatures.getInstance(context)
        val thermal = ThermalGovernor(context)
        val done = PluginData.getInstance(context).pathsWithText(TextSource.OCR)
        val photos = MediaLibrary(context).photos().sortedByDescending { it.second }
        val todo = photos.filter { it.first !in done }
        _progress.value = PhotoTextScan(running = true, done = photos.size - todo.size, total = photos.size)
        try {
            for ((index, photo) in todo.withIndex()) {
                if (isStopped || !prefs.readPhotosInBackground || !manager.isReady(AiPlugin.PHOTO_TEXT)) break
                thermal.awaitCapacity()
                val file = File(photo.first)
                if (file.isFile && features.canReadPhotoText(file)) runCatching { features.readPhotoText(file) }
                _progress.value = PhotoTextScan(running = true, done = photos.size - todo.size + index + 1, total = photos.size)
            }
        } finally {
            _progress.value = _progress.value.copy(running = false)
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "photo_text_scan"
        private const val PERIODIC_NAME = "photo_text_scan_periodic"

        private val _progress = MutableStateFlow(PhotoTextScan())
        val progress: StateFlow<PhotoTextScan> = _progress.asStateFlow()

        private val constraints
            get() = Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true).build()

        /** Starts (next time the phone charges) and keeps catching up daily with new photos. */
        fun schedule(context: Context) {
            val work = WorkManager.getInstance(context)
            work.enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<PhotoTextWorker>().setConstraints(constraints).build()
            )
            work.enqueueUniquePeriodicWork(
                PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<PhotoTextWorker>(1, TimeUnit.DAYS).setConstraints(constraints).build()
            )
        }

        fun cancel(context: Context) {
            val work = WorkManager.getInstance(context)
            work.cancelUniqueWork(WORK_NAME)
            work.cancelUniqueWork(PERIODIC_NAME)
        }
    }
}
