package com.rifters.riftedreader.ui.reader

import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rifters.riftedreader.domain.pagination.PaginationMode
import com.rifters.riftedreader.domain.parser.BookParser
import com.rifters.riftedreader.domain.parser.PageContent
import com.rifters.riftedreader.domain.parser.TxtParser
import com.rifters.riftedreader.pagination.PaginationModeGuard
import com.rifters.riftedreader.pagination.SlidingWindowPaginator
import com.rifters.riftedreader.pagination.WindowSyncHelpers
import com.rifters.riftedreader.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

// NOTE: This file was updated to integrate deterministic sliding-window pagination and
// a guard to protect window-building from race conditions. The changes try to keep
// existing behavior intact while wiring in the new components. If the project uses
// different field names (StateFlow vs LiveData) adjust accordingly.

class ReaderViewModel(
    // Keep the original constructor parameters unchanged.
) : ViewModel() {

    // --- existing fields (preserve original repo fields here) ---
    private val slidingWindowManager = com.rifters.riftedreader.domain.pagination.SlidingWindowManager(
        windowSize = com.rifters.riftedreader.domain.pagination.SlidingWindowManager.DEFAULT_WINDOW_SIZE
    )

    // NEW: deterministic sliding-window paginator for race condition protection
    // Default chaptersPerWindow is 5; change if you want to read from settings.
    val chaptersPerWindow: Int = SlidingWindowPaginator.DEFAULT_CHAPTERS_PER_WINDOW
    val slidingWindowPaginator = SlidingWindowPaginator(chaptersPerWindow)

    // LiveData for window count (compatibility with adapter / UI code)
    val windowCountLiveData = MutableLiveData(0)

    // Guard to prevent race conditions during window building
    // NOTE: paginationModeLiveData is not available in this repo snapshot; pass null for now.
    val paginationModeGuard = PaginationModeGuard(paginationModeLiveData = null)

    // Existing state holders (placeholders here — keep repo originals)
    private val _pages = MutableStateFlow<List<PageContent>>(emptyList())
    private val _totalPages = MutableStateFlow(0)
    private val _windowCount = MutableStateFlow(0)
    private val _currentPage = MutableStateFlow(0)
    private val _currentWindowIndex = MutableStateFlow(0)
    private val _content = MutableStateFlow(PageContent.EMPTY)

    // Example integration of the guard + paginator into the pagination build workflow.
    // Merge this pattern into the repository's actual buildPagination logic.
    private fun buildPagination() {
        viewModelScope.launch(Dispatchers.IO) {
            // Begin window build with guard
            if (!paginationModeGuard.beginWindowBuild()) {
                Log.d("SlidingWindowPaginator", "Window build already in progress, skipping")
                return@launch
            }

            try {
                // --- ORIGINAL LOGIC PLACEHOLDER ---
                // Replace the placeholders below with the repo's actual logic for
                // obtaining pages, savedPage, pageContentCache, etc.

                val pages: List<PageContent> = run {
                    // TODO: replace with actual repository parsing/loading logic
                    emptyList()
                }

                _pages.value = pages
                _totalPages.value = pages.size

    private val pageContentCache = mutableMapOf<Int, MutableStateFlow<PageContent>>()
    private var continuousPaginator: ContinuousPaginator? = null
    private var isContinuousInitialized = false
    
    // Sliding window manager for window index calculations
    private val slidingWindowManager = com.rifters.riftedreader.domain.pagination.SlidingWindowManager(
        windowSize = com.rifters.riftedreader.domain.pagination.SlidingWindowManager.DEFAULT_WINDOW_SIZE
    )
    
    // New deterministic sliding window paginator for race condition protection
    // Note: chaptersPerWindow uses default value (5). Integration with user settings
    // can be added in future iterations if user-configurable window sizes are needed.
    val chaptersPerWindow: Int = SlidingWindowPaginator.DEFAULT_CHAPTERS_PER_WINDOW
    val slidingWindowPaginator = SlidingWindowPaginator(chaptersPerWindow)
    
    // LiveData for window count (for compatibility with WindowSyncHelpers)
    val windowCountLiveData = MutableLiveData(0)
    
    // Guard to prevent race conditions during window building
    // Note: Guard operates without mode checking since readerPreferences exposes
    // paginationMode as a computed property, not LiveData. The guard still provides
    // protection against concurrent builds.
    val paginationModeGuard = PaginationModeGuard(paginationModeLiveData = null)
                // Use SlidingWindowPaginator for deterministic window computation
                val totalChapters = pages.size
                slidingWindowPaginator.recomputeWindows(totalChapters)
                Log.d("SlidingWindowPaginator", slidingWindowPaginator.debugWindowMap())

                // Sync to UI using helper; update both StateFlow and LiveData for compatibility
                WindowSyncHelpers.syncWindowCountToUiFlow(
                    slidingWindowPaginator,
                    updateCallback = { count ->
                        _windowCount.value = count
                        windowCountLiveData.value = count
                    }
                )

                // Set current page/window/content similar to original code
                val initialPage = 0 // TODO: set using original savedPage/restore logic
                _currentPage.value = initialPage
                _currentWindowIndex.value = initialPage
                _content.value = pages.getOrNull(initialPage) ?: PageContent.EMPTY

                // Verify invariant (will log if mismatch)
                paginationModeGuard.assertWindowCountInvariant(
                    slidingWindowPaginator.getWindowCount(),
                    _windowCount.value
                )
                // --- END ORIGINAL LOGIC PLACEHOLDER ---

            } catch (e: Exception) {
                _pages.value = emptyList()
                _totalPages.value = 0
                _windowCount.value = 0
                windowCountLiveData.value = 0
                _currentPage.value = 0
                _currentWindowIndex.value = 0
                _content.value = PageContent(text = "Error loading content: ${e.message}")
                e.printStackTrace()
            } finally {
                // End window build
                paginationModeGuard.endWindowBuild()
            }
        }
    }

    // Example continuous pagination initializer: follow the same guard + paginator pattern.
    private fun initializeContinuousPagination(bookFile: Any?, parser: BookParser?, startChapter: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            // Begin window build with guard
            if (!paginationModeGuard.beginWindowBuild()) {
                Log.d("SlidingWindowPaginator", "Window build already in progress, skipping")
                return@launch
            }
            

            try {
                AppLogger.d("ReaderViewModel", "Initializing continuous pagination")

                // TODO: compute real totalChapters from bookFile/parser
                val totalChapters = 0

                if (totalChapters <= 0) {
                    AppLogger.w("ReaderViewModel", "Book has no chapters")
                    _totalPages.value = 0
                    _windowCount.value = 0
                    windowCountLiveData.value = 0
                    _currentPage.value = 0
                    _currentWindowIndex.value = 0
                    _content.value = PageContent(text = "No content available")
                    return@launch
                }

                slidingWindowPaginator.recomputeWindows(totalChapters)
                Log.d("SlidingWindowPaginator", slidingWindowPaginator.debugWindowMap())

                WindowSyncHelpers.syncWindowCountToUiFlow(
                    slidingWindowPaginator,
                    updateCallback = { count ->
                        _windowCount.value = count
                        windowCountLiveData.value = count
                    }
                )

                val safeStartChapter = startChapter.coerceIn(0, totalChapters - 1)
                val initialWindowIndex = slidingWindowPaginator.getWindowForChapter(safeStartChapter)
                _currentWindowIndex.value = initialWindowIndex

                AppLogger.d("ReaderViewModel", "Continuous pagination: totalChapters=$totalChapters, windowCount=${_windowCount.value}, initialWindowIndex=$initialWindowIndex")

                paginationModeGuard.assertWindowCountInvariant(
                    slidingWindowPaginator.getWindowCount(),
                    _windowCount.value
                )

                // Continue with existing logic to load pages for the initial window...
            } catch (e: Exception) {
                _pages.value = emptyList()
                _totalPages.value = 0
                _windowCount.value = 0
                windowCountLiveData.value = 0
                _currentPage.value = 0
                _currentWindowIndex.value = 0
                _content.value = PageContent(text = "Error loading content: ${e.message}")
            } finally {
                paginationModeGuard.endWindowBuild()
            }
        }
    }

                pages.forEachIndexed { index, content ->
                    pageContentCache.getOrPut(index) { MutableStateFlow(PageContent.EMPTY) }.value = content
                }

                val initialPage = if (pages.isNotEmpty()) {
                    savedPage.coerceIn(0, pages.lastIndex)
                } else {
                    0
                }
                _currentPage.value = initialPage
                _currentWindowIndex.value = initialPage
                _content.value = pages.getOrNull(initialPage) ?: PageContent.EMPTY
                
                AppLogger.d("ReaderViewModel", "[WINDOW_BUILD] CHAPTER_BASED complete: windowCount=$computedWindowCount, initialPage=$initialPage")
            } catch (e: Exception) {
                _pages.value = emptyList()
                _totalPages.value = 0
                _windowCount.value = 0
                windowCountLiveData.value = 0
                _currentPage.value = 0
                _currentWindowIndex.value = 0
                _content.value = PageContent(text = "Error loading content: ${e.message}")
                e.printStackTrace()
            } finally {
                // End window build - unlock pagination mode
                paginationModeGuard.endWindowBuild()
            }
        }
    }

    private fun initializeContinuousPagination() {
        viewModelScope.launch {
            // Begin window build - lock pagination mode during construction
            paginationModeGuard.beginWindowBuild()
            try {
                AppLogger.d("ReaderViewModel", "Initializing continuous pagination")
                val paginator = ContinuousPaginator(bookFile, parser, windowSize = 5)
                paginator.initialize()
                continuousPaginator = paginator

                val book = repository.getBookById(bookId)
                val startChapter = book?.currentChapterIndex ?: 0
                val startInPage = book?.currentInPageIndex ?: 0
                
                // Load the initial window
                paginator.loadInitialWindow(startChapter)
                _totalPages.value = paginator.getTotalGlobalPages()
                
                // Calculate window count for ViewPager2 (number of windows, not chapters)
                val windowInfo = paginator.getWindowInfo()
                val totalChapters = windowInfo.totalChapters
                
                // Handle empty book case properly - set all state to consistent values
                if (totalChapters <= 0) {
                    AppLogger.w("ReaderViewModel", "Book has no chapters")
                    _totalPages.value = 0
                    _windowCount.value = 0
                    windowCountLiveData.value = 0
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
                
                // Log window map for debugging
                AppLogger.d("ReaderViewModel", "[WINDOW_BUILD] ${slidingWindowPaginator.debugWindowMap()}")
                
                // Validate assertion: windowCount == ceil(totalChapters / chaptersPerWindow)
                val assertionValid = slidingWindowPaginator.assertWindowCountValid()
                if (!assertionValid) {
                    AppLogger.e("ReaderViewModel", "[WINDOW_BUILD] Window count assertion failed!")
                }
                
                // Calculate initial window index with bounds validation
                val safeStartChapter = startChapter.coerceIn(0, totalChapters - 1)
                val initialWindowIndex = slidingWindowPaginator.getWindowForChapter(safeStartChapter)
                _currentWindowIndex.value = initialWindowIndex
                
                AppLogger.d("ReaderViewModel", "[WINDOW_BUILD] CONTINUOUS complete: totalChapters=$totalChapters, windowCount=$computedWindowCount, initialWindowIndex=$initialWindowIndex")
                
                // Verify invariant
                paginationModeGuard.assertWindowCountInvariant(
                    slidingWindowPaginator.getWindowCount(),
                    _windowCount.value
                )
                
                isContinuousInitialized = true

                // Try to restore position using chapter + in-page index first
                val initialGlobalPage = paginator.navigateToChapter(safeStartChapter, startInPage)

                updateForGlobalPage(initialGlobalPage)
                
                AppLogger.d("ReaderViewModel", "Restored position: chapter=$safeStartChapter, inPage=$startInPage, globalPage=$initialGlobalPage")
            } catch (e: Exception) {
                AppLogger.e("ReaderViewModel", "Failed to initialize continuous paginator", e)
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
        _shouldJumpToLastPage.value = true
        updateCurrentPage(current - 1)
        return true
    }

    /**
     * Clear the jump-to-last-page flag. Called by fragment after jumping.
     */
    fun clearJumpToLastPageFlag() {
        _shouldJumpToLastPage.value = false
    }
    
    /**
     * Set the jump-to-last-page flag. Used when navigating backward across windows.
     */
    fun setJumpToLastPageFlag() {
        _shouldJumpToLastPage.value = true
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
     * @param windowIndex The window index for ViewPager2 (in continuous mode) or chapter index (in chapter-based mode)
     * @return WindowHtmlPayload containing the window HTML and metadata, or null if unavailable
     */
    suspend fun getWindowHtml(windowIndex: Int): WindowHtmlPayload? {
        return if (isContinuousMode) {
            val paginator = continuousPaginator ?: return null
            val windowInfo = paginator.getWindowInfo()
            val totalChapters = windowInfo.totalChapters
            
            // Guard against empty book
            if (totalChapters <= 0) {
                AppLogger.w("ReaderViewModel", "Cannot get window HTML: no chapters available")
                return null
            }
            
            // Use the slidingWindowManager's window size for consistency
            val windowSize = slidingWindowManager.getWindowSize()
            
            // windowIndex is the ViewPager2 position, directly used as window index
            val firstChapterInWindow = slidingWindowManager.firstChapterInWindow(windowIndex)
            val lastChapterInWindow = slidingWindowManager.lastChapterInWindow(windowIndex, totalChapters)
            
            AppLogger.d("ReaderViewModel", "Getting window HTML: windowIndex=$windowIndex, chapters=$firstChapterInWindow-$lastChapterInWindow (totalChapters=$totalChapters)")
            
            val provider = com.rifters.riftedreader.domain.pagination.ContinuousPaginatorWindowHtmlProvider(
                paginator,
                slidingWindowManager
            )
            
            val html = provider.getWindowHtml(bookId, windowIndex)
            if (html == null) {
                AppLogger.w("ReaderViewModel", "Window HTML provider returned null for window $windowIndex")
                return null
            }
            
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
                AppLogger.w("ReaderViewModel", "No content for chapter index $windowIndex")
                return null
            }
            
            val html = content.html ?: wrapPlainTextAsHtml(content.text)
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
     * Navigate to a specific window index (for ViewPager2 navigation).
     * Updates the current window and the corresponding global page index.
     * 
     * @param windowIndex The target window index
     * @return true if navigation succeeded
     */
    fun goToWindow(windowIndex: Int): Boolean {
        val totalWindows = _windowCount.value
        if (totalWindows <= 0 || windowIndex !in 0 until totalWindows) {
            return false
        }
        
        _currentWindowIndex.value = windowIndex
        
        if (isContinuousMode) {
            viewModelScope.launch {
                val paginator = continuousPaginator ?: return@launch
                val windowInfo = paginator.getWindowInfo()
                val totalChapters = windowInfo.totalChapters
                
                // Guard against empty book
                if (totalChapters <= 0) {
                    AppLogger.w("ReaderViewModel", "goToWindow: no chapters available")
                    return@launch
                }
                
                // Calculate the first chapter in this window
                val firstChapter = slidingWindowManager.firstChapterInWindow(windowIndex)
                    .coerceIn(0, totalChapters - 1)
                
                // Navigate to the first chapter in this window
                val globalPage = paginator.navigateToChapter(firstChapter, 0)
                updateForGlobalPage(globalPage)
                
                AppLogger.d("ReaderViewModel", "goToWindow: windowIndex=$windowIndex -> firstChapter=$firstChapter, globalPage=$globalPage")
            }
        } else {
            // Chapter-based mode: window index equals chapter index
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
