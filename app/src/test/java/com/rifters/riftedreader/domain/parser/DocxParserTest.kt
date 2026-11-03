package com.rifters.riftedreader.domain.parser

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocxParserTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val parser = DocxParser()

    private fun createDocxFile(
        file: File,
        title: String? = null,
        author: String? = null,
        description: String? = null,
        documentBodyXml: String
    ) {
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            // docProps/core.xml
            if (title != null || author != null || description != null) {
                zip.putNextEntry(ZipEntry("docProps/core.xml"))
                val coreXml = buildString {
                    append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
                    append("""<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/">""")
                    if (title != null) append("<dc:title>$title</dc:title>")
                    if (author != null) append("<dc:creator>$author</dc:creator>")
                    if (description != null) append("<dc:description>$description</dc:description>")
                    append("</cp:coreProperties>")
                }
                zip.write(coreXml.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            // word/document.xml
            zip.putNextEntry(ZipEntry("word/document.xml"))
            val docXml = buildString {
                append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
                append("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">""")
                append("<w:body>")
                append(documentBodyXml)
                append("</w:body>")
                append("</w:document>")
            }
            zip.write(docXml.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }

    @Test
    fun extractsMetadataHtmlAndTocFromDocx() = runBlocking {
        val file = tempFolder.newFile("sample.docx")
        val bodyXml = """
            <w:p>
                <w:pPr>
                    <w:pStyle w:val="Heading1"/>
                </w:pPr>
                <w:r>
                    <w:t>Heading One</w:t>
                </w:r>
            </w:p>
            <w:p>
                <w:r>
                    <w:t>Plain </w:t>
                </w:r>
                <w:r>
                    <w:rPr>
                        <w:b/>
                    </w:rPr>
                    <w:t>Bold</w:t>
                </w:r>
                <w:r>
                    <w:rPr>
                        <w:i/>
                    </w:rPr>
                    <w:t> Italic</w:t>
                </w:r>
            </w:p>
        """.trimIndent()

        createDocxFile(
            file = file,
            title = "Sample DOCX",
            author = "Test Author",
            description = "Word sample",
            documentBodyXml = bodyXml
        )

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

    @Test
    fun parsesTablesAndNestedTables() = runBlocking {
        val file = tempFolder.newFile("table_sample.docx")
        val bodyXml = """
            <w:p>
                <w:r><w:t>Before Table</w:t></w:r>
            </w:p>
            <w:tbl>
                <w:tr>
                    <w:tc>
                        <w:p>
                            <w:r><w:t>Cell 1</w:t></w:r>
                        </w:p>
                    </w:tc>
                    <w:tc>
                        <w:p>
                            <w:r><w:t>Cell 2</w:t></w:r>
                        </w:p>
                        <!-- Nested Table -->
                        <w:tbl>
                            <w:tr>
                                <w:tc>
                                    <w:p>
                                        <w:r><w:t>Nested Cell</w:t></w:r>
                                    </w:p>
                                </w:tc>
                            </w:tr>
                        </w:tbl>
                    </w:tc>
                </w:tr>
            </w:tbl>
            <w:p>
                <w:r><w:t>After Table</w:t></w:r>
            </w:p>
        """.trimIndent()

        createDocxFile(file = file, documentBodyXml = bodyXml)

        val page = parser.getPageContent(file, 0)
        assertTrue(page.text.contains("Before Table"))
        assertTrue(page.text.contains("Cell 1"))
        assertTrue(page.text.contains("Cell 2"))
        assertTrue(page.text.contains("Nested Cell"))
        assertTrue(page.text.contains("After Table"))
        assertTrue(page.html?.contains("<p>Nested Cell</p>") == true)
    }

    @Test
    fun parsesMultipleHeadingLevelsIntoTocAndHtml() = runBlocking {
        val file = tempFolder.newFile("headings.docx")
        val bodyXml = """
            <w:p>
                <w:pPr><w:pStyle w:val="Heading 1"/></w:pPr>
                <w:r><w:t>Chapter One</w:t></w:r>
            </w:p>
            <w:p>
                <w:pPr><w:pStyle w:val="Heading2"/></w:pPr>
                <w:r><w:t>Section 1.1</w:t></w:r>
            </w:p>
            <w:p>
                <w:pPr><w:pStyle w:val="Heading3"/></w:pPr>
                <w:r><w:t>Subsection 1.1.1</w:t></w:r>
            </w:p>
        """.trimIndent()

        createDocxFile(file = file, documentBodyXml = bodyXml)

        val toc = parser.getTableOfContents(file)
        assertEquals(3, toc.size)
        assertEquals("Chapter One", toc[0].title)
        assertEquals(0, toc[0].level)
        assertEquals("Section 1.1", toc[1].title)
        assertEquals(1, toc[1].level)
        assertEquals("Subsection 1.1.1", toc[2].title)
        assertEquals(2, toc[2].level)

        val page = parser.getPageContent(file, 0)
        assertTrue(page.html?.contains("<h1>Chapter One</h1>") == true)
        assertTrue(page.html?.contains("<h2>Section 1.1</h2>") == true)
        assertTrue(page.html?.contains("<h3>Subsection 1.1.1</h3>") == true)
    }

    @Test
    fun parsesSpecialFormattingRunsAndHyperlinks() = runBlocking {
        val file = tempFolder.newFile("formatting.docx")
        val bodyXml = """
            <w:p>
                <w:r>
                    <w:rPr>
                        <w:strike/>
                        <w:u w:val="single"/>
                    </w:rPr>
                    <w:t>Strikethrough Underline</w:t>
                </w:r>
                <w:r>
                    <w:tab/>
                    <w:t>After Tab</w:t>
                    <w:br/>
                    <w:t>After Linebreak</w:t>
                </w:r>
                <w:hyperlink>
                    <w:r>
                        <w:t>Link Text</w:t>
                    </w:r>
                </w:hyperlink>
            </w:p>
        """.trimIndent()

        createDocxFile(file = file, documentBodyXml = bodyXml)

        val page = parser.getPageContent(file, 0)
        assertTrue(page.html?.contains("<s><u>Strikethrough Underline</u></s>") == true)
        assertTrue(page.html?.contains("&nbsp;&nbsp;&nbsp;&nbsp;After Tab<br/>After Linebreak") == true)
        assertTrue(page.html?.contains("Link Text") == true)
        assertTrue(page.text.contains("Strikethrough Underline"))
        assertTrue(page.text.contains("After Tab"))
        assertTrue(page.text.contains("Link Text"))
    }

    @Test
    fun infersTitleWhenCorePropertiesMissing() = runBlocking {
        val file = tempFolder.newFile("unnamed_book.docx")
        val bodyXml = """
            <w:p>
                <w:pPr><w:pStyle w:val="Heading1"/></w:pPr>
                <w:r><w:t>My Inferred Title</w:t></w:r>
            </w:p>
            <w:p>
                <w:r><w:t>Body content here.</w:t></w:r>
            </w:p>
        """.trimIndent()

        createDocxFile(file = file, documentBodyXml = bodyXml)

        val meta = parser.extractMetadata(file)
        assertEquals("My Inferred Title", meta.title)
    }

    @Test
    fun handlesEmptyParagraphsGracefully() = runBlocking {
        val file = tempFolder.newFile("empty_para.docx")
        val bodyXml = """
            <w:p/>
            <w:p>
                <w:r><w:t>Non empty</w:t></w:r>
            </w:p>
        """.trimIndent()

        createDocxFile(file = file, documentBodyXml = bodyXml)

        val page = parser.getPageContent(file, 0)
        assertTrue(page.html?.contains("<p><br/></p>") == true)
        assertTrue(page.html?.contains("<p>Non empty</p>") == true)
        assertEquals("Non empty", page.text)
    }

    @Test
    fun handlesNonExistentAndCorruptFilesGracefully() = runBlocking {
        val nonExistent = File(tempFolder.root, "does_not_exist.docx")
        val meta = parser.extractMetadata(nonExistent)
        assertEquals("does_not_exist", meta.title)

        val content = parser.getPageContent(nonExistent, 0)
        assertEquals("", content.text)
        assertEquals("<p></p>", content.html)

        val toc = parser.getTableOfContents(nonExistent)
        assertTrue(toc.isEmpty())

        val corrupt = tempFolder.newFile("corrupt.docx")
        corrupt.writeText("Not a valid zip or docx content")
        val corruptContent = parser.getPageContent(corrupt, 0)
        assertEquals("", corruptContent.text)
        assertEquals("<p></p>", corruptContent.html)
    }
}
