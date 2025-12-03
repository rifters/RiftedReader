package com.rifters.riftedreader.domain.pagination

import com.rifters.riftedreader.domain.parser.BookParser
import com.rifters.riftedreader.domain.parser.PageContent
import com.rifters.riftedreader.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Continuous paginator that maintains a sliding window of chapters in memory.
 * 
 * Features:
 * - Keeps a configurable window of chapters loaded (default: 5 - current + 2 before + 2 after)
 * - Maps global page indices to (chapter, inPageIndex) pairs
 * - Dynamically loads/unloads chapters as user navigates
 * - Preserves reading position during repagination (font size changes)
 * 
 * Thread-safe: All public methods use mutex to ensure consistency.
 */
class ContinuousPaginator(
    private val bookFile: File,
    private val parser: BookParser,
    private val windowSize: Int = DEFAULT_WINDOW_SIZE
) {
    
    private val mutex = Mutex()
    
    // Total number of chapters in the book
    private var totalChapters: Int = 0
    
    // Window state
    private var currentChapterIndex: Int = 0
    private val loadedChapters = mutableMapOf<Int, ChapterData>()
    
    // Global page index mapping: globalPageIndex -> PageLocation
    private val globalPageMap = mutableListOf<PageLocation>()
    
    // Chapter metadata: chapterIndex -> ChapterMetadata
    private val chapterMetadata = mutableListOf<ChapterMetadata>()
    
    /**
     * Initialize the paginator by loading chapter metadata.
     * Must be called before any other operations.
     */
    suspend fun initialize() = mutex.withLock {
        AppLogger.d(TAG, "Initializing ContinuousPaginator")
        
        totalChapters = withContext(Dispatchers.IO) {
            parser.getPageCount(bookFile)
        }
        
        AppLogger.d(TAG, "Total chapters: $totalChapters")
        
        // Initialize chapter metadata with estimated page counts
        // Actual page counts will be updated as chapters are loaded
        chapterMetadata.clear()
        repeat(totalChapters) { chapterIndex ->
            chapterMetadata.add(
                ChapterMetadata(
                    chapterIndex = chapterIndex,
                    estimatedPageCount = 1, // Will be updated when chapter loads
                    actualPageCount = null, // Not loaded yet
                    globalPageStartIndex = -1 // Will be calculated
                )
            )
        }
        
        AppLogger.d(TAG, "Initialized ${chapterMetadata.size} chapter metadata entries")
    }
    
    /**
     * Load the initial window centered on the given chapter.
     * 
     * @param chapterIndex The chapter to center the window on
     * @return GlobalPageIndex for the start of the chapter
     */
    suspend fun loadInitialWindow(chapterIndex: Int): GlobalPageIndex = mutex.withLock {
        val safeChapterIndex = chapterIndex.coerceIn(0, totalChapters - 1)
        currentChapterIndex = safeChapterIndex
        
        AppLogger.d(TAG, "Loading initial window centered on chapter $safeChapterIndex")
        
        // Load the window
        loadWindow(safeChapterIndex)
        
        // Recalculate global page mapping
        recalculateGlobalPageMapping()
        
        // Log window enter event for initial load
        val windowIndices = getWindowIndices(safeChapterIndex)
        AppLogger.logWindowEnter(
            windowIndex = safeChapterIndex,
            chapters = windowIndices,
            navigationMode = "INITIAL_LOAD"
        )
        
        // Update the global window state for logging
        AppLogger.updateWindowState(
            windowIndex = safeChapterIndex,
            chapters = windowIndices,
            navigationMode = "INITIAL_LOAD"
        )
        
        // Return the global page index for the start of the chapter
        chapterMetadata[safeChapterIndex].globalPageStartIndex
    }
    
    /**
     * Navigate to a global page index.
     * Loads/unloads chapters as needed to maintain the window.
     * 
     * @param globalPageIndex The global page index to navigate to
     * @return PageLocation for the target page, or null if invalid
     */
    suspend fun navigateToGlobalPage(globalPageIndex: GlobalPageIndex): PageLocation? = mutex.withLock {
        if (globalPageIndex !in globalPageMap.indices) {
            AppLogger.w(TAG, "Invalid global page index: $globalPageIndex (valid range: 0-${globalPageMap.size - 1})")
            return null
        }
        
        val location = globalPageMap[globalPageIndex]
        val previousChapterIndex = currentChapterIndex
        
        // Check if we need to shift the window
        if (location.chapterIndex != currentChapterIndex) {
            AppLogger.d(TAG, "Shifting window from chapter $currentChapterIndex to ${location.chapterIndex}")
            
            // Log window exit for the old window
            val oldWindowIndices = getWindowIndices(currentChapterIndex)
            AppLogger.logWindowExit(
                windowIndex = currentChapterIndex,
                chapters = oldWindowIndices,
                direction = if (location.chapterIndex > currentChapterIndex) "NEXT" else "PREV",
                targetWindowIndex = location.chapterIndex
            )
            
            currentChapterIndex = location.chapterIndex
            loadWindow(currentChapterIndex)
            recalculateGlobalPageMapping()
            
            // Log navigation event
            AppLogger.logNavigation(
                eventType = "CHAPTER_SWITCH",
                fromWindowIndex = previousChapterIndex,
                toWindowIndex = currentChapterIndex,
                fromChapterIndex = previousChapterIndex,
                toChapterIndex = location.chapterIndex,
                fromPageIndex = 0,
                toPageIndex = location.inPageIndex
            )
        }
        
        location
    }
    
    /**
     * Navigate to a specific chapter and page within that chapter.
     * 
     * @param chapterIndex The chapter index
     * @param inPageIndex The page index within the chapter (default: 0)
     * @return GlobalPageIndex for the target page
     */
    suspend fun navigateToChapter(chapterIndex: Int, inPageIndex: Int = 0): GlobalPageIndex = mutex.withLock {
        val safeChapterIndex = chapterIndex.coerceIn(0, totalChapters - 1)
        val previousChapterIndex = currentChapterIndex
        
        if (safeChapterIndex != currentChapterIndex) {
            // Log window exit for the old window
            val oldWindowIndices = getWindowIndices(currentChapterIndex)
            AppLogger.logWindowExit(
                windowIndex = currentChapterIndex,
                chapters = oldWindowIndices,
                direction = if (safeChapterIndex > currentChapterIndex) "NEXT" else "PREV",
                targetWindowIndex = safeChapterIndex
            )
            
            currentChapterIndex = safeChapterIndex
            loadWindow(currentChapterIndex)
            recalculateGlobalPageMapping()
            
            // Log window enter for the new window
            val newWindowIndices = getWindowIndices(safeChapterIndex)
            AppLogger.logWindowEnter(
                windowIndex = safeChapterIndex,
                chapters = newWindowIndices,
                navigationMode = "USER_NAVIGATION",
                previousWindowIndex = previousChapterIndex
            )
            
            // Log navigation event
            AppLogger.logNavigation(
                eventType = "CHAPTER_JUMP",
                fromWindowIndex = previousChapterIndex,
                toWindowIndex = safeChapterIndex,
                fromChapterIndex = previousChapterIndex,
                toChapterIndex = safeChapterIndex,
                fromPageIndex = 0,
                toPageIndex = inPageIndex
            )
            
            // Update the global window state for logging
            AppLogger.updateWindowState(
                windowIndex = safeChapterIndex,
                chapters = newWindowIndices,
                navigationMode = "USER_NAVIGATION"
            )
        }
        
        // Find the global page index for this chapter + inPageIndex
        val metadata = chapterMetadata[safeChapterIndex]
        val pageCount = metadata.actualPageCount ?: metadata.estimatedPageCount
        val safeInPageIndex = inPageIndex.coerceIn(0, pageCount - 1)
        
        metadata.globalPageStartIndex + safeInPageIndex
    }
    
    /**
     * Get the page content for a global page index.
     */
    suspend fun getPageContent(globalPageIndex: GlobalPageIndex): PageContent? = mutex.withLock {
        if (globalPageIndex !in globalPageMap.indices) {
            AppLogger.w(TAG, "Invalid global page index for content: $globalPageIndex")
            return null
        }
        
        val location = globalPageMap[globalPageIndex]
        val chapterData = loadedChapters[location.chapterIndex]
        
        if (chapterData == null) {
            AppLogger.w(TAG, "Chapter ${location.chapterIndex} not loaded")
            return null
        }
        
        chapterData.content
    }
    
    /**
     * Get the current global page index.
     */
    suspend fun getCurrentGlobalPage(): GlobalPageIndex = mutex.withLock {
        chapterMetadata[currentChapterIndex].globalPageStartIndex
    }
    
    /**
     * Get the total number of global pages.
     */
    suspend fun getTotalGlobalPages(): Int = mutex.withLock {
        globalPageMap.size
    }
    
    /**
     * Get page location (chapter + inPageIndex) for a global page index.
     */
    suspend fun getPageLocation(globalPageIndex: GlobalPageIndex): PageLocation? = mutex.withLock {
        globalPageMap.getOrNull(globalPageIndex)
    }

    /**
     * Get the total number of pages for a specific chapter.
     */
    suspend fun getChapterPageCount(chapterIndex: Int): Int = mutex.withLock {
        val metadata = chapterMetadata.getOrNull(chapterIndex)
            ?: return@withLock 0
        metadata.actualPageCount ?: metadata.estimatedPageCount
    }

    /**
     * Update the measured page count for a chapter (e.g., from WebView pagination).
     * Recalculates the global page mapping if the value changed.
     */
    suspend fun updateChapterPageCount(chapterIndex: Int, pageCount: Int): Boolean = mutex.withLock {
        val metadata = chapterMetadata.getOrNull(chapterIndex) ?: return@withLock false
        val safeCount = pageCount.coerceAtLeast(1)
        val currentCount = metadata.actualPageCount ?: metadata.estimatedPageCount
        if (currentCount == safeCount) {
            return@withLock false
        }

        AppLogger.d(TAG, "Updating chapter $chapterIndex page count: $currentCount -> $safeCount")
        chapterMetadata[chapterIndex] = metadata.copy(actualPageCount = safeCount)
        recalculateGlobalPageMapping()
        true
    }

    /**
     * Get the global page index for a specific chapter + in-page combination.
     */
    suspend fun getGlobalIndexForChapterPage(
        chapterIndex: Int,
        inPageIndex: Int
    ): GlobalPageIndex? = mutex.withLock {
        val metadata = chapterMetadata.getOrNull(chapterIndex) ?: return@withLock null
        val pageCount = metadata.actualPageCount ?: metadata.estimatedPageCount
        val safeInPage = inPageIndex.coerceIn(0, pageCount - 1)
        val startIndex = metadata.globalPageStartIndex
        if (startIndex < 0) return@withLock null
        startIndex + safeInPage
    }
    
    /**
     * Repaginate all loaded chapters (e.g., after font size change).
     * Attempts to preserve the current reading position.
     * 
     * @return New global page index closest to the previous position
     */
    suspend fun repaginate(): GlobalPageIndex = mutex.withLock {
        AppLogger.d(TAG, "Repaginating loaded chapters")
        
        // Save current position
        val currentGlobalPage = chapterMetadata[currentChapterIndex].globalPageStartIndex
        
        // Reload all chapters in the window
        val windowIndices = getWindowIndices(currentChapterIndex)
        
        loadedChapters.clear()
        
        for (chapterIndex in windowIndices) {
            loadChapter(chapterIndex)
        }
        
        // Recalculate global page mapping
        recalculateGlobalPageMapping()
        
        // Try to find a position close to where we were
        // This is best-effort - the actual page count may have changed
        val newGlobalPage = chapterMetadata[currentChapterIndex].globalPageStartIndex
        
        AppLogger.d(TAG, "Repagination complete: old=$currentGlobalPage new=$newGlobalPage")
        
        newGlobalPage
    }
    
    /**
     * Get information about the currently loaded window.
     */
    suspend fun getWindowInfo(): WindowInfo = mutex.withLock {
        WindowInfo(
            currentChapterIndex = currentChapterIndex,
            loadedChapterIndices = loadedChapters.keys.sorted(),
            totalChapters = totalChapters,
            totalGlobalPages = globalPageMap.size
        )
    }

    /**
     * Hint from WebView streaming layer that a chapter segment was evicted.
     * We remove the cached chapter content if it is not the actively centered one,
     * allowing future loads to rehydrate it on demand.
     */
    suspend fun markChapterEvicted(chapterIndex: Int) = mutex.withLock {
        if (chapterIndex == currentChapterIndex) {
            AppLogger.d(TAG, "Ignoring eviction for current chapter $chapterIndex")
            return@withLock
        }
        if (loadedChapters.remove(chapterIndex) != null) {
            AppLogger.d(TAG, "Evicted streamed chapter $chapterIndex from loaded cache")
        }
    }
    
    /**
     * Load the window of chapters centered on the given chapter index.
     */
    private suspend fun loadWindow(centerChapterIndex: Int) {
        val windowIndices = getWindowIndices(centerChapterIndex)
        
        AppLogger.d(TAG, "Loading window: chapters ${windowIndices.first()}-${windowIndices.last()}")
        
        // CRITICAL FIX: During initialization (when many windows are preloading),
        // DO NOT unload chapters that may be needed by other windows being assembled.
        // The WindowBufferManager will handle cleanup after initialization is complete.
        // Only unload chapters if we're NOT in an initialization phase (detected by checking
        // if we have a large number of loaded chapters relative to a single window).
        
        // Determine which chapters to unload
        val toUnload = loadedChapters.keys - windowIndices.toSet()
        
        // SAFETY CHECK: Don't unload if we have fewer loaded chapters than would fill 5 windows.
        // This prevents premature unloading during buffer initialization when 5 windows (0-4)
        // are being assembled in parallel. Each window needs 5 chapters, so we need 25 chapters
        // loaded before it's safe to start unloading. WindowBufferManager will handle cleanup
        // after all 5 buffer windows are cached.
        val safeToUnload = loadedChapters.size >= (windowSize * 5)
        
        if (safeToUnload) {
            toUnload.forEach { chapterIndex ->
                AppLogger.d(TAG, "Unloading chapter $chapterIndex")
                loadedChapters.remove(chapterIndex)
            }
        } else {
            AppLogger.d(TAG, "Skipping unload during initialization: loadedChapters.size=${loadedChapters.size} < ${windowSize * 5}")
        }
        
        // Determine which chapters to load
        val toLoad = windowIndices.filter { it !in loadedChapters.keys }
        toLoad.forEach { chapterIndex ->
            loadChapter(chapterIndex)
        }
        
        // Log the complete window HTML for debugging
        logWindowHtml(centerChapterIndex, windowIndices)
    }
    
    /**
     * Load a single chapter.
     */
    private suspend fun loadChapter(chapterIndex: Int) {
        if (chapterIndex !in 0 until totalChapters) {
            AppLogger.w(TAG, "Cannot load chapter $chapterIndex (out of range)")
            return
        }
        
        AppLogger.d(TAG, "Loading chapter $chapterIndex")
        
        val content = withContext(Dispatchers.IO) {
            parser.getPageContent(bookFile, chapterIndex)
        }
        
        // For now, treat each chapter as a single page
        // In the future, we can implement intra-chapter pagination here
        val pageCount = 1
        
        loadedChapters[chapterIndex] = ChapterData(
            chapterIndex = chapterIndex,
            content = content,
            pageCount = pageCount
        )
        
        // Update chapter metadata
        chapterMetadata[chapterIndex] = chapterMetadata[chapterIndex].copy(
            actualPageCount = pageCount
        )
        
        AppLogger.d(TAG, "Loaded chapter $chapterIndex with $pageCount pages")
    }
    
    /**
     * Get the indices of chapters that should be in the window.
     */
    private fun getWindowIndices(centerChapterIndex: Int): List<Int> {
        if (totalChapters == 0) return emptyList()

        val maxWindow = windowSize.coerceAtMost(totalChapters)
        var start = (centerChapterIndex - windowSize / 2).coerceAtLeast(0)
        var end = (start + maxWindow - 1).coerceAtMost(totalChapters - 1)
        start = (end - maxWindow + 1).coerceAtLeast(0)

        return (start..end).toList()
    }
    
    /**
     * Recalculate the global page mapping based on currently loaded chapters.
     */
    private fun recalculateGlobalPageMapping() {
        AppLogger.d(TAG, "Recalculating global page mapping")
        
        globalPageMap.clear()
        var globalIndex = 0
        
        for (chapterIndex in 0 until totalChapters) {
            val metadata = chapterMetadata[chapterIndex]
            val pageCount = metadata.actualPageCount ?: metadata.estimatedPageCount
            
            // Update the global start index for this chapter
            chapterMetadata[chapterIndex] = metadata.copy(
                globalPageStartIndex = globalIndex
            )
            
            // Add page locations to the global map
            for (inPageIndex in 0 until pageCount) {
                globalPageMap.add(
                    PageLocation(
                        globalPageIndex = globalIndex,
                        chapterIndex = chapterIndex,
                        inPageIndex = inPageIndex,
                        characterOffset = 0
                    )
                )
                globalIndex++
            }
        }
        
        AppLogger.d(TAG, "Global page mapping complete: ${globalPageMap.size} total pages")
    }
    
    /**
     * Log the current window HTML for debugging purposes.
     */
    private fun logWindowHtml(centerChapterIndex: Int, windowIndices: List<Int>) {
        try {
            // Build a map of chapter HTML from loaded chapters
            val chaptersHtml = mutableMapOf<Int, String>()
            windowIndices.forEach { chapterIndex ->
                val chapterData = loadedChapters[chapterIndex]
                if (chapterData != null) {
                    val html = chapterData.content.html ?: chapterData.content.text
                    if (html.isNotBlank()) {
                        chaptersHtml[chapterIndex] = html
                    }
                }
            }
            
            if (chaptersHtml.isNotEmpty()) {
                val metadata = mutableMapOf<String, String>()
                metadata["totalChapters"] = totalChapters.toString()
                metadata["windowSize"] = windowSize.toString()
                metadata["totalGlobalPages"] = globalPageMap.size.toString()
                metadata["loadedChapterCount"] = loadedChapters.size.toString()
                
                com.rifters.riftedreader.util.HtmlDebugLogger.logWindowHtml(
                    bookId = bookFile.absolutePath,
                    windowIndex = centerChapterIndex,
                    chapterIndices = windowIndices,
                    chapters = chaptersHtml,
                    metadata = metadata
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to log window HTML", e)
        }
    }
    
    /**
     * Data class representing a single chapter's data.
     */
    private data class ChapterData(
        val chapterIndex: Int,
        val content: PageContent,
        val pageCount: Int
    )
    
    /**
     * Metadata about a chapter (without loading the full content).
     */
    private data class ChapterMetadata(
        val chapterIndex: Int,
        val estimatedPageCount: Int,
        val actualPageCount: Int?,
        val globalPageStartIndex: GlobalPageIndex
    )
    
    companion object {
        private const val TAG = "ContinuousPaginator"
        private const val DEFAULT_WINDOW_SIZE = 5
    }
}

/**
 * Type alias for global page index (0-based index across all chapters).
 */
typealias GlobalPageIndex = Int

/**
 * Represents a location within the book.
 */
data class PageLocation(
    val globalPageIndex: GlobalPageIndex,
    val chapterIndex: Int,
    val inPageIndex: Int,
    val characterOffset: Int = 0
)

/**
 * Information about the currently loaded window.
 */
data class WindowInfo(
    val currentChapterIndex: Int,
    val loadedChapterIndices: List<Int>,
    val totalChapters: Int,
    val totalGlobalPages: Int
)
