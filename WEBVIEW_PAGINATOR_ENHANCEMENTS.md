# WebView In-Page Paginator Enhancements

## Overview

This document describes the enhancements made to the `inpage_paginator.js` JavaScript file and related Kotlin bridges to support robust chapter streaming, dynamic reflow, and TOC-based navigation.

## Issue Reference

**Issue**: In-page JavaScript: Chapter Streaming and Dynamic Reflow  
**Sub-issue of**: sliding-window-paginator-parent

## Changes Made

### 1. Enhanced JavaScript (`inpage_paginator.js`)

#### New Functions Added

##### `jumpToChapter(chapterIndex, smooth)`
- **Purpose**: Navigate directly to a specific chapter for TOC navigation
- **Parameters**: 
  - `chapterIndex`: The chapter index to jump to
  - `smooth`: Whether to animate the transition
- **Returns**: Boolean indicating success
- **Callbacks**: 
  - `onChapterJumped(chapterIndex, pageIndex)` - Called on successful jump
  - `onChapterNotLoaded(chapterIndex)` - Called if chapter not in loaded segments

##### `removeChapterSegment(chapterIndex)`
- **Purpose**: Remove a specific chapter segment from the DOM
- **Parameters**: `chapterIndex` - The chapter to remove
- **Returns**: Boolean indicating success
- **Behavior**: Reflows after removal and adjusts scroll position

##### `clearAllSegments()`
- **Purpose**: Remove all chapter segments from the DOM
- **Returns**: Boolean indicating success
- **Use Case**: Reset view or handle errors

##### `getLoadedChapters()`
- **Purpose**: Get information about all currently loaded chapter segments
- **Returns**: Array of objects with `{chapterIndex, startPage, endPage, pageCount}`
- **Use Case**: UI state management, debugging

##### `getCurrentChapter()`
- **Purpose**: Get the chapter index for the currently visible page
- **Returns**: Chapter index or -1 if not found
- **Use Case**: Progress tracking, chapter indicators

#### Enhanced Functions

##### `reflow(preservePosition)`
- **Enhancement**: Added optional position preservation parameter
- **Parameters**: `preservePosition` (default: true) - Whether to maintain reading position
- **Returns**: Object with `{success, pageCount, currentPage, error?}`
- **Improvements**:
  - Better error handling with try-catch
  - Saves both page and chapter context before reflow
  - Forces reflow on all segments
  - Returns detailed result object
  - Gracefully handles failures

##### `appendChapterSegment(chapterIndex, rawHtml)`
- **Enhancements**:
  - Added validation for empty HTML
  - Saves current state before append
  - Returns success/failure status
  - Better error logging
  - Preserves scroll position during reflow

##### `prependChapterSegment(chapterIndex, rawHtml)`
- **Enhancements**:
  - Added validation for empty HTML
  - Calculates pages added and adjusts scroll position
  - Returns success/failure status
  - Better error logging
  - Maintains visual continuity for user

### 2. Enhanced Kotlin Bridge (`WebViewPaginatorBridge.kt`)

#### New Methods Added

```kotlin
fun jumpToChapter(webView: WebView, chapterIndex: Int, smooth: Boolean = true)
```
- Triggers TOC-based navigation to a specific chapter

```kotlin
fun removeChapter(webView: WebView, chapterIndex: Int)
```
- Removes a specific chapter segment from WebView

```kotlin
fun clearAllSegments(webView: WebView)
```
- Clears all loaded chapter segments

```kotlin
suspend fun getCurrentChapter(webView: WebView): Int
```
- Gets the chapter index for the current visible page

```kotlin
suspend fun getLoadedChapters(webView: WebView): String
```
- Returns JSON string with info about all loaded chapters

#### Enhanced Methods

```kotlin
fun reflow(webView: WebView, preservePosition: Boolean = true)
```
- Added position preservation parameter
- Allows caller to control scroll behavior during reflow

### 3. Updated Fragment (`ReaderPageFragment.kt`)

#### New JavaScript Interface Callbacks

Added to `PaginationBridge` inner class:

```kotlin
@JavascriptInterface
fun onChapterJumped(chapterIndex: Int, pageIndex: Int)
```
- Called when TOC navigation completes successfully
- Updates page state in ViewModel

```kotlin
@JavascriptInterface
fun onChapterNotLoaded(chapterIndex: Int)
```
- Called when attempting to jump to an unloaded chapter
- Allows app to trigger chapter loading if needed

### 4. Enhanced Tests (`WebViewPageTrackingTest.kt`)

#### New Test Cases

1. **Chapter Streaming Tests**
   - `chapter streaming - append preserves current page`
   - `chapter streaming - prepend adjusts current page`
   - `chapter streaming - remove chapter adjusts page count`

2. **Reflow Tests**
   - `reflow preserves position correctly`
   - `font size change triggers reflow with position preservation`

3. **TOC Navigation Tests**
   - `TOC navigation to chapter calculates correct page`
   - `getCurrentChapter returns correct chapter for page`

## Technical Improvements

### 1. Error Handling
- All chapter operations wrapped in try-catch blocks
- Functions return success/failure status
- Detailed error logging for debugging
- Graceful fallbacks on errors

### 2. Position Preservation
- Reflow operations can maintain reading position
- Prepending chapters adjusts scroll to prevent jarring jumps
- Font size changes preserve relative position

### 3. Memory Management
- Existing segment trimming mechanism (MAX_CHAPTER_SEGMENTS = 5)
- Efficient DOM manipulation
- Proper cleanup on segment removal

### 4. Callback Integration
- JavaScript callbacks to Kotlin for state updates
- TOC navigation with success/failure notifications
- Page change events for UI synchronization

## API Summary

### JavaScript Global API (`window.inpagePaginator`)

```javascript
{
  // Existing methods (preserved)
  isReady: function()
  reflow: function(preservePosition)  // Enhanced
  setFontSize: function(px)
  getPageCount: function()
  getCurrentPage: function()
  goToPage: function(index, smooth)
  nextPage: function()
  prevPage: function()
  getPageForSelector: function(selector)
  createAnchorAroundViewportTop: function(anchorId)
  scrollToAnchor: function(anchorId)
  
  // Chapter management
  appendChapter: function(chapterIndex, rawHtml)  // Enhanced
  prependChapter: function(chapterIndex, rawHtml)  // Enhanced
  removeChapter: function(chapterIndex)  // NEW
  clearAllSegments: function()  // NEW
  setInitialChapter: function(chapterIndex)
  getSegmentPageCount: function(chapterIndex)
  
  // TOC and chapter navigation
  jumpToChapter: function(chapterIndex, smooth)  // NEW
  getLoadedChapters: function()  // NEW
  getCurrentChapter: function()  // NEW
}
```

### Kotlin Bridge Methods (`WebViewPaginatorBridge`)

```kotlin
// Existing methods (preserved)
suspend fun isReady(webView: WebView): Boolean
suspend fun getPageCount(webView: WebView): Int
suspend fun getCurrentPage(webView: WebView): Int
fun goToPage(webView: WebView, index: Int, smooth: Boolean)
fun setFontSize(webView: WebView, px: Int)
fun nextPage(webView: WebView)
fun prevPage(webView: WebView)
suspend fun getPageForSelector(webView: WebView, selector: String): Int
suspend fun createAnchorAroundViewportTop(webView: WebView, anchorId: String): Boolean
suspend fun scrollToAnchor(webView: WebView, anchorId: String): Boolean

// Chapter management
fun setInitialChapter(webView: WebView, chapterIndex: Int)
fun appendChapter(webView: WebView, chapterIndex: Int, html: String)
fun prependChapter(webView: WebView, chapterIndex: Int, html: String)
fun removeChapter(webView: WebView, chapterIndex: Int)  // NEW
fun clearAllSegments(webView: WebView)  // NEW
suspend fun getSegmentPageCount(webView: WebView, chapterIndex: Int): Int

// TOC and chapter navigation
fun jumpToChapter(webView: WebView, chapterIndex: Int, smooth: Boolean)  // NEW
suspend fun getCurrentChapter(webView: WebView): Int  // NEW
suspend fun getLoadedChapters(webView: WebView): String  // NEW

// Reflow
fun reflow(webView: WebView, preservePosition: Boolean)  // Enhanced
```

## Usage Examples

### TOC Navigation

```kotlin
// In ChaptersBottomSheet or TOC UI
fun onChapterSelected(chapterIndex: Int) {
    WebViewPaginatorBridge.jumpToChapter(webView, chapterIndex, smooth = true)
    // Callback will be triggered: onChapterJumped or onChapterNotLoaded
}
```

### Font Size Change with Position Preservation

```kotlin
fun handleFontSizeChange(newSize: Int) {
    // Automatically preserves position (default behavior)
    WebViewPaginatorBridge.setFontSize(webView, newSize)
}
```

### Chapter Streaming

```kotlin
// Append next chapter when user reaches the end
suspend fun loadNextChapter(chapterIndex: Int) {
    val html = parseChapterHtml(chapterIndex)
    WebViewPaginatorBridge.appendChapter(webView, chapterIndex, html)
    // User's current page position is preserved
}

// Prepend previous chapter when user scrolls back
suspend fun loadPreviousChapter(chapterIndex: Int) {
    val html = parseChapterHtml(chapterIndex)
    WebViewPaginatorBridge.prependChapter(webView, chapterIndex, html)
    // Scroll position is automatically adjusted
}
```

### Get Chapter Info

```kotlin
// Get current chapter for UI display
lifecycleScope.launch {
    val currentChapter = WebViewPaginatorBridge.getCurrentChapter(webView)
    updateChapterIndicator(currentChapter)
}

// Get all loaded chapters for debugging/state
lifecycleScope.launch {
    val chaptersJson = WebViewPaginatorBridge.getLoadedChapters(webView)
    val chapters = parseLoadedChapters(chaptersJson)
    logChapterInfo(chapters)
}
```

## Acceptance Criteria Status

- ✅ **Can append/remove chapters from the WebView without glitches**
  - Enhanced append/prepend with position preservation
  - New removeChapter() method
  - clearAllSegments() for bulk operations

- ✅ **Can reflow all loaded chapters on font size change**
  - Enhanced reflow() with position preservation
  - Forces reflow on all segments
  - Preserves relative reading position

- ✅ **UI always shows correct page number, chapter, and progress**
  - getCurrentChapter() provides active chapter
  - Callbacks notify UI of state changes
  - getLoadedChapters() provides full segment info

- ✅ **TOC navigation support**
  - jumpToChapter() method added
  - Callbacks for success/failure scenarios
  - Smooth animation option

## Edge Cases Handled

1. **Initial Load**: Checks for initialization before operations
2. **Empty Content**: Validates HTML before adding segments
3. **Font Change**: Preserves position during reflow
4. **Chapter Unload**: Properly removes segments and reflows
5. **Scroll Adjustment**: Prepending adjusts position to prevent jarring
6. **Error Recovery**: All operations have try-catch and return status
7. **Boundary Conditions**: Page clamping to valid ranges

## Memory Optimization

- Existing `MAX_CHAPTER_SEGMENTS = 5` limit enforced
- Automatic trimming when limit exceeded
- Callbacks notify Android of segment eviction
- Efficient DOM manipulation

## Known Limitations

1. **Build Issue**: Pre-existing resource compilation error in `strings.xml` (unrelated to these changes)
2. **Testing**: Unit tests verify logic, but integration tests require WebView environment

## Future Enhancements

1. **Predictive Loading**: Pre-load adjacent chapters based on reading direction
2. **Chapter Caching**: Cache parsed HTML for faster re-loading
3. **Adaptive Segment Limit**: Adjust MAX_CHAPTER_SEGMENTS based on device memory
4. **Animation Options**: More control over transition animations
5. **Progress Persistence**: Save detailed position including character offset

## Conclusion

These enhancements provide a robust foundation for continuous reading with chapter streaming, TOC navigation, and dynamic content management. The implementation maintains backward compatibility while adding powerful new capabilities for a seamless reading experience.
