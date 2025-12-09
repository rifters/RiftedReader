package com.rifters.riftedreader.ui.reader

import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.RecyclerView
import com.rifters.riftedreader.data.preferences.ChapterVisibilitySettings
import com.rifters.riftedreader.data.preferences.ReaderPreferences
import com.rifters.riftedreader.data.preferences.ReaderSettings
import com.rifters.riftedreader.data.repository.BookRepository
import com.rifters.riftedreader.domain.pagination.ChapterIndexProvider
import com.rifters.riftedreader.domain.pagination.ContinuousPaginator
import com.rifters.riftedreader.domain.pagination.PageLocation
import com.rifters.riftedreader.domain.pagination.PaginationMode
import com.rifters.riftedreader.domain.parser.BookParser
import com.rifters.riftedreader.domain.parser.PageContent
import com.rifters.riftedreader.domain.parser.TocEntry
import com.rifters.riftedreader.domain.parser.TxtParser
import com.rifters.riftedreader.pagination.PaginationModeGuard
import com.rifters.riftedreader.pagination.SlidingWindowPaginator
import com.rifters.riftedreader.pagination.WindowData
import com.rifters.riftedreader.pagination.WindowSyncHelpers
import com.rifters.riftedreader.ui.reader.conveyor.ConveyorBeltSystemViewModel
import com.rifters.riftedreader.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ViewModel for the reader screen, managing book content, pagination, and reading state.
 * 
 * Supports two pagination modes:
 * - CHAPTER_BASED: Each chapter is a separate page/window (1:1 mapping)
 * - CONTINUOUS: Chapters are grouped into sliding windows (N chapters per window)
 * 
 * Integrates deterministic sliding-window pagination with race condition guards to prevent
 * inconsistent state during window building operations.
 */
class ReaderViewModel(
    val bookId: String,
    private val bookFile: File,
    private val parser: BookParser,
    private val repository: BookRepository,
    private val readerPreferences: ReaderPreferences
) : ViewModel() {

    // Expose reader settings as StateFlow for UI consumption
    val readerSettings: StateFlow<ReaderSettings>
        get() = readerPreferences.settings
    
    // Table of Contents - list of chapter entries
    private val _tableOfContents = MutableStateFlow<List<TocEntry>>(emptyList())
    val tableOfContents: StateFlow<List<TocEntry>> = _tableOfContents.asStateFlow()
    
    /**
     * Get the table of contents filtered by current visibility settings.
     * 
     * Returns only entries where the spine index (pageNumber) corresponds to a visible chapter.
     * This filters out entries for cover pages, navigation documents, and non-linear content
     * based on the current ChapterVisibilitySettings.
     * 
     * Use this for displaying the chapter list in the UI (ChaptersBottomSheet).
     * 
     * Note: Before chapters are loaded into ChapterIndexProvider, this returns all TOC entries
     * (since isSpineIndexVisible() returns false for unknown indices, making the filter match nothing).
     * This is safe because TOC loading is async and UI should observe _tableOfContents StateFlow.
     */
    val visibleTableOfContents: List<TocEntry>
        get() {
            // When ChapterIndexProvider has no chapters loaded, return all TOC entries
            // This handles the case where TOC loads before spine data is available
            if (chapterIndexProvider.spineCount == 0) {
                return _tableOfContents.value
            }
            return _tableOfContents.value.filter { tocEntry ->
                chapterIndexProvider.isSpineIndexVisible(tocEntry.pageNumber)
            }
        }

    // Pagination mode from preferences
    val paginationMode: PaginationMode
        get() = readerPreferences.settings.value.paginationMode
    
    /**
     * Check if using horizontal windowed pagination mode.
     * 
     * Note: "CONTINUOUS mode" in the codebase refers to horizontal windowed pagination
     * where chapters are grouped into sliding windows (5 chapters per window) for
     * efficient memory management and smooth horizontal scrolling.
     */
    val isContinuousMode: Boolean
        get() = paginationMode == PaginationMode.CONTINUOUS
    
    /**
     * Get the image cache root directory for WebViewAssetLoader.
     * This is needed to set up the EpubImagePathHandler for loading cached images.
     */
    fun getImageCacheRoot(): File {
        return com.rifters.riftedreader.util.EpubImageAssetHelper.getImageCacheRoot(bookFile)
    }

    // Sliding window manager for window index calculations
    private val slidingWindowManager = com.rifters.riftedreader.domain.pagination.SlidingWindowManager(
        windowSize = com.rifters.riftedreader.domain.pagination.SlidingWindowManager.DEFAULT_WINDOW_SIZE
    )

    // NEW: deterministic sliding-window paginator for race condition protection
    // Default chaptersPerWindow is 5; change if you want to read from settings.
    val chaptersPerWindow: Int = SlidingWindowPaginator.DEFAULT_CHAPTERS_PER_WINDOW
    val slidingWindowPaginator = SlidingWindowPaginator(chaptersPerWindow)
    
    // Threshold for triggering buffer shifts (number of pages from window boundary)
    // When user is within this many pages of window edge, trigger preloading of adjacent window
    private val bufferShiftThresholdPages: Int = 2

    // LiveData for window count (compatibility with adapter / UI code)
    val windowCountLiveData = MutableLiveData(0)

    // Guard to prevent race conditions during window building
    // NOTE: paginationModeLiveData is not available in this repo snapshot; pass null for now.
    val paginationModeGuard = PaginationModeGuard(paginationModeLiveData = null)
    
    // Chapter index provider for unified chapter indexing with visibility settings
    // Provides mapping between UI indices and spine indices based on visibility settings.
    // NOTE: Chapters are populated during pagination initialization (initializeChapterBasedPagination
    // or initializeHorizontalWindowedPagination), not during construction. The initial visibility settings
    // are applied when observeVisibilitySettingsChanges() starts collecting.
    val chapterIndexProvider = ChapterIndexProvider(chaptersPerWindow)

    // Existing state holders (placeholders here — keep repo originals)
    private val _pages = MutableStateFlow<List<PageContent>>(emptyList())
    private val _totalPages = MutableStateFlow(0)
    private val _windowCount = MutableStateFlow(0)
    private val _currentPage = MutableStateFlow(0)
    private val _currentWindowIndex = MutableStateFlow(0)
    private val _content = MutableStateFlow(PageContent.EMPTY)

    // Add missing member variables that were in duplicate block
    private val pageContentCache = mutableMapOf<Int, MutableStateFlow<PageContent>>()
    private var continuousPaginator: ContinuousPaginator? = null
    private var isContinuousInitialized = false
    
    // WindowBufferManager has been deprecated and removed in favor of ConveyorBeltSystemViewModel
    // All window buffer management is now handled by ConveyorBeltSystemViewModel
    
    // Conveyor Belt System for managing window transitions and buffer state
    // This is the primary window controller in development branch
    private var _conveyorBeltSystem: ConveyorBeltSystemViewModel? = null
    
    // Public accessor for conveyor system
    val conveyorBeltSystem: ConveyorBeltSystemViewModel?
        get() = _conveyorBeltSystem
    
    /**
     * Check if the conveyor system is active and should be the authoritative window manager.
     * Returns true when:
     * - enableMinimalPaginator flag is true in settings
     * - conveyorBeltSystem is not null
     * 
     * TODO: Add unit test to verify isConveyorPrimary returns correct value based on flag and conveyor state
     */
    val isConveyorPrimary: Boolean
        get() = readerSettings.value.enableMinimalPaginator && _conveyorBeltSystem != null
    
    // Cache for pre-wrapped HTML to enable fast access for windows 0-4 during initial load
    private val preWrappedHtmlCache = mutableMapOf<Int, String>()
    
    // TTS highlight state
    private val _highlight = MutableStateFlow<TtsHighlight?>(null)
    val highlight: StateFlow<TtsHighlight?> = _highlight.asStateFlow()
    
    // WebView page state
    private val _currentWebViewPage = MutableStateFlow(0)
    val currentWebViewPage: StateFlow<Int> = _currentWebViewPage.asStateFlow()
    private val _totalWebViewPages = MutableStateFlow(0)
    val totalWebViewPages: StateFlow<Int> = _totalWebViewPages.asStateFlow()
    
    // Jump to last page flag (for navigating back to previous window)
    private val _jumpToLastPage = MutableStateFlow(false)
    val jumpToLastPage: StateFlow<Boolean> = _jumpToLastPage.asStateFlow()
    // Alias for backwards compatibility
    val shouldJumpToLastPage: StateFlow<Boolean> = _jumpToLastPage.asStateFlow()
    
    // Public accessors for StateFlows
    val pages: StateFlow<List<PageContent>> = _pages.asStateFlow()
    val totalPages: StateFlow<Int> = _totalPages.asStateFlow()
    val windowCount: StateFlow<Int> = _windowCount.asStateFlow()
    val currentPage: StateFlow<Int> = _currentPage.asStateFlow()
    val currentWindowIndex: StateFlow<Int> = _currentWindowIndex.asStateFlow()
    val content: StateFlow<PageContent> = _content.asStateFlow()
    
    /**
     * Get the count of visible chapters based on current visibility settings.
     * 
     * Use this for progress calculations and UI display instead of total spine count.
     * This accounts for hidden chapters (cover, nav, non-linear) based on user preferences.
     * 
     * Returns 0 if chapters haven't been loaded yet (safe default for progress calculations).
     */
    val visibleChapterCount: Int
        get() = chapterIndexProvider.visibleChapterCount
    
    /**
     * Get the current chapter visibility settings.
     */
    val currentVisibilitySettings: ChapterVisibilitySettings
        get() = chapterIndexProvider.currentVisibilitySettings
    
    /**
     * Calculate reading progress percentage based on visible chapters.
     * 
     * This provides a more accurate progress indication as it excludes hidden chapters
     * (cover, navigation, non-linear content) from the calculation.
     * 
     * **Behavior when current chapter is hidden:**
     * If the user is viewing a hidden chapter (e.g., cover page when cover visibility is off),
     * this method returns 0f. This is intentional because:
     * - Hidden chapters are not part of the user's "reading progress" by design
     * - The user would typically navigate to visible content before meaningful progress occurs
     * - Finding the nearest visible chapter could give misleading progress values
     * 
     * For cases where progress from hidden chapters is needed, callers can check
     * if the spine index is visible first using `chapterIndexProvider.isSpineIndexVisible()`.
     * 
     * @param currentSpineIndex The current spine index (0-based)
     * @return Progress percentage (0-100), or 0f if no visible chapters or current chapter is hidden
     */
    fun calculateVisibleProgress(currentSpineIndex: Int): Float {
        val visibleCount = chapterIndexProvider.visibleChapterCount
        if (visibleCount <= 0) return 0f
        
        // Convert spine index to UI index
        val uiIndex = chapterIndexProvider.spineIndexToUiIndex(currentSpineIndex)
        if (uiIndex < 0) {
            // Current chapter is hidden (e.g., cover page when cover visibility is off)
            // Return 0 progress as the user isn't viewing visible content
            return 0f
        }
        
        return ((uiIndex + 1).toFloat() / visibleCount) * 100f
    }
    
    /**
     * Get the UI index for the current chapter position.
     * 
     * This converts the internal page index to a user-visible chapter index,
     * accounting for hidden chapters.
     * 
     * Note: In chapter-based mode, _currentPage equals the spine index directly.
     * In continuous mode, additional mapping through the paginator may be needed
     * for accurate results.
     * 
     * Returns -1 if:
     * - Chapters haven't been loaded yet
     * - Current position corresponds to a hidden chapter
     * - Current window has no visible chapters
     * 
     * @return The visible chapter index (0-based), or -1 if current position is not visible
     */
    fun getCurrentVisibleChapterIndex(): Int {
        // Guard: Return -1 if chapters haven't been loaded yet
        if (chapterIndexProvider.spineCount == 0) {
            return -1
        }
        
        // In chapter-based mode, _currentPage is the spine/chapter index
        // In continuous mode, this may need additional mapping through ContinuousPaginator
        // for precise chapter identification within a window
        return if (isContinuousMode) {
            // For continuous mode, we need to determine the chapter from the current window
            // For now, use the window's first chapter as an approximation
            val windowIdx = _currentWindowIndex.value
            val spineIndices = chapterIndexProvider.getSpineIndicesForWindow(windowIdx)
            spineIndices.firstOrNull()?.let { spineIndex ->
                chapterIndexProvider.spineIndexToUiIndex(spineIndex)
            } ?: -1
        } else {
            // Chapter-based mode: page index equals spine index
            chapterIndexProvider.spineIndexToUiIndex(_currentPage.value)
        }
    }

    init {
        AppLogger.d("ReaderViewModel", "[PAGINATION_DEBUG] init: bookId=$bookId, paginationMode=$paginationMode")
        
        // Initialize content loading based on pagination mode
        if (isContinuousMode) {
            AppLogger.d("ReaderViewModel", "[WINDOWED] Starting horizontal windowed mode initialization")
            initializeHorizontalWindowedPagination()
        } else {
            AppLogger.d("ReaderViewModel", "[PAGINATION_DEBUG] Starting chapter-based mode initialization")
            initializeChapterBasedPagination()
        }
        
        // Load table of contents
        loadTableOfContents()
        
        // Observe visibility settings changes and update window count accordingly
        observeVisibilitySettingsChanges()
    }
    
    /**
     * Observe changes to chapter visibility settings and update pagination accordingly.
     * 
     * When visibility settings change (e.g., user toggles "Include cover page"), this:
     * 1. Updates the ChapterIndexProvider with new visibility settings
     * 2. Recomputes the window count based on visible chapters
     * 3. Updates the UI state (windowCountLiveData, _windowCount StateFlow)
     * 
     * This enables dynamic visibility changes mid-session without requiring a book reload.
     */
    private fun observeVisibilitySettingsChanges() {
        viewModelScope.launch {
            readerPreferences.settings
                .map { it.chapterVisibility }
                .distinctUntilChanged()
                .collect { visibilitySettings ->
                    handleVisibilitySettingsChange(visibilitySettings)
                }
        }
    }
    
    /**
     * Handle a change in chapter visibility settings.
     * 
     * Updates the ChapterIndexProvider and recomputes window counts.
     * 
     * @param visibilitySettings The new visibility settings
     */
    private fun handleVisibilitySettingsChange(visibilitySettings: ChapterVisibilitySettings) {
        AppLogger.d("ReaderViewModel", "[VISIBILITY] Settings changed: " +
            "cover=${visibilitySettings.includeCover}, " +
            "frontMatter=${visibilitySettings.includeFrontMatter}, " +
            "nonLinear=${visibilitySettings.includeNonLinear}")
        
        // Update the ChapterIndexProvider with new settings
        // This will rebuild the visible chapters list and mappings
        chapterIndexProvider.updateVisibilitySettings(visibilitySettings)
        
        // Only recompute window count if chapters have been loaded into the provider.
        // Check spineCount (total chapters) rather than visibleCount to handle the case
        // where all chapters are filtered out by visibility settings.
        val spineCount = chapterIndexProvider.spineCount
        if (spineCount > 0) {
            val visibleCount = chapterIndexProvider.visibleChapterCount
            val newWindowCount = chapterIndexProvider.getWindowCount()
            
            AppLogger.d("ReaderViewModel", "[VISIBILITY] Recomputing windows: " +
                "spineCount=$spineCount, visibleChapters=$visibleCount, windowCount=$newWindowCount")
            
            // Update window count state
            _windowCount.value = newWindowCount
            windowCountLiveData.postValue(newWindowCount)
            
            // Also update the SlidingWindowPaginator for consistency
            slidingWindowPaginator.recomputeWindows(visibleCount)
            
            AppLogger.d("ReaderViewModel", "[VISIBILITY] Window update complete: " +
                "spineCount=$spineCount, visibleCount=$visibleCount, windowCount=$newWindowCount")
        }
    }
    
    /**
     * Initialize chapter-based pagination mode.
     * Loads all chapters as individual pages.
     */
    private fun initializeChapterBasedPagination() {
        viewModelScope.launch {
            AppLogger.d("ReaderViewModel", "[PAGINATION_DEBUG] Initializing chapter-based pagination for book=$bookId")
            try {
                val pages = generatePages()
                _pages.value = pages
                _totalPages.value = pages.size
                _windowCount.value = pages.size  // In chapter mode, window count equals chapter count
                windowCountLiveData.postValue(pages.size)
                
                AppLogger.d("ReaderViewModel", "[PAGINATION_DEBUG] Chapter-based pagination complete: pageCount=${pages.size}")
                
                // Load saved position
                val book = repository.getBookById(bookId)
                val startPage = book?.currentChapterIndex ?: 0
                val safePage = startPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
                
                _currentPage.value = safePage
                _currentWindowIndex.value = safePage
                _content.value = pages.getOrNull(safePage) ?: PageContent.EMPTY
                
                AppLogger.d("ReaderViewModel", "[PAGINATION_DEBUG] Restored position: page=$safePage, contentLength=${_content.value.text.length}")
            } catch (e: Exception) {
                AppLogger.e("ReaderViewModel", "[PAGINATION_DEBUG] Failed to initialize chapter-based pagination", e)
                _pages.value = emptyList()
                _totalPages.value = 0
                _windowCount.value = 0
                windowCountLiveData.postValue(0)
                _content.value = PageContent.EMPTY
            }
        }
    }
    
    /**
     * Load the table of contents for the book.
     */
    private fun loadTableOfContents() {
        viewModelScope.launch {
            try {
                val toc = withContext(Dispatchers.IO) {
                    parser.getTableOfContents(bookFile)
                }
                _tableOfContents.value = toc
                AppLogger.d("ReaderViewModel", "[PAGINATION_DEBUG] Loaded TOC: ${toc.size} entries")
            } catch (e: Exception) {
                AppLogger.e("ReaderViewModel", "[PAGINATION_DEBUG] Failed to load TOC", e)
                _tableOfContents.value = emptyList()
            }
        }
    }

    /**
     * Initialize horizontal windowed pagination mode.
     * 
     * CONTINUOUS mode = horizontal windowed pagination with 5-chapter windows.
     * This mode groups chapters into sliding windows for efficient memory management
     * and smooth horizontal scrolling through the book content.
     * 
     * Each window contains up to 5 chapters of content, and windows are managed in
     * a 5-window buffer (STARTUP and STEADY phases) by the ConveyorBeltSystemViewModel
     * when enableMinimalPaginator is true.
     */
    private fun initializeHorizontalWindowedPagination() {
        viewModelScope.launch {
            // Begin window build - lock pagination mode during construction
            paginationModeGuard.beginWindowBuild()
            try {
                AppLogger.d("ReaderViewModel", "[WINDOWED] Initializing horizontal windowed pagination for book=$bookId")
                val paginator = ContinuousPaginator(bookFile, parser, windowSize = 5)
                paginator.initialize()
                continuousPaginator = paginator

                val book = repository.getBookById(bookId)
                val startChapter = book?.currentChapterIndex ?: 0
                val startInPage = book?.currentInPageIndex ?: 0
                
                AppLogger.d("ReaderViewModel", "[WINDOWED] Book info: startChapter=$startChapter, startInPage=$startInPage")
                
                // Load the initial window
                paginator.loadInitialWindow(startChapter)
                _totalPages.value = paginator.getTotalGlobalPages()
                
                // Calculate window count for RecyclerView (number of windows, not chapters)
                val windowInfo = paginator.getWindowInfo()
                val totalChapters = windowInfo.totalChapters
                
                AppLogger.d("ReaderViewModel", "[WINDOWED] WindowInfo: totalChapters=$totalChapters, totalGlobalPages=${windowInfo.totalGlobalPages}")
                
                // Handle empty book case properly - set all state to consistent values
                if (totalChapters <= 0) {
                    AppLogger.w("ReaderViewModel", "[WINDOWED] Book has no chapters - applying fallback")
                    _totalPages.value = 0
                    _windowCount.value = 0
                    windowCountLiveData.postValue(0)
                    _currentPage.value = 0
                    _currentWindowIndex.value = 0
                    _content.value = PageContent(text = "No content available")
                    isContinuousInitialized = true
                    paginationModeGuard.endWindowBuild()
                    return@launch
                }
                
                // Use SlidingWindowPaginator for deterministic window computation
                val computedWindowCount = slidingWindowPaginator.recomputeWindows(totalChapters)
                _windowCount.value = computedWindowCount
                // Keep LiveData in sync for observers using traditional LiveData pattern
                windowCountLiveData.postValue(computedWindowCount)
                
                // [WINDOWED] Log window computation details
                AppLogger.d("ReaderViewModel", "[WINDOWED] Window computation: totalChapters=$totalChapters, chaptersPerWindow=$chaptersPerWindow, computedWindowCount=$computedWindowCount")
                
                // [FALLBACK] If zero windows computed, create at least one window with all content
                if (computedWindowCount == 0 && totalChapters > 0) {
                    AppLogger.e("ReaderViewModel", "[WINDOWED] FALLBACK: Zero windows computed for $totalChapters chapters - forcing windowCount=1")
                    _windowCount.value = 1
                    // Also update LiveData for observers using traditional LiveData pattern
                    windowCountLiveData.postValue(1)
                }
                
                // Log window map for debugging
                AppLogger.d("ReaderViewModel", "[WINDOW_BUILD] ${slidingWindowPaginator.debugWindowMap()}")
                
                // Validate invariant: windowCount == ceil(totalChapters / chaptersPerWindow)
                val expectedWindowCount = if (totalChapters <= 0) 0 else 
                    kotlin.math.ceil(totalChapters.toDouble() / chaptersPerWindow).toInt()
                if (computedWindowCount != expectedWindowCount) {
                    AppLogger.e("ReaderViewModel", "[WINDOW_BUILD] Window count assertion failed! computed=$computedWindowCount expected=$expectedWindowCount")
                }
                
                // Calculate initial window index with bounds validation
                val safeStartChapter = startChapter.coerceIn(0, totalChapters - 1)
                val initialWindowIndex = slidingWindowPaginator.getWindowForChapter(safeStartChapter)
                _currentWindowIndex.value = initialWindowIndex
                
                AppLogger.d("ReaderViewModel", "[WINDOWED] Initialization complete: totalChapters=$totalChapters, windowCount=$computedWindowCount, initialWindowIndex=$initialWindowIndex")
                
                // Verify invariant
                paginationModeGuard.assertWindowCountInvariant(
                    slidingWindowPaginator.getWindowCount(),
                    _windowCount.value
                )
                
                isContinuousInitialized = true
                
                // Initialize ConveyorBeltSystemViewModel as the authoritative buffer manager
                // This replaces the deprecated WindowBufferManager system
                val conveyorSystem = _conveyorBeltSystem
                if (conveyorSystem != null && computedWindowCount > 0) {
                    // Set HTML loading dependencies first
                    conveyorSystem.setHtmlLoadingDependencies(
                        paginator = paginator,
                        windowManager = slidingWindowManager,
                        bookId = bookId
                    )
                    
                    // Then initialize the buffer
                    conveyorSystem.initialize(initialWindowIndex, computedWindowCount)
                    AppLogger.d("ReaderViewModel", "[CONVEYOR_ACTIVE] ConveyorBeltSystemViewModel initialized: " +
                        "startWindow=$initialWindowIndex, totalWindows=$computedWindowCount, " +
                        "phase=${conveyorSystem.phase.value}")
                } else {
                    AppLogger.w("ReaderViewModel", "[CONVEYOR_ACTIVE] Conveyor initialization skipped: " +
                        "conveyorSystem=${conveyorSystem != null}, windowCount=$computedWindowCount")
                }
                
                // Pre-generate wrapped HTML for initial windows (0-4 or fewer if book has fewer windows)
                // This ensures downstream windows are prepared during initial load
                preWrapInitialWindows(totalChapters)

                // Try to restore position using chapter + in-page index first
                val initialGlobalPage = paginator.navigateToChapter(safeStartChapter, startInPage)

                updateForGlobalPage(initialGlobalPage)
                
                AppLogger.d("ReaderViewModel", "[WINDOWED] Restored position: chapter=$safeStartChapter, inPage=$startInPage, globalPage=$initialGlobalPage")
            } catch (e: Exception) {
                AppLogger.e("ReaderViewModel", "[WINDOWED] Failed to initialize horizontal windowed pagination", e)
                _pages.value = emptyList()
                _totalPages.value = 0
                _windowCount.value = 0
                windowCountLiveData.value = 0
                _currentPage.value = 0
                _currentWindowIndex.value = 0
                _content.value = PageContent(text = "Error loading content: ${e.message}")
            } finally {
                // End window build - unlock pagination mode
                paginationModeGuard.endWindowBuild()
            }
        }
    }
    
    /**
     * Pre-generate wrapped HTML for initial windows (0-4 or fewer if book has fewer windows).
     * This ensures downstream windows are prepared during initial load, not just window 0.
     * 
     * ISSUE 1 FIX: Window HTML generation only runs for windowIndex=0 during initial load.
     * This method pre-wraps windows 0-4 (or fewer if book has fewer windows) to ensure
     * the adapter has content ready for adjacent windows when user starts swiping.
     * 
     * @param totalChapters Total number of chapters in the book
     */
    private suspend fun preWrapInitialWindows(totalChapters: Int) {
        val paginator = continuousPaginator ?: return
        if (totalChapters <= 0) return
        
        val totalWindows = slidingWindowPaginator.getWindowCount()
        // Pre-wrap up to 5 windows (0-4) or fewer if book has fewer windows
        val windowsToPreWrap = kotlin.math.min(5, totalWindows)
        
        AppLogger.d("ReaderViewModel", "[PAGINATION_DEBUG] preWrapInitialWindows: totalChapters=$totalChapters, totalWindows=$totalWindows, preWrapping=$windowsToPreWrap windows")
        
        // Create provider once and reuse it for all windows (more efficient than creating per window)
        val windowHtmlProvider = com.rifters.riftedreader.domain.pagination.ContinuousPaginatorWindowHtmlProvider(
            paginator,
            slidingWindowManager
        )
        
        for (windowIndex in 0 until windowsToPreWrap) {
            try {
                // FIX #4: Check if this window's chapters are available before pre-wrapping
                // If chapters aren't loaded yet, skip pre-wrapping and let on-demand loading handle it later
                val windowInfo = paginator.getWindowInfo()
                val chapterIndices = slidingWindowManager.chaptersInWindow(windowIndex, totalChapters)
                val firstChapterInWindow = chapterIndices.firstOrNull()
                
                if (firstChapterInWindow != null && !windowInfo.loadedChapterIndices.contains(firstChapterInWindow)) {
                    AppLogger.d("ReaderViewModel", 
                        "[PAGINATION_DEBUG] Skipping pre-wrap for window $windowIndex - " +
                        "chapters not yet loaded (need: ${chapterIndices.first()}-${chapterIndices.last()}, " +
                        "have: ${windowInfo.loadedChapterIndices})")
                    continue // Skip this window, pre-wrap only when we have chapters
                }
                
                val html = windowHtmlProvider.getWindowHtml(bookId, windowIndex)
                if (html != null) {
                    // Store in cache for fast access later
                    preWrappedHtmlCache[windowIndex] = html
                    
                    AppLogger.d("ReaderViewModel", "[PAGINATION_DEBUG] Pre-wrapped HTML for window $windowIndex: htmlLength=${html.length}")
                    
                    // Log the wrapped HTML for debugging
                    com.rifters.riftedreader.util.HtmlDebugLogger.logWrappedHtml(
                        bookId = bookId,
                        chapterIndex = windowIndex, // Use windowIndex as identifier
                        wrappedHtml = html,
                        metadata = mapOf(
                            "windowIndex" to windowIndex.toString(),
                            "type" to "pre-wrapped",
                            "totalWindows" to totalWindows.toString()
                        )
                    )
                } else {
                    AppLogger.w("ReaderViewModel", "[PAGINATION_DEBUG] Failed to pre-wrap HTML for window $windowIndex")
                }
            } catch (e: Exception) {
                AppLogger.e("ReaderViewModel", "[PAGINATION_DEBUG] Error pre-wrapping window $windowIndex", e)
            }
        }
        
        AppLogger.d("ReaderViewModel", "[PAGINATION_DEBUG] preWrapInitialWindows complete: cached ${preWrappedHtmlCache.size} windows")
    }
    
    /**
     * Check if buffer should shift forward based on current position within a window.
     * 
     * DEPRECATED: WindowBufferManager has been removed in favor of ConveyorBeltSystemViewModel.
     * This method is kept for API compatibility but does nothing.
     * 
     * @param currentInPageIndex Current page within the window (0-based)
     * @param totalPagesInWindow Total pages in the current window
     */
    fun maybeShiftForward(currentInPageIndex: Int, totalPagesInWindow: Int) {
        // WindowBufferManager is deprecated and removed - this is a no-op
        return
    }
    
    /**
     * Check if buffer should shift backward based on current position within a window.
     * 
     * DEPRECATED: WindowBufferManager has been removed in favor of ConveyorBeltSystemViewModel.
     * This method is kept for API compatibility but does nothing.
     * 
     * @param currentInPageIndex Current page within the window (0-based)
     */
    fun maybeShiftBackward(currentInPageIndex: Int) {
        // WindowBufferManager is deprecated and removed - this is a no-op
        return
    }
    
    /**
     * Get cached window data from WindowBufferManager.
     * 
     * DEPRECATED: WindowBufferManager has been removed. Always returns null.
     * 
     * @param windowIndex The window index to look up
     * @return Always null (WindowBufferManager is deprecated)
     */
    fun getCachedWindowData(windowIndex: Int): WindowData? {
        return null
    }

    private suspend fun generatePages(): List<PageContent> {
        return withContext(Dispatchers.IO) {
            val pages = mutableListOf<PageContent>()
            val rawPageCount = parser.getPageCount(bookFile).coerceAtLeast(0)

            // For TXT we already paginate by fixed line counts, reuse as-is.
            if (parser is TxtParser) {
                for (index in 0 until rawPageCount) {
                    val pageContent = runCatching { parser.getPageContent(bookFile, index) }
                        .getOrDefault(PageContent.EMPTY)
                    pages += pageContent
                }
                return@withContext pages
            }

            // For EPUB/HTML: Don't split chapters - let WebView handle pagination per-viewport
            // Each parser "page" represents a chapter/spine item
            for (index in 0 until rawPageCount) {
                val chapterContent = runCatching { parser.getPageContent(bookFile, index) }
                    .getOrDefault(PageContent.EMPTY)
                // Add the chapter as-is without splitting
                if (!chapterContent.text.isBlank() || !chapterContent.html.isNullOrBlank()) {
                    pages += chapterContent
                }
            }

            if (pages.isEmpty()) {
                listOf(PageContent.EMPTY)
            } else {
                pages
            }
        }
    }

    fun nextPage(): Boolean {
        val total = _totalPages.value
        if (total <= 0) return false
        val current = _currentPage.value
        if (current >= total - 1) return false
        val nextIndex = current + 1
        AppLogger.d("ReaderViewModel", "Advancing from page $current to $nextIndex (totalPages=$total)")
        return goToPage(nextIndex)
    }

    fun previousPage(): Boolean {
        val total = _totalPages.value
        if (total <= 0) return false
        val current = _currentPage.value
        if (current <= 0) return false
        return goToPage(current - 1)
    }

    /**
     * Navigate to the previous chapter and signal to jump to its last internal page.
     * This is specifically for backward navigation across chapter boundaries in PAGE mode.
     * 
     * @return true if navigation succeeded, false if already at first chapter
     */
    fun previousChapterToLastPage(): Boolean {
        if (isContinuousMode) {
            return previousPage()
        }
        val pages = _pages.value
        if (pages.isEmpty()) return false
        val current = _currentPage.value
        if (current <= 0) {
            return false
        }
        // Set flag before changing page so fragment can react when it loads
        _jumpToLastPage.value = true
        updateCurrentPage(current - 1)
        return true
    }

    /**
     * Clear the jump-to-last-page flag. Called by fragment after jumping.
     */
    fun clearJumpToLastPageFlag() {
        _jumpToLastPage.value = false
    }
    
    /**
     * Set the jump-to-last-page flag. Used when navigating backward across windows.
     */
    fun setJumpToLastPageFlag() {
        _jumpToLastPage.value = true
    }
    
    /**
     * Set the conveyor belt system instance.
     * This should be called early in the reader lifecycle to enable
     * conveyor-based window management for the minimal paginator.
     * 
     * @param conveyorSystem The ConveyorBeltSystemViewModel instance
     */
    fun setConveyorBeltSystem(conveyorSystem: ConveyorBeltSystemViewModel) {
        _conveyorBeltSystem = conveyorSystem
        AppLogger.d("ReaderViewModel", "[CONVEYOR] Conveyor belt system reference set")
    }
    
    /**
     * Called by PaginatorBridge when pagination is ready and stable.
     * This is the entry point for the minimal paginator integration.
     * 
     * The minimal paginator ensures totalPages > 0 before calling this method,
     * avoiding race conditions with 0-page reports that can occur with the
     * inpage_paginator.js system.
     * 
     * This method performs the same state updates as the existing PaginationBridge
     * but with the guarantee of stable, non-zero page counts.
     * 
     * When the conveyor belt system is set, this method notifies it that the window
     * has completed stable pagination, allowing for proper phase transitions.
     * 
     * @param windowIndex The window index that completed pagination
     * @param totalPages The total page count for the window (guaranteed > 0)
     */
    fun onWindowPaginationReady(windowIndex: Int, totalPages: Int) {
        // TASK 4: CONVEYOR AUTHORITATIVE TAKEOVER - Log when forwarding to conveyor
        if (isConveyorPrimary && _conveyorBeltSystem != null) {
            AppLogger.d(
                "ReaderViewModel",
                "[CONVEYOR_ACTIVE] Paginator event forwarded to conveyor: window=$windowIndex totalPages=$totalPages"
            )
        } else {
            AppLogger.d(
                "ReaderViewModel",
                "[LEGACY_ACTIVE] [MIN_PAGINATOR] onWindowPaginationReady: windowIndex=$windowIndex, totalPages=$totalPages, " +
                "currentWindowIndex=$_currentWindowIndex.value"
            )
        }
        
        // Validate input
        if (totalPages <= 0) {
            AppLogger.w(
                "ReaderViewModel",
                "[MIN_PAGINATOR] Received invalid totalPages=$totalPages for windowIndex=$windowIndex"
            )
            return
        }
        
        // Update state flows to reflect stable pagination completion
        _totalWebViewPages.value = totalPages
        
        // If this is the active window, update metrics and notify conveyor system
        if (windowIndex == _currentWindowIndex.value) {
            AppLogger.d(
                "ReaderViewModel",
                "[MIN_PAGINATOR] Active window paginated: windowIndex=$windowIndex, totalPages=$totalPages"
            )
            
            // Update chapter pagination metrics (same as existing PaginationBridge)
            if (isContinuousMode) {
                viewModelScope.launch {
                    try {
                        val location = getPageLocation(windowIndex)
                        val chapterIndex = location?.chapterIndex ?: windowIndex
                        updateChapterPaginationMetrics(chapterIndex, totalPages)
                        AppLogger.d(
                            "ReaderViewModel",
                            "[MIN_PAGINATOR] Updated chapter metrics: chapterIndex=$chapterIndex, totalPages=$totalPages"
                        )
                    } catch (e: Exception) {
                        AppLogger.e(
                            "ReaderViewModel",
                            "[MIN_PAGINATOR] Error updating chapter pagination metrics",
                            e
                        )
                    }
                }
            }
            
            // Notify the conveyor belt system that this window has stable pagination
            _conveyorBeltSystem?.let { conveyor ->
                AppLogger.d(
                    "ReaderViewModel",
                    "[MIN_PAGINATOR] Notifying conveyor system: onWindowEntered($windowIndex)"
                )
                conveyor.onWindowEntered(windowIndex)
                AppLogger.d(
                    "ReaderViewModel",
                    "[MIN_PAGINATOR] Conveyor notified - phase=${conveyor.phase.value}, " +
                    "buffer=${conveyor.buffer.value}, activeWindow=${conveyor.activeWindow.value}"
                )
            } ?: AppLogger.d(
                "ReaderViewModel",
                "[MIN_PAGINATOR] No conveyor system set - skipping conveyor notification"
            )
        } else {
            AppLogger.d(
                "ReaderViewModel",
                "[MIN_PAGINATOR] Non-active window paginated: windowIndex=$windowIndex (current=$_currentWindowIndex.value)"
            )
        }
    }

    fun goToPage(page: Int): Boolean {
        val total = _totalPages.value
        if (total <= 0) return false
        if (page !in 0 until total) {
            return false
        }
        return if (isContinuousMode) {
            viewModelScope.launch {
                updateForGlobalPage(page)
            }
            true
        } else {
            updateCurrentPage(page)
            true
        }
    }

    fun hasNextPage(): Boolean {
        val total = _totalPages.value
        if (total <= 0) return false
        return _currentPage.value < total - 1
    }

    fun hasPreviousPage(): Boolean {
        return _currentPage.value > 0
    }

    fun saveProgress() {
        viewModelScope.launch {
            try {
                if (isContinuousMode) {
                    val location = getPageLocation(_currentPage.value)
                    val total = _totalPages.value
                    val content = _content.value
                    if (location != null && total > 0) {
                        val percent = ((location.globalPageIndex + 1).toFloat() / total) * 100f
                        val previewText = com.rifters.riftedreader.util.BookmarkPreviewExtractor
                            .extractPreview(content, location.characterOffset)
                        repository.updateReadingProgressEnhanced(
                            bookId,
                            location.chapterIndex,
                            location.inPageIndex,
                            location.characterOffset,
                            previewText,
                            percent
                        )
                    }
                } else {
                    repository.updateReadingProgress(
                        bookId,
                        _currentPage.value,
                        _totalPages.value
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun updateCurrentPage(index: Int) {
        val pages = _pages.value
        if (pages.isEmpty()) {
            _currentPage.value = 0
            _content.value = PageContent.EMPTY
            resetWebViewPageState()
            return
        }
        val safeIndex = index.coerceIn(0, pages.lastIndex)
        _currentPage.value = safeIndex
        _content.value = pages.getOrNull(safeIndex) ?: PageContent.EMPTY
        pageContentCache.getOrPut(safeIndex) { MutableStateFlow(PageContent.EMPTY) }.value = _content.value
        // Reset WebView page state when changing chapters
        resetWebViewPageState()
    }

    private suspend fun updateForGlobalPage(globalPageIndex: Int) {
        val paginator = continuousPaginator ?: return
        if (!isContinuousInitialized) return
        val location = paginator.navigateToGlobalPage(globalPageIndex) ?: return
        val content = paginator.getPageContent(globalPageIndex) ?: PageContent.EMPTY

        withContext(Dispatchers.Main) {
            _currentPage.value = globalPageIndex
            _content.value = content
            pageContentCache.getOrPut(globalPageIndex) { MutableStateFlow(PageContent.EMPTY) }.value = content
            _totalPages.value = paginator.getTotalGlobalPages()
        }

        saveEnhancedProgress(location)
    }

    private fun saveEnhancedProgress(location: PageLocation) {
        viewModelScope.launch {
            try {
                val total = _totalPages.value
                if (total <= 0) return@launch
                val percent = ((location.globalPageIndex + 1).toFloat() / total) * 100f
                val content = _content.value
                val previewText = com.rifters.riftedreader.util.BookmarkPreviewExtractor
                    .extractPreview(content, location.characterOffset)
                repository.updateReadingProgressEnhanced(
                    bookId,
                    location.chapterIndex,
                    location.inPageIndex,
                    location.characterOffset,
                    previewText,
                    percent
                )
            } catch (e: Exception) {
                AppLogger.e("ReaderViewModel", "Failed to save enhanced progress", e)
            }
        }
    }

    fun observePageContent(pageIndex: Int): StateFlow<PageContent> {
        return pageContentCache.getOrPut(pageIndex) { MutableStateFlow(PageContent.EMPTY) }.asStateFlow()
    }

    suspend fun getPageLocation(globalPageIndex: Int): PageLocation? {
        return if (isContinuousMode) {
            continuousPaginator?.getPageLocation(globalPageIndex)
        } else {
            PageLocation(globalPageIndex, globalPageIndex, 0)
        }
    }

    /**
     * Character offset tracking for stable position persistence across font changes and reflows.
     * Maps window index to character offset for that position.
     */
    private val characterOffsetMap = mutableMapOf<Int, Int>()

    /**
     * Update reading position with character offset.
     * Called after navigation to capture the current position with stable offset info.
     * 
     * @param windowIndex The window/page index
     * @param pageInWindow The in-page index within the window
     * @param characterOffset The character offset from the JavaScript paginator
     */
    fun updateReadingPosition(windowIndex: Int, pageInWindow: Int, characterOffset: Int) {
        characterOffsetMap[windowIndex] = characterOffset
        AppLogger.d(
            "ReaderViewModel",
            "[CHARACTER_OFFSET] Updated position: windowIndex=$windowIndex, pageInWindow=$pageInWindow, offset=$characterOffset"
        )
    }

    /**
     * Get saved character offset for a window.
     * Used to restore reading position when returning to a window.
     * 
     * @param windowIndex The window index to look up
     * @return The saved character offset, or 0 if not found
     */
    fun getSavedCharacterOffset(windowIndex: Int): Int {
        val offset = characterOffsetMap[windowIndex] ?: 0
        if (offset > 0) {
            AppLogger.d(
                "ReaderViewModel",
                "[CHARACTER_OFFSET] Retrieved offset for windowIndex=$windowIndex: offset=$offset"
            )
        }
        return offset
    }

    /**
     * Clear character offset for a window (useful after window reload).
     */
    fun clearCharacterOffset(windowIndex: Int) {
        characterOffsetMap.remove(windowIndex)
        AppLogger.d(
            "ReaderViewModel",
            "[CHARACTER_OFFSET] Cleared offset for windowIndex=$windowIndex"
        )
    }

    suspend fun navigateToChapter(chapterIndex: Int, inPageIndex: Int = 0): Int? {
        return if (isContinuousMode) {
            val paginator = continuousPaginator ?: return null
            val page = paginator.navigateToChapter(chapterIndex, inPageIndex)
            updateForGlobalPage(page)
            page
        } else {
            chapterIndex.coerceIn(0, _pages.value.lastIndex)
        }
    }

    suspend fun repaginateContinuous(): Int? {
        if (!isContinuousMode) return null
        val paginator = continuousPaginator ?: return null
        val newGlobalPage = paginator.repaginate()
        updateForGlobalPage(newGlobalPage)
        return newGlobalPage
    }

    suspend fun getChapterPageCount(chapterIndex: Int): Int {
        return if (isContinuousMode) {
            continuousPaginator?.getChapterPageCount(chapterIndex) ?: 0
        } else {
            1
        }
    }

    fun updateChapterPaginationMetrics(chapterIndex: Int, measuredPageCount: Int) {
        if (!isContinuousMode) return
        viewModelScope.launch {
            val paginator = continuousPaginator ?: return@launch
            val safeChapter = chapterIndex.coerceAtLeast(0)
            val safeCount = measuredPageCount.coerceAtLeast(1)
            val currentLocation = paginator.getPageLocation(_currentPage.value)
            val changed = paginator.updateChapterPageCount(safeChapter, safeCount)
            if (changed) {
                val total = paginator.getTotalGlobalPages()
                _totalPages.value = total
                currentLocation?.let { location ->
                    val updatedIndex = paginator.getGlobalIndexForChapterPage(
                        location.chapterIndex,
                        location.inPageIndex
                    )
                    if (updatedIndex != null && updatedIndex != _currentPage.value) {
                        _currentPage.value = updatedIndex.coerceIn(0, (total - 1).coerceAtLeast(0))
                    }
                }
            }
        }
    }

    fun publishHighlight(pageIndex: Int, range: IntRange?) {
        _highlight.value = TtsHighlight(pageIndex, range)
    }

    fun onChapterSegmentEvicted(chapterIndex: Int) {
        if (!isContinuousMode) return
        viewModelScope.launch {
            continuousPaginator?.markChapterEvicted(chapterIndex)
        }
    }
    
    /**
     * Update WebView page state from the current fragment.
     * Called by ReaderPageFragment when WebView pagination changes.
     * 
     * @param currentPage Current WebView page index (0-based)
     * @param totalPages Total number of WebView pages in current chapter
     */
    fun updateWebViewPageState(currentPage: Int, totalPages: Int) {
        _currentWebViewPage.value = currentPage
        _totalWebViewPages.value = totalPages
    }
    
    /**
     * Reset WebView page state (e.g., when switching chapters or to scroll mode).
     */
    fun resetWebViewPageState() {
        _currentWebViewPage.value = 0
        _totalWebViewPages.value = 0
    }

    suspend fun getStreamingChapterPayload(globalPageIndex: Int): StreamingChapterPayload? {
        val startMs = SystemClock.elapsedRealtime()
        val source = if (isContinuousMode) "continuous" else "chapter_buffer"
        return if (isContinuousMode) {
            val paginator = continuousPaginator ?: run {
                logStreamingPayloadFailure(source, globalPageIndex, startMs, "missing_paginator")
                return null
            }
            val location = paginator.navigateToGlobalPage(globalPageIndex) ?: run {
                logStreamingPayloadFailure(source, globalPageIndex, startMs, "location_null")
                return null
            }
            val content = paginator.getPageContent(globalPageIndex) ?: run {
                logStreamingPayloadFailure(source, globalPageIndex, startMs, "content_null")
                return null
            }
            val html = content.html ?: wrapPlainTextAsHtml(content.text)
            logStreamingPayloadSuccess(source, globalPageIndex, location.chapterIndex, startMs)
            StreamingChapterPayload(location.chapterIndex, html)
        } else {
            val content = _pages.value.getOrNull(globalPageIndex) ?: run {
                logStreamingPayloadFailure(source, globalPageIndex, startMs, "content_missing")
                return null
            }
            val html = content.html ?: wrapPlainTextAsHtml(content.text)
            logStreamingPayloadSuccess(source, globalPageIndex, globalPageIndex, startMs)
            StreamingChapterPayload(globalPageIndex, html)
        }
    }

    private fun wrapPlainTextAsHtml(text: String): String {
        if (text.isBlank()) {
            return "<p></p>"
        }
        val escaped = TextUtils.htmlEncode(text)
        return "<p>$escaped</p>"
    }

    private fun logStreamingPayloadSuccess(
        source: String,
        globalPageIndex: Int,
        chapterIndex: Int,
        startMs: Long
    ) {
        val durationMs = SystemClock.elapsedRealtime() - startMs
        AppLogger.event(
            "ReaderViewModel",
            "[STREAM_PAYLOAD_SUCCESS] source=$source globalIndex=$globalPageIndex chapter=$chapterIndex durationMs=$durationMs",
            "ui/webview/streaming"
        )
    }

    private fun logStreamingPayloadFailure(
        source: String,
        globalPageIndex: Int,
        startMs: Long,
        reason: String
    ) {
        val durationMs = SystemClock.elapsedRealtime() - startMs
        AppLogger.event(
            "ReaderViewModel",
            "[STREAM_PAYLOAD_FAIL] source=$source globalIndex=$globalPageIndex durationMs=$durationMs reason=$reason",
            "ui/webview/streaming"
        )
    }

    /**
     * Get the window HTML for a specific window index.
     * In continuous mode, this returns a sliding window combining multiple chapters.
     * In chapter-based mode, this returns just the single chapter HTML.
     * 
     * @param windowIndex The window index for RecyclerView (in continuous mode) or chapter index (in chapter-based mode)
     * @return WindowHtmlPayload containing the window HTML and metadata, or null if unavailable
     */
    suspend fun getWindowHtml(windowIndex: Int): WindowHtmlPayload? {
        AppLogger.d("ReaderViewModel", "[PAGINATION_DEBUG] getWindowHtml called: windowIndex=$windowIndex, mode=$paginationMode")
        
        return if (isContinuousMode) {
            val paginator = continuousPaginator ?: run {
                AppLogger.w("ReaderViewModel", "[PAGINATION_DEBUG] getWindowHtml: continuousPaginator is null")
                return null
            }
            val windowInfo = paginator.getWindowInfo()
            val totalChapters = windowInfo.totalChapters
            
            // Guard against empty book
            if (totalChapters <= 0) {
                AppLogger.w("ReaderViewModel", "[PAGINATION_DEBUG] Cannot get window HTML: no chapters available (totalChapters=$totalChapters)")
                return null
            }
            
            // Use the slidingWindowManager's window size for consistency
            val windowSize = slidingWindowManager.getWindowSize()
            
            // windowIndex is the RecyclerView position, directly used as window index
            val firstChapterInWindow = slidingWindowManager.firstChapterInWindow(windowIndex)
            val lastChapterInWindow = slidingWindowManager.lastChapterInWindow(windowIndex, totalChapters)
            
            AppLogger.d("ReaderViewModel", "[PAGINATION_DEBUG] Getting window HTML: windowIndex=$windowIndex, chapters=$firstChapterInWindow-$lastChapterInWindow (totalChapters=$totalChapters, windowSize=$windowSize)")
            
            // Check ConveyorBeltSystemViewModel cache first if conveyor is primary
            if (isConveyorPrimary) {
                val conveyorHtml = _conveyorBeltSystem?.getCachedWindowHtml(windowIndex)
                if (conveyorHtml != null) {
                    AppLogger.d("ReaderViewModel", "[CONVEYOR_ACTIVE] Using ConveyorBeltSystemViewModel cached HTML for window $windowIndex (htmlLength=${conveyorHtml.length})")
                    return WindowHtmlPayload(
                        html = conveyorHtml,
                        windowIndex = windowIndex,
                        chapterIndex = firstChapterInWindow,
                        inPageIndex = 0,
                        windowSize = windowSize,
                        totalChapters = totalChapters
                    )
                }
            }
            
            // Check pre-wrapped cache (initial load optimization)
            val cachedHtml = preWrappedHtmlCache[windowIndex]
            val html = if (cachedHtml != null) {
                AppLogger.d("ReaderViewModel", "[PAGINATION_DEBUG] Using cached pre-wrapped HTML for window $windowIndex")
                cachedHtml
            } else {
                // Generate HTML if not in cache
                val provider = com.rifters.riftedreader.domain.pagination.ContinuousPaginatorWindowHtmlProvider(
                    paginator,
                    slidingWindowManager
                )
                
                val generatedHtml = provider.getWindowHtml(bookId, windowIndex)
                if (generatedHtml == null) {
                    AppLogger.w("ReaderViewModel", "[PAGINATION_DEBUG] Window HTML provider returned null for window $windowIndex")
                    return null
                }
                generatedHtml
            }
            
            // [PAGINATION_DEBUG] Log HTML size for debugging
            AppLogger.d("ReaderViewModel", "[PAGINATION_DEBUG] Window HTML generated: windowIndex=$windowIndex, htmlLength=${html.length}")
            
            WindowHtmlPayload(
                html = html,
                windowIndex = windowIndex,
                chapterIndex = firstChapterInWindow,
                inPageIndex = 0,
                windowSize = windowSize,
                totalChapters = totalChapters
            )
        } else {
            // Chapter-based mode: windowIndex is chapter index (one window per chapter)
            val content = _pages.value.getOrNull(windowIndex)
            if (content == null) {
                AppLogger.w("ReaderViewModel", "[PAGINATION_DEBUG] No content for chapter index $windowIndex (pages.size=${_pages.value.size})")
                return null
            }
            
            val html = content.html ?: wrapPlainTextAsHtml(content.text)
            
            // [PAGINATION_DEBUG] Log chapter HTML details
            AppLogger.d("ReaderViewModel", "[PAGINATION_DEBUG] Chapter HTML: chapterIndex=$windowIndex, htmlLength=${html.length}, hasRawHtml=${content.html != null}")
            
            WindowHtmlPayload(
                html = html,
                windowIndex = windowIndex,
                chapterIndex = windowIndex,
                inPageIndex = 0,
                windowSize = 1,
                totalChapters = _pages.value.size
            )
        }
    }
    
    /**
     * Navigate to a specific window index (for RecyclerView navigation).
     * Updates the current window and the corresponding global page index.
     * 
     * @param windowIndex The target window index
     * @return true if navigation succeeded
     */
    fun goToWindow(windowIndex: Int): Boolean {
        val totalWindows = _windowCount.value
        val previousWindow = _currentWindowIndex.value
        
        AppLogger.d("ReaderViewModel", 
            "goToWindow called: windowIndex=$windowIndex, previousWindow=$previousWindow, " +
            "totalWindows=$totalWindows, paginationMode=$paginationMode [WINDOW_NAV_REQUEST]")
        
        if (totalWindows <= 0) {
            AppLogger.w("ReaderViewModel", 
                "goToWindow BLOCKED: totalWindows=$totalWindows (no windows available) [WINDOW_NAV_BLOCKED]")
            return false
        }
        
        if (windowIndex !in 0 until totalWindows) {
            AppLogger.w("ReaderViewModel", 
                "goToWindow BLOCKED: windowIndex=$windowIndex out of range [0, $totalWindows) [WINDOW_NAV_BLOCKED]")
            return false
        }
        
        _currentWindowIndex.value = windowIndex
        AppLogger.d("ReaderViewModel",
            "goToWindow: _currentWindowIndex updated from $previousWindow to $windowIndex [WINDOW_STATE_UPDATE]")
        
        // ConveyorBeltSystemViewModel handles all window management
        // No manual preloading needed - the conveyor system manages buffer shifts automatically
        
        if (isContinuousMode) {
            viewModelScope.launch {
                val paginator = continuousPaginator
                if (paginator == null) {
                    AppLogger.e("ReaderViewModel", 
                        "goToWindow ERROR: continuousPaginator is null in CONTINUOUS mode [PAGINATOR_NULL]")
                    return@launch
                }
                
                val windowInfo = paginator.getWindowInfo()
                val totalChapters = windowInfo.totalChapters
                
                // Guard against empty book
                if (totalChapters <= 0) {
                    AppLogger.w("ReaderViewModel", 
                        "goToWindow: no chapters available (totalChapters=0) [NO_CHAPTERS]")
                    return@launch
                }
                
                // Calculate the first chapter in this window
                val firstChapter = slidingWindowManager.firstChapterInWindow(windowIndex)
                    .coerceIn(0, totalChapters - 1)
                
                AppLogger.d("ReaderViewModel", 
                    "goToWindow: navigating to firstChapter=$firstChapter in window=$windowIndex " +
                    "(totalChapters=$totalChapters, chaptersPerWindow=$chaptersPerWindow) [CHAPTER_NAV]")
                
                // Navigate to the first chapter in this window
                val globalPage = paginator.navigateToChapter(firstChapter, 0)
                updateForGlobalPage(globalPage)
                
                AppLogger.d("ReaderViewModel", 
                    "goToWindow SUCCESS: windowIndex=$windowIndex -> firstChapter=$firstChapter, " +
                    "globalPage=$globalPage [WINDOW_NAV_SUCCESS]")
            }
        } else {
            // Chapter-based mode: window index equals chapter index
            AppLogger.d("ReaderViewModel", 
                "goToWindow: chapter-based mode, updating page to $windowIndex [CHAPTER_MODE]")
            updateCurrentPage(windowIndex)
        }
        
        return true
    }
    
    /**
     * Navigate to the next window.
     * @return true if navigation succeeded
     */
    fun nextWindow(): Boolean {
        val currentWindow = _currentWindowIndex.value
        return goToWindow(currentWindow + 1)
    }
    
    /**
     * Navigate to the previous window.
     * @return true if navigation succeeded
     */
    fun previousWindow(): Boolean {
        val currentWindow = _currentWindowIndex.value
        return goToWindow(currentWindow - 1)
    }
    
    /**
     * Get the window index for a given chapter.
     * This uses the same SlidingWindowManager instance used internally for consistency.
     * 
     * @param chapterIndex The chapter index
     * @return The window index containing this chapter
     */
    fun getWindowIndexForChapter(chapterIndex: Int): Int {
        return slidingWindowManager.windowForChapter(chapterIndex.coerceAtLeast(0))
    }
    
    /**
     * Recompute the window structure using the deterministic SlidingWindowPaginator.
     * This method ensures thread-safe updates to LiveData and adapter.
     *
     * @param totalChapters Total number of chapters in the book
     * @param adapter The RecyclerView.Adapter to notify of changes
     */
    fun recomputeWindowStructure(totalChapters: Int, adapter: RecyclerView.Adapter<*>) {
        paginationModeGuard.beginWindowBuild()
        try {
            val previousWindowCount = slidingWindowPaginator.getWindowCount()
            slidingWindowPaginator.recomputeWindows(totalChapters)
            val newWindowCount = slidingWindowPaginator.getWindowCount()
            
            AppLogger.d("ReaderViewModel", "[PAGINATION_DEBUG] rebuildSlidingWindows: " +
                "totalChapters=$totalChapters, " +
                "windowCount=$previousWindowCount->$newWindowCount, " +
                "chaptersPerWindow=$chaptersPerWindow")
            AppLogger.d("ReaderViewModel", "[PAGINATION_DEBUG] Window map: ${slidingWindowPaginator.debugWindowMap()}")
            
            // Debug invariant check (development builds only)
            // This duplicates the calculation intentionally to verify SlidingWindowPaginator's correctness
            val expectedWindowCount = if (totalChapters == 0) 0 else {
                kotlin.math.ceil(totalChapters.toDouble() / chaptersPerWindow).toInt()
            }
            val actualWindowCount = slidingWindowPaginator.getWindowCount()
            if (actualWindowCount != expectedWindowCount) {
                AppLogger.e("ReaderViewModel", "[PAGINATION_DEBUG] INVARIANT_VIOLATION: " +
                    "windowCount=$actualWindowCount != expected=$expectedWindowCount " +
                    "(totalChapters=$totalChapters, chaptersPerWindow=$chaptersPerWindow)")
            }
            
            WindowSyncHelpers.syncWindowCountToUi(slidingWindowPaginator, windowCountLiveData) {
                adapter.notifyDataSetChanged()
                AppLogger.d("ReaderViewModel", "[PAGINATION_DEBUG] Adapter notified after rebuildSlidingWindows")
            }
        } finally {
            paginationModeGuard.endWindowBuild()
        }
    }
    
    /**
     * Factory for creating ReaderViewModel instances with the necessary dependencies.
     */
    class Factory(
        private val bookId: String,
        private val bookFile: File,
        private val parser: BookParser,
        private val repository: BookRepository,
        private val readerPreferences: ReaderPreferences
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(ReaderViewModel::class.java)) {
                return ReaderViewModel(bookId, bookFile, parser, repository, readerPreferences) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}

private fun StringBuilder.isNotBlank(): Boolean = this.any { !it.isWhitespace() }

data class TtsHighlight(
    val pageIndex: Int,
    val range: IntRange?
)

data class StreamingChapterPayload(
    val chapterIndex: Int,
    val html: String
)

/**
 * Payload containing window HTML and associated metadata.
 */
data class WindowHtmlPayload(
    val html: String,
    val windowIndex: Int,
    val chapterIndex: Int,
    val inPageIndex: Int,
    val windowSize: Int,
    val totalChapters: Int
)
