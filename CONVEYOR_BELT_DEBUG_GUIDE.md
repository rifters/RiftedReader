# Sliding Window Conveyor Belt Debug Guide

**Date**: December 4, 2025  
**Purpose**: Track and debug the STARTUP -> STEADY phase transition and buffer shifting in continuous pagination mode

---

## Quick Reference: The Two-Phase Lifecycle

### Phase 1: STARTUP
- Begins when app loads or chapter starts
- Fills initial 5-window buffer: [window0, window1, window2, window3, window4]
- User starts at window 0
- **Transition Trigger**: User navigates to window 2 (center window)
- Buffer does NOT shift during STARTUP

### Phase 2: STEADY
- Begins when user enters center window (index 2) of buffer
- Keeps active window centered with 2 ahead and 2 behind
- When user swipes forward/backward, buffer shifts to maintain centering
- Forward: drop window 0, append window 5
- Backward: drop window 4, prepend new window

---

## What to Log For

### 1. Entry Points Being Called

#### ReaderViewModel.onWindowBecameVisible()

**Expected to be called**:
- During normal page swipes (when user settles on new window)
- When user jumps via Table of Contents
- Both forward and backward navigation

**Log Example**:
```
[WINDOW_VISIBILITY] *** onWindowBecameVisible ENTRY *** windowIndex=2
[WINDOW_VISIBILITY] Before onEnteredWindow call:
[WINDOW_VISIBILITY]   windowIndex=2
[WINDOW_VISIBILITY]   phase=STARTUP
[WINDOW_VISIBILITY]   buffer=[0, 1, 2, 3, 4]
[WINDOW_VISIBILITY]   centerWindow=2
```

**Search LogCat**:
```bash
adb logcat | grep "onWindowBecameVisible ENTRY"
```

#### WindowBufferManager.onEnteredWindow()

**Called by**: ReaderViewModel.onWindowBecameVisible()

**Log Example**:
```
[WINDOW_ENTRY] ENTRY: onEnteredWindow(globalWindowIndex=2)
[WINDOW_ENTRY] phase=STARTUP
[WINDOW_ENTRY] globalWindowIndex=2
[WINDOW_ENTRY] buffer.toList()=[0, 1, 2, 3, 4]
[WINDOW_ENTRY] getCenterWindowIndex()=2
[WINDOW_ENTRY] Current state: phase=STARTUP, buffer=[0, 1, 2, 3, 4], ...
[PHASE_TRANS_DEBUG] Checking transition condition: centerWindow=2, globalWindow=2
[WINDOW_ENTRY] *** PHASE TRANSITION: STARTUP -> STEADY ***
```

---

## 2. Phase Transition Logic

### The Transition Condition

**When to transition**: `globalWindowIndex == getCenterWindowIndex()` for the first time

**Expected path**:
1. User navigates to window 2 (center of [0,1,2,3,4])
2. `onEnteredWindow(2)` called
3. Check: `2 == getCenterWindowIndex()` → True
4. Check: `!hasEnteredSteadyState` → True
5. **Action**: Set `hasEnteredSteadyState = true`, set phase = STEADY

**Log verification**:
```
[PHASE_TRANS_DEBUG] Checking transition condition: centerWindow=2, globalWindow=2
[WINDOW_ENTRY] *** PHASE TRANSITION: STARTUP -> STEADY ***
[CONVEYOR] *** PHASE TRANSITION STARTUP -> STEADY ***
  Entered center window (2) of buffer
  buffer=[0, 1, 2, 3, 4]
  activeWindow=2
  Now entering steady state with 2 windows ahead and 2 behind
```

**Search LogCat**:
```bash
adb logcat | grep "PHASE TRANSITION: STARTUP -> STEADY"
```

If you DON'T see this log, the transition never happened. Check:
- Is `onWindowBecameVisible()` being called?
- Are we ever calling `onEnteredWindow()` with the center window index?
- Does `getCenterWindowIndex()` return the correct value?

---

## 3. Buffer Shifting (Only in STEADY Phase)

### Forward Shift

**Triggered when**:
- Phase == STEADY
- User near end of window (within threshold pages)
- `hasNextWindow()` returns true

**Expected behavior**:
```
buffer BEFORE: [0, 1, 2, 3, 4]
activeWindow: 2
                    ↓ user reaches near-end of window 2
[SHIFT_FORWARD] *** TRIGGERING SHIFT ***
  activeWindow=2
  position=98/100
  phase=STEADY
  buffer_before=[0, 1, 2, 3, 4]
                    ↓ call shiftForward()
buffer AFTER: [1, 2, 3, 4, 5]
              (window 0 dropped, window 5 appended)
```

**Log verification**:
```
[SHIFT_FORWARD] ENTRY: currentPage=98/100
[SHIFT_FORWARD] State: phase=STEADY, activeWindow=2, buffer=[0, 1, 2, 3, 4]
[SHIFT_FORWARD] shouldShift=true (threshold=2), phase=STEADY
[SHIFT_FORWARD] *** TRIGGERING SHIFT ***
[SHIFT_FORWARD] Calling bufferManager.shiftForward()...
[CONVEYOR] *** SHIFT FORWARD ***
  oldBuffer=[0, 1, 2, 3, 4]
  newBuffer=[1, 2, 3, 4, 5]
  droppedWindow=0
  newlyCreated=5
[SHIFT_FORWARD] *** SHIFT SUCCEEDED ***
  buffer_after=[1, 2, 3, 4, 5]
```

**Search LogCat**:
```bash
adb logcat | grep "SHIFT SUCCEEDED"
```

### Backward Shift

**Triggered when**:
- Phase == STEADY
- User near start of window (within threshold pages from beginning)
- `hasPreviousWindow()` returns true

**Expected behavior**:
```
buffer BEFORE: [5, 6, 7, 8, 9]
activeWindow: 7
                    ↓ user reaches start of window 7
[SHIFT_BACKWARD] *** TRIGGERING SHIFT ***
  activeWindow=7
  position=0
  phase=STEADY
  buffer_before=[5, 6, 7, 8, 9]
                    ↓ call shiftBackward()
buffer AFTER: [4, 5, 6, 7, 8]
              (window 9 dropped, window 4 prepended)
```

---

## Test Procedure

### Test 1: Verify Entry Points Are Called

1. **Setup**:
   - Load a book with 20+ windows in continuous mode
   - Open LogCat
   - Create filter: `onWindowBecameVisible ENTRY`

2. **Test - Normal Swipe**:
   - Swipe to next window
   - Check LogCat for: `[WINDOW_VISIBILITY] *** onWindowBecameVisible ENTRY *** windowIndex=X`
   - Should appear immediately after swipe settles

3. **Test - ToC Jump**:
   - Open Table of Contents
   - Jump to a chapter in a different window (e.g., window 10)
   - Check LogCat for: `[WINDOW_VISIBILITY] *** onWindowBecameVisible ENTRY *** windowIndex=10`
   - Should appear immediately after jump

**Expected Result**: Both swipes and ToC jumps trigger `onWindowBecameVisible()`

---

### Test 2: Verify STARTUP -> STEADY Transition

1. **Setup**:
   - Load book, close and reopen to reset
   - Open LogCat
   - Create filter: `PHASE TRANSITION`

2. **Test**:
   - App opens → starts in STARTUP phase
   - Swipe forward twice to reach window 2 (center of [0,1,2,3,4])
   - Check LogCat

**Expected Logs**:
```
Window 0: onWindowBecameVisible(0) → onEnteredWindow(0) → still STARTUP (0 != 2)
Window 1: onWindowBecameVisible(1) → onEnteredWindow(1) → still STARTUP (1 != 2)
Window 2: onWindowBecameVisible(2) → onEnteredWindow(2) → **TRANSITION TRIGGERED** (2 == 2) → phase = STEADY
```

**Log Search**:
```bash
adb logcat | grep "PHASE TRANSITION: STARTUP"
```

**Expected Result**: Exactly ONE `PHASE TRANSITION` log after reaching window 2

---

### Test 3: Verify Buffer Shifting in STEADY Phase

1. **Setup**:
   - Load book in continuous mode
   - Reach STEADY phase (navigate to center window as in Test 2)
   - Open LogCat with filters:
     - `SHIFT_FORWARD` OR
     - `SHIFT_BACKWARD`

2. **Test - Forward Shift**:
   - From window 2, scroll to near end of content (last few pages)
   - Keep scrolling
   - Watch for shift trigger
   - Check LogCat for: `[SHIFT_FORWARD] *** SHIFT SUCCEEDED ***`

3. **Test - Backward Shift**:
   - After forward shift, scroll backward to near start of current window
   - Check LogCat for: `[SHIFT_BACKWARD] *** SHIFT SUCCEEDED ***`

**Expected Result**: Buffer shifts appear in logs with before/after states showing 5-window buffer maintained

---

## Diagnostic Checklist

### If Transition NOT Happening

- [ ] Verify `onWindowBecameVisible()` is being called
  ```bash
  adb logcat | grep "onWindowBecameVisible ENTRY"
  ```

- [ ] Check if `onEnteredWindow()` receives center window index
  ```bash
  adb logcat | grep "globalWindowIndex=2"  # or appropriate center index
  ```

- [ ] Verify `getCenterWindowIndex()` returns correct value
  ```bash
  adb logcat | grep "getCenterWindowIndex"
  ```

- [ ] Check if `hasEnteredSteadyState` is being set
  - Search for: `hasEnteredSteadyState = true`
  - Should appear exactly ONCE

### If Buffer Shifting NOT Happening (After Transition)

- [ ] Verify phase is actually STEADY
  ```bash
  adb logcat | grep "\[SHIFT_FORWARD\].*phase=STEADY"
  ```

- [ ] Check if `maybeShiftForward()` is being called
  ```bash
  adb logcat | grep "\[SHIFT_FORWARD\] ENTRY"
  ```

- [ ] Verify user is reaching threshold (near page end)
  ```bash
  adb logcat | grep "shouldShift=true"
  ```

- [ ] Check if `hasNextWindow()` returns true
  ```bash
  adb logcat | grep "NO next window"  # should NOT appear if shifting
  ```

### Buffer State Analysis

**Check buffer is maintained at 5 windows**:
```bash
adb logcat | grep "newBuffer=\["
```

Expected pattern:
```
[0, 1, 2, 3, 4] → [1, 2, 3, 4, 5] → [2, 3, 4, 5, 6] ...
```

---

## Expected vs Actual Comparison

### Expected Behavior (Correct)

```
📊 Timeline for 20-window book in continuous mode:

Time    Action              Buffer          Phase   Note
─────────────────────────────────────────────────────────
0ms     Load book           [0,1,2,3,4]     STARTUP ← Initial 5 windows
100ms   Swipe to win 0      [0,1,2,3,4]     STARTUP
200ms   Swipe to win 1      [0,1,2,3,4]     STARTUP ← Still at buffer[1]
300ms   Swipe to win 2      [0,1,2,3,4]     STEADY  ← ✨ TRANSITION HAPPENS
400ms   Scroll to end       [0,1,2,3,4]     STEADY
500ms   Shift forward       [1,2,3,4,5]     STEADY  ← Buffer shifts!
600ms   Swipe to win 3      [1,2,3,4,5]     STEADY
700ms   Shift forward       [2,3,4,5,6]     STEADY  ← Shifts again
...
```

### Actual Behavior (If Bug Exists)

```
Time    Action              Buffer          Phase   Issue
─────────────────────────────────────────────────────────
0ms     Load book           [0,1,2,3,4]     STARTUP
100ms   Swipe to win 0      [0,1,2,3,4]     STARTUP
200ms   Swipe to win 1      [0,1,2,3,4]     STARTUP
300ms   Swipe to win 2      [0,1,2,3,4]     STARTUP ← ❌ NO TRANSITION
400ms   Scroll to end       [0,1,2,3,4]     STARTUP ← ❌ Shift blocked
500ms   Swipe to win 3      [0,1,2,3,4]     STARTUP ← ❌ Never shifts
...
```

---

## Log Export for Analysis

To capture full session for offline analysis:

```bash
# Start recording with timestamps
adb logcat -v threadtime > /tmp/reader_session.log &

# Let it record (in background)
# Interact with app for 30-60 seconds, perform tests

# Stop recording (kill the background job)
kill %1

# Analyze with grep
grep "WINDOW_ENTRY\|SHIFT_FORWARD\|SHIFT_BACKWARD\|PHASE TRANSITION" /tmp/reader_session.log
```

---

## Summary: What Should Happen

✅ **Phase 1 (STARTUP)**
- `onWindowBecameVisible()` called for windows 0, 1, 2, ...
- `onEnteredWindow()` logs show buffer=[0,1,2,3,4]
- Phase remains STARTUP

✅ **Phase Transition Point**
- When `onWindowBecameVisible(2)` called (window 2 is center)
- `onEnteredWindow()` detects: `2 == getCenterWindowIndex()`
- Log: `PHASE TRANSITION: STARTUP -> STEADY`
- `phase.value` changes from STARTUP to STEADY

✅ **Phase 2 (STEADY)**
- `maybeShiftForward()` called when near end of window
- `maybeShiftBackward()` called when near start of window
- Buffer shifts: [0,1,2,3,4] → [1,2,3,4,5] → [2,3,4,5,6]
- Windows preloaded in background as they enter buffer

---

**Last Updated**: December 4, 2025
