package com.rifters.riftedreader.pagination

import com.rifters.riftedreader.domain.pagination.ChapterIndex
import com.rifters.riftedreader.domain.pagination.ContinuousPaginator
import com.rifters.riftedreader.domain.pagination.ContinuousPaginatorWindowHtmlProvider
import com.rifters.riftedreader.domain.pagination.SlidingWindowManager
import com.rifters.riftedreader.domain.pagination.WindowIndex
import com.rifters.riftedreader.util.AppLogger
import com.rifters.riftedreader.util.HtmlDebugLogger

/**
 * Default implementation of WindowAssembler that delegates to the existing
 * window HTML wrapping code in ContinuousPaginatorWindowHtmlProvider.
 * 
 * This implementation reuses the same pipeline currently used to create one window
 * with CSS columns. Each assembled window is logged using HtmlDebugLogger for debugging.
 * 
 * **Design notes:**
 * - Uses ContinuousPaginator for chapter content access
 * - Uses SlidingWindowManager for window-to-chapter index mapping
 * - Logs all assembled windows via HtmlDebugLogger for debugging
 * - Thread-safe through delegation to thread-safe components
 * 
 * @param paginator The ContinuousPaginator instance for accessing chapter content
 * @param windowManager The SlidingWindowManager for chapter range calculations
 * @param bookId Unique identifier for the book (used in logging)
 */
class DefaultWindowAssembler(
    private val paginator: ContinuousPaginator,
    private val windowManager: SlidingWindowManager,
    private val bookId: String
) : WindowAssembler {
    
    companion object {
        private const val TAG = "DefaultWindowAssembler"
    }
    
    // Delegate to the existing window HTML provider
    private val windowHtmlProvider = ContinuousPaginatorWindowHtmlProvider(
        paginator,
        windowManager
    )
    
    override suspend fun assembleWindow(
        windowIndex: WindowIndex,
        firstChapter: ChapterIndex,
        lastChapter: ChapterIndex
    ): WindowData? {
        AppLogger.d(TAG, "[PAGINATION_DEBUG] assembleWindow: windowIndex=$windowIndex, " +
            "chapters=$firstChapter-$lastChapter")
        
        // Validate inputs
        if (!canAssemble(windowIndex, firstChapter, lastChapter)) {
            AppLogger.w(TAG, "[PAGINATION_DEBUG] Cannot assemble window $windowIndex: " +
                "invalid chapter range $firstChapter-$lastChapter")
            return null
        }
        
        return try {
            // Use the existing window HTML provider to generate the HTML
            val html = windowHtmlProvider.getWindowHtml(bookId, windowIndex)
            
            if (html == null) {
                AppLogger.w(TAG, "[PAGINATION_DEBUG] WindowHtmlProvider returned null " +
                    "for window $windowIndex")
                return null
            }
            
            // Log the assembled window for debugging
            logAssembledWindow(windowIndex, firstChapter, lastChapter, html)
            
            val windowData = WindowData(
                html = html,
                firstChapter = firstChapter,
                lastChapter = lastChapter,
                windowIndex = windowIndex
            )
            
            AppLogger.d(TAG, "[PAGINATION_DEBUG] Assembled window $windowIndex: " +
                "htmlLength=${html.length}, chapterCount=${windowData.chapterCount}")
            
            windowData
        } catch (e: Exception) {
            AppLogger.e(TAG, "[PAGINATION_DEBUG] Failed to assemble window $windowIndex", e)
            null
        }
    }
    
    override fun canAssemble(
        windowIndex: WindowIndex,
        firstChapter: ChapterIndex,
        lastChapter: ChapterIndex
    ): Boolean {
        val totalChapters = getTotalChapters()
        
        // Validate window index
        if (windowIndex < 0) {
            AppLogger.d(TAG, "[PAGINATION_DEBUG] canAssemble: invalid windowIndex=$windowIndex")
            return false
        }
        
        // Validate chapter range
        if (firstChapter < 0 || lastChapter < firstChapter || lastChapter >= totalChapters) {
            AppLogger.d(TAG, "[PAGINATION_DEBUG] canAssemble: invalid chapter range " +
                "$firstChapter-$lastChapter (totalChapters=$totalChapters)")
            return false
        }
        
        return true
    }
    
    /**
     * Get the total chapters count.
     * 
     * Returns the cached value that was set via setTotalChapters().
     * This approach avoids calling the suspend function getWindowInfo().
     * 
     * Important: Callers must call setTotalChapters() before using this assembler
     * to ensure accurate chapter counts.
     */
    @Volatile
    private var cachedTotalChapters: Int = 0
    
    override fun getTotalChapters(): Int {
        return cachedTotalChapters
    }
    
    /**
     * Set the total chapters count.
     * 
     * This must be called during initialization or when the chapter count changes.
     * 
     * @param totalChapters The total number of chapters
     */
    fun setTotalChapters(totalChapters: Int) {
        cachedTotalChapters = totalChapters
        AppLogger.d(TAG, "[PAGINATION_DEBUG] setTotalChapters: $totalChapters")
    }
    
    /**
     * Log the assembled window using HtmlDebugLogger.
     * This helps debug pagination issues by providing visibility into the assembled HTML.
     */
    private fun logAssembledWindow(
        windowIndex: WindowIndex,
        firstChapter: ChapterIndex,
        lastChapter: ChapterIndex,
        html: String
    ) {
        // Log the window using HtmlDebugLogger.logWrappedHtml for single-file logging
        // This is more efficient than creating a map with duplicate HTML strings
        HtmlDebugLogger.logWrappedHtml(
            bookId = bookId,
            chapterIndex = windowIndex, // Use windowIndex as identifier
            wrappedHtml = html,
            metadata = mapOf(
                "type" to "assembled-window",
                "windowIndex" to windowIndex.toString(),
                "firstChapter" to firstChapter.toString(),
                "lastChapter" to lastChapter.toString(),
                "chapterCount" to (lastChapter - firstChapter + 1).toString(),
                "htmlLength" to html.length.toString()
            )
        )
        
        AppLogger.d(TAG, "[PAGINATION_DEBUG] Logged assembled window $windowIndex to HtmlDebugLogger")
    }
}
