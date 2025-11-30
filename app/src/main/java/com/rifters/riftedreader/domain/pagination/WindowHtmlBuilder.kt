package com.rifters.riftedreader.domain.pagination

import android.text.TextUtils
import com.rifters.riftedreader.domain.parser.BookParser
import com.rifters.riftedreader.domain.parser.PageContent
import com.rifters.riftedreader.util.AppLogger
import com.rifters.riftedreader.util.EpubImageAssetHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Builds pre-wrapped HTML for paginated windows with proper CSS for column-based pagination.
 * 
 * This class generates complete HTML documents that include:
 * - Required head CSS (html/body 100%, overflow hidden, no body padding)
 * - Pre-wrapped chapter sections with data-chapter-index attributes
 * - Paginator script injection
 * - Theme-aware styling
 * 
 * The generated HTML is designed to work directly with inpage_paginator.js for
 * CSS column-based horizontal pagination without vertical fallback.
 */
object WindowHtmlBuilder {
    
    private const val TAG = "WindowHtmlBuilder"
    
    /** Script URL using WebViewAssetLoader domain for consistent URL handling */
    private val PAGINATOR_SCRIPT_URL = "https://${EpubImageAssetHelper.ASSET_HOST}/assets/inpage_paginator.js"
    
    /**
     * Configuration for building window HTML.
     */
    data class WindowHtmlConfig(
        val windowIndex: Int,
        val textSizePx: Float,
        val lineHeightMultiplier: Float,
        val backgroundColor: String,
        val textColor: String,
        val enableDiagnostics: Boolean = false
    )
    
    /**
     * Build pre-wrapped HTML for a window containing multiple chapters.
     * 
     * The generated HTML includes:
     * - Full DOCTYPE and HTML structure
     * - CSS optimized for column-based pagination (no body padding, 100% height, overflow hidden)
     * - Chapter sections with data-chapter-index for pre-wrapped content detection
     * - Paginator script injection
     * 
     * @param chapterContents Map of chapter index to HTML content
     * @param config Window HTML configuration
     * @return Complete HTML document ready for WebView.loadDataWithBaseURL()
     */
    fun buildWindowHtml(
        chapterContents: Map<Int, String>,
        config: WindowHtmlConfig
    ): String {
        AppLogger.d(TAG, "Building window HTML: windowIndex=${config.windowIndex}, chapters=${chapterContents.keys}")
        
        // Build chapter sections
        val sectionsHtml = StringBuilder()
        sectionsHtml.append("<div id=\"window-root\" data-window-index=\"${config.windowIndex}\">\n")
        
        for ((chapterIndex, chapterHtml) in chapterContents.entries.sortedBy { it.key }) {
            if (chapterHtml.isBlank()) {
                AppLogger.w(TAG, "Empty content for chapter $chapterIndex, skipping")
                continue
            }
            
            sectionsHtml.append("  <section id=\"chapter-$chapterIndex\" data-chapter-index=\"$chapterIndex\">\n")
            sectionsHtml.append("    ")
            sectionsHtml.append(chapterHtml)
            sectionsHtml.append("\n  </section>\n")
        }
        
        sectionsHtml.append("</div>\n")
        
        // Enable diagnostics in paginator if configured
        val diagnosticsScript = if (config.enableDiagnostics) {
            """
            <script>
                // Enable pagination diagnostics logging
                if (window.inpagePaginator && window.inpagePaginator.enableDiagnostics) {
                    window.inpagePaginator.enableDiagnostics(true);
                }
            </script>
            """
        } else ""
        
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="utf-8"/>
                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=3.0, user-scalable=yes">
                <style>
                    /* CRITICAL: html/body 100% height and overflow hidden for column pagination */
                    html {
                        margin: 0;
                        padding: 0;
                        width: 100%;
                        height: 100%;
                        overflow: hidden;
                        background-color: ${config.backgroundColor};
                    }
                    body {
                        margin: 0;
                        /* CRITICAL: NO body padding - padding applied to #paginator-content post-init */
                        padding: 0;
                        width: 100%;
                        height: 100%;
                        overflow: hidden;
                        background-color: ${config.backgroundColor};
                        color: ${config.textColor};
                        font-size: ${config.textSizePx}px;
                        line-height: ${config.lineHeightMultiplier};
                        font-family: serif;
                        /* Prevent text selection during swipe gestures */
                        -webkit-user-select: none;
                        user-select: none;
                    }
                    /* Allow text selection within content wrapper after paginator ready */
                    #paginator-content {
                        -webkit-user-select: text;
                        user-select: text;
                    }
                    /* Window root container */
                    #window-root {
                        width: 100%;
                    }
                    /* Preserve formatting for all block elements */
                    p, div, article {
                        margin: 0.8em 0;
                    }
                    /* Section styling for window-based chapters */
                    section[data-chapter-index] {
                        margin-bottom: 2em;
                    }
                    h1, h2, h3, h4, h5, h6 {
                        margin: 1em 0 0.5em 0;
                        font-weight: bold;
                        line-height: 1.3;
                    }
                    h1 { font-size: 2em; }
                    h2 { font-size: 1.75em; }
                    h3 { font-size: 1.5em; }
                    h4 { font-size: 1.25em; }
                    h5 { font-size: 1.1em; }
                    h6 { font-size: 1em; }
                    blockquote {
                        margin: 1em 0;
                        padding-left: 1em;
                        border-left: 3px solid ${config.textColor};
                        font-style: italic;
                    }
                    ul, ol {
                        margin: 0.5em 0;
                        padding-left: 2em;
                    }
                    li {
                        margin: 0.3em 0;
                    }
                    /* Image styling - prevent overflow and layout issues */
                    img {
                        max-width: 100% !important;
                        height: auto !important;
                        display: block;
                        margin: 1em auto;
                        /* Prevent images from breaking columns */
                        break-inside: avoid;
                        -webkit-column-break-inside: avoid;
                    }
                    /* Window-root specific image styling to prevent overflow-induced layout issues */
                    #window-root img {
                        max-width: 100% !important;
                        height: auto !important;
                        display: block;
                        object-fit: contain;
                    }
                    pre, code {
                        font-family: monospace;
                        background-color: rgba(128, 128, 128, 0.1);
                        padding: 0.2em 0.4em;
                        border-radius: 3px;
                        /* Prevent code blocks from breaking columns */
                        break-inside: avoid;
                        -webkit-column-break-inside: avoid;
                    }
                    pre {
                        padding: 1em;
                        overflow-x: auto;
                    }
                    /* TTS highlighting */
                    [data-tts-chunk] {
                        cursor: pointer;
                        transition: background-color 0.2s ease-in-out;
                    }
                    .tts-highlight {
                        background-color: rgba(255, 213, 79, 0.4) !important;
                    }
                    /* TTS root container - hidden but accessible */
                    #tts-root {
                        position: absolute;
                        top: -9999px;
                        left: -9999px;
                        overflow: hidden;
                        width: 1px;
                        height: 1px;
                    }
                </style>
                <script src="$PAGINATOR_SCRIPT_URL"></script>
            </head>
            <body>
                <!-- TTS root container for TTS DOM operations -->
                <div id="tts-root" aria-hidden="true"></div>
                ${sectionsHtml}
                $diagnosticsScript
            </body>
            </html>
        """.trimIndent()
    }
    
    /**
     * Build pre-wrapped HTML from PageContent objects.
     * 
     * @param chapters Map of chapter index to PageContent
     * @param config Window HTML configuration
     * @return Complete HTML document ready for WebView.loadDataWithBaseURL()
     */
    fun buildFromPageContents(
        chapters: Map<Int, PageContent>,
        config: WindowHtmlConfig
    ): String {
        val contentMap = chapters.mapValues { (_, pageContent) ->
            pageContent.html ?: wrapTextAsHtml(pageContent.text)
        }
        return buildWindowHtml(contentMap, config)
    }
    
    /**
     * Build pre-wrapped HTML directly from a book parser.
     * 
     * @param bookFile The book file to read from
     * @param parser The parser to use for extracting content
     * @param chapterIndices List of chapter indices to include in the window
     * @param config Window HTML configuration
     * @return Complete HTML document ready for WebView.loadDataWithBaseURL()
     */
    suspend fun buildFromParser(
        bookFile: File,
        parser: BookParser,
        chapterIndices: List<Int>,
        config: WindowHtmlConfig
    ): String {
        val chapters = mutableMapOf<Int, PageContent>()
        
        for (chapterIndex in chapterIndices) {
            val pageContent = withContext(Dispatchers.IO) {
                parser.getPageContent(bookFile, chapterIndex)
            }
            chapters[chapterIndex] = pageContent
        }
        
        return buildFromPageContents(chapters, config)
    }
    
    /**
     * Convert plain text to simple HTML paragraphs.
     */
    private fun wrapTextAsHtml(text: String): String {
        if (text.isBlank()) return "<p></p>"
        
        // Split by paragraphs (double newlines) and wrap each in <p> tags
        return text.split("\n\n")
            .filter { it.isNotBlank() }
            .joinToString("\n") { paragraph ->
                val escaped = TextUtils.htmlEncode(paragraph.trim())
                "<p>$escaped</p>"
            }
    }
}
