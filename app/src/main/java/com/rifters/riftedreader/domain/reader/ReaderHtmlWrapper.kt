package com.rifters.riftedreader.domain.reader

import com.rifters.riftedreader.ui.reader.ReaderThemePalette
import com.rifters.riftedreader.util.EpubImageAssetHelper

/**
 * Configuration for wrapping HTML content for WebView display.
 */
data class ReaderHtmlConfig(
    val textSizePx: Float,
    val lineHeightMultiplier: Float,
    val palette: ReaderThemePalette,
    val enableDiagnostics: Boolean = false,
    val contentPaddingPx: Int = 16  // Padding applied post-init to paginator content
)

/**
 * Wraps HTML content with proper styling and scripts for WebView display.
 * 
 * This wrapper can handle both single-chapter HTML and sliding-window HTML
 * (multiple chapters combined in <section> blocks).
 * 
 * ROBUSTNESS DESIGN:
 * - html/body occupy full viewport with overflow:hidden to prevent vertical scroll
 * - Body has no padding (margin:0, padding:0)
 * - Padding is applied post-init by paginator via paginationReady event
 * - Tap highlight is disabled to prevent purple circles
 */
object ReaderHtmlWrapper {
    
    /** Script URL using WebViewAssetLoader domain for consistent URL handling */
    private val PAGINATOR_SCRIPT_URL = "https://${EpubImageAssetHelper.ASSET_HOST}/assets/inpage_paginator.js"
    
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
        
        // Post-init padding injection script
        // Waits for paginationReady event then applies padding to #paginator-content
        val paddingScript = """
            <script>
                // Post-init padding injection
                // Applied after pagination ready to avoid affecting column calculations
                (function() {
                    'use strict';
                    var CONTENT_PADDING_PX = ${config.contentPaddingPx};
                    var paddingApplied = false;
                    
                    function injectPadding() {
                        if (paddingApplied) return;
                        var content = document.getElementById('paginator-content');
                        if (content) {
                            content.style.padding = CONTENT_PADDING_PX + 'px';
                            paddingApplied = true;
                            console.log('[PADDING] Injected padding: ' + CONTENT_PADDING_PX + 'px');
                            
                            // Trigger reflow after padding to recalculate columns
                            if (window.inpagePaginator && window.inpagePaginator.reapplyColumns) {
                                setTimeout(function() {
                                    window.inpagePaginator.reapplyColumns();
                                }, 50);
                            }
                        }
                    }
                    
                    // Listen for custom paginationReady event from paginator
                    document.addEventListener('paginationReady', function() {
                        setTimeout(injectPadding, 100);
                    });
                })();
            </script>
        """
        
        // TTS initialization script - ensures #tts-root exists and emits ttsReady event
        val ttsInitScript = """
            <script>
                // TTS DOM initialization guard - create container if missing
                (function() {
                    'use strict';
                    
                    function ensureTtsRoot() {
                        if (!document.getElementById('tts-root')) {
                            var ttsRoot = document.createElement('div');
                            ttsRoot.id = 'tts-root';
                            ttsRoot.style.cssText = 'position:absolute;top:-9999px;left:-9999px;overflow:hidden;width:1px;height:1px;';
                            document.body.appendChild(ttsRoot);
                            console.log('[TTS] Created #tts-root container on DOMContentLoaded');
                        }
                    }
                    
                    function emitTtsReady() {
                        var event = new CustomEvent('ttsReady', { detail: { timestamp: Date.now() } });
                        document.dispatchEvent(event);
                        console.log('[TTS] Emitted ttsReady event');
                        
                        if (window.AndroidTtsBridge && window.AndroidTtsBridge.onTtsReady) {
                            try {
                                window.AndroidTtsBridge.onTtsReady();
                            } catch (e) {
                                console.warn('[TTS] Failed to notify Android bridge:', e);
                            }
                        }
                    }
                    
                    function waitForPaginatorThenEmitReady() {
                        if (window.inpagePaginator && window.inpagePaginator.isReady && window.inpagePaginator.isReady()) {
                            emitTtsReady();
                        } else {
                            setTimeout(waitForPaginatorThenEmitReady, 100);
                        }
                    }
                    
                    function onDomReady() {
                        ensureTtsRoot();
                        waitForPaginatorThenEmitReady();
                    }
                    
                    if (document.readyState === 'loading') {
                        document.addEventListener('DOMContentLoaded', onDomReady);
                    } else {
                        onDomReady();
                    }
                })();
            </script>
        """
        
        return """
            <!DOCTYPE html>
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
                        background-color: $backgroundColor;
                        color: $textColor;
                        font-size: ${config.textSizePx}px;
                        line-height: ${config.lineHeightMultiplier};
                        font-family: serif;
                        /* Disable tap highlight that causes purple circles */
                        -webkit-tap-highlight-color: transparent;
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
                    /* Preserve formatting for all block elements */
                    p, div, article {
                        margin: 0.8em 0;
                    }
                    /* Section styling for window-based chapters */
                    section[data-chapter-index] {
                        margin-bottom: 2em;
                    }
                    /* Section within sections (EPUB structure) */
                    section[data-chapter-index] > section {
                        margin: 0.8em 0;
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
                    /* Image styling - prevent overflow and layout issues */
                    img {
                        max-width: 100% !important;
                        height: auto !important;
                        display: block;
                        margin: 1em auto;
                        object-fit: contain;
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
                <script src="$PAGINATOR_SCRIPT_URL"></script>
            </head>
            <body>
                <!-- TTS root container for TTS DOM operations -->
                <div id="tts-root" aria-hidden="true"></div>
                $contentHtml
                $diagnosticsScript
                $paddingScript
                $ttsInitScript
            </body>
            </html>
        """.trimIndent()
    }
}
