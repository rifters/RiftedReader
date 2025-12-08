package com.rifters.riftedreader.pagination

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.rifters.riftedreader.util.AppLogger
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Hidden WebView for offscreen pre-slicing of window HTML.
 * 
 * **Architecture**:
 * 1. This WebView is invisible (1x1 pixel, GONE visibility)
 * 2. It loads wrapped HTML from FlexPaginator
 * 3. flex_paginator.js performs node-walking slicing algorithm
 * 4. JavaScript calls AndroidBridge.onSlicingComplete(metadataJson)
 * 5. This class parses the JSON and returns SliceMetadata
 * 
 * **Performance**:
 * - Slicing happens in background thread (WebView's rendering thread)
 * - Target: <500ms per window
 * - Memory: <10MB per window
 * - WebView pooling recommended for production (not implemented here)
 * 
 * @param context Android context for WebView creation
 */
class OffscreenSlicingWebView(context: Context) {
    
    companion object {
        private const val TAG = "OffscreenSlicingWebView"
        private const val SLICING_TIMEOUT_MS = 10000L // 10 seconds
    }
    
    private val webView: WebView = WebView(context).apply {
        // Make WebView invisible
        layoutParams = android.view.ViewGroup.LayoutParams(1, 1)
        visibility = View.GONE
        
        // Enable JavaScript (required for flex_paginator.js)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        
        // Disable unnecessary features for performance
        settings.loadWithOverviewMode = false
        settings.useWideViewPort = false
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.setSupportZoom(false)
        
        // Add JavaScript interface for receiving slice metadata
        addJavascriptInterface(SlicingBridge(), "AndroidBridge")
    }
    
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentCallback: SlicingCallback? = null
    
    /**
     * Slice a window HTML document into pages and return metadata.
     * 
     * This is a suspending function that loads the HTML into the WebView,
     * waits for flex_paginator.js to complete slicing, and returns the metadata.
     * 
     * @param wrappedHtml The HTML from FlexPaginator.assembleWindow()
     * @param windowIndex The window index (for metadata validation)
     * @return SliceMetadata with page slices and character offsets
     * @throws SlicingException if slicing fails or times out
     */
    suspend fun sliceWindow(wrappedHtml: String, windowIndex: Int): SliceMetadata {
        return suspendCancellableCoroutine { continuation ->
            AppLogger.d(TAG, "[SLICE] Starting slicing for window $windowIndex")
            
            // Set up callback
            val callback = SlicingCallback(
                onSuccess = { metadata ->
                    AppLogger.d(TAG, "[SLICE] Slicing complete for window $windowIndex: " +
                        "${metadata.totalPages} pages")
                    continuation.resume(metadata)
                },
                onError = { error ->
                    AppLogger.e(TAG, "[SLICE] Slicing failed for window $windowIndex: $error")
                    continuation.resumeWithException(SlicingException(error))
                }
            )
            currentCallback = callback
            
            // Set timeout
            val timeoutRunnable = Runnable {
                val cb = currentCallback
                if (cb != null && !cb.isCompleted) {
                    cb.onError("Slicing timeout after ${SLICING_TIMEOUT_MS}ms")
                    currentCallback = null
                }
            }
            mainHandler.postDelayed(timeoutRunnable, SLICING_TIMEOUT_MS)
            
            // Cancel timeout on coroutine cancellation
            continuation.invokeOnCancellation {
                mainHandler.removeCallbacks(timeoutRunnable)
                currentCallback = null
            }
            
            // Load HTML into WebView
            // The flex_paginator.js will be injected via <script> tag in the HTML
            mainHandler.post {
                try {
                    val htmlWithScript = injectFlexPaginatorScript(wrappedHtml, windowIndex)
                    webView.loadDataWithBaseURL(
                        "file:///android_asset/",
                        htmlWithScript,
                        "text/html",
                        "UTF-8",
                        null
                    )
                } catch (e: Exception) {
                    AppLogger.e(TAG, "[SLICE] Error loading HTML", e)
                    callback.onError("Failed to load HTML: ${e.message}")
                    currentCallback = null
                    mainHandler.removeCallbacks(timeoutRunnable)
                }
            }
        }
    }
    
    /**
     * Inject flex_paginator.js script into the HTML document.
     * 
     * The script will:
     * 1. Parse the <section> elements
     * 2. Perform node-walking slicing
     * 3. Call AndroidBridge.onSlicingComplete(metadataJson)
     */
    private fun injectFlexPaginatorScript(wrappedHtml: String, windowIndex: Int): String {
        return buildString {
            append("<!DOCTYPE html>\n")
            append("<html>\n")
            append("<head>\n")
            append("  <meta charset=\"UTF-8\">\n")
            append("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n")
            append("  <style>\n")
            append("    * { margin: 0; padding: 0; box-sizing: border-box; }\n")
            append("    body { font-family: sans-serif; font-size: 16px; line-height: 1.6; }\n")
            append("    section { margin-bottom: 1em; }\n")
            append("  </style>\n")
            append("</head>\n")
            append("<body>\n")
            append(wrappedHtml)
            append("\n")
            append("  <script>\n")
            append("    // Window index for metadata validation\n")
            append("    window.FLEX_WINDOW_INDEX = $windowIndex;\n")
            append("  </script>\n")
            append("  <script src=\"file:///android_asset/flex_paginator.js\"></script>\n")
            append("</body>\n")
            append("</html>\n")
        }
    }
    
    /**
     * Clean up WebView resources.
     * 
     * Call this when the WebView is no longer needed.
     */
    fun destroy() {
        mainHandler.post {
            currentCallback = null
            webView.destroy()
        }
    }
    
    /**
     * JavaScript interface for receiving slice metadata from flex_paginator.js.
     */
    private inner class SlicingBridge {
        
        /**
         * Called by flex_paginator.js when slicing is complete.
         * 
         * Expected JSON format:
         * ```json
         * {
         *   "windowIndex": 5,
         *   "totalPages": 34,
         *   "slices": [
         *     { "page": 0, "chapter": 25, "startChar": 0, "endChar": 523, "heightPx": 600 },
         *     { "page": 1, "chapter": 25, "startChar": 523, "endChar": 1045, "heightPx": 600 },
         *     ...
         *   ]
         * }
         * ```
         * 
         * @param metadataJson JSON string with slice metadata
         */
        @JavascriptInterface
        fun onSlicingComplete(metadataJson: String) {
            mainHandler.post {
                val callback = currentCallback ?: return@post
                if (callback.isCompleted) return@post
                
                AppLogger.d(TAG, "[BRIDGE] onSlicingComplete called, parsing JSON...")
                
                try {
                    val metadata = parseSliceMetadata(metadataJson)
                    callback.onSuccess(metadata)
                    currentCallback = null
                } catch (e: Exception) {
                    AppLogger.e(TAG, "[BRIDGE] Failed to parse slice metadata", e)
                    callback.onError("Failed to parse metadata: ${e.message}")
                    currentCallback = null
                }
            }
        }
        
        /**
         * Called by flex_paginator.js if slicing fails.
         * 
         * @param errorMessage Error message from JavaScript
         */
        @JavascriptInterface
        fun onSlicingError(errorMessage: String) {
            mainHandler.post {
                val callback = currentCallback ?: return@post
                if (callback.isCompleted) return@post
                
                AppLogger.e(TAG, "[BRIDGE] onSlicingError: $errorMessage")
                callback.onError(errorMessage)
                currentCallback = null
            }
        }
    }
    
    /**
     * Parse slice metadata JSON from JavaScript.
     * 
     * @param json JSON string from flex_paginator.js
     * @return Parsed SliceMetadata
     * @throws Exception if JSON is invalid
     */
    private fun parseSliceMetadata(json: String): SliceMetadata {
        val obj = JSONObject(json)
        
        val windowIndex = obj.getInt("windowIndex")
        val totalPages = obj.getInt("totalPages")
        val slicesArray = obj.getJSONArray("slices")
        
        val slices = mutableListOf<PageSlice>()
        for (i in 0 until slicesArray.length()) {
            val sliceObj = slicesArray.getJSONObject(i)
            slices.add(
                PageSlice(
                    page = sliceObj.getInt("page"),
                    chapter = sliceObj.getInt("chapter"),
                    startChar = sliceObj.getInt("startChar"),
                    endChar = sliceObj.getInt("endChar"),
                    heightPx = sliceObj.getInt("heightPx")
                )
            )
        }
        
        val metadata = SliceMetadata(
            windowIndex = windowIndex,
            totalPages = totalPages,
            slices = slices
        )
        
        // Validate metadata
        if (!metadata.isValid()) {
            throw IllegalStateException("Invalid metadata: totalPages=$totalPages, slices=${slices.size}")
        }
        
        return metadata
    }
    
    /**
     * Callback for slicing completion.
     */
    private class SlicingCallback(
        private val onSuccess: (SliceMetadata) -> Unit,
        private val onError: (String) -> Unit
    ) {
        @Volatile
        var isCompleted = false
            private set
        
        fun onSuccess(metadata: SliceMetadata) {
            if (!isCompleted) {
                isCompleted = true
                onSuccess.invoke(metadata)
            }
        }
        
        fun onError(error: String) {
            if (!isCompleted) {
                isCompleted = true
                onError.invoke(error)
            }
        }
    }
}

/**
 * Exception thrown when slicing fails.
 */
class SlicingException(message: String) : Exception(message)
