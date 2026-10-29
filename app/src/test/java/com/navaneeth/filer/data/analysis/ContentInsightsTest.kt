package com.navaneeth.filer.data.analysis

import com.navaneeth.filer.data.storage.StorageVolumeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ContentInsightsTest {

    private val minute = 60_000L
    private val mb = 1024L * 1024

    private fun photo(name: String, at: Long, size: Long, vararg v: Float) =
        ContentInsights.PhotoVector(FoundFile("/storage/emulated/0/DCIM/Camera/$name", size, at), v)

    // Similar shots

    @Test
    fun burstOfLookalikesKeepsTheBiggest() {
        val groups = ContentInsights.similarGroups(
            listOf(
                photo("a.jpg", 0, 3 * mb, 1f, 0f, 0f),
                photo("b.jpg", minute, 5 * mb, 0.99f, 0.05f, 0f),
                photo("c.jpg", 2 * minute, 4 * mb, 0.98f, 0.1f, 0f)
            )
        )
        assertEquals(1, groups.size)
        assertEquals("b.jpg", groups[0].keep.name)
        assertEquals(listOf("c.jpg", "a.jpg"), groups[0].others.map { it.name })
        assertEquals(7 * mb, groups[0].extraBytes)
    }

    @Test
    fun differentPicturesOrFarApartAreNotGrouped() {
        val groups = ContentInsights.similarGroups(
            listOf(
                photo("a.jpg", 0, mb, 1f, 0f, 0f),
                photo("b.jpg", minute, mb, 0f, 1f, 0f), // a different picture
                photo("c.jpg", 60 * minute, mb, 0f, 1f, 0f) // the same picture an hour later
            )
        )
        assertTrue(groups.isEmpty())
    }

    @Test
    fun runsCompareWithTheirFirstPhotoSoTheyDontDrift() {
        // Each photo is close to the one before, but the last has drifted away from the first.
        val groups = ContentInsights.similarGroups(
            listOf(
                photo("a.jpg", 0, mb, 1f, 0f),
                photo("b.jpg", minute, mb, 0.95f, 0.31f),
                photo("c.jpg", 2 * minute, mb, 0.8f, 0.6f)
            )
        )
        assertEquals(listOf(listOf("a.jpg", "b.jpg")), groups.map { g -> (listOf(g.keep) + g.others).map { it.name }.sorted() })
    }

    // Space by kind and cleanups

    @Test
    fun spaceAddsUpPerKind() {
        val (bytes, other) = ContentInsights.spaceByKind(
            listOf(
                FoundFile("/a", 10, 0) to ContentKind.FOOD,
                FoundFile("/b", 5, 0) to ContentKind.FOOD,
                FoundFile("/c", 7, 0) to null
            )
        )
        assertEquals(mapOf(ContentKind.FOOD to 15L), bytes)
        assertEquals(7L, other)
    }

    @Test
    fun forwardsLeaveChatMediaSoNothingCountsTwice() {
        val forward = FoundFile("/storage/emulated/0/WhatsApp/Media/WhatsApp Images/gm.jpg", 2 * mb, 0)
        val holiday = FoundFile("/storage/emulated/0/WhatsApp/Media/WhatsApp Images/trip.jpg", 3 * mb, 0)
        val report = report(mapOf(CleanupKind.CHAT_MEDIA to listOf(holiday, forward)))
        val contents = ContentReport(ContentReport.Status.READY, forwards = listOf(forward))

        val updated = ContentInsights.withContents(report, contents, emptySet())

        assertEquals(listOf(holiday), updated.files(CleanupKind.CHAT_MEDIA))
        assertEquals(listOf(forward), updated.files(CleanupKind.FORWARDS))
        assertEquals(5 * mb, updated.clutterBytes)
    }

    @Test
    fun sensitiveFilesAreNeverClutter() {
        val id = FoundFile("/storage/emulated/0/Download/id.jpg", 4 * mb, 0)
        val updated = ContentInsights.withContents(report(emptyMap()), ContentReport(ContentReport.Status.READY, sensitive = listOf(id)), emptySet())
        assertEquals(listOf(id), updated.files(CleanupKind.SENSITIVE))
        assertEquals(0L, updated.clutterBytes)
        assertTrue(StorageInsights.highlights(updated).first() is Highlight.Sensitive)
    }

    @Test
    fun installersForAppsYouHaveAreCalledOut() {
        val old = FoundFile("/storage/emulated/0/Download/app.apk", 30 * mb, 0)
        val new = FoundFile("/storage/emulated/0/Download/other.apk", 20 * mb, 0)
        val updated = ContentInsights.withContents(report(mapOf(CleanupKind.INSTALLERS to listOf(old, new))), ContentReport.NONE, setOf(old.path))
        val highlight = StorageInsights.highlights(updated).filterIsInstance<Highlight.InstallersYouHave>().single()
        assertEquals(1, highlight.have)
        assertEquals(2, highlight.total)
    }

    private fun report(cleanups: Map<CleanupKind, List<FoundFile>>) = VolumeReport(
        volume = StorageVolumeInfo(File("/storage/emulated/0"), "Internal", isPrimary = true, totalBytes = 128L * 1024 * mb, freeBytes = 30L * 1024 * mb),
        scannedAt = 0,
        fileCount = 0,
        spaceByType = emptyMap(),
        root = FolderNode("0", "/storage/emulated/0", 0, 0, emptyList()),
        cleanups = cleanups,
        duplicates = emptyList(),
        addedByMonth = emptyList(),
        recentSources = emptyMap(),
        chatAppBytes = emptyMap(),
        screenshotCount = 0
    )
}
