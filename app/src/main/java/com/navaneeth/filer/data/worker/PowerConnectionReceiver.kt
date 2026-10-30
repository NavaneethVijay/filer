package com.navaneeth.filer.data.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.navaneeth.filer.data.repository.IndexRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Listens for charger connection (ACTION_POWER_CONNECTED) so background indexing
 * kicks off immediately without waiting for Android's 30+ minute device idle timers.
 */
class PowerConnectionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_POWER_CONNECTED) {
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val repo = IndexRepository.getInstance(context)
                    if (repo.hasPendingFiles()) {
                        Log.i("PowerConnectionReceiver", "Charger connected with pending files. Starting charging pass.")
                        IndexingWorker.enqueue(context, requireCharging = true)
                    }
                } catch (t: Throwable) {
                    Log.w("PowerConnectionReceiver", "Failed to check pending files: ${t.message}")
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
