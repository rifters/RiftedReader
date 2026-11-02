package com.rifters.riftedreader.domain.parser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FormatParserRegistrationTest {

    @Test
    fun fb2IsRegisteredAsSupportedParser() {
        assertTrue(ParserFactory.isSupported(File("sample.fb2")))
        assertTrue(ParserFactory.getParser(File("sample.fb2")) is Fb2Parser)

        assertTrue(ParserFactory.isSupported(File("sample.fb2.zip")))
        assertTrue(ParserFactory.getParser(File("sample.fb2.zip")) is Fb2Parser)
    }

    @Test
    fun mobiIsRegisteredAsSupportedParser() {
        assertTrue(ParserFactory.isSupported(File("sample.mobi")))
        assertTrue(ParserFactory.getParser(File("sample.mobi")) is MobiParser)
    }

    @Test
    fun docxAndRtfAreRegisteredAsSupportedParsers() {
        assertTrue(ParserFactory.isSupported(File("sample.docx")))
        assertTrue(ParserFactory.getParser(File("sample.docx")) is DocxParser)

        assertTrue(ParserFactory.isSupported(File("sample.rtf")))
        assertTrue(ParserFactory.getParser(File("sample.rtf")) is RtfParser)
    }

    @Test
    fun allCatalogFormatsAreSupportedAndNoPreviewDescriptorsRemain() {
        assertTrue(FormatCatalog.inProgressDescriptors().isEmpty())
        assertTrue(FormatCatalog.plannedDescriptors().isEmpty())
        val supportedIds = FormatCatalog.supportedDescriptors().map { it.id }
        assertTrue(supportedIds.contains("fb2"))
        assertTrue(supportedIds.contains("mobi"))
        assertTrue(supportedIds.contains("docx"))
        assertTrue(supportedIds.contains("rtf"))
    }
}
