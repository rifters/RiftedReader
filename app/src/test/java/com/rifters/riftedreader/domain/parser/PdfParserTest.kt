package com.rifters.riftedreader.domain.parser

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PdfParserTest {

    private val parser = PdfParser()
    private val missing = File("/tmp/no_such_file.pdf")

    // ── canParse ────────────────────────────────────────────────────

    @Test fun `canParse accepts pdf`() {
        assertTrue(parser.canParse(File("document.pdf")))
    }

    @Test fun `canParse rejects non-pdf extensions`() {
        assertFalse(parser.canParse(File("book.epub")))
        assertFalse(parser.canParse(File("book.mobi")))
        assertFalse(parser.canParse(File("book.cbz")))
        assertFalse(parser.canParse(File("book.fb2")))
    }

    @Test fun `canParse is case-insensitive`() {
        assertTrue(parser.canParse(File("document.PDF")))
        assertTrue(parser.canParse(File("document.Pdf")))
    }

    // ── getPageCount ────────────────────────────────────────────────

    @Test fun `getPageCount returns 1 for missing file`() {
        val count = runBlocking { parser.getPageCount(missing) }
        assertEquals(1, count)
    }

    // ── getPageContent ──────────────────────────────────────────────

    @Test fun `getPageContent returns placeholder for missing file page 0`() {
        val content = runBlocking { parser.getPageContent(missing, 0) }
        assertNotNull(content)
        assertEquals("Could not open PDF", content.text)
    }

    @Test fun `getPageContent returns placeholder for missing file negative page`() {
        val content = runBlocking { parser.getPageContent(missing, -1) }
        assertNotNull(content)
        assertEquals("Could not open PDF", content.text)
    }

    @Test fun `getPageContent placeholder html contains error class`() {
        val content = runBlocking { parser.getPageContent(missing, 0) }
        assertTrue(content.html?.contains("image-page-error") == true)
    }

    @Test fun `getPageContent placeholder html mentions page number`() {
        val content = runBlocking { parser.getPageContent(missing, 2) }
        // placeholderPage uses page + 1
        assertTrue(content.html?.contains("Page 3") == true)
    }

    // ── getTableOfContents ──────────────────────────────────────────

    @Test fun `getTableOfContents always returns empty list`() {
        val toc = runBlocking { parser.getTableOfContents(missing) }
        assertTrue(toc.isEmpty())
    }

    // ── extractMetadata ─────────────────────────────────────────────

    @Test fun `extractMetadata totalPages is at least 1 for missing file`() {
        val meta = runBlocking { parser.extractMetadata(missing) }
        assertTrue(meta.totalPages >= 1)
    }

    @Test fun `extractMetadata format is PDF`() {
        val meta = runBlocking { parser.extractMetadata(missing) }
        assertEquals("PDF", meta.format)
    }

    @Test fun `extractMetadata title falls back to filename without extension`() {
        val meta = runBlocking { parser.extractMetadata(missing) }
        // missing.nameWithoutExtension = "no_such_file"
        assertEquals("no_such_file", meta.title)
    }
}
