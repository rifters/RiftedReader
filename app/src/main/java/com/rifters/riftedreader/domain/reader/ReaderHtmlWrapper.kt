package com.rifters.riftedreader.domain.reader

import android.text.TextUtils
import com.rifters.riftedreader.ui.reader.ReaderThemePalette

/**
 * Configuration for wrapping HTML content for WebView display.
 */
data class ReaderHtmlConfig(
    val textSizePx: Float,
    val lineHeightMultiplier: Float,
    val palette: ReaderThemePalette
)

/**
 * Wraps HTML content with proper styling and scripts for WebView display.
 * 
 * This wrapper can handle both single-chapter HTML and sliding-window HTML
 * (multiple chapters combined in <section> blocks).
 */
object ReaderHtmlWrapper {
    
    /**
     * Convert a color integer to a hex color string.
     */
    private fun colorToHex(color: Int): String {
        return String.format("#%06X", 0xFFFFFF and color)
    }
    
    /**
     * Wrap arbitrary HTML content for display in a WebView.
     * 
     * @param contentHtml The HTML content to wrap (can be single chapter or multi-chapter window)
     * @param config Configuration for styling and display
     * @return Complete HTML document ready for WebView.loadDataWithBaseURL()
     */
    fun wrap(contentHtml: String, config: ReaderHtmlConfig): String {
        val backgroundColor = colorToHex(config.palette.backgroundColor)
        val textColor = colorToHex(config.palette.textColor)
        
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="utf-8"/>
                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=3.0, user-scalable=yes">
                <style>
                    html, body {
                        margin: 0;
                        padding: 0;
                        background-color: $backgroundColor;
                        color: $textColor;
                        font-size: ${config.textSizePx}px;
                        line-height: ${config.lineHeightMultiplier};
                        font-family: serif;
                    }
                    body {
                        padding: 16px;
                        word-wrap: break-word;
                        overflow-wrap: break-word;
                    }
                    /* Preserve formatting for all block elements */
                    p, div, section, article {
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
                        border-left: 3px solid $textColor;
                        font-style: italic;
                    }
                    ul, ol {
                        margin: 0.5em 0;
                        padding-left: 2em;
                    }
                    li {
                        margin: 0.3em 0;
                    }
                    img {
                        max-width: 100% !important;
                        height: auto !important;
                        display: block;
                        margin: 1em auto;
                    }
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
                <script src="file:///android_asset/inpage_paginator.js"></script>
            </head>
            <body>
                $contentHtml
            </body>
            </html>
        """.trimIndent()
    }
}
