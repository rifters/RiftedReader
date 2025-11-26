package com.rifters.riftedreader.domain.pagination

/**
 * Manages chapter-to-window index mapping for sliding-window pagination.
 * 
 * A sliding window groups multiple chapters together for efficient memory management
 * and smooth navigation. For example, with windowSize=5:
 * - Window 0: chapters 0-4
 * - Window 1: chapters 5-9
 * - Window 2: chapters 10-14
 * 
 * @param windowSize Number of chapters per window (default: 5)
 */
class SlidingWindowManager(private val windowSize: Int = DEFAULT_WINDOW_SIZE) {
    
    init {
        require(windowSize > 0) { "Window size must be positive, got: $windowSize" }
    }
    
    /**
     * Calculate which window contains the given chapter.
     * 
     * @param chapterIndex The chapter index (0-based)
     * @return The window index containing this chapter
     */
    fun windowForChapter(chapterIndex: Int): Int {
        require(chapterIndex >= 0) { "Chapter index must be non-negative, got: $chapterIndex" }
        return chapterIndex / windowSize
    }
    
    /**
     * Get the first chapter index in a given window.
     * 
     * @param windowIndex The window index (0-based)
     * @return The first chapter index in this window
     */
    fun firstChapterInWindow(windowIndex: Int): Int {
        require(windowIndex >= 0) { "Window index must be non-negative, got: $windowIndex" }
        return windowIndex * windowSize
    }
    
    /**
     * Get the last chapter index in a given window, respecting the total chapter count.
     * 
     * @param windowIndex The window index (0-based)
     * @param totalChapters Total number of chapters in the book
     * @return The last chapter index in this window (inclusive)
     */
    fun lastChapterInWindow(windowIndex: Int, totalChapters: Int): Int {
        require(windowIndex >= 0) { "Window index must be non-negative, got: $windowIndex" }
        require(totalChapters > 0) { "Total chapters must be positive, got: $totalChapters" }
        
        val firstChapter = firstChapterInWindow(windowIndex)
        val lastPossible = firstChapter + windowSize - 1
        return lastPossible.coerceAtMost(totalChapters - 1)
    }
    
    /**
     * Get all chapter indices in a given window.
     * 
     * @param windowIndex The window index (0-based)
     * @param totalChapters Total number of chapters in the book
     * @return List of chapter indices in this window
     */
    fun chaptersInWindow(windowIndex: Int, totalChapters: Int): List<Int> {
        val first = firstChapterInWindow(windowIndex)
        val last = lastChapterInWindow(windowIndex, totalChapters)
        return (first..last).toList()
    }
    
    /**
     * Calculate the total number of windows needed for the given chapter count.
     * 
     * @param totalChapters Total number of chapters in the book
     * @return The number of windows needed
     */
    fun totalWindows(totalChapters: Int): Int {
        require(totalChapters > 0) { "Total chapters must be positive, got: $totalChapters" }
        return (totalChapters + windowSize - 1) / windowSize
    }
    
    /**
     * Get the window size (number of chapters per window).
     * 
     * @return The window size
     */
    fun getWindowSize(): Int = windowSize
    
    companion object {
        const val DEFAULT_WINDOW_SIZE = 5
    }
}
