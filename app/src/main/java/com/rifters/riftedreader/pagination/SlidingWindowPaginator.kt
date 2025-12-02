package com.rifters.riftedreader.pagination

import com.rifters.riftedreader.util.AppLogger

/**
 * Deterministic sliding-window paginator for chapter-to-window grouping.
 * 
 * **Ownership Model:**
 * This class is a **stateless helper with memoization**. It does NOT:
 * - Track which windows are currently active or buffered
 * - Own window lifecycle, loading, or caching
 * - Maintain runtime state about the reading session
 * 
 * It DOES provide:
 * - Deterministic chapter-to-window index mapping (pure math)
 * - Cached window count after `recomputeWindows()` (memoization for efficiency)
 * - Window range calculations
 * 
 * For runtime window management (which windows exist, caching, etc.), use
 * `WindowBufferManager` as the authoritative source.
 * 
 * **Usage:**
 * Provides stable, predictable mapping between chapters and windows to prevent
 * race conditions in RecyclerView pagination. Windows are computed once
 * and remain stable until explicitly recomputed.
 * 
 * **Sliding Window Rules:**
 * - Each window contains exactly [DEFAULT_CHAPTERS_PER_WINDOW] (5) chapters
 * - Last window may have fewer chapters if totalChapters is not evenly divisible
 * - Window count = ceil(totalChapters / chaptersPerWindow)
 * - All computations are logged to session_log for debugging
 * 
 * Example with chaptersPerWindow=5 and 120 total chapters:
 * - Window 0: chapters 0-4 (5 chapters)
 * - Window 1: chapters 5-9 (5 chapters)
 * - ...
 * - Window 23: chapters 115-119 (5 chapters)
 * 
 * @param chaptersPerWindow Number of chapters per window (default: 5)
 * 
 * @see WindowBufferManager For runtime window management (authoritative owner)
 * @see com.rifters.riftedreader.domain.pagination.SlidingWindowManager Similar helper in domain package
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
        AppLogger.d(TAG, "[PAGINATION_DEBUG] SlidingWindowPaginator (pagination pkg) created with chaptersPerWindow=$chaptersPerWindow")
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
        
        val previousWindowCount = cachedWindowCount
        val previousTotalChapters = cachedTotalChapters
        
        cachedTotalChapters = totalChapters
        cachedWindowCount = if (totalChapters <= 0) {
            0
        } else {
            (totalChapters + chaptersPerWindow - 1) / chaptersPerWindow
        }
        
        // Enhanced debug logging
        AppLogger.d(TAG, "[PAGINATION_DEBUG] recomputeWindows: " +
            "totalChapters=$previousTotalChapters->$totalChapters, " +
            "chaptersPerWindow=$chaptersPerWindow, " +
            "windowCount=$previousWindowCount->$cachedWindowCount")
        
        // Validate the computation
        val expectedWindowCount = if (totalChapters <= 0) 0 else 
            kotlin.math.ceil(totalChapters.toDouble() / chaptersPerWindow).toInt()
        if (cachedWindowCount != expectedWindowCount) {
            AppLogger.e(TAG, "[PAGINATION_DEBUG] WINDOW_COUNT_MISMATCH: computed=$cachedWindowCount, expected=$expectedWindowCount")
        }
        
        // Log the complete window map
        AppLogger.d(TAG, "[PAGINATION_DEBUG] Window map: ${debugWindowMap()}")
        
        // Warn on zero windows for non-zero chapters
        if (cachedWindowCount == 0 && totalChapters > 0) {
            AppLogger.e(TAG, "[PAGINATION_DEBUG] WARNING: Zero windows computed for $totalChapters chapters!")
        }
        
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
        val chaptersInWindow = lastChapter - firstChapter + 1
        
        AppLogger.d(TAG, "[PAGINATION_DEBUG] getWindowRange: windowIndex=$windowIndex -> " +
            "chapters=$firstChapter-$lastChapter ($chaptersInWindow chapters)")
        
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
        
        val windowIndex = chapterIndex / chaptersPerWindow
        AppLogger.d(TAG, "[PAGINATION_DEBUG] getWindowForChapter: chapterIndex=$chapterIndex -> windowIndex=$windowIndex")
        return windowIndex
    }
    
    /**
     * Update the number of chapters per window. Requires calling recomputeWindows
     * afterward to update the window count.
     * 
     * @param newChaptersPerWindow The new chapters per window value
     */
    fun setChaptersPerWindow(newChaptersPerWindow: Int) {
        require(newChaptersPerWindow > 0) { "chaptersPerWindow must be positive, got: $newChaptersPerWindow" }
        val oldValue = chaptersPerWindow
        this.chaptersPerWindow = newChaptersPerWindow
        // Reset cached values to force recomputation
        cachedWindowCount = 0
        cachedTotalChapters = 0
        AppLogger.d(TAG, "[PAGINATION_DEBUG] setChaptersPerWindow: $oldValue -> $newChaptersPerWindow (cache cleared)")
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
        sb.append("WindowMap[total=$cachedTotalChapters, perWindow=$chaptersPerWindow, count=$cachedWindowCount]: ")
        
        for (windowIndex in 0 until cachedWindowCount) {
            val firstChapter = windowIndex * chaptersPerWindow
            val lastChapter = minOf(firstChapter + chaptersPerWindow - 1, cachedTotalChapters - 1)
            val chaptersInWindow = lastChapter - firstChapter + 1
            sb.append("W$windowIndex=[${firstChapter}-${lastChapter}]($chaptersInWindow)")
            if (windowIndex < cachedWindowCount - 1) sb.append(", ")
        }
        
        return sb.toString()
    }
    
    /**
     * Get detailed debug information for session log.
     * 
     * @return Detailed debug information
     */
    fun getDetailedDebugInfo(): String {
        val sb = StringBuilder()
        sb.appendLine("=== SlidingWindowPaginator (pagination pkg) Debug Info ===")
        sb.appendLine("Configuration:")
        sb.appendLine("  chaptersPerWindow: $chaptersPerWindow")
        sb.appendLine("  cachedTotalChapters: $cachedTotalChapters")
        sb.appendLine("  cachedWindowCount: $cachedWindowCount")
        sb.appendLine("Window Mappings:")
        if (cachedTotalChapters > 0 && cachedWindowCount > 0) {
            for (windowIndex in 0 until cachedWindowCount) {
                val firstChapter = windowIndex * chaptersPerWindow
                val lastChapter = minOf(firstChapter + chaptersPerWindow - 1, cachedTotalChapters - 1)
                val chaptersInWindow = lastChapter - firstChapter + 1
                val isComplete = chaptersInWindow == chaptersPerWindow || windowIndex == cachedWindowCount - 1
                sb.appendLine("  Window $windowIndex: chapters $firstChapter-$lastChapter ($chaptersInWindow) ${if (isComplete) "✓" else "⚠"}")
            }
        } else {
            sb.appendLine("  (no windows computed)")
        }
        sb.appendLine("=========================================")
        return sb.toString()
    }
}
