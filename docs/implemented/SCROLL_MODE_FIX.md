# SCROLL Mode + CONTINUOUS Pagination Fix

**Date**: 2025-01-XX  
**Commit**: TBD

## Problem

Window navigation completely broken in SCROLL mode with CONTINUOUS pagination:
- "EDGE_HIT" never appeared in logs
- Navigation stuck at window 0
- Hardware keys, gestures, tap zones all failed

## Root Cause

The `updateReaderModeUi()` method in `ReaderActivity.kt` was hiding the RecyclerView in SCROLL mode:

```kotlin
// BEFORE (BROKEN)
if (readerMode == ReaderMode.PAGE) {
    binding.pageRecyclerView.isVisible = true   // ✅ Works
} else {
    binding.pageRecyclerView.isVisible = false  // ❌ Hides RecyclerView!
    binding.contentScrollView.isVisible = true
}
```

**The Issue**:
1. CONTINUOUS pagination **requires** RecyclerView (for sliding windows)
2. SCROLL mode was hiding RecyclerView
3. WebView fragments were never visible
4. `handleHardwarePageKey()` returned false at visibility check:
   ```kotlin
   if (binding.pageWebView.visibility != View.VISIBLE) return false
   ```
5. Edge detection never ran
6. Navigation failed silently

## Solution

Changed logic to use RecyclerView whenever CONTINUOUS pagination is enabled, regardless of reader mode:

```kotlin
// AFTER (FIXED)
val useRecyclerView = viewModel.paginationMode == PaginationMode.CONTINUOUS || 
                      readerMode == ReaderMode.PAGE

if (useRecyclerView) {
    binding.contentScrollView.isVisible = false
    binding.pageRecyclerView.isVisible = true  // ✅ Visible for CONTINUOUS
    AppLogger.d("ReaderActivity", "RecyclerView visible for paginationMode=${viewModel.paginationMode}, readerMode=$readerMode")
    viewModel.publishHighlight(viewModel.currentPage.value, currentHighlightRange)
} else {
    // Only use ScrollView for SCROLL mode + CHAPTER_BASED pagination
    binding.pageRecyclerView.isVisible = false
    binding.contentScrollView.isVisible = true
    // ...
}
```

## Architecture Clarification

### Supported Combinations

| Reader Mode | Pagination Mode | View Used | Navigation Type |
|-------------|----------------|-----------|-----------------|
| PAGE | CONTINUOUS | RecyclerView | Sliding windows with edge detection |
| PAGE | CHAPTER_BASED | RecyclerView | Chapter-by-chapter |
| SCROLL | CONTINUOUS | **RecyclerView** | Sliding windows with edge detection |
| SCROLL | CHAPTER_BASED | ScrollView | Traditional scroll |

**Key Insight**: CONTINUOUS pagination **always** uses RecyclerView because it needs sliding window management. Reader mode (PAGE vs SCROLL) affects gesture handling but not the underlying view structure.

## Enhanced Diagnostics

Added detailed logging to `handleHardwarePageKey()` in `ReaderPageFragment.kt`:

```kotlin
com.rifters.riftedreader.util.AppLogger.d(
    "ReaderPageFragment",
    "handleHardwarePageKey BLOCKED: windowIndex=$pageIndex, isWebViewReady=$isWebViewReady, " +
    "webViewVisibility=${binding.pageWebView.visibility} (VISIBLE=${View.VISIBLE}), " +
    "isPaginatorInitialized=$isPaginatorInitialized, readerMode=${(activity as? ReaderActivity)?.readerMode}, " +
    "paginationMode=${readerViewModel.paginationMode} [EDGE_DEBUG]"
)
```

This helps diagnose:
- WebView readiness state
- Visibility values (with constant comparison)
- Paginator initialization
- Current reader mode
- Current pagination mode

## Testing

To verify the fix:

1. **Open app in SCROLL mode with CONTINUOUS pagination**
   - Settings → Reader → Reading Mode: SCROLL
   - Settings → Reader → Pagination: CONTINUOUS

2. **Navigate with hardware keys**
   - Press Volume Down to go forward
   - Check logcat for `[EDGE_DEBUG]` and `[EDGE_AWARE_NAV]` tags
   - Should see "handleHardwarePageKey ACCEPTED"
   - Should eventually see "EDGE_HIT" when reaching window edge

3. **Navigate with gestures**
   - Swipe left (or use configured tap zones)
   - Should navigate through pages
   - At edge, should advance to next window

4. **Expected Log Flow**:
   ```
   [EDGE_DEBUG] handleHardwarePageKey ACCEPTED: windowIndex=0, launching navigation
   [EDGE_AWARE_NAV] HARDWARE_KEY navigation: windowIndex=0, inPage=4/5, paginationMode=CONTINUOUS
   [EDGE_AWARE_NAV] HARDWARE_KEY → goToPage(4)
   [EDGE_AWARE_NAV] HARDWARE_KEY navigation: windowIndex=0, inPage=5/5, paginationMode=CONTINUOUS
   [EDGE_HIT] HARDWARE_KEY at FORWARD edge: inPage=5/5, delegating to Activity
   RecyclerView smoothScrollToPosition: 1
   ```

## Files Changed

- `ReaderActivity.kt`: Modified `updateReaderModeUi()`
- `ReaderPageFragment.kt`: Enhanced logging in `handleHardwarePageKey()`

## Related Issues

- Fixes "navigation stuck at window 0"
- Fixes "EDGE_HIT never appears in logs"
- Complements window navigation circular update fix (fc66c28)
- Complements content loading separation (53a3b92, e8903f1)
- Complements tap zone integration (64b70d5)
- Complements readerMode check removal (36f25a3)

## References

- `WINDOW_NAVIGATION_FIX.md` - Previous window navigation fixes
- `STABLE_WINDOW_MODEL.md` - Sliding window architecture
- `ARCHITECTURE.md` - Overall system architecture
