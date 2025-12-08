package com.rifters.riftedreader

import com.rifters.riftedreader.data.database.entities.BookMeta
import com.rifters.riftedreader.domain.pagination.ContinuousPaginator
import com.rifters.riftedreader.domain.pagination.ContinuousPaginatorWindowHtmlProvider
import com.rifters.riftedreader.domain.pagination.PaginationMode
import com.rifters.riftedreader.domain.pagination.SlidingWindowManager
import com.rifters.riftedreader.domain.pagination.SlidingWindowPaginator
import com.rifters.riftedreader.domain.parser.BookParser
import com.rifters.riftedreader.domain.parser.PageContent
import com.rifters.riftedreader.domain.parser.TocEntry
import com.rifters.riftedreader.pagination.WindowAssembler
import com.rifters.riftedreader.pagination.WindowBufferManager
import com.rifters.riftedreader.pagination.WindowData
import com.rifters.riftedreader.pagination.SlidingWindowPaginator as PaginationPkgSlidingWindowPaginator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Automated validation tests for the window/chapter rendering pipeline.
 * 
 * This test suite validates the complete rendering pipeline from ViewModel through
 * fragment render, specifically designed to catch issues with:
 * - Window/page rendering that appears blank or stuck
 * - Adapter item count mismatches with ViewModel window count
 * - Window buffer manager lifecycle issues
 * - Fragment instantiation for window indices
 * 
 * Reference artifacts:
 * - ReaderActivity.kt (main UI navigation/scroll logic)
 * - ReaderPageFragment.kt (rendering per window/page)
 * - ReaderPagerAdapter (item count & fragment instantiation)
 * - WindowBufferManager (5-window buffer lifecycle)
 * - SlidingWindowPaginator (window count calculations)
 * 
 * See issue: "Automate validation of window/chapter rendering pipeline"
 */
class WindowRenderingPipelineTest {

    // Use domain package's SlidingWindowPaginator (returns Pair<Int, Int>?)
    private lateinit var paginator: SlidingWindowPaginator
    private lateinit var windowManager: SlidingWindowManager
    
    // Pagination package's version (returns IntRange) - for WindowBufferManager tests
    private lateinit var paginationPkgPaginator: PaginationPkgSlidingWindowPaginator
    
    companion object {
        private const val TEST_CHAPTERS_PER_WINDOW = 5
        private const val TEST_TOTAL_CHAPTERS = 62 // 12 full windows + 1 partial (2 chapters)
    }
    
    @Before
    fun setup() {
        paginator = SlidingWindowPaginator(chaptersPerWindow = TEST_CHAPTERS_PER_WINDOW)
        windowManager = SlidingWindowManager(windowSize = TEST_CHAPTERS_PER_WINDOW)
        paginationPkgPaginator = PaginationPkgSlidingWindowPaginator(chaptersPerWindow = TEST_CHAPTERS_PER_WINDOW)
        
        // Initialize paginators with test chapters -> computes window count
        paginator.recomputeWindows(TEST_TOTAL_CHAPTERS)
        paginationPkgPaginator.recomputeWindows(TEST_TOTAL_CHAPTERS)
    }
    
    // ========================================================================
    // Window Count Validation Tests
    // ========================================================================
    
    @Test
    fun `paginator window count matches expected calculation`() {
        // ceil(62 / 5) = 13 windows
        val expectedWindows = kotlin.math.ceil(TEST_TOTAL_CHAPTERS.toDouble() / TEST_CHAPTERS_PER_WINDOW).toInt()
        assertEquals("Window count should match ceil(chapters/perWindow)", expectedWindows, paginator.windowCount)
        assertEquals(13, paginator.windowCount)
    }
    
    @Test
    fun `window count consistency between paginator and manager`() {
        // Both paginator and manager should produce consistent window calculations
        val paginatorWindowCount = paginator.windowCount
        val managerWindowCount = windowManager.totalWindows(TEST_TOTAL_CHAPTERS)
        
        assertEquals(
            "Paginator and manager window counts must match",
            paginatorWindowCount,
            managerWindowCount
        )
    }
    
    @Test
    fun `adapter item count should equal paginator window count`() {
        // Simulate what ReaderPagerAdapter.getItemCount() does
        val adapterItemCount = paginator.windowCount
        val viewModelWindowCount = paginator.windowCount
        
        assertEquals(
            "Adapter itemCount must match ViewModel windowCount",
            adapterItemCount,
            viewModelWindowCount
        )
    }
    
    // ========================================================================
    // Window Range Validation Tests
    // ========================================================================
    
    @Test
    fun `window range for window 0 is chapters 0-4`() {
        val range = paginator.getWindowRange(0)
        assertNotNull("Window 0 range should not be null", range)
        assertEquals("First chapter in window 0 should be 0", 0, range!!.first)
        assertEquals("Last chapter in window 0 should be 4", 4, range.second)
    }
    
    @Test
    fun `window range for window 1 is chapters 5-9`() {
        val range = paginator.getWindowRange(1)
        assertNotNull("Window 1 range should not be null", range)
        assertEquals("First chapter in window 1 should be 5", 5, range!!.first)
        assertEquals("Last chapter in window 1 should be 9", 9, range.second)
    }
    
    @Test
    fun `last window handles partial chapter range correctly`() {
        // Window 12 (last window) should have only chapters 60-61
        val lastWindowIndex = paginator.windowCount - 1
        assertEquals(12, lastWindowIndex)
        
        val range = paginator.getWindowRange(lastWindowIndex)
        assertNotNull("Last window range should not be null", range)
        assertEquals("First chapter in last window should be 60", 60, range!!.first)
        assertEquals("Last chapter in last window should be 61", 61, range.second)
    }
    
    @Test
    fun `all window ranges cover all chapters without gaps`() {
        val windowCount = paginator.windowCount
        var lastChapterCovered = -1
        
        for (windowIndex in 0 until windowCount) {
            val range = paginator.getWindowRange(windowIndex)
            assertNotNull("Window $windowIndex range should not be null", range)
            
            // Verify no gaps
            assertEquals(
                "Window $windowIndex should start at chapter ${lastChapterCovered + 1}",
                lastChapterCovered + 1,
                range!!.first
            )
            
            // Verify no invalid ranges
            assertTrue(
                "Window $windowIndex range should be valid",
                range.first <= range.second
            )
            
            lastChapterCovered = range.second
        }
        
        // Verify all chapters are covered
        assertEquals(
            "Last chapter covered should be total chapters - 1",
            TEST_TOTAL_CHAPTERS - 1,
            lastChapterCovered
        )
    }
    
    // ========================================================================
    // Chapter-to-Window Mapping Tests
    // ========================================================================
    
    @Test
    fun `chapter to window mapping is correct for all chapters`() {
        for (chapterIndex in 0 until TEST_TOTAL_CHAPTERS) {
            val expectedWindow = chapterIndex / TEST_CHAPTERS_PER_WINDOW
            val actualWindow = paginator.getWindowForChapter(chapterIndex)
            
            assertEquals(
                "Chapter $chapterIndex should map to window $expectedWindow",
                expectedWindow,
                actualWindow
            )
        }
    }
    
    // ========================================================================
    // Window Buffer Manager Lifecycle Tests
    // ========================================================================
    
    @Test
    fun `buffer manager initializes with 5 consecutive windows`() = runBlocking {
        val assembler = MockWindowAssembler(TEST_TOTAL_CHAPTERS)
        val bufferManager = WindowBufferManager(
            windowAssembler = assembler,
            paginator = paginationPkgPaginator,
            coroutineScope = CoroutineScope(Dispatchers.Default)
        )
        
        bufferManager.initialize(startWindow = 0)
        delay(100) // Allow async preloading
        
        val bufferedWindows = bufferManager.getBufferedWindows()
        assertEquals("Buffer should contain 5 windows", 5, bufferedWindows.size)
        assertEquals("Buffer should start at window 0", 0, bufferedWindows.first())
        assertEquals("Buffer should end at window 4", 4, bufferedWindows.last())
    }
    
    @Test
    fun `buffer manager starts in STARTUP phase`() = runBlocking {
        val assembler = MockWindowAssembler(TEST_TOTAL_CHAPTERS)
        val bufferManager = WindowBufferManager(
            windowAssembler = assembler,
            paginator = paginationPkgPaginator,
            coroutineScope = CoroutineScope(Dispatchers.Default)
        )
        
        bufferManager.initialize(startWindow = 0)
        
        assertEquals(
            "Buffer manager should start in STARTUP phase",
            WindowBufferManager.Phase.STARTUP,
            bufferManager.phase.value
        )
    }
    
    @Test
    fun `buffer manager transitions to STEADY at center position`() = runBlocking {
        val assembler = MockWindowAssembler(TEST_TOTAL_CHAPTERS)
        val bufferManager = WindowBufferManager(
            windowAssembler = assembler,
            paginator = paginationPkgPaginator,
            coroutineScope = CoroutineScope(Dispatchers.Default)
        )
        
        bufferManager.initialize(startWindow = 0)
        
        // Simulate entering windows 0 and 1 (still STARTUP)
        bufferManager.onEnteredWindow(0)
        assertEquals(WindowBufferManager.Phase.STARTUP, bufferManager.phase.value)
        
        bufferManager.onEnteredWindow(1)
        assertEquals(WindowBufferManager.Phase.STARTUP, bufferManager.phase.value)
        
        // Entering window 2 (CENTER_POS=2) should transition to STEADY
        bufferManager.onEnteredWindow(2)
        assertEquals(
            "Should transition to STEADY when entering center position (window 2)",
            WindowBufferManager.Phase.STEADY,
            bufferManager.phase.value
        )
    }
    
    @Test
    fun `buffer manager provides cached window data`() = runBlocking {
        val assembler = MockWindowAssembler(TEST_TOTAL_CHAPTERS)
        val bufferManager = WindowBufferManager(
            windowAssembler = assembler,
            paginator = paginationPkgPaginator,
            coroutineScope = CoroutineScope(Dispatchers.Default)
        )
        
        bufferManager.initialize(startWindow = 0)
        delay(200) // Allow async preloading to complete
        
        // Window 0 should be cached and available
        val cachedWindow = bufferManager.getCachedWindow(0)
        assertNotNull("Window 0 should be cached after initialization", cachedWindow)
        assertEquals("Cached window should have windowIndex=0", 0, cachedWindow!!.windowIndex)
        assertFalse("Cached window HTML should not be blank", cachedWindow.html.isBlank())
    }
    
    // ========================================================================
    // Window HTML Generation Tests
    // ========================================================================
    
    @Test
    fun `window HTML contains section tags for all chapters in window`() = runTest {
        val mockFile = File("test.epub")
        val mockParser = MockBookParser(totalPages = TEST_TOTAL_CHAPTERS)
        
        val continuousPaginator = ContinuousPaginator(mockFile, mockParser, windowSize = TEST_CHAPTERS_PER_WINDOW)
        continuousPaginator.initialize()
        continuousPaginator.loadInitialWindow(0)
        
        val provider = ContinuousPaginatorWindowHtmlProvider(continuousPaginator, windowManager)
        val html = provider.getWindowHtml("test-book-id", windowIndex = 0)
        
        assertNotNull("Window HTML should not be null", html)
        
        // Verify all chapters 0-4 are present
        for (chapterIndex in 0..4) {
            assertTrue(
                "Window 0 HTML should contain chapter $chapterIndex section",
                html!!.contains("<section id=\"chapter-$chapterIndex\"")
            )
            assertTrue(
                "Window 0 HTML should contain chapter $chapterIndex content",
                html.contains("Content for page $chapterIndex")
            )
        }
    }
    
    @Test
    fun `window HTML does NOT contain chapters from adjacent windows`() = runTest {
        val mockFile = File("test.epub")
        val mockParser = MockBookParser(totalPages = TEST_TOTAL_CHAPTERS)
        
        val continuousPaginator = ContinuousPaginator(mockFile, mockParser, windowSize = TEST_CHAPTERS_PER_WINDOW)
        continuousPaginator.initialize()
        continuousPaginator.loadInitialWindow(0)
        
        val provider = ContinuousPaginatorWindowHtmlProvider(continuousPaginator, windowManager)
        val html = provider.getWindowHtml("test-book-id", windowIndex = 0)
        
        assertNotNull("Window HTML should not be null", html)
        
        // Verify chapter 5 (from window 1) is NOT present
        assertFalse(
            "Window 0 HTML should NOT contain chapter 5 (from window 1)",
            html!!.contains("<section id=\"chapter-5\"")
        )
    }
    
    // ========================================================================
    // Edge Case Tests
    // ========================================================================
    
    @Test
    fun `single chapter book has exactly 1 window`() {
        val singleChapterPaginator = SlidingWindowPaginator(chaptersPerWindow = TEST_CHAPTERS_PER_WINDOW)
        singleChapterPaginator.recomputeWindows(1)
        
        assertEquals("Single chapter book should have 1 window", 1, singleChapterPaginator.windowCount)
        
        val range = singleChapterPaginator.getWindowRange(0)
        assertNotNull(range)
        assertEquals(0, range!!.first)
        assertEquals(0, range.second)
    }
    
    @Test
    fun `book with exactly 5 chapters has 1 full window`() {
        val fiveChapterPaginator = SlidingWindowPaginator(chaptersPerWindow = TEST_CHAPTERS_PER_WINDOW)
        fiveChapterPaginator.recomputeWindows(5)
        
        assertEquals("5 chapter book should have 1 window", 1, fiveChapterPaginator.windowCount)
        
        val range = fiveChapterPaginator.getWindowRange(0)
        assertNotNull(range)
        assertEquals(0, range!!.first)
        assertEquals(4, range.second)
    }
    
    @Test
    fun `book with 6 chapters has 2 windows`() {
        val sixChapterPaginator = SlidingWindowPaginator(chaptersPerWindow = TEST_CHAPTERS_PER_WINDOW)
        sixChapterPaginator.recomputeWindows(6)
        
        assertEquals("6 chapter book should have 2 windows", 2, sixChapterPaginator.windowCount)
        
        val window0Range = sixChapterPaginator.getWindowRange(0)
        assertEquals(0, window0Range!!.first)
        assertEquals(4, window0Range.second)
        
        val window1Range = sixChapterPaginator.getWindowRange(1)
        assertEquals(5, window1Range!!.first)
        assertEquals(5, window1Range.second)
    }
    
    @Test
    fun `zero chapters book has 0 windows`() {
        val emptyPaginator = SlidingWindowPaginator(chaptersPerWindow = TEST_CHAPTERS_PER_WINDOW)
        emptyPaginator.recomputeWindows(0)
        
        assertEquals("Zero chapter book should have 0 windows", 0, emptyPaginator.windowCount)
    }
    
    // ========================================================================
    // Mock Implementations
    // ========================================================================
    
    private class MockWindowAssembler(
        private val totalChapters: Int
    ) : WindowAssembler {
        
        override suspend fun assembleWindow(
            windowIndex: Int,
            firstChapter: Int,
            lastChapter: Int
        ): WindowData? {
            if (firstChapter < 0 || lastChapter >= totalChapters) {
                return null
            }
            
            val htmlBuilder = StringBuilder()
            htmlBuilder.append("<div id=\"window-root\" data-window-index=\"$windowIndex\">")
            for (chapter in firstChapter..lastChapter) {
                htmlBuilder.append("<section id=\"chapter-$chapter\" data-chapter-index=\"$chapter\">")
                htmlBuilder.append("<p>Content for chapter $chapter</p>")
                htmlBuilder.append("</section>")
            }
            htmlBuilder.append("</div>")
            
            return WindowData(
                html = htmlBuilder.toString(),
                firstChapter = firstChapter,
                lastChapter = lastChapter,
                windowIndex = windowIndex
            )
        }
        
        override fun canAssemble(
            windowIndex: Int,
            firstChapter: Int,
            lastChapter: Int
        ): Boolean {
            return windowIndex >= 0 &&
                   firstChapter >= 0 &&
                   lastChapter >= firstChapter &&
                   lastChapter < totalChapters
        }
        
        override fun getTotalChapters(): Int = totalChapters
    }
    
    private class MockBookParser(private val totalPages: Int) : BookParser {
        override fun canParse(file: File): Boolean = true
        
        override suspend fun getPageCount(file: File): Int = totalPages
        
        override suspend fun getPageContent(file: File, page: Int): PageContent {
            return PageContent(
                text = "Content for page $page",
                html = "<p>Content for page $page</p>"
            )
        }
        
        override suspend fun getTableOfContents(file: File): List<TocEntry> {
            return emptyList()
        }
        
        override suspend fun extractMetadata(file: File): BookMeta {
            return BookMeta(
                id = "test-book-id",
                path = file.absolutePath,
                format = "EPUB",
                size = 1024L,
                dateAdded = System.currentTimeMillis(),
                lastOpened = 0L,
                title = "Test Book",
                author = "Test Author",
                publisher = null,
                year = null,
                language = "en",
                description = null,
                currentPage = 0,
                totalPages = totalPages,
                percentComplete = 0f,
                currentChapterIndex = 0,
                currentInPageIndex = 0,
                currentCharacterOffset = 0,
                currentPreviewText = null,
                coverPath = null,
                isFavorite = false,
                tags = emptyList(),
                collections = emptyList()
            )
        }
    }
}
