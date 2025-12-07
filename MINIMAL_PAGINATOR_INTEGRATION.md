# Minimal Paginator Integration Guide

## Overview

The minimal paginator integration provides a robust, non-invasive pagination system that integrates with the **ConveyorBeltSystemViewModel** in the development branch. The integration is **feature-flagged** and **enabled by default** for all development and QA work.

### Architecture Overview
- **Development branch**: ConveyorBeltSystemViewModel is the window controller (replacing main's WindowBufferManager)
- **Main branch**: Uses WindowBufferManager for window management
- **This integration**: Minimal paginator calls `ConveyorBeltSystemViewModel.onWindowEntered()` when pagination is stable

The conveyor system manages:
- 5-window buffer lifecycle
- STARTUP → STEADY phase transitions (unlocked at window 2, triggered at window 3)
- Window shifting (forward/backward navigation)
- Active window tracking

## Feature Flag

### Location
The feature flag is stored in **ReaderPreferences** as part of **ReaderSettings**.

```kotlin
// In ReaderSettings data class
val enableMinimalPaginator: Boolean = true  // Default: ENABLED for development
```

### Default State

**As of December 2025, the minimal paginator is ENABLED BY DEFAULT** for all development and QA builds. This ensures all ongoing work covers the new paginator/conveyor system without manual setup.

- Fresh installs: Minimal paginator active
- Upgrades: Minimal paginator active (unless previously disabled by user)
- ConveyorBeltSystemViewModel: Always wired and ready

### How to Disable (Testing Legacy Paginator)

#### Method 1: Via SharedPreferences (Programmatic)
```kotlin
// In your app code - to DISABLE the minimal paginator
readerPreferences.updateSettings { settings ->
    settings.copy(enableMinimalPaginator = false)
}

// To RE-ENABLE (restore default)
readerPreferences.updateSettings { settings ->
    settings.copy(enableMinimalPaginator = true)
}
```

#### Method 2: Via ADB (For Testing Legacy Paginator)
```bash
# Disable the feature (test legacy paginator)
adb shell "run-as com.rifters.riftedreader \
  echo 'enable_minimal_paginator=false' >> \
  /data/data/com.rifters.riftedreader/shared_prefs/reader_preferences.xml"

# Re-enable the feature (restore default)
adb shell "run-as com.rifters.riftedreader \
  echo 'enable_minimal_paginator=true' >> \
  /data/data/com.rifters.riftedreader/shared_prefs/reader_preferences.xml"
```

#### Method 3: Temporary Toggle (For Development)
You can temporarily hardcode the flag in `ReaderPreferences.kt`:

```kotlin
// In ReaderPreferences.readSettings()
val enableMinimalPaginator = false  // Force disable for legacy testing
```

**Remember to revert this change before committing!**

## Architecture

### Integration with Conveyor System

The minimal paginator integrates with ConveyorBeltSystemViewModel by:

1. **Stable Pagination Events**: Reports `onPaginationReady` only when totalPages > 0 and stable (avoids 0-page race conditions)
2. **State Synchronization**: Updates `isPaginatorInitialized` flag and chapter metrics (same as existing system)
3. **Boundary Detection**: Triggers window navigation via `handleMinimalPaginatorBoundary` → `navigateToNextPage/PreviousPage`
4. **Conveyor Notification**: `onWindowPaginationReady` calls `ConveyorBeltSystemViewModel.onWindowEntered()` with stable page count

The ConveyorBeltSystemViewModel manages:
- **STARTUP phase**: Initial 5-window buffer (windows 0-4), user starts at window 0
- **Unlock condition**: Reaching window 2 unlocks buffer shifts
- **STEADY transition**: Moving to window 3 triggers STARTUP → STEADY phase
- **STEADY phase**: Keeps active window centered with 2 windows ahead/behind, shifts buffer as needed

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
   - New method: `setConveyorBeltSystem(ConveyorBeltSystemViewModel)` - sets the conveyor reference
   - New method: `onWindowPaginationReady(windowIndex: Int, totalPages: Int)`
   - Updates StateFlows, chapter metrics, and calls `conveyor.onWindowEntered(windowIndex)`

5. **ConveyorBeltSystemViewModel** (existing)
   - Location: `app/src/main/java/com/rifters/riftedreader/ui/reader/conveyor/ConveyorBeltSystemViewModel.kt`
   - Manages window buffer and phase transitions
   - `onWindowEntered(windowIndex)` - entry point for pagination events
   - Tracks phase (STARTUP/STEADY), buffer state, and active window

### Key Relationships

```
minimal_paginator.js (stable totalPages > 0)
         ↓
   PaginatorBridge (JavascriptInterface)
         ↓
   ReaderPageFragment (sets isPaginatorInitialized)
         ↓
   ReaderViewModel.onWindowPaginationReady()
         ↓
   ConveyorBeltSystemViewModel.onWindowEntered(windowIndex)
         ↓
   Phase transitions: STARTUP → STEADY
   Buffer shifts: forward/backward navigation
```

## Behavior

### When Feature Flag is DISABLED (for legacy testing)
- No PaginatorBridge is registered
- No `window.initPaginator()` call is made
- Existing pagination system operates normally
- No performance impact
- Use this mode to compare behavior or debug regressions

### When Feature Flag is ENABLED (default)
- PaginatorBridge is registered to WebView before HTML load
- After HTML loads, `window.initPaginator('#window-root')` is called
- minimal_paginator.js waits for stable layout (DOM + fonts + images)
- When stable and totalPages > 0, calls `onPaginationReady`
- When user reaches window boundaries, calls `onBoundary`
- Boundary events trigger existing navigation logic (navigateToNextPage/navigateToPreviousPage)
- ConveyorBeltSystemViewModel manages window lifecycle and phase transitions

### When Feature Flag is DISABLED (for legacy testing)
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

### With Feature Flag OFF (legacy testing - requires manual toggle)
- [ ] Disable flag via ADB or code (see "How to Disable" section above)
- [ ] Open a book - should behave like pre-minimal-paginator development branch
- [ ] Navigate between pages - no console errors
- [ ] Check logcat - no PaginatorBridge messages
- [ ] Window transitions work normally using legacy system

### With Feature Flag ON (default - should always work)
- [ ] Open a book (e.g., "Hell Difficulty Tutorial 2")
- [ ] Check logcat for:
  - `[MINIMAL_PAGINATOR_DEFAULT] Minimal paginator is now DEFAULT for development: ENABLED`
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

### With Feature Flag OFF (legacy testing - requires manual toggle)
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

5. **ConveyorBeltSystemViewModel Setup**: ✅ **COMPLETE** - The conveyor system is automatically instantiated and wired in ReaderActivity.onCreate(). It receives pagination events via ReaderViewModel.setConveyorBeltSystem(). No manual setup needed.

## Conveyor System Integration Notes

The development branch uses ConveyorBeltSystemViewModel as the primary window controller (replacing main's WindowBufferManager):

- **Purpose**: Drives window management and phase transitions
- **STARTUP phase**: Initial 5-window buffer, user at window 0, shifts unlock at window 2, STEADY triggers at window 3
- **STEADY phase**: Active window kept centered with 2 windows ahead/behind
- **Integration**: Minimal paginator calls `onWindowEntered(windowIndex)` when pagination is stable
- **Benefit**: Ensures pagination is ready before phase transitions occur

## Future Enhancements

~~When the feature is stable and proven:~~ **UPDATE: Completed December 2025**
1. ✅ Change default to `true` in ReaderSettings
2. ✅ Wire ConveyorBeltSystemViewModel automatically in ReaderActivity
3. Add UI toggle in Reader Settings screen (future enhancement)
4. Wire character offset tracking to bookmark system (future enhancement)
5. Remove feature flag once fully adopted (future consideration)
6. Consider removing WindowBufferManager once conveyor system fully replaces it (future consideration)

## Code Locations

| File | Purpose |
|------|---------|
| `app/src/main/assets/minimal_paginator.js` | JavaScript pagination engine |
| `app/src/main/java/com/rifters/riftedreader/ui/reader/PaginatorBridge.kt` | JS-to-Kotlin bridge |
| `app/src/main/java/com/rifters/riftedreader/ui/reader/ReaderPageFragment.kt` | Bridge registration & initialization |
| `app/src/main/java/com/rifters/riftedreader/ui/reader/ReaderViewModel.kt` | `onWindowPaginationReady()` + `setConveyorBeltSystem()` |
| `app/src/main/java/com/rifters/riftedreader/ui/reader/conveyor/ConveyorBeltSystemViewModel.kt` | Window controller (receives `onWindowEntered()`) |
| `app/src/main/java/com/rifters/riftedreader/data/preferences/ReaderPreferences.kt` | Feature flag storage |

## Support

For issues or questions about the minimal paginator integration:
1. Check the logs with filters above
2. Verify feature flag state
3. Compare behavior with flag on vs off
4. Review this document for expected behavior

---

**Last Updated**: 2025-12-07  
**Status**: Implemented, Feature-Flagged (Default: **ON** - Enabled for all development/QA)
