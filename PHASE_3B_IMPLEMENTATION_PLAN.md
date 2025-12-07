# Phase 3b Implementation Plan - Character Offsets & Integration

**Date**: December 6, 2025  
**Status**: Starting  
**Duration**: ~6-8 hours  
**Objective**: Integrate character offset APIs into reader UI and implement bookmark support

---

## Current State Assessment

### ✅ Completed (Phase 3 Bridge)
- `WebViewPaginatorBridge.kt` refactored (729 → 334 lines)
- Character offset APIs implemented:
  - `getCharacterOffsetForPage(pageIndex): Int`
  - `goToPageWithCharacterOffset(offset: Int): Boolean`
- JavaScript object renamed to `minimalPaginator`

### ✅ Ready (Data Layer)
- `Bookmark` entity has `characterOffset: Int` field
- `BookmarkDao` ready for queries
- `BookmarkRepository` ready for CRUD
- `BookmarkManager` ready for create/restore operations

### ✅ Partially Done (ViewModel)
- `ReaderViewModel.saveProgress()` already uses character offsets in continuous mode
- Uses `updateReadingProgressEnhanced()` method

### ⏳ TODO (UI Integration)
1. Add character offset capture in `ReaderPageFragment`
2. Integrate bookmark creation UI
3. Integrate bookmark restoration UI
4. Add character offset to progress saving
5. Test roundtrip: create bookmark → change font → restore

---

## Phase 3b Tasks

### Task 1: Add Character Offset Capture to ReaderPageFragment
**File**: `ReaderPageFragment.kt` (line ~789-813)  
**What**: When navigating to a page, capture character offset from JavaScript

**Current code (line 813):**
```kotlin
WebViewPaginatorBridge.setInitialChapter(binding.pageWebView, chapterIndex)
```

**Problem**: `setInitialChapter` was removed in Phase 3 refactoring!

**Solution**: Replace with character offset capture:
```kotlin
// Capture character offset for current page
val pageIndex = WebViewPaginatorBridge.getCurrentPage(binding.pageWebView)
val charOffset = WebViewPaginatorBridge.getCharacterOffsetForPage(binding.pageWebView, pageIndex)
// Store for bookmark creation
```

**Locations to update**:
- Line 199: Font size change → recalculate offsets
- Line 229-236: Navigation → capture offset
- Line 281-289: Boundary handling → capture offset
- Line 405-413: Settings changes → capture offset
- Line 436-443: Programmatic navigation → capture offset
- Line 789-813: Touch navigation → capture offset

### Task 2: Integrate Bookmark Creation UI
**File**: `ReaderPageFragment.kt` + UI Components  
**What**: Add "Create Bookmark" action that captures current position with character offset

**Implementation**:
```kotlin
private suspend fun createBookmarkAtCurrentPosition() {
    val currentPage = WebViewPaginatorBridge.getCurrentPage(webView)
    val charOffset = WebViewPaginatorBridge.getCharacterOffsetForPage(webView, currentPage)
    val content = pageContent  // From ViewModel
    
    bookmarkManager.createBookmark(
        bookId = bookId,
        chapterIndex = currentChapterIndex,
        inChapterPage = currentPage,
        characterOffset = charOffset,  // ⭐ NEW
        chapterTitle = getCurrentChapterTitle(),
        pageContent = content,
        percentageThrough = getCurrentProgress(),
        fontSize = readerSettings.textSizeSp
    )
}
```

### Task 3: Integrate Bookmark Restoration UI
**File**: `ReaderPageFragment.kt` + Bookmark UI  
**What**: When restoring bookmark, use character offset API instead of page index

**Current behavior**: Uses page index (breaks after font change)  
**New behavior**: Uses character offset (stable across font changes)

**Implementation**:
```kotlin
private suspend fun restoreBookmark(bookmark: Bookmark) {
    val charOffset = bookmark.characterOffset
    
    // Navigate using character offset instead of page
    WebViewPaginatorBridge.goToPageWithCharacterOffset(webView, charOffset)
}
```

**Also update**: Bookmark list display to show character offset for debugging

### Task 4: Update Progress Saving
**File**: `ReaderViewModel.kt` (line 922)  
**What**: Already mostly done, but verify character offset is captured

**Current code** (line 931-938):
```kotlin
val location = getPageLocation(_currentPage.value)
...
repository.updateReadingProgressEnhanced(
    bookId,
    location.chapterIndex,
    location.inPageIndex,
    location.characterOffset,  // ✅ Already here!
    previewText,
    percent
)
```

**Verify**: `getPageLocation()` returns character offset from bridge

### Task 5: Error Handling
**What**: Handle cases where character offset might be -1 or invalid

**Implementation**:
```kotlin
val charOffset = WebViewPaginatorBridge.getCharacterOffsetForPage(webView, page)
if (charOffset < 0) {
    // Fallback to page-based restoration
    logWarning("Character offset invalid, using page-based restoration")
    WebViewPaginatorBridge.goToPage(webView, page)
} else {
    WebViewPaginatorBridge.goToPageWithCharacterOffset(webView, charOffset)
}
```

---

## Testing Strategy

### Unit Tests
1. **Character offset calculation**
   - Test: Same text, different font sizes → same character offset
   - Test: Different text positions → different character offsets
   
2. **Bookmark roundtrip**
   - Create bookmark with offset A
   - Restore bookmark → verify page matches
   - Change font size
   - Restore bookmark → verify same page (with new font)

3. **Edge cases**
   - First page (offset 0)
   - Last page (offset near max)
   - Empty pages
   - Very large pages

### Integration Tests (Device)
1. Open book at page 5
2. Create bookmark
3. Navigate to page 15
4. Restore bookmark → should be at page 5
5. Change font size from 12sp to 20sp
6. Restore same bookmark → should be at correct page (may differ visually but content same)
7. Close and reopen app
8. Verify bookmark and progress restored

---

## Code Changes Summary

| File | Change | Lines | Type |
|------|--------|-------|------|
| `ReaderPageFragment.kt` | Capture char offset on nav | 10-20 | Add |
| `ReaderPageFragment.kt` | Integrate bookmark creation | 15-25 | Add |
| `ReaderPageFragment.kt` | Integrate bookmark restore | 15-25 | Add |
| `ReaderPageFragment.kt` | Remove deprecated calls | 5-10 | Remove |
| `ReaderViewModel.kt` | Verify offset capture | 0 | Review |

**Total LOC changes**: ~50-80 lines

---

## Integration Checklist

- [ ] Remove calls to `setInitialChapter()` (deprecated)
- [ ] Remove calls to `appendChapter()` (moved to Conveyor)
- [ ] Add character offset capture at all navigation points
- [ ] Implement bookmark creation with character offset
- [ ] Implement bookmark restoration with character offset
- [ ] Add error handling for invalid offsets
- [ ] Update progress saving to use character offsets
- [ ] Add logging for debugging
- [ ] Create unit tests
- [ ] Test on device
- [ ] Verify bookmarks survive font changes
- [ ] Verify progress persists across app close/reopen

---

## Success Criteria

✅ **Character offset APIs used** - All navigation calls capture and use offsets  
✅ **Bookmarks work** - Create → restore chain works  
✅ **Font-safe bookmarks** - Bookmarks work after font changes  
✅ **Progress persistent** - Progress survives app close  
✅ **No deprecated calls** - All removed Phase 2 methods replaced  
✅ **Tests pass** - Unit and integration tests all green  
✅ **Device tests pass** - Manual testing on device successful  

---

## Risk Assessment

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|-----------|
| Wrong offset calculation | Low | High | Test with various font sizes |
| Missing deprecated method removal | Medium | Medium | Grep for all Phase 2 methods |
| Bookmark UI not updated | Low | Medium | Check all bookmark UIs |
| Performance regression | Low | Low | Monitor load times |

---

## Next Steps (After Phase 3b)

Phase 4: Advanced Features
- Highlight system for bookmarks
- Reading statistics
- Smart collections
- Cloud sync (future)

---

**Status**: Ready to start Task 1  
**Estimated Time**: 6-8 hours  
**Assigned to**: Current developer
