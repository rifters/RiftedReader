# Paginator API Reference

This document describes the RiftedReader pagination system and its API for clearly distinguishing between windows, chapters, and in-page indices.

## Table of Contents

- [Core Concepts](#core-concepts)
- [Pagination Modes](#pagination-modes)
- [API Reference](#api-reference)
- [Configuration Examples](#configuration-examples)
- [Migration Guide](#migration-guide)

## Core Concepts

The RiftedReader pagination system operates on three distinct levels:

### 1. Window (WebView Instance)

A **window** is a ViewPager2 page that contains one WebView instance. In continuous pagination mode, a window can contain multiple chapters wrapped into a single HTML document.

- **Identified by:** `WindowIndex` (Int)
- **Scope:** ViewPager2 page level
- **In CHAPTER_BASED mode:** One window = one chapter
- **In CONTINUOUS mode:** One window = multiple chapters (sliding window)

**Example:** Window 190 might contain chapters 84-88.

### 2. Chapter (Logical Book Chapter)

A **chapter** is a logical division of the book content, as defined by the book's structure (e.g., EPUB spine items, TXT sections).

- **Identified by:** `ChapterIndex` (Int, 0-based)
- **Scope:** Book structure level
- **Used for:** TOC navigation, TTS tracking, bookmarks, progress tracking
- **In DOM:** Represented as `<section data-chapter-index="X">`

### 3. In-Page Index (Sub-Page)

An **in-page index** is a page slice within a window's continuous content, created by CSS column-based pagination.

- **Identified by:** `InPageIndex` (Int, 0-based)
- **Scope:** Within a single window
- **Resets:** To 0 for each new window
- **Example:** If a window has 16 columns, InPageIndex ranges from 0-15

### 4. Global Page Index

A **global page index** is a flattened index spanning all chapters and pages across the entire book.

- **Identified by:** `GlobalPageIndex` (Int, 0-based)
- **Scope:** Entire book
- **Used in:** CONTINUOUS mode by `ContinuousPaginator`
- **Example:** If chapters 0-2 have 10, 15, and 8 pages:
  - Chapter 0, page 5 → GlobalPageIndex 5
  - Chapter 1, page 0 → GlobalPageIndex 10
  - Chapter 2, page 3 → GlobalPageIndex 28

## Pagination Modes

### CHAPTER_BASED Mode (Traditional)

In this mode:
- Each ViewPager2 page contains **one chapter**
- `WindowIndex == ChapterIndex`
- The WebView paginator operates on a single `<section>` element
- Simpler mental model, familiar to users

**DOM Structure:**
```html
<body>
  <section data-chapter-index="5">
    <!-- Single chapter content -->
  </section>
</body>
```

**Configuration:**
```kotlin
PaginatorConfig(
    mode = PaginatorConfig.PaginatorMode.CHAPTER,
    windowIndex = 5,
    chapterIndex = 5,
    rootSelector = null // Uses section[data-chapter-index="5"]
)
```

### CONTINUOUS Mode (Sliding Window)

In this mode:
- Each ViewPager2 page contains **multiple chapters** (a window)
- `WindowIndex != ChapterIndex`
- The WebView paginator operates on the full document
- Enables seamless reading across chapter boundaries
- Better for immersive reading experience

**DOM Structure:**
```html
<body>
  <div id="window-root" data-window-index="190">
    <section data-chapter-index="84">...</section>
    <section data-chapter-index="85">...</section>
    <section data-chapter-index="86">...</section>
    <section data-chapter-index="87">...</section>
    <section data-chapter-index="88">...</section>
  </div>
</body>
```

**Configuration:**
```kotlin
PaginatorConfig(
    mode = PaginatorConfig.PaginatorMode.WINDOW,
    windowIndex = 190,
    chapterIndex = 85, // Optional: for initial positioning
    rootSelector = null // Uses document.body or #window-root
)
```

## API Reference

### Type Aliases

Defined in `PaginationTypes.kt`:

```kotlin
typealias WindowIndex = Int      // ViewPager2 page index
typealias ChapterIndex = Int     // Logical chapter (0-based)
typealias InPageIndex = Int      // Page within window (0-based)
typealias GlobalPageIndex = Int  // Flattened book-wide index
```

### PaginatorConfig

Configuration object for initializing the WebView paginator:

```kotlin
data class PaginatorConfig(
    val mode: PaginatorMode,              // WINDOW or CHAPTER
    val windowIndex: WindowIndex,         // ViewPager page index
    val chapterIndex: ChapterIndex? = null, // Required for CHAPTER mode
    val rootSelector: String? = null,     // Optional CSS selector
    val initialInPageIndex: InPageIndex = 0 // Initial page to show
) {
    enum class PaginatorMode {
        WINDOW,   // Paginate full document (multi-chapter)
        CHAPTER   // Paginate single chapter section
    }
}
```

### WebViewPaginatorBridge

Bridge for communicating with the JavaScript paginator:

#### configure()

Configure the paginator before initialization:

```kotlin
fun configure(webView: WebView, config: PaginatorConfig)
```

**When to call:** Before page content loads, or immediately after load but before any pagination operations.

**Example:**
```kotlin
val config = PaginatorConfig(
    mode = if (paginationMode == PaginationMode.CONTINUOUS) {
        PaginatorConfig.PaginatorMode.WINDOW
    } else {
        PaginatorConfig.PaginatorMode.CHAPTER
    },
    windowIndex = pageIndex,
    chapterIndex = resolvedChapterIndex,
    initialInPageIndex = targetInPageIndex
)

WebViewPaginatorBridge.configure(webView, config)
```

#### Other Methods

All existing methods remain unchanged:

- `suspend fun isReady(webView: WebView): Boolean`
- `suspend fun getPageCount(webView: WebView): Int`
- `suspend fun getCurrentPage(webView: WebView): Int`
- `fun goToPage(webView: WebView, index: Int, smooth: Boolean = true)`
- `fun nextPage(webView: WebView)`
- `fun prevPage(webView: WebView)`
- etc.

### JavaScript API

The JavaScript paginator (`inpage_paginator.js`) now exposes:

#### configure()

```javascript
window.inpagePaginator.configure({
    mode: "window" | "chapter",
    windowIndex: number,
    chapterIndex: number | null,
    rootSelector: string | null,
    initialInPageIndex: number
})
```

**Behavior:**

- **mode: "window"**
  - Paginate the entire document
  - Preserve all `<section data-chapter-index>` elements
  - Use full document for scroll calculations
  - Best for CONTINUOUS pagination mode

- **mode: "chapter"**
  - Extract and paginate only the specified chapter section
  - Remove other sections from DOM
  - Use single section for scroll calculations
  - Best for CHAPTER_BASED pagination mode

#### getConfig()

Get the current configuration:

```javascript
const config = window.inpagePaginator.getConfig();
console.log(config.mode, config.windowIndex, config.chapterIndex);
```

## Configuration Examples

### Example 1: Chapter-Based Mode (Traditional)

```kotlin
// In ReaderPageFragment
val config = PaginatorConfig(
    mode = PaginatorConfig.PaginatorMode.CHAPTER,
    windowIndex = pageIndex,
    chapterIndex = pageIndex, // Same as windowIndex
    rootSelector = null,
    initialInPageIndex = 0
)

// Configure before loading content
WebViewPaginatorBridge.configure(binding.pageWebView, config)

// Then load content
renderBaseContent()
```

### Example 2: Continuous Mode (Sliding Window)

```kotlin
// In ReaderPageFragment with continuous pagination
val config = PaginatorConfig(
    mode = PaginatorConfig.PaginatorMode.WINDOW,
    windowIndex = pageIndex, // e.g., 190
    chapterIndex = targetChapterIndex, // e.g., 85 (for positioning)
    rootSelector = "#window-root", // Optional
    initialInPageIndex = targetInPageIndex // e.g., 5
)

// Configure before loading content
WebViewPaginatorBridge.configure(binding.pageWebView, config)

// Then load window HTML (multiple chapters)
renderWindowContent()
```

### Example 3: Navigating to a Specific Chapter in Window

```kotlin
// User taps TOC entry for chapter 86 while viewing window 190 (chapters 84-88)
val config = PaginatorConfig(
    mode = PaginatorConfig.PaginatorMode.WINDOW,
    windowIndex = 190,
    chapterIndex = 86, // Chapter to jump to
    rootSelector = null,
    initialInPageIndex = 0 // Start at beginning of chapter
)

WebViewPaginatorBridge.configure(binding.pageWebView, config)

// After page loads, the paginator will position at chapter 86
```

## Migration Guide

### For Existing Code

The new API is **backward compatible**. If you don't call `configure()`, the paginator defaults to:

```javascript
{
    mode: "window",  // Compatible with existing sliding-window implementation
    windowIndex: 0,
    chapterIndex: null,
    rootSelector: null,
    initialInPageIndex: 0
}
```

### Recommended Updates

1. **Explicitly configure mode:**

   ```kotlin
   // Old (implicit)
   // Just loaded content, paginator guessed the mode
   
   // New (explicit)
   val config = PaginatorConfig(
       mode = if (paginationMode == PaginationMode.CONTINUOUS) 
           PaginatorConfig.PaginatorMode.WINDOW 
       else 
           PaginatorConfig.PaginatorMode.CHAPTER,
       windowIndex = pageIndex,
       chapterIndex = resolvedChapterIndex
   )
   WebViewPaginatorBridge.configure(binding.pageWebView, config)
   ```

2. **Use type aliases for clarity:**

   ```kotlin
   // Old
   val pageIndex: Int = arguments.getInt(ARG_PAGE_INDEX)
   val currentChapter: Int = getCurrentChapter()
   
   // New
   val windowIndex: WindowIndex = arguments.getInt(ARG_PAGE_INDEX)
   val chapterIndex: ChapterIndex = getCurrentChapter()
   val inPageIndex: InPageIndex = getCurrentPage()
   ```

3. **Update comments and variable names:**

   ```kotlin
   // Old
   private val pageIndex: Int // Ambiguous: window? chapter? in-page?
   
   // New
   private val windowIndex: WindowIndex // Clear: ViewPager page
   private val currentChapterIndex: ChapterIndex // Clear: logical chapter
   private val currentInPageIndex: InPageIndex // Clear: page within window
   ```

## Benefits

### 1. Prevents Bugs

The old implicit model led to bugs like:
- Pagination stuck on TOC when multiple chapters were present
- Conflating `pageIndex` with `chapterIndex`
- Incorrect scroll root selection

The explicit API makes it impossible to make these mistakes.

### 2. Better Code Clarity

```kotlin
// Before: What does this mean?
val index = getPageIndex()

// After: Crystal clear
val windowIndex: WindowIndex = getWindowIndex()
val chapterIndex: ChapterIndex = getChapterIndex()
val inPageIndex: InPageIndex = getCurrentInPageIndex()
```

### 3. Foundation for Advanced Features

The explicit API enables:
- **TTS navigation:** Know exactly which chapter is being read
- **Bookmarks:** Save precise position (chapter + in-page offset)
- **Progress tracking:** Accurate chapter-level and page-level progress
- **TOC sync:** Highlight current chapter even in window mode

### 4. Easier Debugging

Logs now show:
```
[DEBUG] Paginator configured: mode=window, windowIndex=190, chapterIndex=85
[DEBUG] Current position: window=190, chapter=86, inPage=5
```

Instead of:
```
[DEBUG] Page index: 190
[DEBUG] Current page: 5
```

## Best Practices

1. **Always configure explicitly:**
   Call `configure()` before initializing pagination.

2. **Use type aliases consistently:**
   Prefer `WindowIndex`, `ChapterIndex`, `InPageIndex` over raw `Int`.

3. **Log with context:**
   ```kotlin
   AppLogger.d(TAG, "Navigate: window=$windowIndex, chapter=$chapterIndex, inPage=$inPageIndex")
   ```

4. **Validate indices:**
   ```kotlin
   require(windowIndex >= 0) { "WindowIndex must be non-negative" }
   require(chapterIndex in 0 until totalChapters) { "ChapterIndex out of range" }
   ```

5. **Document mode assumptions:**
   ```kotlin
   /**
    * Load window HTML for continuous pagination.
    * @param windowIndex The window to load (may contain multiple chapters)
    */
   suspend fun loadWindow(windowIndex: WindowIndex) { ... }
   ```

## Troubleshooting

### Issue: Pagination stuck on first chapter

**Cause:** Paginator configured in CHAPTER mode when HTML contains multiple chapters.

**Fix:** Configure in WINDOW mode:
```kotlin
PaginatorConfig(mode = PaginatorConfig.PaginatorMode.WINDOW, ...)
```

### Issue: Wrong chapter highlighted in TOC

**Cause:** Using `windowIndex` instead of `chapterIndex` for TOC sync.

**Fix:** Use `getCurrentChapter()` from JavaScript:
```kotlin
val chapterIndex = WebViewPaginatorBridge.evaluateInt(webView, 
    "window.inpagePaginator.getCurrentChapter()")
```

### Issue: Position lost after font size change

**Cause:** Not preserving both chapter and in-page index.

**Fix:** Save and restore complete position:
```kotlin
val chapterIndex = getCurrentChapter()
val inPageIndex = getCurrentInPageIndex()
// ... change font size ...
jumpToChapter(chapterIndex)
goToPage(inPageIndex)
```

## See Also

- [ARCHITECTURE.md](ARCHITECTURE.md) - Overall system architecture
- [SLIDING_WINDOW_PAGINATION_STATUS.md](../implemented/SLIDING_WINDOW_PAGINATION_STATUS.md) - Sliding window implementation
- [PaginationTypes.kt](../../app/src/main/java/com/rifters/riftedreader/domain/pagination/PaginationTypes.kt) - Type definitions
