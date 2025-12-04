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
            
            // FIX: Ensure ALL chapters in the window are loaded.
            // The paginator loads a sliding window of 5 chapters centered around the navigation target.
            // For a window with chapters 20-24, if we navigate to chapter 20, it might load 18-22,
            // missing chapters 23-24. We need to ensure all chapters in the range are loaded.
            val firstChapterInWindow = chapterIndices.first()
            val lastChapterInWindow = chapterIndices.last()
            val loadedChapters = windowInfo.loadedChapterIndices
            
            // Check if ALL chapters in the window are loaded
            val allChaptersLoaded = chapterIndices.all { it in loadedChapters }
            
            if (!allChaptersLoaded) {
                val missingChapters = chapterIndices.filter { it !in loadedChapters }
                AppLogger.d(TAG, "[PAGINATION_DEBUG] Window $windowIndex has missing chapters: $missingChapters " +
                    "(loaded: $loadedChapters, needed: $chapterIndices)")
                
                // FIX: The paginator loads a 5-chapter window CENTERED around the navigation target.
                // For window 4 with chapters 20-24, navigating to chapter 20 loads 18-22 (centered at 20).
                // Instead, we must navigate to the MIDDLE chapter of our desired range.
                // For chapters 20-24, the middle is 22, which will load 20-24 (centered at 22). ✓
                
                try {
                    // Calculate the middle chapter of the requested window range
                    val middleChapterInWindow = chapterIndices[chapterIndices.size / 2]
                    
                    AppLogger.d(TAG, "[PAGINATION_DEBUG] Loading window chapters $firstChapterInWindow-$lastChapterInWindow: " +
                        "navigating to MIDDLE chapter $middleChapterInWindow (this will load a 5-chapter window centered at $middleChapterInWindow)")
                    
                    // Navigate to the middle chapter - this will load a window centered around it
                    paginator.navigateToChapter(middleChapterInWindow, 0)
                    
                    // Verify all chapters are now loaded
                    val updatedWindowInfo = paginator.getWindowInfo()
                    val nowLoadedChapters = updatedWindowInfo.loadedChapterIndices
                    
                    val nowAllLoaded = chapterIndices.all { it in nowLoadedChapters }
                    
                    if (nowAllLoaded) {
                        AppLogger.d(TAG, "[PAGINATION_DEBUG] On-demand load successful for window $windowIndex " +
                            "(chapters $firstChapterInWindow-$lastChapterInWindow), now loaded: $nowLoadedChapters")
                    } else {
                        val stillMissing = chapterIndices.filter { it !in nowLoadedChapters }
                        AppLogger.w(TAG, "[PAGINATION_DEBUG] On-demand load incomplete for window $windowIndex - " +
                            "still missing chapters: $stillMissing (loaded: $nowLoadedChapters, needed: $chapterIndices)")
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "[PAGINATION_DEBUG] Navigation failed for on-demand load of window $windowIndex: ${e.message}", e)
                    // Continue anyway - we'll try to get whatever content we can
                }
            } else {
                AppLogger.d(TAG, "[PAGINATION_DEBUG] All chapters for window $windowIndex already loaded: $loadedChapters")
            }
            
            // Collect chapter content from paginator
            AppLogger.d(TAG, "[HTML_BUILD] Starting chapter collection for window $windowIndex, chapters: $chapterIndices")
            
            val chapterContents = mutableMapOf<Int, PageContent>()
            var missingChapters = 0
            
            for (chapterIndex in chapterIndices) {
                AppLogger.d(TAG, "[HTML_BUILD] Attempting to fetch chapter $chapterIndex...")
                
                val globalPageIndex = paginator.getGlobalIndexForChapterPage(chapterIndex, 0)
                if (globalPageIndex == null) {
                    AppLogger.w(TAG, "[HTML_BUILD] Could not get global page index for chapter $chapterIndex - MISSING")
                    missingChapters++
                    continue
                }
                
                AppLogger.d(TAG, "[HTML_BUILD] Chapter $chapterIndex -> globalPageIndex=$globalPageIndex, fetching content...")
                
                val pageContent = paginator.getPageContent(globalPageIndex)
                if (pageContent == null) {
                    AppLogger.w(TAG, "[HTML_BUILD] No content available for chapter $chapterIndex (globalPageIndex=$globalPageIndex) - MISSING")
                    missingChapters++
                    continue
                }
                
                chapterContents[chapterIndex] = pageContent
                AppLogger.d(TAG, "[HTML_BUILD] Chapter $chapterIndex content FETCHED successfully (htmlLength=${pageContent.html?.length ?: 0}, textLength=${pageContent.text.length})")
            }
            
            AppLogger.d(TAG, "[HTML_BUILD] Chapter collection complete for window $windowIndex: " +
                "collected=${chapterContents.keys.sorted()}, missing=$missingChapters, " +
                "totalRequested=${chapterIndices.size}")
            
            // If we still have missing chapters, log a warning
            if (missingChapters > 0) {
                AppLogger.w(TAG, "[HTML_BUILD] $missingChapters chapters missing in window $windowIndex " +
                    "after navigation attempt. Loaded: ${chapterContents.keys}, Expected: $chapterIndices")
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
        val addedChapterIndices = mutableListOf<Int>()
        
        AppLogger.d(TAG, "[HTML_BUILD] buildWindowHtml START: windowIndex=$windowIndex, " +
            "requestedChapters=${chapterIndices}, availableChapters=${chapterContents.keys.sorted()}")
        
        // Start window-root container
        htmlBuilder.append("<div id=\"window-root\" data-window-index=\"$windowIndex\">\n")
        
        for (chapterIndex in chapterIndices) {
            val pageContent = chapterContents[chapterIndex]
            
            if (pageContent == null) {
                AppLogger.w(TAG, "[HTML_BUILD] Chapter $chapterIndex NOT FOUND in chapterContents map - SKIPPING")
                continue
            }
            
            // Extract HTML or convert text to HTML
            val chapterHtml = pageContent.html ?: wrapTextAsHtml(pageContent.text)
            
            if (chapterHtml.isBlank()) {
                AppLogger.w(TAG, "[HTML_BUILD] Chapter $chapterIndex has EMPTY content - SKIPPING")
                continue
            }
            
            // Wrap in section with chapter ID for navigation
            appendChapterSection(htmlBuilder, chapterIndex, chapterHtml)
            sectionsAdded++
            addedChapterIndices.add(chapterIndex)
            
            AppLogger.d(TAG, "[HTML_BUILD] Chapter $chapterIndex ADDED to window HTML (htmlLength=${chapterHtml.length})")
        }
        
        // Close window-root container
        htmlBuilder.append("</div>\n")
        
        AppLogger.d(TAG, "[HTML_BUILD] buildWindowHtml COMPLETE: windowIndex=$windowIndex, " +
            "sectionsAdded=$sectionsAdded, addedChapters=$addedChapterIndices")
        
        // Return null if no chapter sections were added
        if (sectionsAdded == 0) {
            AppLogger.w(TAG, "[HTML_BUILD] Generated EMPTY HTML for window $windowIndex (no chapter sections)")
            return null
        }
        
        val combinedHtml = htmlBuilder.toString()
        AppLogger.d(TAG, "[HTML_BUILD] Final window HTML for window $windowIndex: ${combinedHtml.length} characters, " +
            "$sectionsAdded sections, chapters=$addedChapterIndices")
        
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
