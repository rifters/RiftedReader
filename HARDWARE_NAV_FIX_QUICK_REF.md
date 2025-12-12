# Hardware Navigation Fix - Quick Reference

## What Was Fixed
- ✅ Volume keys now advance exactly one page (no more skipping pages 2-4)
- ✅ No freezing after images load in window mode
- ✅ PageCount stays stable when content loads dynamically
- ✅ Automatic snap-to-page after user scrolling

## Key Changes at a Glance

### JavaScript (`minimal_paginator.js`)
```javascript
// OLD: Inconsistent width sources
state.viewportWidth = window.innerWidth;  // Layout
pageCount = scrollWidth / clientWidth;     // Calculation (mismatch!)

// NEW: Single source of truth
const measuredWidth = state.contentWrapper.clientWidth;
state.viewportWidth = measuredWidth;      // Both use same value
```

### Android (`ReaderPageFragment.kt`)
```kotlin
// OLD: Used cached values
val pageCount = WebViewPaginatorBridge.getPageCount(webView)  // Stale!

// NEW: Fresh read before navigation
val freshPageCount = webView.evaluateJavascriptSuspend(
    "window.minimalPaginator.getPageCount()"
)
if (freshPageCount <= 0) return false  // Guard!
```

## When to Check These Logs

### Success Pattern
```
[MIN_PAGINATOR:INIT] Using measured content width: 1080px
[MIN_PAGINATOR:POST_INIT_RECOMPUTE] Running scheduled recompute
[MIN_PAGINATOR:ALL_IMAGES_LOADED] Final recompute
[MIN_PAGINATOR:SNAP] Snapping to page 3
HARDWARE_KEY navigation: inPage=3/10 (fresh read)
HARDWARE_KEY: next in-page (4/10) [IN_PAGE_NAV]
```

### Failure Pattern
```
[NAV_GUARD] navigation BLOCKED: freshPageCount=-1 (paginator not ready)
EDGE_HIT: Navigation attempted with stale values
```

## Files Modified
- `app/src/main/assets/minimal_paginator.js` - Core fixes
- `app/src/main/java/com/rifters/riftedreader/ui/reader/ReaderPageFragment.kt` - Guards

## Testing Commands
```bash
# Build
./gradlew clean assembleDebug --no-daemon

# Test
./gradlew test --no-daemon

# Manual test: Open book with images, use volume keys
# Verify: Single page advancement, no skipping, no freezing
```

## Important Notes

⚠️ **Never use `window.innerWidth` for page width** - Always use measured content width  
⚠️ **Always re-read fresh state** before navigation decisions  
⚠️ **Guard against pageCount <= 0** - Indicates unstable paginator  
⚠️ **Images trigger recompute** - Wait for ALL_IMAGES_LOADED log  

## Related Docs
- Full details: `FIX_HARDWARE_NAV_SUMMARY.md`
- Architecture: `MINIMAL_PAGINATOR_INTEGRATION.md`
- Phase 3 Bridge: `PHASE3_BRIDGE_QUICK_REF.md`
