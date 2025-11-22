# Paginator API Documentation

## Overview

The RiftedReader paginator system enables horizontal pagination of EPUB and text content within a WebView. This document describes the clear separation between window-based and chapter-based pagination modes, along with the terminology and API design.

## Terminology

The paginator system distinguishes between three different types of indices to avoid ambiguity:

### WindowIndex
**Definition**: The index of a window in the ViewPager2.

A **window** is a contiguous subset of chapters that are wrapped together into one HTML document and loaded into one WebView instance. In continuous pagination mode, multiple chapters can exist in a single window.

**Example**: 
- Window 0 might contain chapters 0-2
- Window 1 might contain chapters 3-5

### ChapterIndex  
**Definition**: The logical chapter index in the book.

**Chapters** are the semantic divisions of the book content, typically corresponding to the book's table of contents. Each chapter has a unique index regardless of which window it is rendered in.

**Example**: A book with 10 chapters has chapterIndex values from 0 to 9.

### InPageIndex
**Definition**: The sub-page index within a window.

When horizontal pagination is enabled (using CSS columns), a window can be divided into multiple pages. This index identifies which page within the window is currently visible.

**Example**: If a window has 3 pages due to CSS column pagination, inPageIndex can be 0, 1, or 2.

## Pagination Modes

The paginator operates in one of two explicit modes:

### Window Mode (`PaginatorMode.WINDOW`)

In window mode, pagination spans multiple chapters within a single window. The entire document serves as the scroll root.

**Use case**: Continuous reading mode where users scroll through multiple chapters seamlessly.

**Characteristics**:
- Pagination root: The entire document (typically `#window-root` container)
- Page counts: Include all chapters in the window
- Chapter boundaries: Tracked separately for navigation and TTS

**Configuration**:
```kotlin
PaginatorConfig(
    mode = PaginatorMode.WINDOW,
    windowIndex = pageIndex,
    chapterIndex = null,  // Optional in window mode
    rootSelector = "#window-root"
)
```

### Chapter Mode (`PaginatorMode.CHAPTER`)

In chapter mode, pagination is limited to a single chapter section. A specific section element serves as the scroll root.

**Use case**: Traditional chapter-by-chapter reading where each ViewPager page represents exactly one chapter.

**Characteristics**:
- Pagination root: A specific `section[data-chapter-index]` element
- Page counts: Limited to the single chapter
- Chapter boundaries: Not needed (one chapter per window)

**Configuration**:
```kotlin
PaginatorConfig(
    mode = PaginatorMode.CHAPTER,
    windowIndex = pageIndex,
    chapterIndex = chapterIndex,  // Required in chapter mode
    rootSelector = null  // Will use section[data-chapter-index]
)
```

## DOM Structure

### Window Mode HTML Structure

When using window mode (continuous pagination), the HTML is structured as follows:

```html
<div id="window-root" data-window-index="0">
  <section id="chapter-0" data-chapter-index="0">
    <!-- Chapter 0 content -->
  </section>
  <section id="chapter-1" data-chapter-index="1">
    <!-- Chapter 1 content -->
  </section>
  <section id="chapter-2" data-chapter-index="2">
    <!-- Chapter 2 content -->
  </section>
</div>
```

**Key points**:
- The `#window-root` container wraps all chapter sections
- Each `section` has both an `id` and `data-chapter-index` attribute
- The paginator uses `#window-root` as the pagination root
- Chapter boundaries are preserved for navigation and position tracking

### Chapter Mode HTML Structure

In chapter mode, a single chapter is loaded per window:

```html
<section id="chapter-5" data-chapter-index="5">
  <!-- Chapter 5 content only -->
</section>
```

**Key points**:
- Only one chapter section is present
- The `section` element itself becomes the pagination root
- No window-root container is needed

## JavaScript API

### Configuration

The JavaScript paginator must be configured before initialization using the `configure()` method:

```javascript
window.inpagePaginator.configure({
    mode: 'window',        // 'window' or 'chapter'
    windowIndex: 0,        // ViewPager2 page index
    chapterIndex: null,    // Optional for window mode, required for chapter mode
    rootSelector: '#window-root'  // Optional CSS selector for pagination root
});
```

**Mode behavior**:

**`mode: 'window'`**:
- Paginate across the entire window containing multiple chapters
- Use full document or `#window-root` as pagination root
- Ignore `chapterIndex` for scroll calculations
- Page count includes all chapters in the window

**`mode: 'chapter'`**:
- Paginate within a single chapter section
- Use `section[data-chapter-index]` as pagination root
- Require `chapterIndex` to locate the chapter
- Page count limited to the single chapter

### Core API Methods

```javascript
// Configuration (must be called before init)
window.inpagePaginator.configure(config)

// State queries
window.inpagePaginator.isReady()           // Returns boolean
window.inpagePaginator.getPageCount()      // Returns number of pages
window.inpagePaginator.getCurrentPage()    // Returns current page index (0-based)
window.inpagePaginator.getCurrentChapter() // Returns current chapter index

// Navigation
window.inpagePaginator.goToPage(index, smooth)      // Navigate to specific page
window.inpagePaginator.nextPage()                   // Go to next page
window.inpagePaginator.prevPage()                   // Go to previous page
window.inpagePaginator.jumpToChapter(chapterIndex, smooth)  // Jump to chapter start

// Font size and reflow
window.inpagePaginator.setFontSize(px)              // Change font size (triggers reflow)
window.inpagePaginator.reflow(preservePosition)     // Recalculate pagination

// Chapter streaming (for dynamic content)
window.inpagePaginator.appendChapter(chapterIndex, html)
window.inpagePaginator.prependChapter(chapterIndex, html)
window.inpagePaginator.removeChapter(chapterIndex)
window.inpagePaginator.getLoadedChapters()          // Returns array of loaded chapters

// Position preservation
window.inpagePaginator.createAnchorAroundViewportTop(anchorId)
window.inpagePaginator.scrollToAnchor(anchorId)
```

## Android Bridge (Kotlin)

### WebViewPaginatorBridge

The `WebViewPaginatorBridge` object provides Kotlin methods to interact with the JavaScript paginator:

```kotlin
// Configuration (must be called before paginator initializes)
WebViewPaginatorBridge.configure(webView, paginatorConfig)

// State queries
suspend fun isReady(webView: WebView): Boolean
suspend fun getPageCount(webView: WebView): Int
suspend fun getCurrentPage(webView: WebView): Int
suspend fun getCurrentChapter(webView: WebView): Int

// Navigation
fun goToPage(webView: WebView, index: Int, smooth: Boolean = true)
fun nextPage(webView: WebView)
fun prevPage(webView: WebView)
fun jumpToChapter(webView: WebView, chapterIndex: Int, smooth: Boolean = true)

// Font size and reflow
fun setFontSize(webView: WebView, px: Int)
fun reflow(webView: WebView, preservePosition: Boolean = true)

// Chapter streaming
fun appendChapter(webView: WebView, chapterIndex: Int, html: String)
fun prependChapter(webView: WebView, chapterIndex: Int, html: String)
fun removeChapter(webView: WebView, chapterIndex: Int)
suspend fun getLoadedChapters(webView: WebView): String
```

### PaginatorConfig Data Class

```kotlin
data class PaginatorConfig(
    val mode: PaginatorMode,           // WINDOW or CHAPTER
    val windowIndex: WindowIndex,      // ViewPager2 page index
    val chapterIndex: ChapterIndex? = null,  // Required for CHAPTER mode
    val rootSelector: String? = null   // Optional CSS selector
)

enum class PaginatorMode {
    WINDOW,   // Multi-chapter window mode
    CHAPTER   // Single chapter mode
}
```

## Integration Example

### ReaderPageFragment Configuration

```kotlin
private fun configurePaginator() {
    if (_binding == null || !isWebViewReady) return
    
    val paginatorConfig = when (readerViewModel.paginationMode) {
        PaginationMode.CONTINUOUS -> {
            // Window mode: paginate across multiple chapters
            PaginatorConfig(
                mode = PaginatorMode.WINDOW,
                windowIndex = pageIndex,
                chapterIndex = null,
                rootSelector = "#window-root"
            )
        }
        PaginationMode.CHAPTER_BASED -> {
            // Chapter mode: paginate within a single chapter
            val chapterIndex = resolvedChapterIndex ?: pageIndex
            PaginatorConfig(
                mode = PaginatorMode.CHAPTER,
                windowIndex = pageIndex,
                chapterIndex = chapterIndex,
                rootSelector = null
            )
        }
    }
    
    WebViewPaginatorBridge.configure(binding.pageWebView, paginatorConfig)
}
```

### WebViewClient Integration

```kotlin
webViewClient = object : WebViewClient() {
    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        
        // Configure paginator before it initializes
        configurePaginator()
        
        // Set font size (triggers initialization and reflow)
        WebViewPaginatorBridge.setFontSize(webView, textSizePx)
        
        // Wait for paginator ready, then restore position...
    }
}
```

## Chapter Boundaries and Navigation

### Tracking Chapter Boundaries in Window Mode

Even when using window mode (where the entire document is the scroll root), the system maintains information about chapter boundaries for:

- **TTS navigation**: Jumping to the start of a specific chapter
- **Bookmarks**: Saving position with chapter context
- **Progress tracking**: Knowing which chapter the user is viewing

The `ChapterBoundary` data class captures this information:

```kotlin
data class ChapterBoundary(
    val chapterIndex: ChapterIndex,    // Logical chapter index
    val startOffset: Int,              // Starting scroll offset in pixels
    val elementId: String? = null      // Optional element ID for navigation
)
```

### getCurrentChapter() Behavior

The `getCurrentChapter()` method determines which chapter is currently visible based on:

1. **Viewport center position**: Uses `scrollLeft + pageWidth/2` instead of scroll edge
2. **Section boundaries**: Checks which section contains the viewport center
3. **Absolute positioning**: Uses `currentScrollLeft + segmentRect.left` for accurate detection

This ensures reliable chapter detection even when multiple chapters are visible simultaneously.

## Pagination Root Selection

The pagination root determines which DOM element's scroll height defines the total paginated content. The system selects the root as follows:

### Window Mode Root Selection
1. If `rootSelector` is provided (e.g., `"#window-root"`), use that element
2. Otherwise, use `document.body`
3. The root encompasses all chapter sections

### Chapter Mode Root Selection
1. If `rootSelector` is provided, use that element
2. Otherwise, select `section[data-chapter-index="${chapterIndex}"]`
3. The root is limited to a single chapter section

### Why This Matters

Incorrect root selection was the source of the original "TOC pagination bug":
- The paginator was treating the first `section` (TOC) as the root
- This limited pagination to only the TOC content
- Even though additional chapters existed in the DOM, they were ignored

With explicit configuration, the system now:
- Uses `#window-root` as root in window mode → includes all chapters
- Uses the specific chapter section in chapter mode → limits to one chapter
- Makes the distinction explicit and debuggable

## Error Handling and Edge Cases

### Missing Configuration
If `configure()` is not called before initialization:
- The paginator falls back to default behavior (window mode, windowIndex=0)
- A warning is logged to the console
- The system attempts to detect pre-wrapped sections automatically

### Invalid Mode
If an invalid mode is provided:
- An error is logged to the console
- Configuration is rejected
- The paginator uses previous or default configuration

### Chapter Mode Without chapterIndex
If chapter mode is configured without a `chapterIndex`:
- An error is logged to the console
- Configuration is rejected
- The paginator may fall back to window mode

### Pre-wrapped vs. Non-wrapped Content
The paginator detects pre-wrapped content automatically:
- If `section[data-chapter-index]` elements exist, they are preserved
- If no sections exist, content is wrapped in a single section
- The chapter index for wrapping respects the configuration

## Performance Considerations

### Window Mode Performance
- **Memory**: Higher (multiple chapters in DOM)
- **Initial load**: Slower (more content to parse and render)
- **Navigation**: Faster (no page transitions)
- **Reflow**: More expensive (larger DOM)

### Chapter Mode Performance
- **Memory**: Lower (single chapter in DOM)
- **Initial load**: Faster (less content)
- **Navigation**: Slower (ViewPager transitions)
- **Reflow**: Less expensive (smaller DOM)

### Recommendations
- Use **window mode** for books with short chapters (better reading flow)
- Use **chapter mode** for books with very long chapters (memory efficiency)
- Limit window size to 3-5 chapters to balance memory and UX

## Debugging

### Logging

The paginator logs configuration and state changes:

```
inpage_paginator: [CONFIG] Configured with mode=window, windowIndex=0, chapterIndex=null, rootSelector=#window-root
inpage_paginator: [CONFIG] Found 3 pre-wrapped chapter sections
inpage_paginator: [CONFIG] Pre-wrapped chapters: 0, 1, 2
```

### Common Issues

**Issue**: Pagination stuck on first chapter (TOC bug)
- **Cause**: Paginator not configured, using first section as root
- **Solution**: Call `configure()` with `mode: 'window'` and `rootSelector: '#window-root'`

**Issue**: Chapter navigation not working
- **Cause**: Using chapter mode with window-structured HTML
- **Solution**: Use window mode for multi-chapter HTML

**Issue**: Page count incorrect
- **Cause**: Pagination root not matching actual content
- **Solution**: Verify `rootSelector` matches DOM structure

## Future Enhancements

Potential improvements to the paginator API:

1. **Automatic mode detection**: Infer mode from DOM structure
2. **Dynamic mode switching**: Switch between window/chapter modes without reload
3. **Chapter boundary reporting**: Return chapter boundaries with pagination results
4. **Scroll percentage API**: Get/set position as percentage of window
5. **Page transition callbacks**: Notify when crossing chapter boundaries

## See Also

- `docs/complete/SLIDING_WINDOW_PAGINATION.md` - Overall sliding window architecture
- `docs/complete/ARCHITECTURE.md` - Full system architecture
- `docs/implemented/SLIDING_WINDOW_PAGINATION_STATUS.md` - Implementation status

## Changelog

### 2025-11-22
- Initial version documenting explicit window vs. chapter mode separation
- Added configuration API with `PaginatorConfig`
- Documented `#window-root` container structure
- Clarified pagination root selection logic
