package com.rifters.riftedreader.pagination

import android.util.Log

/**
 * Deterministic sliding-window paginator for chapter-to-window grouping.
 * 
 * Provides stable, predictable mapping between chapters and windows to prevent
 * race conditions in RecyclerView pagination. Windows are computed once
 * and remain stable until explicitly recomputed.
 * 
 * Example with chaptersPerWindow=5 and 120 total chapters:
 * - Window 0: chapters 0-4
 * - Window 1: chapters 5-9
 * - ...
 * - Window 23: chapters 115-119
 * 
 * @param chaptersPerWindow Number of chapters per window (default: 5)
 */
class SlidingWindowPaginator(
    private var chaptersPerWindow: Int = DEFAULT_CHAPTERS_PER_WINDOW
) {
    
    companion object {
        const val DEFAULT_CHAPTERS_PER_WINDOW = 5
        private const val TAG = "SlidingWindowPaginator"
    }
    
    init {
        require(chaptersPerWindow > 0) { "chaptersPerWindow must be positive, got: $chaptersPerWindow" }
    }
    
    // Cached window count after recompute
    private var cachedWindowCount: Int = 0
    private var cachedTotalChapters: Int = 0
    
    /**
     * Recompute window mappings based on the given total number of chapters.
     * This must be called whenever the total chapter count changes.
     * 
     * @param totalChapters Total number of chapters in the book
     * @return The number of windows computed
     */
    fun recomputeWindows(totalChapters: Int): Int {
        require(totalChapters >= 0) { "totalChapters must be non-negative, got: $totalChapters" }
        
        cachedTotalChapters = totalChapters
        cachedWindowCount = if (totalChapters <= 0) {
            0
        } else {
            (totalChapters + chaptersPerWindow - 1) / chaptersPerWindow
        }
        
        Log.d(TAG, "totalChapters=$totalChapters, chaptersPerWindow=$chaptersPerWindow, windowCount=$cachedWindowCount")
        
        return cachedWindowCount
    }
    
    /**
     * Get the range of chapter indices for a given window.
     * 
     * @param windowIndex The window index (0-based)
     * @return IntRange representing the chapters in this window (inclusive start and end)
     * @throws IllegalArgumentException if windowIndex is invalid
     */
    fun getWindowRange(windowIndex: Int): IntRange {
        require(windowIndex >= 0) { "windowIndex must be non-negative, got: $windowIndex" }
        require(cachedTotalChapters > 0) { "No chapters computed. Call recomputeWindows first." }
        require(windowIndex < cachedWindowCount) { 
            "windowIndex $windowIndex out of bounds, windowCount is $cachedWindowCount" 
        }
        
        val firstChapter = windowIndex * chaptersPerWindow
        val lastChapter = minOf(firstChapter + chaptersPerWindow - 1, cachedTotalChapters - 1)
        
        return firstChapter..lastChapter
    }
    
    /**
     * Get the window index containing the given chapter.
     * 
     * @param chapterIndex The chapter index (0-based)
     * @return The window index containing this chapter
     * @throws IllegalArgumentException if chapterIndex is invalid
     */
    fun getWindowForChapter(chapterIndex: Int): Int {
        require(chapterIndex >= 0) { "chapterIndex must be non-negative, got: $chapterIndex" }
        require(cachedTotalChapters > 0) { "No chapters computed. Call recomputeWindows first." }
        require(chapterIndex < cachedTotalChapters) {
            "chapterIndex $chapterIndex out of bounds, totalChapters is $cachedTotalChapters"
        }
        
        return chapterIndex / chaptersPerWindow
    }
    
    /**
     * Update the number of chapters per window. Requires calling recomputeWindows
     * afterward to update the window count.
     * 
     * @param newChaptersPerWindow The new chapters per window value
     */
    fun setChaptersPerWindow(newChaptersPerWindow: Int) {
        require(newChaptersPerWindow > 0) { "chaptersPerWindow must be positive, got: $newChaptersPerWindow" }
        this.chaptersPerWindow = newChaptersPerWindow
        // Reset cached values to force recomputation
        cachedWindowCount = 0
        cachedTotalChapters = 0
    }
    
    /**
     * Get the current chapters per window value.
     * 
     * @return The number of chapters per window
     */
    fun getChaptersPerWindow(): Int = chaptersPerWindow
    
    /**
     * Get the cached window count from the last recompute.
     * 
     * @return The number of windows
     */
    fun getWindowCount(): Int = cachedWindowCount
    
    /**
     * Get the cached total chapters from the last recompute.
     * 
     * @return The total number of chapters
     */
    fun getTotalChapters(): Int = cachedTotalChapters
    
    /**
     * Generate a debug string showing the current window mappings.
     * Useful for logging and debugging.
     * 
     * @return A debug string showing window-to-chapter mappings
     */
    fun debugWindowMap(): String {
        if (cachedTotalChapters <= 0 || cachedWindowCount <= 0) {
            return "No windows computed (totalChapters=$cachedTotalChapters, windowCount=$cachedWindowCount)"
        }
        
        val sb = StringBuilder()
        sb.append("totalChapters=$cachedTotalChapters, chaptersPerWindow=$chaptersPerWindow, windowCount=$cachedWindowCount\n")
        sb.append("Window mappings:\n")
        
        for (windowIndex in 0 until cachedWindowCount) {
            val range = getWindowRange(windowIndex)
            sb.append("  Window $windowIndex: chapters ${range.first}-${range.last}\n")
        }
        
        return sb.toString()
    }
}
