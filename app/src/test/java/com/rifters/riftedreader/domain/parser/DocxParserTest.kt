package com.rifters.riftedreader.domain.parser

import kotlinx.coroutines.runBlocking
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DocxParserTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val parser = DocxParser()

    @Test
    fun extractsMetadataHtmlAndTocFromDocx() = runBlocking {
        val file = tempFolder.newFile("sample.docx")
        XWPFDocument().use { document ->
            document.properties.coreProperties.title = "Sample DOCX"
            document.properties.coreProperties.creator = "Test Author"
            document.properties.coreProperties.description = "Word sample"

            val heading = document.createParagraph()
            heading.style = "Heading1"
            heading.createRun().setText("Heading One")

            val paragraph = document.createParagraph()
            paragraph.createRun().setText("Plain ")
            paragraph.createRun().apply {
                isBold = true
                setText("Bold")
            }
            paragraph.createRun().apply {
                isItalic = true
                setText(" Italic")
            }

            file.outputStream().use(document::write)
        }

        val metadata = parser.extractMetadata(file)
        assertEquals("Sample DOCX", metadata.title)
        assertEquals("Test Author", metadata.author)
        assertEquals("Word sample", metadata.description)
        assertEquals("DOCX", metadata.format)
        assertEquals(1, metadata.totalPages)

        val page = parser.getPageContent(file, 0)
        assertEquals("Sample DOCX", page.title)
        assertTrue(page.text.contains("Heading One"))
        assertTrue(page.text.contains("Plain Bold Italic"))
        assertTrue(page.html?.contains("<b>Bold</b>") == true)
        assertTrue(page.html?.contains("<i> Italic</i>") == true)

        val toc = parser.getTableOfContents(file)
        assertEquals(listOf("Heading One"), toc.map { it.title })
        assertEquals(listOf(0), toc.map { it.pageNumber })
        assertEquals(listOf(0), toc.map { it.level })
    }
}
