# FlexPaginator Settings Lock & Concurrent Operations

## Overview

When reader settings are open, we need to prevent conflicting operations that could corrupt state or cause visual glitches. This document defines the locking mechanisms and operation queuing strategy.

## Core Principle

**Settings open = Lock everything except settings changes.**

When settings are open:
- ❌ No page flipping (scroll locked)
- ❌ No Conveyor buffer shifts
- ❌ No window creation/destruction
- ❌ No re-slicing operations
- ✅ Settings changes are queued
- ✅ Queued operations execute when settings close

## Lock Types

### 1. Scroll Lock (User Interaction)

**Purpose**: Prevent page flipping while user adjusts settings

```kotlin
class ReaderActivity : AppCompatActivity() {
    
    private var isScrollLocked = false
    
    fun onSettingsOpened() {
        isScrollLocked = true
        
        // Disable touch events on WebView
        readerWebView.isEnabled = false
        
        // Disable gesture detection
        gestureDetector.isEnabled = false
        
        Log.d(TAG, "[SETTINGS_LOCK] Scroll locked")
    }
    
    fun onSettingsClosed() {
        isScrollLocked = false
        
        // Re-enable touch events
        readerWebView.isEnabled = true
        
        // Re-enable gesture detection
        gestureDetector.isEnabled = true
        
        Log.d(TAG, "[SETTINGS_LOCK] Scroll unlocked")
    }
}
```

**Prevents**:
- Accidental page flips during settings adjustment
- Touch events on reader WebView
- Gesture-based navigation (swipe, tap zones)

### 2. Conveyor Lock (Buffer Management)

**Purpose**: Prevent buffer shifts while settings are open

```kotlin
class ConveyorBeltSystemViewModel : ViewModel() {
    
    private var isBufferShiftLocked = false
    
    fun lockBufferShifts() {
        isBufferShiftLocked = true
        Log.d(TAG, "[CONVEYOR_LOCK] Buffer shifts locked")
    }
    
    fun unlockBufferShifts() {
        isBufferShiftLocked = false
        Log.d(TAG, "[CONVEYOR_LOCK] Buffer shifts unlocked")
    }
    
    fun onBoundaryReached(direction: String) {
        if (isBufferShiftLocked) {
            Log.d(TAG, "[CONVEYOR_LOCK] Buffer shift ignored: $direction (locked)")
            return
        }
        
        // Normal buffer shift
        when (direction) {
            "NEXT" -> shiftBufferForward()
            "PREVIOUS" -> shiftBufferBackward()
        }
    }
    
    private suspend fun shiftBufferForward() {
        if (isBufferShiftLocked) {
            Log.w(TAG, "[CONVEYOR_LOCK] Attempted buffer shift while locked")
            return
        }
        
        // Perform shift
        // ...
    }
}
```

**Prevents**:
- Buffer shifts when user reaches window boundary
- Window creation during settings adjustment
- Window eviction during settings adjustment
- Conveyor phase transitions

### 3. Re-Slice Lock (Operation Safety)

**Purpose**: Prevent concurrent re-slicing operations

```kotlin
class ReaderViewModel : ViewModel() {
    
    private val resliceMutex = Mutex()
    private var isReSlicingInProgress = false
    
    suspend fun executeResliceOperation(operation: ResliceOperation) {
        // Check if already in progress
        if (isReSlicingInProgress) {
            Log.w(TAG, "[RESLICE_LOCK] Re-slice already in progress, skipping")
            return
        }
        
        // Try to acquire mutex
        if (!resliceMutex.tryLock()) {
            Log.w(TAG, "[RESLICE_LOCK] Could not acquire mutex, operation queued")
            queuedResliceOperation = operation
            return
        }
        
        try {
            isReSlicingInProgress = true
            Log.d(TAG, "[RESLICE_LOCK] Mutex acquired, starting re-slice")
            
            // Perform re-slice
            performReslice(operation)
            
        } finally {
            isReSlicingInProgress = false
            resliceMutex.unlock()
            Log.d(TAG, "[RESLICE_LOCK] Mutex released")
        }
    }
}
```

**Prevents**:
- Multiple simultaneous re-slice operations
- Re-slicing while previous re-slice is in progress
- Race conditions in SliceMetadata updates

## Operation Queuing

### Settings Change Queue

**Problem**: User changes multiple settings rapidly

```
User opens settings:
  ├─ Font size: 14px → 16px
  ├─ Line height: 1.4 → 1.6
  ├─ Font size: 16px → 18px
  └─ Line height: 1.6 → 1.8
```

**Naive approach**: Re-slice 4 times (wasteful)

**Smart approach**: Queue operations, execute only latest when settings close

```kotlin
data class ResliceOperation(
    val reason: ResliceReason,
    val newFontSize: Int,
    val newLineHeight: Float,
    val newFontFamily: String
)

enum class ResliceReason {
    FONT_SIZE_CHANGE,
    LINE_HEIGHT_CHANGE,
    FONT_FAMILY_CHANGE
}

class ReaderViewModel : ViewModel() {
    
    private var queuedResliceOperation: ResliceOperation? = null
    
    fun onFontSizeChanged(newFontSize: Int) {
        // Update or create queued operation
        queuedResliceOperation = ResliceOperation(
            reason = ResliceReason.FONT_SIZE_CHANGE,
            newFontSize = newFontSize,
            newLineHeight = queuedResliceOperation?.newLineHeight ?: currentLineHeight,
            newFontFamily = queuedResliceOperation?.newFontFamily ?: currentFontFamily
        )
        
        Log.d(TAG, "[QUEUE] Font size queued: $newFontSize")
    }
    
    fun onLineHeightChanged(newLineHeight: Float) {
        // Update or create queued operation
        queuedResliceOperation = ResliceOperation(
            reason = ResliceReason.LINE_HEIGHT_CHANGE,
            newFontSize = queuedResliceOperation?.newFontSize ?: currentFontSize,
            newLineHeight = newLineHeight,
            newFontFamily = queuedResliceOperation?.newFontFamily ?: currentFontFamily
        )
        
        Log.d(TAG, "[QUEUE] Line height queued: $newLineHeight")
    }
    
    fun onFontFamilyChanged(newFontFamily: String) {
        // Update or create queued operation
        queuedResliceOperation = ResliceOperation(
            reason = ResliceReason.FONT_FAMILY_CHANGE,
            newFontSize = queuedResliceOperation?.newFontSize ?: currentFontSize,
            newLineHeight = queuedResliceOperation?.newLineHeight ?: currentLineHeight,
            newFontFamily = newFontFamily
        )
        
        Log.d(TAG, "[QUEUE] Font family queued: $newFontFamily")
    }
}
```

**Result**: Only the final combination is re-sliced

### Execute Queued Operation

```kotlin
fun onSettingsClosed() {
    Log.d(TAG, "[SETTINGS_LOCK] Settings closed")
    
    // Execute queued operation (if any)
    queuedResliceOperation?.let { operation ->
        Log.d(TAG, "[QUEUE] Executing queued operation: ${operation.reason}")
        
        lifecycleScope.launch {
            executeResliceOperation(operation)
        }
        
        queuedResliceOperation = null
    }
    
    // Unlock scroll
    isScrollLocked = false
    
    // Unlock Conveyor
    conveyorBeltSystem.unlockBufferShifts()
    
    // Resume normal navigation
    Log.d(TAG, "[SETTINGS_LOCK] All locks released")
}
```

## Complete Lock Flow

### Settings Opened

```
┌──────────────────────────────────────────┐
│ User Opens Settings                      │
└──────────────────────────────────────────┘
                  ↓
┌──────────────────────────────────────────┐
│ Lock All Operations                      │
│                                          │
│ 1. Scroll Lock                           │
│    • Disable WebView touch events        │
│    • Disable gesture detection           │
│                                          │
│ 2. Conveyor Lock                         │
│    • Prevent buffer shifts               │
│    • Prevent window creation/eviction    │
│                                          │
│ 3. Re-Slice Lock                         │
│    • Mark re-slice as not in progress    │
│    • Clear queued operations             │
│                                          │
│ 4. Save Current Position                 │
│    • Store windowIndex, page, charOffset │
└──────────────────────────────────────────┘
                  ↓
┌──────────────────────────────────────────┐
│ Settings Ready                           │
│ User can adjust font size, etc.          │
└──────────────────────────────────────────┘
```

### Settings Changed

```
┌──────────────────────────────────────────┐
│ User Changes Font Size: 14px → 16px      │
└──────────────────────────────────────────┘
                  ↓
┌──────────────────────────────────────────┐
│ Queue Operation (Don't Execute)          │
│                                          │
│ queuedResliceOperation =                 │
│   ResliceOperation(                      │
│     fontSize = 16,                       │
│     lineHeight = current,                │
│     fontFamily = current                 │
│   )                                      │
│                                          │
│ No re-slice yet!                         │
└──────────────────────────────────────────┘
                  ↓
┌──────────────────────────────────────────┐
│ User Changes Line Height: 1.4 → 1.8      │
└──────────────────────────────────────────┘
                  ↓
┌──────────────────────────────────────────┐
│ Update Queued Operation                  │
│                                          │
│ queuedResliceOperation =                 │
│   ResliceOperation(                      │
│     fontSize = 16,     (from previous)   │
│     lineHeight = 1.8,  (updated)         │
│     fontFamily = current                 │
│   )                                      │
│                                          │
│ Still no re-slice!                       │
└──────────────────────────────────────────┘
                  ↓
┌──────────────────────────────────────────┐
│ User Changes Font Size: 16px → 18px      │
└──────────────────────────────────────────┘
                  ↓
┌──────────────────────────────────────────┐
│ Update Queued Operation Again            │
│                                          │
│ queuedResliceOperation =                 │
│   ResliceOperation(                      │
│     fontSize = 18,     (updated)         │
│     lineHeight = 1.8,  (from previous)   │
│     fontFamily = current                 │
│   )                                      │
│                                          │
│ Only final values are kept               │
└──────────────────────────────────────────┘
```

### Settings Closed

```
┌──────────────────────────────────────────┐
│ User Closes Settings                     │
└──────────────────────────────────────────┘
                  ↓
┌──────────────────────────────────────────┐
│ Check for Queued Operation               │
│                                          │
│ queuedResliceOperation?                  │
│   ├─ YES: Execute re-slice               │
│   └─ NO: Skip re-slice                   │
└──────────────────────────────────────────┘
                  ↓
┌──────────────────────────────────────────┐
│ Execute Queued Re-Slice                  │
│ (Only once, with final settings)         │
│                                          │
│ 1. Show loading overlay                  │
│ 2. Re-slice buffer windows               │
│ 3. Restore reading position              │
│ 4. Dismiss overlay                       │
└──────────────────────────────────────────┘
                  ↓
┌──────────────────────────────────────────┐
│ Unlock All Operations                    │
│                                          │
│ 1. Scroll Unlock                         │
│    • Enable WebView touch events         │
│    • Enable gesture detection            │
│                                          │
│ 2. Conveyor Unlock                       │
│    • Allow buffer shifts                 │
│    • Allow window creation/eviction      │
│                                          │
│ 3. Re-Slice Unlock                       │
│    • Allow future re-slice operations    │
└──────────────────────────────────────────┘
                  ↓
┌──────────────────────────────────────────┐
│ Resume Normal Reading                    │
└──────────────────────────────────────────┘
```

## Edge Cases

### Case 1: User Opens/Closes Settings Without Changes

```kotlin
fun onSettingsClosed() {
    if (queuedResliceOperation == null) {
        // No changes, just unlock
        Log.d(TAG, "[QUEUE] No queued operations, unlocking immediately")
        
        isScrollLocked = false
        conveyorBeltSystem.unlockBufferShifts()
        
        return
    }
    
    // Normal queued operation execution
    // ...
}
```

**Result**: Immediate unlock, no re-slice

### Case 2: User Navigates Away During Settings

```kotlin
override fun onPause() {
    super.onPause()
    
    if (isSettingsOpen) {
        // Auto-close settings
        onSettingsClosed()
    }
}
```

**Result**: Queued operation executes when activity resumes

### Case 3: App Killed During Settings

```kotlin
override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    
    // Save queued operation
    queuedResliceOperation?.let { operation ->
        outState.putParcelable("queued_reslice", operation)
    }
}

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    
    // Restore queued operation
    savedInstanceState?.getParcelable<ResliceOperation>("queued_reslice")?.let { operation ->
        queuedResliceOperation = operation
        
        // Execute on next settings close
        Log.d(TAG, "[QUEUE] Restored queued operation from saved state")
    }
}
```

**Result**: Queued operation survives app restart

### Case 4: Re-Slice Already in Progress

```kotlin
fun onSettingsClosed() {
    if (isReSlicingInProgress) {
        Log.w(TAG, "[SETTINGS_LOCK] Re-slice in progress, delaying unlock")
        
        // Wait for re-slice to complete
        lifecycleScope.launch {
            // Wait for current re-slice
            resliceMutex.withLock {
                // Re-slice finished, now unlock
                unlockAllOperations()
            }
        }
        
        return
    }
    
    // Normal unlock
    unlockAllOperations()
}
```

**Result**: Unlock waits for ongoing re-slice to complete

### Case 5: User Changes Theme (No Re-Slice Needed)

```kotlin
fun onThemeChanged(newTheme: Theme) {
    // No re-slice needed (CSS only)
    // No queued operation
    
    // Apply immediately
    applyThemeToWebView(newTheme)
    
    Log.d(TAG, "[QUEUE] Theme changed (no re-slice needed)")
}
```

**Result**: Instant update, no queued operation, no re-slice

## Implementation Checklist

### Phase 1: Basic Locks

- [ ] Implement `isScrollLocked` in ReaderActivity
- [ ] Disable WebView touch events when locked
- [ ] Disable gesture detection when locked
- [ ] Implement `lockBufferShifts()` in ConveyorBeltSystemViewModel
- [ ] Check lock before all buffer shift operations
- [ ] Implement `resliceMutex` in ReaderViewModel
- [ ] Check mutex before all re-slice operations

### Phase 2: Operation Queuing

- [ ] Create `ResliceOperation` data class
- [ ] Implement `queuedResliceOperation` field
- [ ] Queue font size changes
- [ ] Queue line height changes
- [ ] Queue font family changes
- [ ] Update queued operation (don't replace)
- [ ] Execute queued operation on settings close
- [ ] Clear queued operation after execution

### Phase 3: Settings Lifecycle

- [ ] Hook `onSettingsOpened()` to settings dialog
- [ ] Lock all operations on settings opened
- [ ] Save current reading position
- [ ] Hook `onSettingsClosed()` to settings dialog
- [ ] Execute queued operation on settings closed
- [ ] Unlock all operations after re-slice completes
- [ ] Handle settings dialog dismissal (back button, outside tap)

### Phase 4: Edge Cases

- [ ] Handle settings closed without changes
- [ ] Handle navigation away during settings
- [ ] Handle app killed during settings (save/restore state)
- [ ] Handle re-slice in progress when settings close
- [ ] Handle theme changes (no re-slice)
- [ ] Handle concurrent operation attempts

### Phase 5: Testing

- [ ] Test scroll lock prevents page flipping
- [ ] Test Conveyor lock prevents buffer shifts
- [ ] Test re-slice lock prevents concurrent operations
- [ ] Test operation queuing (multiple changes)
- [ ] Test queued operation execution
- [ ] Test unlock after re-slice
- [ ] Test edge cases (no changes, app killed, etc.)

## Code Example: Complete Integration

```kotlin
class ReaderActivity : AppCompatActivity() {
    
    // Locks
    private var isScrollLocked = false
    private var isSettingsOpen = false
    
    // Settings dialog
    private lateinit var settingsDialog: ReaderSettingsDialog
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Setup settings dialog
        settingsDialog = ReaderSettingsDialog(this)
        settingsDialog.onSettingsOpened = { onSettingsOpened() }
        settingsDialog.onSettingsClosed = { onSettingsClosed() }
        settingsDialog.onFontSizeChanged = { size -> viewModel.onFontSizeChanged(size) }
        settingsDialog.onLineHeightChanged = { height -> viewModel.onLineHeightChanged(height) }
        settingsDialog.onFontFamilyChanged = { family -> viewModel.onFontFamilyChanged(family) }
    }
    
    fun openSettings() {
        settingsDialog.show()
    }
    
    private fun onSettingsOpened() {
        isSettingsOpen = true
        
        // Lock scroll
        isScrollLocked = true
        readerWebView.isEnabled = false
        gestureDetector.isEnabled = false
        
        // Lock Conveyor
        viewModel.conveyorBeltSystem.lockBufferShifts()
        
        // Save position
        viewModel.saveCurrentPosition()
        
        Log.d(TAG, "[SETTINGS_LOCK] All locks acquired")
    }
    
    private fun onSettingsClosed() {
        isSettingsOpen = false
        
        // Execute queued operations
        viewModel.executeQueuedResliceOperation()
        
        // Unlock scroll
        isScrollLocked = false
        readerWebView.isEnabled = true
        gestureDetector.isEnabled = true
        
        // Unlock Conveyor
        viewModel.conveyorBeltSystem.unlockBufferShifts()
        
        Log.d(TAG, "[SETTINGS_LOCK] All locks released")
    }
    
    override fun onBackPressed() {
        if (isSettingsOpen) {
            settingsDialog.dismiss()
            return
        }
        
        super.onBackPressed()
    }
}

class ReaderViewModel : ViewModel() {
    
    private val resliceMutex = Mutex()
    private var isReSlicingInProgress = false
    private var queuedResliceOperation: ResliceOperation? = null
    
    fun onFontSizeChanged(newFontSize: Int) {
        queuedResliceOperation = ResliceOperation(
            newFontSize = newFontSize,
            newLineHeight = queuedResliceOperation?.newLineHeight ?: currentLineHeight,
            newFontFamily = queuedResliceOperation?.newFontFamily ?: currentFontFamily
        )
        
        Log.d(TAG, "[QUEUE] Font size queued: $newFontSize")
    }
    
    fun onLineHeightChanged(newLineHeight: Float) {
        queuedResliceOperation = ResliceOperation(
            newFontSize = queuedResliceOperation?.newFontSize ?: currentFontSize,
            newLineHeight = newLineHeight,
            newFontFamily = queuedResliceOperation?.newFontFamily ?: currentFontFamily
        )
        
        Log.d(TAG, "[QUEUE] Line height queued: $newLineHeight")
    }
    
    fun onFontFamilyChanged(newFontFamily: String) {
        queuedResliceOperation = ResliceOperation(
            newFontSize = queuedResliceOperation?.newFontSize ?: currentFontSize,
            newLineHeight = queuedResliceOperation?.newLineHeight ?: currentLineHeight,
            newFontFamily = newFontFamily
        )
        
        Log.d(TAG, "[QUEUE] Font family queued: $newFontFamily")
    }
    
    fun executeQueuedResliceOperation() {
        queuedResliceOperation?.let { operation ->
            Log.d(TAG, "[QUEUE] Executing queued operation")
            
            viewModelScope.launch {
                executeResliceOperation(operation)
                queuedResliceOperation = null
            }
        } ?: run {
            Log.d(TAG, "[QUEUE] No queued operation to execute")
        }
    }
    
    private suspend fun executeResliceOperation(operation: ResliceOperation) {
        if (!resliceMutex.tryLock()) {
            Log.w(TAG, "[RESLICE_LOCK] Could not acquire mutex")
            return
        }
        
        try {
            isReSlicingInProgress = true
            
            // Show loading overlay
            showResliceLoadingOverlay()
            
            // Re-slice buffer
            resliceBufferWindows(
                operation.newFontSize,
                operation.newLineHeight,
                operation.newFontFamily
            )
            
            // Restore position
            restorePosition()
            
            // Dismiss overlay
            dismissResliceLoadingOverlay()
            
        } finally {
            isReSlicingInProgress = false
            resliceMutex.unlock()
        }
    }
}
```

## Success Criteria

- ✅ Scroll locked when settings open
- ✅ Conveyor buffer shifts blocked when settings open
- ✅ Concurrent re-slicing prevented by mutex
- ✅ Multiple font size changes queue correctly
- ✅ Only final settings are used for re-slice
- ✅ Locks released after re-slice completes
- ✅ Edge cases handled (no changes, app killed, etc.)
- ✅ No race conditions or state corruption

## Next Steps

1. Implement lock mechanisms in ReaderActivity
2. Implement operation queuing in ReaderViewModel
3. Hook settings lifecycle events
4. Test with rapid settings changes
5. Test edge cases
6. Integration testing with real books

---

**Document Version**: 1.0  
**Date**: 2025-12-08  
**Status**: Planning Complete ✅  
**Dependencies**: Phase 6 Integration, Font Size Re-Slicing
