package com.navaneeth.filer.data.analysis

import com.navaneeth.filer.data.storage.StorageVolumeInfo
import java.time.YearMonth

/** What the space on a volume is used by, as shown in the overview ring. */
enum class SpaceType { VIDEOS, PHOTOS, AUDIO, DOCUMENTS, OTHER, APPS_AND_SYSTEM }

/** Where new files come from, for the "Where new files come from" insight. */
enum class FileSource { CAMERA, WHATSAPP, TELEGRAM, DOWNLOADS, SCREENSHOTS, SCREEN_RECORDINGS, OTHER }

/**
 * A kind of file the analyser suggests reviewing. [isClutter] kinds add up to the "You can free up"
 * total; the others (like 4K videos) are only offered from an insight.
 */
enum class CleanupKind(val isClutter: Boolean = true) {
    LARGE_FILES,
    DUPLICATES,
    OLD_DOWNLOADS,
    CHAT_MEDIA,
    INSTALLERS,
    OLD_SCREENSHOTS,
    JUNK,
    /** Near-identical photos taken moments apart (smart search): all but the best of each group. */
    SIMILAR_PHOTOS,
    /** Greetings, quotes and memes received in chat apps (smart search). */
    FORWARDS,
    VIDEOS_4K(isClutter = false),
    /** IDs, passports and bank papers outside the Locked folder (smart search): for locking, not deleting. */
    SENSITIVE(isClutter = false)
}

/** One file (or empty folder) on disk. */
data class FoundFile(val path: String, val sizeBytes: Long, val modifiedAt: Long) {
    val name: String get() = path.substringAfterLast('/')
    val parentPath: String get() = path.substringBeforeLast('/')
}

/** Identical files: same size and same contents. */
data class DuplicateGroup(val sizeBytes: Long, val copies: List<FoundFile>) {
    /** Space freed by keeping just one copy. */
    val extraBytes: Long get() = sizeBytes * (copies.size - 1)
}

/** A folder in the space map, with everything under it added up. */
class FolderNode(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val fileCount: Int,
    /** Largest first. */
    val children: List<FolderNode>
)

/** Bytes added in one month (by file date), split by type. */
data class MonthAdded(val month: YearMonth, val bytes: Map<SpaceType, Long>) {
    val total: Long get() = bytes.values.sum()
}

/** Everything the analyser found on one volume. */
data class VolumeReport(
    val volume: StorageVolumeInfo,
    val scannedAt: Long,
    val fileCount: Int,
    /** Includes [SpaceType.APPS_AND_SYSTEM]: used space that no file we can see accounts for. */
    val spaceByType: Map<SpaceType, Long>,
    val root: FolderNode,
    /** Files per kind, largest first. Duplicates live in [duplicates] instead. */
    val cleanups: Map<CleanupKind, List<FoundFile>>,
    /** Null until the duplicate check (which reads file contents) has finished. */
    val duplicates: List<DuplicateGroup>?,
    /** Oldest first, one entry per month that has files. */
    val addedByMonth: List<MonthAdded>,
    /** Bytes added in the last 12 months, by where they came from. */
    val recentSources: Map<FileSource, Long>,
    /** All-time size of each chat app's folder. */
    val chatAppBytes: Map<FileSource, Long>,
    val screenshotCount: Int,
    /** What smart search says the files are (see [ContentReport.status]). */
    val contents: ContentReport = ContentReport.NONE,
    /** Installer files for apps already installed at the same or a newer version. */
    val installedApkPaths: Set<String> = emptySet()
) {
    val usedBytes: Long get() = (volume.totalBytes - volume.freeBytes).coerceAtLeast(0)

    fun files(kind: CleanupKind): List<FoundFile> = when (kind) {
        CleanupKind.DUPLICATES -> duplicates.orEmpty().flatMap { it.copies.drop(1) }
        else -> cleanups[kind].orEmpty()
    }

    fun bytes(kind: CleanupKind): Long = when (kind) {
        CleanupKind.DUPLICATES -> duplicates.orEmpty().sumOf { it.extraBytes }
        else -> cleanups[kind].orEmpty().sumOf { it.sizeBytes }
    }

    /** What could be freed, counting each file once even when it fits several kinds. */
    val clutterBytes: Long by lazy {
        val seen = HashSet<String>()
        CleanupKind.entries.filter { it.isClutter }
            .flatMap { files(it) }
            .filter { seen.add(it.path) }
            .sumOf { it.sizeBytes }
    }
}

/** Progress of a scan, for the wavy indicator. */
sealed interface ScanStage {
    data class Walking(val filesSeen: Int) : ScanStage
    /** [done] of [total] bytes read while comparing same-size files. */
    data class Duplicates(val done: Long, val total: Long) : ScanStage {
        val fraction: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    }
}
