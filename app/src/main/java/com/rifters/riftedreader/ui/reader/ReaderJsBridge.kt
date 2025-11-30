package com.rifters.riftedreader.ui.reader

import android.webkit.JavascriptInterface
import com.rifters.riftedreader.util.AppLogger

/**
 * JavaScript bridge for reader pagination callbacks from inpage_paginator.js.
 * 
 * This bridge handles communication from the WebView's JavaScript paginator
 * back to Android for events like:
 * - Pagination ready (page count available)
 * - Page changes
 * - Boundary reached (start/end of window)
 * - Streaming requests (load adjacent chapters)
 * - Segment eviction
 * - Diagnostics
 * 
 * The bridge is designed to work with the pre-wrapped HTML pipeline where
 * sections with data-chapter-index attributes are detected directly by
 * the paginator without needing to move body children into a wrapper.
 * 
 * Padding Injection Pattern:
 * After onPaginationReady fires with valid page count, the callback can
 * trigger padding injection on the paginator content wrapper to ensure
 * columns remain aligned while textual content has proper margins.
 * 
 * @param callback The callback interface to notify of events
 */
class ReaderJsBridge(
    private val callback: Callback
) {
    
    companion object {
        private const val TAG = "ReaderJsBridge"
        
        /** JavaScript interface name exposed to WebView */
        const val JS_INTERFACE_NAME = "AndroidBridge"
    }
    
    /**
     * Callback interface for pagination events.
     */
    interface Callback {
        /**
         * Called when pagination is complete and total page count is known.
         * This is the trigger for padding injection on the content wrapper.
         * 
         * @param totalPages The total number of pages in the window
         */
        fun onPaginationReady(totalPages: Int)
        
        /**
         * Called when user navigates to a different page within the window.
         * 
         * @param newPage The new page index (0-based)
         */
        fun onPageChanged(newPage: Int)
        
        /**
         * Called when user reaches a boundary (first or last page).
         * 
         * @param direction "NEXT" or "PREVIOUS"
         * @param boundaryPage The current page at boundary
         * @param totalPages Total pages in window
         */
        fun onBoundaryReached(direction: String, boundaryPage: Int, totalPages: Int)
        
        /**
         * Called when paginator requests streaming of adjacent chapter content.
         * 
         * @param direction "NEXT" or "PREVIOUS"
         * @param boundaryPage The current page at boundary
         * @param totalPages Total pages in window
         */
        fun onStreamingRequest(direction: String, boundaryPage: Int, totalPages: Int)
        
        /**
         * Called when a chapter segment is evicted from the sliding window.
         * 
         * @param chapterIndex The evicted chapter index
         */
        fun onSegmentEvicted(chapterIndex: Int)
        
        /**
         * Called after a TOC jump completes.
         * 
         * @param chapterIndex The chapter jumped to
         * @param pageIndex The page within the chapter
         */
        fun onChapterJumped(chapterIndex: Int, pageIndex: Int)
        
        /**
         * Called when a chapter is not loaded for TOC navigation.
         * 
         * @param chapterIndex The requested chapter index
         */
        fun onChapterNotLoaded(chapterIndex: Int)
        
        /**
         * Called when window is finalized and locked for reading.
         * 
         * @param pageCount The final page count
         */
        fun onWindowFinalized(pageCount: Int)
        
        /**
         * Called when window load completes successfully.
         * 
         * @param payload JSON string with windowIndex, pageCount, chapterBoundaries
         */
        fun onWindowLoaded(payload: String)
        
        /**
         * Called when window load fails.
         * 
         * @param payload JSON string with windowIndex, errorMessage, errorType
         */
        fun onWindowLoadError(payload: String)
        
        /**
         * Called with reconfigure completion info.
         * 
         * @param payload JSON string with fontSize, pageCount
         */
        fun onReconfigureComplete(payload: String)
        
        /**
         * Called with diagnostics log from paginator.
         * 
         * @param context The event context
         * @param payload JSON payload
         */
        fun onDiagnosticsLog(context: String, payload: String)
        
        /**
         * Called with pagination state log.
         * 
         * @param payload JSON string with pagination state
         */
        fun onPaginationStateLog(payload: String)
        
        /**
         * Called when images fail to load.
         * 
         * @param payload JSON string with failed image info
         */
        fun onImagesFailedToLoad(payload: String)
    }
    
    // ========================================================================
    // Core Pagination Events
    // ========================================================================
    
    @JavascriptInterface
    fun onPaginationReady(totalPages: Int) {
        AppLogger.d(TAG, "[JS_CALLBACK] onPaginationReady: totalPages=$totalPages")
        callback.onPaginationReady(totalPages)
    }
    
    @JavascriptInterface
    fun onPageChanged(newPage: Int) {
        AppLogger.d(TAG, "[JS_CALLBACK] onPageChanged: newPage=$newPage")
        callback.onPageChanged(newPage)
    }
    
    @JavascriptInterface
    fun onBoundaryReached(direction: String?, boundaryPage: Int, totalPages: Int) {
        val safeDirection = direction ?: "UNKNOWN"
        AppLogger.d(TAG, "[JS_CALLBACK] onBoundaryReached: direction=$safeDirection, boundaryPage=$boundaryPage, totalPages=$totalPages")
        callback.onBoundaryReached(safeDirection, boundaryPage, totalPages)
    }
    
    @JavascriptInterface
    fun onStreamingRequest(direction: String?, boundaryPage: Int, totalPages: Int) {
        val safeDirection = direction ?: "UNKNOWN"
        AppLogger.d(TAG, "[JS_CALLBACK] onStreamingRequest: direction=$safeDirection, boundaryPage=$boundaryPage, totalPages=$totalPages")
        callback.onStreamingRequest(safeDirection, boundaryPage, totalPages)
    }
    
    @JavascriptInterface
    fun onSegmentEvicted(chapterIndex: Int) {
        AppLogger.d(TAG, "[JS_CALLBACK] onSegmentEvicted: chapterIndex=$chapterIndex")
        callback.onSegmentEvicted(chapterIndex)
    }
    
    // ========================================================================
    // TOC Navigation Events
    // ========================================================================
    
    @JavascriptInterface
    fun onChapterJumped(chapterIndex: Int, pageIndex: Int) {
        AppLogger.d(TAG, "[JS_CALLBACK] onChapterJumped: chapterIndex=$chapterIndex, pageIndex=$pageIndex")
        callback.onChapterJumped(chapterIndex, pageIndex)
    }
    
    @JavascriptInterface
    fun onChapterNotLoaded(chapterIndex: Int) {
        AppLogger.d(TAG, "[JS_CALLBACK] onChapterNotLoaded: chapterIndex=$chapterIndex")
        callback.onChapterNotLoaded(chapterIndex)
    }
    
    // ========================================================================
    // Window Lifecycle Events
    // ========================================================================
    
    @JavascriptInterface
    fun onWindowFinalized(pageCount: Int) {
        AppLogger.d(TAG, "[JS_CALLBACK] onWindowFinalized: pageCount=$pageCount")
        callback.onWindowFinalized(pageCount)
    }
    
    @JavascriptInterface
    fun onWindowLoaded(payload: String?) {
        val safePayload = payload ?: "{}"
        AppLogger.d(TAG, "[JS_CALLBACK] onWindowLoaded: payload=$safePayload")
        callback.onWindowLoaded(safePayload)
    }
    
    @JavascriptInterface
    fun onWindowLoadError(payload: String?) {
        val safePayload = payload ?: "{}"
        AppLogger.e(TAG, "[JS_CALLBACK] onWindowLoadError: payload=$safePayload", null)
        callback.onWindowLoadError(safePayload)
    }
    
    @JavascriptInterface
    fun onReconfigureComplete(payload: String?) {
        val safePayload = payload ?: "{}"
        AppLogger.d(TAG, "[JS_CALLBACK] onReconfigureComplete: payload=$safePayload")
        callback.onReconfigureComplete(safePayload)
    }
    
    // ========================================================================
    // Diagnostics Events
    // ========================================================================
    
    @JavascriptInterface
    fun onDiagnosticsLog(context: String?, payload: String?) {
        val safeContext = context ?: "unknown"
        val safePayload = payload ?: "{}"
        AppLogger.d(TAG, "[JS_DIAG] $safeContext: $safePayload")
        callback.onDiagnosticsLog(safeContext, safePayload)
    }
    
    @JavascriptInterface
    fun onPaginationStateLog(payload: String?) {
        val safePayload = payload ?: "{}"
        AppLogger.d(TAG, "[JS_STATE] $safePayload")
        callback.onPaginationStateLog(safePayload)
    }
    
    @JavascriptInterface
    fun onImagesFailedToLoad(payload: String?) {
        val safePayload = payload ?: "[]"
        AppLogger.w(TAG, "[JS_IMAGES_FAILED] $safePayload")
        callback.onImagesFailedToLoad(safePayload)
    }
}
