package com.rifters.riftedreader

import com.rifters.riftedreader.domain.pagination.ContinuousPaginator
import com.rifters.riftedreader.domain.pagination.GlobalPageIndex
import com.rifters.riftedreader.domain.parser.BookParser
import com.rifters.riftedreader.domain.parser.PageContent
import com.rifters.riftedreader.domain.parser.TocEntry
import com.rifters.riftedreader.data.database.entities.BookMeta
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Unit tests for ContinuousPaginator
 */
class ContinuousPaginatorTest {
    
    private lateinit var mockParser: MockBookParser
    private lateinit var paginator: ContinuousPaginator
    private val mockFile = File("/fake/book.epub")
    
    @Before
    fun setup() {
        mockParser = MockBookParser(totalChapters = 10)
        paginator = ContinuousPaginator(mockFile, mockParser, windowSize = 5)
    }
    
    @Test
    fun `initialize loads chapter metadata`() = runBlocking {
        paginator.initialize()
        
        val windowInfo = paginator.getWindowInfo()
        assertEquals(10, windowInfo.totalChapters)
    }
    
    @Test
    fun `loadInitialWindow centers on target chapter`() = runBlocking {
        paginator.initialize()
        
        val globalPage = paginator.loadInitialWindow(chapterIndex = 5)
        
        val windowInfo = paginator.getWindowInfo()
        assertEquals(5, windowInfo.currentChapterIndex)
        assertTrue(windowInfo.loadedChapterIndices.contains(5))
        
        // Window should be: 3, 4, 5, 6, 7 (5 chapters centered on 5)
        assertEquals(5, windowInfo.loadedChapterIndices.size)
        assertEquals(3, windowInfo.loadedChapterIndices.first())
        assertEquals(7, windowInfo.loadedChapterIndices.last())
    }
    
    @Test
    fun `loadInitialWindow handles edge cases at start`() = runBlocking {
        paginator.initialize()
        
        paginator.loadInitialWindow(chapterIndex = 0)
        
        val windowInfo = paginator.getWindowInfo()
        assertEquals(0, windowInfo.currentChapterIndex)
        
        // Window should be: 0, 1, 2, 3, 4 (start of book)
        assertEquals(5, windowInfo.loadedChapterIndices.size)
        assertEquals(0, windowInfo.loadedChapterIndices.first())
        assertEquals(4, windowInfo.loadedChapterIndices.last())
    }
    
    @Test
    fun `loadInitialWindow handles edge cases at end`() = runBlocking {
        paginator.initialize()
        
        paginator.loadInitialWindow(chapterIndex = 9)
        
        val windowInfo = paginator.getWindowInfo()
        assertEquals(9, windowInfo.currentChapterIndex)
        
        // Window should be: 5, 6, 7, 8, 9 (end of book)
        assertEquals(5, windowInfo.loadedChapterIndices.size)
        assertEquals(5, windowInfo.loadedChapterIndices.first())
        assertEquals(9, windowInfo.loadedChapterIndices.last())
    }
    
    @Test
    fun `navigateToGlobalPage shifts window when needed`() = runBlocking {
        paginator.initialize()
        paginator.loadInitialWindow(chapterIndex = 0)
        
        // Navigate to chapter 7 (global page 7 assuming 1 page per chapter)
        val location = paginator.navigateToGlobalPage(globalPageIndex = 7)
        
        assertNotNull(location)
        assertEquals(7, location?.chapterIndex)
        
        val windowInfo = paginator.getWindowInfo()
        assertEquals(7, windowInfo.currentChapterIndex)
        
        // Window should have shifted to: 5, 6, 7, 8, 9
        assertEquals(5, windowInfo.loadedChapterIndices.first())
        assertEquals(9, windowInfo.loadedChapterIndices.last())
    }
    
    @Test
    fun `navigateToChapter loads correct window`() = runBlocking {
        paginator.initialize()
        paginator.loadInitialWindow(chapterIndex = 0)
        
        val globalPage = paginator.navigateToChapter(chapterIndex = 6, inPageIndex = 0)
        
        val windowInfo = paginator.getWindowInfo()
        assertEquals(6, windowInfo.currentChapterIndex)
        
        // Window should be: 4, 5, 6, 7, 8
        assertEquals(5, windowInfo.loadedChapterIndices.size)
        assertEquals(4, windowInfo.loadedChapterIndices.first())
        assertEquals(8, windowInfo.loadedChapterIndices.last())
    }
    
    @Test
    fun `getPageContent returns correct content`() = runBlocking {
        paginator.initialize()
        paginator.loadInitialWindow(chapterIndex = 2)
        
        val content = paginator.getPageContent(globalPageIndex = 2)
        
        assertNotNull(content)
        assertEquals("Chapter 2 content", content?.text)
    }
    
    @Test
    fun `getPageLocation returns correct mapping`() = runBlocking {
        paginator.initialize()
        paginator.loadInitialWindow(chapterIndex = 5)
        
        val location = paginator.getPageLocation(globalPageIndex = 5)
        
        assertNotNull(location)
        assertEquals(5, location?.globalPageIndex)
        assertEquals(5, location?.chapterIndex)
        assertEquals(0, location?.inPageIndex)
        assertEquals(0, location?.characterOffset)
    }
    
    @Test
    fun `getTotalGlobalPages returns correct count`() = runBlocking {
        paginator.initialize()
        paginator.loadInitialWindow(chapterIndex = 0)
        
        val totalPages = paginator.getTotalGlobalPages()
        
        // 10 chapters, 1 page each = 10 total pages
        assertEquals(10, totalPages)
    }
    
    @Test
    fun `window unloads chapters outside range`() = runBlocking {
        paginator.initialize()
        paginator.loadInitialWindow(chapterIndex = 2)
        
        val initialWindowInfo = paginator.getWindowInfo()
        // Window: 0, 1, 2, 3, 4
        assertEquals(5, initialWindowInfo.loadedChapterIndices.size)
        assertTrue(initialWindowInfo.loadedChapterIndices.contains(0))
        assertTrue(initialWindowInfo.loadedChapterIndices.contains(4))
        
        // Navigate to chapter 7
        paginator.navigateToChapter(chapterIndex = 7)
        
        val newWindowInfo = paginator.getWindowInfo()
        // Window: 5, 6, 7, 8, 9
        assertEquals(5, newWindowInfo.loadedChapterIndices.size)
        assertTrue(newWindowInfo.loadedChapterIndices.contains(5))
        assertTrue(newWindowInfo.loadedChapterIndices.contains(9))
        
        // Chapters 0-4 should be unloaded
        assertFalse(newWindowInfo.loadedChapterIndices.contains(0))
        assertFalse(newWindowInfo.loadedChapterIndices.contains(4))
    }

    @Test
    fun `getChapterPageCount returns fallback when not loaded`() = runBlocking {
        paginator.initialize()
        paginator.loadInitialWindow(chapterIndex = 0)

        val count = paginator.getChapterPageCount(3)

        assertEquals(1, count)
    }

    @Test
    fun `updateChapterPageCount recalculates global map`() = runBlocking {
        paginator.initialize()
        paginator.loadInitialWindow(chapterIndex = 0)

        val updated = paginator.updateChapterPageCount(chapterIndex = 2, pageCount = 3)

        assertTrue(updated)
        assertEquals(3, paginator.getChapterPageCount(2))
        assertEquals(12, paginator.getTotalGlobalPages())

        val chapterTwoStart = paginator.getPageLocation(2)
        assertEquals(2, chapterTwoStart?.chapterIndex)
        assertEquals(0, chapterTwoStart?.inPageIndex)

        val locationAfterExpandedChapter = paginator.getPageLocation(5)
        assertEquals(3, locationAfterExpandedChapter?.chapterIndex)
        assertEquals(0, locationAfterExpandedChapter?.inPageIndex)
    }

    @Test
    fun `updateChapterPageCount ignores identical counts`() = runBlocking {
        paginator.initialize()
        paginator.loadInitialWindow(chapterIndex = 0)

        assertTrue(paginator.updateChapterPageCount(chapterIndex = 4, pageCount = 2))
        assertFalse(paginator.updateChapterPageCount(chapterIndex = 4, pageCount = 2))
    }

    @Test
    fun `getGlobalIndexForChapterPage returns null before window load`() = runBlocking {
        paginator.initialize()

        val result = paginator.getGlobalIndexForChapterPage(chapterIndex = 0, inPageIndex = 0)

        assertNull(result)
    }

    @Test
    fun `markChapterEvicted removes chapter from cache`() = runBlocking {
        paginator.initialize()
        paginator.loadInitialWindow(chapterIndex = 2)

        val before = paginator.getWindowInfo()
        assertTrue(before.loadedChapterIndices.contains(0))

        paginator.markChapterEvicted(0)

        val after = paginator.getWindowInfo()
        assertFalse(after.loadedChapterIndices.contains(0))
    }

    @Test
    fun `markChapterEvicted ignores current chapter`() = runBlocking {
        paginator.initialize()
        paginator.loadInitialWindow(chapterIndex = 2)

        val before = paginator.getWindowInfo()
        assertTrue(before.loadedChapterIndices.contains(2))

        paginator.markChapterEvicted(2)

        val after = paginator.getWindowInfo()
        assertTrue(after.loadedChapterIndices.contains(2))
    }
    
    /**
     * Mock BookParser for testing
     */
    private class MockBookParser(
        private val totalChapters: Int
    ) : BookParser {
        
        override fun canParse(file: File): Boolean = true
        
        override suspend fun extractMetadata(file: File): BookMeta {
            return BookMeta(
                path = file.path,
                format = "epub",
                size = 1000L,
                title = "Test Book",
                totalPages = totalChapters
            )
        }
        
        override suspend fun getPageContent(file: File, page: Int): PageContent {
            return PageContent(
                text = "Chapter $page content",
                html = "<p>Chapter $page content</p>"
            )
        }
        
        override suspend fun getPageCount(file: File): Int {
            return totalChapters
        }
        
        override suspend fun getTableOfContents(file: File): List<TocEntry> {
            return (0 until totalChapters).map { index ->
                TocEntry(
                    title = "Chapter ${index + 1}",
                    pageNumber = index,
                    level = 0
                )
            }
        }
    }
}
