# Paginator Refactor Plan: Minimalist V2

**Date**: December 6, 2025  
**Branch**: development  
**Status**: Planning Phase

---

## Overview

Replace the current 2400-line `inpage_paginator.js` with a **minimal, focused paginator** that handles ONLY:
- Page count calculation via CSS columns
- In-window navigation (goToPage/getCurrentPage)
- Boundary detection with Android callbacks
- Font reflow operations
- Character offset tracking (NEW)

## Current State Analysis

### Current Paginator (2400 lines, ~51 functions)
- ✅ CSS column-based pagination
- ❌ Chapter streaming (append/prepend/remove)
- ❌ Window mode discipline (CONSTRUCTION vs ACTIVE)
- ❌ Segment management and eviction
- ❌ TOC-based chapter jumping
- ❌ Dynamic chapter management

### Dependencies in Codebase
- `WebViewPaginatorBridge.kt` - Main Kotlin interface to JS paginator
  - `configure()`, `getPageCount()`, `getCurrentPage()`, `goToPage()`
  - `setFontSize()`, `reconfigure()`
  - ❌ `appendChapter()`, `prependChapter()`, `getSegmentPageCount()` - **LEGACY**
  - ❌ `setInitialChapter()`, `jumpToChapter()` - **LEGACY**

---

## Phase 1: Audit & Mapping (CURRENT)

### Task 1.1: Identify All Paginator Dependencies
- [ ] Find all calls to `window.inpagePaginator.*` in Kotlin
- [ ] Find all Android bridge callbacks from JS
- [ ] Map which are REQUIRED (kept) vs LEGACY (removed)

**Current Findings**:
```
REQUIRED (Keep):
  ✅ configure()
  ✅ getPageCount()
  ✅ getCurrentPage()
  ✅ goToPage()
  ✅ setFontSize()
  ✅ reconfigure() - maybe fold into configure()
  ✅ nextPage() / prevPage()
  
LEGACY (Remove):
  ❌ appendChapter() / prependChapter() - conveyor handles now
  ❌ removeChapter()
  ❌ getSegmentPageCount()
  ❌ setInitialChapter()
  ❌ jumpToChapter()
  ❌ enableDiagnostics() / disableDiagnostics()
  ❌ forceHorizontal()
  
NEW (Add):
  ✅ getCharacterOffsetForPage()
  ✅ goToPageWithCharacterOffset()
```

### Task 1.2: Map Downstream Dependencies
- [ ] ReaderPageFragment - how does it call paginator?
- [ ] ReaderViewModel - what paginator methods it calls?
- [ ] BookmarkManager - relies on paginator for what?
- [ ] TTS integration - needs what from paginator?
- [ ] ProgressTracking - how it uses paginator?

### Task 1.3: Document Breaking Points
Create file: `PAGINATOR_REFACTOR_BREAKING_CHANGES.md`
- List every code path that will break
- For each: specify migration path or removal

---

## Phase 2: New Minimal Paginator Implementation

### Task 2.1: Create `minimal_paginator.js` (~200 lines)
**Responsibilities**:
```javascript
// Initialize
initialize(htmlContent, viewportWidth)

// Page operations
getPageCount() -> Int
getCurrentPage() -> Int
goToPage(pageIndex, smooth) -> void

// Navigation callbacks (to Android)
onBoundaryReached(direction: 'FORWARD' | 'BACKWARD') -> void via AndroidBridge

// Font handling
setFontSize(px) -> void

// CHARACTER OFFSET TRACKING (NEW)
getCharacterOffsetForPage(pageIndex) -> Int
goToPageWithCharacterOffset(offset) -> void

// Reflow on viewport change
handleViewportChange(newWidth) -> void
```

**Implementation approach**:
1. Parse HTML with `<section>` tags (pre-wrapped by conveyor)
2. Use CSS columns for layout
3. Query `scrollWidth` vs `clientWidth` to calculate page count
4. Track `currentPage` explicitly
5. On scroll near boundaries → call Android bridge
6. Character offset via DOM `nodeOffset` tracking

### Task 2.2: Character Offset Tracking API
**New capabilities**:
- Track character position within current chapter
- Map between (pageIndex) → (characterOffset)
- Restore position after font size changes

**Key insight**: When font changes, page boundaries shift, but character position remains stable anchor point.

**Implementation**:
```javascript
// Build offset map on initialize
let charOffsets = [];  // [offset0, offset1, offset2] = start char of each page

getCharacterOffsetForPage(pageIdx) {
  return charOffsets[pageIdx] || 0;
}

goToPageWithCharacterOffset(offset) {
  // Binary search charOffsets to find page
  // Go to that page
}
```

---

## Phase 3: Integration & Migration

### Task 3.1: Update WebViewPaginatorBridge.kt
- Remove calls to legacy functions
- Add new character offset APIs
- Update `configure()` to pass only needed config

### Task 3.2: Update Kotlin to use new APIs
- ReaderPageFragment
- ReaderViewModel  
- BookmarkManager
- ProgressTracking

### Task 3.3: Update Conveyor System
- Ensure window HTML is **pre-wrapped** with `<section>` tags
- Conveyor sends: `{ html: "...", windowIndex: N }`
- Paginator receives and initializes

---

## Phase 4: Bookmark & Progress Tracking

### Task 4.1: BookmarkManager Updates
```kotlin
// Before: Always save characterOffset=0
fun createBookmark(pageIndex, text) {
  val offset = paginatorBridge.getCharacterOffsetForPage(pageIndex)
  return Bookmark(pageIndex, offset, text)
}

// On font size change: Restore by offset, not page
fun restoreBookmark(bookmark, newFontSize) {
  paginatorBridge.setFontSize(newFontSize)
  paginatorBridge.goToPageWithCharacterOffset(bookmark.characterOffset)
}
```

### Task 4.2: Progress Tracking
```kotlin
fun saveReadingPosition() {
  val page = paginatorBridge.getCurrentPage()
  val offset = paginatorBridge.getCharacterOffsetForPage(page)
  bookRepository.updateProgress(offset)  // Store offset instead of page
}

fun restoreReadingPosition(offset) {
  paginatorBridge.goToPageWithCharacterOffset(offset)
}
```

### Task 4.3: TTS Integration
- TTS resumes from saved character offset
- Accurate position after font changes

---

## Phase 5: Testing & Validation

### Test 1: Character Offset Tracking
- [ ] Save bookmark at page N
- [ ] Change font size
- [ ] Restore bookmark → should be at same character position

### Test 2: Progress Persistence
- [ ] Read to page M
- [ ] Save progress
- [ ] Close app, reopen
- [ ] Resume should show same text position

### Test 3: Boundary Callbacks
- [ ] Navigate to last page
- [ ] Swipe/scroll right → Android receives `onBoundaryReached('FORWARD')`
- [ ] Conveyor shifts window

### Test 4: No Regressions
- [ ] TTS continues to work
- [ ] Bookmarks survive app restart
- [ ] Font size changes don't break progress

---

## Acceptance Criteria

### For Phase 1 (Audit):
- [ ] All paginator API calls documented
- [ ] Legacy functions identified and marked for removal
- [ ] Migration paths defined for each broken code path
- [ ] File created: `PAGINATOR_REFACTOR_BREAKING_CHANGES.md`

### For Phase 2 (New Paginator):
- [ ] `minimal_paginator.js` implemented (~200 lines)
- [ ] All REQUIRED APIs working
- [ ] Character offset tracking API working
- [ ] Minimal logging for debugging

### For Phase 3 (Integration):
- [ ] `WebViewPaginatorBridge.kt` updated
- [ ] All Kotlin callers updated
- [ ] App builds and runs
- [ ] No compilation errors

### For Phase 4 (Bookmarks/Progress):
- [ ] `BookmarkManager` saves character offset
- [ ] Bookmarks restore correctly after font changes
- [ ] Progress tracking uses character offsets
- [ ] TTS resumes at correct position

### For Phase 5 (Validation):
- [ ] All tests pass
- [ ] No regressions in TTS, bookmarks, progress
- [ ] Debug activity shows boundaries being detected correctly

---

## Files to Create/Modify

### New Files:
- `/app/src/main/assets/minimal_paginator.js` - New minimal implementation
- `/PAGINATOR_REFACTOR_BREAKING_CHANGES.md` - Breaking change audit
- `/PAGINATOR_V2_MIGRATION_GUIDE.md` - How-to for developers

### Modified Files:
- `/app/src/main/java/com/rifters/riftedreader/ui/reader/WebViewPaginatorBridge.kt`
- `/app/src/main/java/com/rifters/riftedreader/ui/reader/ReaderPageFragment.kt`
- `/app/src/main/java/com/rifters/riftedreader/ui/reader/ReaderViewModel.kt`
- `/app/src/main/java/com/rifters/riftedreader/data/bookmark/BookmarkManager.kt`
- `/app/src/main/java/com/rifters/riftedreader/domain/tts/` - TTS progress tracking

### Deprecated/Removed:
- `/app/src/main/assets/inpage_paginator.js` - Replaced by minimal_paginator.js

---

## Timeline

- **Phase 1 (Audit)**: 1-2 days
- **Phase 2 (Implementation)**: 2-3 days
- **Phase 3 (Integration)**: 1-2 days
- **Phase 4 (Bookmarks)**: 1 day
- **Phase 5 (Testing)**: 2-3 days

**Total**: ~1-2 weeks for full implementation + testing

---

## Success Criteria Summary

✅ Working minimal paginator (200 lines vs 2400)  
✅ Character offset tracking API implemented  
✅ Bookmarks survive font size changes  
✅ Progress accurate and persistent  
✅ TTS resumes at correct position  
✅ All legacy code migrated or removed  
✅ No regressions in existing features  
✅ Conveyor system fully integrated  

---

**Next**: Start Phase 1 with detailed audit of all paginator calls
