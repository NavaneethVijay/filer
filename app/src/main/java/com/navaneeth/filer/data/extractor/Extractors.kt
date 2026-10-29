package com.navaneeth.filer.data.extractor

import android.content.Context
import android.net.Uri
import android.util.Xml
import com.navaneeth.filer.data.storage.IndexKind
import com.navaneeth.filer.data.storage.IndexRules
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.CancellationException
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * A file the indexer can't read. Its [reason] is stored on the file so it isn't tried again until
 * the file itself changes (a password-protected PDF would fail the same way on every pass).
 */
class UnreadableFileException(val reason: String, cause: Throwable? = null) : Exception(reason, cause) {
    companion object {
        /** Needs a password to open: skipped, still found by name. */
        const val PROTECTED = "PROTECTED"
        /** Damaged, unsupported or too big to read. */
        const val UNREADABLE = "UNREADABLE"
    }
}

/** Runs [read], turning any failure into an [UnreadableFileException] (cancellation passes through). */
private inline fun <T> readOrThrow(read: () -> T): T = try {
    read()
} catch (e: CancellationException) {
    throw e
} catch (e: UnreadableFileException) {
    throw e
} catch (e: InvalidPasswordException) {
    throw UnreadableFileException(UnreadableFileException.PROTECTED, e)
} catch (t: Throwable) {
    // Includes OutOfMemoryError from huge PDFs: retrying would only fail again.
    throw UnreadableFileException(UnreadableFileException.UNREADABLE, t)
}

class PdfExtractor(private val context: Context) {

    companion object {
        const val MAX_PAGES = 50
        const val MAX_CHARS_TOTAL = 100_000
    }

    fun extract(uri: Uri): List<TextChunk> = readOrThrow {
        val chunker = TextChunker()
        val allChunks = mutableListOf<TextChunk>()

        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            PDDocument.load(inputStream).use { document ->
                val numPages = minOf(document.numberOfPages, MAX_PAGES)
                val stripper = PDFTextStripper()
                var totalChars = 0

                for (page in 1..numPages) {
                    if (totalChars >= MAX_CHARS_TOTAL) break
                    stripper.startPage = page
                    stripper.endPage = page
                    val pageText = stripper.getText(document) ?: ""
                    if (pageText.isNotBlank()) {
                        totalChars += pageText.length
                        val pageChunks = chunker.chunk(pageText, pageNumber = page)
                        allChunks.addAll(pageChunks)
                    }
                }
            }
        }

        allChunks
    }
}

class PlainTextExtractor(private val context: Context) {

    companion object {
        const val MAX_FILE_SIZE_BYTES = 5 * 1024 * 1024L // 5 MB cap
    }

    fun extract(uri: Uri, sizeBytes: Long): List<TextChunk> = readOrThrow {
        if (sizeBytes > MAX_FILE_SIZE_BYTES) return@readOrThrow emptyList()

        val text = context.contentResolver.openInputStream(uri)?.use { stream ->
            stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } ?: return@readOrThrow emptyList()

        TextChunker().chunk(text)
    }
}

/**
 * Word (.docx) and OpenDocument (.odt) text: both are zipped XML, read without extra libraries.
 * Only the body is read (no headers, footers or comments).
 */
class OfficeTextExtractor(private val context: Context) {

    companion object {
        val EXTENSIONS = setOf("docx", "odt")
        const val MAX_CHARS_TOTAL = PdfExtractor.MAX_CHARS_TOTAL
        /** The XML part is read only this far: a small file can unzip to something huge. */
        private const val MAX_XML_BYTES = 30 * 1024 * 1024L
    }

    fun extract(uri: Uri, extension: String): List<TextChunk> = readOrThrow {
        val docx = extension == "docx"
        val part = if (docx) "word/document.xml" else "content.xml"
        val text = context.contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                generateSequence { zip.nextEntry }.firstOrNull { it.name == part } ?: return@use null
                readXmlText(LimitedStream(zip, MAX_XML_BYTES), docx)
            }
        } ?: throw UnreadableFileException(UnreadableFileException.UNREADABLE)
        TextChunker().chunk(text)
    }

    /**
     * Paragraph text with line breaks. In .docx the words are in `w:t` (other text is field codes or
     * deleted revisions); in .odt every text node inside a paragraph or heading counts.
     */
    private fun readXmlText(input: InputStream, docx: Boolean): String {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)
        val out = StringBuilder()
        var inText = 0
        // .docx: <w:tabs> lists tab stops; only a <w:tab/> outside it is a tab in the text.
        var inTabStops = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT && out.length < MAX_CHARS_TOTAL) {
            val name = parser.name?.substringAfter(':')
            when (event) {
                XmlPullParser.START_TAG -> when {
                    docx && name == "t" -> inText++
                    !docx && (name == "p" || name == "h") -> inText++
                    docx && name == "tabs" -> inTabStops = true
                    name == "tab" && !inTabStops -> out.append('\t')
                    name == "br" || name == "cr" || name == "line-break" -> out.append('\n')
                    // .odt: <text:s/> is one space, <text:s text:c="3"/> several.
                    !docx && name == "s" -> out.append(" ".repeat((parser.getAttributeValue(null, "text:c")?.toIntOrNull() ?: 1).coerceIn(1, 64)))
                }
                XmlPullParser.END_TAG -> when {
                    docx && name == "t" -> inText--
                    docx && name == "tabs" -> inTabStops = false
                    !docx && (name == "p" || name == "h") -> { inText--; out.append('\n') }
                    docx && name == "p" -> out.append('\n')
                }
                XmlPullParser.TEXT -> if (inText > 0) out.append(parser.text)
            }
            event = parser.next()
        }
        return out.toString()
    }

    /** Stops reading after [limit] bytes. */
    private class LimitedStream(private val inner: InputStream, private var limit: Long) : InputStream() {
        override fun read(): Int = if (limit <= 0) -1 else inner.read().also { if (it >= 0) limit-- }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (limit <= 0) return -1
            val n = inner.read(b, off, minOf(len.toLong(), limit).toInt())
            if (n > 0) limit -= n
            return n
        }
    }
}

/** Reading a file's own text, shared by smart search and the AI plugins. */
object DocumentText {

    /** True when the file's text can be read directly (PDFs, plain text and code, Word, OpenDocument). */
    fun canRead(name: String): Boolean = IndexRules.kindOf(name, "") in setOf(IndexKind.PDFS, IndexKind.TEXT)

    /** The file's text in passages; throws [UnreadableFileException] when it can't be read. */
    fun extract(context: Context, file: File): List<TextChunk> {
        val uri = Uri.fromFile(file)
        return when (val ext = file.name.substringAfterLast('.', "").lowercase()) {
            "pdf" -> PdfExtractor(context).extract(uri)
            in OfficeTextExtractor.EXTENSIONS -> OfficeTextExtractor(context).extract(uri, ext)
            else -> PlainTextExtractor(context).extract(uri, file.length())
        }
    }
}
