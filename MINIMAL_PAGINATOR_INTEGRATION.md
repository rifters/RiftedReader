# Minimal Paginator Integration Guide

## Overview

The minimal paginator integration provides a robust, non-invasive pagination system that works with the **WindowBufferManager/conveyor belt system** in the development branch. The integration is **feature-flagged** and disabled by default for backward compatibility.

### Key Difference from Main Branch
- **Main branch**: Conveyor system is isolated for debugging
- **Development branch**: Conveyor system is actively wired to WindowBufferManager and pagination
- **This integration**: Provides stable pagination events to support conveyor phase transitions

## Feature Flag

### Location
The feature flag is stored in **ReaderPreferences** as part of **ReaderSettings**.

```kotlin
// In ReaderSettings data class
val enableMinimalPaginator: Boolean = false  // Default: disabled
```

### How to Enable/Disable

#### Method 1: Via SharedPreferences (Programmatic)
```kotlin
// In your app code
readerPreferences.updateSettings { settings ->
    settings.copy(enableMinimalPaginator = true)
}
```

#### Method 2: Via ADB (For Testing)
```bash
# Enable the feature
adb shell "run-as com.rifters.riftedreader \
  echo 'enable_minimal_paginator=true' >> \
  /data/data/com.rifters.riftedreader/shared_prefs/reader_preferences.xml"

# Disable the feature
adb shell "run-as com.rifters.riftedreader \
  echo 'enable_minimal_paginator=false' >> \
  /data/data/com.rifters.riftedreader/shared_prefs/reader_preferences.xml"
```

#### Method 3: Temporary Toggle (For Development)
You can temporarily hardcode the flag in `ReaderPreferences.kt`:

```kotlin
// In ReaderPreferences.readSettings()
val enableMinimalPaginator = true  // Force enable for testing
```

**Remember to revert this change before committing!**

## Architecture

### Integration with Conveyor System

The minimal paginator integrates with the development branch's conveyor/WindowBufferManager system by:

1. **Stable Pagination Events**: Reports `onPaginationReady` only when totalPages > 0 and stable (avoids 0-page race conditions)
2. **State Synchronization**: Updates `isPaginatorInitialized` flag and chapter metrics (same as existing system)
3. **Boundary Detection**: Triggers window shifts via `handleMinimalPaginatorBoundary` → `navigateToNextPage/PreviousPage`
4. **Conveyor Integration**: `onWindowPaginationReady` updates state flows and chapter metrics used by WindowBufferManager

The WindowBufferManager receives navigation events through existing paths:
- `onEnteredWindow(windowIndex)` - called when user enters a window
- `maybeShiftForward/Backward()` - called when user approaches window boundaries
- `updatePosition()` - called with stable totalPages for progress tracking

### Components

1. **minimal_paginator.js**
   - Location: `app/src/main/assets/minimal_paginator.js`
   - Robust pagination with DOM/font/image stability detection
   - Exposes `window.initPaginator(rootSelector)` for initialization
   - Calls `PaginatorBridge.onPaginationReady(json)` when stable
   - Calls `PaginatorBridge.onBoundary(json)` at window edges

2. **PaginatorBridge.kt**
   - Location: `app/src/main/java/com/rifters/riftedreader/ui/reader/PaginatorBridge.kt`
   - JavascriptInterface that receives callbacks from JS
   - Posts events to main thread
   - Forwards to ReaderViewModel methods

3. **ReaderPageFragment Integration**
   - Location: `app/src/main/java/com/rifters/riftedreader/ui/reader/ReaderPageFragment.kt`
   - Registers `PaginatorBridge` as `"PaginatorBridge"` JavascriptInterface (when flag enabled)
   - Calls `window.initPaginator('#window-root')` after HTML loads (when flag enabled)
   - Handles boundary events via `handleMinimalPaginatorBoundary()`

4. **ReaderViewModel Integration**
   - Location: `app/src/main/java/com/rifters/riftedreader/ui/reader/ReaderViewModel.kt`
   - New method: `onWindowPaginationReady(windowIndex: Int, totalPages: Int)`
   - Updates StateFlows and chapter metrics
   - Integrates with WindowBufferManager/conveyor system through existing navigation methods

### Key Relationships

```
minimal_paginator.js (stable pagination)
         ↓
   PaginatorBridge (JavascriptInterface)
         ↓
   ReaderPageFragment (sets isPaginatorInitialized)
         ↓
   ReaderViewModel.onWindowPaginationReady()
         ↓
   Updates: _totalWebViewPages, chapter metrics
         ↓
   WindowBufferManager/Conveyor (uses via existing navigation methods)
         ↓
   Phase transitions: STARTUP → STEADY
```

## Behavior

### When Feature Flag is DISABLED (default)
- No PaginatorBridge is registered
- No `window.initPaginator()` call is made
- Existing pagination system operates normally
- No performance impact

### When Feature Flag is ENABLED
- PaginatorBridge is registered to WebView before HTML load
- After HTML loads, `window.initPaginator('#window-root')` is called
- minimal_paginator.js waits for stable layout (DOM + fonts + images)
- When stable and totalPages > 0, calls `onPaginationReady`
- When user reaches window boundaries, calls `onBoundary`
- Boundary events trigger existing navigation logic (navigateToNextPage/navigateToPreviousPage)

## Logging

All minimal paginator events are logged with distinct prefixes for easy filtering:

### JavaScript Console Logs
Prefix: `[MIN_PAGINATOR:TAG]`

Examples:
```
[MIN_PAGINATOR:INIT] initPaginator called with selector: #window-root
[MIN_PAGINATOR:INIT_SUCCESS] pageCount=45, charOffsets=45
[MIN_PAGINATOR:BOUNDARY] Reached FORWARD boundary
```

Filter in Chrome DevTools:
```
MIN_PAGINATOR
```

### Android Logcat
Tag: `PaginatorBridge`, `ReaderPageFragment`, `ReaderViewModel`

Filter examples:
```bash
# All paginator-related logs
adb logcat | grep -E "MIN_PAGINATOR|PaginatorBridge"

# Only pagination ready events
adb logcat | grep "PAGINATION_READY"

# Only boundary events
adb logcat | grep "BOUNDARY"
```

## Testing Checklist

### With Feature Flag OFF (default)
- [ ] Open a book - should behave like current development branch
- [ ] Navigate between pages - no console errors
- [ ] Check logcat - no PaginatorBridge messages
- [ ] Window transitions work normally

### With Feature Flag ON
- [ ] Open a book (e.g., "Hell Difficulty Tutorial 2")
- [ ] Check logcat for:
  - `[MIN_PAGINATOR] Registered PaginatorBridge for windowIndex=X`
  - `[MIN_PAGINATOR] Called window.initPaginator for windowIndex=X`
  - `[PAGINATION_READY] windowIndex=X, pageCount=Y` (Y should be > 0)
- [ ] Navigate to last page in window
- [ ] Swipe/tap next - should trigger boundary event
- [ ] Check logcat for: `[BOUNDARY] windowIndex=X, direction=NEXT`
- [ ] Verify window transition occurs (conveyor shift)
- [ ] Navigate to first page in window
- [ ] Swipe/tap previous - should trigger boundary event
- [ ] Check logcat for: `[BOUNDARY] windowIndex=X, direction=PREVIOUS`
- [ ] Verify backward window transition occurs

## Known Issues / TODOs

1. **Dual Pagination Systems**: When feature flag is OFF, `inpage_paginator.js` and `AndroidBridge` handle pagination. When ON, `minimal_paginator.js` and `PaginatorBridge` handle it. Both systems coexist but only one is active at a time based on the flag.

2. **Console Message Capture**: WebView console messages from minimal_paginator.js could be captured via WebChromeClient for better diagnostics. This is optional and not implemented yet.

3. **Character Offset Support**: The minimal paginator tracks character offsets for bookmarks/progress, but this isn't wired to the bookmark system yet.

4. **Conveyor Phase Testing**: The main benefit of stable pagination is avoiding 0-page reports that cause conveyor phase transition issues. Test with books that previously had pagination timing issues.

## Conveyor System Integration Notes

The development branch actively wires the conveyor system (which was isolated in main) to the pagination system:

- **WindowBufferManager**: Manages 5-window buffer with STARTUP → STEADY phase transition
- **Phase Transition Trigger**: When user reaches center window (index 2) of buffer
- **Stable Pagination Benefit**: Ensures `totalPages > 0` before any navigation logic runs
- **Integration Points**:
  - `onEnteredWindow(windowIndex)` - user enters a window
  - `updatePosition(chapterIndex, inPageIndex, totalPagesInWindow)` - progress tracking
  - `maybeShiftForward/Backward()` - buffer shift triggers based on position

The minimal paginator ensures all these integration points receive accurate, stable page counts, preventing race conditions where pagination isn't ready yet.

## Future Enhancements

When the feature is stable and proven:
1. Change default to `true` in ReaderSettings
2. Add UI toggle in Reader Settings screen
3. Wire character offset tracking to bookmark system
4. Full WindowBufferManager conveyor integration
5. Remove feature flag once fully adopted

## Code Locations

| File | Purpose |
|------|---------|
| `app/src/main/assets/minimal_paginator.js` | JavaScript pagination engine |
| `app/src/main/java/com/rifters/riftedreader/ui/reader/PaginatorBridge.kt` | JS-to-Kotlin bridge |
| `app/src/main/java/com/rifters/riftedreader/ui/reader/ReaderPageFragment.kt` | Bridge registration & initialization |
| `app/src/main/java/com/rifters/riftedreader/ui/reader/ReaderViewModel.kt` | `onWindowPaginationReady()` handler |
| `app/src/main/java/com/rifters/riftedreader/data/preferences/ReaderPreferences.kt` | Feature flag storage |

## Support

For issues or questions about the minimal paginator integration:
1. Check the logs with filters above
2. Verify feature flag state
3. Compare behavior with flag on vs off
4. Review this document for expected behavior

---

**Last Updated**: 2025-12-07  
**Status**: Implemented, Feature-Flagged (Default: OFF)
