# Paginator Quick Reference: What It Is & Does

## The Paginator Purpose: A Focused Layout + Page-Indexing Engine

**What It Receives**:
- Block of HTML (chapter content)
- Viewport dimensions (1080x2400 on device)

**What It Does**:
1. **Computes pages** - Measures content with CSS columns to determine total pages
2. **Applies layout** - Renders content so one "page" visible at a time
3. **Indexes pages** - Maintains currentPage (0-based) and pageCount
4. **Navigates** - Moves between pages within the window
5. **Reports state** - Syncs page info to Kotlin via callback

**Example Flow**:
```
Window contains chapters 0-4, merged into HTML
  ↓
CSS columns applied (width = viewport = 1 page)
  ↓
Total scrollable width calculated = 47 pages × 1080px = 50760px
  ↓
pageCount = 50760 / 1080 = 47
  ↓
Call syncPaginationState(pageCount=47, currentPage=0)
  ↓
Kotlin reads: "OK, 47 pages in this window"
```

---

## What It Is NOT Responsible For

| Task | Owned By |
|------|----------|
| ❌ Chapter streaming | Conveyor Belt |
| ❌ Window switching | ReaderActivity/ReaderViewModel |
| ❌ Buffer management | WindowBufferManager |
| ❌ TOC navigation | ReaderViewModel |
| ❌ TTS text extraction | TtsWebBridge |
| ❌ Scroll gesture handling | GestureDetector |

---

## The Problem It Solves

**Before paginator**: "How many pages are in this content?"  
**Had to**: Use suspend functions, async JS eval, callbacks  
**Problem**: Race conditions, timeouts, stale values

**With paginator**: "Tell me when page state changes"  
**Now**: JavaScript syncs state immediately after layout  
**Benefit**: Kotlin reads cached values, always current

---

## Critical State: currentPage + pageCount

These two numbers determine **everything** about navigation:

```kotlin
val currentPage = 0    // Where user is now
val pageCount = 47     // How many pages total

// At start:
if (0 < 47-1) → nextPage()  ✓ Scroll within window

// At middle:
if (23 < 47-1) → nextPage()  ✓ Scroll within window

// At end:
if (46 < 47-1) → nextPage()  ✗ No pages left
                → nextWindow()  ✓ Jump to next window
```

**When these are wrong** (e.g., pageCount=-1):
- Edge detection fails
- Every key press jumps windows
- In-page scrolling impossible

---

## The Fix: State Synchronization

### Before
```
JS: "I computed 47 pages"
→ WebView callback async eval
→ Timeout (race condition)
→ Kotlin reads: pageCount=-1 (default)
❌ BROKEN
```

### After
```
JS: window.AndroidBridge._syncPaginationState(47, 0)
→ PaginationBridge receives @JavascriptInterface call
→ Updates WebViewPaginatorBridge cache
→ Kotlin reads: pageCount=47 (always current)
✓ WORKS
```

---

## Navigation Decision Tree

```
Volume Down pressed
│
├─► Get state from paginator
│   currentPage = cache (0)
│   pageCount = cache (47)
│
├─► Edge detection
│   if (currentPage < pageCount - 1)?
│   if (0 < 46)? = YES
│
└─► In-page scroll
    goToPage(1)  ← Page 0→1, stay in same window

─────────────

At last page:

Volume Down pressed
│
├─► Get state from paginator
│   currentPage = cache (46)
│   pageCount = cache (47)
│
├─► Edge detection
│   if (currentPage < pageCount - 1)?
│   if (46 < 46)? = NO
│
└─► Window transition
    nextWindow()  ← Jump to window 1
```

---

## Summary

| Aspect | Details |
|--------|---------|
| **Purpose** | Layout + page-indexing for HTML content |
| **Input** | HTML chapter(s), viewport size |
| **Output** | currentPage, pageCount, ability to navigate |
| **Lifecycle** | Initialized per window, persists until window unloads |
| **Dependency** | CSS columns (standard web tech) |
| **Error State** | pageCount=-1, currentPage=0 (defaults) |
| **Sync Mechanism** | JavaScript → Android Bridge → Kotlin cache |

The paginator is **not** complex - it's focused and does one thing well: **tell Kotlin how many pages are visible and where the user currently is**.

All higher-level concerns (chapters, windows, buffers, TOC) are handled by other systems.
