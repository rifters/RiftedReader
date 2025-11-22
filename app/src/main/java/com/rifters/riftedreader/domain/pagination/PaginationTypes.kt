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
