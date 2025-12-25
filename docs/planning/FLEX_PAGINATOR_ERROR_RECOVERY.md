# FlexPaginator Error Recovery & Fallback Strategy

## Overview

Re-slicing operations can fail for various reasons. This document defines comprehensive error recovery strategies, rollback mechanisms, and fallback paths to ensure the reader remains functional even when things go wrong.

## Core Principle

**Never destroy working state until new state is verified.**

- Keep old SliceMetadata until new metadata succeeds
- Rollback to previous state on failure
- Allow user to continue reading with old layout
- Offer retry options
- Graceful degradation, never crash

## Error Scenarios

### 1. Single Window Re-Slice Failure

**Scenario**: One window fails during buffer re-slice

```
Re-slicing buffer (5 windows):
  ├─ Window 1: SUCCESS ✅ (new metadata cached)
  ├─ Window 2: SUCCESS ✅ (new metadata cached)
  ├─ Window 3: FAILURE ❌ (timeout or WebView error)
  ├─ Window 4: SKIPPED (continue with remaining)
  └─ Window 5: SUCCESS ✅ (new metadata cached)

Result:
  • Windows 1, 2, 5: New layout (18px font)
  • Window 3: Old layout (14px font) - fallback
  • User can continue reading
```

### 2. Complete Re-Slice Failure

**Scenario**: All windows fail during re-slice

```
Re-slicing buffer (5 windows):
  ├─ Window 1: FAILURE ❌
  ├─ Window 2: FAILURE ❌
  ├─ Window 3: FAILURE ❌
  ├─ Window 4: FAILURE ❌
  └─ Window 5: FAILURE ❌

Result:
  • All windows keep old layout
  • Show error overlay
  • Offer "Try Again" or "Revert Font Size"
  • User can continue reading with old font
```

### 3. WebView Initialization Failure

**Scenario**: OffscreenSlicingWebView fails to create

```
Error: Failed to create OffscreenSlicingWebView
  • Memory pressure
  • WebView system crash
  • Android version incompatibility

Fallback:
  • Use minimal_paginator.js (simpler slicing)
  • Or disable FlexPaginator entirely
  • Fall back to old pagination system
```

### 4. JavaScript Execution Error

**Scenario**: flex_paginator.js throws exception

```
Error: JavaScript exception in flex_paginator.js
  • Invalid HTML content
  • Malformed chapter data
  • JavaScript engine error

Fallback:
  • Catch exception in AndroidBridge
  • Log error details
  • Return partial metadata (if any pages succeeded)
  • Or use default single-page layout
```

### 5. Metadata Validation Failure

**Scenario**: SliceMetadata.isValid() returns false

```
Error: Invalid metadata received
  • Empty slices array
  • Negative character offsets
  • Invalid page indices
  • Inconsistent chapter references

Fallback:
  • Reject invalid metadata
  • Keep old metadata
  • Retry re-slice
  • Or use minimal_paginator fallback
```

## Detailed Error Handling

### Re-Slice with Rollback

```kotlin
suspend fun resliceWindowWithRollback(
    windowData: WindowData,
    newFontSize: Int,
    newLineHeight: Float,
    newFontFamily: String
): Result<SliceMetadata> {
    
    // Keep old metadata for rollback
    val oldMetadata = windowData.sliceMetadata
    
    Log.d(TAG, "Re-slicing window ${windowData.windowIndex} (old metadata preserved)")
    
    try {
        // Attempt re-slice
        val newMetadata = offscreenSlicingWebView.sliceWindow(
            html = windowData.html,
            windowIndex = windowData.windowIndex,
            fontSize = newFontSize,
            lineHeight = newLineHeight,
            fontFamily = newFontFamily
        )
        
        // Validate new metadata
        if (!newMetadata.isValid()) {
            Log.e(TAG, "Invalid metadata for window ${windowData.windowIndex}")
            return Result.failure(InvalidMetadataException("Metadata validation failed"))
        }
        
        // Success - cache new metadata
        Log.d(TAG, "Window ${windowData.windowIndex} re-sliced successfully")
        return Result.success(newMetadata)
        
    } catch (e: TimeoutException) {
        Log.e(TAG, "Timeout re-slicing window ${windowData.windowIndex}", e)
        return Result.failure(e)
        
    } catch (e: WebViewException) {
        Log.e(TAG, "WebView error re-slicing window ${windowData.windowIndex}", e)
        return Result.failure(e)
        
    } catch (e: Exception) {
        Log.e(TAG, "Unknown error re-slicing window ${windowData.windowIndex}", e)
        return Result.failure(e)
    }
    
    // Note: oldMetadata is NOT destroyed
    // If re-slice fails, we still have it for fallback
}
```

### Buffer Re-Slice with Partial Success

```kotlin
data class ResliceResult(
    val successfulWindows: List<Int>,
    val failedWindows: List<Int>,
    val errors: Map<Int, Exception>
)

suspend fun resliceBufferWithPartialSuccess(
    newFontSize: Int,
    newLineHeight: Float,
    newFontFamily: String
): ResliceResult {
    
    val buffer = windowBuffer.getAllWindows()
    val successfulWindows = mutableListOf<Int>()
    val failedWindows = mutableListOf<Int>()
    val errors = mutableMapOf<Int, Exception>()
    
    buffer.forEachIndexed { index, windowData ->
        Log.d(TAG, "Re-slicing window ${windowData.windowIndex} (${index + 1}/${buffer.size})")
        
        // Update progress
        updateResliceProgress(index + 1, buffer.size)
        
        // Attempt re-slice with rollback
        val result = resliceWindowWithRollback(
            windowData = windowData,
            newFontSize = newFontSize,
            newLineHeight = newLineHeight,
            newFontFamily = newFontFamily
        )
        
        if (result.isSuccess) {
            // Success - update window with new metadata
            val newMetadata = result.getOrThrow()
            val updatedWindowData = windowData.copy(
                sliceMetadata = newMetadata
            )
            windowBuffer.update(windowData.windowIndex, updatedWindowData)
            successfulWindows.add(windowData.windowIndex)
            
            Log.d(TAG, "Window ${windowData.windowIndex} updated with new metadata")
            
        } else {
            // Failure - keep old metadata, continue with next window
            failedWindows.add(windowData.windowIndex)
            errors[windowData.windowIndex] = result.exceptionOrNull()!!
            
            Log.w(TAG, "Window ${windowData.windowIndex} re-slice failed, kept old metadata")
            // Don't return early - continue with remaining windows
        }
    }
    
    Log.d(TAG, "Re-slice complete: ${successfulWindows.size} success, ${failedWindows.size} failed")
    
    return ResliceResult(
        successfulWindows = successfulWindows,
        failedWindows = failedWindows,
        errors = errors
    )
}
```

### Error Overlay Display

```kotlin
fun showResliceErrorOverlay(result: ResliceResult) {
    // Dismiss loading spinner
    progressSpinner.visibility = View.GONE
    
    // Show error icon
    errorIcon.visibility = View.VISIBLE
    
    // Error message
    val message = when {
        result.failedWindows.isEmpty() -> {
            // All succeeded (shouldn't show error overlay)
            return
        }
        result.successfulWindows.isEmpty() -> {
            // All failed
            "Oops! Couldn't adjust text layout.\nYou can continue reading with the current layout."
        }
        else -> {
            // Partial failure
            "Adjusted ${result.successfulWindows.size} of ${result.successfulWindows.size + result.failedWindows.size} sections.\n" +
            "You can continue reading or try again."
        }
    }
    
    errorText.text = message
    errorText.visibility = View.VISIBLE
    
    // Action buttons
    tryAgainButton.visibility = View.VISIBLE
    tryAgainButton.setOnClickListener {
        retryFailedWindows(result.failedWindows)
    }
    
    continueReadingButton.visibility = View.VISIBLE
    continueReadingButton.setOnClickListener {
        dismissErrorOverlay()
        // User continues with mixed layout (some windows new, some old)
    }
    
    revertFontSizeButton.visibility = View.VISIBLE
    revertFontSizeButton.setOnClickListener {
        revertToOldFontSize()
        dismissErrorOverlay()
    }
    
    Log.d(TAG, "Error overlay displayed: ${result.failedWindows.size} failures")
}
```

### Retry Failed Windows

```kotlin
suspend fun retryFailedWindows(failedWindowIndices: List<Int>) {
    Log.d(TAG, "Retrying ${failedWindowIndices.size} failed windows")
    
    // Show loading spinner again
    showResliceLoadingOverlay()
    updateResliceProgress(0, failedWindowIndices.size)
    
    val retriedSuccesses = mutableListOf<Int>()
    val retriedFailures = mutableListOf<Int>()
    
    failedWindowIndices.forEachIndexed { index, windowIndex ->
        val windowData = windowBuffer.get(windowIndex)
        
        // Retry re-slice
        val result = resliceWindowWithRollback(
            windowData = windowData,
            newFontSize = currentFontSize,
            newLineHeight = currentLineHeight,
            newFontFamily = currentFontFamily
        )
        
        if (result.isSuccess) {
            val newMetadata = result.getOrThrow()
            val updatedWindowData = windowData.copy(sliceMetadata = newMetadata)
            windowBuffer.update(windowIndex, updatedWindowData)
            retriedSuccesses.add(windowIndex)
            
            Log.d(TAG, "Retry succeeded for window $windowIndex")
        } else {
            retriedFailures.add(windowIndex)
            Log.w(TAG, "Retry failed for window $windowIndex")
        }
        
        updateResliceProgress(index + 1, failedWindowIndices.size)
    }
    
    // Dismiss overlay
    dismissResliceLoadingOverlay()
    
    if (retriedFailures.isEmpty()) {
        // All retries succeeded
        Log.d(TAG, "All retries succeeded")
        Toast.makeText(context, "Text layout adjusted successfully!", Toast.LENGTH_SHORT).show()
    } else {
        // Some still failed
        Log.w(TAG, "${retriedFailures.size} windows still failed after retry")
        showResliceErrorOverlay(ResliceResult(
            successfulWindows = retriedSuccesses,
            failedWindows = retriedFailures,
            errors = emptyMap()
        ))
    }
}
```

### Revert Font Size

```kotlin
fun revertToOldFontSize() {
    Log.d(TAG, "Reverting font size from $currentFontSize to $previousFontSize")
    
    // Revert setting
    readerSettings.fontSize = previousFontSize
    readerSettings.lineHeight = previousLineHeight
    readerSettings.fontFamily = previousFontFamily
    
    // Update UI
    fontSizeSlider.value = previousFontSize.toFloat()
    
    // No re-slice needed - windows still have old metadata
    
    Toast.makeText(context, "Reverted to previous font size", Toast.LENGTH_SHORT).show()
}
```

## Concurrent Operation Safety

### Mutex for Re-Slicing

```kotlin
class ReaderViewModel : ViewModel() {
    
    // Mutex ensures only one re-slice at a time
    private val resliceMutex = Mutex()
    
    suspend fun executeResliceOperation(operation: ResliceOperation) {
        // Try to acquire lock
        if (!resliceMutex.tryLock()) {
            Log.w(TAG, "Re-slicing already in progress, queuing operation")
            queuedResliceOperation = operation
            return
        }
        
        try {
            Log.d(TAG, "Re-slice mutex acquired, starting operation")
            
            // Execute re-slice
            performReslice(operation)
            
        } finally {
            resliceMutex.unlock()
            Log.d(TAG, "Re-slice mutex released")
        }
    }
}
```

### Prevent Conveyor Shifts During Re-Slice

```kotlin
class ConveyorBeltSystemViewModel {
    
    private var isBufferShiftLocked = false
    
    fun lockBufferShifts() {
        isBufferShiftLocked = true
        Log.d(TAG, "Conveyor buffer shifts locked")
    }
    
    fun unlockBufferShifts() {
        isBufferShiftLocked = false
        Log.d(TAG, "Conveyor buffer shifts unlocked")
    }
    
    fun onBoundaryReached(direction: String) {
        if (isBufferShiftLocked) {
            Log.d(TAG, "Buffer shift ignored (locked during re-slice)")
            return
        }
        
        // Normal buffer shift
        when (direction) {
            "NEXT" -> shiftBufferForward()
            "PREVIOUS" -> shiftBufferBackward()
        }
    }
}
```

### Queue Multiple Font Size Changes

```kotlin
class ReaderViewModel : ViewModel() {
    
    private var queuedResliceOperation: ResliceOperation? = null
    
    fun onFontSizeChanged(newFontSize: Int) {
        // Only queue the LATEST change
        queuedResliceOperation = ResliceOperation(
            reason = ResliceReason.FONT_SIZE_CHANGE,
            newFontSize = newFontSize,
            newLineHeight = currentLineHeight,
            newFontFamily = currentFontFamily
        )
        
        Log.d(TAG, "Queued font size change: $newFontSize (replacing previous queued op)")
    }
    
    fun onLineHeightChanged(newLineHeight: Float) {
        // Replace queued operation with latest settings
        queuedResliceOperation = ResliceOperation(
            reason = ResliceReason.LINE_HEIGHT_CHANGE,
            newFontSize = queuedResliceOperation?.newFontSize ?: currentFontSize,
            newLineHeight = newLineHeight,
            newFontFamily = queuedResliceOperation?.newFontFamily ?: currentFontFamily
        )
        
        Log.d(TAG, "Queued line height change: $newLineHeight (updated queued op)")
    }
    
    fun onSettingsClosed() {
        queuedResliceOperation?.let { operation ->
            lifecycleScope.launch {
                executeResliceOperation(operation)
            }
            queuedResliceOperation = null
        }
    }
}
```

**Result**: If user changes font size 3 times, only the final value is used for re-slicing.

## Fallback Strategies

### Strategy 1: Partial Success

```
User changes font size:
  ├─ Re-slice Window 1: SUCCESS ✅
  ├─ Re-slice Window 2: SUCCESS ✅
  ├─ Re-slice Window 3: FAILURE ❌
  ├─ Re-slice Window 4: SUCCESS ✅
  └─ Re-slice Window 5: SUCCESS ✅

Result:
  • 4 windows: New layout (18px font)
  • 1 window: Old layout (14px font)
  • User can continue reading
  • Show message: "Adjusted 4 of 5 sections"
  • Offer "Try Again" for Window 3
```

**UX Impact**: Minimal - user only notices when reaching Window 3

### Strategy 2: Complete Failure with Rollback

```
User changes font size:
  ├─ Re-slice Window 1: FAILURE ❌
  ├─ Re-slice Window 2: FAILURE ❌
  ├─ Re-slice Window 3: FAILURE ❌
  ├─ Re-slice Window 4: FAILURE ❌
  └─ Re-slice Window 5: FAILURE ❌

Result:
  • All windows: Old layout (14px font)
  • Show error: "Couldn't adjust text layout"
  • Offer "Try Again" or "Revert Font Size"
  • User continues with old font
```

**UX Impact**: Moderate - font size didn't change, but user can retry

### Strategy 3: Disable FlexPaginator

```
WebView initialization fails:
  └─ OffscreenSlicingWebView cannot be created

Fallback:
  ├─ Disable FlexPaginator feature
  ├─ Fall back to minimal_paginator.js
  └─ Show toast: "Using simplified pagination"

Result:
  • User continues reading with simpler system
  • No re-slicing on font size changes
  • Bookmarks less precise
  • Feature gracefully degrades
```

**UX Impact**: High - loss of FlexPaginator features, but reader still functional

### Strategy 4: Emergency Fallback to Old System

```
FlexPaginator completely broken:
  └─ Cannot slice any windows

Fallback:
  ├─ Switch to ContinuousPaginator (old system)
  ├─ Or switch to ChapterPaginator (chapter-by-chapter)
  └─ Show message: "Switched to alternative pagination"

Result:
  • User continues reading with old pagination system
  • Different UX but functional
  • Can be re-enabled after app restart
```

**UX Impact**: Very high - different pagination experience, but no data loss

## Error Logging & Diagnostics

### Comprehensive Error Logging

```kotlin
fun logResliceError(
    windowIndex: Int,
    error: Exception,
    context: String
) {
    Log.e(TAG, """
        ═══════════════════════════════════════════════════
        RE-SLICE ERROR
        ═══════════════════════════════════════════════════
        Context: $context
        Window: $windowIndex
        Error Type: ${error.javaClass.simpleName}
        Error Message: ${error.message}
        Stack Trace:
        ${error.stackTraceToString()}
        ═══════════════════════════════════════════════════
        Device Info:
        - Android Version: ${Build.VERSION.SDK_INT}
        - Device: ${Build.MANUFACTURER} ${Build.MODEL}
        - Available Memory: ${getAvailableMemoryMB()} MB
        - WebView Version: ${getWebViewVersion()}
        ═══════════════════════════════════════════════════
    """.trimIndent())
    
    // Send to crash reporting service (Firebase Crashlytics, Sentry, etc.)
    crashlytics.recordException(error)
}
```

### User-Friendly Error Messages

```kotlin
fun getErrorMessage(error: Exception): String {
    return when (error) {
        is TimeoutException ->
            "Oops! This is taking longer than expected.\nTry closing some apps to free up memory."
        
        is WebViewException ->
            "Something went wrong with the text layout.\nYour reading position is safe."
        
        is InvalidMetadataException ->
            "Couldn't calculate page layout.\nYou can continue reading with the current layout."
        
        is OutOfMemoryError ->
            "Low memory! Close some apps and try again."
        
        else ->
            "Something unexpected happened.\nYour book and reading position are safe."
    }
}
```

## Testing Error Scenarios

### Unit Tests

```kotlin
@Test
fun `partial reslice failure keeps old metadata for failed windows`() = runTest {
    // Given: 5 windows in buffer
    val windows = (1..5).map { createWindow(it) }
    
    // Mock: Window 3 fails
    coEvery { 
        offscreenSlicingWebView.sliceWindow(any(), eq(3), any(), any(), any()) 
    } throws TimeoutException("Simulated timeout")
    
    // When: Re-slice operation
    val result = viewModel.resliceBufferWithPartialSuccess(18, 1.5f, "Serif")
    
    // Then: Window 3 keeps old metadata
    assertEquals(4, result.successfulWindows.size)
    assertEquals(1, result.failedWindows.size)
    assertEquals(3, result.failedWindows[0])
    
    val window3 = windowBuffer.get(3)
    assertEquals(oldMetadata, window3.sliceMetadata) // Old metadata preserved
}
```

```kotlin
@Test
fun `complete reslice failure shows error overlay`() = runTest {
    // Given: All windows fail
    coEvery { 
        offscreenSlicingWebView.sliceWindow(any(), any(), any(), any(), any()) 
    } throws WebViewException("Simulated WebView crash")
    
    // When: Re-slice operation
    viewModel.onFontSizeChanged(18)
    viewModel.onSettingsClosed()
    advanceUntilIdle()
    
    // Then: Error overlay shown
    assertTrue(viewModel.isErrorOverlayVisible.value)
    assertEquals("Oops! Couldn't adjust text layout.", viewModel.errorMessage.value)
}
```

### Integration Tests

```kotlin
@Test
fun `retry after failure succeeds`() = runTest {
    // Given: First attempt fails
    var attemptCount = 0
    coEvery { 
        offscreenSlicingWebView.sliceWindow(any(), eq(2), any(), any(), any()) 
    } answers {
        attemptCount++
        if (attemptCount == 1) {
            throw TimeoutException("First attempt timeout")
        } else {
            createValidSliceMetadata()
        }
    }
    
    // When: Re-slice fails, then retry
    val result1 = viewModel.resliceBufferWithPartialSuccess(18, 1.5f, "Serif")
    assertEquals(1, result1.failedWindows.size)
    
    viewModel.retryFailedWindows(result1.failedWindows)
    advanceUntilIdle()
    
    // Then: Retry succeeds
    val window2 = windowBuffer.get(2)
    assertNotNull(window2.sliceMetadata)
    assertTrue(window2.sliceMetadata!!.isValid())
}
```

### Manual Testing

1. **Simulate Timeout**: Set timeout to 100ms, trigger re-slice
2. **Simulate WebView Crash**: Mock WebView to throw exception
3. **Simulate Low Memory**: Trigger OutOfMemoryError
4. **Simulate Invalid HTML**: Use malformed chapter content
5. **Verify Fallback**: Ensure user can continue reading after errors
6. **Verify Retry**: Test "Try Again" button functionality
7. **Verify Revert**: Test "Revert Font Size" functionality

## Success Criteria

- ✅ Partial failures don't crash the app
- ✅ User can always continue reading (even with old layout)
- ✅ Error messages are clear and actionable
- ✅ "Try Again" functionality works correctly
- ✅ "Revert Font Size" restores previous state
- ✅ Concurrent operations are prevented (mutex)
- ✅ Conveyor shifts are blocked during re-slice
- ✅ Old metadata is preserved until new metadata succeeds
- ✅ Error logging provides diagnostic information
- ✅ No data loss or bookmark corruption

## Next Steps

1. Implement settings lock (see FLEX_PAGINATOR_SETTINGS_LOCK.md)
2. Design error overlay UI (see FLEX_PAGINATOR_PROGRESS_OVERLAY.md)
3. Add comprehensive error logging
4. Test error scenarios with real books
5. Monitor crash reports in production

---

**Document Version**: 1.0  
**Date**: 2025-12-08  
**Status**: Planning Complete ✅  
**Dependencies**: Phase 6 Integration, Font Size Re-Slicing
