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
 * FIX: On-demand window HTML generation for TOC jumps.
 * When the paginator doesn't have chapters loaded for a window (e.g., user jumps to chapter 10
 * via TOC), this provider now navigates the paginator to load those chapters first.
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
                AppLogger.w(TAG, "[PAGINATION_DEBUG] No chapters available for window HTML")
                return null
            }
            
            // Calculate which chapters belong to this window
            val chapterIndices = windowManager.chaptersInWindow(windowIndex, totalChapters)
            
            if (chapterIndices.isEmpty()) {
                AppLogger.w(TAG, "[PAGINATION_DEBUG] No chapters in window $windowIndex (totalChapters=$totalChapters)")
                return null
            }
            
            AppLogger.d(TAG, "[PAGINATION_DEBUG] Generating window HTML for window $windowIndex, chapters: ${chapterIndices.first()}-${chapterIndices.last()}")
            
            // FIX: Check if the first chapter of this window is loaded in the paginator.
            // If not, navigate to it first to load the window's chapters.
            // This handles TOC jumps to windows that aren't in the pre-wrapped cache.
            val firstChapterInWindow = chapterIndices.first()
            val loadedChapters = windowInfo.loadedChapterIndices
            
            if (!loadedChapters.contains(firstChapterInWindow)) {
                AppLogger.d(TAG, "[PAGINATION_DEBUG] Window $windowIndex chapters not loaded (loaded: $loadedChapters), " +
                    "triggering on-demand load for chapter $firstChapterInWindow")
                
                // Navigate to the first chapter in the window to trigger loading
                // Wrap in try-catch to handle potential navigation failures gracefully
                try {
                    paginator.navigateToChapter(firstChapterInWindow, 0)
                    
                    // Verify navigation was successful by checking loaded chapters after navigation
                    val updatedWindowInfo = paginator.getWindowInfo()
                    val nowLoadedChapters = updatedWindowInfo.loadedChapterIndices
                    
                    if (nowLoadedChapters.contains(firstChapterInWindow)) {
                        AppLogger.d(TAG, "[PAGINATION_DEBUG] On-demand load successful for window $windowIndex " +
                            "(chapters ${chapterIndices.first()}-${chapterIndices.last()}), now loaded: $nowLoadedChapters")
                    } else {
                        AppLogger.w(TAG, "[PAGINATION_DEBUG] On-demand load may have failed for window $windowIndex - " +
                            "chapter $firstChapterInWindow still not in loaded chapters: $nowLoadedChapters")
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "[PAGINATION_DEBUG] Navigation failed for on-demand load of window $windowIndex " +
                        "(chapter $firstChapterInWindow): ${e.message}", e)
                    // Continue anyway - we'll try to get whatever content we can
                }
            }
            
            // Collect chapter content from paginator
            val chapterContents = mutableMapOf<Int, PageContent>()
            var missingChapters = 0
            
            for (chapterIndex in chapterIndices) {
                val globalPageIndex = paginator.getGlobalIndexForChapterPage(chapterIndex, 0)
                if (globalPageIndex == null) {
                    AppLogger.w(TAG, "[PAGINATION_DEBUG] Could not get global page index for chapter $chapterIndex")
                    missingChapters++
                    continue
                }
                
                val pageContent = paginator.getPageContent(globalPageIndex)
                if (pageContent == null) {
                    AppLogger.w(TAG, "[PAGINATION_DEBUG] No content available for chapter $chapterIndex " +
                        "(may need to navigate to this chapter first)")
                    missingChapters++
                    continue
                }
                
                chapterContents[chapterIndex] = pageContent
            }
            
            // If we still have missing chapters, log a warning
            if (missingChapters > 0) {
                AppLogger.w(TAG, "[PAGINATION_DEBUG] $missingChapters chapters missing in window $windowIndex " +
                    "after navigation attempt. Loaded: ${chapterContents.keys}")
            }
            
            // If no chapters could be loaded, return null
            if (chapterContents.isEmpty()) {
                AppLogger.e(TAG, "[PAGINATION_DEBUG] Failed to load any chapters for window $windowIndex")
                return null
            }
            
            val html = buildWindowHtml(windowIndex, chapterIndices, chapterContents)
            
            if (html != null) {
                AppLogger.d(TAG, "[PAGINATION_DEBUG] Built wrapped HTML for window $windowIndex " +
                    "(chapters ${chapterIndices.first()}-${chapterIndices.last()}), htmlLength=${html.length}")
            } else {
                AppLogger.w(TAG, "[PAGINATION_DEBUG] buildWindowHtml returned null for window $windowIndex " +
                    "(chapters ${chapterIndices.first()}-${chapterIndices.last()})")
            }
            
            html
        } catch (e: Exception) {
            AppLogger.e(TAG, "[PAGINATION_DEBUG] Error generating window HTML for window $windowIndex", e)
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
