package com.navaneeth.filer.data.model

enum class SourceType {
    /** A folder on local storage chosen for smart search; its uri is the absolute path. */
    LOCAL_FOLDER,

    /** Every photo, PDF and text file on every storage volume ("Whole phone"); uri is [DEVICE_SOURCE_URI]. */
    DEVICE
}

const val DEVICE_SOURCE_URI = "device://all"


enum class IndexStatus {
    PENDING,
    INDEXED,
    FAILED,
    SKIPPED,
    /** In Filer's Recycle bin: hidden from search, vectors kept so Undo/Restore needn't re-embed. */
    GONE
}

enum class FileTypeFilter(val label: String, val extensions: List<String>) {
    ALL("All", emptyList()),
    PDF("PDF", listOf("pdf")),
    IMAGES("Images", listOf("jpg", "jpeg", "png", "webp", "gif", "heic")),
    DOCUMENTS("Docs", listOf("txt", "md", "csv", "json", "xml", "doc", "docx")),
    CODE("Code", listOf("kt", "java", "py", "js", "ts", "html", "css", "c", "cpp", "go", "rs", "sh"))
}

/**
 * Everything Filer keeps on the phone. [totalBytes] is what Android's Settings › Apps shows (app +
 * data + cache); the parts are measured, and [otherBytes] is the rest of the data (settings, file
 * manager data, small files).
 */
data class StorageBreakdown(
    val totalBytes: Long,
    val appBytes: Long,
    val modelBytes: Long,
    val indexBytes: Long,
    val pluginBytes: Long,
    val lockedBytes: Long,
    val cacheBytes: Long,
    val otherBytes: Long,
    val freeSpaceBytes: Long
)

data class FileTypeStats(
    val type: FileTypeFilter,
    val count: Int,
    val totalSizeBytes: Long
)

data class SearchResultItem(
    val fileId: Long,
    val uriString: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val similarityScore: Float, // 0.0 to 1.0 (e.g. 0.94 -> 94%)
    val matchSnippet: String,
    val matchRationale: String, // e.g. "Matches: 'q3 freelance invoice'"
    val pageNumber: Int? = null,
    val isImage: Boolean = false,
    /** The file's passages that matched best, strongest first (empty for photos and name-only matches). */
    val matchedChunkIds: List<Long> = emptyList(),
    /** Query words found in the file's text or name. */
    val matchedTerms: List<String> = emptyList(),
    /** True when the file cleared the meaning gate, not just the word match. */
    val meaningMatch: Boolean = false
)
