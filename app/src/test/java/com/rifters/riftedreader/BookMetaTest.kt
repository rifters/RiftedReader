package com.rifters.riftedreader

import com.rifters.riftedreader.data.database.entities.BookMeta
import org.junit.Test
import org.junit.Assert.*

/**
 * Example local unit test for BookMeta entity
 */
class BookMetaTest {
    @Test
    fun bookMeta_creation_isCorrect() {
        val book = BookMeta(
            id = "test-id",
            path = "/storage/emulated/0/Books/test.epub",
            title = "Test Book",
            author = "Test Author",
            format = "EPUB",
            size = 1024L,
            totalPages = 100
        )
        
        assertEquals("test-id", book.id)
        assertEquals("Test Book", book.title)
        assertEquals("Test Author", book.author)
        assertEquals("EPUB", book.format)
        assertEquals(100, book.totalPages)
        assertEquals(0, book.currentPage)
        assertEquals(0f, book.percentComplete)
        assertFalse(book.isFavorite)
    }
}
