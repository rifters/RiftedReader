# Edge Detection Trigger Trace - STEADY State Window 2

## When User Hits Forward Edge (page 18/20) in STEADY State

### Step 1: JavaScript Detects Page Change
```
inpage_paginator.js (browser)
  └─> onPageChanged(18)  [user swiped to page 18 of window 2]
      └─> Calls native JS bridge method
```

### Step 2: Bridge Receives Event
```
ReaderPageFragment.kt : LINE 1782
    └─> PaginationBridge.onPageChanged(18)
        ├─ Called from JavaScript via @JavascriptInterface
        └─> Executes synchronously on UI thread
```

### Step 3: Cooldown Check
```
ReaderPageFragment.kt : LINE 1810
    ├─ currentWindowIndex = readerViewModel.windowBufferManager?.getActiveWindowIndex()
    ├─ Check: lastKnownWindowIndex != currentWindowIndex?
    │   └─ YES: Window transition happened
    │       └─ windowTransitionTimestamp = System.currentTimeMillis()
    │       └─ Log: "[WINDOW_SHIFT] Window transition detected"
    │
    └─ Continue to edge check
```

### Step 4: Edge Detection - FORWARD EDGE
```
ReaderPageFragment.kt : LINE 1828 ✅ **EDGE DETECTION POINT**
    ├─ Condition: newPage >= totalPages - 2
    │   └─ Check: 18 >= 20 - 2 = 18 >= 18 ✓ TRUE
    │
    ├─ Condition: !inCooldownPeriod
    │   └─ Check: timeSinceTransition >= 300ms ✓ TRUE
    │
    └─ BOTH TRUE → Edge condition triggered ✓
        ├─ Log: "[CONVEYOR] Near window END: page 18/20, cooldown=false"
        └─> LINE 1835: readerViewModel.maybeShiftForward(18, 20)
            ├─ Pass: currentInPageIndex=18
            └─ Pass: totalPagesInWindow=20
```

### Step 5: Decision Logic - STEADY Check
```
ReaderViewModel.kt : LINE 683
    ├─ Function: maybeShiftForward(currentInPageIndex=18, totalPagesInWindow=20)
    │
    ├─ LINE 689-691: Check hasNextWindow()
    │   ├─ activeWindow = 2
    │   ├─ Check: 2 < totalWindows - 1?
    │   └─ YES → hasNextWindow() = true ✓
    │
    ├─ LINE 700-701: Calculate shouldShift
    │   ├─ shouldShift = (20 > 0) AND (18 >= 20 - 2)
    │   ├─ shouldShift = true AND true
    │   └─ shouldShift = true ✓
    │
    └─ LINE 703: **CRITICAL CHECK** ✅
        ├─ Condition: shouldShift AND bufferManager.phase.value == STEADY
        │   └─ true AND STEADY ✓
        │
        ├─ **YES → THIS IS WHERE THE PROCESS STARTS**
        │
        └─> LINE 711: bufferManager.shiftForward()
            └─ Wrapped in: viewModelScope.launch { ... }
                ├─ Executes asynchronously in background
                ├─ Uses Dispatchers.IO for heavy work
                └─ Does NOT block UI thread
```

### Step 6: Shift Execution - ASYNC
```
WindowBufferManager.kt : LINE 322
    ├─ Function: suspend fun shiftForward(): Boolean
    │
    ├─ Executed in: viewModelScope.launch { ... }
    │   └─ Asynchronous execution
    │
    ├─ LINE 347: buffer.removeFirst()
    │   ├─ Remove window 0 from buffer
    │   └─ buffer: [0,1,2,3,4] → [1,2,3,4]
    │
    ├─ LINE 350: windowCache.remove(0)
    │   ├─ Free memory of dropped window
    │   └─ Memory returned to system
    │
    ├─ LINE 353: buffer.addLast(5)
    │   ├─ Add window 5 to buffer
    │   └─ buffer: [1,2,3,4] → [1,2,3,4,5]
    │
    ├─ LINE 365: preloadWindow(5)
    │   └─ Launch async HTML generation for window 5
    │
    └─ Return: true (shift successful)
```

### Step 7: Preload - ASYNC HTML Generation
```
WindowBufferManager.kt : LINE 648
    ├─ Function: private fun preloadWindow(windowIndex: 5)
    │
    ├─ Launch: coroutineScope.launch(Dispatchers.IO)
    │   └─ Executes on background thread (IO pool)
    │
    ├─ Get window range: paginator.getWindowRange(5)
    │   └─ Maps window index 5 to chapters [25..29]
    │
    ├─ Call: windowAssembler.assembleWindow(5, 25, 29)
    │   ├─ Extract chapter text
    │   ├─ Generate HTML
    │   └─ Return WindowData
    │
    ├─ Cache result: windowCache[5] = windowData
    │   └─ Ready for immediate display
    │
    └─ No blocking - UI remains responsive
```

---

## Timeline for User in Window 2 Reading Forward

```
User scrolls                 JS detects page change      Shift triggered
|                            |                           |
+-- page 10 → onPageChanged  (not near edge, no action)
+-- page 12 → onPageChanged  (not near edge, no action)
+-- page 15 → onPageChanged  (not near edge, no action)
+-- page 18 → onPageChanged  ✅ (18 >= 20-2) ✅ (STEADY)
              [CONVEYOR] Near window END: page 18/20
              maybeShiftForward(18, 20) called
              shiftForward() STARTED
              |
              +-- DROP: window 0 (memory freed)
              +-- ADD: window 5
              +-- PRELOAD: window 5 HTML generation (async)
              |
              +-- Return to UI immediately (non-blocking)
              |
              ✓ Buffer now: [1,2,3,4,5] (ready for user scroll)
+-- page 19 → onPageChanged  (no action needed)
+-- page 20 → onPageChanged  (near edge, but already shifted)
+-- User swipes to window 3  (window 5 already preloaded and cached)
```

---

## When Edge Is Hit in STEADY State - Complete Call Stack

```
onPageChanged(18)                          ◄─── JS Bridge call
  └─ activity.runOnUiThread { }            ◄─── Main thread
      └─ lifecycleScope.launch { }         ◄─── Coroutine (Main dispatcher)
          ├─ totalPages = 20
          ├─ newPage = 18
          ├─ cooldown check: 18 >= 20-2 ✓
          ├─ cooldown check: !inCooldown ✓
          └─ readerViewModel.maybeShiftForward(18, 20)
                ├─ hasNextWindow() = true ✓
                ├─ shouldShift = true ✓
                ├─ phase == STEADY ✓        ◄─── **KEY GATE**
                └─ viewModelScope.launch {  ◄─── Background thread
                      └─ bufferManager.shiftForward()
                            ├─ bufferMutex.withLock { }  ◄─── Synchronized
                            ├─ buffer.removeFirst()      ◄─── Drop window 0
                            ├─ windowCache.remove(0)     ◄─── Free memory
                            ├─ buffer.addLast(5)         ◄─── Add window 5
                            ├─ preloadWindow(5)
                            │     └─ coroutineScope.launch(Dispatchers.IO) {
                            │           └─ windowAssembler.assembleWindow(5)
                            │                 └─ Cache result
                            └─ return true
```

---

## Key Points Where Action Happens

### 🟢 **TRIGGER POINT** (Line 1828 in ReaderPageFragment)
```kotlin
if (totalPages > 0 && newPage >= totalPages - 2 && !inCooldownPeriod) {
    // Edge detected HERE
    readerViewModel.maybeShiftForward(newPage, totalPages)
}
```
✅ This is where the detection happens - JavaScript page changes trigger this check

### 🟢 **DECISION POINT** (Line 703 in ReaderViewModel)
```kotlin
if (shouldShift && bufferManager.phase.value == WindowBufferManager.Phase.STEADY) {
    // Decision made HERE
    viewModelScope.launch {
        bufferManager.shiftForward()  // Process started
    }
}
```
✅ This is where STEADY phase gate decides whether to proceed

### 🟢 **EXECUTION POINT** (Line 322 in WindowBufferManager)
```kotlin
suspend fun shiftForward(): Boolean {
    bufferMutex.withLock {
        // Execution happens HERE
        buffer.removeFirst()         // Drop window 0
        buffer.addLast(nextWindow)   // Add window 5
        preloadWindow(nextWindow)    // Load new window
        return true
    }
}
```
✅ This is where the buffer actually moves and new window is created

### 🟢 **PRELOAD POINT** (Line 648 in WindowBufferManager)
```kotlin
private fun preloadWindow(windowIndex: WindowIndex) {
    coroutineScope.launch(Dispatchers.IO) {
        // HTML generation happens HERE (async, background thread)
        val windowData = windowAssembler.assembleWindow(...)
        windowCache[windowIndex] = windowData  // Cache when ready
    }
}
```
✅ This is where new window HTML is generated (non-blocking)

---

## Summary: The Exact Sequence When Hitting Forward Edge

1. **User page reaches 18/20** → JavaScript onPageChanged fires
2. **ReaderPageFragment line 1828** → Edge detected (18 >= 18 ✓)
3. **ReaderPageFragment line 1835** → maybeShiftForward called
4. **ReaderViewModel line 703** → STEADY phase check (GATE)
5. **ReaderViewModel line 711** → shiftForward() scheduled (async)
6. **WindowBufferManager line 347** → Window 0 dropped
7. **WindowBufferManager line 353** → Window 5 added
8. **WindowBufferManager line 365** → preloadWindow(5) scheduled
9. **WindowBufferManager line 648** → HTML generation (background)
10. **WindowBufferManager cache** → Window 5 ready for display

**Total time to shift: ~0-5ms (UI thread), ~100-500ms (HTML generation in background)**

---

## Verification: Is It Actually Starting?

To verify the shift is actually starting in STEADY state:

```bash
# Search logs for STEADY phase gate passing
grep "[CONVEYOR] maybeShiftForward TRIGGERED" session_log.txt

# Should see:
# [CONVEYOR] maybeShiftForward TRIGGERED
#   activeWindow=2
#   position=18/20
#   threshold=2 pages from end
#   phase=STEADY       ◄─── This is the confirmation
#   currentBuffer=[0, 1, 2, 3, 4]

# Then look for actual shift:
grep "[CONVEYOR] \*\*\* SHIFT FORWARD" session_log.txt

# Should see:
# [CONVEYOR] *** SHIFT FORWARD ***
#   oldBuffer=[0, 1, 2, 3, 4]
#   newBuffer=[1, 2, 3, 4, 5]   ◄─── Window 5 created
#   droppedWindow=0 (was in cache: true)
#   newlyCreated=5 (preloading...)
#   activeWindow=2
#   cacheSize=4 (after drop)
```

---

**Answer**: When user hits forward edge at page 18/20 in window 2 (STEADY state):
- **Line 1835 in ReaderPageFragment** calls `maybeShiftForward(18, 20)`
- **Line 703 in ReaderViewModel** checks `phase == STEADY` ✓
- **Line 711 in ReaderViewModel** calls `bufferManager.shiftForward()`
- **Line 347 in WindowBufferManager** executes the shift asynchronously
- **Lines 353-365** complete buffer modification and preload window 5
