package com.rifters.riftedreader

import com.rifters.riftedreader.data.database.dao.BookMetaDao
import com.rifters.riftedreader.data.database.entities.Bookmark
import com.rifters.riftedreader.data.database.entities.BookMeta
import com.rifters.riftedreader.data.preferences.ReaderMode
import com.rifters.riftedreader.data.preferences.ReaderPreferences
import com.rifters.riftedreader.data.preferences.ReaderSettings
import com.rifters.riftedreader.data.repository.BookRepository
import com.rifters.riftedreader.data.repository.BookmarkRepository
import com.rifters.riftedreader.domain.pagination.ContinuousPaginator
import com.rifters.riftedreader.domain.pagination.PaginationMode
import com.rifters.riftedreader.domain.parser.BookParser
import com.rifters.riftedreader.domain.parser.PageContent
import com.rifters.riftedreader.domain.parser.TocEntry
import com.rifters.riftedreader.pagination.PageSlice
import com.rifters.riftedreader.pagination.SliceMetadata
import com.rifters.riftedreader.pagination.WindowData
import com.rifters.riftedreader.ui.reader.ReaderViewModel
import com.rifters.riftedreader.ui.reader.conveyor.ConveyorBeltSystemViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.lang.reflect.Proxy

/**
 * Tests for bookmark restoration with character offset fallback.
 * Verifies that bookmarks restore correctly when font changes or window migration occurs.
 */
class BookmarkRestorationTest {

    @Test
    fun bookmarkRestoration_usesChapterAndInPageIndex() = runBlocking {
        val parser = MockParser(chapterCount = 10)
        val paginator = ContinuousPaginator(File("test.txt"), parser, windowSize = 5)
        paginator.initialize()
        
        // Load window at chapter 5
        paginator.loadInitialWindow(5)
        
        // Navigate to chapter 5, page 0 (simulating bookmark restoration)
        val globalPage = paginator.navigateToChapter(5, 0)
        
        assertNotNull(globalPage)
        val location = paginator.getPageLocation(globalPage!!)
        assertNotNull(location)
        assertEquals(5, location!!.chapterIndex)
        assertEquals(0, location.inPageIndex)
    }
    
    @Test
    fun bookmarkRestoration_fallsBackToChapterStart_whenInPageIndexInvalid() = runBlocking {
        val parser = MockParser(chapterCount = 10)
        val paginator = ContinuousPaginator(File("test.txt"), parser, windowSize = 5)
        paginator.initialize()
        
        paginator.loadInitialWindow(3)
        
        // Try to navigate to an invalid in-page index (should be clamped to valid range)
        val globalPage = paginator.navigateToChapter(3, 999)
        
        assertNotNull(globalPage)
        val location = paginator.getPageLocation(globalPage!!)
        assertNotNull(location)
        assertEquals(3, location!!.chapterIndex)
        // Should be clamped to the last page of the chapter (0 in this mock)
        assertEquals(0, location.inPageIndex)
    }
    
    @Test
    fun bookmarkRestoration_worksAfterWindowMigration() = runBlocking {
        val bookmarkRepository = TestBookmarkRepository()
        bookmarkRepository.saveLastRead(
            Bookmark(
                bookId = "book1",
                chapterIndex = 15,
                charOffset = 125,
                pageIndexHint = 2,
                nearestAnchorId = "chapter-15",
                nearestAnchorText = "Chapter 15",
                savedAt = 1_000
            )
        )
        val viewModel = readerViewModel(bookmarkRepository)
        val conveyor = ConveyorBeltSystemViewModel()
        conveyor.cacheWindowData(
            WindowData(
                html = "<html></html>",
                firstChapter = 15,
                lastChapter = 19,
                windowIndex = 3,
                sliceMetadata = SliceMetadata(
                    windowIndex = 3,
                    totalPages = 3,
                    slices = listOf(
                        PageSlice(page = 0, chapter = 15, startChar = 0, endChar = 100, heightPx = 800),
                        PageSlice(page = 1, chapter = 15, startChar = 100, endChar = 200, heightPx = 800),
                        PageSlice(page = 2, chapter = 16, startChar = 0, endChar = 100, heightPx = 800)
                    )
                ),
                isSliceStale = false
            )
        )
        viewModel.setConveyorBeltSystem(conveyor)

        val restoredPage = viewModel.restoreLastRead("book1")

        assertEquals(1, restoredPage)
    }
    
    @Test
    fun bookmarkMetadata_includesAllRequiredFields() {
        val bookmark = BookMeta(
            id = "test-book",
            path = "/path/to/book.epub",
            title = "Test Book",
            format = "epub",
            size = 1024L,
            currentChapterIndex = 5,
            currentInPageIndex = 2,
            currentCharacterOffset = 1500,
            currentPreviewText = "This is a preview of the bookmark location...",
            percentComplete = 45.5f
        )
        
        // Verify all bookmark fields are set
        assertEquals(5, bookmark.currentChapterIndex)
        assertEquals(2, bookmark.currentInPageIndex)
        assertEquals(1500, bookmark.currentCharacterOffset)
        assertEquals("This is a preview of the bookmark location...", bookmark.currentPreviewText)
        assertEquals(45.5f, bookmark.percentComplete, 0.01f)
    }
    
    @Test
    fun bookmarkMetadata_defaultValues() {
        val bookmark = BookMeta(
            id = "test-book",
            path = "/path/to/book.epub",
            title = "Test Book",
            format = "epub",
            size = 1024L
        )
        
        // Verify default values for bookmark fields
        assertEquals(0, bookmark.currentChapterIndex)
        assertEquals(0, bookmark.currentInPageIndex)
        assertEquals(0, bookmark.currentCharacterOffset)
        assertNull(bookmark.currentPreviewText)
    }
    
    @Test
    fun bookmarkSorting_byPosition() {
        val bookmark1 = BookMeta(
            id = "book1", path = "/book1.epub", title = "Book 1", format = "epub", size = 1024L,
            currentChapterIndex = 5, currentInPageIndex = 0, currentPreviewText = "Preview 1"
        )
        val bookmark2 = BookMeta(
            id = "book2", path = "/book2.epub", title = "Book 2", format = "epub", size = 1024L,
            currentChapterIndex = 3, currentInPageIndex = 2, currentPreviewText = "Preview 2"
        )
        val bookmark3 = BookMeta(
            id = "book3", path = "/book3.epub", title = "Book 3", format = "epub", size = 1024L,
            currentChapterIndex = 5, currentInPageIndex = 3, currentPreviewText = "Preview 3"
        )
        
        val bookmarks = listOf(bookmark1, bookmark2, bookmark3)
        val sorted = bookmarks.sortedWith(
            compareBy({ it.currentChapterIndex }, { it.currentInPageIndex })
        )
        
        // Should be sorted: book2 (3,2), book1 (5,0), book3 (5,3)
        assertEquals("book2", sorted[0].id)
        assertEquals("book1", sorted[1].id)
        assertEquals("book3", sorted[2].id)
    }
    
    @Test
    fun bookmarkFiltering_onlyWithPreviewText() {
        val withPreview = BookMeta(
            id = "book1", path = "/book1.epub", title = "Book 1", format = "epub", size = 1024L,
            currentPreviewText = "Preview text"
        )
        val withoutPreview = BookMeta(
            id = "book2", path = "/book2.epub", title = "Book 2", format = "epub", size = 1024L,
            currentPreviewText = null
        )
        
        val allBooks = listOf(withPreview, withoutPreview)
        val booksWithBookmarks = allBooks.filter { it.currentPreviewText != null }
        
        assertEquals(1, booksWithBookmarks.size)
        assertEquals("book1", booksWithBookmarks[0].id)
    }
}

/**
 * Mock parser for testing
 */
private class MockParser(private val chapterCount: Int) : BookParser {
    override fun canParse(file: File): Boolean = true
    
    override suspend fun extractMetadata(file: File): BookMeta {
        return BookMeta(
            id = "test-id",
            path = file.path,
            title = "Test Book",
            format = "test",
            size = 1024L
        )
    }
    
    override suspend fun getPageCount(file: File): Int = chapterCount
    
    override suspend fun getPageContent(file: File, page: Int): PageContent {
        return PageContent(text = "Content for chapter $page")
    }
    
    override suspend fun getTableOfContents(file: File): List<TocEntry> {
        return (0 until chapterCount).map { 
            TocEntry(title = "Chapter ${it + 1}", pageNumber = it) 
        }
    }

    private class TestBookmarkRepository : BookmarkRepository {
        private val lastRead = mutableMapOf<String, Bookmark>()

        override suspend fun saveLastRead(bookmark: Bookmark) {
            lastRead[bookmark.bookId] = bookmark
        }

        override suspend fun loadLastRead(bookId: String): Bookmark? {
            return lastRead[bookId]
        }

        override suspend fun saveNamedBookmark(bookmark: Bookmark) = Unit

        override suspend fun loadNamedBookmarks(bookId: String): List<Bookmark> = emptyList()

        override suspend fun delete(bookmark: Bookmark) {
            lastRead.remove(bookmark.bookId)
        }
    }

    private fun readerViewModel(bookmarkRepository: BookmarkRepository): ReaderViewModel {
        return ReaderViewModel(
            bookId = "book1",
            bookFile = File("test.txt"),
            parser = MockParser(chapterCount = 20),
            repository = BookRepository(noOpBookMetaDao()),
            bookmarkRepository = bookmarkRepository,
            readerPreferences = testReaderPreferences()
        )
    }

    private fun noOpBookMetaDao(): BookMetaDao {
        return Proxy.newProxyInstance(
            BookMetaDao::class.java.classLoader,
            arrayOf(BookMetaDao::class.java)
        ) { _, method, _ ->
            when (method.returnType.name) {
                "kotlinx.coroutines.flow.Flow" -> flowOf(emptyList<BookMeta>())
                "boolean" -> false
                "int" -> 0
                "float" -> 0f
                else -> null
            }
        } as BookMetaDao
    }

    private fun testReaderPreferences(): ReaderPreferences {
        val unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null)
        val allocateInstance = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
        val preferences = allocateInstance.invoke(unsafe, ReaderPreferences::class.java) as ReaderPreferences
        val settingsField = ReaderPreferences::class.java.getDeclaredField("_settings")
        settingsField.isAccessible = true
        settingsField.set(
            preferences,
            MutableStateFlow(
                ReaderSettings(
                    mode = ReaderMode.PAGINATED,
                    paginationMode = PaginationMode.CONTINUOUS
                )
            )
        )
        return preferences
    }

    private fun ConveyorBeltSystemViewModel.cacheWindowData(windowData: WindowData) {
        val cacheField = ConveyorBeltSystemViewModel::class.java.getDeclaredField("windowDataCache")
        cacheField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val cache = cacheField.get(this) as MutableMap<Int, WindowData>
        cache[windowData.windowIndex] = windowData
    }
}
