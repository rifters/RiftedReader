package com.rifters.riftedreader.ui.reader

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.WebView
import com.google.gson.Gson
import com.rifters.riftedreader.domain.pagination.PaginatorConfig
import com.rifters.riftedreader.domain.pagination.PaginatorMode
import com.rifters.riftedreader.util.AppLogger
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.text.Charsets

/**
 * Bridge for communicating with the minimal paginator JavaScript API (Phase 3).
 * 
 * @deprecated This bridge object is deprecated and scheduled for removal.
 *             All pagination now uses PaginatorBridge.kt with minimal_paginator.js.
 *             Direct JavaScript evaluation should be used instead of these methods.
 * 
 * Legacy bridge that provided Kotlin functions to interact with `minimal_paginator.js`.
 * This has been replaced by:
 * - PaginatorBridge.kt: The ONLY bridge for pagination callbacks
 * - Direct evaluateJavascript() calls: For controlling pagination from Kotlin
 * 
 * This file is kept temporarily for reference during migration but should NOT be used.
 * 
 * @see PaginatorBridge for the current pagination bridge
 * @see app/src/main/assets/minimal_paginator.js for implementation
 */
@Deprecated(
    message = "Use PaginatorBridge and direct evaluateJavascript calls instead",
    replaceWith = ReplaceWith("Direct evaluateJavascript() calls to window.minimalPaginator"),
    level = DeprecationLevel.ERROR
)
object WebViewPaginatorBridge {
    
    private val mainHandler = Handler(Looper.getMainLooper())
    private val gson = Gson()
    
    // Synchronized cache - read directly from JS state without async callbacks
    // Updated in real-time by minimal_paginator.js via _syncPaginationState()
    private var cachedPageCount: Int = -1
    private var cachedCurrentPage: Int = 0
    
    /**
     * Check if the paginator is initialized and ready for operations.
     * 
     * @param webView The WebView to check
     * @return true if paginator is ready, false otherwise
     */
    suspend fun isReady(webView: WebView): Boolean {
        return try {
            evaluateBoolean(webView, "window.minimalPaginator && window.minimalPaginator.isReady()")
        } catch (e: Exception) {
            AppLogger.e("WebViewPaginatorBridge", "Error checking if paginator is ready", e)
            false
        }
    }
    
    /**
     * Configure the paginator before initialization.
     * 
     * This sets mode (window/chapter) and indices for logging/context.
     * Conveyor system passes complete window HTML to initialize().
     * 
     * @param webView The WebView to configure
     * @param config The paginator configuration
     */
    fun configure(webView: WebView, config: PaginatorConfig) {
        val mode = when (config.mode) {
            PaginatorMode.WINDOW -> "window"
            PaginatorMode.CHAPTER -> "chapter"
        }
        
        val jsConfig = "{mode: '$mode', windowIndex: ${config.windowIndex}}"
        
        AppLogger.d("WebViewPaginatorBridge", "configure: $jsConfig")
        mainHandler.post {
            webView.evaluateJavascript(
                "if (window.minimalPaginator) { window.minimalPaginator.configure($jsConfig); }",
                null
            )
        }
    }
    
    /**
     * Initialize the paginator after HTML is loaded.
     * 
     * Must be called after HTML content is loaded into the WebView.
     * This sets up the column layout and calculates page count.
     * 
     * @param webView The WebView containing the HTML
     */
    fun initialize(webView: WebView) {
        AppLogger.d("WebViewPaginatorBridge", "initialize: calling window.minimalPaginator.initialize()")
        mainHandler.post {
            webView.evaluateJavascript(
                "if (window.minimalPaginator) { window.minimalPaginator.initialize(); }",
                null
            )
        }
    }
    
    /**
     * Evaluate a JavaScript expression and return the result as an Int.
     * Must be called from a coroutine as it suspends until the result is available.
     * 
     * @param webView The WebView to evaluate JavaScript in
     * @param expression The JavaScript expression to evaluate
     * @return The result as an Int
     * @throws NumberFormatException if the result cannot be parsed as an Int
     * @throws Exception if JavaScript evaluation fails
     */
    private suspend fun evaluateInt(webView: WebView, expression: String): Int {
        val result = evaluateString(webView, expression)
        return result.toIntOrNull() 
            ?: throw NumberFormatException("JavaScript returned non-integer value: $result")
    }
    
    /**
     * Evaluate a JavaScript expression and return the result as a String.
     * Must be called from a coroutine as it suspends until the result is available.
     * 
     * @param webView The WebView to evaluate JavaScript in
     * @param expression The JavaScript expression to evaluate
     * @return The result as a String (may be "null" if JavaScript returns null/undefined)
     */
    private suspend fun evaluateString(webView: WebView, expression: String): String = 
        suspendCancellableCoroutine { continuation ->
            AppLogger.d("WebViewPaginatorBridge", "evaluateJavascript: $expression")
            mainHandler.post {
                try {
                    webView.evaluateJavascript(expression) { result ->
                        // Remove quotes from string results
                        val cleanResult = result?.trim()?.removeSurrounding("\"") ?: "null"
                        AppLogger.d("WebViewPaginatorBridge", "evaluateJavascript result: $cleanResult")
                        continuation.resume(cleanResult)
                    }
                } catch (e: Exception) {
                    AppLogger.e("WebViewPaginatorBridge", "evaluateJavascript exception for: $expression", e)
                    continuation.resumeWithException(e)
                }
            }
        }
    
    /**
     * Evaluate a JavaScript expression that returns a boolean.
     * 
     * @param webView The WebView to evaluate JavaScript in
     * @param expression The JavaScript expression to evaluate
     * @return The result as a Boolean
     */
    private suspend fun evaluateBoolean(webView: WebView, expression: String): Boolean {
        val result = evaluateString(webView, expression)
        return result == "true"
    }
    
    /**
     * Get total page count in current window.
     * Returns cached value - synchronized with minimal_paginator.js state.
     * No async callback - reads directly from paginator state.
     * 
     * @param webView The WebView to query (not used, reads cached state)
     * @return Page count, or -1 if not ready
     */
    fun getPageCount(webView: WebView): Int {
        return cachedPageCount
    }
    
    /**
     * Get currently displayed page index.
     * Returns cached value - synchronized with minimal_paginator.js state.
     * No async callback - reads directly from paginator state.
     * 
     * @param webView The WebView to query (not used, reads cached state)
     * @return Current page (0-indexed), or 0 if not ready
     */
    fun getCurrentPage(webView: WebView): Int {
        return cachedCurrentPage
    }
    
    /**
     * Internal method called by PaginationBridge to sync page state.
     * Called from ReaderPageFragment.PaginationBridge._syncPaginationState()
     * which receives the callback from minimal_paginator.js.
     * This allows Kotlin to read page info synchronously without async callbacks.
     * DO NOT call from Kotlin code directly.
     */
    fun _syncPaginationState(pageCount: Int, currentPage: Int) {
        cachedPageCount = pageCount
        cachedCurrentPage = currentPage
        AppLogger.d(
            "WebViewPaginatorBridge",
            "_syncPaginationState: pageCount=$pageCount, currentPage=$currentPage [SYNC]"
        )
    }
    
    /**
     * Navigate to specific page in current window.
     * 
     * @param webView The WebView to navigate
     * @param index Page index (0-indexed)
     * @param smooth Use smooth scroll animation
     */
    fun goToPage(webView: WebView, index: Int, smooth: Boolean = false) {
        AppLogger.d("WebViewPaginatorBridge", "goToPage: $index, smooth=$smooth")
        mainHandler.post {
            webView.evaluateJavascript(
                "if (window.minimalPaginator && window.minimalPaginator.isReady()) { window.minimalPaginator.goToPage($index, $smooth); }",
                null
            )
        }
    }
    
    /**
     * Navigate to next page.
     * 
     * Silently fails if paginator is not ready.
     * 
     * @param webView The WebView to navigate
     */
    fun nextPage(webView: WebView) {
        AppLogger.d("WebViewPaginatorBridge", "nextPage")
        mainHandler.post {
            webView.evaluateJavascript(
                "if (window.minimalPaginator && window.minimalPaginator.isReady()) { window.minimalPaginator.nextPage(); }",
                null
            )
        }
    }
    
    /**
     * Navigate to previous page.
     * 
     * Silently fails if paginator is not ready.
     * 
     * @param webView The WebView to navigate
     */
    fun prevPage(webView: WebView) {
        AppLogger.d("WebViewPaginatorBridge", "prevPage")
        mainHandler.post {
            webView.evaluateJavascript(
                "if (window.minimalPaginator && window.minimalPaginator.isReady()) { window.minimalPaginator.prevPage(); }",
                null
            )
        }
    }
    
    /**
     * Set font size and trigger reflow.
     * 
     * Recalculates page boundaries and character offsets after font change.
     * 
     * @param webView The WebView to modify
     * @param px Font size in pixels
     */
    fun setFontSize(webView: WebView, px: Int) {
        AppLogger.d("WebViewPaginatorBridge", "setFontSize: ${px}px")
        mainHandler.post {
            webView.evaluateJavascript(
                "if (window.minimalPaginator) { window.minimalPaginator.setFontSize($px); }",
                null
            )
        }
    }
    
    /**
     * Get character offset at start of a page.
     * 
     * Character offset is stable across font size changes.
     * Used for precise bookmark and progress tracking.
     * 
     * @param webView The WebView to query
     * @param pageIndex Page index (0-indexed)
     * @return Character offset within current window
     */
    suspend fun getCharacterOffsetForPage(webView: WebView, pageIndex: Int): Int {
        return try {
            evaluateInt(webView, "window.minimalPaginator.getCharacterOffsetForPage($pageIndex)")
        } catch (e: Exception) {
            AppLogger.e("WebViewPaginatorBridge", "Error getting character offset for page $pageIndex", e)
            0
        }
    }
    
    /**
     * Navigate to page containing specific character offset.
     * 
     * **NEW API**: Essential for restoring bookmarks after font size changes.
     * Character offset is stable; page indices shift with font changes.
     * 
     * @param webView The WebView to navigate
     * @param offset Character offset within current window
     */
    fun goToPageWithCharacterOffset(webView: WebView, offset: Int) {
        AppLogger.d("WebViewPaginatorBridge", "goToPageWithCharacterOffset: offset=$offset")
        mainHandler.post {
            webView.evaluateJavascript(
                "if (window.minimalPaginator) { window.minimalPaginator.goToPageWithCharacterOffset($offset); }",
                null
            )
        }
    }
    
}

private fun String.toBase64(): String {
    return Base64.encodeToString(this.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
}
