# Stable Sliding Window Reading Model

## Overview

The Stable Sliding Window Reading Model provides an immutable, predictable reading experience while maintaining efficient memory management through background window preloading. This document describes the architecture, principles, and integration points.

## Core Principles

### 1. **Immutable Active Window**

The active window (the window currently being read) is represented as an immutable `WindowSnapshot`. Once a window becomes active:
- Its content CANNOT be mutated through append/prepend operations
- Its chapter structure remains stable
- Its page count is fixed until a window transition occurs

This immutability prevents:
- Calculation tangles when mapping page positions
- Race conditions between UI updates and content changes
- Unexpected behavior during user interactions

### 2. **Background Window Construction**

New windows (previous and next) are constructed entirely in the background using mutable `WindowState`:
- Loading happens asynchronously without blocking the reading experience
- JavaScript streaming operations (append/prepend) are ONLY used during construction
- Once construction completes, the window is converted to an immutable snapshot

### 3. **Atomic Window Transitions**

Window transitions happen atomically through explicit navigation calls:
- `navigateToNextWindow()`: activeWindow → prevWindow, nextWindow → activeWindow
- `navigateToPrevWindow()`: activeWindow → nextWindow, prevWindow → activeWindow

During transitions:
- Position is remapped to the new active window
- WebView content is reloaded with the new window's HTML
- User experience remains smooth and predictable

### 4. **3-Window Memory Policy**

At most 3 windows are kept in memory:
- **prevWindow**: The window before the active one (for backward navigation)
- **activeWindow**: The window currently being read (immutable)
- **nextWindow**: The window after the active one (for forward navigation)

When navigating:
- The furthest window is dropped (following 3-window policy)
- A new adjacent window is preloaded in its place

### 5. **Progress-Based Preloading**

Adjacent windows are preloaded when the user reaches a configured threshold (default: 75%) within the active window:
- Forward progress ≥ 75% → preload next window
- Backward progress ≤ 25% → preload previous window

This ensures adjacent windows are ready before the user needs them.

## Architecture

### Data Model

```kotlin
// Immutable snapshot (active window during reading)
data class WindowSnapshot(
    val windowIndex: WindowIndex,
    val firstChapterIndex: ChapterIndex,
    val lastChapterIndex: ChapterIndex,
    val chapters: List<WindowChapterData>,
    val totalPages: Int,
    val htmlContent: String?,
    val loadState: WindowLoadState,
    val errorMessage: String?
)

// Mutable state (during background construction)
data class WindowState(
    val windowIndex: WindowIndex,
    val firstChapterIndex: ChapterIndex,
    val lastChapterIndex: ChapterIndex,
    var loadState: WindowLoadState,
    val chapters: MutableList<WindowChapterData>,
    var errorMessage: String?
)

// Chapter data within a window
data class WindowChapterData(
    val chapterIndex: ChapterIndex,
    val title: String,
    val pageCount: Int,
    val startPage: Int,
    val content: PageContent?
)

// Current reading position
data class WindowPosition(
    val windowIndex: WindowIndex,
    val chapterIndex: ChapterIndex,
    val inPageIndex: InPageIndex,
    val progress: Double
)
```

### Window Manager

The `StableWindowManager` class orchestrates window lifecycle:

```kotlin
class StableWindowManager(
    private val bookFile: File,
    private val parser: BookParser,
    private val windowHtmlProvider: WindowHtmlProvider,
    private val config: WindowPreloadConfig
) {
    // State flows for reactive UI updates
    val prevWindow: StateFlow<WindowSnapshot?>
    val activeWindow: StateFlow<WindowSnapshot?>
    val nextWindow: StateFlow<WindowSnapshot?>
    val currentPosition: StateFlow<WindowPosition?>
    
    // Initialization
    suspend fun initialize()
    
    // Load initial window
    suspend fun loadInitialWindow(
        chapterIndex: ChapterIndex,
        inPageIndex: InPageIndex = 0
    ): WindowSnapshot
    
    // Update reading position (triggers preloading)
    suspend fun updatePosition(
        windowIndex: WindowIndex,
        chapterIndex: ChapterIndex,
        inPageIndex: InPageIndex
    )
    
    // Atomic window transitions
    suspend fun navigateToNextWindow(): WindowSnapshot?
    suspend fun navigateToPrevWindow(): WindowSnapshot?
    
    // Boundary detection
    suspend fun isAtWindowBoundary(direction: NavigationDirection): Boolean
}
```

## Integration Points

### 1. ReaderViewModel Integration

The `ReaderViewModel` should integrate `StableWindowManager` for continuous pagination mode:

```kotlin
class ReaderViewModel(/* ... */) : ViewModel() {
    
    private var windowManager: StableWindowManager? = null
    
    // Expose window state to UI
    val activeWindow: StateFlow<WindowSnapshot?>
        get() = windowManager?.activeWindow ?: MutableStateFlow(null)
    
    val currentPosition: StateFlow<WindowPosition?>
        get() = windowManager?.currentPosition ?: MutableStateFlow(null)
    
    private fun initializeContinuousPagination() {
        viewModelScope.launch {
            val manager = StableWindowManager(
                bookFile = bookFile,
                parser = parser,
                windowHtmlProvider = ContinuousPaginatorWindowHtmlProvider(),
                config = WindowPreloadConfig(
                    preloadThreshold = 0.75,
                    maxWindows = 3
                )
            )
            
            manager.initialize()
            windowManager = manager
            
            // Load initial window
            val book = repository.getBookById(bookId)
            val startChapter = book?.currentChapterIndex ?: 0
            val startInPage = book?.currentInPageIndex ?: 0
            
            manager.loadInitialWindow(startChapter, startInPage)
        }
    }
    
    suspend fun navigateToNextPage() {
        val manager = windowManager ?: return
        
        // Check if at window boundary
        if (manager.isAtWindowBoundary(NavigationDirection.NEXT)) {
            // Transition to next window
            manager.navigateToNextWindow()
        } else {
            // Navigate within active window
            // ... WebView in-page navigation
        }
    }
}
```

### 2. ReaderPageFragment Integration

The fragment should render the active window and report position updates:

```kotlin
class ReaderPageFragment : Fragment() {
    
    private fun observeActiveWindow() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.activeWindow.collectLatest { window ->
                if (window?.isReady == true) {
                    // Render window HTML in WebView
                    renderWindowContent(window.htmlContent!!)
                }
            }
        }
    }
    
    private fun onPageChanged(chapterIndex: ChapterIndex, inPageIndex: InPageIndex) {
        viewLifecycleOwner.lifecycleScope.launch {
            val position = viewModel.currentPosition.value
            if (position != null) {
                // Update position (triggers preloading if needed)
                viewModel.updatePosition(
                    position.windowIndex,
                    chapterIndex,
                    inPageIndex
                )
            }
        }
    }
    
    private fun renderWindowContent(html: String) {
        // Load HTML into WebView
        webView.loadDataWithBaseURL(
            "file:///android_asset/",
            html,
            "text/html",
            "UTF-8",
            null
        )
    }
}
```

### 3. JavaScript Integration Discipline

The JavaScript paginator must distinguish between construction and active reading:

**During Window Construction (Background):**
```javascript
// ALLOWED: Append/prepend chapters during construction
function constructWindow(chapters) {
    chapters.forEach(chapter => {
        appendChapter(chapter);  // OK - building in background
    });
}
```

**During Active Reading:**
```javascript
// FORBIDDEN: Do not mutate active window content
function onPageChange(newPage) {
    // WRONG - mutates active window
    // appendChapter(nextChapter);
    
    // CORRECT - just navigate within existing content
    goToPage(newPage);
    
    // CORRECT - notify Android if at boundary
    if (isAtLastPage()) {
        AndroidBridge.onWindowBoundaryReached('next');
    }
}
```

**Enforcing Discipline:**
```javascript
let isConstructing = true;

function finalizeWindow() {
    isConstructing = false;  // Lock down the window
}

function appendChapter(chapter) {
    if (!isConstructing) {
        throw new Error('Cannot append to active window');
    }
    // ... proceed with append
}
```

### 4. Android ↔ JavaScript Handoff Points

**Android → JavaScript:**
1. **Window Load**: `webView.loadDataWithBaseURL()` with complete window HTML
2. **Configuration**: `window.inpagePaginator.configure({ mode: 'WINDOW', ... })`
3. **Navigation**: `window.inpagePaginator.goToPage(pageIndex)`

**JavaScript → Android:**
1. **Page Change**: `AndroidBridge.onPageChanged(chapterIndex, inPageIndex)`
2. **Boundary Reached**: `AndroidBridge.onWindowBoundaryReached(direction)`
3. **Page Count Update**: `AndroidBridge.onPaginationReady(pageCount, chapterBoundaries)`

### 5. Boundary Notifications

When the user reaches a window boundary, JavaScript notifies Android:

```javascript
function onPageChange(newPage) {
    const pageCount = getPageCount();
    
    if (newPage >= pageCount - 1) {
        // At last page of window
        AndroidBridge.onWindowBoundaryReached('next');
    } else if (newPage <= 0) {
        // At first page of window
        AndroidBridge.onWindowBoundaryReached('prev');
    }
    
    // Normal page change notification
    const currentChapter = getCurrentChapter();
    AndroidBridge.onPageChanged(currentChapter.index, newPage);
}
```

Android handles the boundary notification:

```kotlin
@JavascriptInterface
fun onWindowBoundaryReached(direction: String) {
    viewLifecycleOwner.lifecycleScope.launch {
        when (direction) {
            "next" -> viewModel.navigateToNextWindow()
            "prev" -> viewModel.navigateToPrevWindow()
        }
    }
}
```

## Position Mapping Strategy

### Within Active Window

Position is tracked as `(windowIndex, chapterIndex, inPageIndex)`:
- **windowIndex**: Identifies which window is active
- **chapterIndex**: Identifies which chapter within the window
- **inPageIndex**: Identifies which page within the chapter

### During Window Transitions

When transitioning to a new window, position is remapped:

**Forward Transition (active → next):**
- New position: `(nextWindowIndex, firstChapterIndex, 0)`
- User starts at the beginning of the new window

**Backward Transition (active → prev):**
- New position: `(prevWindowIndex, lastChapterIndex, lastPageIndex)`
- User starts at the end of the previous window

### Saving Reading Position

When saving progress to the database:

```kotlin
suspend fun saveReadingPosition() {
    val position = windowManager?.getCurrentPosition() ?: return
    
    repository.updateReadingProgressEnhanced(
        bookId = bookId,
        currentChapterIndex = position.chapterIndex,
        currentInPageIndex = position.inPageIndex,
        currentCharacterOffset = 0  // Optional: precise offset
    )
}
```

## Memory Management

### Window Lifecycle

1. **Construction**: Window is built in background as mutable `WindowState`
2. **Ready**: Window is converted to immutable `WindowSnapshot` and marked ready
3. **Active**: Window becomes the active window through atomic transition
4. **Cached**: Window moves to prev/next position after transition
5. **Dropped**: Window is garbage collected when no longer needed

### Memory Footprint

With default configuration (3 windows, 5 chapters per window):
- **Active Window**: ~15 chapters worth of HTML in memory
- **Typical Book** (50 chapters): ~30% of book in memory
- **Large Book** (200 chapters): ~7.5% of book in memory

### Optimization Opportunities

1. **Lazy Content Loading**: Load chapter content on-demand rather than eagerly
2. **Weak References**: Use weak references for non-active windows
3. **Disk Caching**: Cache rendered HTML to disk for faster reloading
4. **Adaptive Window Size**: Adjust window size based on available memory

## Error Handling

### Window Load Failures

If a window fails to load:
```kotlin
val window = manager.loadInitialWindow(chapterIndex)
if (!window.isReady) {
    // Show error to user
    showError("Failed to load content: ${window.errorMessage}")
    
    // Attempt fallback (e.g., load single chapter)
    fallbackToChapterMode()
}
```

### Boundary Navigation Failures

If navigation fails (e.g., next window not ready):
```kotlin
val nextWindow = manager.navigateToNextWindow()
if (nextWindow == null) {
    // Show message to user
    showMessage("Loading next section...")
    
    // Optionally retry after delay
    delay(1000)
    manager.navigateToNextWindow()
}
```

## Testing Strategy

### Unit Tests

1. **WindowState Tests**:
   - Creation and mutation
   - Conversion to snapshot
   - Chapter addition

2. **WindowSnapshot Tests**:
   - Immutability verification
   - Chapter lookup
   - Boundary checks

3. **StableWindowManager Tests**:
   - Initialization
   - Window loading
   - Position tracking
   - Preloading logic
   - Atomic transitions
   - Boundary detection

### Integration Tests

1. **Full Reading Flow**:
   - Load book → read through multiple windows → verify position
   - Navigate back and forth → verify window caching
   - Close and reopen → verify position restoration

2. **Memory Tests**:
   - Read through large book → verify memory stays bounded
   - Rapid navigation → verify no memory leaks

3. **Error Scenarios**:
   - Corrupt chapter → verify graceful degradation
   - Missing content → verify error handling

## Performance Considerations

### Preloading Timing

The 75% threshold balances:
- **Too early** (e.g., 50%): Wastes memory loading windows user may not reach
- **Too late** (e.g., 90%): Risk of delay when user reaches boundary

Adjust based on:
- Device memory capacity
- Average chapter sizes
- User navigation patterns

### HTML Generation Cost

Window HTML generation can be expensive for large windows:
- Consider caching rendered HTML
- Use incremental rendering for very large windows
- Profile and optimize HTML generation code

### WebView Loading Time

Minimize WebView reload time during transitions:
- Preload next window HTML in hidden WebView
- Swap WebView instances rather than reloading content
- Use WebView caching features

## Future Enhancements

1. **Predictive Preloading**: Analyze user patterns to preload likely destinations
2. **Variable Window Sizes**: Adjust window size based on chapter lengths
3. **Seamless Transitions**: Use animations during window switches
4. **Multi-Window Cache**: Keep more than 3 windows for faster navigation
5. **Incremental Loading**: Stream chapter content within windows

## References

- [PAGINATOR_API.md](../complete/PAGINATOR_API.md) - JavaScript paginator API
- [SLIDING_WINDOW_PAGINATION.md](../historical/SLIDING_WINDOW_PAGINATION.md) - Original design
- [ARCHITECTURE.md](../complete/ARCHITECTURE.md) - Overall system architecture
- [sliding-window-inpage-pagination-notes.md](../../sliding-window-inpage-pagination-notes.md) - Design notes

## Change History

- 2025-11-23: Initial stable window model documentation
