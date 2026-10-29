package com.navaneeth.filer.data.analysis

import com.navaneeth.filer.data.storage.StorageVolumeInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.YearMonth

class StorageAnalysisTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val gb = 1024L * 1024 * 1024

    // PathRules

    @Test
    fun chatMediaIsOnlyWhatOthersSent() {
        val media = "android/media/com.whatsapp/whatsapp/media/"
        assertTrue(PathRules.isReceivedChatMedia(media + "whatsapp video/vid-1.mp4"))
        assertFalse(PathRules.isReceivedChatMedia(media + "whatsapp video/sent/vid-2.mp4"))
        assertFalse(PathRules.isReceivedChatMedia(media + "whatsapp images/private/img.jpg"))
        // Chat backups are never offered.
        assertFalse(PathRules.isReceivedChatMedia("android/media/com.whatsapp/whatsapp/databases/msgstore.db.crypt14"))
        assertFalse(PathRules.isReceivedChatMedia("whatsapp/backups/chat.db"))
    }

    @Test
    fun sourcesComeFromWhereFilesLive() {
        assertEquals(FileSource.CAMERA, PathRules.source("dcim/camera/img_1.jpg"))
        assertEquals(FileSource.SCREENSHOTS, PathRules.source("pictures/screenshots/s.png"))
        assertEquals(FileSource.SCREENSHOTS, PathRules.source("dcim/screenshots/s.png"))
        assertEquals(FileSource.DOWNLOADS, PathRules.source("download/file.pdf"))
        assertEquals(FileSource.TELEGRAM, PathRules.source("telegram/telegram video/v.mp4"))
        assertEquals(FileSource.OTHER, PathRules.source("documents/notes.txt"))
    }

    @Test
    fun appAndSystemFilesAreNeverSuggested() {
        // Android's and apps' own folders.
        assertFalse(PathRules.isReviewable("android/media/com.spotify.music/cache.bin"))
        assertFalse(PathRules.isReviewable("android/media/com.whatsapp/whatsapp/databases/msgstore.db.crypt14"))
        assertFalse(PathRules.isReviewable("android/media/com.whatsapp/whatsapp/media/.statuses/a.mp4"))
        // Hidden files and folders, and app backups outside Android/.
        assertFalse(PathRules.isReviewable(".config/app/settings.json"))
        assertFalse(PathRules.isReviewable("documents/.secret.txt"))
        assertFalse(PathRules.isReviewable("whatsapp/backups/chat.db"))
        assertFalse(PathRules.isReviewable("documents/backup/contacts.vcf"))
        // The user's own files, and media others sent, are fine.
        assertTrue(PathRules.isReviewable("download/movie.mp4"))
        assertTrue(PathRules.isReviewable("android/media/com.whatsapp/whatsapp/media/whatsapp video/vid-1.mp4"))
    }

    @Test
    fun junkIsOnlyTempFilesAndCaches() {
        assertTrue(PathRules.isTempFile("crash.log"))
        assertTrue(PathRules.isPartialDownload("movie.mp4.crdownload"))
        assertFalse(PathRules.isTempFile("settings.bak"))
        assertTrue(PathRules.isThumbnailCache("dcim/.thumbnails/1.jpg"))
        assertFalse(PathRules.isThumbnailCache("android/media/com.app/.thumbnails/1.jpg"))
    }

    @Test
    fun androidFoldersCantBeRemoved() {
        assertFalse(PathRules.isRemovableEmptyFolder("music"))
        assertFalse(PathRules.isRemovableEmptyFolder("android/media/com.app"))
        assertFalse(PathRules.isRemovableEmptyFolder("documents/.hidden"))
        assertTrue(PathRules.isRemovableEmptyFolder("documents/old project"))
        assertFalse(PathRules.canTrashFolder("dcim"))
        assertFalse(PathRules.canTrashFolder("android"))
        assertFalse(PathRules.canTrashFolder("android/media/com.whatsapp"))
        assertTrue(PathRules.canTrashFolder("dcim/camera"))
        assertTrue(PathRules.canTrashFolder("movies/old trips"))
    }

    // Insights

    @Test
    fun forecastFollowsThePace() {
        val today = YearMonth.of(2026, 10)
        // 10 GB a month for a year; 60 GB free → full in 6 months.
        val months = (11L downTo 0L).map { MonthAdded(today.minusMonths(it), mapOf(SpaceType.PHOTOS to 10 * gb)) }
        val report = report(total = 256 * gb, free = 60 * gb, months = months)

        val forecast = StorageInsights.forecast(report, InsightRange.ONE_YEAR, today)

        assertEquals(10 * gb, forecast.pacePerMonth)
        assertEquals(today.plusMonths(6), forecast.fullBy)
        assertEquals(12, forecast.history.size)
        assertEquals(report.usedBytes, forecast.history.last().usedBytes)
        assertEquals(report.usedBytes - 110 * gb, forecast.history.first().usedBytes)
    }

    @Test
    fun noForecastWhenNothingIsAdded() {
        val forecast = StorageInsights.forecast(report(total = 256 * gb, free = 60 * gb, months = emptyList()), InsightRange.ONE_YEAR)
        assertNull(forecast.fullBy)
        assertNull(forecast.cleanupBuysDays)
    }

    @Test
    fun growthIsSplitByType() {
        val today = YearMonth.of(2026, 10)
        val months = listOf(
            MonthAdded(today.minusMonths(20), mapOf(SpaceType.VIDEOS to 50 * gb)), // outside a 1-year range
            MonthAdded(today.minusMonths(2), mapOf(SpaceType.VIDEOS to 3 * gb, SpaceType.PHOTOS to 1 * gb)),
            MonthAdded(today, mapOf(SpaceType.PHOTOS to 1 * gb))
        )
        val grew = StorageInsights.grew(report(256 * gb, 60 * gb, months), InsightRange.ONE_YEAR, today)
        assertEquals(listOf(SpaceType.VIDEOS to 3 * gb, SpaceType.PHOTOS to 2 * gb), grew)
    }

    // Duplicates

    @Test
    fun duplicatesNeedIdenticalContentsAndKeepTheCameraCopy() = runBlocking {
        val root = temp.root
        val camera = file(root, "DCIM/Camera/a.jpg", "same contents".repeat(1000))
        val download = file(root, "Download/a (1).jpg", "same contents".repeat(1000))
        val sameSizeDifferent = file(root, "Pictures/b.jpg", "SAME CONTENTS".repeat(1000))

        val groups = DuplicateFinder().find(listOf(download, sameSizeDifferent, camera), root.path) { _, _ -> }

        assertEquals(1, groups.size)
        assertEquals(listOf(camera.path, download.path), groups.single().copies.map { it.path })
        assertEquals(camera.sizeBytes, groups.single().extraBytes)
    }

    private fun file(root: File, rel: String, text: String): FoundFile {
        val file = File(root, rel).apply { parentFile?.mkdirs(); writeText(text) }
        return FoundFile(file.path, file.length(), file.lastModified())
    }

    private fun report(total: Long, free: Long, months: List<MonthAdded>) = VolumeReport(
        volume = StorageVolumeInfo(File("/storage/emulated/0"), "Internal", isPrimary = true, totalBytes = total, freeBytes = free),
        scannedAt = 0,
        fileCount = 0,
        spaceByType = emptyMap(),
        root = FolderNode("0", "/storage/emulated/0", 0, 0, emptyList()),
        cleanups = emptyMap(),
        duplicates = emptyList(),
        addedByMonth = months,
        recentSources = emptyMap(),
        chatAppBytes = emptyMap(),
        screenshotCount = 0
    )
}
