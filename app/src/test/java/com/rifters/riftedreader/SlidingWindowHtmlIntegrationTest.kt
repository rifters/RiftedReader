package com.rifters.riftedreader

import com.rifters.riftedreader.domain.pagination.ContinuousPaginatorWindowHtmlProvider
import com.rifters.riftedreader.domain.pagination.SlidingWindowManager
import com.rifters.riftedreader.domain.pagination.ContinuousPaginator
import com.rifters.riftedreader.domain.parser.PageContent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Integration tests for sliding window HTML generation and loading.
 * Tests the complete pipeline from ContinuousPaginator through WindowHtmlProvider.
 */
class SlidingWindowHtmlIntegrationTest {

    companion object {
        // Test constant representing a book with 62 chapters (12 full windows + 1 partial window of 2 chapters)
        private const val TOTAL_TEST_CHAPTERS = 62
    }

    @Test
    fun `window HTML contains multiple chapters with section tags`() = runTest {
        // Setup with mock book parser
        val mockFile = File("test.epub")
        val mockParser = MockBookParser(totalPages = TOTAL_TEST_CHAPTERS)
        
        // Initialize paginator
        val paginator = ContinuousPaginator(mockFile, mockParser, windowSize = 5)
        paginator.initialize()
        paginator.loadInitialWindow(0)
        
        // Create provider
        val windowManager = SlidingWindowManager(windowSize = 5)
        val provider = ContinuousPaginatorWindowHtmlProvider(paginator, windowManager)
        
        // Get window HTML for window 0 (chapters 0-4)
        val html = provider.getWindowHtml("test-book-id", windowIndex = 0)
        
        // Verify HTML is not null
        assertNotNull("Window HTML should not be null", html)
        
        // Verify it contains window-root container
        assertTrue("Should contain window-root container", html!!.contains("<div id=\"window-root\""))
        assertTrue("Should have data-window-index=\"0\"", html.contains("data-window-index=\"0\""))
        
        // Verify it contains section tags for chapters 0-4
        assertTrue("Should contain chapter 0 section", html.contains("<section id=\"chapter-0\""))
        assertTrue("Should contain chapter 1 section", html.contains("<section id=\"chapter-1\""))
        assertTrue("Should contain chapter 2 section", html.contains("<section id=\"chapter-2\""))
        assertTrue("Should contain chapter 3 section", html.contains("<section id=\"chapter-3\""))
        assertTrue("Should contain chapter 4 section", html.contains("<section id=\"chapter-4\""))
        
        // Verify it contains data-chapter-index attributes
        assertTrue("Should have data-chapter-index=\"0\"", html.contains("data-chapter-index=\"0\""))
        assertTrue("Should have data-chapter-index=\"4\"", html.contains("data-chapter-index=\"4\""))
        
        // Verify it does NOT contain chapter 5
        assertFalse("Should not contain chapter 5 section", html.contains("<section id=\"chapter-5\""))
    }

    @Test
    fun `window HTML for last window handles partial window correctly`() = runTest {
        val mockFile = File("test.epub")
        val mockParser = MockBookParser(totalPages = TOTAL_TEST_CHAPTERS) // 62 chapters = 12 full windows + 1 partial
        
        val paginator = ContinuousPaginator(mockFile, mockParser, windowSize = 5)
        paginator.initialize()
        paginator.loadInitialWindow(0)
        
        val windowManager = SlidingWindowManager(windowSize = 5)
        
        // Calculate last window index
        val lastWindow = windowManager.windowForChapter(61) // Should be window 12
        assertEquals(12, lastWindow)
        
        // Load the last window
        paginator.loadInitialWindow(61)
        
        val provider = ContinuousPaginatorWindowHtmlProvider(paginator, windowManager)
        val html = provider.getWindowHtml("test-book-id", windowIndex = lastWindow)
        
        assertNotNull("Window HTML should not be null", html)
        
        // Verify it contains window-root container with correct index
        assertTrue("Should contain window-root container", html!!.contains("<div id=\"window-root\""))
        assertTrue("Should have data-window-index=\"12\"", html.contains("data-window-index=\"12\""))
        
        // Verify it contains only chapters 60-61
        assertTrue("Should contain chapter 60", html.contains("<section id=\"chapter-60\""))
        assertTrue("Should contain chapter 61", html.contains("<section id=\"chapter-61\""))
        
        // Should not contain chapter 62 (out of bounds)
        assertFalse("Should not contain chapter 62", html.contains("<section id=\"chapter-62\""))
    }

    @Test
    fun `window HTML contains correct chapters for given window index`() = runTest {
        val mockFile = File("test.epub")
        val mockParser = MockBookParser(totalPages = TOTAL_TEST_CHAPTERS)
        
        val paginator = ContinuousPaginator(mockFile, mockParser, windowSize = 5)
        paginator.initialize()
        
        val windowManager = SlidingWindowManager(windowSize = 5)
        
        // Test window 1 (chapters 5-9)
        val windowIndex = 1
        val expectedChapters = windowManager.chaptersInWindow(windowIndex, TOTAL_TEST_CHAPTERS)
        assertEquals(listOf(5, 6, 7, 8, 9), expectedChapters)
        
        // Navigate to middle of window to ensure all chapters are loaded
        paginator.navigateToChapter(7, 0)
        
        val provider = ContinuousPaginatorWindowHtmlProvider(paginator, windowManager)
        val html = provider.getWindowHtml("test-book-id", windowIndex = windowIndex)
        
        assertNotNull("Window HTML should not be null", html)
        
        // Verify all expected chapters are present
        for (chapterIndex in expectedChapters) {
            assertTrue(
                "Should contain chapter $chapterIndex", 
                html!!.contains("<section id=\"chapter-$chapterIndex\"")
            )
        }
    }

    @Test
    fun `chapter to window mapping matches expected window indices`() = runTest {
        val windowManager = SlidingWindowManager(windowSize = 5)
        val mockFile = File("test.epub")
        val mockParser = MockBookParser(totalPages = TOTAL_TEST_CHAPTERS)
        
        val paginator = ContinuousPaginator(mockFile, mockParser, windowSize = 5)
        paginator.initialize()
        
        // Test that chapter indices map to correct windows
        // Window 0: chapters 0-4
        assertEquals(0, windowManager.windowForChapter(0))
        assertEquals(0, windowManager.windowForChapter(4))
        
        // Window 1: chapters 5-9
        assertEquals(1, windowManager.windowForChapter(5))
        assertEquals(1, windowManager.windowForChapter(9))
        
        // Window 2: chapters 10-14
        assertEquals(2, windowManager.windowForChapter(10))
        assertEquals(2, windowManager.windowForChapter(14))
        
        // Last window 12: chapters 60-61
        assertEquals(12, windowManager.windowForChapter(60))
        assertEquals(12, windowManager.windowForChapter(61))
    }

    @Test
    fun `provider returns null for invalid window index`() = runTest {
        val mockFile = File("test.epub")
        val mockParser = MockBookParser(totalPages = 5) // Only 1 window worth of chapters
        
        val paginator = ContinuousPaginator(mockFile, mockParser, windowSize = 5)
        paginator.initialize()
        paginator.loadInitialWindow(0)
        
        val windowManager = SlidingWindowManager(windowSize = 5)
        val provider = ContinuousPaginatorWindowHtmlProvider(paginator, windowManager)
        
        // Window 5 is way out of bounds
        val html = provider.getWindowHtml("test-book-id", windowIndex = 5)
        
        // Should return null or empty for invalid window
        assertTrue("Should return null or empty for out-of-bounds window", 
            html == null || html.isEmpty())
    }

    @Test
    fun `window HTML contains actual chapter content`() = runTest {
        val mockFile = File("test.epub")
        val mockParser = MockBookParser(totalPages = 10)
        
        val paginator = ContinuousPaginator(mockFile, mockParser, windowSize = 5)
        paginator.initialize()
        paginator.loadInitialWindow(0)
        
        val windowManager = SlidingWindowManager(windowSize = 5)
        val provider = ContinuousPaginatorWindowHtmlProvider(paginator, windowManager)
        
        val html = provider.getWindowHtml("test-book-id", windowIndex = 0)
        
        assertNotNull("Window HTML should not be null", html)
        
        // Verify it contains the actual chapter content from MockBookParser
        assertTrue("Should contain chapter 0 content", html!!.contains("Content for page 0"))
        assertTrue("Should contain chapter 1 content", html.contains("Content for page 1"))
        assertTrue("Should contain chapter 2 content", html.contains("Content for page 2"))
        assertTrue("Should contain chapter 3 content", html.contains("Content for page 3"))
        assertTrue("Should contain chapter 4 content", html.contains("Content for page 4"))
    }

    /**
     * Mock BookParser for testing.
     * Returns simple HTML content with identifiable text for each page.
     */
    private class MockBookParser(private val totalPages: Int) : com.rifters.riftedreader.domain.parser.BookParser {
        override fun canParse(file: File): Boolean = true
        
        override suspend fun getPageCount(file: File): Int = totalPages
        
        override suspend fun getPageContent(file: File, page: Int): PageContent {
            return PageContent(
                text = "Content for page $page",
                html = "<p>Content for page $page</p>"
            )
        }
        
        override suspend fun getTableOfContents(file: File): List<com.rifters.riftedreader.domain.parser.TocEntry> {
            return emptyList()
        }
        
        override suspend fun extractMetadata(file: File): com.rifters.riftedreader.data.database.entities.BookMeta {
            return com.rifters.riftedreader.data.database.entities.BookMeta(
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
