package com.rifters.riftedreader.pagination

import android.text.TextUtils
import com.rifters.riftedreader.domain.pagination.ChapterIndex
import com.rifters.riftedreader.domain.pagination.WindowIndex
import com.rifters.riftedreader.domain.parser.BookParser
import com.rifters.riftedreader.domain.parser.PageContent
import com.rifters.riftedreader.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * FlexPaginator - Clean-slate pagination system with pre-slicing support.
 * 
 * **Single Responsibility**: This class ONLY assembles window HTML by wrapping chapters
 * in <section> tags. It does NOT handle:
 * - Page slicing (delegated to flex_paginator.js)
 * - Page counting (delegated to flex_paginator.js)
 * - Character offset tracking (delegated to flex_paginator.js)
 * - Window lifecycle (delegated to Conveyor)
 * 
 * The assembled HTML is designed for flex-based layout (not CSS columns) and
 * includes data attributes for the JavaScript slicing algorithm to process.
 * 
 * @param parser The BookParser for reading chapter content
 * @param bookFile The book file to read from
 */
class FlexPaginator(
    private val parser: BookParser,
    private val bookFile: File
) {
    
    companion object {
        private const val TAG = "FlexPaginator"
    }
    
    /**
     * Assemble a window by wrapping N chapters into a single HTML document.
     * 
     * The output HTML structure:
     * ```html
     * <div id="window-root" data-window-index="5">
     *   <section data-chapter="25">...chapter 25 content...</section>
     *   <section data-chapter="26">...chapter 26 content...</section>
     *   <section data-chapter="27">...chapter 27 content...</section>
     * </div>
     * ```
     * 
     * This HTML is then passed to OffscreenSlicingWebView which loads flex_paginator.js
     * to perform the actual slicing into viewport-sized pages.
     * 
     * @param windowIndex The window index (used for metadata only)
     * @param firstChapter The first chapter to include (inclusive)
     * @param lastChapter The last chapter to include (inclusive)
     * @return WindowData with assembled HTML, or null on failure
     */
    suspend fun assembleWindow(
        windowIndex: WindowIndex,
        firstChapter: ChapterIndex,
        lastChapter: ChapterIndex
    ): WindowData? {
        AppLogger.d(TAG, "[FLEX] Assembling window $windowIndex, chapters $firstChapter-$lastChapter")
        
        return try {
            // Validate chapter range
            if (firstChapter < 0 || lastChapter < firstChapter) {
                AppLogger.e(TAG, "[FLEX] Invalid chapter range: $firstChapter-$lastChapter")
                return null
            }
            
            // Collect chapter content
            val chapterContents = mutableMapOf<ChapterIndex, PageContent>()
            for (chapterIndex in firstChapter..lastChapter) {
                val content = withContext(Dispatchers.IO) {
                    parser.getPageContent(bookFile, chapterIndex)
                }
                if (content != null) {
                    chapterContents[chapterIndex] = content
                } else {
                    AppLogger.w(TAG, "[FLEX] Failed to load chapter $chapterIndex")
                }
            }
            
            // If no chapters loaded, return null
            if (chapterContents.isEmpty()) {
                AppLogger.e(TAG, "[FLEX] No chapters loaded for window $windowIndex")
                return null
            }
            
            // Build wrapped HTML
            val html = buildWrappedHtml(windowIndex, firstChapter, lastChapter, chapterContents)
            
            if (html == null) {
                AppLogger.e(TAG, "[FLEX] Failed to build HTML for window $windowIndex")
                return null
            }
            
            AppLogger.d(TAG, "[FLEX] Assembled window $windowIndex: ${html.length} chars, " +
                "${chapterContents.size} chapters")
            
            WindowData(
                html = html,
                firstChapter = firstChapter,
                lastChapter = lastChapter,
                windowIndex = windowIndex,
                sliceMetadata = null // Will be populated by OffscreenSlicingWebView
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "[FLEX] Error assembling window $windowIndex", e)
            null
        }
    }
    
    /**
     * Build the wrapped HTML document with flex-friendly structure.
     * 
     * Key differences from column-based pagination:
     * - Uses data-chapter instead of data-chapter-index for clarity
     * - No CSS column styles (flex_paginator.js handles layout)
     * - Simpler structure optimized for node-walking algorithm
     * 
     * @param windowIndex The window index for metadata
     * @param firstChapter First chapter in the window
     * @param lastChapter Last chapter in the window
     * @param chapterContents Map of chapter indices to their content
     * @return The complete HTML document, or null if no sections added
     */
    private fun buildWrappedHtml(
        windowIndex: WindowIndex,
        firstChapter: ChapterIndex,
        lastChapter: ChapterIndex,
        chapterContents: Map<ChapterIndex, PageContent>
    ): String? {
        val builder = StringBuilder()
        var sectionsAdded = 0
        
        // Start window container
        builder.append("<div id=\"window-root\" data-window-index=\"$windowIndex\" ")
        builder.append("data-first-chapter=\"$firstChapter\" data-last-chapter=\"$lastChapter\">\n")
        
        // Add each chapter as a section
        for (chapterIndex in firstChapter..lastChapter) {
            val content = chapterContents[chapterIndex] ?: continue
            
            // Extract HTML or convert text to HTML
            val chapterHtml = content.html ?: wrapTextAsHtml(content.text)
            
            if (chapterHtml.isBlank()) {
                AppLogger.w(TAG, "[FLEX] Skipping empty chapter $chapterIndex")
                continue
            }
            
            // Wrap in section with data-chapter attribute
            // This attribute is used by flex_paginator.js to enforce hard breaks
            builder.append("  <section data-chapter=\"$chapterIndex\">\n")
            builder.append("    ")
            builder.append(chapterHtml)
            builder.append("\n  </section>\n")
            
            sectionsAdded++
        }
        
        // Close window container
        builder.append("</div>\n")
        
        // Return null if no sections were added
        if (sectionsAdded == 0) {
            AppLogger.w(TAG, "[FLEX] No sections added for window $windowIndex")
            return null
        }
        
        return builder.toString()
    }
    
    /**
     * Convert plain text to simple HTML paragraphs.
     * 
     * This is used when the parser returns only text content (e.g., for TXT files).
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
}
