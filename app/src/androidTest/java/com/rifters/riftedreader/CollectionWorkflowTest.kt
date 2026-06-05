package com.rifters.riftedreader

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rifters.riftedreader.data.database.BookDatabase
import com.rifters.riftedreader.data.database.entities.BookMeta
import com.rifters.riftedreader.data.repository.CollectionRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CollectionWorkflowTest {

    private lateinit var context: Context
    private lateinit var database: BookDatabase
    private lateinit var repository: CollectionRepository

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, BookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = CollectionRepository(database.collectionDao())

        database.bookMetaDao().insertBooks(
            listOf(
                testBook(id = "book-1", title = "First Book"),
                testBook(id = "book-2", title = "Second Book")
            )
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun createCollection_appearsInSnapshot() = runBlocking {
        val created = repository.createCollection("Favorites")

        val collections = repository.snapshot()

        assertEquals(listOf(created.id), collections.map { it.id })
        assertEquals(listOf("Favorites"), collections.map { it.name })
    }

    @Test
    fun addAndRemoveBookFromCollection_updatesCrossRefState() = runBlocking {
        val collection = repository.createCollection("Queue")

        repository.addBookToCollection("book-1", collection.id)

        assertTrue(repository.isBookInCollection("book-1", collection.id))
        assertEquals(listOf(collection.id), repository.getCollectionIdsForBook("book-1"))
        assertEquals(1, repository.assignmentCount())

        repository.removeBookFromCollection("book-1", collection.id)

        assertFalse(repository.isBookInCollection("book-1", collection.id))
        assertTrue(repository.getCollectionIdsForBook("book-1").isEmpty())
        assertEquals(0, repository.assignmentCount())
    }

    @Test
    fun deleteCollection_removesCrossRefs() = runBlocking {
        val collection = repository.createCollection("Archive")
        repository.addBookToCollection("book-1", collection.id)
        repository.addBookToCollection("book-2", collection.id)

        repository.deleteCollection(collection)

        assertTrue(repository.snapshot().isEmpty())
        assertEquals(0, repository.assignmentCount())
        assertTrue(repository.getCollectionIdsForBook("book-1").isEmpty())
        assertTrue(repository.getCollectionIdsForBook("book-2").isEmpty())
    }

    @Test
    fun renameCollection_persistsUpdatedName() = runBlocking {
        val created = repository.createCollection("To Rename")

        repository.updateCollection(created.copy(name = "Renamed Collection"))

        val updated = repository.snapshot().single()
        assertEquals(created.id, updated.id)
        assertEquals("Renamed Collection", updated.name)
    }

    private fun testBook(id: String, title: String): BookMeta =
        BookMeta(
            id = id,
            path = "/books/$id.epub",
            title = title,
            format = "epub",
            size = 1024L
        )
}
