package com.rifters.riftedreader.domain.pagination

import com.rifters.riftedreader.domain.parser.PageContent

/**
 * Represents the loading state of a window.
 */
enum class WindowLoadState {
    /** Window has not been loaded yet */
    UNLOADED,
    
    /** Window is currently being loaded */
    LOADING,
    
    /** Window has been successfully loaded and is ready for reading */
    LOADED,
    
    /** Window failed to load */
    ERROR
}

/**
 * Immutable snapshot of a window's content.
 * 
 * Once created, this snapshot represents a stable view of the chapters within a window.
 * This immutability is critical to avoid calculation tangles and UI inconsistencies during
 * active reading.
 * 
 * @property windowIndex The index of this window in the ViewPager
 * @property firstChapterIndex The first chapter index contained in this window (inclusive)
 * @property lastChapterIndex The last chapter index contained in this window (inclusive)
 * @property chapters List of chapter data contained in this window
 * @property totalPages Total number of pages across all chapters in this window
 * @property htmlContent The complete HTML content for this window (with all chapters)
 * @property loadState The current loading state of this window
 * @property errorMessage Optional error message if loadState is ERROR
 */
data class WindowSnapshot(
    val windowIndex: WindowIndex,
    val firstChapterIndex: ChapterIndex,
    val lastChapterIndex: ChapterIndex,
    val chapters: List<WindowChapterData>,
    val totalPages: Int,
    val htmlContent: String? = null,
    val loadState: WindowLoadState = WindowLoadState.UNLOADED,
    val errorMessage: String? = null
) {
    /**
     * Check if this window is loaded and ready for reading.
     */
    val isReady: Boolean
        get() = loadState == WindowLoadState.LOADED && htmlContent != null
    
    /**
     * Get the chapter indices contained in this window.
     */
    val chapterIndices: IntRange
        get() = firstChapterIndex..lastChapterIndex
    
    /**
     * Check if this window contains the given chapter.
     */
    fun containsChapter(chapterIndex: ChapterIndex): Boolean {
        return chapterIndex in firstChapterIndex..lastChapterIndex
    }
    
    /**
     * Get chapter data by chapter index.
     */
    fun getChapter(chapterIndex: ChapterIndex): WindowChapterData? {
        return chapters.find { it.chapterIndex == chapterIndex }
    }
    
    /**
     * Convert this snapshot to a WindowDescriptor for the loadWindow API.
     * 
     * @param entryPosition Optional entry position for navigation after loading
     * @return WindowDescriptor suitable for JavaScript loadWindow() call
     */
    fun toDescriptor(entryPosition: EntryPosition? = null): WindowDescriptor {
        return WindowDescriptor(
            windowIndex = windowIndex,
            firstChapterIndex = firstChapterIndex,
            lastChapterIndex = lastChapterIndex,
            chapters = chapters.map { chapter ->
                ChapterDescriptor(
                    chapterIndex = chapter.chapterIndex,
                    title = chapter.title,
                    elementId = CHAPTER_ELEMENT_ID_PREFIX + chapter.chapterIndex
                )
            },
            entryPosition = entryPosition,
            estimatedPageCount = totalPages
        )
    }
    
    companion object {
        /**
         * Prefix for chapter element IDs in HTML.
         * Must match the format used in ContinuousPaginatorWindowHtmlProvider.
         */
        const val CHAPTER_ELEMENT_ID_PREFIX = "chapter-"
    }
}

/**
 * Data about a single chapter within a window.
 * 
 * @property chapterIndex The chapter index in the book
 * @property title The chapter title (from TOC or inferred)
 * @property pageCount Number of pages in this chapter
 * @property startPage Starting page index within the window (0-based)
 * @property content The chapter content (may be null if not yet loaded)
 */
data class WindowChapterData(
    val chapterIndex: ChapterIndex,
    val title: String,
    val pageCount: Int,
    val startPage: Int,
    val content: PageContent? = null
)

/**
 * Mutable state tracking for a window during construction or background loading.
 * 
 * This is used during window construction and should NOT be exposed to the active
 * reading experience. Once construction is complete, create a WindowSnapshot for
 * immutable access.
 * 
 * @property windowIndex The index of this window
 * @property firstChapterIndex First chapter in this window
 * @property lastChapterIndex Last chapter in this window
 * @property loadState Current loading state
 * @property chapters Mutable list of chapter data being constructed
 * @property errorMessage Error message if loading failed
 */
data class WindowState(
    val windowIndex: WindowIndex,
    val firstChapterIndex: ChapterIndex,
    val lastChapterIndex: ChapterIndex,
    var loadState: WindowLoadState = WindowLoadState.UNLOADED,
    val chapters: MutableList<WindowChapterData> = mutableListOf(),
    var errorMessage: String? = null
) {
    /**
     * Convert this mutable state to an immutable snapshot.
     * 
     * This method recalculates the startPage for each chapter based on cumulative
     * page counts. This is critical for accurate progress calculations in
     * StableWindowManager.updatePosition().
     * 
     * @param htmlContent The complete HTML for this window
     * @return Immutable WindowSnapshot
     */
    fun toSnapshot(htmlContent: String? = null): WindowSnapshot {
        // Recalculate startPage for each chapter based on cumulative page counts
        var cumulativeStartPage = 0
        val chaptersWithStartPages = chapters.map { chapter ->
            val updatedChapter = chapter.copy(startPage = cumulativeStartPage)
            cumulativeStartPage += chapter.pageCount
            updatedChapter
        }
        
        return WindowSnapshot(
            windowIndex = windowIndex,
            firstChapterIndex = firstChapterIndex,
            lastChapterIndex = lastChapterIndex,
            chapters = chaptersWithStartPages, // Use chapters with recalculated startPage values
            totalPages = chaptersWithStartPages.sumOf { it.pageCount },
            htmlContent = htmlContent,
            loadState = loadState,
            errorMessage = errorMessage
        )
    }
    
    /**
     * Add a chapter to this window during construction.
     */
    fun addChapter(chapterData: WindowChapterData) {
        chapters.add(chapterData)
    }
    
    /**
     * Mark this window as loaded.
     */
    fun markLoaded() {
        loadState = WindowLoadState.LOADED
    }
    
    /**
     * Mark this window as failed with an error message.
     */
    fun markError(message: String) {
        loadState = WindowLoadState.ERROR
        errorMessage = message
    }
}

/**
 * Configuration for window preloading behavior.
 * 
 * @property preloadThreshold Percentage of current window progress (0.0-1.0) at which
 *                           to trigger preloading of adjacent windows. Default: 0.75 (75%)
 * @property maxWindows Maximum number of windows to keep in memory. Default: 3
 *                      (prev, active, next)
 */
data class WindowPreloadConfig(
    val preloadThreshold: Double = 0.75,
    val maxWindows: Int = 3
) {
    init {
        require(preloadThreshold in 0.0..1.0) {
            "preloadThreshold must be between 0.0 and 1.0, got: $preloadThreshold"
        }
        require(maxWindows >= 1) {
            "maxWindows must be at least 1, got: $maxWindows"
        }
    }
}

/**
 * Represents the user's current position within a window.
 * 
 * @property windowIndex The current window index
 * @property chapterIndex The current chapter index within the window
 * @property inPageIndex The current page index within the chapter
 * @property progress Progress through the current window (0.0-1.0)
 */
data class WindowPosition(
    val windowIndex: WindowIndex,
    val chapterIndex: ChapterIndex,
    val inPageIndex: InPageIndex,
    val progress: Double
) {
    init {
        require(progress in 0.0..1.0) {
            "progress must be between 0.0 and 1.0, got: $progress"
        }
    }
    
    /**
     * Check if we should trigger preloading based on configured threshold.
     */
    fun shouldPreloadNext(threshold: Double): Boolean {
        return progress >= threshold
    }
    
    /**
     * Check if we should trigger preloading of previous window.
     */
    fun shouldPreloadPrev(threshold: Double): Boolean {
        return progress <= (1.0 - threshold)
    }
}

/**
 * Enhanced window readiness state for gating HTML assembly.
 *
 * This class tracks the requested chapter range vs loaded chapter range,
 * enabling the UI to show a placeholder while chapters are loading.
 *
 * **Usage Flow:**
 * 1. UI requests window X with chapters A through E
 * 2. WindowReadiness tracks: requestedRange = A to E, loadedRange = empty
 * 3. As chapters load: loadedRange updates A, then A to B, then A to C, etc.
 * 4. When loadedRange equals requestedRange, isReady becomes true
 * 5. UI receives notification and rebinds with full content
 *
 * @property windowIndex The window index
 * @property requestedRange Chapters requested for this window
 * @property loadedRange Chapters actually loaded so far
 * @property ready Whether all requested chapters are loaded
 */
data class WindowReadiness(
    val windowIndex: WindowIndex,
    val requestedRange: IntRange,
    val loadedRange: IntRange? = null,
    val ready: Boolean = false
) {
    /**
     * Check if a specific chapter is loaded.
     */
    fun isChapterLoaded(chapterIndex: Int): Boolean {
        return loadedRange?.contains(chapterIndex) == true
    }

    /**
     * Get the loading progress (0.0 to 1.0).
     */
    val loadingProgress: Float
        get() {
            if (requestedRange.isEmpty()) return 1.0f
            if (loadedRange == null || loadedRange.isEmpty()) return 0.0f
            val total = requestedRange.count()
            val loaded = loadedRange.count()
            return (loaded.toFloat() / total).coerceIn(0.0f, 1.0f)
        }

    /**
     * Get the missing chapter indices that still need to be loaded.
     */
    val missingChapters: List<Int>
        get() {
            if (loadedRange == null) return requestedRange.toList()
            return requestedRange.filter { it !in loadedRange }
        }

    companion object {
        /**
         * Create a new WindowReadiness for a window that needs loading.
         */
        fun forWindow(windowIndex: Int, chapterRange: IntRange): WindowReadiness {
            return WindowReadiness(
                windowIndex = windowIndex,
                requestedRange = chapterRange,
                loadedRange = null,
                ready = chapterRange.isEmpty()
            )
        }

        /**
         * Create a ready WindowReadiness (all chapters loaded).
         */
        fun ready(windowIndex: Int, chapterRange: IntRange): WindowReadiness {
            return WindowReadiness(
                windowIndex = windowIndex,
                requestedRange = chapterRange,
                loadedRange = chapterRange,
                ready = true
            )
        }
    }
}

/**
 * Callback interface for window readiness state changes.
 */
interface WindowReadinessCallback {
    /**
     * Called when a window's readiness state changes.
     *
     * @param windowIndex The window that changed
     * @param readiness The new readiness state
     */
    fun onWindowReadinessChanged(windowIndex: Int, readiness: WindowReadiness)

    /**
     * Called when a window becomes fully ready.
     *
     * @param windowIndex The window that is now ready
     */
    fun onWindowReady(windowIndex: Int)
}
