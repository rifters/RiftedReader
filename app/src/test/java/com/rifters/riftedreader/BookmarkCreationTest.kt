package com.rifters.riftedreader

import com.rifters.riftedreader.data.database.entities.Bookmark
import com.rifters.riftedreader.data.repository.BookmarkRepository
import com.rifters.riftedreader.domain.bookmark.BookmarkManager
import com.rifters.riftedreader.domain.parser.PageContent
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import kotlin.math.abs

/**
 * Tests for bookmark creation and restoration using Approach C (Hybrid).
 */
class BookmarkCreationTest {

    @Test
    fun bookmarkCreation_containsAllRequiredFields() = runBlocking {
        val mockRepository = MockBookmarkRepository()
        val manager = BookmarkManager(mockRepository)
        
        val pageContent = PageContent(
            text = "This is a sample text for bookmark preview. It should contain enough content to extract a meaningful preview."
        )
        
        val bookmark = manager.createBookmark(
            bookId = "test-book-123",
            chapterIndex = 10,
            inChapterPage = 5,
            characterOffset = 2584,
            chapterTitle = "Chapter 11: The Discovery",
            pageContent = pageContent,
            percentageThrough = 0.42f,
            fontSize = 16.0f
        )
        
        // Verify all fields are set correctly
        assertEquals("test-book-123", bookmark.bookId)
        assertEquals(10, bookmark.chapterIndex)
        assertEquals(5, bookmark.inChapterPage)
        assertEquals(2584, bookmark.characterOffset)
        assertEquals("Chapter 11: The Discovery", bookmark.chapterTitle)
        assertNotNull(bookmark.previewText)
        assertTrue(bookmark.previewText.isNotBlank())
        assertEquals(0.42f, bookmark.percentageThrough, 0.001f)
        assertEquals(16.0f, bookmark.fontSize, 0.001f)
        assertTrue(bookmark.createdAt > 0)
    }
    
    @Test
    fun bookmarkCreation_extractsPreviewText() = runBlocking {
        val mockRepository = MockBookmarkRepository()
        val manager = BookmarkManager(mockRepository)
        
        val pageContent = PageContent(
            text = "...the ancient artifact was found buried beneath the ruins of the old temple."
        )
        
        val bookmark = manager.createBookmark(
            bookId = "test-book",
            chapterIndex = 10,
            inChapterPage = 5,
            characterOffset = 10,
            chapterTitle = "Chapter 11",
            pageContent = pageContent,
            percentageThrough = 0.42f,
            fontSize = 16.0f
        )
        
        // Preview should contain part of the text
        assertTrue(bookmark.previewText.contains("ancient") || bookmark.previewText.contains("artifact"))
    }
    
    @Test
    fun bookmarkCreation_handlesEmptyContent() = runBlocking {
        val mockRepository = MockBookmarkRepository()
        val manager = BookmarkManager(mockRepository)
        
        val pageContent = PageContent(text = "")
        
        val bookmark = manager.createBookmark(
            bookId = "test-book",
            chapterIndex = 0,
            inChapterPage = 0,
            characterOffset = 0,
            chapterTitle = "Chapter 1",
            pageContent = pageContent,
            percentageThrough = 0.0f,
            fontSize = 16.0f
        )
        
        // Should have a fallback preview
        assertEquals("No preview available", bookmark.previewText)
    }
    
    @Test
    fun bookmarkRestoration_usesInPageWhenFontUnchanged() = runBlocking {
        val mockRepository = MockBookmarkRepository()
        val manager = BookmarkManager(mockRepository)
        
        val bookmark = Bookmark(
            bookId = "test-book",
            chapterIndex = 10,
            inChapterPage = 5,
            characterOffset = 2584,
            chapterTitle = "Chapter 11",
            previewText = "Sample preview",
            percentageThrough = 0.42f,
            fontSize = 16.0f
        )
        
        var chapterNavigated = -1
        var inPageNavigated = -1
        var offsetScrolled = -1
        
        manager.restoreBookmark(
            bookmark = bookmark,
            currentFontSize = 16.0f, // Same font size
            navigateToChapter = { chapterNavigated = it },
            navigateToInPagePosition = { inPageNavigated = it },
            scrollToCharacterOffset = { offsetScrolled = it }
        )
        
        // Should navigate to chapter and use in-page position
        assertEquals(10, chapterNavigated)
        assertEquals(5, inPageNavigated)
        assertEquals(-1, offsetScrolled) // Character offset should NOT be used
    }
    
    @Test
    fun bookmarkRestoration_usesOffsetWhenFontChanged() = runBlocking {
        val mockRepository = MockBookmarkRepository()
        val manager = BookmarkManager(mockRepository)
        
        val bookmark = Bookmark(
            bookId = "test-book",
            chapterIndex = 10,
            inChapterPage = 5,
            characterOffset = 2584,
            chapterTitle = "Chapter 11",
            previewText = "Sample preview",
            percentageThrough = 0.42f,
            fontSize = 16.0f
        )
        
        var chapterNavigated = -1
        var inPageNavigated = -1
        var offsetScrolled = -1
        
        manager.restoreBookmark(
            bookmark = bookmark,
            currentFontSize = 20.0f, // Different font size
            navigateToChapter = { chapterNavigated = it },
            navigateToInPagePosition = { inPageNavigated = it },
            scrollToCharacterOffset = { offsetScrolled = it }
        )
        
        // Should navigate to chapter and use character offset
        assertEquals(10, chapterNavigated)
        assertEquals(-1, inPageNavigated) // In-page should NOT be used
        assertEquals(2584, offsetScrolled)
    }
    
    @Test
    fun bookmarkRestoration_detectsSmallFontChanges() = runBlocking {
        val mockRepository = MockBookmarkRepository()
        val manager = BookmarkManager(mockRepository)
        
        val bookmark = Bookmark(
            bookId = "test-book",
            chapterIndex = 5,
            inChapterPage = 3,
            characterOffset = 1000,
            chapterTitle = "Chapter 6",
            previewText = "Preview",
            percentageThrough = 0.2f,
            fontSize = 16.0f
        )
        
        var offsetScrolled1 = -1
        var inPageNavigated1 = -1
        
        // Small change (within threshold)
        manager.restoreBookmark(
            bookmark = bookmark,
            currentFontSize = 16.05f, // Difference of 0.05 (< 0.1 threshold)
            navigateToChapter = { },
            navigateToInPagePosition = { inPageNavigated1 = it },
            scrollToCharacterOffset = { offsetScrolled1 = it }
        )
        
        // Should use in-page (font change too small)
        assertEquals(3, inPageNavigated1)
        assertEquals(-1, offsetScrolled1)
        
        var offsetScrolled2 = -1
        var inPageNavigated2 = -1
        
        // Larger change (beyond threshold)
        manager.restoreBookmark(
            bookmark = bookmark,
            currentFontSize = 16.15f, // Difference of 0.15 (> 0.1 threshold)
            navigateToChapter = { },
            navigateToInPagePosition = { inPageNavigated2 = it },
            scrollToCharacterOffset = { offsetScrolled2 = it }
        )
        
        // Should use character offset (font changed)
        assertEquals(-1, inPageNavigated2)
        assertEquals(1000, offsetScrolled2)
    }
    
    @Test
    fun bookmark_sortingByPosition() {
        val bookmark1 = Bookmark(
            bookId = "book1",
            chapterIndex = 5,
            inChapterPage = 0,
            characterOffset = 100,
            chapterTitle = "Chapter 6",
            previewText = "Preview 1",
            percentageThrough = 0.3f,
            fontSize = 16.0f
        )
        val bookmark2 = Bookmark(
            bookId = "book1",
            chapterIndex = 3,
            inChapterPage = 2,
            characterOffset = 500,
            chapterTitle = "Chapter 4",
            previewText = "Preview 2",
            percentageThrough = 0.15f,
            fontSize = 16.0f
        )
        val bookmark3 = Bookmark(
            bookId = "book1",
            chapterIndex = 5,
            inChapterPage = 3,
            characterOffset = 200,
            chapterTitle = "Chapter 6",
            previewText = "Preview 3",
            percentageThrough = 0.35f,
            fontSize = 16.0f
        )
        
        val bookmarks = listOf(bookmark1, bookmark2, bookmark3)
        val sorted = bookmarks.sortedWith(
            compareBy({ it.chapterIndex }, { it.inChapterPage })
        )
        
        // Should be sorted: bookmark2 (3,2), bookmark1 (5,0), bookmark3 (5,3)
        assertEquals(bookmark2, sorted[0])
        assertEquals(bookmark1, sorted[1])
        assertEquals(bookmark3, sorted[2])
    }
    
    @Test
    fun bookmark_sortingByCreationTime() {
        val bookmark1 = Bookmark(
            bookId = "book1",
            chapterIndex = 5,
            inChapterPage = 0,
            characterOffset = 100,
            chapterTitle = "Chapter 6",
            previewText = "Preview 1",
            percentageThrough = 0.3f,
            fontSize = 16.0f,
            createdAt = 1000L
        )
        val bookmark2 = Bookmark(
            bookId = "book1",
            chapterIndex = 3,
            inChapterPage = 2,
            characterOffset = 500,
            chapterTitle = "Chapter 4",
            previewText = "Preview 2",
            percentageThrough = 0.15f,
            fontSize = 16.0f,
            createdAt = 3000L
        )
        val bookmark3 = Bookmark(
            bookId = "book1",
            chapterIndex = 5,
            inChapterPage = 3,
            characterOffset = 200,
            chapterTitle = "Chapter 6",
            previewText = "Preview 3",
            percentageThrough = 0.35f,
            fontSize = 16.0f,
            createdAt = 2000L
        )
        
        val bookmarks = listOf(bookmark1, bookmark2, bookmark3)
        val sorted = bookmarks.sortedByDescending { it.createdAt }
        
        // Should be sorted by creation time: bookmark2 (3000), bookmark3 (2000), bookmark1 (1000)
        assertEquals(bookmark2, sorted[0])
        assertEquals(bookmark3, sorted[1])
        assertEquals(bookmark1, sorted[2])
    }
    
    @Test
    fun bookmark_displayFormat() {
        val bookmark = Bookmark(
            bookId = "book1",
            chapterIndex = 10,
            inChapterPage = 5,
            characterOffset = 2584,
            chapterTitle = "Chapter 11: The Discovery",
            previewText = "...the ancient artifact was found buried beneath...",
            percentageThrough = 0.42f,
            fontSize = 16.0f,
            createdAt = System.currentTimeMillis()
        )
        
        // Verify display data is available
        assertEquals("Chapter 11: The Discovery", bookmark.chapterTitle)
        assertEquals(5, bookmark.inChapterPage)
        assertEquals(42, (bookmark.percentageThrough * 100).toInt())
        assertTrue(bookmark.previewText.contains("artifact"))
    }
}

/**
 * Mock repository for testing
 */
private class MockBookmarkRepository : BookmarkRepository(MockBookmarkDao()) {
    private val bookmarks = mutableListOf<Bookmark>()
    
    override suspend fun createBookmark(bookmark: Bookmark) {
        bookmarks.add(bookmark)
    }
    
    override suspend fun getBookmarksForBookSnapshot(bookId: String): List<Bookmark> {
        return bookmarks.filter { it.bookId == bookId }
    }
}

/**
 * Mock DAO for testing
 */
private class MockBookmarkDao : com.rifters.riftedreader.data.database.dao.BookmarkDao {
    private val bookmarks = mutableListOf<Bookmark>()
    
    override fun getBookmarksForBook(bookId: String) = 
        kotlinx.coroutines.flow.flowOf(bookmarks.filter { it.bookId == bookId })
    
    override suspend fun getBookmarksForBookSnapshot(bookId: String) =
        bookmarks.filter { it.bookId == bookId }
    
    override fun getBookmarksForBookSortedByPosition(bookId: String) =
        kotlinx.coroutines.flow.flowOf(
            bookmarks.filter { it.bookId == bookId }
                .sortedWith(compareBy({ it.chapterIndex }, { it.inChapterPage }))
        )
    
    override suspend fun getBookmarkById(bookmarkId: String) =
        bookmarks.find { it.id == bookmarkId }
    
    override fun getRecentBookmarks(limit: Int) =
        kotlinx.coroutines.flow.flowOf(bookmarks.sortedByDescending { it.createdAt }.take(limit))
    
    override fun getAllBookmarks() =
        kotlinx.coroutines.flow.flowOf(bookmarks)
    
    override suspend fun insertBookmark(bookmark: Bookmark) {
        bookmarks.add(bookmark)
    }
    
    override suspend fun insertBookmarks(bookmarks: List<Bookmark>) {
        this.bookmarks.addAll(bookmarks)
    }
    
    override suspend fun updateBookmark(bookmark: Bookmark) {
        val index = bookmarks.indexOfFirst { it.id == bookmark.id }
        if (index != -1) {
            bookmarks[index] = bookmark
        }
    }
    
    override suspend fun deleteBookmark(bookmark: Bookmark) {
        bookmarks.remove(bookmark)
    }
    
    override suspend fun deleteBookmarkById(bookmarkId: String) {
        bookmarks.removeAll { it.id == bookmarkId }
    }
    
    override suspend fun deleteAllBookmarksForBook(bookId: String) {
        bookmarks.removeAll { it.bookId == bookId }
    }
    
    override suspend fun deleteAllBookmarks() {
        bookmarks.clear()
    }
    
    override suspend fun getBookmarkCountForBook(bookId: String) =
        bookmarks.count { it.bookId == bookId }
}
