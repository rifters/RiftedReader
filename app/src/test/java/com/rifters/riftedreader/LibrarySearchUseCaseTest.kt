package com.rifters.riftedreader

import com.rifters.riftedreader.data.database.dao.BookMetaDao
import com.rifters.riftedreader.data.database.dao.CollectionDao
import com.rifters.riftedreader.data.database.entities.BookMeta
import com.rifters.riftedreader.data.database.entities.CollectionEntity
import com.rifters.riftedreader.data.database.entities.CollectionWithBooks
import com.rifters.riftedreader.data.repository.BookRepository
import com.rifters.riftedreader.data.repository.CollectionRepository
import com.rifters.riftedreader.domain.library.LibrarySearchFilters
import com.rifters.riftedreader.domain.library.LibrarySearchUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*

/**
 * Tests for LibrarySearchUseCase focusing on tag filtering case sensitivity
 * and collection filtering bugs.
 * 
 * These tests verify:
 * 1. Tag filtering normalizes both book tags and filter tags to ensure case-insensitive matching
 * 2. Collection filtering properly queries the junction table to find books in collections
 */
class LibrarySearchUseCaseTest {

    private val testBooks = listOf(
        BookMeta(
            id = "book1",
            path = "/path/to/book1.epub",
            title = "Fiction Book",
            format = "epub",
            size = 1024L,
            tags = listOf("Fiction", "Mystery")
        ),
        BookMeta(
            id = "book2",
            path = "/path/to/book2.pdf",
            title = "Non-Fiction Book",
            format = "pdf",
            size = 2048L,
            tags = listOf("NonFiction", "Science")
        ),
        BookMeta(
            id = "book3",
            path = "/path/to/book3.epub",
            title = "Another Book",
            format = "epub",
            size = 512L,
            tags = listOf("FANTASY", "Adventure")
        )
    )

    @Test
    fun tagFiltering_caseSensitivity_shouldNormalizeBothSides() = runBlocking {
        val dao = TestBookMetaDao(testBooks)
        val collDao = TestCollectionDao(emptyList())
        val useCase = LibrarySearchUseCase(
            BookRepository(dao),
            CollectionRepository(collDao)
        )
        
        // Bug fix test: Filter with "Fiction" (capital F) should match book tag "Fiction"
        val filters = LibrarySearchFilters(tags = setOf("Fiction"))
        val result = useCase.observe(filters).first()
        
        assertEquals(1, result.size)
        assertEquals("book1", result[0].id)
    }

    @Test
    fun tagFiltering_caseSensitivity_lowercaseFilter() = runBlocking {
        val dao = TestBookMetaDao(testBooks)
        val collDao = TestCollectionDao(emptyList())
        val useCase = LibrarySearchUseCase(
            BookRepository(dao),
            CollectionRepository(collDao)
        )
        
        // Filter with "fiction" (lowercase) should match book tag "Fiction"
        val filters = LibrarySearchFilters(tags = setOf("fiction"))
        val result = useCase.observe(filters).first()
        
        assertEquals(1, result.size)
        assertEquals("book1", result[0].id)
    }

    @Test
    fun tagFiltering_caseSensitivity_uppercaseFilter() = runBlocking {
        val dao = TestBookMetaDao(testBooks)
        val collDao = TestCollectionDao(emptyList())
        val useCase = LibrarySearchUseCase(
            BookRepository(dao),
            CollectionRepository(collDao)
        )
        
        // Filter with "FICTION" (uppercase) should match book tag "Fiction"
        val filters = LibrarySearchFilters(tags = setOf("FICTION"))
        val result = useCase.observe(filters).first()
        
        assertEquals(1, result.size)
        assertEquals("book1", result[0].id)
    }

    @Test
    fun tagFiltering_caseSensitivity_mixedCaseBookTag() = runBlocking {
        val dao = TestBookMetaDao(testBooks)
        val collDao = TestCollectionDao(emptyList())
        val useCase = LibrarySearchUseCase(
            BookRepository(dao),
            CollectionRepository(collDao)
        )
        
        // Filter with "fantasy" (lowercase) should match book tag "FANTASY" (uppercase)
        val filters = LibrarySearchFilters(tags = setOf("fantasy"))
        val result = useCase.observe(filters).first()
        
        assertEquals(1, result.size)
        assertEquals("book3", result[0].id)
    }

    @Test
    fun tagFiltering_multipleTagsWithDifferentCasing() = runBlocking {
        val dao = TestBookMetaDao(testBooks)
        val collDao = TestCollectionDao(emptyList())
        val useCase = LibrarySearchUseCase(
            BookRepository(dao),
            CollectionRepository(collDao)
        )
        
        // Multiple filters with different casing should all work
        val filters = LibrarySearchFilters(tags = setOf("fiction", "NONFICTION"))
        val result = useCase.observe(filters).first()
        
        assertEquals(2, result.size)
        assertTrue(result.any { it.id == "book1" })
        assertTrue(result.any { it.id == "book2" })
    }

    @Test
    fun collectionFiltering_shouldQueryJunctionTable() = runBlocking {
        val dao = TestBookMetaDao(testBooks)
        
        // Setup: Create collections with books
        val collection1 = CollectionEntity(id = "col1", name = "Collection 1")
        val collection2 = CollectionEntity(id = "col2", name = "Collection 2")
        
        val collectionsWithBooks = listOf(
            CollectionWithBooks(
                collection = collection1,
                books = listOf(testBooks[0], testBooks[1])  // book1 and book2 in col1
            ),
            CollectionWithBooks(
                collection = collection2,
                books = listOf(testBooks[1])  // only book2 in col2
            )
        )
        
        val collDao = TestCollectionDao(collectionsWithBooks)
        val useCase = LibrarySearchUseCase(
            BookRepository(dao),
            CollectionRepository(collDao)
        )
        
        // Filter by collection1 - should return book1 and book2
        val filters1 = LibrarySearchFilters(collections = setOf("col1"))
        val result1 = useCase.observe(filters1).first()
        
        assertEquals(2, result1.size)
        assertTrue(result1.any { it.id == "book1" })
        assertTrue(result1.any { it.id == "book2" })
        
        // Filter by collection2 - should return only book2
        val filters2 = LibrarySearchFilters(collections = setOf("col2"))
        val result2 = useCase.observe(filters2).first()
        
        assertEquals(1, result2.size)
        assertEquals("book2", result2[0].id)
    }

    @Test
    fun collectionFiltering_emptyFilter_shouldReturnAllBooks() = runBlocking {
        val dao = TestBookMetaDao(testBooks)
        val collDao = TestCollectionDao(emptyList())
        val useCase = LibrarySearchUseCase(
            BookRepository(dao),
            CollectionRepository(collDao)
        )
        
        // Empty collection filter should return all books
        val filters = LibrarySearchFilters(collections = emptySet())
        val result = useCase.observe(filters).first()
        
        assertEquals(3, result.size)
    }

    @Test
    fun collectionFiltering_bookNotInAnyCollection_shouldNotMatch() = runBlocking {
        val dao = TestBookMetaDao(testBooks)
        
        // Setup: Only book1 and book2 in collections
        val collection1 = CollectionEntity(id = "col1", name = "Collection 1")
        val collectionsWithBooks = listOf(
            CollectionWithBooks(
                collection = collection1,
                books = listOf(testBooks[0], testBooks[1])
            )
        )
        
        val collDao = TestCollectionDao(collectionsWithBooks)
        val useCase = LibrarySearchUseCase(
            BookRepository(dao),
            CollectionRepository(collDao)
        )
        
        // Filter by collection1 - book3 should not be included
        val filters = LibrarySearchFilters(collections = setOf("col1"))
        val result = useCase.observe(filters).first()
        
        assertEquals(2, result.size)
        assertFalse(result.any { it.id == "book3" })
    }

    @Test
    fun combinedFiltering_tagsAndCollections() = runBlocking {
        val dao = TestBookMetaDao(testBooks)
        
        // Setup: Collections
        val collection1 = CollectionEntity(id = "col1", name = "Collection 1")
        val collectionsWithBooks = listOf(
            CollectionWithBooks(
                collection = collection1,
                books = listOf(testBooks[0], testBooks[1])  // book1 (Fiction) and book2 (NonFiction)
            )
        )
        
        val collDao = TestCollectionDao(collectionsWithBooks)
        val useCase = LibrarySearchUseCase(
            BookRepository(dao),
            CollectionRepository(collDao)
        )
        
        // Filter by both collection1 AND tag "fiction" - should return only book1
        val filters = LibrarySearchFilters(
            collections = setOf("col1"),
            tags = setOf("fiction")
        )
        val result = useCase.observe(filters).first()
        
        assertEquals(1, result.size)
        assertEquals("book1", result[0].id)
    }

    @Test
    fun filtering_shouldPopulateCollectionsInResults() = runBlocking {
        val dao = TestBookMetaDao(testBooks)
        
        // Setup: Collections with books
        val collection1 = CollectionEntity(id = "col1", name = "Collection 1")
        val collection2 = CollectionEntity(id = "col2", name = "Collection 2")
        
        val collectionsWithBooks = listOf(
            CollectionWithBooks(
                collection = collection1,
                books = listOf(testBooks[0], testBooks[1])  // book1 and book2 in col1
            ),
            CollectionWithBooks(
                collection = collection2,
                books = listOf(testBooks[1])  // only book2 in col2
            )
        )
        
        val collDao = TestCollectionDao(collectionsWithBooks)
        val useCase = LibrarySearchUseCase(
            BookRepository(dao),
            CollectionRepository(collDao)
        )
        
        // Filter by format epub (should return book1 and book3)
        val filters = LibrarySearchFilters(formats = setOf("epub"))
        val result = useCase.observe(filters).first()
        
        assertEquals(2, result.size)
        
        // Verify book1 has col1 populated in its collections field
        val book1Result = result.find { it.id == "book1" }
        assertNotNull(book1Result)
        assertEquals(listOf("col1"), book1Result?.collections)
        
        // Verify book3 has empty collections (not in any collection)
        val book3Result = result.find { it.id == "book3" }
        assertNotNull(book3Result)
        assertTrue(book3Result?.collections?.isEmpty() ?: false)
    }

    @Test
    fun filtering_withCollectionFilter_shouldPopulateCollections() = runBlocking {
        val dao = TestBookMetaDao(testBooks)
        
        // Setup: Collections with books
        val collection1 = CollectionEntity(id = "col1", name = "Collection 1")
        val collection2 = CollectionEntity(id = "col2", name = "Collection 2")
        
        val collectionsWithBooks = listOf(
            CollectionWithBooks(
                collection = collection1,
                books = listOf(testBooks[0])  // book1 in col1
            ),
            CollectionWithBooks(
                collection = collection2,
                books = listOf(testBooks[0], testBooks[1])  // book1 and book2 in col2
            )
        )
        
        val collDao = TestCollectionDao(collectionsWithBooks)
        val useCase = LibrarySearchUseCase(
            BookRepository(dao),
            CollectionRepository(collDao)
        )
        
        // Filter by collection2 - should return book1 and book2
        val filters = LibrarySearchFilters(collections = setOf("col2"))
        val result = useCase.observe(filters).first()
        
        assertEquals(2, result.size)
        
        // Verify book1 has both col1 and col2 in its collections field
        val book1Result = result.find { it.id == "book1" }
        assertNotNull(book1Result)
        assertEquals(2, book1Result?.collections?.size)
        assertTrue(book1Result?.collections?.contains("col1") ?: false)
        assertTrue(book1Result?.collections?.contains("col2") ?: false)
        
        // Verify book2 has only col2 in its collections field
        val book2Result = result.find { it.id == "book2" }
        assertNotNull(book2Result)
        assertEquals(listOf("col2"), book2Result?.collections)
    }
}

// Test DAOs that provide minimal implementations for testing
class TestBookMetaDao(private val books: List<BookMeta>) : BookMetaDao {
    override fun getAllBooks(): Flow<List<BookMeta>> = MutableStateFlow(books)
    override suspend fun getAllBooksSnapshot(): List<BookMeta> = books
    override suspend fun getBookById(bookId: String): BookMeta? = books.find { it.id == bookId }
    override suspend fun getBookByPath(path: String): BookMeta? = books.find { it.path == path }
    override fun getFavoriteBooks(): Flow<List<BookMeta>> = MutableStateFlow(books.filter { it.isFavorite })
    override fun getRecentBooks(limit: Int): Flow<List<BookMeta>> = MutableStateFlow(books.take(limit))
    override fun searchBooks(query: String): Flow<List<BookMeta>> = MutableStateFlow(emptyList())
    override suspend fun insertBook(book: BookMeta) {}
    override suspend fun insertBooks(books: List<BookMeta>) {}
    override suspend fun updateBook(book: BookMeta) {}
    override suspend fun deleteBook(book: BookMeta) {}
    override suspend fun deleteBookById(bookId: String) {}
    override suspend fun deleteAllBooks() {}
    override suspend fun updateReadingProgress(bookId: String, page: Int, percent: Float, timestamp: Long) {}
    override suspend fun updateReadingProgressEnhanced(
        bookId: String,
        chapterIndex: Int,
        inPageIndex: Int,
        characterOffset: Int,
        previewText: String?,
        percentComplete: Float,
        timestamp: Long
    ) {}
    override suspend fun setFavorite(bookId: String, isFavorite: Boolean) {}
    override fun getBooksWithBookmarks(): Flow<List<BookMeta>> = MutableStateFlow(books.filter { it.currentPreviewText != null })
    override suspend fun getBooksWithBookmarksSortedByTitle(): List<BookMeta> = books.filter { it.currentPreviewText != null }.sortedBy { it.title }
    override suspend fun getBooksWithBookmarksSortedByPosition(): List<BookMeta> = books.filter { it.currentPreviewText != null }.sortedWith(
        compareBy({ it.currentChapterIndex }, { it.currentInPageIndex })
    )
    override suspend fun updateChapterVisibilitySettings(
        bookId: String,
        includeCover: Boolean?,
        includeFrontMatter: Boolean?,
        includeNonLinear: Boolean?
    ) {}
    override suspend fun resetChapterVisibilitySettings(bookId: String) {}
}

class TestCollectionDao(private val collectionsWithBooksData: List<CollectionWithBooks>) : CollectionDao {
    override fun observeCollectionsWithBooks(): Flow<List<CollectionWithBooks>> = MutableStateFlow(collectionsWithBooksData)
    override fun observeCollections(): Flow<List<CollectionEntity>> = MutableStateFlow(collectionsWithBooksData.map { it.collection })
    override suspend fun insertCollection(collection: CollectionEntity) {}
    override suspend fun updateCollection(collection: CollectionEntity) {}
    override suspend fun deleteCollection(collection: CollectionEntity) {}
    override suspend fun insertCrossRef(crossRef: com.rifters.riftedreader.data.database.entities.BookCollectionCrossRef) {}
    override suspend fun deleteCrossRef(bookId: String, collectionId: String) {}
    override suspend fun deleteCrossRefsForCollection(collectionId: String) {}
    override suspend fun isBookInCollection(bookId: String, collectionId: String): Boolean = false
    override suspend fun getCollectionCount(): Int = collectionsWithBooksData.size
    override suspend fun getAssignmentsCount(): Int = 0
    override suspend fun getCollectionsSnapshot(): List<CollectionEntity> = collectionsWithBooksData.map { it.collection }
    override suspend fun getCollectionIdsForBook(bookId: String): List<String> = emptyList()
}
