package com.rifters.riftedreader.domain.pagination

/**
 * Type aliases for pagination concepts to provide semantic clarity.
 * 
 * The paginator system distinguishes between three different types of indices:
 * - WindowIndex: ViewPager2 page position (one HTML document per window)
 * - ChapterIndex: Logical chapter in the book
 * - InPageIndex: Sub-page within a window (horizontal scroll position)
 * 
 * These type aliases make it explicit which concept is being used in method signatures
 * and prevent confusion between "page index" in different contexts.
 */

/**
 * Index of a window in the ViewPager2.
 * 
 * A window is a contiguous subset of chapters that are wrapped together into one HTML
 * document and loaded into one WebView instance. In continuous pagination mode, multiple
 * chapters can exist in a single window.
 * 
 * Example: window 0 might contain chapters 0-2, window 1 might contain chapters 3-5.
 */
typealias WindowIndex = Int

/**
 * Logical chapter index in the book.
 * 
 * Chapters are the semantic divisions of the book content, typically corresponding
 * to the book's table of contents. Each chapter has a unique index regardless of
 * which window it is rendered in.
 * 
 * Example: A book with 10 chapters has chapterIndex values from 0 to 9.
 */
typealias ChapterIndex = Int

/**
 * Sub-page index within a window.
 * 
 * When horizontal pagination is enabled (CSS columns), a window can be divided into
 * multiple pages. This index identifies which page within the window is currently visible.
 * 
 * Example: If a window has 3 pages due to CSS column pagination, inPageIndex can be 0, 1, or 2.
 */
typealias InPageIndex = Int

/**
 * Configuration for the in-page paginator JavaScript API.
 * 
 * This configuration makes explicit whether the paginator should treat the entire
 * window as the pagination root (window mode) or a single chapter section (chapter mode).
 * 
 * @property mode The pagination mode (window or chapter)
 * @property windowIndex The window index (always provided)
 * @property chapterIndex The chapter index (required for chapter mode, optional for window mode)
 * @property rootSelector Optional CSS selector for the pagination root (defaults to document.body or window-root)
 */
data class PaginatorConfig(
    val mode: PaginatorMode,
    val windowIndex: WindowIndex,
    val chapterIndex: ChapterIndex? = null,
    val rootSelector: String? = null
)

/**
 * Pagination mode for the in-page paginator.
 * 
 * - WINDOW: Paginate across the entire window containing multiple chapters.
 *   The pagination root is the full document, and page counts include all chapters.
 *   
 * - CHAPTER: Paginate within a single chapter section.
 *   The pagination root is the specific chapter element, and page counts are limited to it.
 */
enum class PaginatorMode {
    /**
     * Window mode: pagination spans multiple chapters in a single window.
     * The entire document is the scroll root.
     */
    WINDOW,
    
    /**
     * Chapter mode: pagination is limited to a single chapter.
     * A specific section element is the scroll root.
     */
    CHAPTER
}

/**
 * Information about which chapters are contained in a window and their boundaries.
 * 
 * This mapping allows the system to:
 * - Navigate to specific chapters within a window
 * - Track reading position (which chapter the user is viewing)
 * - Support TTS and bookmarks even when the scroll root is the entire window
 * 
 * @property chapterIndex The logical chapter index
 * @property startOffset The starting scroll offset (in pixels or as a percentage) for this chapter
 * @property elementId Optional ID of the chapter element for direct navigation
 */
data class ChapterBoundary(
    val chapterIndex: ChapterIndex,
    val startOffset: Int,
    val elementId: String? = null
)

/**
 * Result of pagination initialization or reflow.
 * 
 * @property success Whether the operation succeeded
 * @property pageCount The total number of pages in the current configuration
 * @property currentPage The current page index after the operation
 * @property chapterBoundaries List of chapter boundaries within the window
 */
data class PaginationResult(
    val success: Boolean,
    val pageCount: Int,
    val currentPage: InPageIndex = 0,
    val chapterBoundaries: List<ChapterBoundary> = emptyList()
)

// ============================================================================
// Window Communication API Types
// ============================================================================
// These types define the pseudo-API for communication between Android/Kotlin 
// and JavaScript (WebView) for managing reading windows.
// See docs/complete/WINDOW_COMMUNICATION_API.md for full documentation.

/**
 * Descriptor for a reading window sent from Android to JavaScript.
 * Contains all information needed to render and navigate within a window.
 * 
 * This is the primary payload for the loadWindow() API call.
 * 
 * @property windowIndex Unique identifier for this window
 * @property firstChapterIndex First chapter index in this window (inclusive)
 * @property lastChapterIndex Last chapter index in this window (inclusive)
 * @property chapters Chapter metadata for each chapter in the window
 * @property entryPosition Initial position to navigate to after loading (optional)
 * @property estimatedPageCount Total pages in the window if known from previous load
 */
data class WindowDescriptor(
    val windowIndex: WindowIndex,
    val firstChapterIndex: ChapterIndex,
    val lastChapterIndex: ChapterIndex,
    val chapters: List<ChapterDescriptor>,
    val entryPosition: EntryPosition? = null,
    val estimatedPageCount: Int? = null
)

/**
 * Metadata for a single chapter within a window.
 * Used in WindowDescriptor to describe each chapter's identity.
 * 
 * @property chapterIndex Chapter index in the book
 * @property title Chapter title (from TOC or inferred)
 * @property elementId Element ID for this chapter in the HTML (e.g., "chapter-5")
 */
data class ChapterDescriptor(
    val chapterIndex: ChapterIndex,
    val title: String,
    val elementId: String
)

/**
 * Initial position to navigate to after window loads.
 * Used to restore reading position during window transitions.
 * 
 * @property chapterIndex Target chapter index
 * @property inPageIndex Target page within the window (0-based)
 * @property characterOffset Optional character offset for precise positioning
 */
data class EntryPosition(
    val chapterIndex: ChapterIndex,
    val inPageIndex: InPageIndex = 0,
    val characterOffset: Int? = null
)

/**
 * Detailed mapping from global book position to window-local position.
 * Enables accurate position restoration when switching windows.
 * 
 * @property windowIndex Window index
 * @property chapterIndex Chapter index within the window
 * @property windowPageIndex Page index within the window (0-based, across all chapters)
 * @property chapterPageIndex Page index within the chapter (0-based)
 * @property totalWindowPages Total pages in the window
 * @property totalChapterPages Total pages in the chapter
 */
data class PageMappingInfo(
    val windowIndex: WindowIndex,
    val chapterIndex: ChapterIndex,
    val windowPageIndex: InPageIndex,
    val chapterPageIndex: InPageIndex,
    val totalWindowPages: Int,
    val totalChapterPages: Int
)

/**
 * Chapter boundary information with page range.
 * Used by getChapterBoundaries() to report chapter page mappings.
 * 
 * Example: If a chapter occupies pages 3, 4, and 5 (3 pages total):
 * - startPage = 3 (first page, 0-based, inclusive)
 * - endPage = 6 (one past last page, 0-based, exclusive)
 * - pageCount = 3 (6 - 3 = 3 pages)
 * 
 * @property chapterIndex Chapter index in the book
 * @property startPage First page of this chapter within the window (0-based, inclusive)
 * @property endPage Page after the last page of this chapter (0-based, exclusive)
 * @property pageCount Number of pages in this chapter (endPage - startPage)
 */
data class ChapterBoundaryInfo(
    val chapterIndex: ChapterIndex,
    val startPage: InPageIndex,
    val endPage: InPageIndex,
    val pageCount: Int
)

// ============================================================================
// JavaScript → Android Event Types
// ============================================================================

/**
 * Result sent from JavaScript when a window finishes loading.
 * Sent via AndroidBridge.onWindowLoaded callback.
 * 
 * @property windowIndex The window that was loaded
 * @property pageCount Total pages in the window
 * @property chapterBoundaries Page boundaries for each chapter
 */
data class WindowLoadedResult(
    val windowIndex: WindowIndex,
    val pageCount: Int,
    val chapterBoundaries: List<ChapterBoundaryInfo>
)

/**
 * Event sent from JavaScript when the current page changes.
 * Sent via AndroidBridge.onPageChanged callback.
 * 
 * @property page Current page index (0-based)
 * @property chapter Current chapter index
 * @property pageCount Total pages in the window
 */
data class PageChangedEvent(
    val page: InPageIndex,
    val chapter: ChapterIndex,
    val pageCount: Int
)

/**
 * Event sent from JavaScript when user reaches a window boundary.
 * Sent via AndroidBridge.onBoundaryReached callback.
 * 
 * Directions:
 * - NEXT: Reached last page, should transition to next window
 * - PREVIOUS: Reached first page, should transition to previous window
 * 
 * @property direction Boundary direction ("NEXT" or "PREVIOUS")
 * @property currentPage Current page index
 * @property pageCount Total pages in the window
 * @property currentChapter Current chapter index
 */
data class BoundaryReachedEvent(
    val direction: String,
    val currentPage: InPageIndex,
    val pageCount: Int,
    val currentChapter: ChapterIndex
) {
    companion object {
        const val DIRECTION_NEXT = "NEXT"
        const val DIRECTION_PREVIOUS = "PREVIOUS"
    }
    
    /** True if boundary is at end of window (next window needed) */
    val isNextBoundary: Boolean get() = direction == DIRECTION_NEXT
    
    /** True if boundary is at start of window (previous window needed) */
    val isPreviousBoundary: Boolean get() = direction == DIRECTION_PREVIOUS
}

/**
 * Event sent from JavaScript when finalizeWindow() is called.
 * Indicates the window is locked and ready for reading.
 * 
 * This type is provided for structured event handling. The current
 * implementation of AndroidBridge.onWindowFinalized receives just the
 * pageCount as an integer parameter. When integrating with the new
 * Window Communication API via loadWindow(), use this structured type.
 * 
 * @property windowIndex The window that was finalized
 * @property pageCount Total pages in the window
 */
data class WindowFinalizedEvent(
    val windowIndex: WindowIndex,
    val pageCount: Int
)

/**
 * Error event sent from JavaScript when window loading fails.
 * Sent via AndroidBridge.onWindowLoadError callback.
 * 
 * @property windowIndex The window that failed to load
 * @property errorMessage Human-readable error message
 * @property errorType Type of error for programmatic handling
 */
data class WindowLoadError(
    val windowIndex: WindowIndex,
    val errorMessage: String,
    val errorType: String
) {
    companion object {
        const val ERROR_LOAD_FAILED = "LOAD_FAILED"
        const val ERROR_NOT_READY = "NOT_READY"
        const val ERROR_INVALID_DESCRIPTOR = "INVALID_DESCRIPTOR"
    }
}
