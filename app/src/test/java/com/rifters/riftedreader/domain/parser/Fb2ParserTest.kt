package com.rifters.riftedreader.domain.parser

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class Fb2ParserTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val parser = Fb2Parser()

    @Test
    fun extractsMetadataAndSectionsFromFb2() = runBlocking {
        val file = tempFolder.newFile("sample.fb2")
        file.writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <FictionBook>
              <description>
                <title-info>
                  <genre>fantasy</genre>
                  <author>
                    <first-name>Jane</first-name>
                    <last-name>Doe</last-name>
                  </author>
                  <book-title>Sample Book</book-title>
                  <lang>en</lang>
                  <annotation>Short description</annotation>
                </title-info>
                <publish-info>
                  <publisher>Rift Press</publisher>
                  <year>2026</year>
                </publish-info>
              </description>
              <body>
                <section>
                  <title><p>Chapter One</p></title>
                  <p>Hello</p>
                  <image href="#cover"/>
                  <section>
                    <title><p>Subsection</p></title>
                    <p>Nested</p>
                  </section>
                </section>
              </body>
              <binary id="cover" content-type="image/png">QUJD</binary>
            </FictionBook>
            """.trimIndent()
        )

        val metadata = parser.extractMetadata(file)
        assertEquals("Sample Book", metadata.title)
        assertEquals("Jane Doe", metadata.author)
        assertEquals("Rift Press", metadata.publisher)
        assertEquals("2026", metadata.year)
        assertEquals("en", metadata.language)
        assertEquals("Short description", metadata.description)
        assertEquals(1, metadata.totalPages)

        val page = parser.getPageContent(file, 0)
        assertEquals("Chapter One", page.title)
        assertTrue(page.text.contains("Hello"))
        assertTrue(page.html?.contains("data:image/png;base64,QUJD") == true)

        val toc = parser.getTableOfContents(file)
        assertEquals("Chapter One", toc.first().title)
        assertTrue(toc.any { it.title == "Subsection" })
    }
}
