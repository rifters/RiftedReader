package com.rifters.riftedreader.domain.pagination

import android.text.TextUtils
import com.rifters.riftedreader.domain.parser.BookParser
import com.rifters.riftedreader.domain.parser.PageContent
import com.rifters.riftedreader.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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
            
            // Collect chapter content from paginator
            val chapterContents = mutableMapOf<Int, PageContent>()
            for (chapterIndex in chapterIndices) {
                val globalPageIndex = paginator.getGlobalIndexForChapterPage(chapterIndex, 0)
                if (globalPageIndex == null) {
                    AppLogger.w(TAG, "Could not get global page index for chapter $chapterIndex")
                    continue
                }
                
                val pageContent = paginator.getPageContent(globalPageIndex)
                if (pageContent == null) {
                    AppLogger.w(TAG, "No content available for chapter $chapterIndex")
                    continue
                }
                
                chapterContents[chapterIndex] = pageContent
            }
            
            buildWindowHtml(windowIndex, chapterIndices, chapterContents)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error generating window HTML for window $windowIndex", e)
            null
        }
    }
    
    override suspend fun generateWindowHtml(
        windowIndex: WindowIndex,
        firstChapterIndex: ChapterIndex,
        lastChapterIndex: ChapterIndex,
        bookFile: File,
        parser: BookParser
    ): String? {
        return try {
            AppLogger.d(TAG, "Generating window HTML directly from parser for window $windowIndex, chapters: $firstChapterIndex-$lastChapterIndex")
            
            val chapterIndices = (firstChapterIndex..lastChapterIndex).toList()
            
            // Collect chapter content from parser
            val chapterContents = mutableMapOf<Int, PageContent>()
            for (chapterIndex in chapterIndices) {
                val pageContent = withContext(Dispatchers.IO) {
                    parser.getPageContent(bookFile, chapterIndex)
                }
                chapterContents[chapterIndex] = pageContent
            }
            
            buildWindowHtml(windowIndex, chapterIndices, chapterContents)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error generating window HTML for window $windowIndex", e)
            null
        }
    }
    
    /**
     * Build the combined HTML for a window from chapter content.
     * 
     * This is the shared logic for both getWindowHtml and generateWindowHtml methods.
     * 
     * @param windowIndex The window index for metadata
     * @param chapterIndices List of chapter indices in this window
     * @param chapterContents Map of chapter index to page content
     * @return Combined HTML string, or null if no chapter sections were added
     */
    private fun buildWindowHtml(
        windowIndex: Int,
        chapterIndices: List<Int>,
        chapterContents: Map<Int, PageContent>
    ): String? {
        val htmlBuilder = StringBuilder()
        var sectionsAdded = 0
        
        // Start window-root container
        htmlBuilder.append("<div id=\"window-root\" data-window-index=\"$windowIndex\">\n")
        
        for (chapterIndex in chapterIndices) {
            val pageContent = chapterContents[chapterIndex] ?: continue
            
            // Extract HTML or convert text to HTML
            val chapterHtml = pageContent.html ?: wrapTextAsHtml(pageContent.text)
            
            if (chapterHtml.isBlank()) {
                AppLogger.w(TAG, "Empty content for chapter $chapterIndex")
                continue
            }
            
            // Wrap in section with chapter ID for navigation
            appendChapterSection(htmlBuilder, chapterIndex, chapterHtml)
            sectionsAdded++
        }
        
        // Close window-root container
        htmlBuilder.append("</div>\n")
        
        // Return null if no chapter sections were added
        if (sectionsAdded == 0) {
            AppLogger.w(TAG, "Generated empty HTML for window $windowIndex (no chapter sections)")
            return null
        }
        
        val combinedHtml = htmlBuilder.toString()
        AppLogger.d(TAG, "Successfully generated window HTML for window $windowIndex: ${combinedHtml.length} characters, $sectionsAdded sections")
        
        return combinedHtml
    }
    
    /**
     * Append a chapter section to the HTML builder.
     */
    private fun appendChapterSection(
        htmlBuilder: StringBuilder,
        chapterIndex: Int,
        chapterHtml: String
    ) {
        htmlBuilder.append("  <section id=\"chapter-$chapterIndex\" data-chapter-index=\"$chapterIndex\">\n")
        htmlBuilder.append("    ")
        htmlBuilder.append(chapterHtml)
        htmlBuilder.append("\n  </section>\n")
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
