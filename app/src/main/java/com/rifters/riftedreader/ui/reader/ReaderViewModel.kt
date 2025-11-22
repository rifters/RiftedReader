package com.rifters.riftedreader.ui.reader

import android.os.SystemClock
import android.text.TextUtils
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rifters.riftedreader.data.preferences.ReaderPreferences
import com.rifters.riftedreader.data.preferences.ReaderSettings
import com.rifters.riftedreader.data.repository.BookRepository
import com.rifters.riftedreader.domain.pagination.ContinuousPaginator
import com.rifters.riftedreader.domain.pagination.PageLocation
import com.rifters.riftedreader.domain.pagination.PaginationMode
import com.rifters.riftedreader.domain.parser.BookParser
import com.rifters.riftedreader.domain.parser.PageContent
import com.rifters.riftedreader.domain.parser.TxtParser
import com.rifters.riftedreader.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ReaderViewModel(
    // Expose bookId for debug logging
    val bookId: String,
    private val bookFile: File,
    private val parser: BookParser,
    private val repository: BookRepository,
    private val readerPreferences: ReaderPreferences
) : ViewModel() {
    
    private val _pages = MutableStateFlow<List<PageContent>>(emptyList())
    val pages: StateFlow<List<PageContent>> = _pages.asStateFlow()

    private val _content = MutableStateFlow(PageContent.EMPTY)
    val content: StateFlow<PageContent> = _content.asStateFlow()

    private val _currentPage = MutableStateFlow(0)
    val currentPage: StateFlow<Int> = _currentPage.asStateFlow()

    private val _totalPages = MutableStateFlow(0)
    val totalPages: StateFlow<Int> = _totalPages.asStateFlow()

    private val _highlight = MutableStateFlow<TtsHighlight?>(null)
    val highlight: StateFlow<TtsHighlight?> = _highlight.asStateFlow()

    private val _tableOfContents = MutableStateFlow<List<com.rifters.riftedreader.domain.parser.TocEntry>>(emptyList())
    val tableOfContents: StateFlow<List<com.rifters.riftedreader.domain.parser.TocEntry>> = _tableOfContents.asStateFlow()

    // Signal to fragments: when true, jump to last internal page after WebView loads
    private val _shouldJumpToLastPage = MutableStateFlow(false)
    val shouldJumpToLastPage: StateFlow<Boolean> = _shouldJumpToLastPage.asStateFlow()

    // WebView in-page navigation tracking (for PAGE mode with WebView)
    private val _currentWebViewPage = MutableStateFlow(0)
    val currentWebViewPage: StateFlow<Int> = _currentWebViewPage.asStateFlow()
    
    private val _totalWebViewPages = MutableStateFlow(0)
    val totalWebViewPages: StateFlow<Int> = _totalWebViewPages.asStateFlow()

    val readerSettings: StateFlow<ReaderSettings> = readerPreferences.settings

    private val pageContentCache = mutableMapOf<Int, MutableStateFlow<PageContent>>()
    private var continuousPaginator: ContinuousPaginator? = null
    private var isContinuousInitialized = false

    val paginationMode: PaginationMode
        get() {
            val settings = readerPreferences.settings.value
            val requestedMode = settings.paginationMode
            return if (requestedMode == PaginationMode.CONTINUOUS && !settings.continuousStreamingEnabled) {
                PaginationMode.CHAPTER_BASED
            } else {
                requestedMode
            }
        }

    private val isContinuousMode: Boolean
        get() = paginationMode == PaginationMode.CONTINUOUS

    class Factory(
        private val bookId: String,
        private val bookFile: File,
        private val parser: BookParser,
        private val repository: BookRepository,
        private val readerPreferences: ReaderPreferences
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ReaderViewModel::class.java)) {
                "Unknown ViewModel class ${modelClass.name}"
            }
            return ReaderViewModel(bookId, bookFile, parser, repository, readerPreferences) as T
        }
    }
    
    init {
        if (isContinuousMode) {
            initializeContinuousPagination()
        } else {
            buildPagination()
        }
        loadTableOfContents()
    }

    private fun loadTableOfContents() {
        viewModelScope.launch {
            try {
                val toc = withContext(Dispatchers.IO) {
                    parser.getTableOfContents(bookFile)
                }
                _tableOfContents.value = toc
            } catch (e: Exception) {
                _tableOfContents.value = emptyList()
                e.printStackTrace()
            }
        }
    }

    private fun buildPagination() {
        viewModelScope.launch {
            try {
                val book = repository.getBookById(bookId)
                val savedPage = book?.currentPage ?: 0
                
                // If parser is EpubParser and we have a cover path, set it
                if (parser is com.rifters.riftedreader.domain.parser.EpubParser && book?.coverPath != null) {
                    AppLogger.d("ReaderViewModel", "[COVER_DEBUG] Setting cover path for EPUB: ${book.coverPath}")
                    val coverFile = java.io.File(book.coverPath)
                    AppLogger.d("ReaderViewModel", "[COVER_DEBUG] Cover file exists: ${coverFile.exists()}, canRead: ${coverFile.canRead()}, size: ${coverFile.length()} bytes")
                    parser.setCoverPath(book.coverPath)
                } else if (parser is com.rifters.riftedreader.domain.parser.EpubParser) {
                    AppLogger.d("ReaderViewModel", "[COVER_DEBUG] No cover path available for EPUB (book.coverPath is null)")
                }

                val pages = withContext(Dispatchers.IO) { generatePages() }

                _pages.value = pages
                _totalPages.value = pages.size

                pages.forEachIndexed { index, content ->
                    pageContentCache.getOrPut(index) { MutableStateFlow(PageContent.EMPTY) }.value = content
                }

                val initialPage = if (pages.isNotEmpty()) {
                    savedPage.coerceIn(0, pages.lastIndex)
                } else {
                    0
                }
                _currentPage.value = initialPage
                _content.value = pages.getOrNull(initialPage) ?: PageContent.EMPTY
            } catch (e: Exception) {
                _pages.value = emptyList()
                _totalPages.value = 0
                _currentPage.value = 0
                _content.value = PageContent(text = "Error loading content: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    private fun initializeContinuousPagination() {
        viewModelScope.launch {
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
                isContinuousInitialized = true

                // Try to restore position using chapter + in-page index first
                val initialGlobalPage = paginator.navigateToChapter(startChapter, startInPage)

                updateForGlobalPage(initialGlobalPage)
                
                AppLogger.d("ReaderViewModel", "Restored position: chapter=$startChapter, inPage=$startInPage, globalPage=$initialGlobalPage")
            } catch (e: Exception) {
                AppLogger.e("ReaderViewModel", "Failed to initialize continuous paginator", e)
                _pages.value = emptyList()
                _totalPages.value = 0
                _currentPage.value = 0
                _content.value = PageContent(text = "Error loading content: ${e.message}")
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
     * Get the window HTML for a specific page/chapter index.
     * In continuous mode, this returns a sliding window combining multiple chapters.
     * In chapter-based mode, this returns just the single chapter HTML.
     * 
     * @param pageIndex The global page index (continuous) or chapter index (chapter-based)
     * @return WindowHtmlPayload containing the window HTML and metadata, or null if unavailable
     */
    suspend fun getWindowHtml(pageIndex: Int): WindowHtmlPayload? {
        return if (isContinuousMode) {
            val paginator = continuousPaginator ?: return null
            val windowManager = com.rifters.riftedreader.domain.pagination.SlidingWindowManager(windowSize = 5)
            val provider = com.rifters.riftedreader.domain.pagination.ContinuousPaginatorWindowHtmlProvider(
                paginator,
                windowManager
            )
            
            // Get the page location to determine chapter and window
            val location = paginator.getPageLocation(pageIndex)
            if (location == null) {
                AppLogger.w("ReaderViewModel", "Could not get page location for index $pageIndex")
                return null
            }
            
            val windowIndex = windowManager.windowForChapter(location.chapterIndex)
            val windowInfo = paginator.getWindowInfo()
            
            AppLogger.d("ReaderViewModel", "Getting window HTML: pageIndex=$pageIndex, chapterIndex=${location.chapterIndex}, windowIndex=$windowIndex")
            
            val html = provider.getWindowHtml(bookId, windowIndex)
            if (html == null) {
                AppLogger.w("ReaderViewModel", "Window HTML provider returned null for window $windowIndex")
                return null
            }
            
            WindowHtmlPayload(
                html = html,
                windowIndex = windowIndex,
                chapterIndex = location.chapterIndex,
                inPageIndex = location.inPageIndex,
                windowSize = 5,
                totalChapters = windowInfo.totalChapters
            )
        } else {
            // Chapter-based mode: return single chapter HTML
            val content = _pages.value.getOrNull(pageIndex)
            if (content == null) {
                AppLogger.w("ReaderViewModel", "No content for page index $pageIndex")
                return null
            }
            
            val html = content.html ?: wrapPlainTextAsHtml(content.text)
            WindowHtmlPayload(
                html = html,
                windowIndex = 0,
                chapterIndex = pageIndex,
                inPageIndex = 0,
                windowSize = 1,
                totalChapters = _pages.value.size
            )
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
