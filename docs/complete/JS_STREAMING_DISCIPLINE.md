# JavaScript Streaming Discipline for Stable Windows

## Overview

This document defines the strict discipline for JavaScript streaming operations (append/prepend) in the stable sliding window model. The key principle: **streaming operations are ONLY allowed during background window construction, NEVER during active reading**.

## The Problem

In previous implementations, JavaScript code could append or prepend chapters to the active window during reading. This caused:

1. **Calculation Tangles**: Page indices and positions became inconsistent as content was added
2. **Race Conditions**: UI updates competed with content mutations
3. **User Interaction Issues**: Tap zones, swipes, and position tracking broke unpredictably
4. **State Synchronization**: Android and JavaScript disagreed about page counts and positions

## The Solution: Construction vs. Active Mode

The paginator operates in two distinct modes:

### Construction Mode (Mutable)
- **When**: During background window loading
- **Allowed**: append(), prepend(), insertChapter()
- **State**: Mutable, content can be added/removed
- **Not Visible**: Window is not being displayed to user

### Active Mode (Immutable)
- **When**: Window is active and being read
- **Forbidden**: No content mutations allowed
- **State**: Immutable, content is fixed
- **Visible**: Window is being displayed to user

## JavaScript API Changes

### Mode Tracking

```javascript
// Global state
let windowMode = 'CONSTRUCTION';  // 'CONSTRUCTION' or 'ACTIVE'

/**
 * Finalize window construction and enter active mode.
 * After this, no mutations are allowed.
 */
function finalizeWindow() {
    if (windowMode === 'ACTIVE') {
        console.warn('Window already finalized');
        return;
    }
    
    windowMode = 'ACTIVE';
    console.log('Window finalized - entering active mode');
    
    // Lock down the content
    Object.freeze(segments);  // Prevent modifications
    
    // Notify Android
    if (AndroidBridge && AndroidBridge.onWindowFinalized) {
        AndroidBridge.onWindowFinalized(getPageCount());
    }
}
```

### Guarded Streaming Operations

```javascript
/**
 * Append a chapter to the window.
 * ONLY allowed during construction mode.
 */
function appendChapter(html, chapterIndex) {
    if (windowMode !== 'CONSTRUCTION') {
        throw new Error('Cannot append chapter: window is in active mode');
    }
    
    // Proceed with append
    const section = document.createElement('section');
    section.setAttribute('data-chapter-index', chapterIndex);
    section.innerHTML = html;
    
    const root = document.getElementById('window-root') || document.body;
    root.appendChild(section);
    
    // Update internal state
    segments.push({
        chapterIndex: chapterIndex,
        element: section
    });
    
    console.log(`Appended chapter ${chapterIndex} (construction mode)`);
}

/**
 * Prepend a chapter to the window.
 * ONLY allowed during construction mode.
 */
function prependChapter(html, chapterIndex) {
    if (windowMode !== 'CONSTRUCTION') {
        throw new Error('Cannot prepend chapter: window is in active mode');
    }
    
    // Proceed with prepend
    const section = document.createElement('section');
    section.setAttribute('data-chapter-index', chapterIndex);
    section.innerHTML = html;
    
    const root = document.getElementById('window-root') || document.body;
    root.insertBefore(section, root.firstChild);
    
    // Update internal state
    segments.unshift({
        chapterIndex: chapterIndex,
        element: section
    });
    
    console.log(`Prepended chapter ${chapterIndex} (construction mode)`);
}
```

### Safe Active Mode Operations

These operations are allowed in active mode because they don't mutate content:

```javascript
/**
 * Navigate to a page within the window.
 * Safe in active mode - no content mutation.
 */
function goToPage(pageIndex, smooth = false) {
    // Mode-independent - navigation is always safe
    const pageCount = getPageCount();
    const safeIndex = Math.max(0, Math.min(pageIndex, pageCount - 1));
    
    currentPage = safeIndex;
    
    const offset = calculateScrollOffset(safeIndex);
    scrollToOffset(offset, smooth);
    
    // Notify Android
    if (AndroidBridge && AndroidBridge.onPageChanged) {
        const chapter = getCurrentChapter();
        AndroidBridge.onPageChanged(chapter.index, safeIndex);
    }
}

/**
 * Get current page information.
 * Safe in active mode - read-only.
 */
function getCurrentPage() {
    return currentPage;
}

/**
 * Get page count.
 * Safe in active mode - read-only.
 */
function getPageCount() {
    return calculatePageCount();
}

/**
 * Get loaded chapters.
 * Safe in active mode - read-only.
 */
function getLoadedChapters() {
    return segments.map(s => s.chapterIndex);
}
```

## Android Integration

### Window Loading Flow

```kotlin
// Step 1: Create window HTML (construction mode)
val windowHtml = windowHtmlProvider.generateWindowHtml(
    windowIndex = windowIndex,
    firstChapterIndex = firstChapter,
    lastChapterIndex = lastChapter,
    bookFile = bookFile,
    parser = parser
)

// Step 2: Load into WebView
webView.loadDataWithBaseURL(
    "file:///android_asset/",
    windowHtml,
    "text/html",
    "UTF-8",
    null
)

// Step 3: Configure paginator (still in construction mode)
webView.evaluateJavascript("""
    window.inpagePaginator.configure({
        mode: 'WINDOW',
        windowIndex: $windowIndex
    });
""", null)

// Step 4: Finalize window (switch to active mode)
webView.evaluateJavascript("""
    window.inpagePaginator.finalizeWindow();
""", null)

// Step 5: Navigate to initial page
webView.evaluateJavascript("""
    window.inpagePaginator.goToPage($initialPage);
""", null)
```

### Bridge Methods

```kotlin
class WebViewPaginatorBridge(/* ... */) {
    
    /**
     * Called when window construction is complete.
     * After this, the window is immutable.
     */
    @JavascriptInterface
    fun onWindowFinalized(pageCount: Int) {
        AppLogger.d(TAG, "Window finalized with $pageCount pages")
        
        // Update ViewModel state
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.onWindowFinalized(pageCount)
        }
    }
    
    /**
     * Called when user reaches window boundary.
     * Triggers window transition in Android.
     */
    @JavascriptInterface
    fun onWindowBoundaryReached(direction: String) {
        AppLogger.d(TAG, "Window boundary reached: $direction")
        
        viewLifecycleOwner.lifecycleScope.launch {
            when (direction) {
                "next" -> viewModel.navigateToNextWindow()
                "prev" -> viewModel.navigateToPrevWindow()
            }
        }
    }
    
    /**
     * Page change notification - always allowed.
     */
    @JavascriptInterface
    fun onPageChanged(chapterIndex: Int, inPageIndex: Int) {
        // Update position tracking
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.updatePosition(chapterIndex, inPageIndex)
        }
    }
}
```

## Background Window Construction

When constructing windows in the background (prev/next windows):

```kotlin
/**
 * Construct a window in the background (not yet visible).
 */
private suspend fun constructWindowInBackground(
    windowIndex: WindowIndex
): WindowSnapshot {
    AppLogger.d(TAG, "Constructing window $windowIndex in background")
    
    // Generate HTML with all chapters
    val html = windowHtmlProvider.generateWindowHtml(
        windowIndex = windowIndex,
        firstChapterIndex = firstChapter,
        lastChapterIndex = lastChapter,
        bookFile = bookFile,
        parser = parser
    )
    
    // Create snapshot (immutable)
    val snapshot = WindowSnapshot(
        windowIndex = windowIndex,
        firstChapterIndex = firstChapter,
        lastChapterIndex = lastChapter,
        chapters = chapters,
        totalPages = totalPages,
        htmlContent = html,
        loadState = WindowLoadState.LOADED
    )
    
    AppLogger.d(TAG, "Background window $windowIndex constructed")
    return snapshot
}
```

**Key Point**: Background windows are constructed as complete HTML documents. There's no JavaScript streaming involved in background construction - the HTML is complete before loading into WebView.

## Mode Transition Diagram

```
┌─────────────────────┐
│  Window Created     │
│  (background)       │
│                     │
│  Mode: N/A          │
└──────────┬──────────┘
           │
           │ generateWindowHtml()
           ▼
┌─────────────────────┐
│  HTML Generated     │
│  (all chapters)     │
│                     │
│  Mode: N/A          │
└──────────┬──────────┘
           │
           │ loadDataWithBaseURL()
           ▼
┌─────────────────────┐
│  WebView Loaded     │
│                     │
│  Mode: CONSTRUCTION │
└──────────┬──────────┘
           │
           │ configure()
           ▼
┌─────────────────────┐
│  Paginator Config   │
│                     │
│  Mode: CONSTRUCTION │
└──────────┬──────────┘
           │
           │ finalizeWindow()
           ▼
┌─────────────────────┐
│  Window Active      │
│  (user reading)     │
│                     │
│  Mode: ACTIVE       │──► No mutations allowed!
│                     │
│  ✓ goToPage()       │
│  ✓ getCurrentPage() │
│  ✗ appendChapter()  │
│  ✗ prependChapter() │
└─────────────────────┘
```

## Error Prevention

### Runtime Checks

```javascript
// Development mode - strict checks
if (DEBUG) {
    // Intercept all segment modifications
    const originalPush = Array.prototype.push;
    segments.push = function(...args) {
        if (windowMode === 'ACTIVE') {
            throw new Error('Cannot modify segments in active mode');
        }
        return originalPush.apply(this, args);
    };
    
    // Similar for unshift, splice, etc.
}
```

### Build-Time Validation

Add linting rules to catch violations:

```javascript
// .eslintrc.js
module.exports = {
    rules: {
        'no-segment-mutation-in-active-mode': ['error', {
            forbiddenFunctions: [
                'appendChapter',
                'prependChapter',
                'insertChapter',
                'removeChapter'
            ],
            allowedModes: ['CONSTRUCTION']
        }]
    }
};
```

## Migration from Old Behavior

### Old Code (Streaming During Active Reading)

```javascript
// ❌ BAD - mutates active window
function onPageChange(newPage) {
    if (newPage >= pageCount - 1) {
        // Streaming next chapter into active window
        AndroidBridge.requestNextChapter();
    }
}

function onChapterReceived(html, chapterIndex) {
    // Appending to active window
    appendChapter(html, chapterIndex);  // ❌ Breaks stability
}
```

### New Code (Boundary Notification)

```javascript
// ✅ GOOD - notifies without mutating
function onPageChange(newPage) {
    if (newPage >= pageCount - 1) {
        // Notify Android of boundary
        AndroidBridge.onWindowBoundaryReached('next');
        // Android will handle window transition
    }
}

// No onChapterReceived - windows are pre-constructed
```

## Testing

### Unit Tests

```javascript
describe('Window Mode Discipline', () => {
    it('should allow append during construction', () => {
        windowMode = 'CONSTRUCTION';
        expect(() => {
            appendChapter('<div>Chapter 1</div>', 1);
        }).not.toThrow();
    });
    
    it('should forbid append during active mode', () => {
        windowMode = 'ACTIVE';
        expect(() => {
            appendChapter('<div>Chapter 2</div>', 2);
        }).toThrow('Cannot append chapter: window is in active mode');
    });
    
    it('should allow navigation in active mode', () => {
        windowMode = 'ACTIVE';
        expect(() => {
            goToPage(5);
        }).not.toThrow();
    });
});
```

### Integration Tests

```kotlin
@Test
fun testWindowImmutabilityDuringReading() {
    // Load initial window
    val window = windowManager.loadInitialWindow(chapterIndex = 0)
    
    // Start reading
    val initialChapters = window.chapters.toList()
    val initialPageCount = window.totalPages
    
    // Simulate reading through window
    for (page in 0 until window.totalPages) {
        windowManager.updatePosition(
            windowIndex = 0,
            chapterIndex = 0,
            inPageIndex = page
        )
    }
    
    // Verify window hasn't changed
    assertEquals(initialChapters, window.chapters)
    assertEquals(initialPageCount, window.totalPages)
}
```

## Summary

### DO:
✅ Load complete windows with all chapters pre-included
✅ Use finalizeWindow() to lock down content before reading
✅ Navigate within windows using goToPage()
✅ Notify Android of boundary conditions
✅ Construct new windows in background

### DON'T:
❌ Append/prepend to active windows
❌ Mutate segment list during reading
❌ Stream chapters into visible content
❌ Modify page structure after finalization

### Key Benefit:
By maintaining strict construction vs. active discipline, we ensure:
- Predictable page indices
- Stable position tracking
- Reliable user interactions
- No calculation tangles

## References

- [STABLE_WINDOW_MODEL.md](./STABLE_WINDOW_MODEL.md) - Overall architecture
- [PAGINATOR_API.md](./PAGINATOR_API.md) - JavaScript API
- [inpage_paginator.js](../../app/src/main/assets/inpage_paginator.js) - Implementation

---

*Last Updated: 2025-11-23*
