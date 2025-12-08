# FlexPaginator Quick Reference

## Overview

Quick reference guide for developers working with FlexPaginator. Contains API summaries, common scenarios, and code snippets.

## Key Components

### 1. FlexPaginator.kt

**Purpose**: Assembles window HTML with `<section>` tags

```kotlin
class FlexPaginator(private val bookParser: BookParser) {
    
    /**
     * Assembles HTML for a window of chapters.
     * 
     * @param windowIndex Window index (0-based)
     * @param firstChapter First chapter in window (0-based)
     * @param lastChapter Last chapter in window (inclusive)
     * @return WindowData with wrapped HTML
     */
    fun assembleWindow(
        windowIndex: Int,
        firstChapter: Int,
        lastChapter: Int
    ): WindowData
}
```

**Output Structure**:
```html
<div id="window-root" data-window-index="5">
  <section data-chapter="25">Chapter 25 content</section>
  <section data-chapter="26">Chapter 26 content</section>
  <!-- ... -->
</div>
```

### 2. OffscreenSlicingWebView.kt

**Purpose**: Pre-slices content in hidden WebView

```kotlin
class OffscreenSlicingWebView(private val context: Context) {
    
    /**
     * Slices wrapped HTML into viewport-sized pages.
     * 
     * @param html Wrapped HTML from FlexPaginator
     * @param windowIndex Window index
     * @param fontSize Font size in pixels (default: 16)
     * @param lineHeight Line height multiplier (default: 1.5)
     * @param fontFamily Font family name (default: "Serif")
     * @return SliceMetadata with page information
     * @throws TimeoutException if slicing takes >10 seconds
     * @throws WebViewException if WebView initialization fails
     */
    suspend fun sliceWindow(
        html: String,
        windowIndex: Int,
        fontSize: Int = 16,
        lineHeight: Float = 1.5f,
        fontFamily: String = "Serif"
    ): SliceMetadata
}
```

### 3. SliceMetadata.kt

**Purpose**: Contains page metadata for a window

```kotlin
data class PageSlice(
    val page: Int,          // Page index within window (0-based)
    val chapter: Int,       // Chapter index (0-based)
    val startChar: Int,     // Start character offset
    val endChar: Int,       // End character offset
    val heightPx: Int       // Measured height in pixels
)

data class SliceMetadata(
    val windowIndex: Int,
    val totalPages: Int,
    val slices: List<PageSlice>
) {
    /** Validate metadata integrity */
    fun isValid(): Boolean
    
    /** Find page containing specific character offset */
    fun findPageByCharOffset(chapter: Int, offset: Int): PageSlice?
    
    /** Get slice by page index */
    fun getSlice(pageIndex: Int): PageSlice?
    
    /** Filter slices by chapter */
    fun getSlicesForChapter(chapter: Int): List<PageSlice>
}
```

### 4. flex_paginator.js

**Purpose**: JavaScript slicing algorithm

```javascript
// Initialize pagination
function initialize(wrappedHtml)

// Navigation
function getCurrentPageIndex()
function getPageCount()
function goToPage(pageIndex)
function goToPageWithCharOffset(chapter, offset)

// Callbacks (called by JavaScript)
AndroidBridge.onSlicingComplete(metadataJson)
AndroidBridge.onSlicingError(errorMessage)
AndroidBridge.onPageChanged(page, charOffset, pageCount, windowIndex)
AndroidBridge.onReachedStartBoundary(windowIndex)
AndroidBridge.onReachedEndBoundary(windowIndex)
```

## Common Scenarios

### Scenario 1: Create Window (Pre-Slice)

```kotlin
suspend fun createWindow(windowIndex: Int, firstChapter: Int, lastChapter: Int): WindowData {
    // 1. Assemble HTML
    val windowData = flexPaginator.assembleWindow(
        windowIndex = windowIndex,
        firstChapter = firstChapter,
        lastChapter = lastChapter
    )
    
    // 2. Pre-slice offscreen
    val metadata = offscreenSlicingWebView.sliceWindow(
        html = windowData.html,
        windowIndex = windowIndex,
        fontSize = currentFontSize,
        lineHeight = currentLineHeight,
        fontFamily = currentFontFamily
    )
    
    // 3. Cache with metadata
    val completeWindowData = windowData.copy(
        sliceMetadata = metadata
    )
    
    windowBuffer.cache(windowIndex, completeWindowData)
    
    Log.d(TAG, "Window $windowIndex created: ${metadata.totalPages} pages")
    
    return completeWindowData
}
```

### Scenario 2: Display Window

```kotlin
fun displayWindow(windowIndex: Int) {
    // Get cached window
    val windowData = windowBuffer.get(windowIndex)
    
    // Load HTML into WebView
    readerWebView.loadDataWithBaseURL(
        null,
        windowData.html,
        "text/html",
        "utf-8",
        null
    )
    
    // Metadata already cached, display is instant
    Log.d(TAG, "Window $windowIndex displayed (zero latency)")
}
```

### Scenario 3: Navigate to Page

```kotlin
fun navigateToPage(pageIndex: Int) {
    readerWebView.evaluateJavascript(
        "goToPage($pageIndex)",
        null
    )
    
    Log.d(TAG, "Navigated to page $pageIndex")
}
```

### Scenario 4: Save Bookmark

```kotlin
fun saveBookmark(): Bookmark {
    // Get current position
    val windowData = windowBuffer.get(currentWindowIndex)
    val slice = windowData.sliceMetadata?.getSlice(currentPage)
    
    return Bookmark(
        windowIndex = currentWindowIndex,
        page = currentPage,
        chapter = slice?.chapter ?: 0,
        charOffset = slice?.startChar ?: 0
    )
}
```

### Scenario 5: Restore Bookmark

```kotlin
fun restoreBookmark(bookmark: Bookmark) {
    // Navigate to window
    displayWindow(bookmark.windowIndex)
    
    // Get metadata
    val windowData = windowBuffer.get(bookmark.windowIndex)
    val metadata = windowData.sliceMetadata
    
    // Find page by character offset
    val restoredSlice = metadata?.findPageByCharOffset(
        chapter = bookmark.chapter,
        offset = bookmark.charOffset
    )
    
    // Navigate to page
    if (restoredSlice != null) {
        navigateToPage(restoredSlice.page)
    } else {
        navigateToPage(bookmark.page) // Fallback
    }
}
```

### Scenario 6: Re-Slice on Font Size Change

```kotlin
suspend fun resliceOnFontSizeChange(newFontSize: Int) {
    // 1. Show loading overlay
    showLoadingOverlay()
    
    // 2. Save position
    val bookmark = saveBookmark()
    
    // 3. Re-slice all windows in buffer
    val buffer = windowBuffer.getAllWindows()
    buffer.forEachIndexed { index, windowData ->
        // Re-slice
        val newMetadata = offscreenSlicingWebView.sliceWindow(
            html = windowData.html,
            windowIndex = windowData.windowIndex,
            fontSize = newFontSize,
            lineHeight = currentLineHeight,
            fontFamily = currentFontFamily
        )
        
        // Update cache
        windowBuffer.update(
            windowData.windowIndex,
            windowData.copy(sliceMetadata = newMetadata)
        )
        
        // Update progress
        updateProgress(index + 1, buffer.size)
    }
    
    // 4. Restore position
    restoreBookmark(bookmark)
    
    // 5. Dismiss overlay
    dismissLoadingOverlay()
}
```

### Scenario 7: Handle Boundary Event

```kotlin
fun onBoundaryReached(direction: String) {
    when (direction) {
        "NEXT" -> {
            // User reached end of current window
            conveyorBeltSystem.shiftBufferForward()
        }
        "PREVIOUS" -> {
            // User reached start of current window
            conveyorBeltSystem.shiftBufferBackward()
        }
    }
}
```

### Scenario 8: Handle Re-Slice Error

```kotlin
suspend fun resliceWithErrorHandling(windowData: WindowData): Result<SliceMetadata> {
    return try {
        val metadata = offscreenSlicingWebView.sliceWindow(
            html = windowData.html,
            windowIndex = windowData.windowIndex,
            fontSize = currentFontSize,
            lineHeight = currentLineHeight,
            fontFamily = currentFontFamily
        )
        
        Result.success(metadata)
        
    } catch (e: TimeoutException) {
        Log.e(TAG, "Timeout re-slicing window ${windowData.windowIndex}", e)
        Result.failure(e)
        
    } catch (e: WebViewException) {
        Log.e(TAG, "WebView error re-slicing window ${windowData.windowIndex}", e)
        Result.failure(e)
    }
}
```

## Data Flow Diagram

```
┌─────────────────────────────────────────────────────────┐
│ BookParser                                              │
│ • getChapterContent(chapter)                            │
└────────────────────┬────────────────────────────────────┘
                     ↓
┌─────────────────────────────────────────────────────────┐
│ FlexPaginator                                           │
│ • assembleWindow(windowIndex, firstChapter, lastChapter)│
│ • Returns: WindowData with wrapped HTML                 │
└────────────────────┬────────────────────────────────────┘
                     ↓
┌─────────────────────────────────────────────────────────┐
│ OffscreenSlicingWebView                                 │
│ • sliceWindow(html, windowIndex, fontSize, ...)         │
│ • Returns: SliceMetadata                                │
└────────────────────┬────────────────────────────────────┘
                     ↓
┌─────────────────────────────────────────────────────────┐
│ WindowData + SliceMetadata                              │
│ • Cached in WindowBufferManager                         │
└────────────────────┬────────────────────────────────────┘
                     ↓
┌─────────────────────────────────────────────────────────┐
│ ReaderWebView                                           │
│ • loadDataWithBaseURL(html)                             │
│ • Display (zero latency)                                │
└─────────────────────────────────────────────────────────┘
```

## AndroidBridge API Reference

### Kotlin → JavaScript

```kotlin
// Navigate to page
webView.evaluateJavascript("goToPage($pageIndex)", null)

// Get current page
webView.evaluateJavascript("getCurrentPageIndex()") { result ->
    val page = result.toInt()
}

// Get page count
webView.evaluateJavascript("getPageCount()") { result ->
    val count = result.toInt()
}

// Navigate by character offset
webView.evaluateJavascript("goToPageWithCharOffset($chapter, $offset)", null)
```

### JavaScript → Kotlin

```kotlin
@JavascriptInterface
fun onSlicingComplete(metadataJson: String) {
    // Parse JSON and return metadata
}

@JavascriptInterface
fun onSlicingError(errorMessage: String) {
    // Handle error
}

@JavascriptInterface
fun onPageChanged(page: Int, charOffset: Int, pageCount: Int, windowIndex: Int) {
    // Update UI
}

@JavascriptInterface
fun onReachedStartBoundary(windowIndex: Int) {
    // Shift buffer backward
}

@JavascriptInterface
fun onReachedEndBoundary(windowIndex: Int) {
    // Shift buffer forward
}
```

## Configuration

### Font Settings

```kotlin
// Default settings
val DEFAULT_FONT_SIZE = 16 // pixels
val DEFAULT_LINE_HEIGHT = 1.5f // multiplier
val DEFAULT_FONT_FAMILY = "Serif"

// Apply to WebView
webView.settings.apply {
    textZoom = (fontSize / 16f * 100).toInt()
    standardFontFamily = fontFamily
}
```

### Viewport Settings

```kotlin
// Default viewport
val VIEWPORT_HEIGHT = 600 // pixels
val VIEWPORT_WIDTH = 400 // pixels

// Adjust based on device
val density = resources.displayMetrics.density
val viewportHeight = (600 * density).toInt()
```

### Performance Settings

```kotlin
// Timeouts
val SLICING_TIMEOUT = 10_000L // 10 seconds
val WINDOW_CREATION_TIMEOUT = 15_000L // 15 seconds

// Buffer size
val BUFFER_SIZE = 5 // windows

// Memory limits
val MAX_WINDOW_MEMORY = 10 * 1024 * 1024 // 10MB per window
val MAX_BUFFER_MEMORY = 50 * 1024 * 1024 // 50MB total
```

## Logging

### Log Tags

```kotlin
const val TAG = "FlexPaginator"
const val TAG_CONVEYOR = "[CONVEYOR]"
const val TAG_SLICING = "[SLICING]"
const val TAG_RESLICE = "[RESLICE]"
const val TAG_OVERLAY = "[OVERLAY]"
const val TAG_ERROR = "[ERROR]"
```

### Log Patterns

```kotlin
// Window creation
Log.d(TAG, "$TAG_CONVEYOR Creating window $windowIndex (chapters $firstChapter-$lastChapter)")

// Slicing start
Log.d(TAG, "$TAG_SLICING Starting slice for window $windowIndex")

// Slicing complete
Log.d(TAG, "$TAG_SLICING Window $windowIndex sliced: ${metadata.totalPages} pages in ${duration}ms")

// Re-slicing
Log.d(TAG, "$TAG_RESLICE Re-slicing window $windowIndex with font size $newFontSize")

// Error
Log.e(TAG, "$TAG_ERROR Failed to slice window $windowIndex", exception)
```

## Testing Helpers

### Mock Data

```kotlin
fun createMockSliceMetadata(windowIndex: Int, pages: Int): SliceMetadata {
    val slices = (0 until pages).map { page ->
        PageSlice(
            page = page,
            chapter = 25 + (page / 10),
            startChar = page * 500,
            endChar = (page + 1) * 500,
            heightPx = 600
        )
    }
    
    return SliceMetadata(
        windowIndex = windowIndex,
        totalPages = pages,
        slices = slices
    )
}

fun createMockWindowData(windowIndex: Int): WindowData {
    return WindowData(
        windowIndex = windowIndex,
        html = "<div>Mock content</div>",
        sliceMetadata = createMockSliceMetadata(windowIndex, 30)
    )
}
```

### Test Utilities

```kotlin
// Wait for slicing to complete
suspend fun waitForSlicingComplete(timeout: Long = 10_000L) {
    withTimeout(timeout) {
        while (!isSlicingComplete) {
            delay(100)
        }
    }
}

// Assert metadata validity
fun assertMetadataValid(metadata: SliceMetadata) {
    assertTrue(metadata.isValid())
    assertTrue(metadata.totalPages > 0)
    assertEquals(metadata.totalPages, metadata.slices.size)
}

// Assert position restored
fun assertPositionRestored(expected: Bookmark, actual: Bookmark) {
    assertEquals(expected.windowIndex, actual.windowIndex)
    assertEquals(expected.chapter, actual.chapter)
    // Allow some tolerance for charOffset (page boundaries may shift)
    assertTrue(abs(expected.charOffset - actual.charOffset) < 100)
}
```

## Performance Metrics

### Tracking

```kotlin
// Measure slicing time
val startTime = System.currentTimeMillis()
val metadata = offscreenSlicingWebView.sliceWindow(html, windowIndex)
val duration = System.currentTimeMillis() - startTime

Log.d(TAG, "Slicing time: ${duration}ms (target: <500ms)")

// Track memory usage
val runtime = Runtime.getRuntime()
val usedMemory = runtime.totalMemory() - runtime.freeMemory()
val usedMemoryMB = usedMemory / (1024 * 1024)

Log.d(TAG, "Memory usage: ${usedMemoryMB}MB (target: <50MB)")
```

### Benchmarks

```kotlin
// Benchmark slicing for different book sizes
suspend fun benchmarkSlicing() {
    val smallBook = loadBook("small.epub") // <1MB
    val mediumBook = loadBook("medium.epub") // 1-5MB
    val largeBook = loadBook("large.epub") // 5-10MB
    
    measureSlicingTime(smallBook) // Expected: 100-200ms
    measureSlicingTime(mediumBook) // Expected: 200-400ms
    measureSlicingTime(largeBook) // Expected: 400-800ms
}
```

## Troubleshooting

### Common Issues

**Issue**: Slicing timeout (>10 seconds)
- **Cause**: Very large chapter or slow device
- **Fix**: Increase timeout or optimize JavaScript algorithm
- **Workaround**: Split large chapters into smaller sections

**Issue**: Invalid metadata (isValid() returns false)
- **Cause**: JavaScript error or malformed HTML
- **Fix**: Validate HTML before slicing, add error handling
- **Workaround**: Use minimal_paginator fallback

**Issue**: Position not restored correctly
- **Cause**: Character offset calculation incorrect
- **Fix**: Debug charOffset array in JavaScript
- **Workaround**: Fall back to page index (less accurate)

**Issue**: Memory leak
- **Cause**: OffscreenSlicingWebView not properly released
- **Fix**: Call destroy() on WebView when done
- **Workaround**: Use WebView pooling

### Debug Commands

```kotlin
// Dump current state
fun dumpState() {
    Log.d(TAG, """
        ═══════════════════════════════════════
        FlexPaginator State Dump
        ═══════════════════════════════════════
        Current Window: $currentWindowIndex
        Current Page: $currentPage
        Buffer Size: ${windowBuffer.size()}
        Font Size: $currentFontSize
        Line Height: $currentLineHeight
        Font Family: $currentFontFamily
        Memory Usage: ${getMemoryUsageMB()}MB
        ═══════════════════════════════════════
    """.trimIndent())
}

// Validate all cached windows
fun validateAllWindows() {
    windowBuffer.getAllWindows().forEach { windowData ->
        val isValid = windowData.sliceMetadata?.isValid() ?: false
        Log.d(TAG, "Window ${windowData.windowIndex}: ${if (isValid) "VALID" else "INVALID"}")
    }
}
```

## Migration from Old System

### Feature Flags

```kotlin
// Enable FlexPaginator
val enableFlexPaginator = BuildConfig.DEBUG || 
    readerSettings.enableExperimentalFeatures

if (enableFlexPaginator) {
    // Use FlexPaginator
    flexPaginator.assembleWindow(...)
} else {
    // Use old pagination system
    continuousPaginator.createWindow(...)
}
```

### Gradual Rollout

```kotlin
// Percentage-based rollout
val rolloutPercentage = 10 // Start with 10%
val userId = getCurrentUserId()
val userBucket = userId % 100

val useFlexPaginator = userBucket < rolloutPercentage

if (useFlexPaginator) {
    // New system
} else {
    // Old system
}
```

## Additional Resources

- **Full Documentation**: See FLEX_PAGINATOR_ARCHITECTURE.md
- **Font Size Changes**: See FLEX_PAGINATOR_FONT_SIZE_CHANGES.md
- **Error Recovery**: See FLEX_PAGINATOR_ERROR_RECOVERY.md
- **Settings Lock**: See FLEX_PAGINATOR_SETTINGS_LOCK.md
- **Progress Overlay**: See FLEX_PAGINATOR_PROGRESS_OVERLAY.md
- **Integration Checklist**: See FLEX_PAGINATOR_INTEGRATION_CHECKLIST.md

---

**Document Version**: 1.0  
**Date**: 2025-12-08  
**Status**: Reference Complete ✅  
**Audience**: Developers implementing or maintaining FlexPaginator
