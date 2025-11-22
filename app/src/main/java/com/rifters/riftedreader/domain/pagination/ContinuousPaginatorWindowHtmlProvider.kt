package com.rifters.riftedreader.domain.pagination

import android.text.TextUtils
import com.rifters.riftedreader.util.AppLogger

/**
 * Implementation of WindowHtmlProvider that generates window HTML from ContinuousPaginator.
 * 
 * This provider combines multiple chapters into a single HTML document using <section> tags
 * with chapter identifiers for proper navigation and styling.
 * 
 * @param paginator The continuous paginator managing the chapter data
 * @param windowManager The sliding window manager for index calculations
 */
class ContinuousPaginatorWindowHtmlProvider(
    private val paginator: ContinuousPaginator,
    private val windowManager: SlidingWindowManager
) : WindowHtmlProvider {
    
    override suspend fun getWindowHtml(bookId: String, windowIndex: Int): String? {
        return try {
            // Get window information from paginator
            val windowInfo = paginator.getWindowInfo()
            val totalChapters = windowInfo.totalChapters
            
            if (totalChapters == 0) {
                AppLogger.w(TAG, "No chapters available for window HTML")
                return null
            }
            
            // Calculate which chapters belong to this window
            val chapterIndices = windowManager.chaptersInWindow(windowIndex, totalChapters)
            
            if (chapterIndices.isEmpty()) {
                AppLogger.w(TAG, "No chapters in window $windowIndex (totalChapters=$totalChapters)")
                return null
            }
            
            AppLogger.d(TAG, "Generating window HTML for window $windowIndex, chapters: ${chapterIndices.first()}-${chapterIndices.last()}")
            
            // Build combined HTML with section tags for each chapter
            // Wrap all sections in a window-root container for clearer structure
            val htmlBuilder = StringBuilder()
            val indent = "  " // Consistent indentation
            
            // Start window-root wrapper
            htmlBuilder.append("<div id=\"window-root\" data-window-index=\"$windowIndex\">\n")
            
            for (chapterIndex in chapterIndices) {
                // Get the global page index for this chapter
                val globalPageIndex = paginator.getGlobalIndexForChapterPage(chapterIndex, 0)
                
                if (globalPageIndex == null) {
                    AppLogger.w(TAG, "Could not get global page index for chapter $chapterIndex")
                    continue
                }
                
                // Get the page content
                val pageContent = paginator.getPageContent(globalPageIndex)
                
                if (pageContent == null) {
                    AppLogger.w(TAG, "No content available for chapter $chapterIndex")
                    continue
                }
                
                // Extract HTML or convert text to HTML
                val chapterHtml = pageContent.html ?: wrapTextAsHtml(pageContent.text)
                
                if (chapterHtml.isBlank()) {
                    AppLogger.w(TAG, "Empty content for chapter $chapterIndex")
                    continue
                }
                
                // Wrap in section with chapter ID for navigation
                htmlBuilder.append(indent)
                    .append("<section id=\"chapter-$chapterIndex\" data-chapter-index=\"$chapterIndex\">\n")
                htmlBuilder.append(indent)
                    .append(indent)
                    .append(chapterHtml)
                    .append("\n")
                htmlBuilder.append(indent)
                    .append("</section>\n")
            }
            
            // Close window-root wrapper
            htmlBuilder.append("</div>\n")
            
            val combinedHtml = htmlBuilder.toString()
            
            if (combinedHtml.isBlank()) {
                AppLogger.w(TAG, "Generated empty HTML for window $windowIndex")
                return null
            }
            
            AppLogger.d(TAG, "Successfully generated window HTML for window $windowIndex: ${combinedHtml.length} characters")
            
            combinedHtml
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error generating window HTML for window $windowIndex", e)
            null
        }
    }
    
    /**
     * Convert plain text to simple HTML paragraphs.
     */
    private fun wrapTextAsHtml(text: String): String {
        if (text.isBlank()) return ""
        
        // Split by paragraphs (double newlines) and wrap each in <p> tags
        return text.split("\n\n")
            .filter { it.isNotBlank() }
            .joinToString("\n") { paragraph ->
                val escaped = TextUtils.htmlEncode(paragraph.trim())
                "<p>$escaped</p>"
            }
    }
    
    companion object {
        private const val TAG = "ContinuousPaginatorWindowHtmlProvider"
    }
}
