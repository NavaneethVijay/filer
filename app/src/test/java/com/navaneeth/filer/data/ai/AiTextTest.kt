package com.navaneeth.filer.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AiTextTest {

    @Test fun cameraAndChatNamesAreUnclear() {
        listOf(
            "IMG_20260814_123456.jpg", "PXL_20261012_183455.jpg", "IMG-20260814-WA0023.pdf", "DSC01234.JPG",
            "Screenshot_20261003-101010.png", "Scan_001.pdf", "document(1).pdf", "download (3).pdf",
            "1698412345678.jpg", "Recording 014.m4a", "PTT-20261001-WA0004.opus"
        ).forEach { assertTrue(it, AiText.isUnclearName(it)) }
    }

    @Test fun realNamesAreClear() {
        listOf(
            "Electricity bill August.pdf", "Resume_Navaneeth.pdf", "Goa trip plan.docx", "tax-2025.xlsx",
            "family.jpg", "notes.md"
        ).forEach { assertFalse(it, AiText.isUnclearName(it)) }
    }

    @Test fun tagsAreNormalised() {
        assertEquals("tax-docs", AiText.normalizeTag("#Tax Docs"))
        assertEquals("receipts", AiText.normalizeTag("  receipts! "))
        assertEquals("बिल", AiText.normalizeTag("#बिल"))
        assertNull(AiText.normalizeTag("#"))
        assertNull(AiText.normalizeTag("a"))
    }

    @Test fun suggestedNameKeepsTheFilesExtension() {
        assertEquals("2026-08_Electricity_Bill.pdf", AiText.cleanSuggestedName("2026-08 Electricity Bill", "IMG_20260814_WA0023.pdf"))
        assertEquals("2026-08_Electricity_Bill.pdf", AiText.cleanSuggestedName("\"2026-08_Electricity_Bill.pdf\"", "IMG-WA0023.pdf"))
        assertEquals("Bill_July.pdf", AiText.cleanSuggestedName("bills/Bill: July", "scan.pdf"))
        assertNull(AiText.cleanSuggestedName("", "scan.pdf"))
        assertNull(AiText.cleanSuggestedName("scan", "scan.pdf"))
    }

    @Test fun hashWordsAreTags() {
        assertEquals(listOf("receipts", "2024") to "coffee shop", AiText.extractTags("#receipts coffee #2024 shop"))
        assertEquals(emptyList<String>() to "c# books", AiText.extractTags("c# books"))
        assertEquals(listOf("tax") to "", AiText.extractTags("#Tax"))
    }

    @Test fun ruleNamesNeedMoreThanADate() {
        val day = LocalDate.of(2026, 8, 12)
        assertEquals("2026-08-12_Invoice_Tata_Power.pdf", AiText.ruleName("Scan_001.pdf", day, "invoice", "Tata Power", null))
        assertEquals("2026-08-14_Beach_Goa.jpg", AiText.ruleName("IMG_1.jpg", LocalDate.of(2026, 8, 14), "beach", null, "Goa"))
        // The title already says what it is: no "Invoice Tax Invoice".
        assertEquals("2026-08-12_Tax_Invoice.pdf", AiText.ruleName("Scan_001.pdf", day, "invoice", "TAX INVOICE", null))
        assertEquals("2026-08-12_GST_Bill.pdf", AiText.ruleName("Scan_001.pdf", day, null, "GST bill", null))
        assertNull(AiText.ruleName("IMG_1.jpg", day, null, null, null))
    }

    @Test fun titleIsTheFirstLineThatReadsLikeOne() {
        assertEquals("Tata Power Electricity Bill", AiText.titleLine("12/08/2026\nPage 1 of 2\nTata Power Electricity Bill\nAccount 12345"))
        assertNull(AiText.titleLine("1234 5678\n%%%%\nx"))
    }

    @Test fun datesAreFoundInText() {
        val today = LocalDate.of(2026, 10, 26)
        assertEquals(LocalDate.of(2026, 8, 12), AiText.dateIn("Bill date: 2026-08-12", today))
        assertEquals(LocalDate.of(2026, 8, 12), AiText.dateIn("Date 12/08/2026 due", today))
        assertEquals(LocalDate.of(2026, 8, 12), AiText.dateIn("Issued 12th Aug 2026", today))
        assertEquals(LocalDate.of(2026, 8, 12), AiText.dateIn("August 12, 2026", today))
        assertNull(AiText.dateIn("Invoice 31/02/2026 and 1/1/1890", today))
    }

    @Test fun passagesForTagsSkipTinyText() {
        assertTrue(AiText.passagesForTags("too short").isEmpty())
        assertEquals(2, AiText.passagesForTags("word ".repeat(300)).size)
    }
}
