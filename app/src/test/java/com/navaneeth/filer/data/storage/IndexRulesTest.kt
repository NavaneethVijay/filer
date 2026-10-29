package com.navaneeth.filer.data.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class IndexRulesTest {

    @Test
    fun logsAndXmlFilesAreNeverIndexed() {
        assertNull(IndexRules.kindOf("app.log", "text/plain"))
        assertNull(IndexRules.kindOf("crash_dump.log", "text/x-log"))
        assertNull(IndexRules.kindOf("logcat.log", ""))
        assertNull(IndexRules.kindOf("dump.trace", ""))

        assertNull(IndexRules.kindOf("preferences.xml", "text/xml"))
        assertNull(IndexRules.kindOf("config.xml", "application/xml"))
        assertNull(IndexRules.kindOf("layout.xml", ""))
    }

    @Test
    fun configsAndTempFilesAreNeverIndexed() {
        assertNull(IndexRules.kindOf("settings.ini", "text/plain"))
        assertNull(IndexRules.kindOf("system.conf", ""))
        assertNull(IndexRules.kindOf("build.prop", ""))
        assertNull(IndexRules.kindOf("app.properties", ""))
        assertNull(IndexRules.kindOf("cache.tmp", ""))
        assertNull(IndexRules.kindOf("backup.bak", ""))
    }

    @Test
    fun documentsAreIndexed() {
        assertEquals(IndexKind.PDFS, IndexRules.kindOf("statement.pdf", "application/pdf"))
        assertEquals(IndexKind.PDFS, IndexRules.kindOf("receipt.pdf", ""))

        assertEquals(IndexKind.TEXT, IndexRules.kindOf("notes.txt", "text/plain"))
        assertEquals(IndexKind.TEXT, IndexRules.kindOf("README.md", "text/markdown"))
        assertEquals(IndexKind.TEXT, IndexRules.kindOf("report.docx", ""))
        assertEquals(IndexKind.TEXT, IndexRules.kindOf("letter.odt", ""))
        assertEquals(IndexKind.TEXT, IndexRules.kindOf("expenses.csv", "text/csv"))
    }

    @Test
    fun imagesAreIndexedWhenAboveMinBytes() {
        val minBytes = 35 * 1024L
        assertEquals(IndexKind.PHOTOS, IndexRules.kindOf("photo.jpg", "image/jpeg", minBytes))
        assertEquals(IndexKind.PHOTOS, IndexRules.kindOf("screenshot.png", "image/png", minBytes + 100))
        assertNull(IndexRules.kindOf("tiny_icon.png", "image/png", minBytes - 1))
    }

    @Test
    fun secretsAreNeverIndexed() {
        assertNull(IndexRules.kindOf("id_rsa", ""))
        assertNull(IndexRules.kindOf(".env", ""))
        assertNull(IndexRules.kindOf("server.key", ""))
        assertNull(IndexRules.kindOf("passwords.txt", "text/plain"))
        assertNull(IndexRules.kindOf("wallet.kdbx", ""))
    }

    @Test
    fun skippedDirectoriesAreDetected() {
        assertTrue(IndexRules.isSkippedDirectory(File("LOST.DIR")))
        assertTrue(IndexRules.isSkippedDirectory(File("lost.dir")))
        assertTrue(IndexRules.isSkippedDirectory(File("logs")))
        assertTrue(IndexRules.isSkippedDirectory(File("Logs")))
        assertTrue(IndexRules.isSkippedDirectory(File("cache")))
        assertTrue(IndexRules.isSkippedDirectory(File("CrashReports")))
        assertTrue(IndexRules.isSkippedDirectory(File(".git")))
        assertTrue(IndexRules.isSkippedDirectory(File(".filertrash")))

        assertFalse(IndexRules.isSkippedDirectory(File("Documents")))
        assertFalse(IndexRules.isSkippedDirectory(File("Download")))
        assertFalse(IndexRules.isSkippedDirectory(File("DCIM")))
        assertFalse(IndexRules.isSkippedDirectory(File("Pictures")))
    }
}
