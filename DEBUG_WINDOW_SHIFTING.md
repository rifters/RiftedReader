# Window Shifting System Debug Guide

## Problem Statement

You reported: "i never see win5 being created unless i missed it"

This means window 5 never appears in the logs when you test the app. This guide will help you identify exactly where the system is blocking.

---

## Expected Log Sequence (Happy Path)

When everything works correctly, you should see this sequence:

```
1. User opens book, initializes buffer:
   [CONVEYOR] onEnteredWindow: globalWindowIndex=0, phase=STARTUP, buffer=[0, 1, 2, 3, 4]

2. User scrolls to window 2 (center):
   [WINDOW_ENTER] windowIndex=2, previousWindow=0, direction=NEXT

3. Window visibility triggers phase transition:
   [CONVEYOR] onWindowBecameVisible: windowIndex=2, ...
   [PHASE_TRANS_DEBUG] Checking transition: centerWindow=2, globalWindow=2, match=true
   [CONVEYOR] *** PHASE TRANSITION STARTUP -> STEADY ***
   Entered center window (2) of buffer. Now entering steady state...

4. User scrolls to page near end (page 28 of 30):
   [EDGE_CHECK] page=28/30, phase=STEADY, window=2, cooldown=false

5. Edge condition triggers maybeShiftForward:
   [CONVEYOR] Near window END: page 28/30 - CALLING maybeShiftForward()

6. ViewModel receives call:
   [PHASE_CHECK_BEFORE_SHIFT] maybeShiftForward ENTRY: currentPage=28/30, phase=STEADY

7. Buffer shifts and window 5 is preloaded:
   [CONVEYOR] maybeShiftForward TRIGGERED
   ...window check passed...
   [CONVEYOR] *** SHIFT FORWARD ***
       oldBuffer=[0, 1, 2, 3, 4]
       newBuffer=[1, 2, 3, 4, 5]
       droppedWindow=0
       newlyCreated=5 (preloading...)
   [PAGINATION_DEBUG] preloadWindow: starting preload for window 5
   [PAGINATION_DEBUG] preloadWindow: cached window 5
```

---

## Diagnostic Checklist

Use this checklist to identify where the system breaks:

### 1. **Phase Transition Failing**

Look for:
```
[PHASE_CHECK_BEFORE_SHIFT] maybeShiftForward ENTRY: currentPage=28/30, phase=STARTUP
```

**Problem**: Phase is STARTUP (not STEADY)

**Check**:
- Search for: `[PHASE_TRANS_DEBUG]`
- If FOUND: Phase transition was attempted but condition failed
  - Check: `centerWindow` vs `globalWindow` values
  - They should match (both should be 2)
  - If not matching: Buffer size mismatch problem
- If NOT FOUND: `onWindowBecameVisible` never called

**Solution**:
- Ensure you're scrolling to window 2
- Check if RecyclerView scroll listener is firing
- Look for `[WINDOW_ENTER]` logs

---

### 2. **Edge Detection Blocked by Cooldown**

Look for:
```
[CONVEYOR] BLOCKED: Near END but IN_COOLDOWN: page=28/30
```

**Problem**: This is EXPECTED behavior for 300ms after entering a window

**Solution**:
- Wait at least 300ms after entering a new window before scrolling near the end
- Then try again - should see `[CONVEYOR] Near window END - CALLING maybeShiftForward()`

---

### 3. **maybeShiftForward Never Called**

Search for:
```
[PHASE_CHECK_BEFORE_SHIFT] maybeShiftForward ENTRY
```

If NOT FOUND:
- Edge detection condition not triggered
- Search for: `[EDGE_CHECK]` logs
- If page numbers look wrong, check if `onPageChanged` is being called

**Check JavaScript trigger**:
```bash
adb logcat | grep "Calling AndroidBridge.onPageChanged"
```

Should see messages like:
```
inpage_paginator: syncCurrentPageFromScroll - Calling AndroidBridge.onPageChanged with page=28
```

If NO JavaScript messages:
- `syncCurrentPageFromScroll()` not detecting page changes
- User not actually scrolling in the WebView
- JavaScript paginator not initialized

---

### 4. **onPageChanged Callback Not Reached**

Search for:
```
PaginationBridge.onPageChanged: fragmentPage=2, inPage=28/30
```

If NOT FOUND:
- JavaScript is calling `AndroidBridge.onPageChanged()`
- But Android callback is not being invoked
- Likely cause: JavaScript bridge not properly connected

**Check bridge registration**:
```bash
adb logcat | grep "WebView.*JavaScript.*Interface"
```

Should appear when activity starts.

---

### 5. **Window Visibility Never Detected**

Search for:
```
[CONVEYOR] onWindowBecameVisible: windowIndex=2
```

If NOT FOUND:
- RecyclerView scroll listener not firing
- RecyclerView settling at correct position

**Check RecyclerView events**:
```bash
adb logcat | grep "RecyclerView.onScrollStateChanged\|WINDOW_ENTER"
```

Should see:
```
RecyclerView.onScrollStateChanged: state=SETTLING
RecyclerView.onScrollStateChanged: state=IDLE
[CONVEYOR] Window 2 became visible
```

---

## Key Log Search Terms

| Search Term | Meaning | Expected Frequency |
|---|---|---|
| `[CONVEYOR]` | Any buffer shifting log | Many times while reading |
| `[EDGE_CHECK]` | Edge detection at page level | Very frequent (every page change) |
| `[PHASE_CHECK_BEFORE_SHIFT]` | maybeShiftForward entry | When near window boundary |
| `[PHASE_TRANS_DEBUG]` | Phase transition attempts | Once per book load (at window 2) |
| `[PHASE TRANSITION STARTUP -> STEADY]` | Successful phase transition | Once per book load |
| `*** SHIFT FORWARD ***` | Window buffer actually shifting | When crossing window boundary |
| `preloadWindow: starting preload for window 5` | Window 5 being preloaded | After SHIFT FORWARD |
| `[WINDOW_ENTER]` | Window boundary crossing | When scrolling between windows |
| `Calling AndroidBridge.onPageChanged` | JavaScript trigger firing | Every time page changes |

---

## Testing Sequence

Follow this step-by-step to test:

### Step 1: Open Book
```bash
adb logcat -c  # Clear logs
adb logcat | grep "CONVEYOR"
```

Should see:
```
[CONVEYOR] onEnteredWindow: globalWindowIndex=0, phase=STARTUP
[CONVEYOR] Buffer initialized: buffer=[0, 1, 2, 3, 4]
```

### Step 2: Navigate to Window 2
Scroll RecyclerView to position 2 (center)

Should see:
```
[WINDOW_ENTER] windowIndex=2
[CONVEYOR] onWindowBecameVisible: windowIndex=2
[PHASE_TRANS_DEBUG] Checking transition: centerWindow=2, globalWindow=2, match=true
[CONVEYOR] *** PHASE TRANSITION STARTUP -> STEADY ***
```

### Step 3: Scroll in WebView
Within window 2, scroll down to near the end (page 28+ of 30)

Wait 300ms for cooldown to expire, then continue scrolling down

Should see:
```
[EDGE_CHECK] page=28/30, phase=STEADY, window=2, cooldown=false
[CONVEYOR] Near window END: page 28/30 - CALLING maybeShiftForward()
[PHASE_CHECK_BEFORE_SHIFT] maybeShiftForward ENTRY: currentPage=28/30, phase=STEADY
```

### Step 4: Trigger Shift
Continue scrolling to actually reach the window boundary (may need to swipe or tap next button)

Should see:
```
[CONVEYOR] *** SHIFT FORWARD ***
  oldBuffer=[0, 1, 2, 3, 4]
  newBuffer=[1, 2, 3, 4, 5]
  newlyCreated=5 (preloading...)
[PAGINATION_DEBUG] preloadWindow: starting preload for window 5
```

---

## Build and Deploy

Build with debugging enabled:

```bash
cd /workspaces/RiftedReader

# Build
./gradlew build

# Ensure APK was created
ls -lh app/build/outputs/apk/debug/app-debug.apk

# Install on device
adb install -r app/build/outputs/apk/debug/app-debug.apk

# View logs (filter to relevant ones)
adb logcat | grep -E "CONVEYOR|EDGE_CHECK|PHASE_CHECK|PHASE_TRANS|preloadWindow"
```

---

## If Window 5 Still Doesn't Appear

### A. Check Every Level

```bash
# 1. JavaScript trigger
adb logcat | grep "Calling AndroidBridge"

# 2. Android callback
adb logcat | grep "PaginationBridge.onPageChanged"

# 3. Edge detection
adb logcat | grep "EDGE_CHECK"

# 4. Phase state
adb logcat | grep "PHASE_CHECK_BEFORE_SHIFT"

# 5. Shift execution
adb logcat | grep "SHIFT FORWARD"

# 6. Window preload
adb logcat | grep "preloadWindow"
```

Each line should appear once you reach that stage.

### B. Check for Exceptions

```bash
adb logcat | grep -E "Exception|Error|Crash"
```

Any exceptions will block execution.

### C. Check Phase Value

Add a manual log check by searching for all phase values:

```bash
adb logcat | grep "phase="
```

Should show progression:
```
phase=STARTUP  (first)
phase=STARTUP  (at window 1)
phase=STEADY   (at window 2) <- TRANSITION POINT
phase=STEADY   (onwards)
```

---

## Expected Behavior Summary

| Action | Log Appearance | Phase Value | Buffer State |
|--------|---|---|---|
| Open book | `onEnteredWindow: 0` | STARTUP | `[0,1,2,3,4]` |
| Scroll to win 1 | `WINDOW_ENTER: 1` | STARTUP | `[0,1,2,3,4]` |
| Scroll to win 2 | `WINDOW_ENTER: 2` + `PHASE TRANSITION` | STEADY | `[0,1,2,3,4]` |
| Near end of win 2 | `Near window END` | STEADY | `[0,1,2,3,4]` |
| Shift forward | `SHIFT FORWARD` | STEADY | `[1,2,3,4,5]` ← Window 5! |

---

## Summary

- **If window 5 doesn't appear**: It means `SHIFT FORWARD` log never shows
- **If SHIFT FORWARD doesn't show**: Phase is STARTUP (check phase transition)
- **If phase transition fails**: Center window index mismatch (buffer initialization problem)
- **If edge detection fails**: `onPageChanged` not being called (JavaScript issue)
- **If callback fails**: JavaScript bridge not registered

Follow the checklist above to identify which level is failing.

---

## Next Steps

1. **Build and deploy** with the new debugging enabled
2. **Test following the sequence** above
3. **Report which log line is missing**
4. I'll help diagnose from there

Good luck! 🚀
