package com.rifters.riftedreader

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rifters.riftedreader.data.database.BookDatabase
import com.rifters.riftedreader.data.database.entities.BookMeta
import com.rifters.riftedreader.data.preferences.LibraryPreferences
import com.rifters.riftedreader.data.repository.BookRepository
import com.rifters.riftedreader.data.repository.CollectionRepository
import com.rifters.riftedreader.domain.library.LibrarySearchFilters
import com.rifters.riftedreader.domain.library.LibrarySearchUseCase
import com.rifters.riftedreader.domain.library.SmartCollectionId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibrarySearchFilterTest {

    private lateinit var context: Context
    private lateinit var database: BookDatabase
    private lateinit var bookRepository: BookRepository
    private lateinit var collectionRepository: CollectionRepository
    private lateinit var searchUseCase: LibrarySearchUseCase

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        context.deleteSharedPreferences(LIBRARY_PREFS_NAME)

        database = Room.inMemoryDatabaseBuilder(context, BookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        bookRepository = BookRepository(database.bookMetaDao())
        collectionRepository = CollectionRepository(database.collectionDao())
        searchUseCase = LibrarySearchUseCase(bookRepository, collectionRepository)

        database.bookMetaDao().insertBooks(
            listOf(
                testBook(
                    id = "epub-normal",
                    title = "EPUB Normal",
                    format = "epub",
                    isFavorite = false,
                    percentComplete = 0f,
                    totalPages = 100
                ),
                testBook(
                    id = "pdf-favorite",
                    title = "PDF Favorite",
                    format = "pdf",
                    isFavorite = true,
                    percentComplete = 55f,
                    totalPages = 200
                ),
                testBook(
                    id = "epub-favorite",
                    title = "EPUB Favorite",
                    format = "epub",
                    isFavorite = true,
                    percentComplete = 100f,
                    totalPages = 100
                )
            )
        )
    }

    @After
    fun tearDown() {
        context.deleteSharedPreferences(LIBRARY_PREFS_NAME)
        database.close()
    }

    @Test
    fun filterByFormat_returnsOnlyMatchingBooks() = runBlocking {
        val result = searchUseCase.observe(
            LibrarySearchFilters(formats = setOf("pdf"))
        ).first()

        assertEquals(listOf("pdf-favorite"), result.map { it.id })
    }

    @Test
    fun filterByFavorite_returnsOnlyFavorites() = runBlocking {
        val result = searchUseCase.observe(
            LibrarySearchFilters(favoritesOnly = true)
        ).first()

        assertEquals(setOf("pdf-favorite", "epub-favorite"), result.map { it.id }.toSet())
    }

    @Test
    fun smartCollectionInProgress_returnsBooksBetweenZeroAndHundredPercent() = runBlocking {
        val result = searchUseCase.observe(
            LibrarySearchFilters(smartCollection = SmartCollectionId.IN_PROGRESS)
        ).first()

        assertEquals(listOf("pdf-favorite"), result.map { it.id })
    }

    @Test
    fun savedSearch_roundTripsThroughLibraryPreferences() {
        val preferences = LibraryPreferences(context)
        val filters = LibrarySearchFilters(
            formats = setOf("epub"),
            favoritesOnly = true,
            smartCollection = SmartCollectionId.IN_PROGRESS
        )

        val saved = preferences.addSavedSearch("Instrumented Search", filters)
        val reloaded = LibraryPreferences(context).getSavedSearch(saved.id)

        assertEquals("Instrumented Search", reloaded?.name)
        assertEquals(filters, reloaded?.filters)
    }

    @Test
    fun combinedFormatAndFavoriteFilter_intersectsResults() = runBlocking {
        val result = searchUseCase.observe(
            LibrarySearchFilters(
                formats = setOf("epub"),
                favoritesOnly = true
            )
        ).first()

        assertEquals(listOf("epub-favorite"), result.map { it.id })
        assertTrue(result.all { it.format == "epub" && it.isFavorite })
    }

    private fun testBook(
        id: String,
        title: String,
        format: String,
        isFavorite: Boolean,
        percentComplete: Float,
        totalPages: Int
    ): BookMeta = BookMeta(
        id = id,
        path = "/books/$id.$format",
        title = title,
        format = format,
        size = 2048L,
        isFavorite = isFavorite,
        percentComplete = percentComplete,
        totalPages = totalPages
    )

    private companion object {
        const val LIBRARY_PREFS_NAME = "library_preferences"
    }
}
