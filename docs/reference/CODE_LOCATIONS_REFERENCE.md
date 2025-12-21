# Code Locations Reference: From Scroll to Window Shift

## Complete Call Chain with Line Numbers

### 1. Browser Event (inpage_paginator.js)
```
FILE: app/src/main/assets/inpage_paginator.js
LINE: 671
CODE: columnContainer.addEventListener('scroll', function() {
          syncCurrentPageFromScroll();
      });
PURPOSE: Install scroll event listener that fires continuously as user scrolls
```

### 2. Page Detection (inpage_paginator.js)
```
FILE: app/src/main/assets/inpage_paginator.js
LINE: 1212-1245
FUNCTION: syncCurrentPageFromScroll()
```

#### Before (Broken):
```javascript
// Lines 1212-1237
function syncCurrentPageFromScroll() {
    if (!columnContainer || !isInitialized) return;
    if (isProgrammaticScroll) return;
    
    const scrollLeft = columnContainer.scrollLeft;
    const pageWidth = viewportWidth || window.innerWidth;
    if (pageWidth === 0) return;
    
    const newPage = Math.round(scrollLeft / pageWidth);
    if (newPage !== currentPage) {
        console.log('... updating currentPage to ' + newPage);
        currentPage = newPage;
        // ❌ NOTHING HERE - no Android notification!
    }
}
```

#### After (Fixed):
```javascript
// Lines 1212-1245
function syncCurrentPageFromScroll() {
    if (!columnContainer || !isInitialized) return;
    if (isProgrammaticScroll) return;
    
    const scrollLeft = columnContainer.scrollLeft;
    const pageWidth = viewportWidth || window.innerWidth;
    if (pageWidth === 0) return;
    
    const newPage = Math.round(scrollLeft / pageWidth);
    if (newPage !== currentPage) {
        console.log('... updating currentPage to ' + newPage);
        currentPage = newPage;
        
        // ✅ NEW: Notify Android (lines 1240-1245)
        if (window.AndroidBridge && window.AndroidBridge.onPageChanged) {
            console.log('... Calling AndroidBridge.onPageChanged with page=' + newPage);
            window.AndroidBridge.onPageChanged(newPage);  // ← THE FIX
        }
    }
}
```

**KEY CHANGE**: Added lines 1240-1245 to notify Android bridge

### 3. JavaScript Bridge Registration (ReaderPageFragment.kt)
```
FILE: app/src/main/java/com/rifters/riftedreader/ui/reader/ReaderPageFragment.kt
LINE: 158
CODE: webView.addJavascriptInterface(PaginationBridge(), "AndroidBridge")
PURPOSE: Make PaginationBridge methods callable from JavaScript with window.AndroidBridge
```

### 4. Callback Entry Point (ReaderPageFragment.kt)
```
FILE: app/src/main/java/com/rifters/riftedreader/ui/reader/ReaderPageFragment.kt
LINE: 1797
FUNCTION: PaginationBridge.onPageChanged(page: Int)

@JavascriptInterface
fun onPageChanged(page: Int) {
    viewModel.onPageChanged(page)
}
```

This is the @JavascriptInterface method that JavaScript calls via `AndroidBridge.onPageChanged()`

### 5. Fragment Event Handler (ReaderPageFragment.kt)
```
FILE: app/src/main/java/com/rifters/riftedreader/ui/reader/ReaderPageFragment.kt
LINE: 1782-1847
FUNCTION: onPageChanged(newPage: Int)

fun onPageChanged(newPage: Int) {
    val timeSinceTransition = System.currentTimeMillis() - lastWindowTransitionTime
    
    // Cooldown check - prevent rapid shifts
    if (timeSinceTransition < WINDOW_TRANSITION_COOLDOWN_MS) {
        return
    }
    lastPageChangeTime = System.currentTimeMillis()
    
    val totalPagesInWindow = ... // from JavaScript
    
    // Forward edge detection (line 1828)
    if (newPage >= totalPages - 2) {
        readerViewModel.maybeShiftForward(newPage, totalPages)  // ← line 1835
        return
    }
    
    // Backward edge detection (line 1841)
    if (newPage < 2) {
        readerViewModel.maybeShiftBackward(newPage)  // ← line 1843
        return
    }
}
```

**KEY LINES**:
- 1823: Cooldown check
- 1828-1835: Forward edge detection and shift trigger
- 1841-1843: Backward edge detection and shift trigger

### 6a. Forward Shift Routing (ReaderViewModel.kt)
```
FILE: app/src/main/java/com/rifters/riftedreader/domain/reader/ReaderViewModel.kt
LINE: 683-724
FUNCTION: maybeShiftForward(currentInPageIndex: Int, totalPagesInWindow: Int)

fun maybeShiftForward(currentInPageIndex: Int, totalPagesInWindow: Int) {
    // Check if we have a next window
    if (!bufferManager.hasNextWindow()) {
        return
    }
    
    // Check phase gate - only shift in STEADY phase (line 703)
    if (bufferManager.phase.value != BufferPhase.STEADY) {
        return
    }
    
    // Call the shift engine (line 711)
    bufferManager.shiftForward()
}
```

**KEY LINES**:
- 690: hasNextWindow() boundary check
- 703: Phase gate check
- 711: Call shiftForward()

### 6b. Backward Shift Routing (ReaderViewModel.kt)
```
FILE: app/src/main/java/com/rifters/riftedreader/domain/reader/ReaderViewModel.kt
LINE: 733-775
FUNCTION: maybeShiftBackward(currentInPageIndex: Int)

fun maybeShiftBackward(currentInPageIndex: Int) {
    // Check if we have a previous window
    if (!bufferManager.hasPreviousWindow()) {
        return
    }
    
    // Check phase gate - only shift in STEADY phase (line 752)
    if (bufferManager.phase.value != BufferPhase.STEADY) {
        return
    }
    
    // Call the shift engine (line 760)
    bufferManager.shiftBackward()
}
```

**KEY LINES**:
- 742: hasPreviousWindow() boundary check
- 752: Phase gate check
- 760: Call shiftBackward()

### 7a. Forward Shift Engine (WindowBufferManager.kt)
```
FILE: app/src/main/java/com/rifters/riftedreader/domain/pagination/buffer/WindowBufferManager.kt
LINE: 322-366
FUNCTION: shiftForward()

fun shiftForward() {
    logDebug("shiftForward() called")
    
    // Drop oldest window (line 347)
    val removedIndex = buffer.removeFirst()
    logDebug("Removed window index $removedIndex")
    
    // Add new window at end (line 353)
    val nextWindowIndex = getCurrentWindowIndex() + buffer.size
    buffer.addLast(nextWindowIndex)
    logDebug("Added window index $nextWindowIndex")
    
    // Preload the new window (line 365)
    preloadWindow(nextWindowIndex)
    
    // Update tracking (line 365)
    notifyWindowShifted(nextWindowIndex)
}
```

**KEY LINES**:
- 347: Drop window (removeFirst)
- 353: Add window (addLast)
- 365: Preload new window

### 7b. Backward Shift Engine (WindowBufferManager.kt)
```
FILE: app/src/main/java/com/rifters/riftedreader/domain/pagination/buffer/WindowBufferManager.kt
LINE: 372-416
FUNCTION: shiftBackward()

fun shiftBackward() {
    logDebug("shiftBackward() called")
    
    // Drop newest window (line 395)
    val removedIndex = buffer.removeLast()
    logDebug("Removed window index $removedIndex")
    
    // Add new window at beginning (line 401)
    val prevWindowIndex = getCurrentWindowIndex() - 1
    buffer.addFirst(prevWindowIndex)
    logDebug("Added window index $prevWindowIndex")
    
    // Preload the new window (line 413)
    preloadWindow(prevWindowIndex)
    
    // Update tracking (line 413)
    notifyWindowShifted(prevWindowIndex)
}
```

**KEY LINES**:
- 395: Drop window (removeLast)
- 401: Add window (addFirst)
- 413: Preload new window

### 8. Phase Management (WindowBufferManager.kt)
```
FILE: app/src/main/java/com/rifters/riftedreader/domain/pagination/buffer/WindowBufferManager.kt
LINE: 284-310
FUNCTION: onEnteredWindow(globalWindowIndex: Int)

fun onEnteredWindow(globalWindowIndex: Int) {
    if (globalWindowIndex !in 0 until getWindowCount()) {
        return
    }
    
    // Check if we've reached center window (window 2 of 5)
    if (globalWindowIndex == CENTER_POSITION) {
        // Transition from STARTUP to STEADY phase
        phase.value = BufferPhase.STEADY
        logDebug("Phase transitioned to STEADY at center window")
    }
}
```

**KEY LINE**: ~290: Phase transition check and set

### 9. Boundary Checks (WindowBufferManager.kt)
```
FILE: app/src/main/java/com/rifters/riftedreader/domain/pagination/buffer/WindowBufferManager.kt
LINE: 568
FUNCTION: hasNextWindow()

fun hasNextWindow(): Boolean {
    return getCurrentWindowIndex() < getWindowCount() - 1
}
```

```
FILE: app/src/main/java/com/rifters/riftedreader/domain/pagination/buffer/WindowBufferManager.kt
LINE: 577
FUNCTION: hasPreviousWindow()

fun hasPreviousWindow(): Boolean {
    return getCurrentWindowIndex() > 0
}
```

---

## Complete Flow Summary

### Scroll Event → Shift Operation (by line number)

```
inpage_paginator.js:671    ← Browser scroll event listener
    ↓
inpage_paginator.js:1212   ← syncCurrentPageFromScroll() called
    ↓
inpage_paginator.js:1233   ← Calculate newPage
    ↓
inpage_paginator.js:1240   ← ✅ NEW: Call AndroidBridge.onPageChanged(newPage)
    ↓
ReaderPageFragment.kt:1797 ← @JavascriptInterface onPageChanged() receives call
    ↓
ReaderPageFragment.kt:1782 ← Fragment.onPageChanged(page) executes
    ↓
ReaderPageFragment.kt:1823 ← Cooldown check
    ↓
ReaderPageFragment.kt:1828 → Edge detected forward? → line 1835 call maybeShift
    or
ReaderPageFragment.kt:1841 → Edge detected backward? → line 1843 call maybeShift
    ↓
ReaderViewModel.kt:683 or 733 ← maybeShiftForward() or maybeShiftBackward()
    ↓
ReaderViewModel.kt:703 or 752 ← Phase gate check (must be STEADY)
    ↓
ReaderViewModel.kt:711 or 760 ← Call bufferManager.shiftForward/Backward()
    ↓
WindowBufferManager.kt:322 or 372 ← Shift engine executes
    ↓
WindowBufferManager.kt:347,353,365 (forward) or 395,401,413 (backward)
    ↓
✅ Window buffer shifted: old window dropped, new window added, preloaded
```

---

## Testing Checklist with Line References

### Verify JavaScript Fires (line 1240)
- [ ] Build app
- [ ] Open book with multiple chapters
- [ ] Scroll manually
- [ ] Logcat: search for "Calling AndroidBridge.onPageChanged"
- [ ] Should see frequent logs (50-100/sec during scroll)

### Verify Edge Detection (line 1828, 1841)
- [ ] Scroll to page 28 of 30
- [ ] Logcat: search for "Edge detected"
- [ ] Should see: "page 28 >= 28"

### Verify Phase Check (line 703, 752)
- [ ] Check logcat: "Phase is STEADY"
- [ ] Should appear after reaching center window

### Verify Shift Execution (line 322, 372)
- [ ] Logcat: search for "shiftForward\|shiftBackward"
- [ ] Should see: "Removed window index 0, Added window index 5"

### Verify Preload (line 365, 413)
- [ ] Logcat: search for "Preloading window"
- [ ] Should see: "Preloading window 5"

---

## Files Modified

| File | Lines Added | Change |
|------|------------|--------|
| inpage_paginator.js | 5 | Add AndroidBridge.onPageChanged() call at line 1240 |

**Total**: 1 file, 5 lines added

---

## This Completes the System

All components verified to exist:

```
✅ inpage_paginator.js:1240     ← JavaScript trigger (NEW)
✅ ReaderPageFragment.kt:1797   ← Native callback
✅ ReaderPageFragment.kt:1782   ← Edge detection routing
✅ ReaderViewModel.kt:683,733   ← Shift decision logic
✅ WindowBufferManager.kt:322   ← Forward shift execution
✅ WindowBufferManager.kt:372   ← Backward shift execution
```

System is now ready for testing.
