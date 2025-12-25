# Paginator API Audit & Dependency Mapping

**Date**: December 6, 2025  
**Status**: Phase 1 - Audit Complete  
**Branch**: development

---

## 1. All WebViewPaginatorBridge API Calls

File: `app/src/main/java/com/rifters/riftedreader/ui/reader/WebViewPaginatorBridge.kt`

### 1.1 REQUIRED APIs (Keep in Minimal Paginator)

#### `isReady(): Boolean`
- **Line**: 67
- **Usage**: Guards all other paginator calls
- **Status**: ✅ KEEP
- **JS**: `window.inpagePaginator.isReady()`
- **Purpose**: Verify pagination is initialized before accessing
- **Kotlin Call**:
  ```kotlin
  evaluateBoolean(webView, "window.inpagePaginator && window.inpagePaginator.isReady()")
  ```

#### `configure(config: PaginatorConfig)`
- **Line**: 106
- **Usage**: Set paginator mode and configuration
- **Status**: ✅ KEEP (may simplify)
- **JS**: `window.inpagePaginator.configure($jsConfig)`
- **Current Config**:
  ```kotlin
  data class PaginatorConfig(
      val mode: String,           // 'window' or 'chapter'
      val windowIndex: Int?,
      val chapterIndex: Int?,
      val rootSelector: String?
  )
  ```
- **Keep**: mode, windowIndex (for logging)
- **Remove**: chapterIndex, rootSelector (legacy chapter management)

#### `getPageCount(): Int`
- **Line**: 184
- **Usage**: Get total pages in current window
- **Status**: ✅ KEEP
- **JS**: `window.inpagePaginator.getPageCount()`
- **Called By**: ReaderViewModel, ReaderPageFragment

#### `getCurrentPage(): Int`
- **Line**: 213
- **Usage**: Get currently displayed page
- **Status**: ✅ KEEP
- **JS**: `window.inpagePaginator.getCurrentPage()`
- **Called By**: ReaderPageFragment (track reading position)

#### `goToPage(index: Int, smooth: Boolean)`
- **Line**: 235
- **Usage**: Navigate to specific page
- **Status**: ✅ KEEP
- **JS**: `window.inpagePaginator.goToPage($index, $smooth)`
- **Called By**: ReaderPageFragment (user navigation)

#### `setFontSize(px: Int)`
- **Line**: 254
- **Usage**: Change font size and reflow
- **Status**: ✅ KEEP
- **JS**: `window.inpagePaginator.setFontSize($px)`
- **Called By**: ReaderPageFragment (settings change)

#### `reconfigure(options: Map<String, Any>)`
- **Line**: 281
- **Usage**: Update configuration without reinit
- **Status**: ⚠️ MAYBE - Could fold into `configure()`
- **JS**: `window.inpagePaginator.reconfigure($jsonOptions)`
- **Current**: Seems redundant with `configure()`
- **Recommendation**: Simplify to single `configure()` call

#### `nextPage()`
- **Line**: 345
- **Usage**: Navigate to next page
- **Status**: ✅ KEEP
- **JS**: `window.inpagePaginator.nextPage()`
- **Called By**: ReaderPageFragment (swipe/gesture)

#### `prevPage()`
- **Line**: 362
- **Usage**: Navigate to previous page
- **Status**: ✅ KEEP
- **JS**: `window.inpagePaginator.prevPage()`
- **Called By**: ReaderPageFragment (swipe/gesture)

---

### 1.2 LEGACY APIs (Remove from Minimal Paginator)

#### `setInitialChapter(chapterIndex: Int)`
- **Line**: 291
- **Usage**: Set starting chapter (legacy)
- **Status**: ❌ REMOVE
- **JS**: `window.inpagePaginator.setInitialChapter($chapterIndex)`
- **Reason**: Conveyor system handles chapter streaming now
- **Replacement**: Conveyor sends pre-wrapped HTML with all chapters

#### `appendChapter(chapterIndex: Int, htmlContent: String)`
- **Line**: 306
- **Usage**: Add chapter to end (legacy streaming)
- **Status**: ❌ REMOVE
- **JS**: `window.inpagePaginator.appendChapter($chapterIndex, atob('$encoded'))`
- **Reason**: Conveyor handles chapter management
- **Replacement**: Conveyor sends complete window HTML

#### `prependChapter(chapterIndex: Int, htmlContent: String)`
- **Line**: 321
- **Usage**: Add chapter to beginning (legacy streaming)
- **Status**: ❌ REMOVE
- **JS**: `window.inpagePaginator.prependChapter($chapterIndex, atob('$encoded'))`
- **Reason**: Conveyor handles chapter management
- **Replacement**: Conveyor sends complete window HTML

#### `getSegmentPageCount(chapterIndex: Int)`
- **Line**: 330
- **Usage**: Get page count for specific chapter (legacy)
- **Status**: ❌ REMOVE
- **JS**: `window.inpagePaginator.getSegmentPageCount($chapterIndex)`
- **Reason**: Paginator no longer manages chapters
- **Replacement**: Conveyor reports page count for whole window

#### `jumpToChapter(chapterIndex: Int, smooth: Boolean)`
- **Line**: 394
- **Usage**: Jump to specific chapter (legacy TOC)
- **Status**: ❌ REMOVE
- **JS**: `window.inpagePaginator.jumpToChapter($chapterIndex, $smooth)`
- **Reason**: Conveyor handles chapter navigation
- **Replacement**: Use conveyor's window navigation

#### `getPageForSelector(selector: String)`
- **Line**: 377
- **Usage**: Find page containing element
- **Status**: ⚠️ PROBABLY REMOVE
- **JS**: `window.inpagePaginator.getPageForSelector('$escapedSelector')`
- **Used By**: Bookmarks, TOC navigation
- **Decision**: Remove if conveyor handles all navigation

---

### 1.3 NEW APIs (Add to Minimal Paginator)

#### `getCharacterOffsetForPage(pageIndex: Int): Int`
- **Status**: ✨ NEW
- **Purpose**: Get character position at start of page
- **Called By**: BookmarkManager (save bookmark), ReaderViewModel (progress)
- **Implementation**:
  - Build character offset array during initialization
  - Each page has starting character offset within window

#### `goToPageWithCharacterOffset(offset: Int): Int`
- **Status**: ✨ NEW
- **Purpose**: Navigate to page containing character offset
- **Called By**: BookmarkManager (restore bookmark), ReaderViewModel (restore progress)
- **Implementation**:
  - Binary search character offset array
  - Go to found page

---

## 2. Android Bridge Callbacks (JS → Kotlin)

### 2.1 Current Callbacks

#### `AndroidBridge.onPageChanged(page: Int)`
- **Source**: `inpage_paginator.js` line ~1240
- **Purpose**: Notify Kotlin when page changes
- **Used By**: ReaderPageFragment
- **Status**: ✅ KEEP (essential for UI sync)

#### `AndroidBridge.onBoundaryReached(direction: String)`
- **Source**: `inpage_paginator.js` (NEW - not yet implemented)
- **Purpose**: Signal when edge of window reached (for conveyor buffer shift)
- **Direction**: 'FORWARD' | 'BACKWARD'
- **Used By**: Conveyor system
- **Status**: ✨ NEW (need to add)

---

## 3. Downstream Dependencies in Kotlin

### 3.1 ReaderPageFragment

**File**: `app/src/main/java/com/rifters/riftedreader/ui/reader/ReaderPageFragment.kt`

**Paginator Dependencies**:
- Calls `paginatorBridge.goToPage()` when user navigates
- Observes page changes from `AndroidBridge.onPageChanged()`
- Calls `paginatorBridge.setFontSize()` on settings change
- ⚠️ May call `getPageForSelector()` for bookmarks (LEGACY)

**Migration Needed**: 
- Replace `getPageForSelector()` with character offset API
- Add calls to save character offset on page changes

### 3.2 ReaderViewModel

**File**: `app/src/main/java/com/rifters/riftedreader/ui/reader/ReaderViewModel.kt`

**Paginator Dependencies**:
- Calls `paginatorBridge.getPageCount()` to get total pages
- May call `jumpToChapter()` (LEGACY)
- Tracks current window index
- ⚠️ Needs to track character offsets for progress

**Migration Needed**:
- Remove `jumpToChapter()` calls
- Add character offset tracking in progress
- Use `getCharacterOffsetForPage()` when saving progress

### 3.3 BookmarkManager

**File**: `app/src/main/java/com/rifters/riftedreader/data/bookmark/BookmarkManager.kt`

**Current Behavior**:
- Saves bookmark with `characterOffset = 0` (always ignored)
- Restores bookmark by `pageIndex` only
- **Problem**: After font size change, page indices shift but character position doesn't

**Migration Needed**:
```kotlin
// Save: Capture character offset
fun createBookmark(pageIndex: Int) {
  val offset = paginatorBridge.getCharacterOffsetForPage(pageIndex)
  return Bookmark(pageIndex, offset, text)
}

// Restore: Use character offset after font change
fun restoreBookmark(bookmark: Bookmark) {
  paginatorBridge.goToPageWithCharacterOffset(bookmark.characterOffset)
  // Or use pageIndex if offset is 0 (legacy bookmarks)
}
```

### 3.4 Progress Tracking

**File**: `app/src/main/java/com/rifters/riftedreader/ui/reader/ReaderViewModel.kt`

**Current Behavior**:
- Saves page index only
- Loses precision when font size changes

**Migration Needed**:
```kotlin
fun saveReadingPosition() {
  val page = paginatorBridge.getCurrentPage()
  val offset = paginatorBridge.getCharacterOffsetForPage(page)
  bookRepository.updateProgress(
    pageIndex = page,
    characterOffset = offset  // NEW
  )
}

fun restoreReadingPosition(offset: Int) {
  if (offset > 0) {
    paginatorBridge.goToPageWithCharacterOffset(offset)
  } else {
    // Fallback to page index for legacy data
    paginatorBridge.goToPage(pageIndex, smooth=false)
  }
}
```

### 3.5 TTS Integration

**File**: `app/src/main/java/com/rifters/riftedreader/domain/tts/TTSEngine.kt`

**Current Behavior**:
- Resumes from saved position
- Uses page index (breaks after font change)

**Migration Needed**:
```kotlin
fun resumeFromOffset(offset: Int) {
  paginatorBridge.goToPageWithCharacterOffset(offset)
  val page = paginatorBridge.getCurrentPage()
  // Resume TTS from page
}
```

---

## 4. Identified Breaking Changes

### 4.1 Paginator API Calls Being Removed

| Function | Current Callers | Migration Path |
|----------|-----------------|-----------------|
| `setInitialChapter()` | WebViewPaginatorBridge | Remove call, conveyor sends HTML |
| `appendChapter()` | WebViewPaginatorBridge | Remove call, conveyor sends HTML |
| `prependChapter()` | WebViewPaginatorBridge | Remove call, conveyor sends HTML |
| `getSegmentPageCount()` | WebViewPaginatorBridge | Query conveyor, not paginator |
| `jumpToChapter()` | ReaderViewModel (maybe) | Use conveyor window navigation |
| `getPageForSelector()` | BookmarkManager (maybe) | Use character offset API |
| `reconfigure()` | WebViewPaginatorBridge | Merge into `configure()` |

### 4.2 Code Paths That Will Break

#### Path 1: Chapter Streaming (LEGACY)
- **Current**: ReaderViewModel calls `appendChapter()` to add new chapters
- **Problem**: Minimal paginator won't support this
- **Solution**: 
  1. Conveyor delivers complete window with all chapters pre-wrapped
  2. Paginator never touches chapter management
  3. ReaderViewModel removes all streaming code

#### Path 2: TOC-Based Navigation (LEGACY)
- **Current**: Clicking TOC entry → `jumpToChapter()`
- **Problem**: Minimal paginator doesn't know about chapters
- **Solution**:
  1. TOC click → Request window shift from conveyor
  2. Conveyor delivers new window
  3. Paginator just displays what it's given

#### Path 3: Bookmark Restoration (BREAKING)
- **Current**: Restore by `pageIndex` (breaks after font change)
- **Problem**: Character offsets always 0
- **Solution**:
  1. New API: `goToPageWithCharacterOffset()`
  2. Save character offset when creating bookmark
  3. Restore by character offset, not page index

#### Path 4: Progress Persistence (BREAKING)
- **Current**: Save page index only
- **Problem**: Imprecise after font size change
- **Solution**:
  1. Save character offset alongside page index
  2. Restore using offset if available
  3. Fallback to page index for old bookmarks

---

## 5. Migration Checklist

### Remove from WebViewPaginatorBridge:
- [ ] `setInitialChapter()`
- [ ] `appendChapter()`
- [ ] `prependChapter()`
- [ ] `getSegmentPageCount()`
- [ ] `jumpToChapter()`
- [ ] `getPageForSelector()` (maybe)
- [ ] Simplify `reconfigure()` → fold into `configure()`

### Add to WebViewPaginatorBridge:
- [ ] `getCharacterOffsetForPage(pageIndex: Int): Int`
- [ ] `goToPageWithCharacterOffset(offset: Int): Unit`

### Update ReaderViewModel:
- [ ] Remove all `appendChapter()` / `prependChapter()` calls
- [ ] Remove `jumpToChapter()` calls
- [ ] Add character offset tracking to progress
- [ ] Wire up boundary callbacks to conveyor

### Update ReaderPageFragment:
- [ ] Remove `getPageForSelector()` usage
- [ ] Add character offset capture on page changes
- [ ] Wire up boundary callback handling

### Update BookmarkManager:
- [ ] Capture character offset when saving
- [ ] Use character offset when restoring (after font change)
- [ ] Fallback to page index for legacy bookmarks

### Update TTS:
- [ ] Resume using character offset, not page index
- [ ] Handle font size changes correctly

### Update Conveyor:
- [ ] Send complete window HTML with `<section>` tags
- [ ] No chapter management in JavaScript
- [ ] Handle boundary callbacks from paginator
- [ ] Shift buffer on boundary reached

---

## 6. Testing Strategy

### Test 1: Remove Legacy Functions
- [ ] Build app with legacy functions removed
- [ ] No compilation errors
- [ ] All Kotlin callers updated

### Test 2: Character Offset Tracking
- [ ] Create bookmark at page 0
- [ ] Verify `getCharacterOffsetForPage(0)` returns correct value
- [ ] Use `goToPageWithCharacterOffset()` to jump
- [ ] Verify we're at same character position

### Test 3: Font Size + Bookmarks
- [ ] Open book, go to page M
- [ ] Create bookmark
- [ ] Change font size
- [ ] Restore bookmark
- [ ] Verify reading same character (page index may differ)

### Test 4: Boundary Callbacks
- [ ] Navigate to last page
- [ ] Swipe forward → `AndroidBridge.onBoundaryReached('FORWARD')`
- [ ] Conveyor shifts window
- [ ] New window loads

### Test 5: Progress Persistence
- [ ] Open book, read to page N
- [ ] Close app
- [ ] Reopen book
- [ ] Resume at same character position

### Test 6: TTS + Font Change
- [ ] Start TTS reading
- [ ] Change font size (pauses TTS)
- [ ] Resume TTS
- [ ] Correct position

---

## Summary

**Total APIs to Remove**: 7 (legacy functions)  
**Total APIs to Keep**: 8 (core pagination)  
**Total APIs to Add**: 2 (character offset tracking)

**Breaking Changes**: 
- Chapter streaming removed
- TOC navigation refactored  
- Bookmark restoration changed (better!)
- Progress tracking improved

**Benefit**: Reduces paginator from 2400 → ~200 lines while improving accuracy and integrating with conveyor system.

---

**Next Steps**:
1. ✅ Complete this audit
2. Create `minimal_paginator.js` with required + new APIs
3. Update WebViewPaginatorBridge.kt
4. Update all downstream callers
5. Test thoroughly
6. Commit and merge to main

