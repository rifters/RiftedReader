package com.rifters.riftedreader.ui.reader

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.WebView
import com.google.gson.Gson
import com.rifters.riftedreader.domain.pagination.ChapterBoundaryInfo
import com.rifters.riftedreader.domain.pagination.EntryPosition
import com.rifters.riftedreader.domain.pagination.PageMappingInfo
import com.rifters.riftedreader.domain.pagination.PaginatorConfig
import com.rifters.riftedreader.domain.pagination.PaginatorMode
import com.rifters.riftedreader.domain.pagination.WindowDescriptor
import com.rifters.riftedreader.util.AppLogger
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.text.Charsets

/**
 * Bridge for communicating with the in-page paginator JavaScript API.
 * 
 * Provides convenient Kotlin functions to interact with the inpagePaginator
 * JavaScript object injected into WebView content.
 * 
 * ## Window Communication API
 * 
 * This bridge implements the pseudo-API for Android ↔ JavaScript communication:
 * 
 * ### Commands (Android → JavaScript)
 * - loadWindow(descriptor) - Load a complete window for reading
 * - goToPage(index, animate) - Navigate to specific page
 * - getPageCount() - Query total pages
 * - getCurrentPage() - Query current page
 * - getCurrentChapter() - Query current chapter
 * - getChapterBoundaries() - Get chapter page mappings
 * - getPageMappingInfo() - Get detailed position info
 * 
 * ### Events (JavaScript → Android)
 * Events are handled via AndroidBridge JavascriptInterface in ReaderPageFragment:
 * - onWindowLoaded - Window ready for reading
 * - onPageChanged - User navigated to new page
 * - onBoundaryReached - User at window boundary
 * - onWindowFinalized - Window locked for reading
 * - onWindowLoadError - Error during window load
 * 
 * See docs/complete/WINDOW_COMMUNICATION_API.md for full documentation.
 */
object WebViewPaginatorBridge {
    
    private val mainHandler = Handler(Looper.getMainLooper())
    private val gson = Gson()
    
    /**
     * Check if the paginator is initialized and ready for operations.
     * This should be called before any other paginator methods.
     * 
     * @param webView The WebView to check
     * @return true if paginator is ready, false otherwise
     */
    suspend fun isReady(webView: WebView): Boolean {
        return try {
            evaluateBoolean(webView, "window.inpagePaginator && window.inpagePaginator.isReady()")
        } catch (e: Exception) {
            AppLogger.e("WebViewPaginatorBridge", "Error checking if paginator is ready", e)
            false
        }
    }
    
    /**
     * Configure the paginator before initialization.
     * This method should be called before the paginator is initialized to set
     * the pagination mode and context.
     * 
     * @param webView The WebView to configure
     * @param config The paginator configuration
     */
    fun configure(webView: WebView, config: PaginatorConfig) {
        val mode = when (config.mode) {
            PaginatorMode.WINDOW -> "window"
            PaginatorMode.CHAPTER -> "chapter"
        }
        
        val chapterIndexParam = config.chapterIndex?.let { ", chapterIndex: $it" } ?: ""
        // Escape rootSelector for safe JavaScript string interpolation
        // Handle single quotes, backslashes, newlines, and control characters
        val rootSelectorParam = config.rootSelector?.let { 
            val escaped = it
                .replace("\\", "\\\\")  // Escape backslashes first
                .replace("'", "\\'")     // Escape single quotes
                .replace("\n", "\\n")    // Escape newlines
                .replace("\r", "\\r")    // Escape carriage returns
                .replace("\t", "\\t")    // Escape tabs
            ", rootSelector: '$escaped'" 
        } ?: ""
        
        val jsConfig = "{ mode: '$mode', windowIndex: ${config.windowIndex}$chapterIndexParam$rootSelectorParam }"
        
        AppLogger.d("WebViewPaginatorBridge", "configure: $jsConfig")
        mainHandler.post {
            webView.evaluateJavascript(
                "if (window.inpagePaginator) { window.inpagePaginator.configure($jsConfig); }",
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
    suspend fun evaluateInt(webView: WebView, expression: String): Int {
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
    suspend fun evaluateString(webView: WebView, expression: String): String = 
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
    suspend fun evaluateBoolean(webView: WebView, expression: String): Boolean {
        val result = evaluateString(webView, expression)
        return result == "true"
    }
    
    /**
     * Get the current page count from the paginator.
     * Returns 1 if paginator is not ready.
     * 
     * @param webView The WebView containing the paginated content
     * @return The number of pages
     */
    suspend fun getPageCount(webView: WebView): Int {
        return try {
            if (!isReady(webView)) {
                AppLogger.d("WebViewPaginatorBridge", "getPageCount: paginator not ready, returning 1")
                return 1
            }
            val count = evaluateInt(webView, "window.inpagePaginator.getPageCount()")
            AppLogger.d("WebViewPaginatorBridge", "getPageCount: $count pages")
            count
        } catch (e: Exception) {
            AppLogger.e("WebViewPaginatorBridge", "Error getting page count", e)
            1 // Return safe default
        }
    }
    
    /**
     * Get the current page index (0-based).
     * Returns 0 if paginator is not ready.
     * 
     * @param webView The WebView containing the paginated content
     * @return The current page index
     */
    suspend fun getCurrentPage(webView: WebView): Int {
        return try {
            if (!isReady(webView)) {
                AppLogger.d("WebViewPaginatorBridge", "getCurrentPage: paginator not ready, returning 0")
                return 0
            }
            val page = evaluateInt(webView, "window.inpagePaginator.getCurrentPage()")
            AppLogger.d("WebViewPaginatorBridge", "getCurrentPage: $page")
            page
        } catch (e: Exception) {
            AppLogger.e("WebViewPaginatorBridge", "Error getting current page", e)
            0 // Return safe default
        }
    }
    
    /**
     * Navigate to a specific page.
     * This method does not suspend - it fires and forgets.
     * Silently fails if paginator is not ready.
     * 
     * @param webView The WebView containing the paginated content
     * @param index The page index to navigate to (0-based)
     * @param smooth Whether to animate the transition (default: true)
     */
    fun goToPage(webView: WebView, index: Int, smooth: Boolean = true) {
        AppLogger.userAction("WebViewPaginatorBridge", "goToPage: index=$index, smooth=$smooth", "ui/webview/pagination")
        mainHandler.post {
            webView.evaluateJavascript(
                "if (window.inpagePaginator && window.inpagePaginator.isReady()) { window.inpagePaginator.goToPage($index, $smooth); }",
                null
            )
        }
    }
    
    /**
     * Set the font size for the paginated content.
     * This will trigger a reflow of the columns.
     * This method does not suspend - it fires and forgets.
     * Silently fails if paginator is not ready.
     * 
     * @param webView The WebView containing the paginated content
     * @param px The font size in pixels
     */
    fun setFontSize(webView: WebView, px: Int) {
        AppLogger.d("WebViewPaginatorBridge", "setFontSize: ${px}px - triggering reflow")
        mainHandler.post {
            webView.evaluateJavascript(
                "if (window.inpagePaginator) { window.inpagePaginator.setFontSize($px); }",
                null
            )
        }
    }

    fun setInitialChapter(webView: WebView, chapterIndex: Int) {
        AppLogger.d("WebViewPaginatorBridge", "setInitialChapter: chapter=$chapterIndex")
        mainHandler.post {
            webView.evaluateJavascript(
                "if (window.inpagePaginator) { window.inpagePaginator.setInitialChapter($chapterIndex); }",
                null
            )
        }
    }

    fun appendChapter(webView: WebView, chapterIndex: Int, html: String) {
        AppLogger.userAction(
            "WebViewPaginatorBridge",
            "appendChapter: chapter=$chapterIndex length=${html.length}",
            "ui/webview/streaming"
        )
        val encoded = html.toBase64()
        mainHandler.post {
            webView.evaluateJavascript(
                "if (window.inpagePaginator) { window.inpagePaginator.appendChapter($chapterIndex, atob('$encoded')); }",
                null
            )
        }
    }

    fun prependChapter(webView: WebView, chapterIndex: Int, html: String) {
        AppLogger.userAction(
            "WebViewPaginatorBridge",
            "prependChapter: chapter=$chapterIndex length=${html.length}",
            "ui/webview/streaming"
        )
        val encoded = html.toBase64()
        mainHandler.post {
            webView.evaluateJavascript(
                "if (window.inpagePaginator) { window.inpagePaginator.prependChapter($chapterIndex, atob('$encoded')); }",
                null
            )
        }
    }

    suspend fun getSegmentPageCount(webView: WebView, chapterIndex: Int): Int {
        return evaluateInt(
            webView,
            "window.inpagePaginator.getSegmentPageCount($chapterIndex)"
        )
    }
    
    /**
     * Navigate to the next page.
     * This method does not suspend - it fires and forgets.
     * Silently fails if paginator is not ready.
     * 
     * @param webView The WebView containing the paginated content
     */
    fun nextPage(webView: WebView) {
        AppLogger.d("WebViewPaginatorBridge", "nextPage: navigating to next in-page")
        mainHandler.post {
            webView.evaluateJavascript(
                "if (window.inpagePaginator && window.inpagePaginator.isReady()) { window.inpagePaginator.nextPage(); }",
                null
            )
        }
    }
    
    /**
     * Navigate to the previous page.
     * This method does not suspend - it fires and forgets.
     * Silently fails if paginator is not ready.
     * 
     * @param webView The WebView containing the paginated content
     */
    fun prevPage(webView: WebView) {
        AppLogger.d("WebViewPaginatorBridge", "prevPage: navigating to previous in-page")
        mainHandler.post {
            webView.evaluateJavascript(
                "if (window.inpagePaginator && window.inpagePaginator.isReady()) { window.inpagePaginator.prevPage(); }",
                null
            )
        }
    }
    
    /**
     * Get the page index for a given CSS selector.
     * 
     * @param webView The WebView containing the paginated content
     * @param selector The CSS selector to find
     * @return The page index containing the element, or -1 if not found
     */
    suspend fun getPageForSelector(webView: WebView, selector: String): Int {
        val escapedSelector = selector.replace("'", "\\'")
        return evaluateInt(webView, "window.inpagePaginator.getPageForSelector('$escapedSelector')")
    }
    
    /**
     * Jump to a specific chapter by chapter index.
     * Useful for TOC navigation - scrolls to the first page of the specified chapter.
     * This method does not suspend - it fires and forgets.
     * Silently fails if paginator is not ready or chapter not loaded.
     * 
     * @param webView The WebView containing the paginated content
     * @param chapterIndex The chapter index to jump to
     * @param smooth Whether to animate the transition (default: true)
     */
    fun jumpToChapter(webView: WebView, chapterIndex: Int, smooth: Boolean = true) {
        AppLogger.userAction("WebViewPaginatorBridge", "jumpToChapter: chapter=$chapterIndex, smooth=$smooth", "ui/webview/toc")
        mainHandler.post {
            webView.evaluateJavascript(
                "if (window.inpagePaginator) { window.inpagePaginator.jumpToChapter($chapterIndex, $smooth); }",
                null
            )
        }
    }
    
    /**
     * Remove a specific chapter segment by chapter index.
     * This method does not suspend - it fires and forgets.
     * 
     * @param webView The WebView containing the paginated content
     * @param chapterIndex The chapter index to remove
     */
    fun removeChapter(webView: WebView, chapterIndex: Int) {
        AppLogger.d("WebViewPaginatorBridge", "removeChapter: chapter=$chapterIndex")
        mainHandler.post {
            webView.evaluateJavascript(
                "if (window.inpagePaginator) { window.inpagePaginator.removeChapter($chapterIndex); }",
                null
            )
        }
    }
    
    /**
     * Clear all chapter segments.
     * This method does not suspend - it fires and forgets.
     * 
     * @param webView The WebView containing the paginated content
     */
    fun clearAllSegments(webView: WebView) {
        AppLogger.d("WebViewPaginatorBridge", "clearAllSegments")
        mainHandler.post {
            webView.evaluateJavascript(
                "if (window.inpagePaginator) { window.inpagePaginator.clearAllSegments(); }",
                null
            )
        }
    }
    
    /**
     * Get the chapter index for the currently visible page.
     * 
     * @param webView The WebView containing the paginated content
     * @return The chapter index or -1 if not found
     */
    suspend fun getCurrentChapter(webView: WebView): Int {
        return try {
            evaluateInt(webView, "window.inpagePaginator.getCurrentChapter()")
        } catch (e: Exception) {
            AppLogger.e("WebViewPaginatorBridge", "Error getting current chapter", e)
            -1
        }
    }
    
    /**
     * Get information about all loaded chapter segments.
     * 
     * @param webView The WebView containing the paginated content
     * @return JSON string with chapter info array
     */
    suspend fun getLoadedChapters(webView: WebView): String {
        return try {
            evaluateString(webView, "JSON.stringify(window.inpagePaginator.getLoadedChapters())")
        } catch (e: Exception) {
            AppLogger.e("WebViewPaginatorBridge", "Error getting loaded chapters", e)
            "[]"
        }
    }
    
    /**
     * Trigger a reflow of the paginated content with optional position preservation.
     * This method does not suspend - it fires and forgets.
     * 
     * @param webView The WebView containing the paginated content
     * @param preservePosition Whether to try to preserve the reading position (default: true)
     */
    fun reflow(webView: WebView, preservePosition: Boolean = true) {
        AppLogger.d("WebViewPaginatorBridge", "reflow: preservePosition=$preservePosition")
        mainHandler.post {
            webView.evaluateJavascript(
                "if (window.inpagePaginator) { window.inpagePaginator.reflow($preservePosition); }",
                null
            )
        }
    }
    
    /**
     * Create an anchor around the viewport top to preserve reading position.
     * 
     * @param webView The WebView containing the paginated content
     * @param anchorId The ID for the anchor element
     * @return true if anchor was created successfully
     */
    suspend fun createAnchorAroundViewportTop(webView: WebView, anchorId: String): Boolean {
        return evaluateBoolean(
            webView,
            "window.inpagePaginator.createAnchorAroundViewportTop('$anchorId')"
        )
    }
    
    /**
     * Scroll to an anchor element by ID.
     * 
     * @param webView The WebView containing the paginated content
     * @param anchorId The ID of the anchor element
     * @return true if scrolled successfully
     */
    suspend fun scrollToAnchor(webView: WebView, anchorId: String): Boolean {
        return evaluateBoolean(
            webView,
            "window.inpagePaginator.scrollToAnchor('$anchorId')"
        )
    }
    
    // ========================================================================
    // Window Communication API
    // ========================================================================
    // These methods implement the pseudo-API for Android ↔ JavaScript 
    // communication for managing reading windows.
    // See docs/complete/WINDOW_COMMUNICATION_API.md for full documentation.
    
    /**
     * Load a complete window for reading.
     * 
     * This is the primary entry point for initializing a window after loading
     * HTML content into the WebView. It:
     * 1. Configures the paginator for window mode
     * 2. Finalizes the window (locks it for reading)
     * 3. Navigates to the entry position if specified
     * 
     * The HTML content should already be loaded via loadDataWithBaseURL().
     * 
     * @param webView The WebView containing the window content
     * @param descriptor The window descriptor with chapters and entry position
     */
    fun loadWindow(webView: WebView, descriptor: WindowDescriptor) {
        val descriptorJson = gson.toJson(descriptor)
        AppLogger.userAction(
            "WebViewPaginatorBridge",
            "loadWindow: windowIndex=${descriptor.windowIndex}, chapters=${descriptor.firstChapterIndex}-${descriptor.lastChapterIndex}",
            "ui/webview/window"
        )
        mainHandler.post {
            webView.evaluateJavascript(
                "if (window.inpagePaginator && window.inpagePaginator.loadWindow) { window.inpagePaginator.loadWindow($descriptorJson); }",
                null
            )
        }
    }
    
    /**
     * Finalize the window (lock it for reading).
     * 
     * After calling this, no mutations (append/prepend) are allowed.
     * Should be called after window HTML is fully loaded and before reading begins.
     * 
     * @param webView The WebView containing the window content
     */
    fun finalizeWindow(webView: WebView) {
        AppLogger.d("WebViewPaginatorBridge", "finalizeWindow: locking window for reading")
        mainHandler.post {
            webView.evaluateJavascript(
                "if (window.inpagePaginator && window.inpagePaginator.finalizeWindow) { window.inpagePaginator.finalizeWindow(); }",
                null
            )
        }
    }
    
    /**
     * Get chapter boundaries for all chapters in the current window.
     * 
     * Returns page ranges for each chapter, enabling:
     * - Chapter navigation
     * - Position tracking
     * - Progress calculation
     * 
     * @param webView The WebView containing the window content
     * @return List of chapter boundary info, or empty list on error
     */
    suspend fun getChapterBoundaries(webView: WebView): List<ChapterBoundaryInfo> {
        return try {
            // Use getChapterBoundaries if available, fallback to getLoadedChapters for compatibility
            val jsExpression = """
                (function() {
                    var p = window.inpagePaginator;
                    if (!p) return null;
                    if (p.getChapterBoundaries) return p.getChapterBoundaries();
                    if (p.getLoadedChapters) return p.getLoadedChapters();
                    return [];
                })()
            """.trimIndent()
            val json = evaluateString(webView, "JSON.stringify($jsExpression)")
            if (json == "null" || json == "[]") {
                emptyList()
            } else {
                gson.fromJson(json, Array<ChapterBoundaryInfo>::class.java).toList()
            }
        } catch (e: Exception) {
            AppLogger.e("WebViewPaginatorBridge", "Error getting chapter boundaries", e)
            emptyList()
        }
    }
    
    /**
     * Get detailed page mapping info for the current position.
     * 
     * Returns information about:
     * - Current window and chapter indices
     * - Page position within window and chapter
     * - Total page counts
     * 
     * Useful for accurate position restoration during window transitions.
     * 
     * @param webView The WebView containing the window content
     * @return PageMappingInfo or null on error
     */
    suspend fun getPageMappingInfo(webView: WebView): PageMappingInfo? {
        return try {
            val json = evaluateString(
                webView,
                "JSON.stringify(window.inpagePaginator.getPageMappingInfo ? window.inpagePaginator.getPageMappingInfo() : null)"
            )
            if (json == "null") {
                null
            } else {
                gson.fromJson(json, PageMappingInfo::class.java)
            }
        } catch (e: Exception) {
            AppLogger.e("WebViewPaginatorBridge", "Error getting page mapping info", e)
            null
        }
    }
    
    /**
     * Navigate to entry position after window load.
     * 
     * Helper method to jump to a specific position, typically used during
     * window transitions to restore reading position.
     * 
     * Note: Uses non-smooth scrolling (false) to ensure immediate positioning.
     * The jumpToChapter and goToPage calls update the internal currentPage state
     * synchronously, even though the scrolling may have a small delay.
     * 
     * @param webView The WebView containing the window content
     * @param entryPosition The position to navigate to
     */
    fun navigateToEntryPosition(webView: WebView, entryPosition: EntryPosition) {
        AppLogger.d(
            "WebViewPaginatorBridge",
            "navigateToEntryPosition: chapter=${entryPosition.chapterIndex}, page=${entryPosition.inPageIndex}"
        )
        mainHandler.post {
            // First jump to the chapter, then to the specific page
            // Using non-smooth scrolling (false) for immediate positioning
            webView.evaluateJavascript("""
                if (window.inpagePaginator) {
                    window.inpagePaginator.jumpToChapter(${entryPosition.chapterIndex}, false);
                    window.inpagePaginator.goToPage(${entryPosition.inPageIndex}, false);
                }
            """.trimIndent(), null)
        }
    }
}

private fun String.toBase64(): String {
    return Base64.encodeToString(this.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
}
