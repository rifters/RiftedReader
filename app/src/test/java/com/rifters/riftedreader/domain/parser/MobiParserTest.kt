package com.rifters.riftedreader.domain.parser

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MobiParserTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val parser = MobiParser()

    @Test
    fun extractsBasicMetadataAndCleansText() = runBlocking {
        val file = tempFolder.newFile("sample.mobi")
        file.writeText("<html><body>Hello <b>world</b>\n<script>alert(1)</script></body></html>")

        val metadata = parser.extractMetadata(file)
        assertEquals("sample", metadata.title)
        assertEquals("MOBI", metadata.format)
        assertTrue(metadata.size > 0)

        val page = parser.getPageContent(file, 0)
        assertEquals("sample", page.title)
        assertTrue(page.text.contains("Hello"))
        assertTrue(page.text.contains("world"))
        assertTrue(page.text.contains("alert"))
        assertTrue(page.html?.startsWith("<pre>") == true)
    }
}
