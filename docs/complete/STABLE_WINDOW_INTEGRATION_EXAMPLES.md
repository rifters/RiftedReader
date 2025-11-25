# Stable Window Manager - Integration Examples

This document provides concrete code examples for integrating `StableWindowManager` into RiftedReader.

## Example 1: ReaderViewModel Integration

```kotlin
class ReaderViewModel(
    val bookId: String,
    private val bookFile: File,
    private val parser: BookParser,
    private val repository: BookRepository,
    private val readerPreferences: ReaderPreferences
) : ViewModel() {
    
    // Stable window manager (for continuous pagination mode)
    private var windowManager: StableWindowManager? = null
    
    // Expose window states to UI
    val activeWindow: StateFlow<WindowSnapshot?> get() = 
        windowManager?.activeWindow ?: MutableStateFlow(null)
    
    val currentPosition: StateFlow<WindowPosition?> get() = 
        windowManager?.currentPosition ?: MutableStateFlow(null)
    
    init {
        val mode = readerPreferences.settings.value.paginationMode
        if (mode == PaginationMode.CONTINUOUS) {
            initializeStableWindows()
        } else {
            buildPagination() // Existing chapter-based
        }
    }
    
    private fun initializeStableWindows() {
        viewModelScope.launch {
            try {
                AppLogger.d(TAG, "Initializing StableWindowManager")
                
                // Create window HTML provider
                val htmlProvider = ContinuousPaginatorWindowHtmlProvider()
                
                // Create manager with configuration
                val manager = StableWindowManager(
                    bookFile = bookFile,
                    parser = parser,
                    windowHtmlProvider = htmlProvider,
                    config = WindowPreloadConfig(
                        preloadThreshold = 0.75,  // Preload at 75%
                        maxWindows = 3            // Keep 3 windows max
                    )
                )
                
                // Initialize manager
                manager.initialize()
                windowManager = manager
                
                // Load initial window based on saved position
                val book = repository.getBookById(bookId)
                val startChapter = book?.currentChapterIndex ?: 0
                val startInPage = book?.currentInPageIndex ?: 0
                
                val activeSnapshot = manager.loadInitialWindow(startChapter, startInPage)
                
                AppLogger.d(TAG, "Initial window loaded: ${activeSnapshot.chapters.size} chapters")
                
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to initialize StableWindowManager", e)
                // Fall back to chapter-based mode
            }
        }
    }
    
    /**
     * Navigate to the next page (or next window if at boundary).
     */
    suspend fun navigateNext() {
        val manager = windowManager ?: return
        
        if (manager.isAtWindowBoundary(NavigationDirection.NEXT)) {
            // At window boundary - transition to next window
            val nextWindow = manager.navigateToNextWindow()
            if (nextWindow != null) {
                AppLogger.d(TAG, "Transitioned to next window ${nextWindow.windowIndex}")
            } else {
                // At end of book
                AppLogger.d(TAG, "At end of book")
            }
        } else {
            // Within window - let WebView handle page navigation
            // The fragment will call updatePosition after navigation
        }
    }
    
    /**
     * Navigate to the previous page (or previous window if at boundary).
     */
    suspend fun navigatePrev() {
        val manager = windowManager ?: return
        
        if (manager.isAtWindowBoundary(NavigationDirection.PREV)) {
            // At window boundary - transition to prev window
            val prevWindow = manager.navigateToPrevWindow()
            if (prevWindow != null) {
                AppLogger.d(TAG, "Transitioned to prev window ${prevWindow.windowIndex}")
            } else {
                // At beginning of book
                AppLogger.d(TAG, "At beginning of book")
            }
        } else {
            // Within window - let WebView handle page navigation
        }
    }
    
    /**
     * Update reading position (called from fragment after page change).
     * This triggers preloading if needed.
     */
    suspend fun updatePosition(
        windowIndex: WindowIndex,
        chapterIndex: ChapterIndex,
        inPageIndex: InPageIndex
    ) {
        windowManager?.updatePosition(windowIndex, chapterIndex, inPageIndex)
    }
    
    /**
     * Save reading position to database.
     */
    suspend fun saveReadingPosition() {
        val position = windowManager?.getCurrentPosition() ?: return
        
        repository.updateReadingProgressEnhanced(
            bookId = bookId,
            currentChapterIndex = position.chapterIndex,
            currentInPageIndex = position.inPageIndex,
            currentCharacterOffset = 0  // TODO: implement character offset
        )
        
        AppLogger.d(TAG, "Saved position: chapter=${position.chapterIndex}, page=${position.inPageIndex}")
    }
    
    companion object {
        private const val TAG = "ReaderViewModel"
    }
}
```

## Example 2: ReaderPageFragment Integration

```kotlin
class ReaderPageFragment : Fragment() {
    
    private val viewModel: ReaderViewModel by activityViewModels()
    private lateinit var webView: WebView
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupWebView()
        observeActiveWindow()
        observePosition()
    }
    
    private fun setupWebView() {
        webView = view.findViewById(R.id.webView)
        
        // Set up WebView settings
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
        }
        
        // Add JavaScript bridge
        webView.addJavascriptInterface(
            WindowPaginatorBridge(),
            "AndroidBridge"
        )
    }
    
    /**
     * Observe active window and render when ready.
     */
    private fun observeActiveWindow() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.activeWindow.collectLatest { window ->
                if (window?.isReady == true) {
                    renderWindow(window)
                }
            }
        }
    }
    
    /**
     * Observe position changes for UI updates.
     */
    private fun observePosition() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.currentPosition.collectLatest { position ->
                position?.let {
                    updateProgressIndicator(it)
                }
            }
        }
    }
    
    /**
     * Render window content in WebView.
     */
    private fun renderWindow(window: WindowSnapshot) {
        AppLogger.d(TAG, "Rendering window ${window.windowIndex}")
        
        // Load HTML into WebView
        webView.loadDataWithBaseURL(
            "file:///android_asset/",
            window.htmlContent!!,
            "text/html",
            "UTF-8",
            null
        )
        
        // Wait for WebView to load, then configure paginator
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                configureAndFinalizeWindow(window)
            }
        }
    }
    
    /**
     * Configure paginator and finalize window (enter active mode).
     */
    private fun configureAndFinalizeWindow(window: WindowSnapshot) {
        // Step 1: Configure paginator
        webView.evaluateJavascript("""
            window.inpagePaginator.configure({
                mode: 'WINDOW',
                windowIndex: ${window.windowIndex}
            });
        """, null)
        
        // Step 2: Finalize window (lock down - no more mutations)
        webView.evaluateJavascript("""
            window.inpagePaginator.finalizeWindow();
        """, null)
        
        // Step 3: Navigate to initial page (if needed)
        val position = viewModel.currentPosition.value
        if (position != null) {
            val chapter = window.getChapter(position.chapterIndex)
            if (chapter != null) {
                val pageInWindow = chapter.startPage + position.inPageIndex
                webView.evaluateJavascript("""
                    window.inpagePaginator.goToPage($pageInWindow);
                """, null)
            }
        }
    }
    
    /**
     * Update progress indicator based on position.
     */
    private fun updateProgressIndicator(position: WindowPosition) {
        val progressPercent = (position.progress * 100).toInt()
        AppLogger.d(TAG, "Position: chapter=${position.chapterIndex}, " +
                        "page=${position.inPageIndex}, progress=$progressPercent%")
        
        // Update UI elements
        // binding.progressText.text = "$progressPercent%"
        // binding.chapterText.text = "Chapter ${position.chapterIndex + 1}"
    }
    
    /**
     * JavaScript bridge for window-based pagination.
     */
    inner class WindowPaginatorBridge {
        
        @JavascriptInterface
        fun onWindowFinalized(pageCount: Int) {
            AppLogger.d(TAG, "Window finalized: $pageCount pages")
        }
        
        @JavascriptInterface
        fun onPageChanged(chapterIndex: Int, inPageIndex: Int) {
            viewLifecycleOwner.lifecycleScope.launch {
                val position = viewModel.currentPosition.value
                if (position != null) {
                    // Update position (this may trigger preloading)
                    viewModel.updatePosition(
                        position.windowIndex,
                        chapterIndex,
                        inPageIndex
                    )
                }
            }
        }
        
        @JavascriptInterface
        fun onWindowBoundaryReached(direction: String) {
            AppLogger.d(TAG, "Window boundary reached: $direction")
            
            viewLifecycleOwner.lifecycleScope.launch {
                when (direction) {
                    "next" -> viewModel.navigateNext()
                    "prev" -> viewModel.navigatePrev()
                }
            }
        }
    }
    
    override fun onPause() {
        super.onPause()
        // Save position when fragment pauses
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.saveReadingPosition()
        }
    }
    
    companion object {
        private const val TAG = "ReaderPageFragment"
    }
}
```

## Example 3: JavaScript Usage in WebView

```javascript
// After WebView loads the window HTML:

// Step 1: Configure paginator (still in CONSTRUCTION mode)
window.inpagePaginator.configure({
    mode: 'WINDOW',
    windowIndex: 5  // Example window index
});

// At this point, mutations are still allowed
// (This happens during background window construction)

// Step 2: Finalize window (switch to ACTIVE mode)
window.inpagePaginator.finalizeWindow();
// After this: NO MORE MUTATIONS ALLOWED

// Step 3: Navigation is always allowed
window.inpagePaginator.goToPage(10);  // ✅ OK
window.inpagePaginator.nextPage();    // ✅ OK

// Step 4: Report page changes to Android
function onPageChange(newPage) {
    const currentChapter = window.inpagePaginator.getCurrentChapter();
    
    // Notify Android
    if (AndroidBridge && AndroidBridge.onPageChanged) {
        AndroidBridge.onPageChanged(currentChapter, newPage);
    }
    
    // Check for window boundaries
    const pageCount = window.inpagePaginator.getPageCount();
    if (newPage >= pageCount - 1) {
        // At last page of window
        if (AndroidBridge && AndroidBridge.onWindowBoundaryReached) {
            AndroidBridge.onWindowBoundaryReached('next');
        }
    } else if (newPage <= 0) {
        // At first page of window
        if (AndroidBridge && AndroidBridge.onWindowBoundaryReached) {
            AndroidBridge.onWindowBoundaryReached('prev');
        }
    }
}

// FORBIDDEN: These will throw errors after finalizeWindow()
// window.inpagePaginator.appendChapter(html, index);  // ❌ ERROR
// window.inpagePaginator.prependChapter(html, index); // ❌ ERROR
```

## Example 4: Custom Window Configuration

```kotlin
// Custom configuration for different scenarios

// Scenario 1: Large memory device - keep 5 windows
val largeMemoryConfig = WindowPreloadConfig(
    preloadThreshold = 0.75,
    maxWindows = 5
)

// Scenario 2: Small memory device - keep only 2 windows
val smallMemoryConfig = WindowPreloadConfig(
    preloadThreshold = 0.80,  // Wait until 80% to preload
    maxWindows = 2
)

// Scenario 3: Fast reader - aggressive preloading
val fastReaderConfig = WindowPreloadConfig(
    preloadThreshold = 0.60,  // Preload earlier at 60%
    maxWindows = 3
)

// Use configuration based on device capabilities
val config = when (getDeviceMemoryClass()) {
    MemoryClass.HIGH -> largeMemoryConfig
    MemoryClass.MEDIUM -> WindowPreloadConfig() // Default
    MemoryClass.LOW -> smallMemoryConfig
}

val manager = StableWindowManager(
    bookFile = bookFile,
    parser = parser,
    windowHtmlProvider = htmlProvider,
    config = config
)
```

## Example 5: Error Handling

```kotlin
// Handle window loading errors gracefully

private fun initializeStableWindows() {
    viewModelScope.launch {
        try {
            val manager = StableWindowManager(/* ... */)
            manager.initialize()
            
            val window = manager.loadInitialWindow(chapterIndex, inPageIndex)
            
            if (!window.isReady) {
                // Window failed to load
                AppLogger.e(TAG, "Window load failed: ${window.errorMessage}")
                showError("Failed to load content: ${window.errorMessage}")
                
                // Fall back to chapter-based mode
                fallbackToChapterMode()
                return@launch
            }
            
            windowManager = manager
            
        } catch (e: Exception) {
            AppLogger.e(TAG, "StableWindowManager initialization failed", e)
            showError("Failed to initialize reader: ${e.message}")
            fallbackToChapterMode()
        }
    }
}

// Handle window transition failures
suspend fun navigateNext() {
    val manager = windowManager ?: return
    
    if (manager.isAtWindowBoundary(NavigationDirection.NEXT)) {
        val nextWindow = manager.navigateToNextWindow()
        
        if (nextWindow == null) {
            // Transition failed (next window not ready or at end)
            showMessage("Loading next section...")
            
            // Optionally retry after delay
            delay(1000)
            val retryWindow = manager.navigateToNextWindow()
            
            if (retryWindow == null) {
                showError("Failed to load next section")
            }
        }
    }
}
```

## Example 6: Position Restoration

```kotlin
// Restore precise reading position after app restart

private fun restorePosition() {
    viewLifecycleOwner.lifecycleScope.launch {
        val manager = windowManager ?: return@launch
        
        // Get saved position from database
        val book = repository.getBookById(bookId)
        val savedChapter = book?.currentChapterIndex ?: 0
        val savedPage = book?.currentInPageIndex ?: 0
        
        // Load window containing saved position
        val window = manager.loadInitialWindow(savedChapter, savedPage)
        
        if (window.isReady) {
            // Window loaded successfully
            val chapter = window.getChapter(savedChapter)
            if (chapter != null) {
                // Calculate page within window
                val pageInWindow = chapter.startPage + savedPage
                
                // Navigate WebView to that page
                webView.evaluateJavascript("""
                    window.inpagePaginator.goToPage($pageInWindow);
                """, null)
                
                AppLogger.d(TAG, "Position restored: chapter=$savedChapter, page=$savedPage")
            }
        }
    }
}
```

## Summary

These examples demonstrate:
- ✅ How to integrate StableWindowManager in ReaderViewModel
- ✅ How to observe and render windows in ReaderPageFragment
- ✅ How to handle JavaScript callbacks for position tracking
- ✅ How to configure preloading behavior
- ✅ How to handle errors gracefully
- ✅ How to restore reading position

For complete details, see:
- [STABLE_WINDOW_MODEL.md](../complete/STABLE_WINDOW_MODEL.md)
- [JS_STREAMING_DISCIPLINE.md](../complete/JS_STREAMING_DISCIPLINE.md)
