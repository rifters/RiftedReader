package com.rifters.riftedreader.pagination

import android.util.Log
import kotlin.math.ceil

/**
 * Deterministic sliding-window paginator for grouping chapters into windows.
 *
 * This class provides a deterministic mapping between chapters and windows,
 * ensuring consistent behavior for ViewPager2 pagination.
 *
 * @param initialChaptersPerWindow Number of chapters per window (default: 5)
 */
class SlidingWindowPaginator(initialChaptersPerWindow: Int = DEFAULT_CHAPTERS_PER_WINDOW) {

    companion object {
        const val DEFAULT_CHAPTERS_PER_WINDOW = 5
        private const val TAG = "SlidingWindowPaginator"
    }

    private var _chaptersPerWindow: Int = initialChaptersPerWindow.coerceAtLeast(1)
    private var _totalChapters: Int = 0
    private var _windowCount: Int = 0

    /**
     * Current number of chapters per window.
     */
    val chaptersPerWindow: Int get() = _chaptersPerWindow

    /**
     * Current total number of chapters.
     */
    val totalChapters: Int get() = _totalChapters

    /**
     * Current window count.
     */
    val windowCount: Int get() = _windowCount

    /**
     * Recompute window structure based on total chapters.
     *
     * @param totalChapters Total number of chapters in the book
     */
    fun recomputeWindows(totalChapters: Int) {
        _totalChapters = totalChapters.coerceAtLeast(0)
        _windowCount = if (_totalChapters == 0) {
            0
        } else {
            ceil(_totalChapters.toDouble() / _chaptersPerWindow).toInt()
        }
        Log.d(TAG, "recomputeWindows: totalChapters=$_totalChapters, chaptersPerWindow=$_chaptersPerWindow, windowCount=$_windowCount")
    }

    /**
     * Get the chapter range for a given window index.
     *
     * @param windowIndex The window index (0-based)
     * @return IntRange of chapter indices in this window, or empty range if invalid
     */
    fun getWindowRange(windowIndex: Int): IntRange {
        if (windowIndex < 0 || windowIndex >= _windowCount || _totalChapters == 0) {
            Log.d(TAG, "getWindowRange: invalid windowIndex=$windowIndex (windowCount=$_windowCount)")
            return IntRange.EMPTY
        }
        val firstChapter = windowIndex * _chaptersPerWindow
        val lastChapter = ((windowIndex + 1) * _chaptersPerWindow - 1).coerceAtMost(_totalChapters - 1)
        return firstChapter..lastChapter
    }

    /**
     * Get the window index for a given chapter.
     *
     * @param chapterIndex The chapter index (0-based)
     * @return The window index containing this chapter, or -1 if invalid
     */
    fun getWindowForChapter(chapterIndex: Int): Int {
        if (chapterIndex < 0 || chapterIndex >= _totalChapters) {
            Log.d(TAG, "getWindowForChapter: invalid chapterIndex=$chapterIndex (totalChapters=$_totalChapters)")
            return -1
        }
        return chapterIndex / _chaptersPerWindow
    }

    /**
     * Set a new chapters per window value and recompute windows.
     *
     * @param newSize The new number of chapters per window (must be >= 1)
     */
    fun setChaptersPerWindow(newSize: Int) {
        val safeSize = newSize.coerceAtLeast(1)
        if (safeSize != _chaptersPerWindow) {
            Log.d(TAG, "setChaptersPerWindow: changing from $_chaptersPerWindow to $safeSize")
            _chaptersPerWindow = safeSize
            recomputeWindows(_totalChapters)
        }
    }

    /**
     * Generate a debug string showing the current window-to-chapter mapping.
     *
     * @return A multi-line string showing all window mappings
     */
    fun debugWindowMap(): String {
        if (_windowCount == 0) {
            return "SlidingWindowPaginator: empty (totalChapters=$_totalChapters)"
        }
        val sb = StringBuilder()
        sb.append("SlidingWindowPaginator: totalChapters=$_totalChapters, chaptersPerWindow=$_chaptersPerWindow, windowCount=$_windowCount\n")
        for (i in 0 until _windowCount) {
            val range = getWindowRange(i)
            sb.append("  Window $i: chapters ${range.first}-${range.last}\n")
        }
        return sb.toString().trimEnd()
    }
}
