package com.navaneeth.filer.data.ai

/**
 * How plugin text sits in the search index: as ordinary passages of the file, marked by a reserved
 * page number so they can be replaced or removed without touching the file's own passages or its
 * photo vector. No schema change to filer.db.
 */
object PluginIndex {
    const val PAGE_OCR = -1001
    const val PAGE_TRANSCRIPT = -1002

    fun pageFor(source: String): Int = if (source == TextSource.TRANSCRIPT) PAGE_TRANSCRIPT else PAGE_OCR

    /** Real page numbers are shown; plugin passages have none of their own. */
    fun isPluginPage(page: Int?): Boolean = page != null && page <= PAGE_OCR
}
