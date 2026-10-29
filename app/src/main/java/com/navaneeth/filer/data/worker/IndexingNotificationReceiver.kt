package com.navaneeth.filer.data.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.navaneeth.filer.data.repository.IndexRepository

/**
 * Handles action button taps directly from the persistent foreground indexing notification:
 * Pause, Resume, or Stop.
 */
class IndexNotificationReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_PAUSE = "com.navaneeth.filer.action.INDEX_PAUSE"
        const val ACTION_RESUME = "com.navaneeth.filer.action.INDEX_RESUME"
        const val ACTION_STOP = "com.navaneeth.filer.action.INDEX_STOP"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val repo = IndexRepository.getInstance(context)
        when (intent.action) {
            ACTION_PAUSE -> repo.pause()
            ACTION_RESUME -> repo.resume()
            ACTION_STOP -> {
                repo.cancel()
                IndexingWorker.cancel(context)
            }
        }
    }
}
