# FlexPaginator Font Size & Re-Slicing Flow

## Overview

When users change font size, line height, or font family, the page layout changes and existing SliceMetadata becomes invalid. This document defines the complete re-slicing workflow to handle these changes gracefully.

## Core Principle

**Character offsets are text-based, not layout-based.**

When layout changes:
- Character offsets remain stable
- Page boundaries shift
- We re-slice all windows in buffer
- Restore user position via character offset
- Display is seamless

## Font Size Change Scenario

### Trigger Events

The following settings changes trigger re-slicing:

1. **Font Size**: 12px → 16px (text becomes larger)
2. **Line Height**: 1.4 → 1.8 (more spacing between lines)
3. **Font Family**: "Serif" → "Sans Serif" (different font metrics)
4. **Text Scaling**: 100% → 125% (Android accessibility setting)

All of these affect page layout and require re-slicing.

### Visual Flow

```
┌───────────────────────────────────────────────────────┐
│ User Reading Window 3, Page 12                        │
│ Current position: Chapter 25, charOffset 1200         │
└───────────────────────────────────────────────────────┘
                        ↓
┌───────────────────────────────────────────────────────┐
│ User Opens Settings                                   │
│ • Lock scroll (disable page flipping)                 │
│ • Lock Conveyor shifts (disable buffer changes)       │
│ • Lock re-slicing (prevent concurrent operations)     │
└───────────────────────────────────────────────────────┘
                        ↓
┌───────────────────────────────────────────────────────┐
│ User Changes Font Size: 14px → 18px                   │
│ • Show loading overlay (book cover + spinner)         │
│ • Display progress: "Adjusting... 0/5"                │
└───────────────────────────────────────────────────────┘
                        ↓
┌───────────────────────────────────────────────────────┐
│ Save Current Position                                 │
│ Bookmark:                                             │
│   • windowIndex = 3                                   │
│   • page = 12                                         │
│   • chapter = 25                                      │
│   • charOffset = 1200                                 │
└───────────────────────────────────────────────────────┘
                        ↓
┌───────────────────────────────────────────────────────┐
│ Clear Old SliceMetadata                               │
│ • Remove metadata from all windows in buffer          │
│ • Keep HTML (no need to reassemble)                   │
│ • Ready for re-slicing                                │
└───────────────────────────────────────────────────────┘
                        ↓
┌───────────────────────────────────────────────────────┐
│ Re-Slice Buffer Windows (5 windows)                   │
│                                                       │
│ For each window in buffer:                            │
│   ├─ Window 1: Re-slice with new font (1/5 = 20%)    │
│   ├─ Window 2: Re-slice with new font (2/5 = 40%)    │
│   ├─ Window 3: Re-slice with new font (3/5 = 60%)    │
│   ├─ Window 4: Re-slice with new font (4/5 = 80%)    │
│   └─ Window 5: Re-slice with new font (5/5 = 100%)   │
│                                                       │
│ Update progress overlay after each window             │
└───────────────────────────────────────────────────────┘
                        ↓
┌───────────────────────────────────────────────────────┐
│ Restore Position                                      │
│ • Find page containing charOffset 1200 in chapter 25  │
│ • Page might be different (was 12, now maybe 15)     │
│ • Navigate to that page                               │
│ • Same text content, different layout                 │
└───────────────────────────────────────────────────────┘
                        ↓
┌───────────────────────────────────────────────────────┐
│ Dismiss Loading Overlay                               │
│ • User sees updated font size                         │
│ • Reading position preserved                          │
│ • Resume reading instantly                            │
└───────────────────────────────────────────────────────┘
                        ↓
┌───────────────────────────────────────────────────────┐
│ User Closes Settings                                  │
│ • Unlock scroll                                       │
│ • Unlock Conveyor shifts                              │
│ • Unlock re-slicing                                   │
│ • Resume normal navigation                            │
└───────────────────────────────────────────────────────┘
```

## Detailed Implementation

### Step 1: Lock Navigation

```kotlin
fun onSettingsOpened() {
    // Prevent page flipping during settings
    isScrollLocked = true
    
    // Prevent Conveyor buffer shifts
    conveyorBeltSystem.lockBufferShifts()
    
    // Prevent concurrent re-slicing
    isReSlicingInProgress = false
    
    // Store current position
    currentBookmark = Bookmark(
        windowIndex = currentWindowIndex,
        page = currentPage,
        chapter = currentChapter,
        charOffset = currentCharOffset
    )
    
    Log.d(TAG, "Settings opened - navigation locked")
}
```

### Step 2: Detect Font Size Change

```kotlin
// In SettingsFragment or ReaderViewModel
fun onFontSizeChanged(newFontSize: Int) {
    if (newFontSize != currentFontSize) {
        // Queue re-slice operation
        queuedResliceOperation = ResliceOperation(
            reason = ResliceReason.FONT_SIZE_CHANGE,
            newFontSize = newFontSize,
            newLineHeight = currentLineHeight,
            newFontFamily = currentFontFamily
        )
        
        Log.d(TAG, "Font size changed: $currentFontSize -> $newFontSize")
    }
}

fun onLineHeightChanged(newLineHeight: Float) {
    if (newLineHeight != currentLineHeight) {
        queuedResliceOperation = ResliceOperation(
            reason = ResliceReason.LINE_HEIGHT_CHANGE,
            newFontSize = currentFontSize,
            newLineHeight = newLineHeight,
            newFontFamily = currentFontFamily
        )
        
        Log.d(TAG, "Line height changed: $currentLineHeight -> $newLineHeight")
    }
}

fun onFontFamilyChanged(newFontFamily: String) {
    if (newFontFamily != currentFontFamily) {
        queuedResliceOperation = ResliceOperation(
            reason = ResliceReason.FONT_FAMILY_CHANGE,
            newFontSize = currentFontSize,
            newLineHeight = currentLineHeight,
            newFontFamily = newFontFamily
        )
        
        Log.d(TAG, "Font family changed: $currentFontFamily -> $newFontFamily")
    }
}
```

### Step 3: Show Loading Overlay

```kotlin
fun showResliceLoadingOverlay() {
    // Show overlay with book cover background
    loadingOverlay.visibility = View.VISIBLE
    
    // Display book cover (semi-transparent)
    bookCoverImage.setImageBitmap(currentBookCover)
    bookCoverImage.alpha = 0.3f
    
    // Start spinner animation
    progressSpinner.visibility = View.VISIBLE
    
    // Show progress text
    progressText.text = "Adjusting... 0/5"
    
    // Optional: Show cancel button
    cancelButton.visibility = View.VISIBLE
    cancelButton.setOnClickListener {
        cancelReslicing()
    }
    
    Log.d(TAG, "Loading overlay displayed")
}
```

### Step 4: Re-Slice Buffer Windows

```kotlin
suspend fun resliceBufferWindows(
    newFontSize: Int,
    newLineHeight: Float,
    newFontFamily: String
) {
    // Lock to prevent concurrent re-slicing
    if (isReSlicingInProgress) {
        Log.w(TAG, "Re-slicing already in progress, skipping")
        return
    }
    
    isReSlicingInProgress = true
    
    try {
        // Get all windows in buffer
        val buffer = windowBuffer.getAllWindows()
        val totalWindows = buffer.size
        
        // Re-slice each window
        buffer.forEachIndexed { index, windowData ->
            Log.d(TAG, "Re-slicing window ${windowData.windowIndex} (${index + 1}/$totalWindows)")
            
            // Update progress overlay
            updateResliceProgress(index + 1, totalWindows)
            
            // Re-slice with new settings
            val newMetadata = offscreenSlicingWebView.sliceWindow(
                html = windowData.html,  // HTML unchanged
                windowIndex = windowData.windowIndex,
                fontSize = newFontSize,
                lineHeight = newLineHeight,
                fontFamily = newFontFamily
            )
            
            // Update WindowData with new metadata
            val updatedWindowData = windowData.copy(
                sliceMetadata = newMetadata
            )
            
            // Cache updated window
            windowBuffer.update(windowData.windowIndex, updatedWindowData)
            
            Log.d(TAG, "Window ${windowData.windowIndex} re-sliced: ${newMetadata.totalPages} pages")
        }
        
        Log.d(TAG, "Re-slicing complete for $totalWindows windows")
        
    } catch (e: Exception) {
        Log.e(TAG, "Re-slicing failed", e)
        // See FLEX_PAGINATOR_ERROR_RECOVERY.md for error handling
        throw e
        
    } finally {
        isReSlicingInProgress = false
    }
}
```

### Step 5: Update Progress

```kotlin
fun updateResliceProgress(current: Int, total: Int) {
    val percent = (current.toFloat() / total * 100).toInt()
    
    progressText.text = "Adjusting... $current/$total ($percent%)"
    
    Log.d(TAG, "Re-slice progress: $current/$total ($percent%)")
}
```

### Step 6: Restore Position

```kotlin
suspend fun restorePositionAfterReslice(bookmark: Bookmark) {
    Log.d(TAG, "Restoring position: chapter=${bookmark.chapter}, charOffset=${bookmark.charOffset}")
    
    // Get updated window metadata
    val windowData = windowBuffer.get(bookmark.windowIndex)
    val metadata = windowData.sliceMetadata
        ?: throw IllegalStateException("No metadata after re-slice")
    
    // Find page containing the saved character offset
    val restoredPage = metadata.findPageByCharOffset(
        chapter = bookmark.chapter,
        offset = bookmark.charOffset
    )
    
    if (restoredPage != null) {
        Log.d(TAG, "Position restored to page ${restoredPage.page} (was ${bookmark.page})")
        
        // Navigate to restored page
        navigateToPage(bookmark.windowIndex, restoredPage.page)
        
    } else {
        Log.w(TAG, "Could not find page for charOffset ${bookmark.charOffset}, using page 0")
        navigateToPage(bookmark.windowIndex, 0)
    }
}
```

### Step 7: Dismiss Overlay

```kotlin
fun dismissResliceLoadingOverlay() {
    // Fade out animation
    loadingOverlay.animate()
        .alpha(0f)
        .setDuration(300)
        .withEndAction {
            loadingOverlay.visibility = View.GONE
            loadingOverlay.alpha = 1f
        }
        .start()
    
    Log.d(TAG, "Loading overlay dismissed")
}
```

### Step 8: Unlock Navigation

```kotlin
fun onSettingsClosed() {
    // Execute queued re-slice if any
    queuedResliceOperation?.let { operation ->
        lifecycleScope.launch {
            executeResliceOperation(operation)
        }
        queuedResliceOperation = null
    }
    
    // Unlock scroll
    isScrollLocked = false
    
    // Unlock Conveyor shifts
    conveyorBeltSystem.unlockBufferShifts()
    
    // Resume normal navigation
    Log.d(TAG, "Settings closed - navigation unlocked")
}
```

## Complete Re-Slice Flow (Code)

```kotlin
class ReaderViewModel : ViewModel() {
    
    private var isReSlicingInProgress = false
    private var queuedResliceOperation: ResliceOperation? = null
    
    suspend fun executeResliceOperation(operation: ResliceOperation) {
        if (isReSlicingInProgress) {
            Log.w(TAG, "Re-slicing already in progress")
            return
        }
        
        try {
            // 1. Show loading overlay
            showResliceLoadingOverlay()
            
            // 2. Save current position
            val bookmark = saveCurrentPosition()
            
            // 3. Re-slice all windows in buffer
            resliceBufferWindows(
                newFontSize = operation.newFontSize,
                newLineHeight = operation.newLineHeight,
                newFontFamily = operation.newFontFamily
            )
            
            // 4. Restore position
            restorePositionAfterReslice(bookmark)
            
            // 5. Dismiss overlay
            dismissResliceLoadingOverlay()
            
            Log.d(TAG, "Re-slice operation complete")
            
        } catch (e: Exception) {
            Log.e(TAG, "Re-slice operation failed", e)
            handleResliceError(e)
        }
    }
}
```

## Theme / Color Changes (No Re-Slice)

Not all settings changes require re-slicing:

### No Re-Slice Needed

- **Theme**: Light → Dark (CSS only)
- **Colors**: Text color, background color (CSS only)
- **Margins**: Page margins (CSS padding)
- **Alignment**: Left/justify/center (CSS text-align)

### Immediate Update

```kotlin
fun onThemeChanged(newTheme: Theme) {
    // Just update CSS, no re-slicing
    readerWebView.evaluateJavascript("""
        document.body.className = '${newTheme.cssClass}';
    """, null)
    
    Log.d(TAG, "Theme changed to ${newTheme.name}, no re-slice needed")
}

fun onTextColorChanged(newColor: Int) {
    // Just update CSS
    readerWebView.evaluateJavascript("""
        document.body.style.color = '${colorToHex(newColor)}';
    """, null)
    
    Log.d(TAG, "Text color changed, no re-slice needed")
}
```

## Edge Cases

### User Changes Font Size Multiple Times

```kotlin
fun onFontSizeChanged(newFontSize: Int) {
    // Only queue the latest change
    queuedResliceOperation = ResliceOperation(
        reason = ResliceReason.FONT_SIZE_CHANGE,
        newFontSize = newFontSize,
        // ... other settings
    )
    
    // Don't execute until settings close
    // This prevents multiple re-slices
}
```

**Result**: Only one re-slice happens with the final font size.

### User Cancels Re-Slice

```kotlin
fun cancelReslicing() {
    // Set cancellation flag
    isResliceCancelled = true
    
    // Keep old metadata
    // Dismiss overlay
    dismissResliceLoadingOverlay()
    
    // Revert font size setting
    revertToOldFontSize()
    
    Log.d(TAG, "Re-slicing cancelled by user")
}
```

### Re-Slice Fails Midway

See **FLEX_PAGINATOR_ERROR_RECOVERY.md** for detailed error handling.

Short version:
- Keep old windows that failed to re-slice
- Show error overlay
- Offer "Try Again" or "Continue Reading"
- User can continue with old layout

## Position Restoration Examples

### Example 1: Font Size Increase

```
Before (14px font):
  Window 3, Page 12
  Chapter 25, charOffset 1200
  
  Page 12: startChar=1150, endChar=1250
  
After (18px font):
  Chapter 25, charOffset 1200
  
  Pages are now shorter (larger font = fewer chars per page)
  Page 15: startChar=1180, endChar=1230  ← Contains offset 1200
  
Result: Navigate to Page 15
```

### Example 2: Line Height Increase

```
Before (1.4 line height):
  Window 3, Page 8
  Chapter 25, charOffset 800
  
  Page 8: startChar=780, endChar=880
  
After (1.8 line height):
  Chapter 25, charOffset 800
  
  Pages are now shorter (more spacing = fewer lines per page)
  Page 10: startChar=790, endChar=840  ← Contains offset 800
  
Result: Navigate to Page 10
```

### Example 3: Font Family Change

```
Before (Serif font):
  Window 3, Page 10
  Chapter 25, charOffset 1000
  
  Page 10: startChar=980, endChar=1080
  
After (Sans Serif font):
  Chapter 25, charOffset 1000
  
  Sans Serif might be slightly wider/narrower
  Page 10: startChar=970, endChar=1060  ← Contains offset 1000
  
Result: Stay on Page 10 (similar layout)
```

## Performance Considerations

### Re-Slice Timing

- **Per-window re-slice**: ~300-500ms
- **5 windows**: ~1.5-2.5 seconds total
- **User sees progress**: Every 500ms (each window)
- **Acceptable UX**: Under 3 seconds is fine (happens rarely)

### Optimization

```kotlin
// Re-slice windows in parallel (if safe)
val deferreds = buffer.map { windowData ->
    async {
        offscreenSlicingWebView.sliceWindow(
            windowData.html,
            windowData.windowIndex,
            newFontSize,
            newLineHeight,
            newFontFamily
        )
    }
}

// Wait for all to complete
val newMetadataList = deferreds.awaitAll()
```

**Caution**: Multiple OffscreenSlicingWebViews use more memory. Test thoroughly.

### Memory Management

- **Peak memory**: 5 windows × 10MB = 50MB (during re-slice)
- **Post re-slice**: Same as before (~25MB for 5 windows)
- **No memory leaks**: Old SliceMetadata garbage collected

## Testing Strategy

### Unit Tests

```kotlin
@Test
fun `reslice after font size change restores position`() = runTest {
    // Given: User reading at specific position
    val bookmark = Bookmark(
        windowIndex = 3,
        page = 12,
        chapter = 25,
        charOffset = 1200
    )
    
    // When: Font size changes
    viewModel.onFontSizeChanged(18)
    viewModel.onSettingsClosed()
    advanceUntilIdle()
    
    // Then: Position restored correctly
    val currentPage = viewModel.currentPage.value
    val metadata = viewModel.getCurrentWindowMetadata()
    val slice = metadata.getSlice(currentPage)
    
    assertTrue(slice.startChar <= 1200 && slice.endChar >= 1200)
}
```

### Integration Tests

```kotlin
@Test
fun `reslice updates all windows in buffer`() = runTest {
    // Given: Buffer with 5 windows
    val buffer = listOf(
        createWindow(1),
        createWindow(2),
        createWindow(3),
        createWindow(4),
        createWindow(5)
    )
    
    // When: Font size changes
    viewModel.onFontSizeChanged(18)
    viewModel.onSettingsClosed()
    advanceUntilIdle()
    
    // Then: All 5 windows have new metadata
    buffer.forEach { windowData ->
        val updated = windowBuffer.get(windowData.windowIndex)
        assertNotNull(updated.sliceMetadata)
        assertTrue(updated.sliceMetadata!!.isValid())
    }
}
```

### Manual Testing

1. Load book in reader
2. Navigate to middle of book
3. Open settings
4. Change font size from 14px to 18px
5. Verify loading overlay appears
6. Verify progress updates (1/5, 2/5, ...)
7. Verify overlay dismisses
8. Verify reading position maintained
9. Verify page layout updated with new font size

## Success Criteria

- ✅ Loading overlay shows book cover + spinner
- ✅ Progress updates every 500ms (1/5, 2/5, ...)
- ✅ Re-slicing completes in <3 seconds
- ✅ Reading position restored correctly
- ✅ No visual glitches or flashing
- ✅ User can continue reading immediately
- ✅ No crashes or errors
- ✅ Memory usage stays under 50MB

## Next Steps

1. Implement settings lock (see FLEX_PAGINATOR_SETTINGS_LOCK.md)
2. Implement error recovery (see FLEX_PAGINATOR_ERROR_RECOVERY.md)
3. Design loading overlay UI (see FLEX_PAGINATOR_PROGRESS_OVERLAY.md)
4. Integration testing with real books
5. Performance optimization (parallel re-slicing?)

---

**Document Version**: 1.0  
**Date**: 2025-12-08  
**Status**: Planning Complete ✅  
**Dependencies**: Phase 6 Integration Complete
