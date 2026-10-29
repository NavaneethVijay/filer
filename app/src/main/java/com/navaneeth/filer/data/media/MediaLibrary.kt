package com.navaneeth.filer.data.media

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.format.DateUtils
import com.navaneeth.filer.data.search.DateField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/** A file as Android's media index (MediaStore) knows it. */
data class MediaFile(
    val path: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val modifiedAt: Long
)

/** The device-wide groupings shown on Browse, like the Files app. */
enum class MediaCategory { IMAGES, VIDEOS, AUDIO, DOCUMENTS, DOWNLOADS, APKS }

/** Structured filters for [MediaLibrary.filtered]. [folders]: (name, topLevel) pairs matched on the folder path. */
data class MediaFilter(
    val types: List<TypeFilter> = emptyList(),
    val dateRange: Pair<Long, Long>? = null,
    val dateField: DateField = DateField.ANY,
    val folders: List<Pair<String, Boolean>> = emptyList(),
    val minBytes: Long? = null,
    val maxBytes: Long? = null,
    val nameTerms: List<String> = emptyList()
)

/** Files whose MIME type starts with one of [mimePrefixes], is one of [mimeTypes], or whose name ends in one of [extensions]. */
data class TypeFilter(
    val mimePrefixes: List<String> = emptyList(),
    val mimeTypes: List<String> = emptyList(),
    val extensions: Collection<String> = emptyList()
)

/**
 * Whole-device file queries backed by MediaStore. Android keeps this index up to date itself, so
 * Recent, categories and name search are instant and complete without Filer indexing anything.
 * With all-files access, MediaStore returns every file, not only media.
 */
class MediaLibrary(private val context: Context) {

    private val resolver: ContentResolver get() = context.contentResolver
    private val filesUri: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

    private val projection = arrayOf(
        MediaStore.Files.FileColumns.DATA,
        MediaStore.Files.FileColumns.DISPLAY_NAME,
        MediaStore.Files.FileColumns.MIME_TYPE,
        MediaStore.Files.FileColumns.SIZE,
        MediaStore.Files.FileColumns.DATE_MODIFIED
    )

    /** Files only (folders have no MIME type), and nothing hidden. */
    private val baseSelection =
        "${MediaStore.Files.FileColumns.MIME_TYPE} IS NOT NULL AND ${MediaStore.Files.FileColumns.DATA} NOT LIKE '%/.%'"

    suspend fun recent(limit: Int): List<MediaFile> =
        query(baseSelection, emptyArray(), "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC", limit)

    suspend fun category(category: MediaCategory, limit: Int = 5_000): List<MediaFile> {
        val (where, args) = categorySelection(category)
        return query("$baseSelection AND $where", args, "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC", limit)
    }

    suspend fun count(category: MediaCategory): Int = withContext(Dispatchers.IO) {
        val (where, args) = categorySelection(category)
        resolver.query(filesUri, arrayOf(MediaStore.Files.FileColumns._ID), "$baseSelection AND $where", args, null)
            ?.use { it.count } ?: 0
    }

    /** Case-insensitive file-name match across the whole device, optionally only files of one [type]. */
    suspend fun searchByName(query: String, limit: Int, type: TypeFilter? = null): List<MediaFile> {
        val escaped = query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        if (escaped.isEmpty()) return emptyList()
        val (typeWhere, typeArgs) = type?.let(::typeSelection) ?: ("1" to emptyArray<String>())
        return query(
            "$baseSelection AND ${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ? ESCAPE '\\' AND $typeWhere",
            arrayOf("%$escaped%") + typeArgs,
            "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC",
            limit
        )
    }

    /** Newest files of one type, for browsing a type from search before typing anything. */
    suspend fun ofType(type: TypeFilter, limit: Int): List<MediaFile> {
        val (where, args) = typeSelection(type)
        return query("$baseSelection AND $where", args, "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC", limit)
    }

    private fun typeSelection(type: TypeFilter): Pair<String, Array<String>> {
        val mime = MediaStore.Files.FileColumns.MIME_TYPE
        val name = MediaStore.Files.FileColumns.DISPLAY_NAME
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()
        type.mimePrefixes.forEach { clauses += "$mime LIKE ?"; args += "$it%" }
        if (type.mimeTypes.isNotEmpty()) {
            clauses += "$mime IN (${type.mimeTypes.joinToString { "?" }})"
            args += type.mimeTypes
        }
        type.extensions.forEach { clauses += "$name LIKE ?"; args += "%.$it" }
        return (if (clauses.isEmpty()) "0" else clauses.joinToString(" OR ", "(", ")")) to args.toTypedArray()
    }

    /**
     * Files matching structured filters (from a natural search like "pdfs downloaded 2 days ago"),
     * newest first. All parts are optional; an empty filter returns nothing.
     */
    suspend fun filtered(filter: MediaFilter, limit: Int): List<MediaFile> {
        val where = mutableListOf(baseSelection)
        val args = mutableListOf<String>()
        if (filter.types.isNotEmpty()) {
            val parts = filter.types.map(::typeSelection)
            where += parts.joinToString(" OR ", "(", ")") { it.first }
            parts.forEach { args += it.second }
        }
        filter.dateRange?.let { (start, end) ->
            val added = "(${MediaStore.MediaColumns.DATE_ADDED} BETWEEN ? AND ?)"
            val modified = "(${MediaStore.MediaColumns.DATE_MODIFIED} BETWEEN ? AND ?)"
            val taken = "(${MediaStore.MediaColumns.DATE_TAKEN} BETWEEN ? AND ?)"
            val seconds = listOf((start / 1000).toString(), (end / 1000).toString())
            val millis = listOf(start.toString(), end.toString())
            when (filter.dateField) {
                DateField.ADDED -> { where += added; args += seconds }
                DateField.MODIFIED -> { where += modified; args += seconds }
                // Photos and videos carry the capture time; other files fall back to their edit time.
                DateField.TAKEN -> { where += "($taken OR (${MediaStore.MediaColumns.DATE_TAKEN} IS NULL AND $modified))"; args += millis + seconds }
                DateField.ANY -> { where += "($added OR $modified OR $taken)"; args += seconds + seconds + millis }
            }
        }
        if (filter.folders.isNotEmpty()) {
            where += filter.folders.joinToString(" OR ", "(", ")") { "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?" }
            filter.folders.forEach { args += if (it.second) "${it.first}/%" else "%${it.first}%" }
        }
        filter.minBytes?.let { where += "${MediaStore.MediaColumns.SIZE} >= ?"; args += it.toString() }
        filter.maxBytes?.let { where += "${MediaStore.MediaColumns.SIZE} <= ?"; args += it.toString() }
        if (filter.nameTerms.isNotEmpty()) {
            where += filter.nameTerms.joinToString(" OR ", "(", ")") { "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ? ESCAPE '\\'" }
            filter.nameTerms.forEach { args += "%" + it.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%" }
        }
        val sort = if (filter.dateField == DateField.ADDED) MediaStore.MediaColumns.DATE_ADDED else MediaStore.MediaColumns.DATE_MODIFIED
        return query(where.joinToString(" AND "), args.toTypedArray(), "$sort DESC", limit)
    }

    /** Every photo's path and last-modified time (ms), for reading where photos were taken. */
    suspend fun photos(): List<Pair<String, Long>> = withContext(Dispatchers.IO) {
        val out = ArrayList<Pair<String, Long>>()
        runCatching {
            resolver.query(
                filesUri,
                arrayOf(MediaStore.Files.FileColumns.DATA, MediaStore.Files.FileColumns.DATE_MODIFIED),
                "$baseSelection AND ${MediaStore.Files.FileColumns.MIME_TYPE} LIKE 'image/%'",
                null,
                null
            )?.use { c ->
                while (c.moveToNext()) {
                    val path = c.getString(0) ?: continue
                    out += path to c.getLong(1) * 1000L
                }
            }
        }
        out
    }

    /**
     * Brings the media index up to date with the disk for the last [days] days: files added or
     * edited by apps that didn't tell MediaStore are scanned in, and rows for files that are gone are
     * scanned out. Walks each volume (skipping hidden folders and `/Android` apart from
     * `/Android/media`), at most [budgetMs] long. Returns how many paths were sent to the scanner.
     */
    suspend fun resync(roots: List<File>, days: Int = 30, budgetMs: Long = 8_000): Int = withContext(Dispatchers.IO) {
        val since = System.currentTimeMillis() - days * DateUtils.DAY_IN_MILLIS
        val known = HashMap<String, Long>()
        runCatching {
            resolver.query(
                filesUri,
                arrayOf(MediaStore.Files.FileColumns.DATA, MediaStore.Files.FileColumns.DATE_MODIFIED),
                "$baseSelection AND ${MediaStore.Files.FileColumns.DATE_MODIFIED} >= ?",
                arrayOf((since / 1000).toString()),
                null
            )?.use { c ->
                while (c.moveToNext()) {
                    val path = c.getString(0) ?: continue
                    known[path] = c.getLong(1)
                }
            }
        }

        val stale = LinkedHashSet<String>()
        val deadline = System.currentTimeMillis() + budgetMs
        fun walk(dir: File, depth: Int) {
            if (depth > MAX_DEPTH || System.currentTimeMillis() > deadline || stale.size >= MAX_SCAN) return
            for (child in dir.listFiles() ?: return) {
                if (child.name.startsWith('.')) continue
                if (child.isDirectory) {
                    val androidDir = depth == 0 && child.name == "Android"
                    if (androidDir) File(child, "media").takeIf { it.isDirectory }?.let { walk(it, depth + 2) }
                    else walk(child, depth + 1)
                } else {
                    val modified = child.lastModified()
                    if (modified < since) continue
                    val indexed = known[child.path]
                    if (indexed == null || kotlin.math.abs(indexed - modified / 1000) > 1) stale += child.path
                }
            }
        }
        roots.forEach { walk(it, 0) }
        known.keys.filterTo(stale) { !File(it).exists() }
        if (stale.isEmpty()) return@withContext 0

        val paths = stale.take(MAX_SCAN).toTypedArray()
        // Waits for the scanner (or the budget), so the caller's refresh spinner means something.
        withTimeoutOrNull(budgetMs) {
            suspendCancellableCoroutine { cont ->
                val left = AtomicInteger(paths.size)
                MediaScannerConnection.scanFile(context, paths, null) { _, _ ->
                    if (left.decrementAndGet() == 0 && cont.isActive) cont.resume(Unit)
                }
            }
        }
        paths.size
    }

    /** Emits whenever any file on the device is added, changed or removed. */
    fun changes(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        resolver.registerContentObserver(filesUri, true, observer)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }.conflate()

    private fun categorySelection(category: MediaCategory): Pair<String, Array<String>> {
        val mime = MediaStore.Files.FileColumns.MIME_TYPE
        val name = MediaStore.Files.FileColumns.DISPLAY_NAME
        return when (category) {
            MediaCategory.IMAGES -> "$mime LIKE 'image/%'" to emptyArray()
            MediaCategory.VIDEOS -> "$mime LIKE 'video/%'" to emptyArray()
            MediaCategory.AUDIO -> "$mime LIKE 'audio/%'" to emptyArray()
            MediaCategory.DOCUMENTS -> {
                val types = DOCUMENT_MIME_TYPES
                "($mime IN (${types.joinToString { "?" }}) OR $mime LIKE 'text/%')" to types.toTypedArray()
            }
            MediaCategory.DOWNLOADS -> "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE 'Download/%'" to emptyArray()
            MediaCategory.APKS -> "$name LIKE '%.apk'" to emptyArray()
        }
    }

    private suspend fun query(selection: String, args: Array<String>, sort: String, limit: Int): List<MediaFile> =
        withContext(Dispatchers.IO) {
            val queryArgs = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sort)
                putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
            }
            val out = mutableListOf<MediaFile>()
            runCatching {
                resolver.query(filesUri, projection, queryArgs, null)?.use { c ->
                    while (c.moveToNext()) {
                        val path = c.getString(0) ?: continue
                        out += MediaFile(
                            path = path,
                            name = c.getString(1) ?: File(path).name,
                            mimeType = c.getString(2) ?: "application/octet-stream",
                            sizeBytes = c.getLong(3),
                            modifiedAt = c.getLong(4) * 1000L
                        )
                    }
                }
            }
            out
        }

    private companion object {
        /** Folder depth [resync] walks to, and the most paths it hands the scanner at once. */
        const val MAX_DEPTH = 12
        const val MAX_SCAN = 2_000

        val DOCUMENT_MIME_TYPES = listOf(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/rtf",
            "application/vnd.oasis.opendocument.text",
            "application/vnd.oasis.opendocument.spreadsheet",
            "application/epub+zip"
        )
    }
}
