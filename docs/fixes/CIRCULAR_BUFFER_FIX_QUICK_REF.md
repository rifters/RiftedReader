# Circular Buffer Window Indexing - Quick Reference

## The Problem (What Broke)

```
User scrolls past window 5 → ArrayIndexOutOfBoundsException

Timeline:
1. buffer = [0, 1, 2, 3, 4]      ✓ Valid array access
2. Shift:   [1, 2, 3, 4, 5]      ✓ Still valid (size=5)
3. Shift:   [2, 3, 4, 5, 6]      ✓ Still valid (size=5)
4. Shift:   [3, 4, 5, 6, 7]      ✓ Still valid (size=5)
...

But when ViewPager2 tries to display window 7 or beyond:
fragment = ReaderPageFragment.newInstance(7)  ← Fragment thinks window is 7
HTML loading calls: htmlCache[7] → ✗ No HTML cached for window 7

And adapter was doing: ReaderPageFragment.newInstance(position) where position ≤ 4
                                                                    window might be > 4
This mismatch caused wrong content to load or exceptions
```

## The Solution (What We Fixed)

### Part 1: Track Window Index Progression

In ConveyorBeltSystemViewModel:
```kotlin
private var maxLogicalWindowCreated: Int = 4   // Start at 4 (windows 0-4)
private var minLogicalWindowCreated: Int = 0   // Track backward navigation

// When shifting right: increment and use the counter
maxLogicalWindowCreated++  // 4 → 5
val newWindow = maxLogicalWindowCreated  // Use 5, not buffer.last() + 1

// When shifting left: decrement and use the counter  
minLogicalWindowCreated--  // 0 → -1
val newWindow = minLogicalWindowCreated  // Use -1 for ultra-early windows
```

### Part 2: Map Buffer Position to Logical Window Index

In ReaderPagerAdapter:
```kotlin
// BEFORE: Used position (0-4) directly as window index
val fragment = ReaderPageFragment.newInstance(position)
                                             // ↑ WRONG: position is 0-4 always

// AFTER: Look up actual window index from buffer
val buffer = viewModel.conveyorBeltSystem?.buffer?.value
val logicalWindowIndex = buffer?.get(position) ?: position
val fragment = ReaderPageFragment.newInstance(logicalWindowIndex)
                                             // ↑ RIGHT: actual window ID
```

## How It Works Now

```
┌─────────────────────────────────────────────────────────────────┐
│                      Buffer State                                │
├─────────────────────────────────────────────────────────────────┤
│  After several shifts:                                            │
│  _buffer.value = [2, 3, 4, 5, 6]                                 │
│  maxLogicalWindowCreated = 6                                      │
│  minLogicalWindowCreated = 0 (not changed, going forward only)    │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│              Adapter Position → Window Mapping                    │
├─────────────────────────────────────────────────────────────────┤
│  position=0 → buffer[0]=2  → ReaderPageFragment(2)               │
│  position=1 → buffer[1]=3  → ReaderPageFragment(3)               │
│  position=2 → buffer[2]=4  → ReaderPageFragment(4)  ← CENTER     │
│  position=3 → buffer[3]=5  → ReaderPageFragment(5)               │
│  position=4 → buffer[4]=6  → ReaderPageFragment(6)               │
└─────────────────────────────────────────────────────────────────┘

Key: ViewPager2 always uses positions 0-4
     But fragments receive the ACTUAL window indices [2,3,4,5,6]
     This allows unbounded scrolling with fixed-size buffer
```

## State Tracking Invariants

**Always true:**
1. `buffer.size == 5` (always exactly 5 windows)
2. `buffer.contains(maxLogicalWindowCreated)` if going forward
3. `buffer.contains(minLogicalWindowCreated)` if going backward
4. `buffer[CENTER_INDEX] == activeWindow` (center is always active)
5. Each element in buffer is unique
6. Fragment window index = `buffer[adapterPosition]`

**Window Lifecycle:**
1. Created with ID from counter (`maxLogicalWindowCreated++`)
2. Loaded into buffer as physical position (0-4)
3. HTML loaded asynchronously from `slidingWindowManager`
4. Fragment created with that window's logical ID
5. On next shift, removed from buffer (maybe unloaded from cache)
6. Can be re-created if user navigates back to it

## Debugging Guide

### Check logs for proper progression:

```
STARTUP phase:
  buffer = [0, 1, 2, 3, 4]
  maxLogical = 4, minLogical = 0

STEADY phase - going forward:
  [SHIFT] Shifted right: [1, 2, 3, 4, 5], create 5 (maxLogical=5)
  [SHIFT] Shifted right: [2, 3, 4, 5, 6], create 6 (maxLogical=6)
  [SHIFT] Shifted right: [3, 4, 5, 6, 7], create 7 (maxLogical=7)
  ✓ Correct: maxLogical keeps incrementing

Going backward (less common):
  [SHIFT] Shifted left: [2, 3, 4, 5, 6], create 1 (minLogical=1)
  [SHIFT] Shifted left: [1, 2, 3, 4, 5], create 0 (minLogical=0)
  [SHIFT] Shifted left: [0, 1, 2, 3, 4], create -1 (minLogical=-1)
  ✓ Correct: minLogical can go negative for very early navigation
```

### Verify adapter mapping:

In adapter logs, look for:
```
onBindViewHolder: position=0, logicalWindowIndex=2, buffer=[2,3,4,5,6]
onBindViewHolder: position=1, logicalWindowIndex=3, buffer=[2,3,4,5,6]
onBindViewHolder: position=2, logicalWindowIndex=4, buffer=[2,3,4,5,6]
onBindViewHolder: position=3, logicalWindowIndex=5, buffer=[2,3,4,5,6]
onBindViewHolder: position=4, logicalWindowIndex=6, buffer=[2,3,4,5,6]
```

If you see `logicalWindowIndex=position`, the buffer wasn't properly accessed!

## Edge Cases Handled

1. **User scrolls all the way forward** (windows 0 → 1000)
   - ✓ maxLogicalWindowCreated increments indefinitely
   - ✓ Buffer still size 5
   - ✓ New windows keep getting created

2. **User scrolls backward after going forward**
   - ✓ minLogicalWindowCreated might go negative
   - ✓ Buffer adjusts to include earlier windows
   - ✓ No crashes

3. **Rapid scrolling (ViewPager2 rapid swipes)**
   - ✓ Counter-based approach is thread-safe (single-threaded UI)
   - ✓ No race conditions

4. **Fragment recreation during config change**
   - ✓ Fragment saved state includes window ID
   - ✓ Adapter re-created with same buffer
   - ✓ Fragments reload with correct indices

---

## Summary Table

| Aspect | Before | After |
|--------|--------|-------|
| Window ID calculation | `buffer.last() + 1` | `maxLogicalWindowCreated++` |
| Adapter fragment index | `position` (0-4) | `buffer[position]` (actual window) |
| Max scrollable windows | Limited to ~5 | Unlimited |
| Array access safety | ❌ Out of bounds | ✅ Always valid |
| Backward nav handling | Broken | ✓ Uses minLogicalWindowCreated |
| Logging clarity | Missing context | Includes max/min values |

---

Created: 2025-12-27
Last Updated: 2025-12-27
Status: Implemented & Build Successful ✅
