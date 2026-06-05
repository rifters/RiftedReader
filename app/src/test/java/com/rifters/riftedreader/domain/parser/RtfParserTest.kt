package com.rifters.riftedreader.domain.parser

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RtfParserTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val parser = RtfParser()

    @Test
    fun extractsMetadataAndBasicFormattingFromRtf() = runBlocking {
        val file = tempFolder.newFile("sample.rtf")
        file.writeText(
            """{\rtf1\ansi{\info{\title Sample RTF}{\author Test Author}}\b Bold\b0 plain\par \i Italic\i0 plain}""",
            Charsets.ISO_8859_1
        )

        val metadata = parser.extractMetadata(file)
        assertEquals("Sample RTF", metadata.title)
        assertEquals("Test Author", metadata.author)
        assertEquals("RTF", metadata.format)
        assertEquals(1, metadata.totalPages)

        val page = parser.getPageContent(file, 0)
        assertEquals("Sample RTF", page.title)
        assertTrue(page.text.contains("Bold plain"))
        assertTrue(page.text.contains("Italic"))
        assertTrue(page.html?.contains("<b>Bold</b>") == true)
        assertTrue(page.html?.contains("<i>Italic</i>") == true)
        assertEquals(PageContent.EMPTY, parser.getPageContent(file, 1))
    }
}
