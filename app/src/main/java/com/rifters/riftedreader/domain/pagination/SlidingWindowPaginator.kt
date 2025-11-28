package com.rifters.riftedreader.domain.pagination

import android.util.Log
import kotlin.math.ceil

/**
 * Deterministic paginator that groups chapters into windows using a configurable
 * chaptersPerWindow value.
 *
 * This class serves as the single source-of-truth for window computation,
 * providing deterministic grouping that prevents race conditions from mixed
 * pagination code paths.
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
        this.totalChapters = totalChapters
        _windowCount = if (totalChapters == 0) {
            0
        } else {
            ceil(totalChapters.toDouble() / chaptersPerWindow).toInt()
        }
        Log.d(TAG, "recomputeWindows: totalChapters=$totalChapters, chaptersPerWindow=$chaptersPerWindow -> windowCount=$_windowCount")
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
            Log.d(TAG, "getWindowRange: invalid windowIndex=$windowIndex (windowCount=$_windowCount)")
            return null
        }
        val firstChapter = windowIndex * chaptersPerWindow
        val lastChapter = ((windowIndex + 1) * chaptersPerWindow - 1).coerceAtMost(totalChapters - 1)
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
        return chapterIndex / chaptersPerWindow
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
            return false
        }
        Log.d(TAG, "setChaptersPerWindow: $chaptersPerWindow -> $newSize (recomputation may be needed)")
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
            val range = getWindowRange(i)
            if (range != null) {
                sb.append("W$i=[${range.first}-${range.second}]")
                if (i < _windowCount - 1) sb.append(", ")
            }
        }
        return sb.toString()
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
            Log.e(TAG, "ASSERTION FAILED: windowCount=$_windowCount != expected=$expected (totalChapters=$totalChapters, chaptersPerWindow=$chaptersPerWindow)")
        }
        return valid
    }

    companion object {
        private const val TAG = "SlidingWindowPaginator"
        const val DEFAULT_CHAPTERS_PER_WINDOW = 5
        /** For chapter-based mode: one window per chapter */
        const val CHAPTER_BASED_CHAPTERS_PER_WINDOW = 1
    }
}
