package com.rifters.riftedreader.domain.pagination

import android.util.Log
import com.rifters.riftedreader.util.AppLogger
import kotlin.math.ceil

/**
 * Deterministic paginator that groups chapters into windows using a configurable
 * chaptersPerWindow value.
 *
 * This class serves as the single source-of-truth for window computation,
 * providing deterministic grouping that prevents race conditions from mixed
 * pagination code paths.
 *
 * **Sliding Window Pagination Rules:**
 * - Each window contains exactly [DEFAULT_CHAPTERS_PER_WINDOW] (5) chapters
 * - The last window may contain fewer chapters if totalChapters is not evenly divisible
 * - Window count = ceil(totalChapters / chaptersPerWindow)
 * - All window computations are logged to session_log for debugging
 *
 * Thread safety: This class is not thread-safe. External synchronization should
 * be used if accessed from multiple threads.
 */
class SlidingWindowPaginator(
    private var chaptersPerWindow: Int = DEFAULT_CHAPTERS_PER_WINDOW
) {
    private var totalChapters: Int = 0
    private var _windowCount: Int = 0

    /**
     * The computed window count based on totalChapters and chaptersPerWindow.
     */
    val windowCount: Int
        get() = _windowCount

    init {
        require(chaptersPerWindow > 0) { "chaptersPerWindow must be positive, got: $chaptersPerWindow" }
        AppLogger.d(TAG, "[PAGINATION_DEBUG] SlidingWindowPaginator created with chaptersPerWindow=$chaptersPerWindow")
    }

    /**
     * Recompute windows based on the total number of chapters.
     *
     * This updates the internal window map and windowCount. After calling this,
     * windowCount will equal ceil(totalChapters / chaptersPerWindow).
     *
     * @param totalChapters The total number of chapters in the book
     * @return The new window count
     */
    fun recomputeWindows(totalChapters: Int): Int {
        require(totalChapters >= 0) { "totalChapters must be non-negative, got: $totalChapters" }
        
        val previousTotalChapters = this.totalChapters
        val previousWindowCount = this._windowCount
        
        this.totalChapters = totalChapters
        _windowCount = if (totalChapters == 0) {
            0
        } else {
            ceil(totalChapters.toDouble() / chaptersPerWindow).toInt()
        }
        
        // Enhanced logging for window computation
        AppLogger.d(TAG, "[PAGINATION_DEBUG] recomputeWindows: " +
            "totalChapters=$previousTotalChapters->$totalChapters, " +
            "chaptersPerWindow=$chaptersPerWindow, " +
            "windowCount=$previousWindowCount->$_windowCount")
        
        // Validate the computation
        val expectedWindowCount = if (totalChapters == 0) 0 else ceil(totalChapters.toDouble() / chaptersPerWindow).toInt()
        if (_windowCount != expectedWindowCount) {
            AppLogger.e(TAG, "[PAGINATION_DEBUG] WINDOW_COUNT_MISMATCH: computed=$_windowCount, expected=$expectedWindowCount")
        }
        
        // Log the complete window map for debugging
        AppLogger.d(TAG, "[PAGINATION_DEBUG] Window map after recompute: ${debugWindowMap()}")
        
        // Log warning if zero windows computed for non-zero chapters (edge case)
        if (_windowCount == 0 && totalChapters > 0) {
            AppLogger.e(TAG, "[PAGINATION_DEBUG] WARNING: Zero windows computed for $totalChapters chapters!")
        }
        
        return _windowCount
    }

    /**
     * Get the chapter range (first to last inclusive) for a given window index.
     *
     * @param windowIndex The window index (0-based)
     * @return Pair of (firstChapterIndex, lastChapterIndex) inclusive, or null if invalid
     */
    fun getWindowRange(windowIndex: Int): Pair<Int, Int>? {
        if (windowIndex < 0 || windowIndex >= _windowCount || totalChapters == 0) {
            AppLogger.d(TAG, "[PAGINATION_DEBUG] getWindowRange: invalid windowIndex=$windowIndex " +
                "(windowCount=$_windowCount, totalChapters=$totalChapters)")
            return null
        }
        val firstChapter = windowIndex * chaptersPerWindow
        val lastChapter = ((windowIndex + 1) * chaptersPerWindow - 1).coerceAtMost(totalChapters - 1)
        val chaptersInWindow = lastChapter - firstChapter + 1
        
        // Log window range computation
        AppLogger.d(TAG, "[PAGINATION_DEBUG] getWindowRange: windowIndex=$windowIndex -> " +
            "chapters=$firstChapter-$lastChapter ($chaptersInWindow chapters)")
        
        // Warn if window doesn't contain expected number of chapters (except last window)
        val isLastWindow = windowIndex == _windowCount - 1
        if (chaptersInWindow != chaptersPerWindow && !isLastWindow) {
            AppLogger.w(TAG, "[PAGINATION_DEBUG] WARNING: Window $windowIndex has $chaptersInWindow chapters " +
                "(expected $chaptersPerWindow)")
        }
        
        return Pair(firstChapter, lastChapter)
    }

    /**
     * Get the window index that contains the given chapter.
     *
     * @param chapterIndex The chapter index (0-based)
     * @return The window index containing this chapter
     */
    fun getWindowForChapter(chapterIndex: Int): Int {
        require(chapterIndex >= 0) { "chapterIndex must be non-negative, got: $chapterIndex" }
        val windowIndex = chapterIndex / chaptersPerWindow
        AppLogger.d(TAG, "[PAGINATION_DEBUG] getWindowForChapter: chapterIndex=$chapterIndex -> windowIndex=$windowIndex")
        return windowIndex
    }

    /**
     * Update the chaptersPerWindow setting.
     *
     * Note: This does NOT automatically recompute windows. Call recomputeWindows()
     * after changing this value if windows have already been computed.
     *
     * @param newSize The new number of chapters per window
     * @return true if the size changed and recomputation may be needed, false if unchanged
     */
    fun setChaptersPerWindow(newSize: Int): Boolean {
        require(newSize > 0) { "chaptersPerWindow must be positive, got: $newSize" }
        if (chaptersPerWindow == newSize) {
            AppLogger.d(TAG, "[PAGINATION_DEBUG] setChaptersPerWindow: unchanged ($newSize)")
            return false
        }
        AppLogger.d(TAG, "[PAGINATION_DEBUG] setChaptersPerWindow: $chaptersPerWindow -> $newSize (recomputation needed)")
        chaptersPerWindow = newSize
        return true
    }

    /**
     * Get the current chaptersPerWindow value.
     *
     * @return The number of chapters per window
     */
    fun getChaptersPerWindow(): Int = chaptersPerWindow
    
    /**
     * Get the total chapters currently set.
     *
     * @return The total number of chapters
     */
    fun getTotalChapters(): Int = totalChapters

    /**
     * Generate a debug string showing the complete window-to-chapter mapping.
     *
     * @return A string representation of all windows and their chapter ranges
     */
    fun debugWindowMap(): String {
        if (_windowCount == 0) {
            return "No windows (totalChapters=$totalChapters, chaptersPerWindow=$chaptersPerWindow)"
        }
        val sb = StringBuilder()
        sb.append("WindowMap[totalChapters=$totalChapters, chaptersPerWindow=$chaptersPerWindow, windowCount=$_windowCount]: ")
        for (i in 0 until _windowCount) {
            val range = getWindowRangeInternal(i)
            if (range != null) {
                val chaptersInWindow = range.second - range.first + 1
                sb.append("W$i=[${range.first}-${range.second}]($chaptersInWindow ch)")
                if (i < _windowCount - 1) sb.append(", ")
            }
        }
        return sb.toString()
    }
    
    /**
     * Internal method to get window range without logging (used by debugWindowMap).
     */
    private fun getWindowRangeInternal(windowIndex: Int): Pair<Int, Int>? {
        if (windowIndex < 0 || windowIndex >= _windowCount || totalChapters == 0) {
            return null
        }
        val firstChapter = windowIndex * chaptersPerWindow
        val lastChapter = ((windowIndex + 1) * chaptersPerWindow - 1).coerceAtMost(totalChapters - 1)
        return Pair(firstChapter, lastChapter)
    }

    /**
     * Validate that windowCount equals ceil(totalChapters / chaptersPerWindow).
     *
     * @return true if the assertion holds, false otherwise
     */
    fun assertWindowCountValid(): Boolean {
        val expected = if (totalChapters == 0) 0 else ceil(totalChapters.toDouble() / chaptersPerWindow).toInt()
        val valid = _windowCount == expected
        if (!valid) {
            AppLogger.e(TAG, "[PAGINATION_DEBUG] ASSERTION FAILED: windowCount=$_windowCount != expected=$expected " +
                "(totalChapters=$totalChapters, chaptersPerWindow=$chaptersPerWindow)")
        } else {
            AppLogger.d(TAG, "[PAGINATION_DEBUG] Window count assertion passed: windowCount=$_windowCount " +
                "(totalChapters=$totalChapters, chaptersPerWindow=$chaptersPerWindow)")
        }
        return valid
    }
    
    /**
     * Get detailed debug information about the current state.
     * This includes all window mappings and validation status.
     *
     * @return A detailed debug string for session log
     */
    fun getDetailedDebugInfo(): String {
        val sb = StringBuilder()
        sb.appendLine("=== SlidingWindowPaginator Debug Info ===")
        sb.appendLine("Configuration:")
        sb.appendLine("  chaptersPerWindow: $chaptersPerWindow")
        sb.appendLine("  totalChapters: $totalChapters")
        sb.appendLine("  windowCount: $_windowCount")
        sb.appendLine("Validation:")
        sb.appendLine("  windowCount valid: ${assertWindowCountValid()}")
        sb.appendLine("Window Mappings:")
        for (i in 0 until _windowCount) {
            val range = getWindowRangeInternal(i)
            if (range != null) {
                val chaptersInWindow = range.second - range.first + 1
                val isComplete = chaptersInWindow == chaptersPerWindow || i == _windowCount - 1
                sb.appendLine("  Window $i: chapters ${range.first}-${range.second} ($chaptersInWindow chapters) ${if (isComplete) "✓" else "⚠ incomplete"}")
            }
        }
        sb.appendLine("=========================================")
        return sb.toString()
    }

    companion object {
        private const val TAG = "SlidingWindowPaginator"
        const val DEFAULT_CHAPTERS_PER_WINDOW = 5
        /** For chapter-based mode: one window per chapter */
        const val CHAPTER_BASED_CHAPTERS_PER_WINDOW = 1
        /** Default number of windows to keep active for smooth scrolling */
        const val DEFAULT_ACTIVE_WINDOWS = 5
    }
}
