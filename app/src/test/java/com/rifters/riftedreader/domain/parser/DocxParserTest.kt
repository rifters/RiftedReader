package com.rifters.riftedreader.domain.parser

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        documentBodyXml: String,
        footnotesXml: String? = null,
        endnotesXml: String? = null
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

            // word/footnotes.xml
            if (footnotesXml != null) {
                zip.putNextEntry(ZipEntry("word/footnotes.xml"))
                val xml = buildString {
                    append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
                    append("""<w:footnotes xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">""")
                    append(footnotesXml)
                    append("</w:footnotes>")
                }
                zip.write(xml.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            // word/endnotes.xml
            if (endnotesXml != null) {
                zip.putNextEntry(ZipEntry("word/endnotes.xml"))
                val xml = buildString {
                    append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
                    append("""<w:endnotes xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">""")
                    append(endnotesXml)
                    append("</w:endnotes>")
                }
                zip.write(xml.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
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
    fun parsesDocumentWithFootnotesAndSkipsSeparators() = runBlocking {
        val file = tempFolder.newFile("footnotes.docx")
        val footnotesXml = """
            <w:footnote w:type="separator" w:id="-1">
                <w:p><w:r><w:separator/></w:r></w:p>
            </w:footnote>
            <w:footnote w:type="continuationSeparator" w:id="0">
                <w:p><w:r><w:continuationSeparator/></w:r></w:p>
            </w:footnote>
            <w:footnote w:type="continuationNotice" w:id="99">
                <w:p><w:r><w:t>Layout Continuation Notice</w:t></w:r></w:p>
            </w:footnote>
            <w:footnote w:id="1">
                <w:p>
                    <w:r><w:footnoteRef/></w:r>
                    <w:r><w:t>This is an important footnote.</w:t></w:r>
                </w:p>
            </w:footnote>
            <w:footnote w:id="2">
                <w:p>
                    <w:r>
                        <w:rPr><w:b/></w:rPr>
                        <w:t>Bold note</w:t>
                    </w:r>
                </w:p>
            </w:footnote>
        """.trimIndent()

        val bodyXml = """
            <w:p>
                <w:r><w:t>Sentence referencing first note.</w:t></w:r>
                <w:r><w:footnoteReference w:id="1"/></w:r>
            </w:p>
            <w:p>
                <w:r><w:t>Sentence referencing second note.</w:t></w:r>
                <w:r><w:footnoteReference w:id="2"/></w:r>
            </w:p>
        """.trimIndent()

        createDocxFile(file = file, documentBodyXml = bodyXml, footnotesXml = footnotesXml)

        val page = parser.getPageContent(file, 0)
        // Verify footnote content placed at end of paragraph
        assertTrue(page.text.contains("Sentence referencing first note. [Note: This is an important footnote.]"))
        assertTrue(page.text.contains("Sentence referencing second note. [Note: Bold note]"))
        assertTrue(page.html?.contains("""<span class="footnote">[Note: This is an important footnote.]</span>""") == true)
        assertTrue(page.html?.contains("""<span class="footnote">[Note: <b>Bold note</b>]</span>""") == true)

        // Verify layout artifacts are skipped and never appear
        assertFalse(page.text.contains("Layout Continuation Notice"))
        assertFalse(page.html?.contains("Layout Continuation Notice") == true)
        assertFalse(page.text.contains("separator"))
    }

    @Test
    fun parsesDocumentWithEndnotes() = runBlocking {
        val file = tempFolder.newFile("endnotes.docx")
        val endnotesXml = """
            <w:endnote w:type="separator" w:id="-1">
                <w:p><w:r><w:separator/></w:r></w:p>
            </w:endnote>
            <w:endnote w:id="1">
                <w:p>
                    <w:r><w:endnoteRef/></w:r>
                    <w:r><w:t>This is the referenced endnote.</w:t></w:r>
                </w:p>
            </w:endnote>
        """.trimIndent()

        val bodyXml = """
            <w:p>
                <w:r><w:t>Here is a sentence with an endnote.</w:t></w:r>
                <w:r><w:endnoteReference w:id="1"/></w:r>
            </w:p>
        """.trimIndent()

        createDocxFile(file = file, documentBodyXml = bodyXml, endnotesXml = endnotesXml)

        val page = parser.getPageContent(file, 0)
        assertTrue(page.text.contains("Here is a sentence with an endnote. [Note: This is the referenced endnote.]"))
        assertTrue(page.html?.contains("""<span class="footnote">[Note: This is the referenced endnote.]</span>""") == true)
    }

    @Test
    fun ignoresMissingNoteIdGracefully() = runBlocking {
        val file = tempFolder.newFile("missing_note.docx")
        val bodyXml = """
            <w:p>
                <w:r><w:t>Referencing missing notes.</w:t></w:r>
                <w:r><w:footnoteReference w:id="999"/></w:r>
                <w:r><w:endnoteReference w:id="888"/></w:r>
            </w:p>
        """.trimIndent()

        createDocxFile(file = file, documentBodyXml = bodyXml)

        val page = parser.getPageContent(file, 0)
        assertEquals("Referencing missing notes.", page.text)
        assertTrue(page.html?.contains("<p>Referencing missing notes.</p>") == true)
        assertFalse(page.text.contains("[Note:"))
    }

    @Test
    fun confirmationDocumentsWithoutFootnotesParseExactlyAsBefore() = runBlocking {
        val file = tempFolder.newFile("plain_no_notes.docx")
        val bodyXml = """
            <w:p>
                <w:pPr><w:pStyle w:val="Heading1"/></w:pPr>
                <w:r><w:t>Book Title</w:t></w:r>
            </w:p>
            <w:p>
                <w:r><w:t>First line of story.</w:t></w:r>
            </w:p>
        """.trimIndent()

        createDocxFile(file = file, documentBodyXml = bodyXml)

        val metadata = parser.extractMetadata(file)
        assertEquals("Book Title", metadata.title)

        val page = parser.getPageContent(file, 0)
        assertEquals("Book Title", page.title)
        assertTrue(page.text.contains("Book Title\nFirst line of story."))
        assertTrue(page.html?.contains("<h1>Book Title</h1><p>First line of story.</p>") == true)
        assertFalse(page.html?.contains("footnote") == true)
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
