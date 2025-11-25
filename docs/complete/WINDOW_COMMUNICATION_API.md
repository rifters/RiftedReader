# Window Communication API

## Overview

This document defines the pseudo-API for communication between the Android/Kotlin domain layer and the JavaScript (WebView) in-page paginator for managing reading windows in RiftedReader.

The API provides a stable messaging and data protocol for:
- Window handoff between Android and JavaScript
- Position queries and mapping
- Preloading and atomic window switches
- Boundary detection and navigation

## Design Goals

1. **Decouple window transitions from chapter streaming/append logic** - No mutations during active reading
2. **Stable messaging protocol** - Clear contracts for all operations
3. **Accurate page mapping on handoff** - Precise position restoration
4. **Enable preloading** - Background window preparation
5. **Atomic window switches** - Clean transitions without intermediate states

---

## Key Concepts

### WindowDescriptor

The `WindowDescriptor` is the primary data structure sent from Android to JavaScript when loading a new window. It contains all information needed to render and navigate within a window.

```kotlin
/**
 * Descriptor for a reading window sent from Android to JavaScript.
 * Contains all information needed to render and navigate within the window.
 */
data class WindowDescriptor(
    /** Unique identifier for this window */
    val windowIndex: WindowIndex,
    
    /** First chapter index in this window (inclusive) */
    val firstChapterIndex: ChapterIndex,
    
    /** Last chapter index in this window (inclusive) */
    val lastChapterIndex: ChapterIndex,
    
    /** Chapter metadata for each chapter in the window */
    val chapters: List<ChapterDescriptor>,
    
    /** Initial position to navigate to after loading (optional) */
    val entryPosition: EntryPosition? = null,
    
    /** Total pages in the window (if known from previous load, else null) */
    val estimatedPageCount: Int? = null
)

/**
 * Metadata for a single chapter within a window.
 */
data class ChapterDescriptor(
    /** Chapter index in the book */
    val chapterIndex: ChapterIndex,
    
    /** Chapter title (from TOC or inferred) */
    val title: String,
    
    /** Element ID for this chapter in the HTML (e.g., "chapter-5") */
    val elementId: String
)

/**
 * Initial position to navigate to after window loads.
 */
data class EntryPosition(
    /** Target chapter index */
    val chapterIndex: ChapterIndex,
    
    /** Target page within the chapter (0-based) */
    val inPageIndex: InPageIndex = 0,
    
    /** Optional character offset for precise positioning */
    val characterOffset: Int? = null
)
```

### Page Mapping

Page mapping enables accurate position restoration when switching windows:

```kotlin
/**
 * Mapping from global book position to window-local position.
 */
data class PageMappingInfo(
    /** Window index */
    val windowIndex: WindowIndex,
    
    /** Chapter index within the window */
    val chapterIndex: ChapterIndex,
    
    /** Page index within the window (0-based, across all chapters) */
    val windowPageIndex: InPageIndex,
    
    /** Page index within the chapter (0-based) */
    val chapterPageIndex: InPageIndex,
    
    /** Total pages in the window */
    val totalWindowPages: Int,
    
    /** Total pages in the chapter */
    val totalChapterPages: Int
)
```

---

## API Contract

### Android → JavaScript (Commands)

#### loadWindow(windowDescriptor)

Load a complete window with all its chapters.

**JavaScript Implementation:**
```javascript
/**
 * Load a complete window for reading.
 * Called by Android when initializing or switching windows.
 * 
 * @param {Object} descriptor - WindowDescriptor from Android
 * @param {number} descriptor.windowIndex - Window index
 * @param {number} descriptor.firstChapterIndex - First chapter index
 * @param {number} descriptor.lastChapterIndex - Last chapter index
 * @param {Array} descriptor.chapters - Chapter descriptors
 * @param {Object} [descriptor.entryPosition] - Optional entry position
 * 
 * The HTML content is already loaded via loadDataWithBaseURL().
 * This call configures the paginator and navigates to entry position.
 */
function loadWindow(descriptor) {
    // 1. Configure paginator for window mode
    configure({
        mode: 'window',
        windowIndex: descriptor.windowIndex,
        rootSelector: '#window-root'
    });
    
    // 2. Finalize window (lock down for reading)
    finalizeWindow();
    
    // 3. Navigate to entry position if specified
    if (descriptor.entryPosition) {
        jumpToChapter(descriptor.entryPosition.chapterIndex);
        goToPage(descriptor.entryPosition.inPageIndex, false);
    }
    
    // 4. Report ready to Android
    AndroidBridge.onWindowLoaded({
        windowIndex: descriptor.windowIndex,
        pageCount: getPageCount(),
        chapterBoundaries: getChapterBoundaries()
    });
}
```

**Kotlin Bridge:**
```kotlin
fun loadWindow(webView: WebView, descriptor: WindowDescriptor) {
    val descriptorJson = gson.toJson(descriptor)
    val jsCall = "window.inpagePaginator.loadWindow($descriptorJson);"
    mainHandler.post {
        webView.evaluateJavascript(jsCall, null)
    }
}
```

---

#### getPageCount()

Query the total number of pages in the current window.

**JavaScript:**
```javascript
function getPageCount() {
    // Returns total pages across all chapters in the window
    return calculateTotalPages();
}
```

**Kotlin Bridge:**
```kotlin
suspend fun getPageCount(webView: WebView): Int {
    return evaluateInt(webView, "window.inpagePaginator.getPageCount()")
}
```

---

#### goToPage(pageIndex, animate)

Navigate to a specific page within the window.

**JavaScript:**
```javascript
/**
 * Navigate to a specific page.
 * @param {number} pageIndex - Target page (0-based)
 * @param {boolean} animate - Whether to animate the transition
 */
function goToPage(pageIndex, animate) {
    const safeIndex = Math.max(0, Math.min(pageIndex, getPageCount() - 1));
    currentPage = safeIndex;
    scrollToPage(safeIndex, animate);
    
    // Notify Android of page change
    AndroidBridge.onPageChanged({
        page: safeIndex,
        chapter: getCurrentChapter(),
        pageCount: getPageCount()
    });
}
```

**Kotlin Bridge:**
```kotlin
fun goToPage(webView: WebView, pageIndex: Int, animate: Boolean = true) {
    mainHandler.post {
        webView.evaluateJavascript(
            "window.inpagePaginator.goToPage($pageIndex, $animate);",
            null
        )
    }
}
```

---

#### getCurrentPage()

Query the current page index.

**JavaScript:**
```javascript
function getCurrentPage() {
    // Returns the explicitly tracked page (not calculated from scroll)
    return currentPage;
}
```

**Kotlin Bridge:**
```kotlin
suspend fun getCurrentPage(webView: WebView): Int {
    return evaluateInt(webView, "window.inpagePaginator.getCurrentPage()")
}
```

---

#### getCurrentChapter()

Query the current chapter index based on viewport position.

**JavaScript:**
```javascript
function getCurrentChapter() {
    // Uses viewport center to determine current chapter
    const viewportCenter = currentScrollLeft + pageWidth / 2;
    
    for (const chapter of chapters) {
        if (viewportCenter >= chapter.startOffset && 
            viewportCenter < chapter.endOffset) {
            return chapter.index;
        }
    }
    
    return chapters[0]?.index ?? -1;
}
```

**Kotlin Bridge:**
```kotlin
suspend fun getCurrentChapter(webView: WebView): Int {
    return evaluateInt(webView, "window.inpagePaginator.getCurrentChapter()")
}
```

---

#### getChapterBoundaries()

Get page boundaries for all chapters in the window.

**JavaScript:**
```javascript
/**
 * Get chapter boundaries for position mapping.
 * @returns {Array} Array of {chapterIndex, startPage, endPage, pageCount}
 */
function getChapterBoundaries() {
    return chapters.map(chapter => ({
        chapterIndex: chapter.index,
        startPage: calculateStartPage(chapter),
        endPage: calculateEndPage(chapter),
        pageCount: calculateEndPage(chapter) - calculateStartPage(chapter)
    }));
}
```

**Kotlin Bridge:**
```kotlin
suspend fun getChapterBoundaries(webView: WebView): List<ChapterBoundaryInfo> {
    val json = evaluateString(webView, 
        "JSON.stringify(window.inpagePaginator.getChapterBoundaries())")
    return gson.fromJson(json, Array<ChapterBoundaryInfo>::class.java).toList()
}
```

---

#### getPageMappingInfo()

Get detailed position mapping for the current view.

**JavaScript:**
```javascript
/**
 * Get complete page mapping info for current position.
 * @returns {Object} PageMappingInfo structure
 */
function getPageMappingInfo() {
    const currentChapter = getCurrentChapter();
    const boundaries = getChapterBoundaries();
    const chapterBoundary = boundaries.find(b => b.chapterIndex === currentChapter);
    
    return {
        windowIndex: paginatorConfig.windowIndex,
        chapterIndex: currentChapter,
        windowPageIndex: getCurrentPage(),
        chapterPageIndex: getCurrentPage() - chapterBoundary.startPage,
        totalWindowPages: getPageCount(),
        totalChapterPages: chapterBoundary.pageCount
    };
}
```

**Kotlin Bridge:**
```kotlin
suspend fun getPageMappingInfo(webView: WebView): PageMappingInfo {
    val json = evaluateString(webView, 
        "JSON.stringify(window.inpagePaginator.getPageMappingInfo())")
    return gson.fromJson(json, PageMappingInfo::class.java)
}
```

---

### JavaScript → Android (Events)

#### onWindowLoaded

Fired when window is loaded and ready for reading.

**JavaScript:**
```javascript
AndroidBridge.onWindowLoaded({
    windowIndex: windowIndex,
    pageCount: getPageCount(),
    chapterBoundaries: getChapterBoundaries()
});
```

**Kotlin Interface:**
```kotlin
@JavascriptInterface
fun onWindowLoaded(json: String) {
    val result = gson.fromJson(json, WindowLoadedResult::class.java)
    viewModelScope.launch {
        viewModel.onWindowLoaded(result)
    }
}
```

---

#### onPageChanged

Fired when the current page changes (user navigation or programmatic).

**JavaScript:**
```javascript
AndroidBridge.onPageChanged({
    page: currentPage,
    chapter: getCurrentChapter(),
    pageCount: getPageCount()
});
```

**Kotlin Interface:**
```kotlin
@JavascriptInterface
fun onPageChanged(json: String) {
    val event = gson.fromJson(json, PageChangedEvent::class.java)
    viewModelScope.launch {
        viewModel.updatePosition(
            windowIndex = currentWindowIndex,
            chapterIndex = event.chapter,
            inPageIndex = event.page
        )
    }
}
```

---

#### onBoundaryReached

Fired when user reaches the boundary of a window (first or last page).

**JavaScript:**
```javascript
/**
 * Boundary directions:
 * - 'NEXT': Reached last page, should transition to next window
 * - 'PREVIOUS': Reached first page, should transition to previous window
 */
AndroidBridge.onBoundaryReached({
    direction: 'NEXT' | 'PREVIOUS',
    currentPage: currentPage,
    pageCount: getPageCount(),
    currentChapter: getCurrentChapter()
});
```

**Kotlin Interface:**
```kotlin
@JavascriptInterface
fun onBoundaryReached(json: String) {
    val event = gson.fromJson(json, BoundaryReachedEvent::class.java)
    viewModelScope.launch {
        when (event.direction) {
            "NEXT" -> viewModel.requestNextWindow()
            "PREVIOUS" -> viewModel.requestPreviousWindow()
        }
    }
}
```

---

#### onWindowFinalized

Fired when `finalizeWindow()` is called and the window is locked for reading.

**JavaScript:**
```javascript
AndroidBridge.onWindowFinalized({
    windowIndex: windowIndex,
    pageCount: getPageCount()
});
```

**Kotlin Interface:**
```kotlin
@JavascriptInterface
fun onWindowFinalized(json: String) {
    val event = gson.fromJson(json, WindowFinalizedEvent::class.java)
    AppLogger.d(TAG, "Window ${event.windowIndex} finalized with ${event.pageCount} pages")
}
```

---

## Preloading Protocol

### Overview

Preloading enables smooth window transitions by preparing adjacent windows in the background before the user reaches them.

### Trigger Conditions

Preloading is triggered when:
- Progress through active window reaches threshold (default: 75%)
- User explicitly requests chapter outside current window
- Initial window load completes (preload adjacent windows)

### Flow

```
1. User reaches 75% of active window
   │
   ▼
2. StableWindowManager.updatePosition() detects threshold
   │
   ▼
3. Preload next/previous window in background
   │
   ▼
4. Generate window HTML (all chapters in window)
   │
   ▼
5. Create WindowSnapshot (immutable)
   │
   ▼
6. Store in nextWindow/prevWindow StateFlow
   │
   ▼
7. When user reaches boundary:
   │
   ├─► Atomic transition to preloaded window
   │
   └─► Load window HTML into WebView
       │
       ▼
       JavaScript: loadWindow(descriptor)
```

### Preloading Implementation

**Kotlin (StableWindowManager):**
```kotlin
private suspend fun preloadNextWindow(currentWindowIndex: WindowIndex) {
    if (_nextWindow.value != null) return  // Already loaded
    
    val nextWindowIndex = currentWindowIndex + 1
    if (nextWindowIndex >= totalWindows) return  // At end
    
    AppLogger.d(TAG, "Preloading next window $nextWindowIndex")
    
    // Load window in background
    val snapshot = loadWindow(nextWindowIndex)
    _nextWindow.value = snapshot
}
```

---

## Atomic Window Switching

### Overview

Window switches happen atomically to prevent intermediate states where neither window is fully loaded.

### Switch Flow

```
Active Window (Window N)          Next Window (Window N+1)
        │                                 │
        │  1. User reaches boundary       │
        ▼                                 │
   onBoundaryReached('NEXT')              │
        │                                 │
        │  2. Check next window ready     │
        ▼                                 ▼
   if (nextWindow.isReady) ──────► Use preloaded snapshot
        │
        │  3. Atomic state swap
        ▼
   prevWindow = activeWindow
   activeWindow = nextWindow
   nextWindow = null
        │
        │  4. Load new active into WebView
        ▼
   webView.loadDataWithBaseURL(htmlContent)
        │
        │  5. Configure and finalize
        ▼
   loadWindow(descriptor)
        │
        │  6. Navigate to entry position
        ▼
   goToPage(0) for forward transition
   goToPage(lastPage) for backward transition
        │
        │  7. Trigger preload of new adjacent
        ▼
   preloadNextWindow(newWindowIndex)
```

### Position Mapping on Switch

When switching windows, the entry position is mapped as follows:

**Forward Transition (Next Window):**
```kotlin
val entryPosition = EntryPosition(
    chapterIndex = nextWindow.firstChapterIndex,
    inPageIndex = 0  // Start at beginning
)
```

**Backward Transition (Previous Window):**
```kotlin
val lastChapter = prevWindow.chapters.last()
val entryPosition = EntryPosition(
    chapterIndex = lastChapter.chapterIndex,
    inPageIndex = lastChapter.pageCount - 1  // Start at end
)
```

---

## Error Handling

### Window Load Failures

**JavaScript:**
```javascript
function loadWindow(descriptor) {
    try {
        // ... loading logic
    } catch (error) {
        AndroidBridge.onWindowLoadError({
            windowIndex: descriptor.windowIndex,
            errorMessage: error.message,
            errorType: 'LOAD_FAILED'
        });
    }
}
```

**Kotlin Interface:**
```kotlin
@JavascriptInterface
fun onWindowLoadError(json: String) {
    val error = gson.fromJson(json, WindowLoadError::class.java)
    viewModelScope.launch {
        viewModel.handleWindowLoadError(error)
    }
}
```

### Navigation Errors

**JavaScript:**
```javascript
function goToPage(pageIndex, animate) {
    if (!isReady()) {
        AndroidBridge.onNavigationError({
            requestedPage: pageIndex,
            errorMessage: 'Paginator not ready',
            errorType: 'NOT_READY'
        });
        return;
    }
    // ... navigation logic
}
```

### Recovery Strategies

1. **Retry with timeout** - Attempt load again after delay
2. **Fallback to chapter mode** - Load single chapter if window fails
3. **Show error message** - Inform user and allow manual retry
4. **Clear and reload** - Reset paginator state and try fresh

---

## Integration Points

### StableWindowManager

The `StableWindowManager` orchestrates window lifecycle and calls the API:

```kotlin
class StableWindowManager {
    // Load initial window
    suspend fun loadInitialWindow(chapterIndex: ChapterIndex): WindowSnapshot {
        val window = buildWindow(chapterIndex)
        _activeWindow.value = window
        
        // Android: Load HTML into WebView
        // JavaScript: loadWindow(descriptor) called via WebViewPaginatorBridge
        
        return window
    }
    
    // Navigate to next window
    suspend fun navigateToNextWindow(): WindowSnapshot? {
        val next = _nextWindow.value ?: return null
        
        // Atomic swap
        _prevWindow.value = _activeWindow.value
        _activeWindow.value = next
        _nextWindow.value = null
        
        // Return snapshot for UI to render
        return next
    }
}
```

### ReaderPageFragment

The fragment renders windows and handles JavaScript callbacks:

```kotlin
class ReaderPageFragment : Fragment() {
    
    private fun renderWindow(window: WindowSnapshot) {
        // Load HTML
        webView.loadDataWithBaseURL(
            "file:///android_asset/",
            window.htmlContent!!,
            "text/html",
            "UTF-8",
            null
        )
        
        // After page finished, call loadWindow API
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                val descriptor = window.toDescriptor()
                WebViewPaginatorBridge.loadWindow(webView, descriptor)
            }
        }
    }
    
    // JavaScript callback handler
    @JavascriptInterface
    fun onBoundaryReached(json: String) {
        val event = gson.fromJson(json, BoundaryReachedEvent::class.java)
        lifecycleScope.launch {
            when (event.direction) {
                "NEXT" -> viewModel.navigateToNextWindow()
                "PREVIOUS" -> viewModel.navigateToPrevWindow()
            }
        }
    }
}
```

### ContinuousPaginator

The existing `ContinuousPaginator` should be updated to use `StableWindowManager`:

```kotlin
class ContinuousPaginator {
    private val windowManager: StableWindowManager
    
    suspend fun loadPage(windowIndex: WindowIndex): WindowSnapshot {
        return windowManager.activeWindow.value 
            ?: windowManager.loadInitialWindow(windowIndex)
    }
    
    suspend fun onPageChanged(chapterIndex: ChapterIndex, inPageIndex: InPageIndex) {
        windowManager.updatePosition(
            windowIndex = currentWindowIndex,
            chapterIndex = chapterIndex,
            inPageIndex = inPageIndex
        )
    }
}
```

---

## Summary

### API Overview

| Direction | Operation | Purpose |
|-----------|-----------|---------|
| Android → JS | `loadWindow(descriptor)` | Initialize window for reading |
| Android → JS | `goToPage(index, animate)` | Navigate to specific page |
| Android → JS | `getPageCount()` | Query total pages |
| Android → JS | `getCurrentPage()` | Query current page |
| Android → JS | `getCurrentChapter()` | Query current chapter |
| Android → JS | `getChapterBoundaries()` | Get chapter page mappings |
| Android → JS | `getPageMappingInfo()` | Get detailed position info |
| JS → Android | `onWindowLoaded` | Window ready for reading |
| JS → Android | `onPageChanged` | User navigated to new page |
| JS → Android | `onBoundaryReached` | User at window boundary |
| JS → Android | `onWindowFinalized` | Window locked for reading |
| JS → Android | `onWindowLoadError` | Error during window load |

### Key Principles

1. **Immutable active windows** - No mutations during reading
2. **Background preloading** - Adjacent windows prepared ahead of time
3. **Atomic transitions** - Clean switches between windows
4. **Explicit position tracking** - No race conditions with scroll position
5. **Clear error handling** - Defined error types and recovery paths

---

## References

- [STABLE_WINDOW_MODEL.md](./STABLE_WINDOW_MODEL.md) - Window architecture
- [JS_STREAMING_DISCIPLINE.md](./JS_STREAMING_DISCIPLINE.md) - JavaScript mode discipline
- [PAGINATOR_API.md](./PAGINATOR_API.md) - JavaScript paginator API
- [ARCHITECTURE.md](./ARCHITECTURE.md) - System architecture
- [sliding-window-inpage-pagination-notes.md](../../sliding-window-inpage-pagination-notes.md) - Design notes

---

*Last Updated: 2025-11-25*
