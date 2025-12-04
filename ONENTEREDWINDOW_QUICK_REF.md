# Quick Reference: onEnteredWindow() Call Chain

## TL;DR

When reader enters a new window, the system automatically:
1. Detects the window change (RecyclerView scroll listener)
2. Calls `onWindowBecameVisible()` (ViewModel)
3. Calls `onEnteredWindow()` (BufferManager)
4. Checks if it's the center window (buffer[2])
5. If YES: Transitions STARTUP → STEADY
6. Result: Window shifts can now execute at page edges

---

## The 5-Step Process

### Step 1: Window Visibility (ReaderActivity.kt:376)
```kotlin
if (position >= 0) {
    viewModel.onWindowBecameVisible(position)  // ← TRIGGER
}
```
- Triggered by RecyclerView scroll listener
- Passes global window index to ViewModel

### Step 2: ViewModel Routing (ReaderViewModel.kt:665)
```kotlin
viewModelScope.launch {
    bufferManager.onEnteredWindow(windowIndex)  // ← CALL
}
```
- Launches coroutine
- Routes visibility to buffer manager
- Handles errors gracefully

### Step 3: Buffer Manager Handler (WindowBufferManager.kt:284)
```kotlin
suspend fun onEnteredWindow(globalWindowIndex: WindowIndex) {
    bufferMutex.withLock {
        activeWindowIndex = globalWindowIndex  // ← UPDATE
        updateActiveWindowStateFlow()
        // Check for phase transition...
    }
}
```
- Updates active window index
- Acquires lock for thread safety
- Proceeds to phase transition check

### Step 4: Phase Transition Check (WindowBufferManager.kt:294-301)
```kotlin
if (_phase.value == Phase.STARTUP && !hasEnteredSteadyState) {
    val centerWindowIndex = getCenterWindowIndex()  // ← COMPARE
    if (centerWindowIndex != null && globalWindowIndex == centerWindowIndex) {
        hasEnteredSteadyState = true
        _phase.value = Phase.STEADY  // ← TRANSITION
    }
}
```
- Only transitions from STARTUP to STEADY
- Happens exactly once
- Only if entering center window

### Step 5: Result
```
Before:  phase = STARTUP, shifts blocked
After:   phase = STEADY, shifts enabled
```

---

## Key Constants

| Constant | Value | Meaning |
|----------|-------|---------|
| CENTER_POS | 2 | Center position in 5-window buffer |
| BUFFER_SIZE | 5 | Total windows in buffer |
| Phase.STARTUP | Initial | Before center window reached |
| Phase.STEADY | Active | After center window reached |

---

## Example Timeline

### Book opens at chapter 5

```
Time    Event                           Buffer          Phase       Center
────────────────────────────────────────────────────────────────────────────
0ms     initialize(5)                  [5,6,7,8,9]     STARTUP     7
        User sees chapter 5
        
100ms   User navigates to chapter 7    [5,6,7,8,9]     STARTUP     7
        (position 2 in pager)
        
101ms   onWindowBecameVisible(7)       [5,6,7,8,9]     STARTUP     7
        
102ms   onEnteredWindow(7)
        Check: 7 == getCenterWindowIndex()?
        YES! 7 == 7 ✓
        
103ms   PHASE TRANSITION!              [5,6,7,8,9]     STEADY      7
        Shifts now enabled
        
500ms   User scrolls to last page      [5,6,7,8,9]     STEADY      7
        page 28 of 30
        
501ms   Edge detected: 28 >= 28        [5,6,7,8,9]     STEADY      7
        maybeShiftForward() called
        
502ms   Phase check: STEADY? YES ✓     [5,6,7,8,9]     STEADY      7
        
503ms   SHIFT EXECUTES!                [6,7,8,9,10]    STEADY      8
        Window 5 dropped
        Window 10 added
```

---

## Integration Points

### Incoming Call
- **From**: `ReaderActivity.kt:376` (scroll listener)
- **Function**: `ReaderViewModel.onWindowBecameVisible(position: Int)`
- **Trigger**: RecyclerView scroll settles

### Outgoing Call
- **To**: `WindowBufferManager.onEnteredWindow(globalWindowIndex: WindowIndex)`
- **Effect**: Updates active window, checks phase transition
- **Result**: May transition to STEADY phase

### After Transition
- **Enables**: `maybeShiftForward()` and `maybeShiftBackward()`
- **Unlocks**: Window buffer shifting on page edges
- **Result**: Memory stays constant, seamless reading

---

## Debug Logging

### Expected Messages

When window 7 is entered (center of buffer [5,6,7,8,9]):

```
[PAGINATION_DEBUG] onEnteredWindow: globalWindowIndex=7, 
    currentPhase=STARTUP, buffer=[5, 6, 7, 8, 9]

[CONVEYOR] *** PHASE TRANSITION STARTUP -> STEADY ***
  Entered center window (7) of buffer
  buffer=[5, 6, 7, 8, 9]
  activeWindow=7
  Now entering steady state with 2 windows ahead and 2 behind

[PAGINATION_DEBUG] After onEnteredWindow: phase=STEADY
```

### Logcat Filter
```bash
adb logcat | grep -E "onEnteredWindow|PHASE TRANSITION|activeWindow"
```

---

## Potential Issues & Fixes

| Issue | Cause | Fix |
|-------|-------|-----|
| Phase stays STARTUP | onEnteredWindow not called | Check RecyclerView scroll listener |
| Phase stays STARTUP | getCenterWindowIndex() returns null | Check buffer size > 2 |
| Phase stays STARTUP | globalWindowIndex != centerWindowIndex | Verify position calculation |
| Duplicate transitions | hasEnteredSteadyState flag not set | Check line 299 implementation |
| No debug logs | AppLogger.d() not firing | Check log level settings |

---

## Verification Checklist

- [ ] RecyclerView scroll listener installed (line 288)
- [ ] Window position detected correctly (315-350)
- [ ] onWindowBecameVisible called (line 376)
- [ ] Coroutine launched (ReaderViewModel:662)
- [ ] onEnteredWindow called (ReaderViewModel:665)
- [ ] Phase check: STARTUP? (line 295)
- [ ] Center window detection works (line 297)
- [ ] Phase transition fires (line 302)
- [ ] Debug logs appear (line 303-308)

---

## Related Systems

### Before onEnteredWindow
- Book opens → `initializeWindowBufferManager()` → Buffer created in STARTUP phase
- Window rendered → RecyclerView populated with 5 items

### After onEnteredWindow
- Phase = STEADY → `maybeShiftForward/Backward()` gates removed
- Edge detected → Shift engine can execute
- Window shift → Buffer updated, preloading started
- Memory stable → Old windows dropped, new windows loaded

---

## For Support/Debug

### To trace execution
1. Add breakpoint at `WindowBufferManager.kt:284`
2. Open book
3. Navigate to position 2 (center window)
4. Breakpoint hits with:
   - `globalWindowIndex` = expected window index
   - `buffer` = [expected 5 windows]
   - `_phase.value` = STARTUP (before transition)

### To verify phase change
1. Add breakpoint at `WindowBufferManager.kt:302`
2. Expected to hit exactly once per book load
3. `_phase` changes from STARTUP to STEADY

### To check integration
1. Search logcat for: `onWindowBecameVisible`
2. Should appear after scroll settles at each new position
3. Should trigger `onEnteredWindow` calls

---

## Code References

| File | Lines | Function | Purpose |
|------|-------|----------|---------|
| ReaderActivity.kt | 288 | addOnScrollListener | Install listener |
| ReaderActivity.kt | 315-350 | onScrollStateChanged | Detect position |
| ReaderActivity.kt | 376 | onWindowBecameVisible | **TRIGGER** |
| ReaderViewModel.kt | 653-670 | onWindowBecameVisible | **ROUTE** |
| ReaderViewModel.kt | 665 | onEnteredWindow | **CALL** |
| WindowBufferManager.kt | 284-310 | onEnteredWindow | **HANDLER** |
| WindowBufferManager.kt | 294-310 | Phase transition | **LOGIC** |
| WindowBufferManager.kt | 458-460 | getCenterWindowIndex | **CHECK** |

---

**Status**: ✅ Fully implemented and verified ready for testing
