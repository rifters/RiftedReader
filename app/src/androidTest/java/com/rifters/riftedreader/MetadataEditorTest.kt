package com.rifters.riftedreader

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rifters.riftedreader.data.database.BookDatabase
import com.rifters.riftedreader.data.database.entities.BookMeta
import com.rifters.riftedreader.data.database.entities.Bookmark
import com.rifters.riftedreader.data.preferences.LibraryPreferences
import com.rifters.riftedreader.data.repository.BookRepository
import com.rifters.riftedreader.data.repository.BookmarkRepository
import com.rifters.riftedreader.data.repository.CollectionRepository
import com.rifters.riftedreader.domain.library.BookMetadataUpdate
import com.rifters.riftedreader.domain.library.FavoriteUpdate
import com.rifters.riftedreader.domain.library.TagsUpdateMode
import com.rifters.riftedreader.ui.library.LibraryViewModel
import com.rifters.riftedreader.util.FileScanner
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MetadataEditorTest {

    private lateinit var context: Context
    private lateinit var database: BookDatabase
    private lateinit var repository: BookRepository
    private lateinit var viewModel: LibraryViewModel
    private lateinit var dataStoreFile: File

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        context.deleteSharedPreferences(LIBRARY_PREFS_NAME)

        database = Room.inMemoryDatabaseBuilder(context, BookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = BookRepository(database.bookMetaDao())

        database.bookMetaDao().insertBooks(
            listOf(
                testBook(id = "book-1", title = "Original Title", author = "Original Author", tags = listOf("existing")),
                testBook(id = "book-2", title = "Second Title", author = null, tags = listOf("shared")),
                testBook(id = "book-3", title = "Third Title", author = "Third Author", tags = emptyList())
            )
        )

        dataStoreFile = File(context.cacheDir, DATA_STORE_FILE_NAME)
        dataStoreFile.delete()
        viewModel = LibraryViewModel(
            repository = repository,
            bookmarkRepository = NoOpBookmarkRepository,
            collectionRepository = CollectionRepository(database.collectionDao()),
            fileScanner = FileScanner(context, repository),
            libraryPreferences = LibraryPreferences(context),
            dataStore = testDataStore(dataStoreFile)
        )

        viewModel.books.first { it.size == 3 }
    }

    @After
    fun tearDown() {
        context.deleteSharedPreferences(LIBRARY_PREFS_NAME)
        database.close()
        dataStoreFile.delete()
    }

    @Test
    fun updateTitleAndAuthorForSingleBook_persistsToDatabase() = runBlocking {
        viewModel.updateBookMetadata(
            bookIds = setOf("book-1"),
            update = BookMetadataUpdate(
                title = "Updated Title",
                author = "Updated Author"
            )
        )

        val updated = waitForBook("book-1") { it.title == "Updated Title" && it.author == "Updated Author" }
        assertEquals("Updated Title", updated.title)
        assertEquals("Updated Author", updated.author)
    }

    @Test
    fun bulkUpdateFavoriteState_appliesToAllSelectedBooks() = runBlocking {
        viewModel.updateBookMetadata(
            bookIds = setOf("book-1", "book-2"),
            update = BookMetadataUpdate(favorite = FavoriteUpdate.Favorite)
        )

        val first = waitForBook("book-1") { it.isFavorite }
        val second = waitForBook("book-2") { it.isFavorite }

        assertEquals(true, first.isFavorite)
        assertEquals(true, second.isFavorite)
        assertEquals(false, database.bookMetaDao().getBookById("book-3")?.isFavorite)
    }

    @Test
    fun bulkTagUpdate_mergesTagsForSelectedBooks() = runBlocking {
        viewModel.updateBookMetadata(
            bookIds = setOf("book-1", "book-2"),
            update = BookMetadataUpdate(
                tags = listOf("new", "shared"),
                tagsMode = TagsUpdateMode.APPEND
            )
        )

        val first = waitForBook("book-1") { it.tags == listOf("existing", "new", "shared") }
        val second = waitForBook("book-2") { it.tags == listOf("shared", "new") }

        assertEquals(listOf("existing", "new", "shared"), first.tags)
        assertEquals(listOf("shared", "new"), second.tags)
        assertEquals(emptyList<String>(), database.bookMetaDao().getBookById("book-3")?.tags)
    }

    @Test
    fun updateCoverPathViaRepository_persistsToDatabase() = runBlocking {
        assertNull(database.bookMetaDao().getBookById("book-1")?.coverPath)

        repository.updateCoverPath("book-1", "/covers/book-1.png")

        val updated = waitForBook("book-1") { it.coverPath == "/covers/book-1.png" }
        assertEquals("/covers/book-1.png", updated.coverPath)
    }

    private suspend fun waitForBook(bookId: String, predicate: (BookMeta) -> Boolean): BookMeta {
        return withTimeout(5_000) {
            while (true) {
                val book = database.bookMetaDao().getBookById(bookId)
                if (book != null && predicate(book)) {
                    return@withTimeout book
                }
                delay(50)
            }
            error("Timed out waiting for $bookId")
        }
    }

    private fun testBook(
        id: String,
        title: String,
        author: String?,
        tags: List<String>
    ): BookMeta = BookMeta(
        id = id,
        path = "/books/$id.epub",
        title = title,
        author = author,
        format = "epub",
        size = 1024L,
        tags = tags
    )

    private fun testDataStore(file: File): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { file }

    private object NoOpBookmarkRepository : BookmarkRepository {
        override suspend fun saveLastRead(bookmark: Bookmark) = Unit
        override suspend fun loadLastRead(bookId: String): Bookmark? = null
        override suspend fun loadLastReads(bookIds: Collection<String>): Map<String, Bookmark> = emptyMap()
        override suspend fun saveNamedBookmark(bookmark: Bookmark) = Unit
        override suspend fun loadNamedBookmarks(bookId: String): List<Bookmark> = emptyList()
        override suspend fun delete(bookmark: Bookmark) = Unit
    }

    private companion object {
        const val LIBRARY_PREFS_NAME = "library_preferences"
        const val DATA_STORE_FILE_NAME = "metadata-editor-test.preferences_pb"
    }
}
