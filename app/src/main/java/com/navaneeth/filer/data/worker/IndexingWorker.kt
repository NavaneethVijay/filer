package com.navaneeth.filer.data.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.navaneeth.filer.MainActivity
import com.navaneeth.filer.R
import com.navaneeth.filer.data.preferences.PreferencesManager
import com.navaneeth.filer.data.repository.IndexProgress
import com.navaneeth.filer.data.repository.IndexRepository
import com.navaneeth.filer.ui.util.formatTimeLeft
import java.util.concurrent.TimeUnit

class IndexingWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val CHANNEL_ID = "filer_indexing_channel"
        const val NOTIFICATION_ID = 1001
        const val WORK_NAME = "filer_background_indexing"
        const val BULK_THRESHOLD = 15
        const val KEY_USER_REQUESTED = "user_requested"

        /**
         * Queues an indexing pass.
         *
         * Battery & Charging rules:
         * - User explicit requests ("Index now") run immediately / expedited without caps.
         * - Background passes or charging-only preference start as soon as connected to power.
         */
        fun enqueue(
            context: Context,
            reindexAll: Boolean = false,
            userRequested: Boolean = false,
            requireCharging: Boolean = false
        ) {
            val prefs = PreferencesManager(context)
            if (reindexAll) prefs.isFullReindexPending = true

            val builder = OneTimeWorkRequestBuilder<IndexingWorker>()
                .setInputData(
                    androidx.work.Data.Builder()
                        .putBoolean(KEY_USER_REQUESTED, userRequested)
                        .build()
                )

            if (userRequested) {
                builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            } else {
                val mustCharge = requireCharging || prefs.isFullReindexPending || prefs.isChargingOnly
                val constraints = Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .setRequiresCharging(mustCharge)
                    .build()
                builder.setConstraints(constraints)
            }

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                builder.build()
            )
        }

        /**
         * Developer mode, "Index now via Mac": turns Mac indexing on and starts a full pass right
         * away, without waiting for the charger or idle (a pending rebuild happens in this pass too).
         */
        fun enqueueViaMac(context: Context): Boolean {
            PreferencesManager(context).isIndexWithMac = true
            // A running pass checks for the Mac before every file, so it switches over by itself;
            // restarting it would only throw away the file in progress.
            if (IndexRepository.getInstance(context).progress.value.isRunning) return false
            enqueue(context, userRequested = true)
            return true
        }

        private const val PERIODIC_WORK_NAME = "filer_periodic_sync"

        /**
         * Every 6 hours, checks for new or changed files and embeds them.
         * Scheduled for charging periods to protect battery.
         */
        fun schedulePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .setRequiresCharging(true)
                .build()

            val request = PeriodicWorkRequestBuilder<IndexingWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notif = buildNotification(
            title = "Preparing your files for search…",
            content = "Indexing files in background",
            processed = 0,
            total = 0,
            isPaused = false,
            isThrottled = false
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notif)
        }
    }

    override suspend fun doWork(): Result {
        val repository = IndexRepository.getInstance(applicationContext)
        val notificationManager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Smart search preparation",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows persistent progress while Filer prepares your files for search"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val initialNotif = buildNotification(
            title = "Preparing your files for search…",
            content = "Starting indexing pass",
            processed = 0,
            total = 0,
            isPaused = false,
            isThrottled = false
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setForeground(ForegroundInfo(NOTIFICATION_ID, initialNotif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
            } else {
                setForeground(ForegroundInfo(NOTIFICATION_ID, initialNotif))
            }
        } catch (_: Exception) {
            // Out of quota fallback
        }

        // Keep CPU awake while minimized or locked so background indexing isn't suspended by the OS kernel
        val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "filer:indexing_worker").apply {
            setReferenceCounted(false)
        }
        try {
            wakeLock.acquire(15 * 60 * 1000L) // 15-minute rolling window
        } catch (e: Exception) {
            Log.w("IndexingWorker", "WakeLock acquire: ${e.message}")
        }

        var lastNotificationTime = 0L

        return try {
            val onProgress: (IndexProgress) -> Unit = { progress ->
                // Refresh WakeLock while making progress so lock-screen sleep never pauses the worker
                try {
                    wakeLock.acquire(15 * 60 * 1000L)
                } catch (_: Exception) {}
                val now = SystemClock.elapsedRealtime()
                // Throttle notification updates to at most once every 600ms unless state changed
                if (progress.totalCount > 0 && (now - lastNotificationTime >= 600L || progress.isPaused || progress.isThermalThrottled)) {
                    lastNotificationTime = now
                    val content = when {
                        progress.isPaused -> "Paused · Tap to resume"
                        progress.viaMac -> applicationContext.getString(R.string.via_mac_rate, progress.itemsPerSec)
                        progress.isThermalThrottled -> "Cooling down phone · Paused temporarily"
                        // Time left first: the file name is truncated in the collapsed notification.
                        progress.currentFileName.isNotBlank() ->
                            formatTimeLeft(applicationContext, progress.etaSeconds) ?: progress.currentFileName
                        else -> "Processing smart search folders"
                    }

                    val updatedNotif = buildNotification(
                        title = if (progress.viaMac && !progress.isPaused) {
                            applicationContext.getString(R.string.via_mac_notification_title, progress.processedCount, progress.totalCount)
                        } else {
                            "${progress.processedCount} of ${progress.totalCount} files ready"
                        },
                        content = content,
                        processed = progress.processedCount,
                        total = progress.totalCount,
                        isPaused = progress.isPaused,
                        isThrottled = progress.isThermalThrottled
                    )

                    try {
                        notificationManager.notify(NOTIFICATION_ID, updatedNotif)
                    } catch (_: Throwable) {}
                }
            }

            val isUserRequested = inputData.getBoolean(KEY_USER_REQUESTED, false)
            val prefs = PreferencesManager(applicationContext)
            repository.runFullIndex(
                reindexAll = prefs.isFullReindexPending,
                isUserRequested = isUserRequested,
                onReset = { prefs.isFullReindexPending = false },
                onProgress = onProgress
            )
            Result.success()
        } catch (t: Throwable) {
            Result.failure()
        } finally {
            try {
                if (wakeLock.isHeld) {
                    wakeLock.release()
                }
            } catch (_: Exception) {}
            notificationManager.cancel(NOTIFICATION_ID)
        }
    }

    private fun buildNotification(
        title: String,
        content: String,
        processed: Int,
        total: Int,
        isPaused: Boolean,
        isThrottled: Boolean
    ): Notification {
        val isIndeterminate = total <= 0

        // Tap notification to open IndexStatusScreen
        val openIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_NAVIGATE_TO, MainActivity.DEST_INDEX_STATUS)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setSilent(true)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent)
            .setProgress(total.coerceAtLeast(100), processed, isIndeterminate)

        // Action Buttons: Pause/Resume and Stop
        if (isPaused) {
            val resumeIntent = Intent(applicationContext, IndexNotificationReceiver::class.java).apply {
                action = IndexNotificationReceiver.ACTION_RESUME
            }
            val resumePending = PendingIntent.getBroadcast(
                applicationContext,
                1,
                resumeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_media_play, "Resume", resumePending)
        } else {
            val pauseIntent = Intent(applicationContext, IndexNotificationReceiver::class.java).apply {
                action = IndexNotificationReceiver.ACTION_PAUSE
            }
            val pausePending = PendingIntent.getBroadcast(
                applicationContext,
                2,
                pauseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_media_pause, "Pause", pausePending)
        }

        val stopIntent = Intent(applicationContext, IndexNotificationReceiver::class.java).apply {
            action = IndexNotificationReceiver.ACTION_STOP
        }
        val stopPending = PendingIntent.getBroadcast(
            applicationContext,
            3,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPending)

        return builder.build()
    }
}
