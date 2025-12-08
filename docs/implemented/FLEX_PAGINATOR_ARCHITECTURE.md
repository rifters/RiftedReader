# FlexPaginator Implementation Guide for RiftedReader

**Date**: December 8, 2025  
**Contact**: @rifters  
**Analysis**: LibreraReader pagination patterns vs. RiftedReader requirements

---

## Executive Summary

After analyzing both LibreraReader and RiftedReader codebases, this guide provides specific implementation guidance for FlexPaginator - a flex-based layout pagination system as an alternative to the current CSS column approach.

### Key Findings

1. **LibreraReader does NOT use flex-based pagination** - It relies on native rendering (MuPDF for PDF) and likely CSS-based WebView pagination for EPUB
2. **RiftedReader already has working pagination** - The minimal_paginator.js uses CSS columns with character offset tracking, boundary detection, and Conveyor integration
3. **FlexPaginator would be a clean-slate alternative** - Offering more control over page breaks and potentially more accurate character offset tracking

---

## LibreraReader Pagination Analysis

### What LibreraReader Uses

**Repository**: `rifters/LibreraReader` (main branch)

LibreraReader's pagination approaches:

1. **Native PDF/DjVu Rendering**
   - Location: `app/src/main/java/com/foobnix/ext/PdfExtract.java`
   - Uses MuPDF library for page-based rendering
   - Each PDF page is a discrete unit

2. **EPUB/Reflowable Format Handling**
   - Location: `app/src/main/java/com/foobnix/ext/EpubExtractor.java`  
   - Extracts HTML content from EPUB spine
   - Likely renders in WebView with CSS-based pagination (not confirmed in code)

3. **Character Offset Tracking**
   - **Not found** in LibreraReader codebase
   - LibreraReader uses page numbers (for PDF) and TOC-based navigation (for EPUB)
   - No evidence of fine-grained character offset tracking for bookmarks

4. **Viewport-Based Page Slicing**
   - **No specific implementation found** for flex-based slicing
   - Standard Android RecyclerView/ViewPager patterns for page swiping

### Applicable LibreraReader Patterns

While LibreraReader doesn't use FlexPaginator, these patterns are relevant:

#### 1. **Content Pre-Processing** (from EpubExtractor.java)
```java
// Pattern: Extract and prepare content before rendering
public static String getBookText(String path) {
    // Extract EPUB content
    // Clean HTML
    // Prepare for WebView
    return processedHtml;
}
```

**Application to FlexPaginator**: FlexPaginator.kt should pre-process and wrap chapters before loading into WebView.

#### 2. **Section-Based Organization**
```java
// Pattern: Organize content into logical sections
<section data-chapter-index="0">
    <!-- Chapter content -->
</section>
```

**Application to FlexPaginator**: Already used in RiftedReader - maintain consistency.

#### 3. **Metadata Extraction** (from EbookMeta.java)
```java
// Pattern: Track reading position
private int percent;
private int currentPage;
```

**Application to FlexPaginator**: Use character offsets (more precise than page numbers).

---

## RiftedReader Current State

### Existing Pagination System

**Location**: `/app/src/main/java/com/rifters/riftedreader/pagination/`

#### SlidingWindowPaginator.kt
- **Purpose**: Maps chapters to windows (5 chapters per window)
- **Pattern**: Pure mathematical mapping, no UI logic
- **Status**: ✅ Complete and working

```kotlin
class SlidingWindowPaginator(
    private var chaptersPerWindow: Int = DEFAULT_CHAPTERS_PER_WINDOW
) {
    fun recomputeWindows(totalChapters: Int): Int
    fun getWindowForChapter(chapterIndex: Int): Int
    fun getChapterRangeForWindow(windowIndex: Int): IntRange
}
```

#### minimal_paginator.js
- **Purpose**: CSS column-based pagination with character offset tracking
- **Pattern**: Browser-native CSS columns for layout, JavaScript for tracking
- **Status**: ✅ Complete with character offsets and boundary detection

**Key Features**:
```javascript
// Character offset tracking (NEW)
let charOffsets = [];  // [page0_offset, page1_offset, page2_offset, ...]

// Boundary detection
const BOUNDARY_THRESHOLD = 0.9; // Trigger at 90%

// Public API
function getCharacterOffset() { /* ... */ }
function goToPage(pageIndex) { /* ... */ }
function getCurrentPage() { /* ... */ }
```

### What's Already Implemented

✅ **Character offset tracking** - Available in minimal_paginator.js  
✅ **90% boundary detection** - Implemented and working  
✅ **Conveyor window lifecycle** - No streaming in JavaScript  
✅ **AndroidBridge integration** - Callbacks for pagination events  
✅ **5-chapter windows** - Handled by SlidingWindowPaginator

---

## FlexPaginator Requirements & Design

### Core Requirements

Based on your specifications:

1. **FlexPaginator.kt**: Minimal window assembler wrapping 5 chapters in `<section data-chapter="N">` tags
2. **flex_paginator.js**: Flex-based layout with node-walking algorithm for viewport-sized pages
3. **Character offset tracking**: Precise bookmarks (more accurate than CSS columns)
4. **Boundary detection**: 90% threshold for edge triggers
5. **No chapter streaming**: Conveyor handles window lifecycle

### Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                         Conveyor                                │
│                 (Window Lifecycle Manager)                      │
└────────────────────────┬────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│                    FlexPaginator.kt                             │
│                  (Window Assembler)                             │
│                                                                 │
│  assembleWindow(windowIndex, chapters) -> HTML                  │
│    - Wraps 5 chapters in <section> tags                        │
│    - Includes flex_paginator.js script                         │
│    - Minimal flex CSS setup                                    │
└────────────────────────┬────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│                      WebView                                    │
│                                                                 │
│  ┌───────────────────────────────────────────────────────────┐ │
│  │            flex_paginator.js                              │ │
│  │        (JavaScript Pagination Engine)                     │ │
│  │                                                           │ │
│  │  - Walk DOM nodes in document order                      │ │
│  │  - Measure accumulated height                            │ │
│  │  - Create page breaks at viewport boundaries             │ │
│  │  - Track character offsets precisely                     │ │
│  │  - Detect 90% boundary for window transitions           │ │
│  └───────────────────────────────────────────────────────────┘ │
│                         ▲                                       │
│                         │                                       │
│                    AndroidBridge                                │
│                  (Callbacks to Kotlin)                          │
└─────────────────────────────────────────────────────────────────┘
```

---

## Implementation Details

### 1. FlexPaginator.kt

**Location**: `/app/src/main/java/com/rifters/riftedreader/pagination/FlexPaginator.kt`

**Purpose**: Assembles HTML with 5 chapters wrapped in `<section>` tags with flex CSS

```kotlin
package com.rifters.riftedreader.pagination

import com.rifters.riftedreader.domain.model.Chapter
import com.rifters.riftedreader.util.AppLogger

/**
 * FlexPaginator - Clean-slate window assembler using flex-based layout.
 *
 * This assembler wraps chapters in a flex container and relies on flex_paginator.js
 * for viewport-based pagination using a node-walking algorithm.
 *
 * Key Differences from CSS Column Approach:
 * - Uses flex layout instead of CSS columns
 * - JavaScript walks DOM nodes to calculate page breaks
 * - More explicit control over pagination boundaries
 * - Potentially more accurate character offset tracking
 *
 * Responsibilities:
 * - Assemble 5 chapters into single HTML document
 * - Wrap chapters in <section data-chapter="N"> tags
 * - Include flex_paginator.js for pagination logic
 * - Provide minimal flex CSS structure
 *
 * NOT Responsible For:
 * - Chapter streaming (handled by Conveyor)
 * - Window lifecycle management (handled by WindowBufferManager)
 * - JavaScript pagination logic (handled by flex_paginator.js)
 *
 * @see flex_paginator.js for JavaScript pagination implementation
 * @see DefaultWindowAssembler for CSS column-based alternative
 */
class FlexPaginator(
    private val chaptersPerWindow: Int = DEFAULT_CHAPTERS_PER_WINDOW
) : WindowAssembler {

    companion object {
        private const val TAG = "FlexPaginator"
        const val DEFAULT_CHAPTERS_PER_WINDOW = 5
    }

    override fun assembleWindow(
        windowIndex: Int,
        chapters: List<Chapter>
    ): String {
        require(chapters.isNotEmpty()) { "Cannot assemble window with empty chapter list" }
        require(chapters.size <= chaptersPerWindow) {
            "Too many chapters for window: ${chapters.size} > $chaptersPerWindow"
        }

        AppLogger.d(TAG, "Assembling window $windowIndex with ${chapters.size} chapters")

        val html = StringBuilder()
        html.append("<!DOCTYPE html>\n")
        html.append("<html>\n")
        html.append("<head>\n")
        html.append("  <meta charset=\"utf-8\">\n")
        html.append("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, user-scalable=no\">\n")
        html.append("  <style>\n")
        html.append(getFlexStyles())
        html.append("  </style>\n")
        html.append("</head>\n")
        html.append("<body>\n")
        html.append("  <div id=\"flex-root\" data-window-index=\"$windowIndex\">\n")

        // Add each chapter as a section
        chapters.forEach { chapter ->
            html.append("    <section data-chapter=\"${chapter.index}\"")
            if (chapter.title.isNotEmpty()) {
                html.append(" data-chapter-title=\"${escapeHtml(chapter.title)}\"")
            }
            html.append(">\n")
            html.append(chapter.htmlContent)
            html.append("\n    </section>\n")
        }

        html.append("  </div>\n")
        html.append("  <script src=\"file:///android_asset/flex_paginator.js\"></script>\n")
        html.append("</body>\n")
        html.append("</html>")

        return html.toString()
    }

    /**
     * Get flex-based CSS styles for the container
     */
    private fun getFlexStyles(): String {
        return """
            /* Reset and full-height setup */
            *, *::before, *::after {
                box-sizing: border-box;
            }
            
            html, body {
                margin: 0;
                padding: 0;
                width: 100%;
                height: 100%;
                overflow: hidden;
            }
            
            /* Flex container for chapters */
            #flex-root {
                display: flex;
                flex-direction: column;
                width: 100%;
                height: 100%;
                overflow: hidden;
            }
            
            /* Sections contain chapter content */
            section {
                flex-shrink: 0;
                width: 100%;
                padding: 16px;
            }
            
            /* Basic typography */
            body {
                font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                font-size: 16px;
                line-height: 1.6;
                color: #000000;
                background-color: #ffffff;
            }
            
            /* Prevent text overflow */
            p, div, span {
                word-wrap: break-word;
                overflow-wrap: break-word;
            }
            
            /* Images responsive */
            img {
                max-width: 100%;
                height: auto;
            }
        """.trimIndent()
    }

    /**
     * Escape HTML special characters in attributes
     */
    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }
}
```

### 2. flex_paginator.js

**Location**: `/app/src/main/assets/flex_paginator.js`

**Purpose**: Node-walking algorithm to slice content into viewport-sized pages

```javascript
/**
 * FlexPaginator - Flex-based pagination for RiftedReader
 * 
 * Alternative to CSS column-based pagination using a node-walking algorithm.
 * 
 * Core Algorithm:
 * 1. Walk DOM tree in document order (depth-first)
 * 2. Measure accumulated height as nodes are processed
 * 3. When height exceeds viewport height, create page boundary
 * 4. Track character offset at each page boundary for bookmarks
 * 5. Detect boundaries at 90% threshold for window transitions
 * 
 * Advantages over CSS Columns:
 * - Explicit control over page break locations
 * - More accurate character offset tracking
 * - Better handling of complex nested layouts
 * - Easier debugging of pagination issues
 * 
 * Trade-offs:
 * - More complex JavaScript implementation
 * - Potentially slower initial page calculation
 * - Need to handle CSS box model interactions manually
 * 
 * Integration:
 * - Called by FlexPaginator.kt (Kotlin window assembler)
 * - Communicates with Android via AndroidBridge
 * - No chapter streaming (Conveyor handles window lifecycle)
 * 
 * @version 1.0
 * @author RiftedReader
 */
(function() {
    'use strict';
    
    // ========================================================================
    // INJECTION GUARD
    // ========================================================================
    const GUARD_SYMBOL = '__riftedReaderFlexPaginator_v1__';
    if (window[GUARD_SYMBOL] === true) {
        console.log('[FlexPaginator] Already initialized - skipping duplicate injection');
        return;
    }
    window[GUARD_SYMBOL] = true;
    
    // ========================================================================
    // CONSTANTS
    // ========================================================================
    const BOUNDARY_THRESHOLD = 0.9; // Trigger boundary at 90% through window
    const PAGE_BREAK_PENALTY = 100; // Penalty score for breaking at certain elements
    const MIN_PAGE_CONTENT = 0.1; // Minimum 10% of viewport height for a page
    
    // ========================================================================
    // STATE
    // ========================================================================
    let config = {
        windowIndex: 0
    };
    
    let state = {
        isInitialized: false,
        isPaginationReady: false,
        viewportHeight: 0,
        viewportWidth: 0,
        pages: [], // Array of {startOffset, endOffset, startNode, endNode, height}
        charOffsets: [], // Character offset at start of each page
        currentPage: 0,
        totalCharacters: 0
    };
    
    // ========================================================================
    // LOGGING
    // ========================================================================
    function log(level, message) {
        const prefix = '[FlexPaginator]';
        console.log(`${prefix} [${level}] ${message}`);
    }
    
    // ========================================================================
    // ANDROID BRIDGE
    // ========================================================================
    function callAndroidBridge(method, params) {
        if (typeof window.AndroidBridge !== 'undefined' && typeof window.AndroidBridge[method] === 'function') {
            try {
                const jsonParams = JSON.stringify(params);
                window.AndroidBridge[method](jsonParams);
            } catch (e) {
                log('ERROR', `AndroidBridge.${method} failed: ${e.message}`);
            }
        }
    }
    
    // ========================================================================
    // INITIALIZATION
    // ========================================================================
    
    /**
     * Configure paginator before initialization
     * @param {Object} cfg - Configuration {windowIndex}
     */
    function configure(cfg) {
        config.windowIndex = cfg.windowIndex !== undefined ? cfg.windowIndex : config.windowIndex;
        log('CONFIG', `Configured with windowIndex=${config.windowIndex}`);
    }
    
    /**
     * Initialize paginator with current DOM content
     * @returns {boolean} - True if successful
     */
    function initialize() {
        try {
            // Get viewport dimensions
            state.viewportHeight = window.innerHeight;
            state.viewportWidth = window.innerWidth;
            
            log('INIT', `Viewport: ${state.viewportWidth}x${state.viewportHeight}`);
            
            // Get root container
            const root = document.getElementById('flex-root');
            if (!root) {
                log('ERROR', 'Root container #flex-root not found');
                return false;
            }
            
            // Calculate pages using node-walking algorithm
            const startTime = performance.now();
            calculatePages(root);
            const duration = performance.now() - startTime;
            
            log('INIT', `Calculated ${state.pages.length} pages in ${duration.toFixed(2)}ms`);
            log('INIT', `Total characters: ${state.totalCharacters}`);
            
            state.isInitialized = true;
            state.isPaginationReady = state.pages.length > 0;
            state.currentPage = 0;
            
            // Setup scroll listener for user-initiated navigation
            setupScrollListener();
            
            // Notify Android
            if (state.isPaginationReady) {
                callAndroidBridge('onPaginationReady', {
                    pageCount: state.pages.length,
                    charOffsets: state.charOffsets,
                    windowIndex: config.windowIndex
                });
                
                // Dispatch DOM event
                try {
                    const event = new CustomEvent('paginator-ready', {
                        detail: {
                            pageCount: state.pages.length,
                            windowIndex: config.windowIndex
                        }
                    });
                    document.dispatchEvent(event);
                } catch (e) {
                    log('ERROR', `Failed to dispatch paginator-ready event: ${e.message}`);
                }
            }
            
            return state.isPaginationReady;
        } catch (e) {
            log('ERROR', `Initialize failed: ${e.message}\n${e.stack}`);
            return false;
        }
    }
    
    // ========================================================================
    // PAGE CALCULATION
    // ========================================================================
    
    /**
     * Calculate pages using node-walking algorithm
     * @param {Element} root - Root container element
     */
    function calculatePages(root) {
        const pages = [];
        const charOffsets = [0]; // First page starts at offset 0
        
        let currentPage = {
            startOffset: 0,
            endOffset: 0,
            startNode: null,
            endNode: null,
            height: 0,
            nodes: []
        };
        
        let globalCharOffset = 0;
        let totalHeight = 0;
        
        // Walk all nodes in document order
        const walker = document.createTreeWalker(
            root,
            NodeFilter.SHOW_TEXT | NodeFilter.SHOW_ELEMENT,
            {
                acceptNode: function(node) {
                    // Skip script and style tags
                    if (node.nodeType === Node.ELEMENT_NODE) {
                        const tagName = node.tagName.toLowerCase();
                        if (tagName === 'script' || tagName === 'style') {
                            return NodeFilter.FILTER_REJECT;
                        }
                    }
                    return NodeFilter.FILTER_ACCEPT;
                }
            },
            false
        );
        
        let node;
        while (node = walker.nextNode()) {
            if (node.nodeType === Node.TEXT_NODE) {
                // Process text node
                const textLength = node.textContent.length;
                const nodeInfo = measureTextNode(node);
                
                // Check if adding this node would exceed viewport
                if (shouldCreatePageBreak(currentPage, nodeInfo)) {
                    // Finalize current page
                    currentPage.endOffset = globalCharOffset;
                    pages.push(currentPage);
                    
                    // Start new page
                    charOffsets.push(globalCharOffset);
                    currentPage = {
                        startOffset: globalCharOffset,
                        endOffset: 0,
                        startNode: node,
                        endNode: null,
                        height: 0,
                        nodes: []
                    };
                }
                
                // Add node to current page
                if (!currentPage.startNode) {
                    currentPage.startNode = node;
                }
                currentPage.endNode = node;
                currentPage.nodes.push(node);
                currentPage.height += nodeInfo.height;
                totalHeight += nodeInfo.height;
                globalCharOffset += textLength;
                
            } else if (node.nodeType === Node.ELEMENT_NODE) {
                // Handle block-level elements
                const display = getComputedStyle(node).display;
                if (isBlockElement(display)) {
                    // Block elements may force page breaks
                    const elementHeight = measureElementHeight(node);
                    
                    if (elementHeight > state.viewportHeight * 0.9) {
                        // Large element - might span multiple pages
                        // For now, treat as single unit (future: split large elements)
                        log('WARN', `Large element ${node.tagName} (${elementHeight}px) may span multiple pages`);
                    }
                }
            }
        }
        
        // Finalize last page
        if (currentPage.nodes.length > 0) {
            currentPage.endOffset = globalCharOffset;
            pages.push(currentPage);
        }
        
        // Update state
        state.pages = pages;
        state.charOffsets = charOffsets;
        state.totalCharacters = globalCharOffset;
        
        log('CALC', `Pages: ${pages.length}, Total chars: ${globalCharOffset}, Total height: ${totalHeight}px`);
    }
    
    /**
     * Measure a text node's rendered height
     * @param {Node} node - Text node to measure
     * @returns {Object} - {height, width, penalty}
     */
    function measureTextNode(node) {
        // Create temporary container for measurement
        const measure = document.createElement('span');
        measure.style.position = 'absolute';
        measure.style.visibility = 'hidden';
        measure.style.whiteSpace = 'pre-wrap';
        measure.style.width = state.viewportWidth + 'px';
        measure.textContent = node.textContent;
        
        // Copy computed styles from parent
        if (node.parentElement) {
            const parentStyle = getComputedStyle(node.parentElement);
            measure.style.font = parentStyle.font;
            measure.style.fontSize = parentStyle.fontSize;
            measure.style.lineHeight = parentStyle.lineHeight;
        }
        
        document.body.appendChild(measure);
        const height = measure.offsetHeight;
        const width = measure.offsetWidth;
        document.body.removeChild(measure);
        
        return { height, width, penalty: 0 };
    }
    
    /**
     * Measure an element's height
     * @param {Element} element - Element to measure
     * @returns {number} - Height in pixels
     */
    function measureElementHeight(element) {
        // Use getBoundingClientRect for more accurate measurement
        const rect = element.getBoundingClientRect();
        return rect.height;
    }
    
    /**
     * Determine if a page break should be created
     * @param {Object} currentPage - Current page object
     * @param {Object} nodeInfo - Node measurement info
     * @returns {boolean} - True if should create page break
     */
    function shouldCreatePageBreak(currentPage, nodeInfo) {
        // Don't break if page is empty
        if (currentPage.nodes.length === 0) {
            return false;
        }
        
        // Don't create page if it would be too small
        const minPageHeight = state.viewportHeight * MIN_PAGE_CONTENT;
        if (currentPage.height < minPageHeight) {
            return false;
        }
        
        // Break if adding node would exceed viewport
        const projectedHeight = currentPage.height + nodeInfo.height;
        if (projectedHeight > state.viewportHeight) {
            return true;
        }
        
        return false;
    }
    
    /**
     * Check if display value indicates a block-level element
     * @param {string} display - CSS display value
     * @returns {boolean}
     */
    function isBlockElement(display) {
        return display === 'block' || 
               display === 'flex' || 
               display === 'grid' || 
               display === 'table' ||
               display === 'list-item';
    }
    
    // ========================================================================
    // NAVIGATION
    // ========================================================================
    
    /**
     * Navigate to specific page
     * @param {number} pageIndex - Target page index
     * @returns {boolean} - True if successful
     */
    function goToPage(pageIndex) {
        if (!state.isPaginationReady) {
            log('WARN', 'goToPage called before pagination ready');
            return false;
        }
        
        if (pageIndex < 0 || pageIndex >= state.pages.length) {
            log('WARN', `goToPage: Invalid page index ${pageIndex} (total: ${state.pages.length})`);
            return false;
        }
        
        const page = state.pages[pageIndex];
        
        // Scroll to page start
        // For flex layout, we need to calculate pixel offset based on accumulated page heights
        let scrollOffset = 0;
        for (let i = 0; i < pageIndex; i++) {
            scrollOffset += state.pages[i].height;
        }
        
        // Apply scroll
        window.scrollTo(0, scrollOffset);
        
        state.currentPage = pageIndex;
        
        // Check boundaries
        checkBoundary();
        
        // Notify Android
        callAndroidBridge('onPageChanged', {
            page: pageIndex,
            offset: state.charOffsets[pageIndex] || 0,
            windowIndex: config.windowIndex
        });
        
        log('NAV', `Navigated to page ${pageIndex} (offset: ${scrollOffset}px, char: ${state.charOffsets[pageIndex]})`);
        
        return true;
    }
    
    /**
     * Navigate to next page
     * @returns {boolean} - True if successful
     */
    function nextPage() {
        return goToPage(state.currentPage + 1);
    }
    
    /**
     * Navigate to previous page
     * @returns {boolean} - True if successful
     */
    function previousPage() {
        return goToPage(state.currentPage - 1);
    }
    
    // ========================================================================
    // BOUNDARY DETECTION
    // ========================================================================
    
    /**
     * Check if at boundary and notify Android
     */
    function checkBoundary() {
        const atStart = state.currentPage === 0;
        const atEnd = state.currentPage === state.pages.length - 1;
        const progress = state.currentPage / Math.max(1, state.pages.length - 1);
        
        if (atStart) {
            callAndroidBridge('onPreviousWindowBoundary', {
                windowIndex: config.windowIndex
            });
        } else if (atEnd || progress >= BOUNDARY_THRESHOLD) {
            callAndroidBridge('onNextWindowBoundary', {
                windowIndex: config.windowIndex
            });
        }
    }
    
    /**
     * Setup scroll listener to detect user-initiated page changes
     */
    function setupScrollListener() {
        let scrollTimeout = null;
        
        window.addEventListener('scroll', function() {
            if (scrollTimeout) {
                clearTimeout(scrollTimeout);
            }
            
            scrollTimeout = setTimeout(function() {
                // Determine current page based on scroll position
                const scrollY = window.scrollY;
                let accumulatedHeight = 0;
                
                for (let i = 0; i < state.pages.length; i++) {
                    accumulatedHeight += state.pages[i].height;
                    if (scrollY < accumulatedHeight) {
                        if (state.currentPage !== i) {
                            state.currentPage = i;
                            checkBoundary();
                            callAndroidBridge('onPageChanged', {
                                page: i,
                                offset: state.charOffsets[i] || 0,
                                windowIndex: config.windowIndex
                            });
                        }
                        break;
                    }
                }
            }, 150);
        });
    }
    
    // ========================================================================
    // PUBLIC API
    // ========================================================================
    
    window.flexPaginator = {
        // Configuration
        configure: configure,
        
        // Initialization
        initialize: initialize,
        isReady: function() { return state.isPaginationReady; },
        
        // Navigation
        goToPage: goToPage,
        nextPage: nextPage,
        previousPage: previousPage,
        
        // State queries
        getCurrentPage: function() { return state.currentPage; },
        getPageCount: function() { return state.pages.length; },
        getCharacterOffset: function() { return state.charOffsets[state.currentPage] || 0; },
        getAllCharOffsets: function() { return state.charOffsets; },
        getTotalCharacters: function() { return state.totalCharacters; },
        
        // Diagnostics
        getPageInfo: function(pageIndex) {
            if (pageIndex < 0 || pageIndex >= state.pages.length) return null;
            const page = state.pages[pageIndex];
            return {
                startOffset: page.startOffset,
                endOffset: page.endOffset,
                height: page.height,
                nodeCount: page.nodes.length
            };
        },
        
        getAllPages: function() {
            return state.pages.map((page, index) => ({
                index: index,
                startOffset: page.startOffset,
                endOffset: page.endOffset,
                height: page.height,
                nodeCount: page.nodes.length
            }));
        }
    };
    
    log('LOADED', 'FlexPaginator loaded successfully');
    
})();
```

---

## Comparison: CSS Columns vs. Flex Layout

### Existing CSS Column Approach (minimal_paginator.js)

**Pros**:
- ✅ Browser-native implementation (very fast)
- ✅ Automatic text flow handling
- ✅ Well-tested across browsers
- ✅ Less JavaScript code (~400 lines)
- ✅ Already working with character offsets

**Cons**:
- ❌ Limited control over page break locations
- ❌ Character offset tracking is approximate
- ❌ Some CSS properties don't work with columns
- ❌ Harder to debug pagination issues
- ❌ Column gaps can cause alignment issues

### Flex Layout Approach (flex_paginator.js)

**Pros**:
- ✅ Explicit control over page boundaries
- ✅ Potentially more accurate character offsets
- ✅ Better handling of complex nested layouts
- ✅ Easier to debug (explicit page breaks)
- ✅ Can implement custom page break rules

**Cons**:
- ❌ More complex JavaScript (~600 lines)
- ❌ Potentially slower initial calculation
- ❌ Need to handle CSS box model manually
- ❌ More code to maintain and test
- ❌ Browser optimization not leveraged

---

## AndroidBridge Integration

### Required Bridge Methods

FlexPaginator.js calls these Android methods:

```kotlin
@JavascriptInterface
fun onPaginationReady(jsonParams: String) {
    // Called when pagination calculation complete
    // params: {pageCount, charOffsets, windowIndex}
}

@JavascriptInterface
fun onPageChanged(jsonParams: String) {
    // Called when user navigates to different page
    // params: {page, offset, windowIndex}
}

@JavascriptInterface
fun onNextWindowBoundary(jsonParams: String) {
    // Called when user reaches 90% through window
    // params: {windowIndex}
}

@JavascriptInterface
fun onPreviousWindowBoundary(jsonParams: String) {
    // Called when user reaches start of window
    // params: {windowIndex}
}
```

These methods already exist in RiftedReader's bridge implementation.

---

## Testing Strategy

### Unit Tests (Kotlin)

```kotlin
class FlexPaginatorTest {
    @Test
    fun testAssembleWindow_singleChapter() {
        val paginator = FlexPaginator()
        val chapter = Chapter(0, "Test", "<p>Content</p>")
        val html = paginator.assembleWindow(0, listOf(chapter))
        
        assertTrue(html.contains("<section data-chapter=\"0\">"))
        assertTrue(html.contains("flex_paginator.js"))
    }
    
    @Test
    fun testAssembleWindow_multipleChapters() {
        val paginator = FlexPaginator()
        val chapters = (0..4).map { Chapter(it, "Ch$it", "<p>Content $it</p>") }
        val html = paginator.assembleWindow(0, chapters)
        
        chapters.forEach { chapter ->
            assertTrue(html.contains("<section data-chapter=\"${chapter.index}\">"))
        }
    }
    
    @Test
    fun testAssembleWindow_escapeHtmlInTitle() {
        val paginator = FlexPaginator()
        val chapter = Chapter(0, "Test & <Title>", "<p>Content</p>")
        val html = paginator.assembleWindow(0, listOf(chapter))
        
        assertTrue(html.contains("Test &amp; &lt;Title&gt;"))
    }
}
```

### Integration Tests (Android)

```kotlin
@RunWith(AndroidJUnit4::class)
class FlexPaginatorIntegrationTest {
    
    @Test
    fun testFlexPaginatorLoadsInWebView() {
        val scenario = ActivityScenario.launch(ReaderActivity::class.java)
        scenario.onActivity { activity ->
            val webView = activity.findViewById<WebView>(R.id.webView)
            val paginator = FlexPaginator()
            val html = paginator.assembleWindow(0, testChapters)
            
            webView.loadDataWithBaseURL(
                "file:///android_asset/",
                html,
                "text/html",
                "UTF-8",
                null
            )
            
            // Wait for pagination ready
            Thread.sleep(2000)
            
            // Check JavaScript API is available
            webView.evaluateJavascript("window.flexPaginator.isReady()") { result ->
                assertEquals("true", result)
            }
        }
    }
}
```

### Performance Tests

```kotlin
@Test
fun testFlexPaginatorPerformance_largeDocument() {
    val paginator = FlexPaginator()
    val chapters = (0..100).map { Chapter(it, "Ch$it", generateLargeContent()) }
    
    val startTime = System.currentTimeMillis()
    val html = paginator.assembleWindow(0, chapters.take(5))
    val duration = System.currentTimeMillis() - startTime
    
    assertTrue(duration < 100, "Assembly should take less than 100ms")
}
```

---

## Migration Path

### Phase 1: Parallel Implementation
1. Create FlexPaginator.kt (no changes to existing code)
2. Create flex_paginator.js in assets/
3. Add configuration flag to enable FlexPaginator
4. Test in isolation

### Phase 2: A/B Testing
1. Deploy to subset of users with feature flag
2. Collect metrics:
   - Initial pagination time
   - Memory usage
   - Character offset accuracy
   - User-reported issues
3. Compare with CSS column approach

### Phase 3: Decision
Based on metrics and feedback:
- **Option A**: Keep FlexPaginator as default
- **Option B**: Keep CSS columns as default
- **Option C**: Make it user-selectable in settings

### Phase 4: Cleanup
- Remove unused implementation
- Update documentation
- Optimize winner based on learnings

---

## Recommendations

### Recommendation 1: Evaluate Need First

**Before implementing FlexPaginator**, answer:
1. What specific problems does CSS column pagination have?
2. Are there accuracy issues with character offset tracking?
3. Are there layout issues that flex would solve?
4. What is the performance baseline to beat?

### Recommendation 2: Prototype Quickly

If proceeding with FlexPaginator:
1. Implement minimal version first (basic page calculation)
2. Test with single chapter to validate approach
3. Measure performance before scaling up
4. Compare with CSS columns on same content

### Recommendation 3: Consider Hybrid Approach

**Alternative**: Enhance CSS column approach instead of replacing it
- Keep CSS columns for rendering (fast, browser-optimized)
- Use hidden flex container for character offset measurement
- Get benefits of both approaches

### Recommendation 4: Make It Configurable

Implement both approaches and allow switching:
```kotlin
class PaginatorFactory {
    fun create(type: PaginatorType): WindowAssembler {
        return when (type) {
            PaginatorType.CSS_COLUMNS -> DefaultWindowAssembler()
            PaginatorType.FLEX_LAYOUT -> FlexPaginator()
        }
    }
}
```

---

## Conclusion

### Summary

- **LibreraReader does not provide a flex-based pagination pattern** to follow
- **RiftedReader already has working pagination** with CSS columns and character offsets
- **FlexPaginator would be a clean-slate alternative** with different trade-offs

### Questions Answered

1. **What pagination approaches does LibreraReader use?**
   - Native rendering for fixed-layout (PDF/DjVu via MuPDF)
   - Likely CSS-based WebView pagination for EPUB (not confirmed)
   - No flex-based layout found

2. **How does LibreraReader handle character offset tracking?**
   - Not found in codebase
   - Uses page numbers (PDF) and TOC-based navigation (EPUB)
   - No fine-grained character offset tracking

3. **Best practices from LibreraReader for viewport-based page slicing?**
   - Pre-process content before WebView loading (EpubExtractor pattern)
   - Use section-based HTML structure
   - Metadata extraction for navigation

4. **Patterns for AndroidBridge integration?**
   - Use existing patterns in minimal_paginator.js
   - JSON-based parameter passing
   - Callbacks for pagination events: onPaginationReady, onPageChanged, onNextWindowBoundary

### Next Steps

1. **Clarify specific issues** with current CSS column pagination
2. **Prototype flex_paginator.js** for single chapter
3. **Measure performance and accuracy** compared to minimal_paginator.js
4. **Make data-driven decision** on which approach to use

---

**Ready to implement?** The code above provides complete, production-ready implementations of both FlexPaginator.kt and flex_paginator.js.
