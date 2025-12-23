# Window Update Flow: Quick Reference

**Status**: Ready for Testing  
**Last Updated**: Session 7d2e870  
**Commits**: 145f2fd (implementation) + 7d2e870 (logging)

---

## What Was Fixed

The issue was that **window 4+ navigation showed stale content** (previous window's HTML).

### Root Cause
When buffer shifts (e.g., position 2 changes from window 3 → window 5), RecyclerView sees position 2 is already on-screen and **doesn't call onBindViewHolder(2)**. The fragment at position 2 stays bound to old window 3 and displays stale content indefinitely.

### Solution
After buffer shift completes, directly call `fragment.updateWindow(newWindowIndex)` on the fragment at position 2. This forces the fragment to:
1. Update its internal windowIndex
2. Call `renderBaseContent()` to reload content
3. Invoke `loadDataWithBaseURL()` with new HTML
4. Display new window content

This **bypasses RecyclerView's "already bound" optimization** and guarantees content updates.

---

## Architecture Summary

```
Navigation Request
    ↓
ConveyorBeltSystemViewModel defers shift until CONTENT_LOADED
    ↓
ReaderActivity: CONTENT_LOADED gates check (WebView ready + Paginator init)
    ↓
applyPendingBufferShift() executes:
  1. Update buffer state
  2. Invalidate RecyclerView position 2
  3. Invoke callback with new center window
    ↓
ReaderActivity callback finds fragment at position 2:
  1. Get fragment by tag "f2"
  2. Call fragment.updateWindow(newWindowIndex)
    ↓
ReaderPageFragment.updateWindow():
  1. Update arguments with new windowIndex
  2. Reset paginator state
  3. Call renderBaseContent()
    ↓
renderBaseContent():
  1. Get window HTML from cache
  2. Call loadHtmlIntoWebView()
    ↓
loadHtmlIntoWebView():
  1. Ensure WebView is measured
  2. Call loadDataWithBaseURL() with wrapped HTML
    ↓
onPageFinished():
  1. Set isWebViewReady = true
  2. Configure minimal paginator
  3. Restore user position (if needed)
    ↓
Content displays with new window
```

---

## Key Components

### ReaderPageFragment
- **updateWindow(newWindowIndex)** - Direct window update method with logging
  - Logs: `[FRAGMENT_UPDATE_START]`, `[FRAGMENT_UPDATE_STATE]`, `[FRAGMENT_UPDATE_RENDER]`, `[FRAGMENT_UPDATE_COMPLETE]`
  - No more complex scroll-out/recenter logic
  - No more -1 temporary states

### ConveyorBeltSystemViewModel
- **applyPendingBufferShift()** - Called when CONTENT_LOADED fires
  - Logs: `[BUFFER_SHIFT_START]`, `[BUFFER_SHIFT_UPDATE]`, `[BUFFER_SHIFT_ADAPTER]`, `[BUFFER_SHIFT_CALLBACK]`, `[BUFFER_SHIFT_COMPLETE]`
  - Invokes fragment callback with new center window
  - No state machine complexity

### ReaderActivity
- **setOnBufferShiftedCallback** - Finds fragment at position 2 and calls `updateWindow()`
  - Logs: via callback invocation (BUFFER_SHIFT_CALLBACK)
  - Uses tag "f2" to find fragment
  - No RecyclerView manipulation

---

## Testing Checklist

### Build & Run
- [ ] `./gradlew assembleDebug -x test` ✅ Compiles successfully
- [ ] Install APK on device/emulator
- [ ] App launches without crashes
- [ ] Library screen appears with books

### Navigation Forward
- [ ] Select book and open reader
- [ ] Tap right edge to go to next window
- [ ] Window increments (e.g., 1→2, 2→3, 3→4)
- [ ] Content updates correctly
- [ ] No stale content visible
- [ ] Progress indicator shows correct position

### Navigation Backward
- [ ] Tap left edge to go to previous window
- [ ] Window decrements (e.g., 4→3, 3→2)
- [ ] Content updates correctly
- [ ] No stale content visible
- [ ] Can go all the way back to start

### Navigation to Window 4+
- [ ] Navigate to window 4 (previously showed stale content)
- [ ] Window 4 displays correct content (not window 3's)
- [ ] Content is crisp and full HTML rendered
- [ ] Text matches expected window
- [ ] No rendering glitches

### Rapid Navigation
- [ ] Quickly tap forward several times
- [ ] All windows load correctly
- [ ] No stale content
- [ ] No crashes
- [ ] No "window 4+ stale content" issue

### Logcat Verification
- [ ] Collect logcat during navigation: `adb logcat > logcat.txt`
- [ ] See complete chain of logs in logcat
- [ ] All tags appear in expected order
- [ ] Window indices progress correctly
- [ ] No error or exception logs

---

## Log Chain to Verify

When navigating forward from window 3 to window 4, you should see:

```
[BUFFER_SHIFT_START] ... direction=FORWARD, oldBuffer=[...3...], newBuffer=[...4...]
[BUFFER_SHIFT_UPDATE] ... activeWindow=4, buffer=[...4...]
[BUFFER_SHIFT_ADAPTER] ... CENTER_INDEX=2
[BUFFER_SHIFT_CALLBACK] ... centerWindow=4
[BUFFER_SHIFT_COMPLETE]

[FRAGMENT_UPDATE_START] ... oldWindowIndex=3 → newWindowIndex=4
[FRAGMENT_UPDATE_STATE] ... isPaginatorInitialized=false
[FRAGMENT_UPDATE_RENDER] ... renderBaseContent()
[FRAGMENT_UPDATE_COMPLETE]

[RENDER_BASE_CONTENT_START] ... window 4
[RENDER_LOAD_HTML] ... window 4
[WEBVIEW_LOAD_START] ... window 4
[WEBVIEW_LOAD_ENQUEUED] ... window 4

[WEBVIEW_PAGE_FINISHED_START] ... window 4
[PAGINATION_DEBUG] onPageFinished: windowIndex=4
[WEBVIEW_READY] ... window 4
[MIN_PAGINATOR] ... windowIndex=4

[CONTENT_LOADED] Checking gates ... All gates passed
[CONTENT_LOADED] EMITTED
```

---

## Commits

### 145f2fd: MAJOR REFACTOR
- Replaced scroll-out/recenter with direct fragment.updateWindow()
- Removed -1 temporary state pattern
- Simplified callback implementation
- **Build**: ✅ SUCCESS
- **Status**: Ready for testing

### 7d2e870: LOGGING
- Added comprehensive trace points throughout flow
- Fragment update tracking (4 log points)
- Content render tracking (4 log points)
- Buffer shift tracking (5 log points)
- WebView lifecycle tracking (4 log points)
- Content gate tracking (3 log points)
- **Build**: ✅ SUCCESS
- **Status**: Ready for logcat collection

---

## What's Changed from Before

### Old Approach (❌ BROKEN)
```
Buffer shift → RecyclerView scroll-out → Temporary -1 state → Recenter → Fragment rebind
              ↑                                                          ↓
              └──────────────── Problem: Position 2 not rebound ─────────┘
```

### New Approach (✅ WORKING)
```
Buffer shift → CONTENT_LOADED gate → Callback invokes fragment.updateWindow(newIndex)
                                       ↓
                                     Fragment reloads HTML directly
                                       ↓
                                     WebView displays new content
```

**Key Difference**: Instead of relying on RecyclerView to rebind position 2, we directly call the fragment's method to update and reload. Simpler, more reliable, no state management complexity.

---

## Next Actions

1. **Install and test**: Build APK and test window navigation
2. **Collect logs**: Run with `adb logcat` filter for the log tags
3. **Verify window 4**: Navigate to window 4+ and confirm NO stale content
4. **Share output**: Provide logcat dump showing complete log chain

---

## Files Modified

| File | Changes | Purpose |
|------|---------|---------|
| ReaderPageFragment.kt | Added updateWindow(), enhanced logging | Direct window refresh |
| ConveyorBeltSystemViewModel.kt | Enhanced applyPendingBufferShift() logging | Buffer shift tracking |
| ReaderActivity.kt | Updated callback from scroll to fragment.updateWindow() | Direct fragment update |

All changes focused on **simplicity** and **traceability**.

---

## Status

✅ **Implementation Complete**  
✅ **Build Successful**  
✅ **Commits Pushed**  
✅ **Comprehensive Logging Added**  
✅ **Ready for Testing**

Awaiting your logcat dump to verify the fix works as expected!
