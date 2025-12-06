# Minimal Paginator Bridge - Phase 3 API

**Refactored**: November 2025  
**Status**: Complete - Ready for Phase 3 integration

This document describes the refactored `WebViewPaginatorBridge` that communicates with the minimal paginator JavaScript API.

## Overview

**Phase 3** introduces the **minimal paginator** - a simplified, focused JavaScript implementation that:

✅ **Handles**:
- Page layout via CSS columns
- In-window navigation (`goToPage`, `getCurrentPage`)
- Boundary detection (next/prev window triggers)
- **Character offset tracking** (bookmarks & progress)

❌ **Does NOT handle**:
- Chapter streaming/management (moved to Conveyor Belt)
- Window transitions
- TOC navigation
- Window configuration

This separation of concerns enables:
- **Smaller, testable JS** (~500 lines vs 3000+)
- **Clearer responsibilities** (paginator handles pagination, Conveyor handles windows)
- **Faster iteration** on each component
- **Robust character offsets** for bookmarks/progress

## Core API

### Configuration

```kotlin
// Configure before initializing content
fun configure(webView: WebView, config: PaginatorConfig)
```

Sets:
- `mode`: "window" or "chapter"
- `windowIndex`: Current window number (for logging)

**Example:**
```kotlin
val config = PaginatorConfig(
    mode = PaginatorMode.WINDOW,
    windowIndex = 5
)
bridge.configure(webView, config)
```

### Initialization & Readiness

```kotlin
// Check if paginator is ready for operations
suspend fun isReady(webView: WebView): Boolean

// Initialize with pre-wrapped HTML content
fun initialize(webView: WebView, htmlContent: String)
```

**Example:**
```kotlin
val ready = bridge.isReady(webView)
if (ready) {
    // Safe to call navigation methods
}
```

### Page Navigation

```kotlin
// Get total pages in current window
suspend fun getPageCount(webView: WebView): Int

// Get current page index (0-indexed)
suspend fun getCurrentPage(webView: WebView): Int

// Navigate to specific page
fun goToPage(webView: WebView, index: Int, smooth: Boolean = false)

// Next/Previous page
fun nextPage(webView: WebView)
fun prevPage(webView: WebView)
```

**Example:**
```kotlin
val totalPages = bridge.getPageCount(webView)  // 45
val currentPage = bridge.getCurrentPage(webView)  // 12
val progress = currentPage.toFloat() / totalPages  // 0.267 (26.7%)

bridge.goToPage(webView, 20, smooth = true)  // Jump to page 20 with animation
```

### Character Offset API ⭐ NEW

These are the **most important new additions** for Phase 3:

```kotlin
// Get character offset at start of any page
suspend fun getCharacterOffsetForPage(webView: WebView, pageIndex: Int): Int

// Navigate to page containing a specific character offset
fun goToPageWithCharacterOffset(webView: WebView, offset: Int)
```

**Why character offsets matter:**

When a user changes font size:
- Page indices **CHANGE** (e.g., page 12 becomes page 14)
- Character offsets **STAY THE SAME** (still at position 5034 in the text)

**Bookmarks use character offsets:**
```kotlin
// Saving a bookmark
val charOffset = bridge.getCharacterOffsetForPage(webView, currentPage)
bookmark.charOffset = charOffset  // Stable across font changes

// Restoring after font size change
bridge.setFontSize(webView, newSize)  // Pages get recalculated
bridge.goToPageWithCharacterOffset(webView, bookmark.charOffset)  // Still precise!
```

### Display Configuration

```kotlin
// Set font size and trigger reflow
fun setFontSize(webView: WebView, px: Int)
```

**Effect:**
1. Changes CSS `font-size` 
2. Recalculates column breaks
3. Updates page count and character offsets
4. Reading position preserved by Conveyor

**Example:**
```kotlin
bridge.setFontSize(webView, 18)  // Changed from 16px to 18px
val newPageCount = bridge.getPageCount(webView)  // May be different!
```

## Internal Helpers

The bridge provides private suspend functions for JavaScript evaluation:

```kotlin
// Evaluate expression, return Int
private suspend fun evaluateInt(webView: WebView, expression: String): Int

// Evaluate expression, return String
private suspend fun evaluateString(webView: WebView, expression: String): String

// Evaluate expression, return Boolean
private suspend fun evaluateBoolean(webView: WebView, expression: String): Boolean
```

These handle:
- Main thread dispatch via `Handler`
- Coroutine suspension/resumption
- Quote stripping
- Error logging

**All JavaScript evaluation goes through these helpers** for consistency and safety.

## Complete Method Reference

| Method | Type | Purpose |
|--------|------|---------|
| `isReady()` | Suspend | Check if paginator is initialized |
| `configure()` | Sync | Set mode and indices |
| `initialize()` | Sync | Load HTML into paginator |
| `getPageCount()` | Suspend | Get total pages in window |
| `getCurrentPage()` | Suspend | Get current page index |
| `goToPage()` | Sync | Jump to specific page |
| `nextPage()` | Sync | Go to next page |
| `prevPage()` | Sync | Go to previous page |
| `setFontSize()` | Sync | Change font size, reflow pages |
| `getCharacterOffsetForPage()` | Suspend | Get char offset for a page |
| `goToPageWithCharacterOffset()` | Sync | Jump to page by char offset |

## Usage Pattern: Reading Session

```kotlin
// 1. Start reading a new window
val config = PaginatorConfig(PaginatorMode.WINDOW, windowIndex = 5)
bridge.configure(webView, config)

// 2. Load window content (HTML pre-wrapped with <section> tags)
bridge.initialize(webView, windowHtml)

// 3. Wait for ready
while (!bridge.isReady(webView)) {
    delay(100)
}

// 4. Restore reading position (if exists)
if (bookmark != null) {
    bridge.goToPageWithCharacterOffset(webView, bookmark.charOffset)
}

// 5. During reading - get current position
val currentPage = bridge.getCurrentPage(webView)
val totalPages = bridge.getPageCount(webView)
updateProgressBar(currentPage, totalPages)

// 6. User navigation
bridge.nextPage(webView)  // Page forward
bridge.prevPage(webView)  // Page back

// 7. User settings - font size change
bridge.setFontSize(webView, 18)
// Pages recalculated automatically

// 8. Save bookmark (using character offset for stability)
val charOffset = bridge.getCharacterOffsetForPage(webView, currentPage)
saveBoundmark(charOffset)
```

## Error Handling

All methods have safe error handling:

**Suspend functions** that fail:
- Log error
- Return safe default (0, -1, empty list, etc.)
- Never throw

**Sync functions** that fail:
- Log error  
- Silently do nothing (fire-and-forget)
- No user-visible impact

**Example:**
```kotlin
val count = bridge.getPageCount(webView)
// Returns -1 if error, never throws

if (count == -1) {
    // Handle gracefully
    showRetryUI()
} else {
    updateUI(count)
}
```

## Refactoring Summary

**Removed (moved to Conveyor Belt):**
- `appendChapter()` - Conveyor streams chapters
- `prependChapter()` - Conveyor manages sliding window
- `jumpToChapter()` - Conveyor navigates by chapter
- `getChapterBoundaries()` - Conveyor calculates boundaries
- `loadWindow()` - Conveyor coordinates window transitions
- `finalizeWindow()` - Conveyor manages window lifecycle
- `setInitialChapter()` - Conveyor sets chapter context
- `clearAllSegments()` - Conveyor manages segments
- 15+ other legacy methods

**Kept (core pagination only):**
- Navigation: `goToPage()`, `nextPage()`, `prevPage()`
- State: `getCurrentPage()`, `getPageCount()`, `isReady()`
- Display: `setFontSize()`
- **NEW**: Character offset API for bookmarks/progress

**Result:** From 700 lines down to 335 lines - 52% reduction ✅

## Integration Checklist

- [x] Bridge renamed from `inpagePaginator` to `minimalPaginator`
- [x] All legacy methods removed
- [x] Character offset APIs added
- [x] Documentation updated
- [ ] JavaScript minimal paginator implemented
- [ ] Conveyor Belt system updated
- [ ] ReaderPageFragment tests updated
- [ ] Integration tests passing

## See Also

- `docs/complete/PAGINATOR_AUDIT_PHASE_1.md` - Full API audit
- `app/src/main/assets/minimal_paginator.js` - JavaScript implementation
- `docs/implemented/CONVEYOR_BELT_VERIFIED.md` - Window management
- `docs/complete/STABLE_WINDOW_MODEL.md` - Reading window architecture

---

**Next Step**: Implement minimal paginator JavaScript and integrate with Conveyor Belt.
