package com.rifters.riftedreader.domain.pagination

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
}
