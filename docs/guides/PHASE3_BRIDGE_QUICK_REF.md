# Phase 3 Paginator Bridge - Quick Reference

**TL;DR**: Bridge refactored for Phase 3 - 52% smaller, new character offset APIs, ready for window management separation.

---

## What's New

### ✅ Character Offset APIs

```kotlin
// Get stable character offset for any page
val offset = bridge.getCharacterOffsetForPage(webView, 10)

// Jump to page by character offset (survives font changes!)
bridge.goToPageWithCharacterOffset(webView, offset)
```

### ❌ Removed Methods (→ Conveyor Belt)

```kotlin
// These don't exist anymore:
bridge.appendChapter()        // ❌ Use Conveyor instead
bridge.jumpToChapter()        // ❌ Use Conveyor instead
bridge.getChapterBoundaries() // ❌ Use Conveyor instead
bridge.loadWindow()           // ❌ Use Conveyor instead
// ... and 11 more
```

---

## Core API (Still Works)

```kotlin
// Setup
bridge.configure(webView, config)
bridge.initialize(webView, html)

// Check ready
val ready = bridge.isReady(webView)

// Navigation
bridge.goToPage(webView, 5)
bridge.nextPage(webView)
bridge.prevPage(webView)

// State
val page = bridge.getCurrentPage(webView)
val count = bridge.getPageCount(webView)

// Display
bridge.setFontSize(webView, 18)  // Triggers reflow
```

---

## The Key Change

### Before (Page indices = fragile)
```kotlin
bookmark.pageIndex = 5  // ❌ Wrong after font change!
```

### After (Character offsets = stable)
```kotlin
bookmark.charOffset = 1234  // ✅ Works after font change!
bridge.goToPageWithCharacterOffset(webView, 1234)  // Restores correctly
```

---

## Migration Checklist

- [ ] Replace `inpagePaginator` → `minimalPaginator` in JS calls
- [ ] Remove calls to deleted methods
- [ ] Update bookmarks to use character offsets
- [ ] Test font size changes
- [ ] Coordinate with Conveyor Belt for chapters

---

## Files to Read

1. **`MINIMAL_PAGINATOR_BRIDGE.md`** - Complete API reference
2. **`PHASE2_TO_PHASE3_MIGRATION.md`** - Migration examples
3. **`SESSION_PHASE3_BRIDGE_REFACTORING.md`** - Full summary

---

## Quick Stats

| Metric | Before | After |
|--------|--------|-------|
| Lines | 729 | 335 |
| Methods | 40+ | 13 |
| Object name | `inpagePaginator` | `minimalPaginator` |
| Character offset support | ❌ | ✅ |

---

## Error Handling

All methods are safe:
```kotlin
// Suspend functions return safe defaults on error
val count = bridge.getPageCount(webView)  // Returns -1 on error, never throws

// Sync functions silently fail
bridge.goToPage(webView, 5)  // Does nothing if not ready
```

---

## Example: Reading Session

```kotlin
// 1. Setup
bridge.configure(webView, PaginatorConfig(WINDOW, 5))
bridge.initialize(webView, htmlContent)

// 2. Wait ready
while (!bridge.isReady(webView)) delay(100)

// 3. Restore bookmark (NEW!)
if (bookmark.charOffset > 0) {
    bridge.goToPageWithCharacterOffset(webView, bookmark.charOffset)
}

// 4. Display progress
val page = bridge.getCurrentPage(webView)
val total = bridge.getPageCount(webView)
showProgress("$page/$total")

// 5. User navigation
bridge.nextPage(webView)

// 6. Font size change
bridge.setFontSize(webView, 18)  // Pages recalculated

// 7. Save bookmark (NEW!)
val newOffset = bridge.getCharacterOffsetForPage(webView, page)
bookmark.charOffset = newOffset  // Still works!
```

---

## JavaScript Object

Change everywhere:
```javascript
// Before
window.inpagePaginator.method()

// After
window.minimalPaginator.method()
```

---

## Status

- ✅ Bridge refactored
- ✅ Documentation complete
- ⏳ JavaScript implementation needed
- ⏳ Conveyor Belt integration needed
- ⏳ Testing & deployment

---

**Next**: Implement `minimal_paginator.js` and integrate with Conveyor Belt system.

See full docs for details!
