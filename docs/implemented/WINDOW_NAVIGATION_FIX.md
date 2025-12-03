# Window Navigation Fix - Continuous Mode Edge Navigation

## Problem Summary

In continuous pagination mode, when navigating to the edge of a window (last in-page or first in-page), the UI failed to advance to the next/previous window. The RecyclerView remained stuck on windowIndex=0 and never instantiated fragments for windowIndex=1+.

### Symptoms
- Hardware keys or tap zones at the last in-page of window 0 did not advance to window 1
- Backend correctly prewrapped and cached HTML for windows 1+
- Logical window jumps occurred in backend (ViewModel state updated)
- RecyclerView and adapter never created fragments for windowIndex=1+
- UI rendering remained locked on windowIndex=0

### Historical Context
- Past logs showed window jumps happening (windowIndex 0→1) but with blank rendering
- Current state: navigation stopped entirely at window 0

## Root Cause Analysis

### Primary Issue: Circular Navigation Updates

The navigation flow created a circular dependency between the ViewModel and RecyclerView:

1. **Fragment edge detection** → calls `ReaderActivity.navigateToNextPage()`
2. **ReaderActivity** → calls `viewModel.nextWindow()` (updates `_currentWindowIndex`)
3. **ReaderActivity** → calls `setCurrentItem(nextWindow, animated=true)` (scrolls RecyclerView)
4. **RecyclerView.OnScrollListener** → fires when scroll settles
5. **OnScrollListener** → calls `viewModel.goToWindow(position)` AGAIN
6. **Result**: Redundant state updates, race conditions, and inconsistent navigation state

### Secondary Issues

1. **Missing Validation**:
   - No check that target window is within adapter bounds before scrolling
   - No validation that RecyclerView has items before attempting navigation
   - No guard against negative window indices

2. **Insufficient Logging**:
   - Limited visibility into whether RecyclerView actually scrolled
   - No confirmation that `onBindViewHolder` was called for target window
   - No tracking of fragment lifecycle for windowIndex=1+
   - Unclear whether navigation failures were due to ViewModel logic or RecyclerView issues

3. **RecyclerView State Issues**:
   - If adapter.itemCount was 0 or stale, RecyclerView wouldn't scroll
   - Fragment creation for windowIndex=1+ never triggered

## Solution Implementation

### 1. Programmatic Scroll Tracking

Added `programmaticScrollInProgress` flag to distinguish between:
- **Programmatic scrolls**: Initiated by code (e.g., `navigateToNextPage()`)
- **User gesture scrolls**: Initiated by swipes/flings

**Code**: `ReaderActivity.kt`
```kotlin
private var programmaticScrollInProgress: Boolean = false
```

### 2. Guard Against Circular Updates

Modified `OnScrollListener` to skip ViewModel updates during programmatic scrolls:

```kotlin
if (newState == RecyclerView.SCROLL_STATE_IDLE) {
    val wasProgrammatic = programmaticScrollInProgress
    programmaticScrollInProgress = false
    
    // Only update ViewModel if NOT during programmatic scroll
    if (readerMode == ReaderMode.PAGE && 
        viewModel.currentWindowIndex.value != position && 
        !wasProgrammatic) {
        viewModel.goToWindow(position)
    } else if (wasProgrammatic) {
        AppLogger.d("Skipping ViewModel update (programmatic scroll)")
    }
}
```

### 3. Validation Before Navigation

Added comprehensive validation in navigation methods:

**`navigateToNextPage()`**:
```kotlin
val nextWindow = currentWindow + 1
val totalWindows = viewModel.windowCount.value
val adapterItemCount = pagerAdapter.itemCount

if (nextWindow >= totalWindows || nextWindow >= adapterItemCount) {
    AppLogger.w("Cannot navigate: nextWindow exceeds bounds")
    return
}

val moved = viewModel.nextWindow()
if (moved) {
    programmaticScrollInProgress = true
    setCurrentItem(nextWindow, animated)
}
```

**`navigateToPreviousPage()`**:
```kotlin
val previousWindow = currentWindow - 1

if (previousWindow < 0) {
    AppLogger.w("Cannot navigate: previousWindow is negative")
    return
}

val moved = viewModel.previousWindow()
if (moved) {
    programmaticScrollInProgress = true
    setCurrentItem(previousWindow, animated)
}
```

### 4. Enhanced RecyclerView Scroll Logging

**`setCurrentItem()`**:
```kotlin
private fun setCurrentItem(position: Int, smoothScroll: Boolean) {
    val adapterItemCount = pagerAdapter.itemCount
    val recyclerViewSize = "${binding.pageRecyclerView.width}x${binding.pageRecyclerView.height}"
    
    AppLogger.d("setCurrentItem: position=$position, itemCount=$adapterItemCount, size=$recyclerViewSize")
    
    if (position < 0 || position >= adapterItemCount) {
        AppLogger.e("setCurrentItem ABORTED: position out of bounds")
        programmaticScrollInProgress = false
        return
    }
    
    if (smoothScroll) {
        binding.pageRecyclerView.smoothScrollToPosition(position)
    } else {
        layoutManager.scrollToPositionWithOffset(position, 0)
        currentPagerPosition = position
        programmaticScrollInProgress = false
    }
}
```

### 5. ViewModel Navigation Logging

Enhanced `goToWindow()` with detailed state tracking:

```kotlin
fun goToWindow(windowIndex: Int): Boolean {
    val totalWindows = _windowCount.value
    val previousWindow = _currentWindowIndex.value
    
    AppLogger.d("goToWindow: windowIndex=$windowIndex, previousWindow=$previousWindow, totalWindows=$totalWindows")
    
    if (totalWindows <= 0) {
        AppLogger.w("goToWindow BLOCKED: no windows available")
        return false
    }
    
    if (windowIndex !in 0 until totalWindows) {
        AppLogger.w("goToWindow BLOCKED: windowIndex out of range")
        return false
    }
    
    _currentWindowIndex.value = windowIndex
    AppLogger.d("_currentWindowIndex updated from $previousWindow to $windowIndex")
    
    // ... rest of navigation logic
    return true
}
```

### 6. Fragment Lifecycle Tracking

Enhanced adapter logging to track fragment creation:

```kotlin
override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
    AppLogger.d("onBindViewHolder: position=$position, activeFragments=${activeFragments.size}")
    
    val fragment = ReaderPageFragment.newInstance(position)
    
    mainHandler.post {
        fragmentManager.beginTransaction()
            .add(holder.containerView.id, fragment, "f$position")
            .commitAllowingStateLoss()
        
        activeFragments.add(position)
        AppLogger.d("Fragment COMMITTED: position=$position [FRAGMENT_ADDED]")
    }
}
```

### 7. Fragment Edge Detection Validation

Added bounds checking before calling Activity navigation:

```kotlin
private fun navigateToNextWindow() {
    val currentWindow = readerViewModel.currentWindowIndex.value
    val totalWindows = readerViewModel.windowCount.value
    val targetWindow = currentWindow + 1
    
    if (targetWindow >= totalWindows) {
        AppLogger.w("Cannot navigate: target exceeds totalWindows")
        return
    }
    
    readerActivity.navigateToNextPage(animated = true)
}
```

## Log Tags for Debugging

All changes include structured logging with specific tags:

| Tag | Purpose |
|-----|---------|
| `[WINDOW_NAV_REQUEST]` | ViewModel received navigation request |
| `[WINDOW_NAV_BLOCKED]` | Navigation blocked due to validation failure |
| `[WINDOW_STATE_UPDATE]` | ViewModel state (_currentWindowIndex) updated |
| `[WINDOW_NAV_SUCCESS]` | Navigation completed successfully |
| `[SCROLL_REQUEST]` | RecyclerView scroll requested via setCurrentItem |
| `[SCROLL_ERROR]` | Scroll aborted due to validation failure |
| `[SCROLL_SETTLE]` | RecyclerView scroll completed (IDLE state) |
| `[SCROLL_GUARD]` | Circular update prevented by programmatic flag |
| `[FRAGMENT_ADDED]` | Fragment successfully created and committed |
| `[NAV_BLOCKED]` | Navigation attempt blocked at Activity level |
| `[NAV_FAILED]` | ViewModel returned false for navigation |
| `[HANDOFF_TO_ACTIVITY]` | Fragment delegating navigation to Activity |

## Expected Behavior After Fix

### Forward Navigation (Next Window)
1. User at last in-page of window 0 presses hardware key
2. Fragment detects edge, calls `navigateToNextWindow()`
3. Fragment validates target window exists, calls Activity
4. Activity validates target, calls `viewModel.nextWindow()`
5. ViewModel updates `_currentWindowIndex: 0 → 1`
6. Activity sets `programmaticScrollInProgress = true`
7. Activity calls `setCurrentItem(1, animated=true)`
8. RecyclerView scrolls to position 1
9. Adapter's `onBindViewHolder(position=1)` is called
10. Fragment for windowIndex=1 is created
11. OnScrollListener fires when scroll settles
12. OnScrollListener sees `wasProgrammatic=true`, skips ViewModel update
13. Fragment loads HTML for window 1
14. User sees content for window 1

**Logs**:
```
[EDGE_HIT] last in-page, requesting next window from window 0
[HANDOFF_TO_ACTIVITY] Calling ReaderActivity.navigateToNextPage()
[WINDOW_NAV_REQUEST] goToWindow called: windowIndex=1
[WINDOW_STATE_UPDATE] _currentWindowIndex updated from 0 to 1
[SCROLL_REQUEST] setCurrentItem: position=1
[PAGINATION_DEBUG] onBindViewHolder: position=1
[FRAGMENT_ADDED] Fragment COMMITTED: position=1
[SCROLL_SETTLE] RecyclerView settled: position=1, wasProgrammatic=true
[SCROLL_GUARD] Skipping ViewModel update (programmatic scroll)
```

### Backward Navigation (Previous Window + Jump to Last)
1. User at first in-page of window 1 presses hardware key (backward)
2. Fragment detects edge, calls `navigateToPreviousWindowLastPage()`
3. Fragment validates target window exists, calls Activity
4. Activity validates target, calls `viewModel.previousWindow()`
5. ViewModel updates `_currentWindowIndex: 1 → 0`
6. Activity sets `jumpToLastPageFlag` in ViewModel
7. Activity sets `programmaticScrollInProgress = true`
8. Activity calls `setCurrentItem(0, animated=true)`
9. RecyclerView scrolls to position 0
10. Fragment for windowIndex=0 already exists (reused)
11. Fragment checks `shouldJumpToLastPage` flag
12. Fragment waits for paginator ready, then calls `goToPage(lastPage)`
13. OnScrollListener fires when scroll settles
14. OnScrollListener sees `wasProgrammatic=true`, skips ViewModel update
15. User sees last page of window 0

## Testing Recommendations

### Manual Testing
1. **Forward Navigation**:
   - Open book in continuous mode
   - Navigate to last in-page of window 0 using hardware keys
   - Press volume down (next page)
   - Verify: UI advances to window 1 and renders content
   - Check logs for `[FRAGMENT_ADDED]` and `[WINDOW_NAV_SUCCESS]` tags

2. **Backward Navigation**:
   - Navigate to window 1
   - Go to first in-page of window 1
   - Press volume up (previous page)
   - Verify: UI goes to window 0 and jumps to last in-page
   - Check logs for jump-to-last behavior

3. **Gesture Navigation**:
   - Use swipe/fling gestures to navigate between windows
   - Verify: No circular updates in logs
   - Confirm: `[SCROLL_GUARD]` appears for programmatic scrolls

### Log Analysis
Enable filtering by tags:
```bash
adb logcat | grep -E "\[WINDOW_NAV|\[SCROLL_|\[FRAGMENT_ADDED"
```

Expected sequence for successful next window:
```
[WINDOW_NAV_REQUEST] → [WINDOW_STATE_UPDATE] → [SCROLL_REQUEST] → 
[FRAGMENT_ADDED] → [SCROLL_SETTLE] → [SCROLL_GUARD]
```

### Regression Checks
- Verify chapter-based mode still works (not affected by changes)
- Verify in-page navigation within single window works
- Verify TOC navigation to different windows works
- Verify window count and adapter itemCount stay synchronized

## Files Modified

1. **ReaderActivity.kt**:
   - Added `programmaticScrollInProgress` flag
   - Enhanced `navigateToNextPage()` with validation
   - Enhanced `navigateToPreviousPage()` with validation
   - Enhanced `navigateToPreviousChapterToLastPage()` with validation
   - Modified `OnScrollListener` to guard circular updates
   - Enhanced `setCurrentItem()` with comprehensive logging and validation

2. **ReaderViewModel.kt**:
   - Enhanced `goToWindow()` with detailed state logging
   - Added validation checks for window bounds
   - Improved error logging for null paginator

3. **ReaderPageFragment.kt**:
   - Enhanced `navigateToNextWindow()` with validation
   - Enhanced `navigateToPreviousWindowLastPage()` with validation
   - Added bounds checking before calling Activity navigation

4. **ReaderPagerAdapter.kt**:
   - Enhanced `onBindViewHolder()` logging
   - Added fragment lifecycle state tracking
   - Added visibility logging for reused fragments

## Success Criteria

✅ **Next Window Navigation Works**:
- Hardware keys/tap zones at last in-page advance to next window
- RecyclerView scrolls to correct position
- Fragment for windowIndex=1+ is created and rendered
- HTML content for next window loads and displays

✅ **Previous Window Navigation Works**:
- Hardware keys/tap zones at first in-page go to previous window
- Jump-to-last-page flag works correctly
- Fragment shows last in-page of previous window

✅ **No Circular Updates**:
- OnScrollListener skips ViewModel updates during programmatic scrolls
- Logs show `[SCROLL_GUARD]` for prevented circular updates

✅ **Comprehensive Logging**:
- All navigation attempts logged with detailed context
- Fragment lifecycle tracked with position and state
- RecyclerView scroll operations logged with validation results
- Easy to diagnose issues with structured log tags

✅ **No Regressions**:
- Chapter-based mode unaffected
- In-page navigation within window works
- TOC navigation works
- Existing features remain functional

## Content Loading Separation Fix (2025-12-03)

### Problem: Wrong Content Loading Mechanism in Continuous Mode

After fixing window navigation, fragments for windowIndex=1+ were created but remained blank. Investigation revealed fragments were using the wrong content loading mechanism:

**Issue**: All fragments (both continuous and chapter-based mode) used `observePageContent(pageIndex)`:
```kotlin
readerViewModel.observePageContent(pageIndex).collect { page ->
    latestPageHtml = page.html
    renderBaseContent()
}
```

This pattern works in chapter-based mode where:
- `pageIndex` = chapter index
- ViewModel populates `_pages` map with `PageContent` entries
- `observePageContent(chapterIndex)` returns the correct chapter's content

But fails in continuous mode where:
- `pageIndex` = window index (0, 1, 2...)
- ViewModel's `_pages` map is keyed by chapter indices (0, 1, 2, 3, 4, 5...)
- Fragments waiting for `PageContent` keyed by window index never receive updates
- Example: Fragment for windowIndex=1 waits for `_pages[1]`, but window 1 contains chapters 5-9

### Solution: Mode-Specific Content Loading

Separated content loading logic based on pagination mode in `ReaderPageFragment.onViewCreated()`:

#### Continuous Mode
```kotlin
if (readerViewModel.paginationMode == PaginationMode.CONTINUOUS) {
    // Directly render from window buffer
    renderBaseContent()
}
```

**Flow**: 
1. Fragment created with windowIndex
2. `renderBaseContent()` called immediately
3. `getWindowHtml(windowIndex)` fetches from WindowBufferManager cache
4. HTML loaded into WebView

**Key**: No waiting for PageContent updates. Direct fetch from buffer.

#### Chapter-Based Mode
```kotlin
else {
    // Traditional PageContent observer
    setupChapterBasedContentObserver()
}
```

**Flow**:
1. Fragment created with chapterIndex
2. `setupChapterBasedContentObserver()` sets up Flow observer
3. Waits for ViewModel to emit `PageContent` for that chapter
4. When received, renders content

**Key**: Still uses `_pages`, `pageContentCache`, and `observePageContent` as before.

### Implementation Details

**New Method**: `setupChapterBasedContentObserver()`
```kotlin
private fun setupChapterBasedContentObserver() {
    viewLifecycleOwner.lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.STARTED) {
            readerViewModel.observePageContent(pageIndex).collect { page ->
                latestPageText = page.text
                latestPageHtml = page.html
                renderBaseContent()
            }
        }
    }
}
```

**Modified**: `renderBaseContent()`
```kotlin
private fun renderBaseContent() {
    val html = if (readerViewModel.paginationMode == PaginationMode.CONTINUOUS) {
        "CONTINUOUS_MODE_PLACEHOLDER"  // Will fetch from buffer
    } else {
        latestPageHtml  // From PageContent observer
    }
    
    if (!html.isNullOrBlank()) {
        val contentHtml = if (readerViewModel.paginationMode == PaginationMode.CONTINUOUS) {
            val windowPayload = readerViewModel.getWindowHtml(windowIndex)
            windowPayload?.html ?: html
        } else {
            html
        }
        // ... render contentHtml in WebView
    }
}
```

### WindowBufferManager Impact

**No changes required**:
- Still preloads 5 windows
- Still shifts based on `maybeShiftForward/Backward` and `onWindowBecameVisible`
- Still caches window HTML
- Continuous mode fragments now actively consume the cached data via `getWindowHtml()`

### Benefits

1. **Correct Content Loading**: Continuous mode fragments get window HTML immediately
2. **No Breaking Changes**: Chapter-based mode unchanged
3. **Clear Separation**: Each mode has its own content loading path
4. **Better Logging**: Tagged with `[CONTENT_LOAD]` and `[CHAPTER_CONTENT]`
5. **Maintainability**: Easier to debug and modify each mode independently

### Testing Verification

**Continuous Mode**:
- Fragment creation should show: `[CONTENT_LOAD] Continuous mode: directly rendering window X from buffer`
- Should immediately see: `[PAGINATION_DEBUG] Using window HTML for windowIndex=X`
- No waiting for PageContent updates

**Chapter-Based Mode**:
- Fragment creation should show: `[CONTENT_LOAD] Chapter-based mode: setting up PageContent observer for chapter X`
- Should see: `[CHAPTER_CONTENT] Received PageContent for chapter X`
- Traditional observer pattern maintained

## Known Limitations

1. **Smooth Scroll Animation**: The fix uses smooth scrolling which takes time. Rapid navigation requests may queue up.

2. **Fragment Lifecycle**: Fragments for off-screen windows may be destroyed by RecyclerView's default recycling behavior. This is expected and handled by the adapter.

3. **Edge Cases**: Navigation at very first window (0) or very last window may still need validation improvements if book has fewer windows than expected.

## Future Improvements

1. **Instant Navigation Mode**: Add option for instant (non-animated) window transitions for faster navigation.

2. **Prefetch Optimization**: Prefetch fragments for adjacent windows to reduce loading delay.

3. **Window Preloading**: Use WindowBufferManager to preload window HTML before navigation.

4. **Fragment Pool**: Implement fragment recycling pool to reduce creation overhead.

5. **Navigation Queue**: Implement navigation request queue to handle rapid consecutive requests gracefully.

---

**Last Updated**: 2025-12-03
**Commits**: 
- fc66c28: Window navigation circular update fix
- 53a3b92: Content loading separation fix
**Testing Status**: Ready for testing
