package com.navaneeth.filer.data.analysis

import com.navaneeth.filer.data.files.FileManager
import com.navaneeth.filer.data.storage.StorageVolumeInfo
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Walks one volume once and works out everything the overview needs: space by type, the folder
 * tree, files worth cleaning up and when files arrived. Only file metadata is read here; comparing
 * contents for duplicates is [DuplicateFinder]'s job, on the [Walk.duplicateCandidates] found here.
 */
internal class StorageScanner(private val now: Long = System.currentTimeMillis()) {

    class Walk(val report: VolumeReport, val duplicateCandidates: List<FoundFile>)

    private val zone = ZoneId.systemDefault()
    private val yearAgo = now - TimeUnit.DAYS.toMillis(365)
    private val oldDownload = now - TimeUnit.DAYS.toMillis(OLD_DOWNLOAD_DAYS)
    private val oldScreenshot = now - TimeUnit.DAYS.toMillis(OLD_SCREENSHOT_DAYS)
    private val oldChatMedia = now - TimeUnit.DAYS.toMillis(OLD_CHAT_MEDIA_DAYS)
    private val stalePartial = now - TimeUnit.DAYS.toMillis(1)

    // Totals, filled in while walking.
    private var fileCount = 0
    private var screenshotCount = 0
    private val spaceByType = HashMap<SpaceType, Long>()
    private val addedByMonth = HashMap<YearMonth, HashMap<SpaceType, Long>>()
    private val recentSources = HashMap<FileSource, Long>()
    private val chatAppBytes = HashMap<FileSource, Long>()
    private val cleanups = HashMap<CleanupKind, MutableList<FoundFile>>()
    private val duplicateCandidates = ArrayList<FoundFile>()

    private lateinit var onProgress: (Int) -> Unit

    suspend fun scan(volume: StorageVolumeInfo, onProgress: (filesSeen: Int) -> Unit): Walk {
        this.onProgress = onProgress
        val root = walk(volume.root, rel = "")

        // The Recycle bin isn't walked (nothing in it should be suggested again), but it still takes space.
        val bin = File(volume.root, FileManager.TRASH_DIR)
        val binBytes = bin.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        spaceByType.merge(SpaceType.OTHER, binBytes, Long::plus)

        // Space no visible file accounts for: installed apps, their private data, Android itself.
        val visible = spaceByType.values.sum()
        val used = (volume.totalBytes - volume.freeBytes).coerceAtLeast(0)
        spaceByType[SpaceType.APPS_AND_SYSTEM] = (used - visible).coerceAtLeast(0)

        val report = VolumeReport(
            volume = volume,
            scannedAt = now,
            fileCount = fileCount,
            spaceByType = spaceByType.toMap(),
            root = root,
            cleanups = cleanups.mapValues { (_, files) -> files.sortedByDescending { it.sizeBytes } },
            duplicates = null,
            addedByMonth = addedByMonth.entries
                .sortedBy { it.key }
                .map { (month, bytes) -> MonthAdded(month, bytes.toMap()) },
            recentSources = recentSources.toMap(),
            chatAppBytes = chatAppBytes.toMap(),
            screenshotCount = screenshotCount
        )
        return Walk(report, duplicateCandidates)
    }

    private suspend fun walk(dir: File, rel: String): FolderNode {
        currentCoroutineContext().ensureActive()
        val entries = dir.listFiles().orEmpty()
        var size = 0L
        var count = 0
        val children = ArrayList<FolderNode>()

        for (entry in entries) {
            val childRel = if (rel.isEmpty()) entry.name.lowercase() else "$rel/${entry.name.lowercase()}"
            if (entry.isDirectory) {
                if (PathRules.isSkipped(childRel) || Files.isSymbolicLink(entry.toPath())) continue
                val child = walk(entry, childRel)
                size += child.sizeBytes
                count += child.fileCount
                children += child
            } else {
                val file = FoundFile(entry.path, entry.length(), entry.lastModified())
                record(file, childRel)
                size += file.sizeBytes
                count++
            }
        }

        if (entries.isEmpty() && PathRules.isRemovableEmptyFolder(rel)) {
            add(CleanupKind.JUNK, FoundFile(dir.path, 0, dir.lastModified()))
        }
        return FolderNode(
            name = dir.name,
            path = dir.path,
            sizeBytes = size,
            fileCount = count,
            children = children.sortedByDescending { it.sizeBytes }
        )
    }

    private fun record(file: FoundFile, rel: String) {
        fileCount++
        if (fileCount % PROGRESS_EVERY == 0) onProgress(fileCount)

        val size = file.sizeBytes
        val type = PathRules.spaceType(file.name)
        spaceByType.merge(type, size, Long::plus)
        addedByMonth.getOrPut(monthOf(file.modifiedAt)) { HashMap() }.merge(type, size, Long::plus)

        val source = PathRules.source(rel)
        if (file.modifiedAt >= yearAgo) recentSources.merge(source, size, Long::plus)
        if (source == FileSource.WHATSAPP || source == FileSource.TELEGRAM) chatAppBytes.merge(source, size, Long::plus)

        val isScreenshot = PathRules.isScreenshot(rel) && type == SpaceType.PHOTOS
        if (isScreenshot) screenshotCount++

        // Thumbnail caches live in hidden folders but Android rebuilds them, so they're the one
        // protected thing worth offering.
        if (PathRules.isThumbnailCache(rel)) add(CleanupKind.JUNK, file)

        // Never suggest anything an app or Android keeps for itself.
        if (!PathRules.isReviewable(rel)) return

        if (size >= LARGE_FILE_BYTES) add(CleanupKind.LARGE_FILES, file)
        if (PathRules.isDownload(rel) && file.modifiedAt < oldDownload) add(CleanupKind.OLD_DOWNLOADS, file)
        if (isScreenshot && file.modifiedAt < oldScreenshot) add(CleanupKind.OLD_SCREENSHOTS, file)
        if (PathRules.isReceivedChatMedia(rel) && file.modifiedAt < oldChatMedia) add(CleanupKind.CHAT_MEDIA, file)
        if (file.name.substringAfterLast('.', "").lowercase() in INSTALLER_EXTENSIONS) add(CleanupKind.INSTALLERS, file)
        if (PathRules.isTempFile(file.name)) add(CleanupKind.JUNK, file)
        if (PathRules.isPartialDownload(file.name) && file.modifiedAt < stalePartial) add(CleanupKind.JUNK, file)
        if (size >= DUPLICATE_MIN_BYTES) duplicateCandidates += file
    }

    private fun add(kind: CleanupKind, file: FoundFile) {
        cleanups.getOrPut(kind) { ArrayList() } += file
    }

    private fun monthOf(millis: Long): YearMonth = YearMonth.from(Instant.ofEpochMilli(millis).atZone(zone))

    companion object {
        const val LARGE_FILE_BYTES = 100L * 1024 * 1024
        /** Smaller copies free too little to be worth reading. */
        const val DUPLICATE_MIN_BYTES = 1L * 1024 * 1024
        const val OLD_DOWNLOAD_DAYS = 180L
        const val OLD_SCREENSHOT_DAYS = 90L
        const val OLD_CHAT_MEDIA_DAYS = 30L
        private const val PROGRESS_EVERY = 500
        private val INSTALLER_EXTENSIONS = setOf("apk", "xapk", "apks")
    }
}
