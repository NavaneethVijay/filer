package com.navaneeth.filer.data.search

import com.navaneeth.filer.ui.model.FileCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class QueryParserTest {
    // A Saturday.
    private val today = LocalDate.of(2026, 10, 24)
    private fun parse(q: String) = QueryParser.parse(q, gazetteer = null, today = today)
    private fun ms(d: LocalDate) = d.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test fun pdfsDownloadedTwoDaysAgo() {
        val p = parse("pdf files downloaded 2 days ago")
        assertEquals(setOf(FileCategory.PDFS), p.categories)
        assertEquals(DateField.ADDED, p.dateField)
        assertEquals(ms(today.minusDays(2)), p.date!!.start)
        assertEquals(ms(today.minusDays(1)) - 1, p.date!!.end)
        assertEquals("2 days ago", p.date!!.phrase)
        assertTrue(p.terms.isEmpty())
    }

    @Test fun termsSurviveFilters() {
        val p = parse("show me the invoice from whatsapp last week")
        assertEquals(listOf("invoice"), p.terms)
        assertEquals("WhatsApp", p.folders.single().needle)
        assertEquals(ms(LocalDate.of(2026, 10, 12)), p.date!!.start)
    }

    @Test fun monthsAndYears() {
        assertEquals(ms(LocalDate.of(2025, 12, 1)), parse("photos last december").date!!.start)
        assertEquals(ms(LocalDate.of(2026, 3, 1)), parse("docs in march").date!!.start)
        assertEquals(ms(LocalDate.of(2023, 1, 1)), parse("pictures from 2023").date!!.start)
        assertEquals(ms(LocalDate.of(2026, 3, 5)), parse("files on 5th march").date!!.start)
        // "may" alone is a verb.
        assertNull(parse("files i may need").date)
        assertEquals(ms(LocalDate.of(2026, 5, 1)), parse("videos in may").date!!.start)
    }

    @Test fun sinceAndRanges() {
        val since = parse("screenshots since monday")
        assertEquals(ms(LocalDate.of(2026, 10, 19)), since.date!!.start)
        assertTrue(FileCategory.IMAGES in since.categories)
        assertEquals("Screenshots", since.folders.single().needle)
        assertEquals(ms(today.minusDays(6)), parse("apks in the last 7 days").date!!.start)
        assertEquals(ms(today.minusDays(1)), parse("yesterday").date!!.start)
    }

    @Test fun sizesAndExtensions() {
        val p = parse("large videos")
        assertEquals(200L * 1024 * 1024, p.size!!.minBytes)
        val q = parse("docx over 10mb")
        assertEquals(setOf("docx"), q.extensions)
        assertEquals(10L * 1024 * 1024, q.size!!.minBytes)
    }

    @Test fun takenAndFolders() {
        val p = parse("photos taken yesterday in the camera")
        assertEquals(DateField.TAKEN, p.dateField)
        assertEquals("DCIM", p.folders.single().needle)
        assertEquals("Work", parse("pdfs in work folder").folders.single().label)
    }

    @Test fun plainQueryHasNoFilters() {
        val p = parse("car insurance renewal")
        assertTrue(p.toString(), p.kinds.isEmpty())
        assertEquals(listOf("car", "insurance", "renewal"), p.terms)
    }
}
