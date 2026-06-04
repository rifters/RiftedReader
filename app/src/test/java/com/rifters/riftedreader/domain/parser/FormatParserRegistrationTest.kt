package com.rifters.riftedreader.domain.parser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FormatParserRegistrationTest {

    @Test
    fun fb2AndMobiAreRegisteredAsSupportedParsers() {
        assertTrue(ParserFactory.isSupported(File("sample.fb2")))
        assertTrue(ParserFactory.getParser(File("sample.fb2")) is Fb2Parser)

        assertTrue(ParserFactory.isSupported(File("sample.fb2.zip")))
        assertTrue(ParserFactory.getParser(File("sample.fb2.zip")) is Fb2Parser)

        assertTrue(ParserFactory.isSupported(File("sample.mobi")))
        assertTrue(ParserFactory.getParser(File("sample.mobi")) is MobiParser)
    }

    @Test
    fun fb2AndMobiAreNotPreviewParsersAnymore() {
        val previewIds = FormatCatalog.previewParsers().map { it.descriptor.id }
        assertFalse(previewIds.contains("fb2"))
        assertFalse(previewIds.contains("mobi"))
    }
}
