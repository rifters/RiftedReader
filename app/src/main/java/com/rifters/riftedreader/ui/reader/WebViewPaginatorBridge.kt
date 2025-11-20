package com.rifters.riftedreader.ui.reader

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import com.rifters.riftedreader.util.AppLogger
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Bridge for communicating with the in-page paginator JavaScript API.
 * 
 * Provides convenient Kotlin functions to interact with the inpagePaginator
 * JavaScript object injected into WebView content.
 */
object WebViewPaginatorBridge {
    
    private val mainHandler = Handler(Looper.getMainLooper())
    
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
     * Trigger a reflow of the paginated content.
     * Useful after dynamic content changes.
     * This method does not suspend - it fires and forgets.
     * Silently fails if paginator is not ready.
     * 
     * @param webView The WebView containing the paginated content
     */
    fun reflow(webView: WebView) {
        AppLogger.d("WebViewPaginatorBridge", "reflow: triggering content reflow")
        mainHandler.post {
            webView.evaluateJavascript(
                "if (window.inpagePaginator) { window.inpagePaginator.reflow(); }",
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
}
