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
 * Builds pre-wrapped HTML for reading windows.
 * 
 * This builder generates complete HTML documents with:
 * - A #window-root container with window metadata
 * - Multiple <section data-chapter-index="N"> elements for each chapter
 * - Proper HTML/body styling for full viewport with overflow:hidden
 * - No body padding (padding applied post-init to paginator content wrapper)
 * 
 * The pre-wrapped approach eliminates the need for JavaScript to move
 * body children into a wrapper after injection - sections are detected
 * and used directly by the paginator.
 * 
 * Design decisions:
 * - html/body use overflow:hidden to prevent vertical scrolling
 * - Body has no padding (margin:0, padding:0)
 * - Padding is applied by the paginator to #paginator-content after init
 * - Image URLs use appassets.androidplatform.net scheme for WebViewAssetLoader
 */
object WindowHtmlBuilder {
    
    private const val TAG = "WindowHtmlBuilder"
    
    /**
     * Configuration for building window HTML.
     */
    data class Config(
        val textSizePx: Float,
        val lineHeightMultiplier: Float,
        val backgroundColor: String,  // Hex color like "#FFFFFF"
        val textColor: String,        // Hex color like "#000000"
        val enableDiagnostics: Boolean = false,
        val contentPaddingPx: Int = 16  // Padding applied post-init
    )
    
    /**
     * Build pre-wrapped HTML for a window containing multiple chapters.
     * 
     * @param windowIndex The window index for metadata
     * @param chapterContents Map of chapter index to PageContent
     * @param config Styling configuration
     * @return Complete HTML document, or null if no content available
     */
    fun buildWindowHtml(
        windowIndex: Int,
        chapterContents: Map<Int, PageContent>,
        config: Config
    ): String? {
        if (chapterContents.isEmpty()) {
            AppLogger.w(TAG, "buildWindowHtml: no chapter contents for window $windowIndex")
            return null
        }
        
        val sortedChapters = chapterContents.keys.sorted()
        AppLogger.d(TAG, "buildWindowHtml: window=$windowIndex, chapters=${sortedChapters.joinToString()}")
        
        val sectionHtml = StringBuilder()
        var sectionsAdded = 0
        
        for (chapterIndex in sortedChapters) {
            val pageContent = chapterContents[chapterIndex] ?: continue
            
            // Extract HTML or convert text to HTML
            val chapterHtml = pageContent.html ?: wrapTextAsHtml(pageContent.text)
            
            if (chapterHtml.isBlank()) {
                AppLogger.w(TAG, "Empty content for chapter $chapterIndex")
                continue
            }
            
            // Wrap in section with chapter ID for navigation
            sectionHtml.append(buildChapterSection(chapterIndex, chapterHtml))
            sectionsAdded++
        }
        
        if (sectionsAdded == 0) {
            AppLogger.w(TAG, "No sections added for window $windowIndex")
            return null
        }
        
        val fullHtml = buildFullDocument(
            windowIndex = windowIndex,
            windowRootContent = sectionHtml.toString(),
            config = config
        )
        
        AppLogger.d(TAG, "Built window HTML: window=$windowIndex, sections=$sectionsAdded, length=${fullHtml.length}")
        return fullHtml
    }
    
    /**
     * Build pre-wrapped HTML for a window using chapter data from a parser.
     * 
     * @param windowIndex The window index
     * @param firstChapterIndex First chapter index in window
     * @param lastChapterIndex Last chapter index in window
     * @param bookFile The book file to parse
     * @param parser The parser to use
     * @param config Styling configuration
     * @return Complete HTML document, or null on error
     */
    suspend fun buildFromParser(
        windowIndex: Int,
        firstChapterIndex: Int,
        lastChapterIndex: Int,
        bookFile: File,
        parser: BookParser,
        config: Config
    ): String? = withContext(Dispatchers.IO) {
        try {
            val chapterContents = mutableMapOf<Int, PageContent>()
            
            for (chapterIndex in firstChapterIndex..lastChapterIndex) {
                val pageContent = parser.getPageContent(bookFile, chapterIndex)
                chapterContents[chapterIndex] = pageContent
            }
            
            buildWindowHtml(windowIndex, chapterContents, config)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error building window HTML from parser", e)
            null
        }
    }
    
    /**
     * Build a single chapter section element.
     */
    private fun buildChapterSection(chapterIndex: Int, chapterHtml: String): String {
        return """
    <section id="chapter-$chapterIndex" data-chapter-index="$chapterIndex" class="chapter-section">
$chapterHtml
    </section>
"""
    }
    
    /**
     * Build the full HTML document with proper viewport and styling.
     */
    private fun buildFullDocument(
        windowIndex: Int,
        windowRootContent: String,
        config: Config
    ): String {
        val diagnosticsScript = if (config.enableDiagnostics) {
            """
    <script>
        // Enable pagination diagnostics logging
        document.addEventListener('DOMContentLoaded', function() {
            if (window.inpagePaginator && window.inpagePaginator.enableDiagnostics) {
                window.inpagePaginator.enableDiagnostics(true);
            }
        });
    </script>"""
        } else ""
        
        // Post-init padding injection script
        // Waits for onPaginationReady then applies padding to #paginator-content
        val paddingScript = """
    <script>
        // Post-init padding injection
        // Applied after pagination ready to avoid affecting column calculations
        (function() {
            'use strict';
            var CONTENT_PADDING_PX = ${config.contentPaddingPx};
            
            function injectPadding() {
                var content = document.getElementById('paginator-content');
                if (content) {
                    content.style.padding = CONTENT_PADDING_PX + 'px';
                    console.log('[PADDING] Injected padding: ' + CONTENT_PADDING_PX + 'px');
                    
                    // Trigger reflow after padding to recalculate columns
                    if (window.inpagePaginator && window.inpagePaginator.reapplyColumns) {
                        setTimeout(function() {
                            window.inpagePaginator.reapplyColumns();
                        }, 50);
                    }
                }
            }
            
            // Listen for custom paginationReady event
            document.addEventListener('paginationReady', function() {
                setTimeout(injectPadding, 100);
            });
            
            // Fallback: also check when paginator calls onPaginationReady
            var originalOnReady = window.AndroidBridge && window.AndroidBridge.onPaginationReady;
            if (window.AndroidBridge) {
                var wrappedOnReady = window.AndroidBridge.onPaginationReady;
                window.AndroidBridge.onPaginationReady = function(totalPages) {
                    if (wrappedOnReady) wrappedOnReady.call(window.AndroidBridge, totalPages);
                    setTimeout(injectPadding, 100);
                };
            }
        })();
    </script>"""
        
        return """<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="utf-8"/>
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
    <style>
        /* CRITICAL: html/body occupy full viewport with no scrolling */
        html, body {
            margin: 0;
            padding: 0;
            width: 100%;
            height: 100%;
            overflow: hidden;
            background-color: ${config.backgroundColor};
            color: ${config.textColor};
            font-size: ${config.textSizePx}px;
            line-height: ${config.lineHeightMultiplier};
            font-family: serif;
            /* Disable tap highlight that causes purple circles */
            -webkit-tap-highlight-color: transparent;
        }
        
        /* Window root container - full viewport */
        #window-root {
            width: 100%;
            height: 100%;
        }
        
        /* Chapter section styling */
        .chapter-section {
            margin-bottom: 2em;
        }
        
        /* Block element margins */
        p, div, article {
            margin: 0.8em 0;
        }
        
        /* Section styling within chapters */
        section.chapter-section > section {
            margin: 0.8em 0;
        }
        
        /* Headings */
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
        
        /* Blockquotes */
        blockquote {
            margin: 1em 0;
            padding-left: 1em;
            border-left: 3px solid ${config.textColor};
            font-style: italic;
        }
        
        /* Lists */
        ul, ol {
            margin: 0.5em 0;
            padding-left: 2em;
        }
        li {
            margin: 0.3em 0;
        }
        
        /* Images - prevent overflow and layout issues */
        img {
            max-width: 100% !important;
            height: auto !important;
            display: block;
            margin: 1em auto;
            object-fit: contain;
        }
        
        /* Code blocks */
        pre, code {
            font-family: monospace;
            background-color: rgba(128, 128, 128, 0.1);
            padding: 0.2em 0.4em;
            border-radius: 3px;
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
    </style>
    <script src="https://${EpubImageAssetHelper.ASSET_HOST}/assets/inpage_paginator.js"></script>
</head>
<body>
<div id="window-root" data-window-index="$windowIndex">
$windowRootContent</div>$diagnosticsScript$paddingScript
</body>
</html>"""
    }
    
    /**
     * Convert plain text to simple HTML paragraphs.
     */
    private fun wrapTextAsHtml(text: String): String {
        if (text.isBlank()) return ""
        
        return text.split("\n\n")
            .filter { it.isNotBlank() }
            .joinToString("\n") { paragraph ->
                val escaped = TextUtils.htmlEncode(paragraph.trim())
                "        <p>$escaped</p>"
            }
    }
}
