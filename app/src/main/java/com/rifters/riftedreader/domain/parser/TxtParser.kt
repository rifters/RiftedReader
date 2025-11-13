package com.rifters.riftedreader.domain.parser

import com.rifters.riftedreader.data.database.entities.BookMeta
import java.io.File
import java.nio.charset.Charset

/**
 * Parser for plain text files
 */
class TxtParser : BookParser {
    
    companion object {
        private const val LINES_PER_PAGE = 30
        private val SUPPORTED_EXTENSIONS = listOf("txt", "text")
    }
    
    override fun canParse(file: File): Boolean {
        return file.extension.lowercase() in SUPPORTED_EXTENSIONS
    }
    
    override suspend fun extractMetadata(file: File): BookMeta {
        val title = file.nameWithoutExtension
        val pageCount = getPageCount(file)
        
        return BookMeta(
            path = file.absolutePath,
            title = title,
            format = "TXT",
            size = file.length(),
            totalPages = pageCount,
            dateAdded = System.currentTimeMillis()
        )
    }
    
    override suspend fun getPageContent(file: File, page: Int): String {
        val lines = file.readLines(detectCharset(file))
        val startLine = page * LINES_PER_PAGE
        val endLine = minOf(startLine + LINES_PER_PAGE, lines.size)
        
        if (startLine >= lines.size) {
            return ""
        }
        
        return lines.subList(startLine, endLine).joinToString("\n")
    }
    
    override suspend fun getPageCount(file: File): Int {
        val lineCount = file.readLines(detectCharset(file)).size
        return (lineCount + LINES_PER_PAGE - 1) / LINES_PER_PAGE
    }
    
    override suspend fun getTableOfContents(file: File): List<TocEntry> {
        // TXT files don't have structured TOC
        return emptyList()
    }
    
    /**
     * Detect file encoding (simple implementation)
     */
    private fun detectCharset(file: File): Charset {
        return try {
            // Try UTF-8 first
            file.readLines(Charsets.UTF_8)
            Charsets.UTF_8
        } catch (e: Exception) {
            try {
                // Fall back to ISO-8859-1
                Charsets.ISO_8859_1
            } catch (e: Exception) {
                // Default to system charset
                Charset.defaultCharset()
            }
        }
    }
}
