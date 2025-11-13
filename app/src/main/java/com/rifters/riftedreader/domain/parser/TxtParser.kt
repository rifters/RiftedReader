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
        private const val CHARSET_DETECTION_BUFFER_SIZE = 8192 // Read first 8KB for charset detection
    }
    
    // Cache charset per file path to avoid re-detection
    private val charsetCache = mutableMapOf<String, Charset>()
    
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
        val charset = getOrDetectCharset(file)
        val lines = file.readLines(charset)
        val startLine = page * LINES_PER_PAGE
        val endLine = minOf(startLine + LINES_PER_PAGE, lines.size)
        
        if (startLine >= lines.size) {
            return ""
        }
        
        return lines.subList(startLine, endLine).joinToString("\n")
    }
    
    override suspend fun getPageCount(file: File): Int {
        val charset = getOrDetectCharset(file)
        val lineCount = file.readLines(charset).size
        return (lineCount + LINES_PER_PAGE - 1) / LINES_PER_PAGE
    }
    
    override suspend fun getTableOfContents(file: File): List<TocEntry> {
        // TXT files don't have structured TOC
        return emptyList()
    }
    
    /**
     * Get cached charset or detect it for the file
     */
    private fun getOrDetectCharset(file: File): Charset {
        return charsetCache.getOrPut(file.absolutePath) {
            detectCharset(file)
        }
    }
    
    /**
     * Detect file encoding by reading only the file header (first 8KB)
     */
    private fun detectCharset(file: File): Charset {
        return try {
            // Read only the beginning of the file for charset detection
            val buffer = ByteArray(minOf(CHARSET_DETECTION_BUFFER_SIZE.toLong(), file.length()).toInt())
            file.inputStream().use { input ->
                input.read(buffer)
            }
            
            // Try to decode with UTF-8 - will throw if invalid
            val decoder = Charsets.UTF_8.newDecoder()
            decoder.decode(java.nio.ByteBuffer.wrap(buffer))
            Charsets.UTF_8
        } catch (e: Exception) {
            // Fall back to ISO-8859-1 (which accepts all byte values)
            Charsets.ISO_8859_1
        }
    }
}
