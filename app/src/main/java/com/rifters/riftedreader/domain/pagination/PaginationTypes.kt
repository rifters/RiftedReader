package com.rifters.riftedreader.domain.pagination

/**
 * Pagination type aliases and documentation for the RiftedReader pagination system.
 * 
 * This file defines the core concepts used throughout the pagination architecture to
 * distinguish between different levels of indexing:
 * 
 * - **Window**: A contiguous subset of chapters wrapped into a single HTML document
 *   and loaded into one WebView instance (one ViewPager2 page).
 * 
 * - **Chapter**: A logical chapter in the book, as defined by the book's structure
 *   (e.g., EPUB spine items).
 * 
 * - **In-Page Index**: A slice of the scrollable content within a window, created by
 *   the CSS column-based pagination in the WebView.
 * 
 * ## Pagination Modes
 * 
 * ### CHAPTER_BASED Mode
 * In this mode:
 * - Each ViewPager2 page contains exactly one chapter
 * - WindowIndex == ChapterIndex
 * - The WebView paginator operates in "chapter mode" on a single section
 * 
 * ### CONTINUOUS Mode (Sliding Window)
 * In this mode:
 * - Each ViewPager2 page contains multiple chapters (a "window")
 * - WindowIndex != ChapterIndex (a window can span chapters 84-88, for example)
 * - The WebView paginator operates in "window mode" on the full document
 * - Multiple `<section data-chapter-index="X">` elements exist in one HTML document
 * 
 * ## DOM Structure
 * 
 * ### Chapter-Based Mode
 * ```html
 * <body>
 *   <section data-chapter-index="5">
 *     <!-- Single chapter content -->
 *   </section>
 * </body>
 * ```
 * 
 * ### Continuous Mode (Window)
 * ```html
 * <body>
 *   <div id="window-root" data-window-index="190">
 *     <section data-chapter-index="84">...</section>
 *     <section data-chapter-index="85">...</section>
 *     <section data-chapter-index="86">...</section>
 *   </div>
 * </body>
 * ```
 */

/**
 * Index of a ViewPager2 page (WebView instance).
 * 
 * In CHAPTER_BASED mode: WindowIndex == ChapterIndex
 * In CONTINUOUS mode: WindowIndex identifies which sliding window (e.g., 0, 1, 2...)
 * 
 * Example: Window 190 might contain chapters 84-88.
 */
typealias WindowIndex = Int

/**
 * Logical chapter index in the book (0-based).
 * 
 * Corresponds to the chapter's position in the book structure (e.g., EPUB spine).
 * Used for:
 * - TOC navigation
 * - TTS chapter tracking
 * - Bookmark chapter references
 * - Progress tracking
 * 
 * In the DOM, this is stored as `data-chapter-index` attribute on `<section>` elements.
 */
typealias ChapterIndex = Int

/**
 * Page index within a single window (0-based).
 * 
 * This is the "sub-page" or "in-page" index created by the CSS column-based
 * pagination within a WebView's content.
 * 
 * For example, if a window has 16 columns (pages), InPageIndex ranges from 0-15.
 * 
 * This index is local to the window and resets to 0 for each new window.
 */
typealias InPageIndex = Int

/**
 * Global page index across the entire book (0-based).
 * 
 * In CONTINUOUS mode, this is a flattened index that spans all chapters and
 * all in-page indices. Used by ContinuousPaginator for absolute positioning.
 * 
 * Example: If chapters 0-2 have 10, 15, and 8 pages respectively:
 * - Chapter 0, page 5 → GlobalPageIndex 5
 * - Chapter 1, page 0 → GlobalPageIndex 10
 * - Chapter 2, page 3 → GlobalPageIndex 28
 * 
 * In CHAPTER_BASED mode, this concept is less relevant as each chapter
 * is independently paginated.
 */
typealias GlobalPageIndex = Int

/**
 * Configuration for initializing the WebView paginator.
 * 
 * This configuration makes explicit which mode the paginator should operate in
 * and what the root element for pagination should be.
 */
data class PaginatorConfig(
    /**
     * Pagination mode: "window" or "chapter"
     * 
     * - "window": Paginate the entire document (multiple chapters)
     * - "chapter": Paginate only a specific chapter section
     */
    val mode: PaginatorMode,
    
    /**
     * Window index (ViewPager2 page index).
     * Always provided, used for logging and debugging.
     */
    val windowIndex: WindowIndex,
    
    /**
     * Chapter index (logical chapter).
     * 
     * In "chapter" mode: Required, identifies which section to paginate
     * In "window" mode: Optional, used for initial positioning within window
     */
    val chapterIndex: ChapterIndex? = null,
    
    /**
     * CSS selector for the pagination root element.
     * 
     * If null, defaults are:
     * - "window" mode: document.body or #window-root if present
     * - "chapter" mode: section[data-chapter-index="{chapterIndex}"]
     */
    val rootSelector: String? = null,
    
    /**
     * Initial in-page index to navigate to after initialization.
     * Defaults to 0 if not specified.
     */
    val initialInPageIndex: InPageIndex = 0
) {
    enum class PaginatorMode {
        /** Paginate entire window (multiple chapters) */
        WINDOW,
        
        /** Paginate single chapter section only */
        CHAPTER;
        
        fun toJsString(): String = when (this) {
            WINDOW -> "window"
            CHAPTER -> "chapter"
        }
    }
}

/**
 * Mapping of chapter start positions within a window.
 * 
 * Used to support TTS, bookmarks, and navigation even when the pagination
 * root is the entire window (not a specific chapter).
 */
data class ChapterOffset(
    /**
     * Chapter index
     */
    val chapterIndex: ChapterIndex,
    
    /**
     * Scroll offset (in pixels) where this chapter starts within the window.
     * For horizontal scrolling, this is scrollLeft; for vertical, scrollTop.
     */
    val scrollOffset: Int,
    
    /**
     * Optional: DOM element ID that can be scrolled to (e.g., "chapter-84")
     */
    val elementId: String? = null
)

/**
 * Get chapter offsets within a window.
 * This function can be called from JavaScript via WebViewPaginatorBridge
 * to build a mapping of chapter positions for TTS and bookmark support.
 */
data class ChapterOffsetMapping(
    val windowIndex: WindowIndex,
    val offsets: List<ChapterOffset>
)
