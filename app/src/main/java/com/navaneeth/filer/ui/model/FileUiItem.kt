package com.navaneeth.filer.ui.model

import androidx.compose.runtime.Immutable
import com.navaneeth.filer.data.db.entity.FileEntity
import com.navaneeth.filer.data.media.TypeFilter
import android.net.Uri
import com.navaneeth.filer.R

/** What every file row, grid tile, carousel card and action sheet renders. */
@Immutable
data class FileUiItem(
    val uri: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val location: String? = null,
    val fileId: Long? = null,
    val sourceId: Long? = null,
    val isDirectory: Boolean = false,
    /** For browsed items: the absolute path. */
    val documentId: String? = null,
    /** For folders: how many visible entries they contain. */
    val childCount: Int = 0
) {
    val kind: FileKind = FileKind.of(name, mimeType, isDirectory)
}

/** Absolute path for a file on this phone (browsed, MediaStore or indexed), or null for other URIs. */
fun FileUiItem.pathOrNull(): String? = documentId ?: Uri.parse(uri).takeIf { it.scheme == "file" }?.path

fun FileEntity.toUiItem(location: String?) = FileUiItem(
    uri = uri,
    name = displayName,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    modifiedAt = modifiedAt,
    location = location,
    fileId = id,
    sourceId = sourceId
)

enum class FileKind {
    FOLDER, IMAGE, VIDEO, AUDIO, PDF, DOCUMENT, SPREADSHEET, PRESENTATION, CODE, ARCHIVE, APK, OTHER;

    companion object {
        val images = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp", "tiff", "svg")
        val videos = setOf("mp4", "mkv", "mov", "webm", "3gp", "avi")
        val audio = setOf("mp3", "m4a", "wav", "flac", "ogg", "aac", "opus")
        val sheets = setOf("xls", "xlsx", "csv", "tsv", "ods")
        val slides = setOf("ppt", "pptx", "odp", "key")
        val docs = setOf("doc", "docx", "txt", "md", "rtf", "odt", "log", "pages", "epub")
        val code = setOf(
            "kt", "kts", "java", "py", "js", "ts", "jsx", "tsx", "html", "css", "scss", "c", "cpp", "h", "hpp",
            "go", "rs", "sh", "bash", "zsh", "sql", "json", "xml", "yaml", "yml", "ini", "conf", "gradle",
            "swift", "rb", "php"
        )
        val archives = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz")
        val apks = setOf("apk", "aab", "xapk", "apks")

        fun of(name: String, mimeType: String, isDirectory: Boolean = false): FileKind {
            if (isDirectory) return FOLDER
            val ext = name.substringAfterLast('.', "").lowercase()
            val mime = mimeType.lowercase()
            return when {
                ext == "pdf" || mime == "application/pdf" -> PDF
                ext in images || mime.startsWith("image/") -> IMAGE
                ext in videos || mime.startsWith("video/") -> VIDEO
                ext in audio || mime.startsWith("audio/") -> AUDIO
                ext in sheets -> SPREADSHEET
                ext in slides -> PRESENTATION
                ext in code -> CODE
                ext in docs || mime.startsWith("text/") -> DOCUMENT
                ext in archives -> ARCHIVE
                ext in apks || mime == "application/vnd.android.package-archive" -> APK
                else -> OTHER
            }
        }
    }
}

/** The type chips in search: filter results, or browse a type before typing anything. */
enum class FileCategory {
    IMAGES, VIDEOS, AUDIO, DOCUMENTS, PDFS, CODE, ARCHIVES, APKS;

    fun matches(kind: FileKind): Boolean = when (this) {
        IMAGES -> kind == FileKind.IMAGE
        VIDEOS -> kind == FileKind.VIDEO
        AUDIO -> kind == FileKind.AUDIO
        PDFS -> kind == FileKind.PDF
        DOCUMENTS -> kind == FileKind.DOCUMENT || kind == FileKind.SPREADSHEET || kind == FileKind.PRESENTATION
        CODE -> kind == FileKind.CODE
        ARCHIVES -> kind == FileKind.ARCHIVE
        APKS -> kind == FileKind.APK
    }

    /**
     * The MediaStore query for this type. It can be a little broader than [matches] (e.g. "text/" types also
     * covers code), so results are filtered with [matches] afterwards.
     */
    val typeFilter: TypeFilter
        get() = when (this) {
            IMAGES -> TypeFilter(mimePrefixes = listOf("image/"), extensions = FileKind.images)
            VIDEOS -> TypeFilter(mimePrefixes = listOf("video/"), extensions = FileKind.videos)
            AUDIO -> TypeFilter(mimePrefixes = listOf("audio/"), extensions = FileKind.audio)
            PDFS -> TypeFilter(mimeTypes = listOf("application/pdf"), extensions = listOf("pdf"))
            DOCUMENTS -> TypeFilter(mimePrefixes = listOf("text/"), extensions = FileKind.docs + FileKind.sheets + FileKind.slides)
            CODE -> TypeFilter(extensions = FileKind.code)
            ARCHIVES -> TypeFilter(extensions = FileKind.archives)
            APKS -> TypeFilter(mimeTypes = listOf("application/vnd.android.package-archive"), extensions = FileKind.apks)
        }
}

/** The name shown for a type chip or filter; null is "All". */
val FileCategory?.labelRes: Int
    get() = when (this) {
        null -> R.string.filter_all
        FileCategory.IMAGES -> R.string.category_images
        FileCategory.VIDEOS -> R.string.category_videos
        FileCategory.AUDIO -> R.string.category_audio
        FileCategory.DOCUMENTS -> R.string.category_documents
        FileCategory.PDFS -> R.string.category_pdfs
        FileCategory.CODE -> R.string.category_code
        FileCategory.ARCHIVES -> R.string.category_archives
        FileCategory.APKS -> R.string.category_apks
    }
