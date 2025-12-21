# CRITICAL FIX: Wrong Paginator Was Being Loaded

## The Problem

The app was failing with `inPage=0/-1` (pageCount = -1) despite having a cache synchronization mechanism in place. The cache was never being populated.

**Root Cause**: The HTML was loading **`inpage_paginator.js`** but the Kotlin code was trying to use **`minimal_paginator`** API:

```kotlin
// WebViewPaginatorBridge.kt calls:
evaluateJavascript("window.minimalPaginator.initialize()")
evaluateJavascript("window.minimalPaginator.isReady()")
evaluateJavascript("window.minimalPaginator.goToPage(...)")
evaluateJavascript("window.minimalPaginator.nextPage()")

// But the HTML was loading:
<script src=".../inpage_paginator.js"></script>  // ← WRONG!

// So window.minimalPaginator doesn't exist!
// All JS calls silently fail
// Cache never gets synced
// pageCount stays -1
```

## The Fix

Changed line 1295 in `ReaderPageFragment.kt`:

```kotlin
// BEFORE:
<script src="https://${EpubImageAssetHelper.ASSET_HOST}/assets/inpage_paginator.js"></script>

// AFTER:
<script src="https://${EpubImageAssetHelper.ASSET_HOST}/assets/minimal_paginator.js"></script>
```

## Why This Works

`minimal_paginator.js` provides:
1. **`window.minimalPaginator` API** that Kotlin code expects
2. **`syncPaginationState()` function** that calls `window.AndroidBridge._syncPaginationState(pageCount, currentPage)`
3. **Synchronization calls** at initialization (line 119) and after page navigation (line 175)

The sync chain now works:

```
minimal_paginator.js:initialize()
  ↓ (line 119)
  syncPaginationState()
  ↓
  window.AndroidBridge._syncPaginationState(state.pageCount, state.currentPage)
  ↓
  PaginationBridge._syncPaginationState()  [has @JavascriptInterface]
  ↓
  WebViewPaginatorBridge._syncPaginationState()
  ↓
  cachedPageCount = pageCount  (e.g., 47 instead of -1!)
  cachedCurrentPage = currentPage
  ↓
  ReaderPageFragment.getCurrentPage() reads from cache
  ↓
  Edge detection works: if (currentPage < pageCount - 1) → in-page scroll ✓
```

## Files Changed

- `ReaderPageFragment.kt` line 1295: Changed script src from `inpage_paginator.js` → `minimal_paginator.js`

## Testing

After building and installing the new APK:

1. **Open a book** (multi-page EPUB/PDF)
2. **Check console logs** for `_syncPaginationState` entries confirming sync is happening
3. **Press Volume Down** repeatedly
4. **Expected**:
   - Log shows: `inPage=0/47`, `inPage=1/47`, `inPage=2/47`... (not `0/-1`)
   - Pages scroll within window, not jump immediately to next window
   - At last page of window: jumps to next window

## Commit

```
Fix: Load correct minimal_paginator.js instead of inpage_paginator.js

This was the critical bug - Kotlin code called window.minimalPaginator API
but HTML was loading the old inpage_paginator.js which doesn't export that
API. Result: all JS calls failed silently, cache never synced, pageCount=-1.

Now loads minimal_paginator.js which:
- Exports window.minimalPaginator API that Kotlin expects
- Calls syncPaginationState() after init and navigation
- Properly syncs page state to Kotlin cache

This enables in-page scrolling instead of immediate window jumps.
```

## Related Files

- `WebViewPaginatorBridge.kt`: Calls window.minimalPaginator API
- `PaginationBridge` (in ReaderPageFragment.kt): Receives sync callback
- `minimal_paginator.js`: Provides the correct API and sync calls
- `inpage_paginator.js`: Old paginator (no longer used)
