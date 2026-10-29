package com.navaneeth.filer

import android.app.Application
import com.navaneeth.filer.data.vector.VectorIndex
import com.navaneeth.filer.data.worker.IndexingWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class FilerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize PdfBox-Android font and cmap loaders
        PDFBoxResourceLoader.init(this)
        IndexingWorker.schedulePeriodic(this)

        // Ensure zero telemetry leaks: clean up any legacy datatransport events db
        try {
            deleteDatabase("com.google.android.datatransport.events")
        } catch (_: Throwable) {}
    }

    /** The search matrix can be tens of MB; drop it once the UI is hidden and rebuild on the next search. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            CoroutineScope(Dispatchers.Default).launch { VectorIndex.release() }
        }
    }
}
