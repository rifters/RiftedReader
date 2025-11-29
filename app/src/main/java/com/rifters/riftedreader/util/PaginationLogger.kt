package com.rifters.riftedreader.util

import com.rifters.riftedreader.domain.pagination.PaginationMode
import org.json.JSONArray
import org.json.JSONObject

/**
 * Dedicated logger for sliding window pagination debugging.
 * 
 * Provides specialized logging methods for all pagination-related events,
 * ensuring consistent log formats and comprehensive debugging information.
 * 
 * All logs are written to session_log_*.txt via AppLogger and are filterable
 * using the [PAGINATION_DEBUG] prefix.
 * 
 * **Sliding Window Pagination Rules:**
 * - Each window contains exactly 5 chapters (DEFAULT_CHAPTERS_PER_WINDOW)
 * - 5 windows should be active at any time for smooth scrolling
 * - Window count = ceil(totalChapters / chaptersPerWindow)
 */
object PaginationLogger {
    private const val TAG = "PaginationLogger"
    
    // Sliding window configuration constants (for validation)
    const val EXPECTED_CHAPTERS_PER_WINDOW = 5
    const val EXPECTED_ACTIVE_WINDOWS = 5
    
    /**
     * Log window computation with full details.
     */
    fun logWindowComputation(
        totalChapters: Int,
        chaptersPerWindow: Int,
        computedWindowCount: Int,
        context: String = ""
    ) {
        val expectedWindowCount = if (totalChapters <= 0) 0 else 
            kotlin.math.ceil(totalChapters.toDouble() / chaptersPerWindow).toInt()
        val isValid = computedWindowCount == expectedWindowCount
        
        val message = buildString {
            append("[PAGINATION_DEBUG] Window computation")
            if (context.isNotEmpty()) append(" ($context)")
            append(": totalChapters=$totalChapters, ")
            append("chaptersPerWindow=$chaptersPerWindow, ")
            append("windowCount=$computedWindowCount")
            if (!isValid) {
                append(" [MISMATCH: expected=$expectedWindowCount]")
            }
        }
        
        if (isValid) {
            AppLogger.d(TAG, message)
        } else {
            AppLogger.e(TAG, message)
        }
        
        // Also log to event for JSON tracking
        logPaginationEvent("WINDOW_COMPUTATION", mapOf(
            "totalChapters" to totalChapters,
            "chaptersPerWindow" to chaptersPerWindow,
            "computedWindowCount" to computedWindowCount,
            "expectedWindowCount" to expectedWindowCount,
            "isValid" to isValid,
            "context" to context
        ))
    }
    
    /**
     * Log window range mapping.
     */
    fun logWindowRange(
        windowIndex: Int,
        firstChapter: Int,
        lastChapter: Int,
        totalChapters: Int,
        chaptersPerWindow: Int
    ) {
        val chaptersInWindow = lastChapter - firstChapter + 1
        val totalWindows = if (totalChapters <= 0) 0 else 
            kotlin.math.ceil(totalChapters.toDouble() / chaptersPerWindow).toInt()
        val isLastWindow = windowIndex == totalWindows - 1
        val isValidSize = chaptersInWindow == chaptersPerWindow || isLastWindow
        
        val message = buildString {
            append("[PAGINATION_DEBUG] Window $windowIndex: ")
            append("chapters $firstChapter-$lastChapter ($chaptersInWindow chapters)")
            if (!isValidSize) {
                append(" [WARNING: expected $chaptersPerWindow chapters]")
            }
        }
        
        if (isValidSize) {
            AppLogger.d(TAG, message)
        } else {
            AppLogger.w(TAG, message)
        }
    }
    
    /**
     * Log adapter state changes.
     */
    fun logAdapterStateChange(
        itemCount: Int,
        windowCount: Int,
        paginationMode: PaginationMode,
        chaptersPerWindow: Int,
        activeFragments: Int,
        context: String = ""
    ) {
        val isMatch = itemCount == windowCount
        
        val message = buildString {
            append("[PAGINATION_DEBUG] Adapter state")
            if (context.isNotEmpty()) append(" ($context)")
            append(": itemCount=$itemCount, ")
            append("windowCount=$windowCount, ")
            append("mode=$paginationMode, ")
            append("chaptersPerWindow=$chaptersPerWindow, ")
            append("activeFragments=$activeFragments")
            if (!isMatch) {
                append(" [MISMATCH]")
            }
        }
        
        if (isMatch) {
            AppLogger.d(TAG, message)
        } else {
            AppLogger.e(TAG, message)
        }
    }
    
    /**
     * Log RecyclerView measurement state.
     */
    fun logRecyclerViewMeasurement(
        width: Int,
        height: Int,
        itemCount: Int,
        context: String = ""
    ) {
        val isMeasured = width > 0 && height > 0
        
        val message = buildString {
            append("[PAGINATION_DEBUG] RecyclerView measurement")
            if (context.isNotEmpty()) append(" ($context)")
            append(": ${width}x${height}, ")
            append("itemCount=$itemCount, ")
            append("isMeasured=$isMeasured")
        }
        
        AppLogger.d(TAG, message)
    }
    
    /**
     * Log streaming/eviction events.
     */
    fun logStreamingEvent(
        eventType: String,
        chapterIndex: Int,
        direction: String,
        windowIndex: Int,
        details: String = ""
    ) {
        val message = buildString {
            append("[PAGINATION_DEBUG] Streaming $eventType: ")
            append("chapter=$chapterIndex, ")
            append("direction=$direction, ")
            append("window=$windowIndex")
            if (details.isNotEmpty()) {
                append(" ($details)")
            }
        }
        
        AppLogger.d(TAG, message)
        
        logPaginationEvent("STREAMING_$eventType", mapOf(
            "chapterIndex" to chapterIndex,
            "direction" to direction,
            "windowIndex" to windowIndex,
            "details" to details
        ))
    }
    
    /**
     * Log active window state.
     */
    fun logActiveWindowState(
        currentWindowIndex: Int,
        totalWindows: Int,
        activeWindowIndices: List<Int>,
        loadedChapterIndices: List<Int>
    ) {
        val activeCount = activeWindowIndices.size
        val isExpectedActiveCount = activeCount >= EXPECTED_ACTIVE_WINDOWS.coerceAtMost(totalWindows)
        
        val message = buildString {
            append("[PAGINATION_DEBUG] Active window state: ")
            append("current=$currentWindowIndex, ")
            append("total=$totalWindows, ")
            append("active=$activeCount windows $activeWindowIndices, ")
            append("loadedChapters=${loadedChapterIndices.size} $loadedChapterIndices")
            if (!isExpectedActiveCount && totalWindows >= EXPECTED_ACTIVE_WINDOWS) {
                append(" [WARNING: expected at least $EXPECTED_ACTIVE_WINDOWS active windows]")
            }
        }
        
        if (isExpectedActiveCount || totalWindows < EXPECTED_ACTIVE_WINDOWS) {
            AppLogger.d(TAG, message)
        } else {
            AppLogger.w(TAG, message)
        }
    }
    
    /**
     * Log window navigation event.
     */
    fun logWindowNavigation(
        fromWindow: Int,
        toWindow: Int,
        totalWindows: Int,
        navigationReason: String
    ) {
        val direction = when {
            toWindow > fromWindow -> "FORWARD"
            toWindow < fromWindow -> "BACKWARD"
            else -> "SAME"
        }
        
        val message = buildString {
            append("[PAGINATION_DEBUG] Window navigation: ")
            append("$fromWindow -> $toWindow ($direction), ")
            append("total=$totalWindows, ")
            append("reason=$navigationReason")
        }
        
        AppLogger.d(TAG, message)
        
        logPaginationEvent("WINDOW_NAVIGATION", mapOf(
            "fromWindow" to fromWindow,
            "toWindow" to toWindow,
            "totalWindows" to totalWindows,
            "direction" to direction,
            "reason" to navigationReason
        ))
    }
    
    /**
     * Log complete pagination state snapshot.
     * Call this at key checkpoints for comprehensive debugging.
     */
    fun logPaginationSnapshot(
        totalChapters: Int,
        chaptersPerWindow: Int,
        windowCount: Int,
        currentWindowIndex: Int,
        currentChapterIndex: Int,
        paginationMode: PaginationMode,
        adapterItemCount: Int,
        activeFragmentCount: Int,
        context: String = ""
    ) {
        val expectedWindowCount = if (totalChapters <= 0) 0 else 
            kotlin.math.ceil(totalChapters.toDouble() / chaptersPerWindow).toInt()
        val isWindowCountValid = windowCount == expectedWindowCount
        val isAdapterSynced = adapterItemCount == windowCount
        
        AppLogger.d(TAG, "=== PAGINATION SNAPSHOT${if (context.isNotEmpty()) " ($context)" else ""} ===")
        AppLogger.d(TAG, "[PAGINATION_DEBUG] totalChapters=$totalChapters")
        AppLogger.d(TAG, "[PAGINATION_DEBUG] chaptersPerWindow=$chaptersPerWindow")
        AppLogger.d(TAG, "[PAGINATION_DEBUG] windowCount=$windowCount ${if (!isWindowCountValid) "(EXPECTED: $expectedWindowCount)" else "✓"}")
        AppLogger.d(TAG, "[PAGINATION_DEBUG] currentWindow=$currentWindowIndex")
        AppLogger.d(TAG, "[PAGINATION_DEBUG] currentChapter=$currentChapterIndex")
        AppLogger.d(TAG, "[PAGINATION_DEBUG] paginationMode=$paginationMode")
        AppLogger.d(TAG, "[PAGINATION_DEBUG] adapterItemCount=$adapterItemCount ${if (!isAdapterSynced) "(MISMATCH!)" else "✓"}")
        AppLogger.d(TAG, "[PAGINATION_DEBUG] activeFragments=$activeFragmentCount")
        AppLogger.d(TAG, "=== END SNAPSHOT ===")
        
        // Log as JSON event for parsing
        logPaginationEvent("PAGINATION_SNAPSHOT", mapOf(
            "totalChapters" to totalChapters,
            "chaptersPerWindow" to chaptersPerWindow,
            "windowCount" to windowCount,
            "expectedWindowCount" to expectedWindowCount,
            "isWindowCountValid" to isWindowCountValid,
            "currentWindowIndex" to currentWindowIndex,
            "currentChapterIndex" to currentChapterIndex,
            "paginationMode" to paginationMode.name,
            "adapterItemCount" to adapterItemCount,
            "isAdapterSynced" to isAdapterSynced,
            "activeFragmentCount" to activeFragmentCount,
            "context" to context
        ))
    }
    
    /**
     * Log pagination event as JSON for structured analysis.
     */
    private fun logPaginationEvent(eventType: String, data: Map<String, Any>) {
        try {
            val event = JSONObject().apply {
                put("event", eventType)
                put("timestamp", System.currentTimeMillis())
                data.forEach { (key, value) ->
                    when (value) {
                        is List<*> -> put(key, JSONArray(value))
                        else -> put(key, value)
                    }
                }
            }
            
            AppLogger.event(TAG, "[PAGINATION_EVENT] ${event.toString()}", "pagination")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error logging pagination event: ${e.message}", e)
        }
    }
    
    /**
     * Log boundary condition detection.
     */
    fun logBoundaryCondition(
        boundaryType: String,
        windowIndex: Int,
        chapterIndex: Int,
        inPageIndex: Int,
        totalPagesInChapter: Int,
        action: String
    ) {
        val message = buildString {
            append("[PAGINATION_DEBUG] Boundary condition: ")
            append("type=$boundaryType, ")
            append("window=$windowIndex, ")
            append("chapter=$chapterIndex, ")
            append("inPage=$inPageIndex/$totalPagesInChapter, ")
            append("action=$action")
        }
        
        AppLogger.d(TAG, message)
        
        AppLogger.logBoundaryCondition(boundaryType, windowIndex, chapterIndex, action)
    }
    
    /**
     * Log race condition warning.
     */
    fun logRaceConditionWarning(
        description: String,
        expectedValue: Any,
        actualValue: Any,
        context: String = ""
    ) {
        val message = buildString {
            append("[PAGINATION_DEBUG] RACE_CONDITION_WARNING: ")
            append("$description - ")
            append("expected=$expectedValue, ")
            append("actual=$actualValue")
            if (context.isNotEmpty()) {
                append(" ($context)")
            }
        }
        
        AppLogger.e(TAG, message)
        
        logPaginationEvent("RACE_CONDITION_WARNING", mapOf(
            "description" to description,
            "expectedValue" to expectedValue.toString(),
            "actualValue" to actualValue.toString(),
            "context" to context
        ))
    }
}
