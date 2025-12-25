# FlexPaginator Core Architecture

## Overview

FlexPaginator is a clean-slate pagination system for RiftedReader with offscreen pre-slicing capabilities. This document defines the core architecture, design principles, data flow, and character offset tracking system.

## Design Principles

### 1. Single Responsibility Per Component

Each component has one clear purpose:

- **Conveyor**: Manages window lifecycle (creation, buffering, destruction)
- **FlexPaginator**: Wraps chapters in HTML with `<section>` tags
- **OffscreenSlicingWebView**: Pre-slices content in hidden WebView
- **flex_paginator.js**: Slices content into viewport-sized pages
- **WindowData**: Caches sliced metadata

### 2. No Chapter Streaming

Unlike the previous system, FlexPaginator does NOT:
- Append/prepend chapters dynamically
- Remove chapters from DOM
- Manage chapter lifecycle in JavaScript

**Rationale**: The Conveyor manages window lifecycle at the Kotlin level, creating entire windows with multiple chapters at once. This eliminates complex state management in JavaScript.

### 3. Pre-Slicing = Zero Latency

All slicing happens **before** the window is displayed:

```
Window Creation:
  ├─ FlexPaginator assembles HTML
  ├─ OffscreenSlicingWebView slices content
  ├─ Cache metadata with WindowData
  └─ Window ready for instant display

User Navigates:
  └─ Display window immediately (zero latency)

> Implementation note (current repo state)
>
> - Pre-slicing exists, but the current slicer uses a fixed viewport height and height *estimation*.
> - “Zero latency display” requires that offscreen slicing uses the **same viewport + CSS** as the on-screen reader.
> - Treat this doc as the target architecture; see the Phase 1 “Reality Check” in `FLEX_PAGINATOR_INTEGRATION_CHECKLIST.md` for current gaps.
```

### 4. Character Offset Tracking

Pages are tracked by **text position**, not layout:

- Each page has `startChar` and `endChar` offsets
- Offsets survive font size changes
- Bookmarks use `(window, page, charOffset)` tuples
- Position restoration is precise and reliable

> Implementation note (current repo state)
>
> - Current JS resets `currentCharOffset` to 0 at each chapter boundary, so offsets are **chapter-local**.
> - This is compatible with bookmark tuples that include `chapter`, but it is not a single “global char offset” across the whole book.

## Component Architecture

### Layer 1: Kotlin (Data & Lifecycle)

```
┌─────────────────────────────────────────┐
│ FlexPaginator.kt                        │
│ • assembleWindow(windowIndex)           │
│ • Wraps chapters in <section> tags      │
│ • Returns WindowData with HTML          │
└──────────────┬──────────────────────────┘
               ↓
┌─────────────────────────────────────────┐
│ OffscreenSlicingWebView.kt              │
│ • Hidden WebView (1x1 pixel)            │
│ • sliceWindow(html, windowIndex)        │
│ • Returns SliceMetadata                 │
└──────────────┬──────────────────────────┘
               ↓
┌─────────────────────────────────────────┐
│ WindowData + SliceMetadata              │
│ • Cached in WindowBufferManager         │
│ • Ready for instant display             │
└─────────────────────────────────────────┘
```

### Layer 2: JavaScript (Slicing & Measurement)

```
┌─────────────────────────────────────────┐
│ flex_paginator.js                       │
│ • Node-walking algorithm                │
│ • Slices into viewport-sized pages      │
│ • Measures real heights                 │
│ • Builds character offset array         │
│ • Reports via AndroidBridge             │
└─────────────────────────────────────────┘
```

### Layer 3: Conveyor (Window Management)

```
┌─────────────────────────────────────────┐
│ ConveyorBeltSystemViewModel            │
│ • Creates windows on-demand             │
│ • Maintains buffer of 5 windows         │
│ • Shifts buffer when user reaches edge  │
│ • Evicts old windows from memory        │
└─────────────────────────────────────────┘
```

## Data Flow Pipeline

### Input: Chapter Content

```kotlin
// BookParser provides chapter content
val chapter25 = bookParser.getChapterContent(25)
val chapter26 = bookParser.getChapterContent(26)
// ...
```

### Stage 1: Chapter Wrapping

```kotlin
// FlexPaginator wraps chapters
val windowData = FlexPaginator.assembleWindow(
    windowIndex = 5,
    firstChapter = 25,
    lastChapter = 29
)

// Output HTML structure:
<div id="window-root" data-window-index="5">
  <section data-chapter="25">
    <!-- Chapter 25 content -->
  </section>
  <section data-chapter="26">
    <!-- Chapter 26 content -->
  </section>
  <!-- ... -->
</div>
```

### Stage 2: Offscreen Slicing

```kotlin
// OffscreenSlicingWebView pre-slices
val sliceMetadata = offscreenWebView.sliceWindow(
    wrappedHtml = windowData.html,
    windowIndex = 5
)

// Result: SliceMetadata with page boundaries
```

### Stage 3: Metadata Caching

```kotlin
// Update WindowData with metadata
val completeWindowData = windowData.copy(
    sliceMetadata = sliceMetadata
)

// Cache in buffer
windowBuffer.cache(5, completeWindowData)
```

### Stage 4: Display

```kotlin
// When user navigates to Window 5
// Display is instant - slicing already complete
```

## Pre-Slicing Workflow (Detailed)

### Scenario: User Approaches Window Boundary

```
User reading Window 3:
  ├─ flex_paginator.js detects 90% scroll threshold
  ├─ Calls onReachedEndBoundary(3)
  └─ Conveyor receives boundary event

Conveyor shifts buffer forward:
  ├─ Evict Window 1 (no longer needed)
  ├─ Create Window 6 (ahead of user)
  │
  ├─ FlexPaginator.assembleWindow(6)
  │   ├─ Get chapters 30-34 from BookParser
  │   ├─ Wrap in <section data-chapter="N"> tags
  │   └─ Return WindowData with HTML
  │
  ├─ OffscreenSlicingWebView.sliceWindow(wrappedHtml, 6)
  │   ├─ Create hidden WebView (1x1 pixel)
  │   ├─ Load wrapped HTML
  │   ├─ Inject flex_paginator.js
  │   ├─ Wait for slicing to complete
  │   │
  │   ├─ [JavaScript] flex_paginator.js initializes
  │   ├─ [JavaScript] Walk DOM nodes recursively
  │   ├─ [JavaScript] Accumulate content into .page divs
  │   ├─ [JavaScript] Measure heights (viewport = 600px)
  │   ├─ [JavaScript] Enforce hard breaks at <section> boundaries
  │   ├─ [JavaScript] Build charOffset[] array
  │   ├─ [JavaScript] Call AndroidBridge.onSlicingComplete(metadata)
  │   │
  │   ├─ Parse metadata JSON
  │   └─ Return SliceMetadata object
  │
  ├─ Update WindowData with sliceMetadata
  └─ Cache Window 6 in buffer (ready)

User navigates to Window 4:
  └─ Display instantly (already pre-sliced)
```

### Timing

- **Window Creation**: 300-500ms (assembly + slicing)
- **Display**: 0ms (pre-cached, instant)
- **Buffer Shift**: <1s (happens in background)
- **User Experience**: Seamless, no delays

## Character Offset Tracking

### Purpose

Character offsets enable:
1. **Precise bookmarks**: Save exact reading position
2. **Font size resilience**: Position survives layout changes
3. **Cross-device sync**: Same position on all devices
4. **TOC navigation**: Jump to any chapter offset

### Structure

```kotlin
data class PageSlice(
    val page: Int,           // Page index within window
    val chapter: Int,        // Chapter index
    val startChar: Int,      // Start character offset
    val endChar: Int,        // End character offset
    val heightPx: Int        // Measured height
)

data class SliceMetadata(
    val windowIndex: Int,
    val totalPages: Int,
    val slices: List<PageSlice>
)
```

### Example

```
Window 5 (chapters 25-29):
  Page 0: chapter=25, startChar=0,    endChar=523,  height=600px
  Page 1: chapter=25, startChar=523,  endChar=1045, height=600px
  Page 2: chapter=25, startChar=1045, endChar=1568, height=600px
  Page 3: chapter=26, startChar=0,    endChar=445,  height=600px
  ...
```

### Bookmark Restoration

```kotlin
// Save bookmark
val bookmark = Bookmark(
    windowIndex = 5,
    page = 2,
    charOffset = 1200  // Mid-page position
)

// Restore after font size change
// (SliceMetadata has been re-sliced with new font)
val restoredPage = sliceMetadata.findPageByCharOffset(
    chapter = 25,
    offset = 1200
)
// restoredPage might be page 3 now (different layout)
// but charOffset 1200 points to same text content
```

## HTML Structure

### Wrapped HTML (FlexPaginator Output)

```html
<!DOCTYPE html>
<html>
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <style>
        /* Reader theme CSS injected here */
    </style>
</head>
<body>
    <div id="window-root" data-window-index="5">
        <section data-chapter="25">
            <h1>Chapter 25: The Journey Begins</h1>
            <p>It was a dark and stormy night...</p>
            <!-- More content -->
        </section>
        <section data-chapter="26">
            <h1>Chapter 26: The Adventure Continues</h1>
            <p>The morning sun rose over the hills...</p>
            <!-- More content -->
        </section>
        <!-- More sections -->
    </div>
</body>
</html>
```

### Sliced HTML (flex_paginator.js Output)

```html
<div id="flex-container">
    <div class="page" 
         data-window="5" 
         data-slice="0" 
         data-chapter="25">
        <!-- Chapter 25 content (first 523 characters) -->
    </div>
    <div class="page" 
         data-window="5" 
         data-slice="1" 
         data-chapter="25">
        <!-- Chapter 25 content (next 522 characters) -->
    </div>
    <!-- More pages -->
</div>
```

### CSS (Flex Layout)

```css
#flex-container {
    display: flex;
    flex-direction: row;
    overflow-x: scroll;
    scroll-snap-type: x mandatory;
    height: 100vh;
}

.page {
    flex: 0 0 100vw;
    scroll-snap-align: start;
    padding: 20px;
    box-sizing: border-box;
}
```

## Node-Walking Slicing Algorithm

### Overview

```
Input: <section data-chapter="N"> elements
  ↓
For each section:
  ├─ Walk DOM nodes recursively
  ├─ Accumulate content into current .page div
  ├─ When accumulated height >= viewport height:
  │   ├─ Finalize current page
  │   ├─ Start new page
  │   └─ Continue accumulation
  ├─ Hard break at end of section (chapter boundary)
  └─ Track character offsets
  ↓
Output: Array of .page elements with metadata
```

### Pseudo-code

```javascript
function sliceSection(section) {
    let currentPage = createPage();
    let accumulatedHeight = 0;
    let charOffset = 0;
    
    walkNodes(section, (node) => {
        const nodeHeight = measureNode(node);
        
        if (accumulatedHeight + nodeHeight > VIEWPORT_HEIGHT) {
            // Finalize current page
            finalizePage(currentPage, charOffset);
            
            // Start new page
            currentPage = createPage();
            accumulatedHeight = 0;
        }
        
        // Add node to current page
        currentPage.appendChild(node.cloneNode(true));
        accumulatedHeight += nodeHeight;
        charOffset += getTextLength(node);
    });
    
    // Finalize last page of section
    finalizePage(currentPage, charOffset);
    
    // Hard break - force new page for next section
    createPage(); // Empty page signals chapter boundary
}
```

### Height Estimation

flex_paginator.js estimates heights for different node types:

```javascript
function estimateHeight(node) {
    if (node.tagName === 'P') return 80;
    if (node.tagName === 'H1') return 100;
    if (node.tagName === 'H2') return 90;
    if (node.tagName === 'H3') return 80;
    if (node.tagName === 'IMG') return node.height || 300;
    if (node.tagName === 'DIV') return measureChildren(node);
    // Default
    return 50;
}
```

**Note**: Estimates are refined by actual measurement in WebView.

## AndroidBridge API

### JavaScript → Kotlin

```javascript
// Called when slicing completes
AndroidBridge.onSlicingComplete(metadataJson)

// Called when slicing fails
AndroidBridge.onSlicingError(errorMessage)

// Called when user reaches window boundary
AndroidBridge.onReachedStartBoundary(windowIndex)
AndroidBridge.onReachedEndBoundary(windowIndex)

// Called on page change
AndroidBridge.onPageChanged(page, charOffset, totalPages, windowIndex)
```

### Kotlin → JavaScript

```kotlin
// Navigate to specific page
webView.evaluateJavascript("goToPage($pageIndex)", null)

// Get current page
webView.evaluateJavascript("getCurrentPageIndex()", callback)

// Get total page count
webView.evaluateJavascript("getPageCount()", callback)

// Navigate by character offset
webView.evaluateJavascript("goToPageWithCharOffset($offset)", null)
```

## Window Buffer Management

### Buffer Structure

```
Current Position: Window 3
Buffer: [Window 1, Window 2, Window 3, Window 4, Window 5]
         ^^^^^^^^                       ^^^^^^^^^  ^^^^^^^^^
         Can evict                      Pre-sliced Pre-sliced
```

### Buffer Shift Forward

```
User reaches 90% of Window 3:
  ├─ Shift buffer forward
  ├─ Evict Window 1
  ├─ Create Window 6 (pre-slice)
  └─ New buffer: [Window 2, Window 3, Window 4, Window 5, Window 6]
```

### Buffer Shift Backward

```
User reaches 10% of Window 3:
  ├─ Shift buffer backward
  ├─ Evict Window 5
  ├─ Create Window 0 (pre-slice)
  └─ New buffer: [Window 0, Window 1, Window 2, Window 3, Window 4]
```

## Memory Management

### Per-Window Memory

- **HTML content**: ~5-10KB per chapter × 5 chapters = 25-50KB
- **SliceMetadata**: ~100 bytes per page × 30 pages = 3KB
- **WebView overhead**: ~2-5MB (when loaded)

**Total per window**: ~2-5MB (negligible)

### Buffer Memory (5 windows)

- **Total**: 10-25MB (acceptable)
- **Peak**: ~50MB during window creation (one OffscreenWebView active)

### Eviction Strategy

- LRU eviction: Oldest windows removed first
- Aggressive eviction: Only keep 5-window buffer
- No persistent cache: Windows recreated on-demand

## Performance Targets

| Metric | Target | Notes |
|--------|--------|-------|
| Slicing time | <500ms/window | Offscreen, non-blocking |
| Memory per window | <10MB | Including all metadata |
| Max buffer memory | <50MB | 5 windows + 1 slicing |
| Page navigation | <100ms | Pre-sliced, instant |
| Window shift | <1s | Background operation |
| Display latency | 0ms | Already pre-cached |

## Error Handling

### Slicing Timeout

```kotlin
// OffscreenSlicingWebView
try {
    val metadata = withTimeout(10_000) {
        sliceWindow(html, windowIndex)
    }
} catch (e: TimeoutException) {
    // Fallback: Use minimal_paginator.js
    // or retry with simpler slicing
}
```

### Invalid Metadata

```kotlin
// Validate metadata before caching
if (!sliceMetadata.isValid()) {
    Log.e(TAG, "Invalid metadata for window $windowIndex")
    // Fallback to previous window or re-slice
}
```

### WebView Creation Failure

```kotlin
// Handle WebView initialization failure
try {
    val webView = OffscreenSlicingWebView(context)
} catch (e: Exception) {
    // Fallback to synchronous slicing
    // or disable FlexPaginator
}
```

## Integration Points

### Primary: ConveyorBeltSystemViewModel

```kotlin
class ConveyorBeltSystemViewModel {
    private val flexPaginator = FlexPaginator(bookParser)
    private val slicingWebView = OffscreenSlicingWebView(context)
    
    suspend fun createWindow(windowIndex: Int) {
        // 1. Assemble HTML
        val windowData = flexPaginator.assembleWindow(
            windowIndex, firstChapter, lastChapter
        )
        
        // 2. Pre-slice offscreen
        val metadata = slicingWebView.sliceWindow(
            windowData.html, windowIndex
        )
        
        // 3. Cache with metadata
        val complete = windowData.copy(sliceMetadata = metadata)
        windowBuffer.cache(windowIndex, complete)
    }
}
```

### Secondary: ReaderPageFragment

```kotlin
class ReaderPageFragment : Fragment() {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Load pre-sliced HTML
        val windowData = windowBuffer.get(windowIndex)
        
        webView.loadDataWithBaseURL(
            null,
            windowData.html,
            "text/html",
            "utf-8",
            null
        )
        
        // Metadata already cached, instant display
    }
}
```

## Success Criteria

- ✅ Zero display latency (pre-slicing complete before display)
- ✅ Character offsets enable precise bookmarks
- ✅ Font size changes don't break position
- ✅ Memory usage stays under 50MB for 5 windows
- ✅ Slicing time stays under 500ms per window
- ✅ No visible lag when navigating between windows
- ✅ Clear separation of concerns (assembly → slicing → display)

## Next Steps

1. **Phase 6a**: Wire FlexPaginator into ConveyorBeltSystemViewModel
2. **Phase 6b**: Test pre-slicing workflow end-to-end
3. **Phase 6c**: Verify zero-latency navigation
4. **Phase 6d**: Benchmark performance (slicing time, memory)
5. **Phase 6e**: Test with real EPUB files
6. **Phase 7**: Font size re-slicing (see FLEX_PAGINATOR_FONT_SIZE_CHANGES.md)
7. **Phase 8**: Error recovery (see FLEX_PAGINATOR_ERROR_RECOVERY.md)

---

**Document Version**: 1.0  
**Date**: 2025-12-08  
**Status**: Planning Complete ✅  
**Next**: Integration (Phase 6)
