package com.rifters.riftedreader.domain.parser

import java.io.File

/**
 * Factory for creating appropriate parser for a file
 */
object ParserFactory {
    
    private val parsers = listOf(
        TxtParser(),
        EpubParser(),
        PdfParser()
    )
    
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
        return listOf("txt", "text", "epub", "pdf")
    }
}
