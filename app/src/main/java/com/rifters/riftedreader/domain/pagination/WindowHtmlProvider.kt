package com.rifters.riftedreader.domain.pagination

import com.rifters.riftedreader.domain.parser.BookParser
import java.io.File

/**
 * Provides HTML content for sliding windows of chapters.
 * 
 * A sliding window combines multiple consecutive chapters into a single HTML document
 * for efficient memory management and smooth navigation.
 */
interface WindowHtmlProvider {
    /**
     * Get the combined HTML for all chapters in the specified window.
     * 
     * @param bookId Unique identifier for the book
     * @param windowIndex The window index (0-based)
     * @return Combined HTML containing all chapters in the window, or null if unavailable
     */
    suspend fun getWindowHtml(bookId: String, windowIndex: Int): String?
    
    /**
     * Generate HTML for a window directly from a book file and parser.
     * 
     * This method is used when building windows from scratch without a pre-existing
     * paginator instance.
     * 
     * @param windowIndex The window index (0-based)
     * @param firstChapterIndex First chapter index in the window (inclusive)
     * @param lastChapterIndex Last chapter index in the window (inclusive)
     * @param bookFile The book file to read from
     * @param parser The parser to use for extracting content
     * @return Combined HTML containing all chapters in the window, or null if unavailable
     */
    suspend fun generateWindowHtml(
        windowIndex: WindowIndex,
        firstChapterIndex: ChapterIndex,
        lastChapterIndex: ChapterIndex,
        bookFile: File,
        parser: BookParser
    ): String?
}
