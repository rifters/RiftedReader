# FlexPaginator Progress Overlay Design

## Overview

When re-slicing windows due to font size changes, we show a loading overlay to keep the user informed. This document defines the UI design, animation, and progress reporting for the overlay.

## Design Goals

1. **Visually Appealing**: Use book cover as background
2. **Informative**: Show clear progress (X/Y windows)
3. **Non-Intrusive**: Semi-transparent, centered layout
4. **Cancelable**: Optional cancel button (future feature)
5. **Fast**: Dismiss quickly when complete

## Visual Design

### Layout Structure

```
┌─────────────────────────────────────────────────────────┐
│                                                         │
│                                                         │
│              [Book Cover Background]                    │
│              (Semi-transparent overlay)                 │
│                                                         │
│                    ⟳ Spinner                           │
│              (Circular progress indicator)              │
│                                                         │
│              Adjusting layout...                        │
│                   2/5 (40%)                            │
│                                                         │
│                                                         │
│                  [ Cancel ]                            │
│              (Optional, future feature)                 │
│                                                         │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

### Component Breakdown

```xml
<FrameLayout
    android:id="@+id/reslice_overlay"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:visibility="gone"
    android:background="@color/overlay_background">
    
    <!-- Book Cover Background -->
    <ImageView
        android:id="@+id/book_cover_background"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:scaleType="centerCrop"
        android:alpha="0.3" />
    
    <!-- Dark Overlay -->
    <View
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:background="#80000000" />
    
    <!-- Content Container -->
    <LinearLayout
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="center"
        android:orientation="vertical"
        android:gravity="center"
        android:padding="32dp">
        
        <!-- Spinner -->
        <ProgressBar
            android:id="@+id/progress_spinner"
            android:layout_width="64dp"
            android:layout_height="64dp"
            android:indeterminate="true"
            android:indeterminateTint="@color/primary" />
        
        <!-- Progress Text -->
        <TextView
            android:id="@+id/progress_text"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="24dp"
            android:text="Adjusting layout..."
            android:textSize="18sp"
            android:textColor="@android:color/white"
            android:textAlignment="center" />
        
        <!-- Progress Detail -->
        <TextView
            android:id="@+id/progress_detail"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="8dp"
            android:text="0/5 (0%)"
            android:textSize="16sp"
            android:textColor="@color/progress_detail"
            android:textAlignment="center" />
        
        <!-- Cancel Button (Optional) -->
        <Button
            android:id="@+id/cancel_button"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="32dp"
            android:text="Cancel"
            android:visibility="gone"
            style="@style/Widget.Material3.Button.TextButton" />
        
    </LinearLayout>
    
</FrameLayout>
```

## Color Scheme

### Light Theme

```xml
<color name="overlay_background">#F0FFFFFF</color>
<color name="progress_detail">#CCCCCC</color>
<color name="primary">#1976D2</color>
```

### Dark Theme

```xml
<color name="overlay_background">#E0000000</color>
<color name="progress_detail">#AAAAAA</color>
<color name="primary">#64B5F6</color>
```

### Sepia Theme

```xml
<color name="overlay_background">#F0F4ECD8</color>
<color name="progress_detail">#8B7355</color>
<color name="primary">#8B4513</color>
```

### Black OLED Theme

```xml
<color name="overlay_background">#FF000000</color>
<color name="progress_detail">#666666</color>
<color name="primary">#4FC3F7</color>
```

## Animation

### Show Overlay (Fade In)

```kotlin
fun showResliceLoadingOverlay() {
    resliceOverlay.visibility = View.VISIBLE
    resliceOverlay.alpha = 0f
    
    // Fade in animation
    resliceOverlay.animate()
        .alpha(1f)
        .setDuration(200)
        .setInterpolator(DecelerateInterpolator())
        .start()
    
    // Load book cover
    loadBookCoverBackground()
    
    // Start spinner
    progressSpinner.visibility = View.VISIBLE
    
    // Reset progress
    updateProgress(0, 5)
    
    Log.d(TAG, "[OVERLAY] Loading overlay shown")
}
```

### Dismiss Overlay (Fade Out)

```kotlin
fun dismissResliceLoadingOverlay() {
    // Fade out animation
    resliceOverlay.animate()
        .alpha(0f)
        .setDuration(300)
        .setInterpolator(AccelerateInterpolator())
        .withEndAction {
            resliceOverlay.visibility = View.GONE
            resliceOverlay.alpha = 1f
        }
        .start()
    
    Log.d(TAG, "[OVERLAY] Loading overlay dismissed")
}
```

### Spinner Animation

```kotlin
// Default Material spinner animation
// No custom animation needed - use indeterminate ProgressBar
```

## Progress Reporting

### Update Progress

```kotlin
fun updateProgress(current: Int, total: Int) {
    val percent = (current.toFloat() / total * 100).toInt()
    
    // Update progress text
    progressText.text = "Adjusting layout..."
    progressDetail.text = "$current/$total ($percent%)"
    
    Log.d(TAG, "[OVERLAY] Progress: $current/$total ($percent%)")
}
```

### Progress Stages

```
Stage 1: Starting re-slice
  └─ "Adjusting layout... 0/5 (0%)"

Stage 2: Completed Window 1
  └─ "Adjusting layout... 1/5 (20%)"

Stage 3: Completed Window 2
  └─ "Adjusting layout... 2/5 (40%)"

Stage 4: Completed Window 3
  └─ "Adjusting layout... 3/5 (60%)"

Stage 5: Completed Window 4
  └─ "Adjusting layout... 4/5 (80%)"

Stage 6: Completed Window 5, Restoring Position
  └─ "Adjusting layout... 5/5 (100%)"

Stage 7: Dismiss overlay
  └─ Fade out
```

## Book Cover Background

### Load Book Cover

```kotlin
fun loadBookCoverBackground() {
    // Get book cover from BookRepository
    val bookCover = bookRepository.getBookCover(currentBookId)
    
    if (bookCover != null) {
        // Load cover into ImageView
        bookCoverBackground.setImageBitmap(bookCover)
        bookCoverBackground.alpha = 0.3f // Semi-transparent
        
        // Apply blur (optional)
        applyBlur(bookCoverBackground)
        
    } else {
        // Fallback: Use default gradient
        bookCoverBackground.setImageResource(R.drawable.default_gradient)
        bookCoverBackground.alpha = 0.2f
    }
}
```

### Blur Effect (Optional)

```kotlin
fun applyBlur(imageView: ImageView) {
    // Use RenderScript for blur effect
    val bitmap = (imageView.drawable as BitmapDrawable).bitmap
    val blurred = BlurHelper.blur(context, bitmap, 25f)
    imageView.setImageBitmap(blurred)
}
```

## Cancel Button (Future Feature)

### Show Cancel Button

```kotlin
fun showCancelButton() {
    cancelButton.visibility = View.VISIBLE
    cancelButton.setOnClickListener {
        onCancelReslice()
    }
}
```

### Handle Cancel

```kotlin
fun onCancelReslice() {
    Log.d(TAG, "[OVERLAY] Cancel button clicked")
    
    // Set cancellation flag
    isResliceCancelled = true
    
    // Show cancelling message
    progressText.text = "Cancelling..."
    progressDetail.text = ""
    
    // Wait for current window to finish
    // (Don't abort mid-window to avoid corrupted state)
    
    // Revert settings
    revertToOldFontSize()
    
    // Dismiss overlay
    dismissResliceLoadingOverlay()
    
    Toast.makeText(context, "Cancelled - reverted to previous font size", Toast.LENGTH_SHORT).show()
}
```

## Error State

### Show Error

```kotlin
fun showResliceError(errorMessage: String) {
    // Hide spinner
    progressSpinner.visibility = View.GONE
    
    // Show error icon
    val errorIcon = ImageView(context).apply {
        setImageResource(R.drawable.ic_error)
        layoutParams = LinearLayout.LayoutParams(64.dp, 64.dp)
    }
    contentContainer.addView(errorIcon, 0)
    
    // Update text
    progressText.text = "Oops!"
    progressDetail.text = errorMessage
    
    // Show action buttons
    showErrorButtons()
}

fun showErrorButtons() {
    // Try Again button
    val tryAgainButton = Button(context).apply {
        text = "Try Again"
        setOnClickListener {
            retryReslice()
        }
    }
    
    // Continue Reading button
    val continueButton = Button(context).apply {
        text = "Continue Reading"
        setOnClickListener {
            dismissResliceLoadingOverlay()
        }
    }
    
    contentContainer.addView(tryAgainButton)
    contentContainer.addView(continueButton)
}
```

## Complete Implementation

```kotlin
class ResliceLoadingOverlay(private val context: Context) {
    
    private lateinit var overlayView: View
    private lateinit var bookCoverBackground: ImageView
    private lateinit var progressSpinner: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var progressDetail: TextView
    private lateinit var cancelButton: Button
    
    fun createOverlay(parent: ViewGroup): View {
        // Inflate layout
        overlayView = LayoutInflater.from(context)
            .inflate(R.layout.reslice_loading_overlay, parent, false)
        
        // Get references
        bookCoverBackground = overlayView.findViewById(R.id.book_cover_background)
        progressSpinner = overlayView.findViewById(R.id.progress_spinner)
        progressText = overlayView.findViewById(R.id.progress_text)
        progressDetail = overlayView.findViewById(R.id.progress_detail)
        cancelButton = overlayView.findViewById(R.id.cancel_button)
        
        // Initially hidden
        overlayView.visibility = View.GONE
        
        return overlayView
    }
    
    fun show(bookCover: Bitmap?) {
        // Load book cover
        if (bookCover != null) {
            bookCoverBackground.setImageBitmap(bookCover)
            bookCoverBackground.alpha = 0.3f
        } else {
            bookCoverBackground.setImageResource(R.drawable.default_gradient)
            bookCoverBackground.alpha = 0.2f
        }
        
        // Reset progress
        progressSpinner.visibility = View.VISIBLE
        progressText.text = "Adjusting layout..."
        progressDetail.text = "0/5 (0%)"
        cancelButton.visibility = View.GONE
        
        // Fade in
        overlayView.visibility = View.VISIBLE
        overlayView.alpha = 0f
        overlayView.animate()
            .alpha(1f)
            .setDuration(200)
            .setInterpolator(DecelerateInterpolator())
            .start()
        
        Log.d(TAG, "[OVERLAY] Shown")
    }
    
    fun updateProgress(current: Int, total: Int) {
        val percent = (current.toFloat() / total * 100).toInt()
        progressDetail.text = "$current/$total ($percent%)"
        
        Log.d(TAG, "[OVERLAY] Progress: $current/$total ($percent%)")
    }
    
    fun dismiss() {
        overlayView.animate()
            .alpha(0f)
            .setDuration(300)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                overlayView.visibility = View.GONE
                overlayView.alpha = 1f
            }
            .start()
        
        Log.d(TAG, "[OVERLAY] Dismissed")
    }
    
    fun showError(message: String, onTryAgain: () -> Unit, onContinue: () -> Unit) {
        // Hide spinner
        progressSpinner.visibility = View.GONE
        
        // Update text
        progressText.text = "Oops!"
        progressDetail.text = message
        
        // Show buttons
        val buttonContainer = overlayView.findViewById<LinearLayout>(R.id.button_container)
        buttonContainer.visibility = View.VISIBLE
        
        buttonContainer.findViewById<Button>(R.id.try_again_button).setOnClickListener {
            onTryAgain()
        }
        
        buttonContainer.findViewById<Button>(R.id.continue_button).setOnClickListener {
            onContinue()
        }
    }
}
```

## Usage Example

```kotlin
class ReaderViewModel : ViewModel() {
    
    private lateinit var resliceOverlay: ResliceLoadingOverlay
    
    fun setupOverlay(parent: ViewGroup) {
        resliceOverlay = ResliceLoadingOverlay(context)
        val overlayView = resliceOverlay.createOverlay(parent)
        parent.addView(overlayView)
    }
    
    suspend fun executeResliceOperation(operation: ResliceOperation) {
        try {
            // 1. Show overlay
            val bookCover = bookRepository.getBookCover(currentBookId)
            resliceOverlay.show(bookCover)
            
            // 2. Re-slice windows
            val buffer = windowBuffer.getAllWindows()
            buffer.forEachIndexed { index, windowData ->
                // Re-slice window
                val metadata = offscreenSlicingWebView.sliceWindow(
                    windowData.html, 
                    windowData.windowIndex,
                    operation.newFontSize,
                    operation.newLineHeight,
                    operation.newFontFamily
                )
                
                // Update progress
                resliceOverlay.updateProgress(index + 1, buffer.size)
                
                // Cache metadata
                windowBuffer.update(windowData.windowIndex, 
                    windowData.copy(sliceMetadata = metadata))
            }
            
            // 3. Restore position
            restorePosition()
            
            // 4. Dismiss overlay
            resliceOverlay.dismiss()
            
        } catch (e: Exception) {
            // Show error
            resliceOverlay.showError(
                message = "Couldn't adjust text layout",
                onTryAgain = { retryReslice(operation) },
                onContinue = { resliceOverlay.dismiss() }
            )
        }
    }
}
```

## Accessibility

### Screen Reader Support

```xml
<ProgressBar
    android:id="@+id/progress_spinner"
    android:contentDescription="Adjusting text layout"
    ... />

<TextView
    android:id="@+id/progress_text"
    android:importantForAccessibility="yes"
    ... />

<TextView
    android:id="@+id/progress_detail"
    android:importantForAccessibility="yes"
    ... />
```

### Announce Progress Updates

```kotlin
fun updateProgress(current: Int, total: Int) {
    val percent = (current.toFloat() / total * 100).toInt()
    progressDetail.text = "$current/$total ($percent%)"
    
    // Announce to screen reader
    progressDetail.announceForAccessibility(
        "Progress: $current of $total windows completed, $percent percent"
    )
}
```

## Performance Considerations

### Lazy Cover Loading

```kotlin
fun loadBookCoverBackground() {
    // Load asynchronously to avoid blocking UI
    lifecycleScope.launch(Dispatchers.IO) {
        val cover = bookRepository.getBookCover(currentBookId)
        
        withContext(Dispatchers.Main) {
            if (cover != null) {
                bookCoverBackground.setImageBitmap(cover)
            }
        }
    }
}
```

### Memory Management

```kotlin
override fun onDestroyView() {
    super.onDestroyView()
    
    // Release bitmap memory
    val drawable = bookCoverBackground.drawable as? BitmapDrawable
    drawable?.bitmap?.recycle()
}
```

## Testing

### Manual Testing Checklist

- [ ] Overlay shows with book cover
- [ ] Overlay shows without book cover (fallback)
- [ ] Progress updates correctly (0/5 → 5/5)
- [ ] Spinner animates smoothly
- [ ] Overlay dismisses smoothly
- [ ] Error state displays correctly
- [ ] Cancel button works (if enabled)
- [ ] Screen reader announces progress
- [ ] Works in all themes (Light, Dark, Sepia, Black)

### Automated Testing

```kotlin
@Test
fun `overlay shows and dismisses correctly`() = runTest {
    // Given: Overlay is hidden
    assertEquals(View.GONE, overlay.visibility)
    
    // When: Show overlay
    overlay.show(mockBookCover)
    
    // Then: Overlay is visible
    assertEquals(View.VISIBLE, overlay.visibility)
    
    // When: Dismiss overlay
    overlay.dismiss()
    advanceTimeBy(400) // Wait for animation
    
    // Then: Overlay is hidden
    assertEquals(View.GONE, overlay.visibility)
}

@Test
fun `progress updates correctly`() = runTest {
    overlay.show(mockBookCover)
    
    // Update progress
    overlay.updateProgress(2, 5)
    
    // Verify text
    assertEquals("2/5 (40%)", progressDetail.text)
}
```

## Success Criteria

- ✅ Book cover displays as background (semi-transparent)
- ✅ Spinner animates smoothly
- ✅ Progress text updates (X/Y, percent%)
- ✅ Overlay fades in/out smoothly
- ✅ Cancel button works (optional feature)
- ✅ Error state displays clearly
- ✅ Screen reader announces progress
- ✅ Works in all themes
- ✅ No visual glitches or flashing

## Next Steps

1. Create XML layout (reslice_loading_overlay.xml)
2. Implement ResliceLoadingOverlay class
3. Integrate with ReaderViewModel
4. Test with real books and covers
5. Add blur effect (optional polish)
6. Implement cancel functionality (optional)

---

**Document Version**: 1.0  
**Date**: 2025-12-08  
**Status**: Planning Complete ✅  
**Dependencies**: Font Size Re-Slicing, Settings Lock
