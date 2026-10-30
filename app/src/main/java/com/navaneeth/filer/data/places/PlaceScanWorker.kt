package com.navaneeth.filer.data.places

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters

/** Reads where new photos were taken, in the background (light file reads; no model). */
class PlaceScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        PhotoPlaces.getInstance(applicationContext).scan()
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "photo_places_scan"

        /** No-op without the media-location permission. */
        fun enqueue(context: Context) {
            if (!PhotoPlaces.getInstance(context).hasPermission()) return
            val request = OneTimeWorkRequestBuilder<PlaceScanWorker>()
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
