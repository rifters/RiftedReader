# Conveyor + WebView Flow with Flex Pagination Architecture

> **Document Type**: Planning and Architecture Specification  
> **Status**: Design Phase  
> **Created**: 2025-12-08  
> **Last Updated**: 2025-12-08

## Executive Summary

This document defines the comprehensive architecture for the next-generation pagination system in RiftedReader that integrates:

1. **Conveyor Window Management** - 5-window sliding buffer for efficient memory usage
2. **Node-Walking Slicing Algorithm** - Viewport-sized page generation with hard chapter breaks
3. **Flex + Scroll-Snap Navigation** - Smooth discrete page swipes using modern CSS
4. **WebView Lifecycle Integration** - Coordinated HTML injection and cleanup

This architecture replaces the current CSS column-based pagination with a more robust flex container system that provides better control over page boundaries, cleaner chapter break handling, and improved performance.

## Table of Contents

1. [System Overview](#system-overview)
2. [Current System Understanding](#current-system-understanding)
3. [Detailed Architecture](#detailed-architecture)
4. [Data Flow Pipeline](#data-flow-pipeline)
5. [Component Specifications](#component-specifications)
6. [API Contracts](#api-contracts)
7. [Implementation Considerations](#implementation-considerations)
8. [Migration Path](#migration-path)
9. [Testing Strategy](#testing-strategy)
10. [Performance Considerations](#performance-considerations)

---

## System Overview

### High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                      Android/Kotlin Layer                        │
│                                                                   │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐          │
│  │   Conveyor   │  │   Window     │  │   Reader     │          │
│  │    Belt      │◄─┤   Buffer     │◄─┤   View       │          │
│  │   System     │  │   Manager    │  │   Model      │          │
│  └──────┬───────┘  └──────┬───────┘  └──────────────┘          │
│         │                  │                                      │
│         │      ┌──────────▼────────┐                            │
│         │      │  Window Assembler  │                            │
│         │      │   + HTML Builder   │                            │
│         │      └──────────┬─────────┘                            │
│         │                 │                                       │
└─────────┼─────────────────┼───────────────────────────────────┘
          │                 │
          │    Wrapped      │
          │    HTML         │
          ▼                 ▼
┌─────────────────────────────────────────────────────────────────┐
│                    WebView / JavaScript Layer                     │
│                                                                   │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │         Flex Pagination Engine (JavaScript)              │   │
│  │                                                           │   │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  │   │
│  │  │  Chapter     │  │  Node-Walk   │  │  Flex        │  │   │
│  │  │  Wrapper     │─►│  Slicer      │─►│  Container   │  │   │
│  │  └──────────────┘  └──────────────┘  └──────────────┘  │   │
│  │                                                           │   │
│  │  ┌──────────────────────────────────────────────────┐  │   │
│  │  │         Scroll-Snap Navigation                    │  │   │
│  │  │  (Discrete page swipes with snap points)          │  │   │
│  │  └──────────────────────────────────────────────────┘  │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                   │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                   Rendered Pages                         │   │
│  │   [Page 1] [Page 2] [Page 3] ... [Page N]              │   │
│  └─────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

### Key Components

| Component | Responsibility | Layer |
|-----------|---------------|-------|
| **ConveyorBeltSystemViewModel** | Phase management (STARTUP/STEADY), buffer state tracking | Kotlin |
| **WindowBufferManager** | 5-window buffer lifecycle, shift operations, cache management | Kotlin |
| **WindowAssembler** | Chapter aggregation, HTML wrapping with `<section>` tags | Kotlin |
| **FlexPaginationEngine** | Node-walking, slice generation, flex container creation | JavaScript |
| **ScrollSnapController** | Horizontal navigation, snap behavior, boundary detection | JavaScript |
| **WebViewManager** | Lifecycle coordination, HTML injection, cleanup | Kotlin |

---

## Current System Understanding

### Conveyor System

The conveyor belt system manages a **5-window sliding buffer** for memory-efficient continuous reading:

**Buffer Lifecycle:**
- **STARTUP Phase**: Creates windows [0, 1, 2, 3, 4] at initialization
- **Transition**: Enters STEADY phase when user reaches window at buffer[2] (center position)
- **STEADY Phase**: Maintains active window at center (buffer[2]) with 2 windows ahead and 2 behind

**Buffer Operations:**
```
Initial:  [0, 1, 2, 3, 4]  ← User starts at window 0
          
Forward:  [0, 1, 2, 3, 4]  ← User reaches window 2 → Enter STEADY
Shift:    [1, 2, 3, 4, 5]  ← Drop 0, create 5
Shift:    [2, 3, 4, 5, 6]  ← Drop 1, create 6

Backward: [1, 2, 3, 4, 5]  ← User moves back
Shift:    [0, 1, 2, 3, 4]  ← Drop 5, recreate 0
```

**Memory Management:**
- Each window = 5 chapters wrapped in single HTML document
- Typical window size: ~400KB
- Maximum memory: ~2MB (5 windows)
- Evicted windows freed immediately
- Windows rebuilt on-demand if revisited

### Current Pagination System

The existing system uses **CSS column-based pagination**:

```javascript
// Current approach (inpage_paginator.js)
.paginated-content {
    column-width: 100vw;
    column-gap: 0;
    height: 100vh;
}
```

**Limitations:**
- Page break control is limited (CSS column-break properties are inconsistent)
- Chapter boundaries may split across column breaks unexpectedly
- Difficult to enforce "hard breaks" at chapter starts
- Complex CSS interactions with nested elements
- Limited control over pagination reflow

---

## Detailed Architecture

### Data Flow Pipeline

The complete flow from raw chapters to rendered pages consists of 5 distinct stages:

```
┌────────────────────────────────────────────────────────────────┐
│ Stage 1: Chapter Extraction                                     │
│ ┌────────────────────────────────────────────────────────┐    │
│ │ Input: Book database, Window index                      │    │
│ │ Process: Extract 5 consecutive chapters for window      │    │
│ │ Output: List<ChapterHtml>                               │    │
│ └────────────────────────────────────────────────────────┘    │
└────────────────────────────────────────────────────────────────┘
                            ↓
┌────────────────────────────────────────────────────────────────┐
│ Stage 2: Chapter Wrapping                                       │
│ ┌────────────────────────────────────────────────────────┐    │
│ │ Input: List<ChapterHtml>                                │    │
│ │ Process: Wrap each chapter in <section> with metadata   │    │
│ │ Output: Combined HTML document                          │    │
│ │                                                          │    │
│ │ <section data-chapter="0" data-chapter-index="0">       │    │
│ │   <!-- Chapter 0 content -->                            │    │
│ │ </section>                                              │    │
│ │ <section data-chapter="1" data-chapter-index="1">       │    │
│ │   <!-- Chapter 1 content -->                            │    │
│ │ </section>                                              │    │
│ └────────────────────────────────────────────────────────┘    │
└────────────────────────────────────────────────────────────────┘
                            ↓
┌────────────────────────────────────────────────────────────────┐
│ Stage 3: WebView Injection                                      │
│ ┌────────────────────────────────────────────────────────┐    │
│ │ Input: Wrapped HTML + Flex pagination script            │    │
│ │ Process: Load into WebView, execute initialization      │    │
│ │ Output: DOM ready for pagination                        │    │
│ └────────────────────────────────────────────────────────┘    │
└────────────────────────────────────────────────────────────────┘
                            ↓
┌────────────────────────────────────────────────────────────────┐
│ Stage 4: Node-Walking Slicing (JavaScript)                      │
│ ┌────────────────────────────────────────────────────────┐    │
│ │ Input: Wrapped HTML in DOM                              │    │
│ │ Process:                                                │    │
│ │   1. Walk each <section>'s child nodes                  │    │
│ │   2. Measure node heights                               │    │
│ │   3. Accumulate nodes into .page divs                   │    │
│ │   4. Force new page at chapter boundaries               │    │
│ │   5. Split when page height ≥ viewport height          │    │
│ │ Output: Array of .page divs with metadata               │    │
│ │                                                          │    │
│ │ [                                                        │    │
│ │   <div class="page" data-window="0" data-slice="1"      │    │
│ │        data-chapter="0">slice 1 content</div>,          │    │
│ │   <div class="page" data-window="0" data-slice="2"      │    │
│ │        data-chapter="0">slice 2 content</div>,          │    │
│ │   <div class="page" data-window="0" data-slice="3"      │    │
│ │        data-chapter="1">chapter 2 start</div>           │    │
│ │ ]                                                        │    │
│ └────────────────────────────────────────────────────────┘    │
└────────────────────────────────────────────────────────────────┘
                            ↓
┌────────────────────────────────────────────────────────────────┐
│ Stage 5: Flex Container Rendering                               │
│ ┌────────────────────────────────────────────────────────┐    │
│ │ Input: Array of .page divs                              │    │
│ │ Process: Wrap in flex container with scroll-snap        │    │
│ │ Output: Horizontally scrollable page view               │    │
│ │                                                          │    │
│ │ <div class="paginator" data-window="0">                 │    │
│ │   <div class="page">Page 1</div>                        │    │
│ │   <div class="page">Page 2</div>                        │    │
│ │   <div class="page">Page 3</div>                        │    │
│ │   ...                                                    │    │
│ │ </div>                                                   │    │
│ │                                                          │    │
│ │ CSS:                                                     │    │
│ │   .paginator {                                          │    │
│ │     display: flex;                                      │    │
│ │     flex-direction: row;                                │    │
│ │     overflow-x: auto;                                   │    │
│ │     scroll-snap-type: x mandatory;                      │    │
│ │     width: 100vw; height: 100vh;                        │    │
│ │   }                                                      │    │
│ │   .page {                                               │    │
│ │     flex: 0 0 100%;                                     │    │
│ │     height: 100%;                                       │    │
│ │     scroll-snap-align: start;                           │    │
│ │     overflow: hidden;                                   │    │
│ │   }                                                      │    │
│ └────────────────────────────────────────────────────────┘    │
└────────────────────────────────────────────────────────────────┘
```

---

## Component Specifications

### 1. Chapter Wrapper (Kotlin)

**Location**: `WindowHtmlBuilder.kt` (existing)

**Current Behavior**: Already wraps chapters in `<section>` tags with `data-chapter-index`

**Enhancement Needed**: Add additional metadata for flex pagination

```kotlin
fun wrapChapterInSection(
    chapterIndex: ChapterIndex,
    chapterHtml: String,
    chapterTitle: String?
): String {
    return """
        <section 
            id="chapter-$chapterIndex"
            data-chapter-index="$chapterIndex"
            data-chapter-title="${chapterTitle?.escapeHtml() ?: "Chapter $chapterIndex"}"
            class="chapter-section">
            $chapterHtml
        </section>
    """.trimIndent()
}
```

**Key Points:**
- Maintains existing behavior (backward compatible)
- Adds title metadata for TOC navigation
- Section boundaries serve as hard break markers for slicing algorithm

### 2. Node-Walking Slicing Algorithm (JavaScript)

**Location**: New file `flex_paginator.js` or integration into `inpage_paginator.js`

**Core Algorithm:**

```javascript
/**
 * Slice wrapped HTML into viewport-sized pages with hard chapter breaks.
 * 
 * @param {string} wrappedHtml - HTML with <section> wrapped chapters
 * @param {number} windowIndex - Window index for metadata
 * @returns {Array<HTMLElement>} Array of .page divs
 */
function sliceWrappedWindow(wrappedHtml, windowIndex) {
    // Create temporary container for measurement
    const tempContainer = document.createElement('div');
    tempContainer.style.cssText = `
        position: absolute;
        visibility: hidden;
        width: ${window.innerWidth}px;
        top: -9999px;
        left: 0;
    `;
    tempContainer.innerHTML = wrappedHtml;
    document.body.appendChild(tempContainer);
    
    const pages = [];
    let sliceIndex = 1;
    const viewportHeight = window.innerHeight;
    
    // Process each section (chapter)
    const sections = tempContainer.querySelectorAll('section[data-chapter-index]');
    
    sections.forEach((section) => {
        const chapterIndex = parseInt(section.getAttribute('data-chapter-index'), 10);
        const chapterTitle = section.getAttribute('data-chapter-title') || '';
        
        // Start new page for each chapter (hard break)
        let currentPage = createPage(windowIndex, sliceIndex++, chapterIndex, chapterTitle);
        let currentHeight = 0;
        
        // Walk through section's child nodes
        const walker = document.createTreeWalker(
            section,
            NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT,
            null,
            false
        );
        
        let node;
        let lastElement = null;
        
        while (node = walker.nextNode()) {
            // Skip empty text nodes
            if (node.nodeType === Node.TEXT_NODE && !node.textContent.trim()) {
                continue;
            }
            
            // Get the element (or parent element for text nodes)
            const element = node.nodeType === Node.ELEMENT_NODE 
                ? node 
                : node.parentElement;
                
            // Skip if we've already processed this element
            if (element === lastElement) {
                continue;
            }
            lastElement = element;
            
            // Clone and measure
            const clone = element.cloneNode(true);
            const measurer = document.createElement('div');
            measurer.style.cssText = `
                position: absolute;
                visibility: hidden;
                width: ${window.innerWidth}px;
                top: -9999px;
            `;
            measurer.appendChild(clone);
            document.body.appendChild(measurer);
            
            const nodeHeight = measurer.offsetHeight;
            document.body.removeChild(measurer);
            
            // Check if adding this node would overflow viewport
            if (currentHeight + nodeHeight > viewportHeight && currentHeight > 0) {
                // Finish current page and start new one
                pages.push(currentPage);
                currentPage = createPage(windowIndex, sliceIndex++, chapterIndex, chapterTitle);
                currentHeight = 0;
            }
            
            // Add node to current page
            currentPage.appendChild(element.cloneNode(true));
            currentHeight += nodeHeight;
        }
        
        // Add final page if it has content
        if (currentPage.childNodes.length > 0) {
            pages.push(currentPage);
        }
    });
    
    // Cleanup
    document.body.removeChild(tempContainer);
    
    return pages;
}

/**
 * Create a new page div with metadata.
 */
function createPage(windowIndex, sliceIndex, chapterIndex, chapterTitle) {
    const page = document.createElement('div');
    page.className = 'page';
    page.setAttribute('data-window', windowIndex);
    page.setAttribute('data-slice', sliceIndex);
    page.setAttribute('data-chapter', chapterIndex);
    page.setAttribute('data-chapter-title', chapterTitle);
    return page;
}
```

**Algorithm Features:**
- **Hard Breaks**: Each `<section>` forces a new page
- **Viewport-Sized**: Pages fill viewport height (±tolerance)
- **Format Preservation**: `cloneNode(true)` preserves all formatting
- **Element-Level Splitting**: Splits at element boundaries (no mid-paragraph breaks)
- **Metadata Rich**: Each page tracks window, slice, and chapter info

**Edge Cases Handled:**
- Empty text nodes skipped
- Orphan content (last page with minimal content)
- Very tall elements (images, tables) - may need special handling
- Nested elements (proper cloning with tree walker)

### 3. Flex Container Manager (JavaScript)

**Responsibility**: Wrap sliced pages in flex container with scroll-snap

```javascript
/**
 * Create flex paginator container from sliced pages.
 * 
 * @param {Array<HTMLElement>} pages - Array of .page divs
 * @param {number} windowIndex - Window index
 * @returns {HTMLElement} Paginator container
 */
function createFlexPaginator(pages, windowIndex) {
    const paginator = document.createElement('div');
    paginator.className = 'paginator';
    paginator.setAttribute('data-window', windowIndex);
    
    // Add all pages
    pages.forEach(page => {
        paginator.appendChild(page);
    });
    
    return paginator;
}

/**
 * Inject paginator styles into document.
 */
function injectPaginatorStyles() {
    if (document.getElementById('flex-paginator-styles')) {
        return; // Already injected
    }
    
    const style = document.createElement('style');
    style.id = 'flex-paginator-styles';
    style.textContent = `
        html, body {
            margin: 0;
            padding: 0;
            width: 100%;
            height: 100%;
            overflow: hidden;
        }
        
        .paginator {
            display: flex;
            flex-direction: row;
            overflow-x: auto;
            overflow-y: hidden;
            scroll-snap-type: x mandatory;
            width: 100vw;
            height: 100vh;
            scroll-behavior: smooth;
            -webkit-overflow-scrolling: touch; /* iOS momentum scrolling */
        }
        
        .page {
            flex: 0 0 100%;
            min-width: 100vw;
            height: 100vh;
            scroll-snap-align: start;
            scroll-snap-stop: always;
            overflow: hidden;
            box-sizing: border-box;
            padding: 2rem; /* Reading margins */
        }
        
        /* Prevent text selection during swipe gestures */
        .paginator.swiping .page {
            user-select: none;
        }
    `;
    document.head.appendChild(style);
}
```

### 4. Scroll-Snap Navigation Controller (JavaScript)

**Responsibility**: Manage page navigation, boundary detection, progress tracking

```javascript
class ScrollSnapController {
    constructor(paginatorElement) {
        this.paginator = paginatorElement;
        this.pages = Array.from(paginatorElement.querySelectorAll('.page'));
        this.currentPageIndex = 0;
        this.isScrolling = false;
        this.scrollTimeout = null;
        
        this.attachScrollListener();
        this.attachTouchListener();
    }
    
    /**
     * Get total page count.
     */
    getPageCount() {
        return this.pages.length;
    }
    
    /**
     * Navigate to specific page index.
     */
    goToPage(pageIndex, smooth = true) {
        if (pageIndex < 0 || pageIndex >= this.pages.length) {
            console.warn(`Invalid page index: ${pageIndex}`);
            return false;
        }
        
        const targetPage = this.pages[pageIndex];
        const scrollLeft = targetPage.offsetLeft;
        
        this.paginator.scrollTo({
            left: scrollLeft,
            behavior: smooth ? 'smooth' : 'auto'
        });
        
        this.currentPageIndex = pageIndex;
        return true;
    }
    
    /**
     * Get current page index based on scroll position.
     */
    getCurrentPageIndex() {
        const scrollLeft = this.paginator.scrollLeft;
        const pageWidth = this.paginator.clientWidth;
        const computedIndex = Math.round(scrollLeft / pageWidth);
        return Math.min(Math.max(0, computedIndex), this.pages.length - 1);
    }
    
    /**
     * Attach scroll listener for progress tracking and boundary detection.
     */
    attachScrollListener() {
        this.paginator.addEventListener('scroll', () => {
            this.isScrolling = true;
            
            // Clear existing timeout
            if (this.scrollTimeout) {
                clearTimeout(this.scrollTimeout);
            }
            
            // Set new timeout
            this.scrollTimeout = setTimeout(() => {
                this.isScrolling = false;
                this.onScrollEnd();
            }, 150);
            
            this.updateProgress();
        });
    }
    
    /**
     * Attach touch listener for swipe detection.
     */
    attachTouchListener() {
        let startX = 0;
        
        this.paginator.addEventListener('touchstart', (e) => {
            startX = e.touches[0].clientX;
            this.paginator.classList.add('swiping');
        });
        
        this.paginator.addEventListener('touchend', () => {
            this.paginator.classList.remove('swiping');
        });
    }
    
    /**
     * Handle scroll end (snap complete).
     */
    onScrollEnd() {
        const newPageIndex = this.getCurrentPageIndex();
        
        if (newPageIndex !== this.currentPageIndex) {
            this.currentPageIndex = newPageIndex;
            this.onPageChanged(newPageIndex);
        }
    }
    
    /**
     * Update progress and check boundaries.
     */
    updateProgress() {
        const pageIndex = this.getCurrentPageIndex();
        const pageCount = this.getPageCount();
        const progress = pageCount > 0 ? (pageIndex + 1) / pageCount : 0;
        
        // Check boundaries
        if (pageIndex === 0) {
            this.onReachedStart();
        } else if (pageIndex === pageCount - 1) {
            this.onReachedEnd();
        }
        
        // Notify progress
        this.onProgressChanged(progress, pageIndex, pageCount);
    }
    
    /**
     * Callback: Page changed.
     */
    onPageChanged(pageIndex) {
        const page = this.pages[pageIndex];
        const chapterIndex = parseInt(page.getAttribute('data-chapter'), 10);
        const sliceIndex = parseInt(page.getAttribute('data-slice'), 10);
        
        console.log(`[FlexPaginator] Page changed: page=${pageIndex}, chapter=${chapterIndex}, slice=${sliceIndex}`);
        
        // Bridge to Android
        if (window.AndroidBridge) {
            window.AndroidBridge.onPageChanged(pageIndex, chapterIndex);
        }
    }
    
    /**
     * Callback: Progress changed.
     */
    onProgressChanged(progress, pageIndex, pageCount) {
        // Bridge to Android
        if (window.AndroidBridge) {
            window.AndroidBridge.onProgressChanged(progress, pageIndex, pageCount);
        }
    }
    
    /**
     * Callback: Reached start boundary.
     */
    onReachedStart() {
        console.log('[FlexPaginator] Reached start boundary');
        
        if (window.AndroidBridge) {
            window.AndroidBridge.onReachedStartBoundary();
        }
    }
    
    /**
     * Callback: Reached end boundary.
     */
    onReachedEnd() {
        console.log('[FlexPaginator] Reached end boundary');
        
        if (window.AndroidBridge) {
            window.AndroidBridge.onReachedEndBoundary();
        }
    }
}
```

### 5. WebView Lifecycle Manager (Kotlin)

**Location**: Integration into `ReaderPageFragment.kt` or new `FlexPaginationWebViewManager.kt`

**Responsibilities:**
- Load wrapped HTML into WebView
- Inject flex pagination script
- Execute initialization sequence
- Handle WebView lifecycle (create/destroy)

```kotlin
class FlexPaginationWebViewManager(
    private val webView: WebView,
    private val windowIndex: WindowIndex
) {
    
    companion object {
        private const val TAG = "FlexPaginationWebView"
        private const val FLEX_PAGINATOR_SCRIPT = "flex_paginator.js"
    }
    
    /**
     * Initialize WebView with flex pagination support.
     */
    fun initialize() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
        }
        
        // Add JavaScript interface for Android ↔ JS communication
        webView.addJavascriptInterface(
            FlexPaginatorBridge(this),
            "AndroidBridge"
        )
        
        AppLogger.d(TAG, "[FLEX_INIT] WebView initialized for window $windowIndex")
    }
    
    /**
     * Load window HTML and initialize flex pagination.
     */
    suspend fun loadWindow(wrappedHtml: String, initialChapterIndex: ChapterIndex? = null) {
        withContext(Dispatchers.Main) {
            // Load base HTML structure
            val baseHtml = buildBaseHtml(wrappedHtml)
            webView.loadDataWithBaseURL(
                "file:///android_asset/",
                baseHtml,
                "text/html",
                "UTF-8",
                null
            )
            
            // Wait for DOM ready
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    
                    // Execute flex pagination initialization
                    initializeFlexPagination(initialChapterIndex)
                }
            }
            
            AppLogger.d(TAG, "[FLEX_LOAD] Loading window $windowIndex HTML")
        }
    }
    
    /**
     * Build complete HTML document with flex paginator script.
     */
    private fun buildBaseHtml(wrappedHtml: String): String {
        val flexPaginatorScript = loadAssetScript(FLEX_PAGINATOR_SCRIPT)
        
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
                <title>Window $windowIndex</title>
            </head>
            <body>
                <div id="content-root">
                    $wrappedHtml
                </div>
                
                <script>
                    $flexPaginatorScript
                </script>
            </body>
            </html>
        """.trimIndent()
    }
    
    /**
     * Initialize flex pagination after DOM ready.
     */
    private fun initializeFlexPagination(initialChapterIndex: ChapterIndex?) {
        val initScript = """
            (function() {
                try {
                    // Inject paginator styles
                    injectPaginatorStyles();
                    
                    // Get wrapped HTML
                    const contentRoot = document.getElementById('content-root');
                    const wrappedHtml = contentRoot.innerHTML;
                    
                    // Slice into pages
                    const pages = sliceWrappedWindow(wrappedHtml, $windowIndex);
                    console.log('[FlexPaginator] Sliced into ' + pages.length + ' pages');
                    
                    // Create flex paginator
                    const paginator = createFlexPaginator(pages, $windowIndex);
                    
                    // Replace content with paginator
                    document.body.innerHTML = '';
                    document.body.appendChild(paginator);
                    
                    // Initialize scroll controller
                    const controller = new ScrollSnapController(paginator);
                    window.paginatorController = controller; // Expose globally
                    
                    // Navigate to initial chapter if specified
                    ${initialChapterIndex?.let { "controller.goToChapterStart($it);" } ?: ""}
                    
                    // Notify Android that pagination is ready
                    if (window.AndroidBridge) {
                        window.AndroidBridge.onPaginationReady(pages.length);
                    }
                    
                    console.log('[FlexPaginator] Initialization complete');
                } catch (error) {
                    console.error('[FlexPaginator] Initialization failed:', error);
                    if (window.AndroidBridge) {
                        window.AndroidBridge.onPaginationError(error.message);
                    }
                }
            })();
        """
        
        webView.evaluateJavascript(initScript, null)
        AppLogger.d(TAG, "[FLEX_INIT] Executed flex pagination initialization")
    }
    
    /**
     * Load JavaScript file from assets.
     */
    private fun loadAssetScript(filename: String): String {
        return webView.context.assets.open(filename).bufferedReader().use { it.readText() }
    }
    
    /**
     * Cleanup and destroy WebView.
     */
    fun cleanup() {
        webView.stopLoading()
        webView.clearHistory()
        webView.removeAllViews()
        webView.destroy()
        
        AppLogger.d(TAG, "[FLEX_CLEANUP] WebView destroyed for window $windowIndex")
    }
}
```

### 6. JavaScript Bridge (Kotlin)

**Communication interface between WebView JavaScript and Android**

```kotlin
class FlexPaginatorBridge(
    private val manager: FlexPaginationWebViewManager
) {
    
    /**
     * Called when pagination is ready with total page count.
     */
    @JavascriptInterface
    fun onPaginationReady(pageCount: Int) {
        AppLogger.d("FlexPaginatorBridge", "[JS→ANDROID] onPaginationReady: pageCount=$pageCount")
        // TODO: Notify ViewModel
    }
    
    /**
     * Called when pagination fails.
     */
    @JavascriptInterface
    fun onPaginationError(errorMessage: String) {
        AppLogger.e("FlexPaginatorBridge", "[JS→ANDROID] onPaginationError: $errorMessage")
        // TODO: Handle error
    }
    
    /**
     * Called when user changes page.
     */
    @JavascriptInterface
    fun onPageChanged(pageIndex: Int, chapterIndex: Int) {
        AppLogger.d("FlexPaginatorBridge", "[JS→ANDROID] onPageChanged: page=$pageIndex, chapter=$chapterIndex")
        // TODO: Update position tracking
    }
    
    /**
     * Called when scroll progress changes.
     */
    @JavascriptInterface
    fun onProgressChanged(progress: Double, pageIndex: Int, pageCount: Int) {
        // TODO: Update UI progress indicators
    }
    
    /**
     * Called when user reaches start boundary.
     */
    @JavascriptInterface
    fun onReachedStartBoundary() {
        AppLogger.d("FlexPaginatorBridge", "[JS→ANDROID] onReachedStartBoundary")
        // TODO: Trigger backward window shift
    }
    
    /**
     * Called when user reaches end boundary.
     */
    @JavascriptInterface
    fun onReachedEndBoundary() {
        AppLogger.d("FlexPaginatorBridge", "[JS→ANDROID] onReachedEndBoundary")
        // TODO: Trigger forward window shift
    }
}
```

---

## API Contracts

### Kotlin → JavaScript API

**Functions called from Android/Kotlin into WebView JavaScript:**

```kotlin
// Navigate to specific page
webView.evaluateJavascript("window.paginatorController.goToPage($pageIndex, true);", null)

// Navigate to chapter start
webView.evaluateJavascript("window.paginatorController.goToChapterStart($chapterIndex);", null)

// Get current page count
webView.evaluateJavascript("window.paginatorController.getPageCount();") { result ->
    val pageCount = result.toInt()
}

// Get current page index
webView.evaluateJavascript("window.paginatorController.getCurrentPageIndex();") { result ->
    val pageIndex = result.toInt()
}
```

### JavaScript → Kotlin API

**Functions called from JavaScript via AndroidBridge:**

```javascript
// Pagination lifecycle events
AndroidBridge.onPaginationReady(pageCount);
AndroidBridge.onPaginationError(errorMessage);

// Navigation events
AndroidBridge.onPageChanged(pageIndex, chapterIndex);
AndroidBridge.onProgressChanged(progress, pageIndex, pageCount);

// Boundary events (trigger window shifts)
AndroidBridge.onReachedStartBoundary();
AndroidBridge.onReachedEndBoundary();
```

---

## Implementation Considerations

### Performance Optimization

**1. Lazy Slicing**
- Don't slice all windows at startup
- Slice window when it enters buffer (onWindowEntered)
- Cache sliced pages to avoid re-slicing on revisit

**2. Measurement Optimization**
```javascript
// Instead of measuring every node individually
// Measure in batches for better performance
function measureNodesBatch(nodes) {
    const fragment = document.createDocumentFragment();
    const measurer = createMeasurerContainer();
    
    const heights = nodes.map(node => {
        const clone = node.cloneNode(true);
        fragment.appendChild(clone);
    });
    
    measurer.appendChild(fragment);
    document.body.appendChild(measurer);
    
    // Measure all at once (single reflow)
    const results = Array.from(measurer.children).map(el => el.offsetHeight);
    
    document.body.removeChild(measurer);
    return results;
}
```

**3. WebView Pooling**
- Reuse WebView instances when possible
- Destroy only when memory pressure detected
- Implement LRU cache for window WebViews

### Memory Management

**Buffer Lifecycle Integration:**
```kotlin
// When window leaves buffer (shiftForward/shiftBackward)
fun onWindowEvicted(windowIndex: WindowIndex) {
    // Destroy WebView
    windowWebViews[windowIndex]?.cleanup()
    windowWebViews.remove(windowIndex)
    
    // Clear cached HTML
    windowHtmlCache.remove(windowIndex)
    
    AppLogger.d(TAG, "[MEMORY] Evicted window $windowIndex, freed WebView")
}

// When window enters buffer
fun onWindowEntered(windowIndex: WindowIndex) {
    // Create new WebView
    val webView = createWebView()
    windowWebViews[windowIndex] = FlexPaginationWebViewManager(webView, windowIndex)
    
    // Load HTML and initialize
    viewModelScope.launch {
        val html = windowAssembler.buildWindow(windowIndex)
        windowWebViews[windowIndex]?.loadWindow(html)
    }
    
    AppLogger.d(TAG, "[MEMORY] Created WebView for window $windowIndex")
}
```

### Error Handling

**Slicing Failures:**
```javascript
try {
    const pages = sliceWrappedWindow(wrappedHtml, windowIndex);
    if (pages.length === 0) {
        throw new Error('Slicing produced zero pages');
    }
} catch (error) {
    console.error('[FlexPaginator] Slicing failed:', error);
    
    // Fallback: Create single page with all content
    const fallbackPage = createPage(windowIndex, 1, 0, 'Content');
    fallbackPage.innerHTML = wrappedHtml;
    
    AndroidBridge.onPaginationError('Slicing failed, using fallback: ' + error.message);
    return [fallbackPage];
}
```

**WebView Crashes:**
```kotlin
webView.webViewClient = object : WebViewClient() {
    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
        AppLogger.e(TAG, "[ERROR] WebView render process gone for window $windowIndex")
        
        // Recreate WebView
        recreateWebView(windowIndex)
        return true
    }
}
```

### Edge Cases

**1. Very Tall Elements**
```javascript
// If single element exceeds viewport height, allow it to overflow
if (nodeHeight > viewportHeight) {
    // Create dedicated page for this element
    const dedicatedPage = createPage(windowIndex, sliceIndex++, chapterIndex, chapterTitle);
    dedicatedPage.appendChild(clone);
    dedicatedPage.style.overflowY = 'auto'; // Allow scrolling within page
    pages.push(dedicatedPage);
    continue;
}
```

**2. Empty Chapters**
```javascript
// Skip chapters with no meaningful content
const hasContent = Array.from(section.childNodes).some(node => {
    return node.textContent.trim().length > 0 || 
           node.tagName === 'IMG' ||
           node.tagName === 'VIDEO';
});

if (!hasContent) {
    console.log(`[FlexPaginator] Skipping empty chapter ${chapterIndex}`);
    continue;
}
```

**3. Chapter Spanning Multiple Windows**
- Current design: Each window = 5 chapters (discrete boundaries)
- Future consideration: Support variable-sized windows or mid-chapter window breaks

### Backward Compatibility

**Migration Strategy:**
1. Implement flex pagination as **opt-in feature** (feature flag)
2. Keep existing CSS column pagination as default
3. Add configuration option in ReaderSettings:
   ```kotlin
   enum class PaginationEngine {
       CSS_COLUMNS,    // Current system
       FLEX_CONTAINER  // New system
   }
   ```
4. Test thoroughly before making default
5. Provide rollback mechanism if issues arise

---

## Migration Path

### Phase 1: Prototype (Weeks 1-2)
- [ ] Implement basic node-walking slicer in JavaScript
- [ ] Create flex container and scroll-snap CSS
- [ ] Test with single-window prototype
- [ ] Validate page boundaries and chapter breaks

### Phase 2: Integration (Weeks 3-4)
- [ ] Integrate with WindowBufferManager
- [ ] Implement WebView lifecycle management
- [ ] Add JavaScript ↔ Android bridge
- [ ] Wire up boundary detection to trigger window shifts

### Phase 3: Testing (Weeks 5-6)
- [ ] Test with various EPUBs (simple, complex, image-heavy)
- [ ] Performance profiling (memory, slicing time)
- [ ] Edge case testing (tall images, empty chapters, etc.)
- [ ] Multi-device testing (phones, tablets, different screen sizes)

### Phase 4: Polish (Weeks 7-8)
- [ ] Optimize slicing algorithm performance
- [ ] Add loading indicators during pagination
- [ ] Implement error recovery mechanisms
- [ ] Documentation and code cleanup

### Phase 5: Rollout (Weeks 9-10)
- [ ] Beta testing with opt-in feature flag
- [ ] Collect user feedback and metrics
- [ ] Fix reported issues
- [ ] Gradual rollout to all users

---

## Testing Strategy

### Unit Tests

**Slicing Algorithm Tests:**
```javascript
describe('Node-Walking Slicer', () => {
    test('should create hard break at chapter boundary', () => {
        const html = `
            <section data-chapter-index="0">Chapter 1</section>
            <section data-chapter-index="1">Chapter 2</section>
        `;
        const pages = sliceWrappedWindow(html, 0);
        
        // First page should end before second chapter
        expect(pages[0].getAttribute('data-chapter')).toBe('0');
        expect(pages[1].getAttribute('data-chapter')).toBe('1');
    });
    
    test('should respect viewport height', () => {
        // Create content taller than viewport
        const tallContent = '<p>' + 'Lorem ipsum '.repeat(1000) + '</p>';
        const html = `<section data-chapter-index="0">${tallContent}</section>`;
        
        const pages = sliceWrappedWindow(html, 0);
        
        // Should create multiple pages
        expect(pages.length).toBeGreaterThan(1);
        
        // Each page should be approximately viewport height
        pages.forEach(page => {
            expect(page.offsetHeight).toBeLessThanOrEqual(window.innerHeight * 1.1);
        });
    });
});
```

**Flex Container Tests:**
```javascript
describe('ScrollSnapController', () => {
    test('should navigate to correct page', () => {
        const controller = new ScrollSnapController(mockPaginator);
        controller.goToPage(5);
        
        expect(controller.getCurrentPageIndex()).toBe(5);
    });
    
    test('should detect boundaries', () => {
        const controller = new ScrollSnapController(mockPaginator);
        const startBoundarySpy = jest.spyOn(controller, 'onReachedStart');
        
        controller.goToPage(0);
        expect(startBoundarySpy).toHaveBeenCalled();
    });
});
```

### Integration Tests

**Conveyor + Flex Integration:**
```kotlin
@Test
fun `window shift should create new WebView with flex pagination`() = runTest {
    // Setup
    val bufferManager = createWindowBufferManager()
    bufferManager.initialize(startWindow = 0, totalWindows = 20)
    
    // Navigate forward to trigger shift
    repeat(10) {
        bufferManager.onProgressChanged(0.9) // Near end
    }
    
    // Verify
    val buffer = bufferManager.buffer.value
    assertTrue(buffer.contains(5)) // Window 5 created
    assertFalse(buffer.contains(0)) // Window 0 evicted
    
    // Verify WebView exists for window 5
    val webView = windowWebViews[5]
    assertNotNull(webView)
}
```

### Manual Testing Checklist

- [ ] Open book in CONTINUOUS mode with flex pagination enabled
- [ ] Verify smooth horizontal page swipes
- [ ] Confirm hard breaks at chapter boundaries (no mid-chapter breaks)
- [ ] Test rapid forward/backward navigation
- [ ] Verify window shifts occur correctly (buffer maintains 5 windows)
- [ ] Test boundary detection (start/end of window)
- [ ] Verify page count accuracy
- [ ] Test with books of various sizes (short, medium, very long)
- [ ] Test with image-heavy chapters
- [ ] Test on multiple devices (phones, tablets)
- [ ] Test with different font sizes
- [ ] Verify memory usage stays within bounds

---

## Performance Considerations

### Benchmarks and Targets

| Metric | Target | Measurement |
|--------|--------|-------------|
| **Slicing Time** | < 500ms per window | Time from HTML ready to pagination complete |
| **Memory per Window** | < 10MB | WebView + sliced DOM |
| **Max Memory (5 windows)** | < 50MB | Total active memory footprint |
| **Page Navigation** | < 100ms | Scroll-snap to next page |
| **Window Shift Time** | < 1s | Drop old window + create new window |

### Optimization Techniques

**1. Incremental Slicing**
```javascript
// Don't slice entire window at once
// Slice chapters incrementally as user progresses
async function sliceChapterIncremental(section, chapterIndex) {
    const pages = [];
    let currentPage = createPage(windowIndex, sliceIndex++, chapterIndex, '');
    
    // Process nodes in chunks to avoid blocking main thread
    const nodes = Array.from(section.childNodes);
    const CHUNK_SIZE = 50;
    
    for (let i = 0; i < nodes.length; i += CHUNK_SIZE) {
        const chunk = nodes.slice(i, i + CHUNK_SIZE);
        
        // Process chunk
        chunk.forEach(node => {
            // ... slicing logic
        });
        
        // Yield to main thread
        if (i % 100 === 0) {
            await new Promise(resolve => setTimeout(resolve, 0));
        }
    }
    
    return pages;
}
```

**2. Virtual Scrolling for Very Long Windows**
```javascript
// For windows with 100+ pages, implement virtual scrolling
// Only render pages ±2 from current page
class VirtualPaginator {
    constructor(pages, windowSize = 5) {
        this.allPages = pages;
        this.windowSize = windowSize;
        this.currentCenter = 0;
        this.renderedPages = new Map();
    }
    
    updateVisiblePages(centerIndex) {
        const start = Math.max(0, centerIndex - Math.floor(this.windowSize / 2));
        const end = Math.min(this.allPages.length, start + this.windowSize);
        
        // Render only visible pages
        for (let i = start; i < end; i++) {
            if (!this.renderedPages.has(i)) {
                this.renderPage(i);
            }
        }
        
        // Remove pages outside window
        this.renderedPages.forEach((page, index) => {
            if (index < start || index >= end) {
                this.unrenderPage(index);
            }
        });
    }
}
```

**3. WebView Recycling**
```kotlin
// Maintain pool of 3 extra WebViews for faster window creation
class WebViewPool(private val context: Context) {
    private val pool = mutableListOf<WebView>()
    private val maxSize = 3
    
    fun acquire(): WebView {
        return if (pool.isNotEmpty()) {
            pool.removeAt(0).apply {
                clearHistory()
                clearCache(false)
            }
        } else {
            createWebView(context)
        }
    }
    
    fun release(webView: WebView) {
        if (pool.size < maxSize) {
            pool.add(webView)
        } else {
            webView.destroy()
        }
    }
}
```

---

## Appendix

### Comparison: CSS Columns vs Flex Pagination

| Aspect | CSS Columns | Flex Pagination |
|--------|------------|-----------------|
| **Page Breaks** | Limited control (CSS column-break) | Full control (hard breaks) |
| **Chapter Boundaries** | May split mid-chapter | Guaranteed hard breaks |
| **Element Handling** | Automatic (may split elements) | Manual (element-level control) |
| **Performance** | Browser-optimized | Custom JS (potential overhead) |
| **Flexibility** | Limited | High (custom logic) |
| **Complexity** | Low (CSS-based) | Medium (JS + CSS) |
| **Browser Support** | Excellent | Excellent (flex + scroll-snap) |
| **Debugging** | Difficult (CSS rendering) | Easier (explicit pages) |

### References

- **Existing Implementation**: `inpage_paginator.js` (CSS column-based)
- **Conveyor System**: `WindowBufferManager.kt`, `ConveyorBeltSystemViewModel.kt`
- **Window HTML Builder**: `WindowHtmlBuilder.kt`
- **Documentation**: 
  - `docs/complete/ARCHITECTURE.md`
  - `docs/complete/WINDOW_COMMUNICATION_API.md`
  - `docs/implemented/CONVEYOR_BELT_QUICK_REF.md`
  - `Flex Pagination+Hard Breaks+Node‑Walk.md`

### Glossary

- **Window**: Group of 5 chapters loaded together in one WebView
- **Buffer**: Array of 5 active windows maintained by conveyor system
- **Slice**: Viewport-sized page created by node-walking algorithm
- **Hard Break**: Guaranteed page boundary at chapter start
- **Scroll-Snap**: CSS feature for discrete horizontal page navigation
- **Node-Walking**: DOM traversal technique for measuring and splitting content
- **Conveyor Phase**: STARTUP (initial load) or STEADY (continuous reading)

---

**Document Status**: Design Phase - Ready for Review and Implementation  
**Next Steps**: Review with team → Prototype Phase 1 → Integration Testing

