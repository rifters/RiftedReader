package com.rifters.riftedreader.domain.parser

import java.io.File

/**
 * Factory for creating appropriate parser for a file
 */
object ParserFactory {

    private val parsers = mutableListOf<BookParser>()

    init {
        register(TxtParser())
        register(EpubParser())
        register(PdfParser())
        register(HtmlParser())
    }

    fun register(parser: BookParser) {
        parsers += parser
    }

    fun enablePreviewParsers() {
        FormatCatalog.previewParsers().forEach { preview ->
            val alreadyRegistered = parsers.any { existing ->
                existing is PreviewParser && existing.descriptor.id == preview.descriptor.id
            }
            if (!alreadyRegistered) {
                parsers += preview
            }
        }
    }

    /**
     * Get parser for the given file
     */
    fun getParser(file: File): BookParser? {
        return parsers.firstOrNull { it.canParse(file) }
    }
    
    /**
     * Check if file format is supported
     */
    fun isSupported(file: File): Boolean {
        return parsers.any { it.canParse(file) }
    }
    
    /**
     * Get list of supported extensions
     */
    fun getSupportedExtensions(): List<String> {
        return FormatCatalog.supportedExtensions().toList()
    }
}
