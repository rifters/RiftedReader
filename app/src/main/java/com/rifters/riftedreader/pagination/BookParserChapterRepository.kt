package com.rifters.riftedreader.pagination

import com.rifters.riftedreader.domain.pagination.ChapterIndex
import com.rifters.riftedreader.domain.parser.BookParser
import com.rifters.riftedreader.util.AppLogger
import java.io.File

/**
 * Adapter that implements ChapterRepository by wrapping a BookParser.
 * 
 * This allows FlexPaginator to work with the existing parser infrastructure
 * without needing to know about BookParser implementation details.
 * 
 * @param bookFile The book file to read from
 * @param parser The parser instance for this book format
 */
class BookParserChapterRepository(
    private val bookFile: File,
    private val parser: BookParser
) : ChapterRepository {
    
    companion object {
        private const val TAG = "BookParserChapterRepo"
    }
    
    private var cachedTotalChapters: Int = -1
    
    override suspend fun getChapterHtml(chapterIndex: ChapterIndex): String? {
        return try {
            val pageContent = parser.getPageContent(bookFile, chapterIndex)
            // Prefer HTML if available, otherwise wrap text in basic HTML
            pageContent.html ?: wrapTextInHtml(pageContent.text, pageContent.title)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to get chapter $chapterIndex HTML", e)
            null
        }
    }
    
    override fun getTotalChapterCount(): Int {
        // Cache the chapter count since it's expensive to compute
        if (cachedTotalChapters < 0) {
            cachedTotalChapters = try {
                // Note: getPageCount is a suspend function, but getTotalChapterCount is not.
                // This is a limitation - we can't call suspend functions from here.
                // The caller must initialize this by calling initializeTotalChapters() first.
                AppLogger.w(TAG, "getTotalChapterCount called before initialization")
                0
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to get chapter count", e)
                0
            }
        }
        return cachedTotalChapters
    }
    
    /**
     * Initialize the total chapter count.
     * Must be called before using this repository.
     */
    suspend fun initializeTotalChapters() {
        if (cachedTotalChapters < 0) {
            cachedTotalChapters = try {
                parser.getPageCount(bookFile)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to initialize chapter count", e)
                0
            }
            AppLogger.d(TAG, "Initialized with $cachedTotalChapters chapters")
        }
    }
    
    /**
     * Wrap plain text in basic HTML structure.
     */
    private fun wrapTextInHtml(text: String, title: String?): String {
        val html = StringBuilder()
        if (title != null && title.isNotEmpty()) {
            html.append("<h1>").append(escapeHtml(title)).append("</h1>\n")
        }
        // Simple paragraph wrapping - split by double newlines
        val paragraphs = text.split("\n\n")
        for (para in paragraphs) {
            if (para.trim().isNotEmpty()) {
                html.append("<p>").append(escapeHtml(para.trim())).append("</p>\n")
            }
        }
        return html.toString()
    }
    
    /**
     * Escape HTML special characters.
     */
    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }
}
