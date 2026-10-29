package com.navaneeth.filer.data.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.Settings
import android.webkit.MimeTypeMap
import java.io.File

/** A mounted storage volume: the phone's internal shared storage or an SD card / USB drive. */
data class StorageVolumeInfo(
    val root: File,
    val label: String,
    val isPrimary: Boolean,
    val totalBytes: Long,
    val freeBytes: Long
)

/** All-files access and the device's storage volumes. */
object Storage {

    fun hasAllFilesAccess(): Boolean = Environment.isExternalStorageManager()

    /** Opens this app's "All files access" page in system Settings. */
    fun allFilesAccessIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))

    fun volumes(context: Context): List<StorageVolumeInfo> {
        val manager = context.getSystemService(StorageManager::class.java) ?: return emptyList()
        return manager.storageVolumes.mapNotNull { volume ->
            val dir = volume.directory ?: return@mapNotNull null
            if (!dir.canRead()) return@mapNotNull null
            StorageVolumeInfo(
                root = dir,
                label = volume.getDescription(context),
                isPrimary = volume.isPrimary,
                totalBytes = dir.totalSpace,
                freeBytes = dir.usableSpace
            )
        }.sortedByDescending { it.isPrimary }
    }

    /** The volume a path lives on, so folder paths can be shown as "Internal storage › Documents". */
    fun volumeFor(context: Context, path: String): StorageVolumeInfo? =
        volumes(context).filter { path == it.root.path || path.startsWith(it.root.path + "/") }
            .maxByOrNull { it.root.path.length }

    /** "Internal storage › Documents › Work" for a path. */
    fun displayPath(context: Context, path: String): String {
        val volume = volumeFor(context, path) ?: return path
        val rel = path.removePrefix(volume.root.path).trim('/')
        return listOf(volume.label).plus(rel.split('/').filter { it.isNotEmpty() }).joinToString(" › ")
    }

    fun mimeTypeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }
}

/** The kinds of file smart search can read; the user picks which ones in Settings. */
enum class IndexKind { PHOTOS, PDFS, TEXT }

/** What the indexer embeds. Everything else is listed for browsing but never sent to the model. */
object IndexRules {
    private val textExtensions = setOf(
        // Documents
        "pdf", "txt", "md", "csv", "tsv", "rtf", "docx", "odt",
        // Code & scripts
        "kt", "kts", "java", "py", "js", "ts", "jsx", "tsx", "html", "css", "scss",
        "c", "cpp", "h", "hpp", "go", "rs", "sh", "bash", "zsh", "sql"
    )
    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp")

    /**
     * File extensions that should never be embedded by meaning:
     * logs, app/system configurations, XML markup, diagnostic dumps, and temporary files.
     * These remain searchable by file name, but are skipped for semantic vector search.
     */
    private val excludedExtensions = setOf(
        // Logs & diagnostic dumps
        "log", "logs", "logcat", "dump", "dmp", "trace", "crash", "crashlog", "tombstone",
        // System, app config & data markup
        "xml", "ini", "conf", "cfg", "prop", "properties", "prefs", "manifest",
        // Temporary, cache & backup files
        "tmp", "temp", "bak", "swp", "old", "dat", "bin"
    )

    /** Icons, stickers and thumbnails are tiny; skipping them saves many model runs. */
    private const val MIN_IMAGE_BYTES = 20 * 1024L

    /** Secrets are never read, even inside a smart-search folder. */
    private val sensitiveExtensions = setOf("pem", "key", "p12", "pfx", "keystore", "jks", "kdbx", "gpg", "asc", "ovpn")
    private val sensitiveNames = setOf("id_rsa", "id_ed25519", "id_ecdsa", "id_dsa", ".env", "credentials", "passwords.txt")

    /** Which kind of readable file this is, or null if it's never embedded (videos, audio, secrets…). */
    fun kindOf(name: String, mimeType: String, sizeBytes: Long = Long.MAX_VALUE): IndexKind? {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext in sensitiveExtensions || name.lowercase() in sensitiveNames) return null
        if (ext in excludedExtensions) return null

        val mime = mimeType.lowercase()
        val isImage = ext in imageExtensions || (mime.startsWith("image/") && mime != "image/svg+xml")
        return when {
            isImage -> IndexKind.PHOTOS.takeIf { sizeBytes >= MIN_IMAGE_BYTES }
            ext == "pdf" || mime == "application/pdf" -> IndexKind.PDFS
            ext in textExtensions -> IndexKind.TEXT
            mime.startsWith("text/") && mime != "text/xml" && !mime.contains("log") -> IndexKind.TEXT
            else -> null
        }
    }

    /** True if the file is readable and its kind is one the user wants searched by meaning. */
    fun isIndexable(
        name: String,
        mimeType: String,
        sizeBytes: Long = Long.MAX_VALUE,
        kinds: Set<IndexKind> = IndexKind.entries.toSet()
    ): Boolean = kindOf(name, mimeType, sizeBytes) in kinds

    private val skippedDirectories = setOf(
        "cache", "thumbnails", "node_modules", "__pycache__", "build", "venv",
        "lost.dir", "logs", "log", "crashreports", "crashreport", "crashdumps", "crashdump"
    )

    /** Folders never worth walking: hidden ones (.ssh, .git…), caches and build/dependency output. */
    fun isSkippedDirectory(dir: File): Boolean {
        val name = dir.name
        return name.startsWith(".") || name.lowercase() in skippedDirectories
    }
}
