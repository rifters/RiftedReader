package com.rifters.riftedreader.ui.reader

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import com.rifters.riftedreader.pagination.PageSlice
import com.rifters.riftedreader.pagination.SliceMetadata
import com.rifters.riftedreader.util.AppLogger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONObject

data class PageChangedEvent(
    val pageIndex: Int,
    val chapterIndex: Int,
    val charOffset: Int
)

data class ScrollPositionEvent(
    val anchorId: String,
    val scrollY: Int
)

/**
 * JavascriptInterface bridge for flex_paginator.js callbacks.
 *
 * This bridge receives pre-slicing metadata, page changes, and boundary events
 * from the flex paginator implementation. Events are posted to the main thread
 * before forwarding to supplied callbacks.
 */
class FlexPaginatorBridge(
    private val windowIndex: Int,
    private val onSlicingComplete: (windowIndex: Int, metadata: SliceMetadata) -> Unit,
    private val onSlicingError: (windowIndex: Int, message: String) -> Unit,
    private val onPageChanged: (windowIndex: Int, event: PageChangedEvent) -> Unit,
    private val onBoundaryReached: (windowIndex: Int, direction: String) -> Unit
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val _scrollPositionEvents = MutableSharedFlow<ScrollPositionEvent>(
        extraBufferCapacity = 16
    )
    val scrollPositionEvents: SharedFlow<ScrollPositionEvent> = _scrollPositionEvents.asSharedFlow()

    /**
     * Called by flex_paginator.js when slicing is complete.
     *
     * @param json JSON string containing SliceMetadata.
     */
    @JavascriptInterface
    fun onSlicingComplete(json: String) {
        try {
            val metadata = parseSliceMetadata(json)

            AppLogger.d(
                TAG,
                "[SLICING_COMPLETE] windowIndex=$windowIndex, metadataWindow=${metadata.windowIndex}, totalPages=${metadata.totalPages}"
            )

            mainHandler.post {
                onSlicingComplete(windowIndex, metadata)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "[SLICING_COMPLETE] Error parsing JSON: $json", e)
            mainHandler.post {
                onSlicingError(windowIndex, "Failed to parse metadata: ${e.message}")
            }
        }
    }

    /**
     * Called by flex_paginator.js when slicing fails.
     *
     * @param message Error message from JavaScript.
     */
    @JavascriptInterface
    fun onSlicingError(message: String) {
        AppLogger.e(TAG, "[SLICING_ERROR] windowIndex=$windowIndex, message=$message")
        mainHandler.post {
            onSlicingError(windowIndex, message)
        }
    }

    /**
     * Called by flex_paginator.js after visible page navigation.
     */
    @JavascriptInterface
    fun onPageChanged(pageIndex: Int, chapterIndex: Int, charOffset: Int) {
        val event = PageChangedEvent(
            pageIndex = pageIndex,
            chapterIndex = chapterIndex,
            charOffset = charOffset
        )

        AppLogger.d(
            TAG,
            "[PAGE_CHANGED] windowIndex=$windowIndex, pageIndex=$pageIndex, chapterIndex=$chapterIndex, charOffset=$charOffset"
        )

        mainHandler.post {
            onPageChanged(windowIndex, event)
        }
    }

    /**
     * Called by flex_paginator.js when the visible page reaches a window boundary.
     */
    @JavascriptInterface
    fun onBoundaryReached(direction: String) {
        if (direction != "forward" && direction != "backward") {
            AppLogger.w(
                TAG,
                "[BOUNDARY_REACHED] Ignoring unexpected direction=$direction for windowIndex=$windowIndex"
            )
            return
        }

        AppLogger.d(
            TAG,
            "[BOUNDARY_REACHED] windowIndex=$windowIndex, direction=$direction"
        )

        mainHandler.post {
            onBoundaryReached(windowIndex, direction)
        }
    }

    @JavascriptInterface
    fun onScrollPositionChanged(anchorId: String, scrollY: Int) {
        val event = ScrollPositionEvent(anchorId = anchorId, scrollY = scrollY)
        mainHandler.post {
            _scrollPositionEvents.tryEmit(event)
        }
    }

    private fun parseSliceMetadata(json: String): SliceMetadata {
        val obj = JSONObject(json)

        val metadataWindowIndex = obj.getInt("windowIndex")
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
            windowIndex = metadataWindowIndex,
            totalPages = totalPages,
            slices = slices
        )

        if (!metadata.isValid()) {
            throw IllegalStateException(
                "Invalid metadata: totalPages must be > 0 and equal slices.size (totalPages=$totalPages, slices=${slices.size})"
            )
        }

        return metadata
    }

    companion object {
        private const val TAG = "FlexPaginator"
    }
}
