package com.rifters.riftedreader.domain.pagination

import com.rifters.riftedreader.util.AppLogger
import kotlin.math.ceil

/**
 * Pure calculation logic for window computation from chapter lists.
 *
 * This class provides deterministic window calculations without side effects.
 * All methods are stateless and can be safely called from any thread.
 *
 * **Window Computation Rules:**
 * - `windowCount = ceil(chapterCount / chaptersPerWindow)`
 * - Each window contains exactly `chaptersPerWindow` chapters except possibly the last
 * - Empty input (0 chapters) produces 0 windows
 * - Window indices are 0-based
 *
 * **Example with 22 chapters and chaptersPerWindow=5:**
 * - Window 0: chapters 0-4 (5 chapters)
 * - Window 1: chapters 5-9 (5 chapters)
 * - Window 2: chapters 10-14 (5 chapters)
 * - Window 3: chapters 15-19 (5 chapters)
 * - Window 4: chapters 20-21 (2 chapters, final window)
 * - Total: 5 windows
 *
 * @see ChapterIndexProvider for integration with EPUB parsing
 * @see SlidingWindowPaginator for stateful pagination with caching
 */
object WindowCalculator {

    private const val TAG = "WindowCalculator"

    /**
     * Calculate the number of windows needed for a given chapter count.
     *
     * @param chapterCount Total number of chapters (must be >= 0)
     * @param chaptersPerWindow Number of chapters per window (must be > 0)
     * @return Number of windows (0 if chapterCount is 0)
     * @throws IllegalArgumentException if parameters are invalid
     */
    fun calculateWindowCount(chapterCount: Int, chaptersPerWindow: Int): Int {
        require(chapterCount >= 0) { "chapterCount must be non-negative, got: $chapterCount" }
        require(chaptersPerWindow > 0) { "chaptersPerWindow must be positive, got: $chaptersPerWindow" }

        if (chapterCount == 0) return 0
        return ceil(chapterCount.toDouble() / chaptersPerWindow).toInt()
    }

    /**
     * Get the window index containing a given chapter.
     *
     * @param chapterIndex The chapter index (0-based, must be >= 0)
     * @param chaptersPerWindow Number of chapters per window (must be > 0)
     * @return The window index containing this chapter
     * @throws IllegalArgumentException if parameters are invalid
     */
    fun getWindowForChapter(chapterIndex: Int, chaptersPerWindow: Int): Int {
        require(chapterIndex >= 0) { "chapterIndex must be non-negative, got: $chapterIndex" }
        require(chaptersPerWindow > 0) { "chaptersPerWindow must be positive, got: $chaptersPerWindow" }

        return chapterIndex / chaptersPerWindow
    }

    /**
     * Get the chapter range for a given window index.
     *
     * @param windowIndex The window index (0-based)
     * @param totalChapters Total number of chapters in the book
     * @param chaptersPerWindow Number of chapters per window
     * @return Pair of (firstChapterIndex, lastChapterIndex) inclusive, or null if invalid
     */
    fun getWindowRange(windowIndex: Int, totalChapters: Int, chaptersPerWindow: Int): Pair<Int, Int>? {
        require(chaptersPerWindow > 0) { "chaptersPerWindow must be positive, got: $chaptersPerWindow" }

        if (windowIndex < 0 || totalChapters <= 0) return null

        val windowCount = calculateWindowCount(totalChapters, chaptersPerWindow)
        if (windowIndex >= windowCount) return null

        val firstChapter = windowIndex * chaptersPerWindow
        val lastChapter = ((windowIndex + 1) * chaptersPerWindow - 1).coerceAtMost(totalChapters - 1)

        return Pair(firstChapter, lastChapter)
    }

    /**
     * Validate that a computed window count matches expected value.
     *
     * Logs a warning if there's a mismatch, helping detect calculation bugs.
     *
     * @param computed The computed window count
     * @param chapterCount Total chapter count
     * @param chaptersPerWindow Chapters per window setting
     * @return true if valid, false if mismatch detected
     */
    fun validateWindowCount(computed: Int, chapterCount: Int, chaptersPerWindow: Int): Boolean {
        val expected = calculateWindowCount(chapterCount, chaptersPerWindow)
        val valid = computed == expected

        if (!valid) {
            AppLogger.e(TAG, "[Pagination] WINDOW_COUNT_MISMATCH: " +
                "computed=$computed, expected=$expected " +
                "(chapterCount=$chapterCount, chaptersPerWindow=$chaptersPerWindow)")
        }

        return valid
    }

    /**
     * Generate a debug string showing all window-to-chapter mappings.
     *
     * @param totalChapters Total chapters in the book
     * @param chaptersPerWindow Chapters per window
     * @return Human-readable string of all window mappings
     */
    fun debugWindowMap(totalChapters: Int, chaptersPerWindow: Int): String {
        val windowCount = calculateWindowCount(totalChapters, chaptersPerWindow)
        if (windowCount == 0) {
            return "WindowMap[empty] (totalChapters=$totalChapters, chaptersPerWindow=$chaptersPerWindow)"
        }

        val sb = StringBuilder()
        sb.append("WindowMap[totalChapters=$totalChapters, cpw=$chaptersPerWindow, windows=$windowCount]: ")

        for (i in 0 until windowCount) {
            val range = getWindowRange(i, totalChapters, chaptersPerWindow)
            if (range != null) {
                val count = range.second - range.first + 1
                sb.append("W$i=[${range.first}-${range.second}]($count)")
                if (i < windowCount - 1) sb.append(", ")
            }
        }

        return sb.toString()
    }
}
