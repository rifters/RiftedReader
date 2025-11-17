package com.rifters.riftedreader.ui.reader

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * WebViewPaginatorBridge - Kotlin utility for interacting with the in-page paginator JavaScript API
 * 
 * Provides suspend functions to safely evaluate JavaScript code in the WebView and return numeric results.
 * All WebView operations are guaranteed to run on the UI thread.
 */
object WebViewPaginatorBridge {
    
    private val mainHandler = Handler(Looper.getMainLooper())
    
    /**
     * Evaluate JavaScript code in the WebView and return an Int result
     * This is a suspend function that safely executes on the UI thread
     * 
     * @param webView The WebView instance to evaluate JavaScript in
     * @param script The JavaScript code to evaluate
     * @return The result as an Int, or null if evaluation failed
     */
    suspend fun evaluateJsForInt(webView: WebView, script: String): Int? = suspendCancellableCoroutine { continuation ->
        // Ensure we're on the UI thread
        if (Looper.myLooper() == Looper.getMainLooper()) {
            evaluateJsForIntOnUiThread(webView, script, continuation)
        } else {
            mainHandler.post {
                evaluateJsForIntOnUiThread(webView, script, continuation)
            }
        }
    }
    
    private fun evaluateJsForIntOnUiThread(
        webView: WebView,
        script: String,
        continuation: kotlinx.coroutines.CancellableContinuation<Int?>
    ) {
        try {
            webView.evaluateJavascript(script) { result ->
                try {
                    val intValue = when {
                        result == null || result == "null" -> null
                        result.startsWith("\"") && result.endsWith("\"") -> {
                            // Remove quotes if present
                            result.substring(1, result.length - 1).toIntOrNull()
                        }
                        else -> result.toIntOrNull()
                    }
                    if (continuation.isActive) {
                        continuation.resume(intValue)
                    }
                } catch (e: Exception) {
                    if (continuation.isActive) {
                        continuation.resume(null)
                    }
                }
            }
        } catch (e: Exception) {
            if (continuation.isActive) {
                continuation.resume(null)
            }
        }
    }
    
    /**
     * Evaluate JavaScript code in the WebView without expecting a return value
     * Runs on the UI thread
     * 
     * @param webView The WebView instance
     * @param script The JavaScript code to execute
     */
    fun evaluateJs(webView: WebView, script: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            webView.evaluateJavascript(script, null)
        } else {
            mainHandler.post {
                webView.evaluateJavascript(script, null)
            }
        }
    }
    
    /**
     * Set the font size in the paginator
     * 
     * @param webView The WebView instance
     * @param sizePx Font size in pixels
     */
    fun setFontSize(webView: WebView, sizePx: Float) {
        evaluateJs(webView, "inpagePaginator.setFontSize($sizePx);")
    }
    
    /**
     * Get the total number of pages
     * 
     * @param webView The WebView instance
     * @return The page count, or null if the operation failed
     */
    suspend fun getPageCount(webView: WebView): Int? {
        return evaluateJsForInt(webView, "inpagePaginator.getPageCount();")
    }
    
    /**
     * Navigate to a specific page
     * 
     * @param webView The WebView instance
     * @param pageIndex Zero-based page index
     * @param smooth Whether to use smooth scrolling
     */
    fun goToPage(webView: WebView, pageIndex: Int, smooth: Boolean = true) {
        evaluateJs(webView, "inpagePaginator.goToPage($pageIndex, $smooth);")
    }
    
    /**
     * Navigate to the next page
     * 
     * @param webView The WebView instance
     * @return The new page index, or null if the operation failed
     */
    suspend fun nextPage(webView: WebView): Int? {
        return evaluateJsForInt(webView, "inpagePaginator.nextPage();")
    }
    
    /**
     * Navigate to the previous page
     * 
     * @param webView The WebView instance
     * @return The new page index, or null if the operation failed
     */
    suspend fun prevPage(webView: WebView): Int? {
        return evaluateJsForInt(webView, "inpagePaginator.prevPage();")
    }
    
    /**
     * Get the current page index
     * 
     * @param webView The WebView instance
     * @return The current page index, or null if the operation failed
     */
    suspend fun getCurrentPage(webView: WebView): Int? {
        return evaluateJsForInt(webView, "inpagePaginator.getCurrentPage();")
    }
    
    /**
     * Get the page index containing an element matching the given selector
     * 
     * @param webView The WebView instance
     * @param selector CSS selector for the target element
     * @return The page index, or null if not found or operation failed
     */
    suspend fun getPageForSelector(webView: WebView, selector: String): Int? {
        // Escape the selector string for JavaScript
        val escapedSelector = selector.replace("\\", "\\\\").replace("\"", "\\\"")
        return evaluateJsForInt(webView, "inpagePaginator.getPageForSelector(\"$escapedSelector\");")
    }
    
    /**
     * Reflow the content (e.g., after orientation change)
     * 
     * @param webView The WebView instance
     */
    fun reflow(webView: WebView) {
        evaluateJs(webView, "inpagePaginator.reflow();")
    }
}
