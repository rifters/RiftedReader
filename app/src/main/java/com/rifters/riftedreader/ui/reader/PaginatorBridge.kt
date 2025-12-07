package com.rifters.riftedreader.ui.reader

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import com.rifters.riftedreader.util.AppLogger
import org.json.JSONObject

/**
 * JavascriptInterface bridge for minimal_paginator.js callbacks.
 * 
 * This bridge receives pagination-ready and boundary events from the robust
 * minimal paginator implementation. It ensures stable pagination (totalPages > 0)
 * and provides explicit boundary detection for window transitions.
 * 
 * Callbacks:
 * - onPaginationReady: Called when pagination is stable with totalPages > 0
 * - onBoundary: Called when user reaches window boundaries (NEXT/PREVIOUS)
 * 
 * Events are posted to main thread before forwarding to supplied callbacks.
 */
class PaginatorBridge(
    private val windowIndex: Int,
    private val onPaginationReady: (windowIndex: Int, totalPages: Int) -> Unit,
    private val onBoundary: (windowIndex: Int, direction: String) -> Unit
) {
    
    private val mainHandler = Handler(Looper.getMainLooper())
    
    /**
     * Called by minimal_paginator.js when pagination is ready and stable.
     * The paginator ensures totalPages > 0 before calling this method.
     * 
     * @param json JSON string containing pagination state: { "pageCount": N }
     */
    @JavascriptInterface
    fun onPaginationReady(json: String) {
        try {
            val jsonObj = JSONObject(json)
            val pageCount = jsonObj.optInt("pageCount", -1)
            
            AppLogger.d(
                TAG,
                "[PAGINATION_READY] windowIndex=$windowIndex, pageCount=$pageCount"
            )
            
            if (pageCount > 0) {
                mainHandler.post {
                    onPaginationReady(windowIndex, pageCount)
                }
            } else {
                AppLogger.w(
                    TAG,
                    "[PAGINATION_READY] Ignoring invalid pageCount=$pageCount for windowIndex=$windowIndex"
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "[PAGINATION_READY] Error parsing JSON: $json", e)
        }
    }
    
    /**
     * Called by minimal_paginator.js when user attempts to navigate past window boundaries.
     * Direction can be "NEXT" or "PREVIOUS".
     * 
     * @param json JSON string containing boundary info: { "direction": "NEXT"|"PREVIOUS" }
     */
    @JavascriptInterface
    fun onBoundary(json: String) {
        try {
            val jsonObj = JSONObject(json)
            val direction = jsonObj.optString("direction", "")
            
            AppLogger.d(
                TAG,
                "[BOUNDARY] windowIndex=$windowIndex, direction=$direction"
            )
            
            if (direction.isNotEmpty()) {
                mainHandler.post {
                    onBoundary(windowIndex, direction)
                }
            } else {
                AppLogger.w(
                    TAG,
                    "[BOUNDARY] Ignoring boundary event with empty direction for windowIndex=$windowIndex"
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "[BOUNDARY] Error parsing JSON: $json", e)
        }
    }
    
    companion object {
        private const val TAG = "PaginatorBridge"
    }
}
