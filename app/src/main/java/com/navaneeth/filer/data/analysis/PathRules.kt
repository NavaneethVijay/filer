package com.navaneeth.filer.data.analysis

import com.navaneeth.filer.ui.model.FileKind

/**
 * Where a file lives says a lot about it. All checks take the path relative to the volume root, in
 * lower case, e.g. `dcim/camera/img_1.jpg` or `android/media/com.whatsapp/whatsapp/media/...`.
 *
 * Safety first: the analyser only ever suggests the user's own files. Anything an app or Android
 * keeps for itself ([isProtected]) is never offered for cleanup, with two narrow, well-known
 * exceptions: media someone sent through a chat app, and thumbnail caches Android rebuilds.
 */
internal object PathRules {

    private val whatsAppRoots = listOf("android/media/com.whatsapp/whatsapp/", "whatsapp/")
    private val telegramRoots = listOf("android/media/org.telegram.messenger/telegram/", "telegram/")

    /** Top-level folders Android creates. They're never junk and never deleted whole. */
    private val standardFolders = setOf(
        "alarms", "android", "audiobooks", "dcim", "documents", "download", "movies", "music",
        "notifications", "pictures", "podcasts", "recordings", "ringtones"
    )

    /** Folder names apps use for data they need back (chat history, restores). */
    private val appDataFolders = setOf("backups", "backup", "databases", "database")

    /** Leftovers that are safe to remove: temp files and logs. */
    private val tempExtensions = setOf("tmp", "temp", "log")

    /** Unfinished downloads; only stale ones are junk (a fresh one may still be downloading). */
    private val partialExtensions = setOf("crdownload", "part", "partial")

    fun spaceType(name: String): SpaceType = when (FileKind.of(name, mimeType = "")) {
        FileKind.VIDEO -> SpaceType.VIDEOS
        FileKind.IMAGE -> SpaceType.PHOTOS
        FileKind.AUDIO -> SpaceType.AUDIO
        FileKind.PDF, FileKind.DOCUMENT, FileKind.SPREADSHEET, FileKind.PRESENTATION -> SpaceType.DOCUMENTS
        else -> SpaceType.OTHER
    }

    fun source(rel: String): FileSource = when {
        rel.startsWith("dcim/camera/") -> FileSource.CAMERA
        isScreenshot(rel) -> FileSource.SCREENSHOTS
        "screen recordings/" in rel || "screenrecord" in rel -> FileSource.SCREEN_RECORDINGS
        whatsAppRoots.any { rel.startsWith(it) } -> FileSource.WHATSAPP
        telegramRoots.any { rel.startsWith(it) } -> FileSource.TELEGRAM
        rel.startsWith("download/") -> FileSource.DOWNLOADS
        else -> FileSource.OTHER
    }

    /**
     * Files apps or Android keep for themselves: everything under `Android/`, anything in a hidden
     * folder or hidden itself, and app backup/database folders. Never suggested for cleanup.
     */
    fun isProtected(rel: String): Boolean {
        val segments = rel.split('/')
        return segments.first() == "android" ||
            segments.any { it.startsWith(".") } ||
            segments.any { it in appDataFolders }
    }

    /** Whether the analyser may suggest this file at all. */
    fun isReviewable(rel: String): Boolean = !isProtected(rel) || isReceivedChatMedia(rel)

    fun isScreenshot(rel: String): Boolean = "/screenshots/" in "/$rel"

    fun isDownload(rel: String): Boolean = rel.startsWith("download/")

    /**
     * Photos, videos, voice notes and documents someone sent through a chat app, in its visible
     * media folders. Never chat backups or databases, statuses or other hidden folders, and never
     * what the user sent themselves.
     */
    fun isReceivedChatMedia(rel: String): Boolean {
        val inWhatsApp = whatsAppRoots.any { rel.startsWith(it + "media/") }
        val inTelegram = telegramRoots.any { rel.startsWith(it) }
        if (!inWhatsApp && !inTelegram) return false
        val segments = rel.split('/')
        return "sent" !in segments && "private" !in segments &&
            segments.none { it.startsWith(".") || it in appDataFolders }
    }

    /** Android's thumbnail caches; it rebuilds them when needed, so they're always safe to clear. */
    fun isThumbnailCache(rel: String): Boolean = "/.thumbnails/" in "/$rel" && !rel.startsWith("android/")

    fun isTempFile(name: String): Boolean = extensionOf(name) in tempExtensions

    fun isPartialDownload(name: String): Boolean = extensionOf(name) in partialExtensions

    /** An empty folder that's safe to remove: the user's own, not one Android or an app made. */
    fun isRemovableEmptyFolder(rel: String): Boolean =
        rel.isNotEmpty() && rel !in standardFolders && !isProtected(rel)

    /** Whether the space map may offer to bin this whole folder. */
    fun canTrashFolder(rel: String): Boolean =
        rel.isNotEmpty() && rel !in standardFolders && !isProtected(rel)

    /** Folders the analyser never walks: other apps' private data and Filer's own bin. */
    fun isSkipped(rel: String): Boolean =
        rel == "android/data" || rel == "android/obb" || rel == ".filertrash"

    private fun extensionOf(name: String) = name.substringAfterLast('.', "").lowercase()
}
