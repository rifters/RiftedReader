package com.rifters.riftedreader

import com.rifters.riftedreader.data.database.entities.Bookmark
import com.rifters.riftedreader.data.repository.BookmarkRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookmarkCreationTest {
    @Test
    fun lastRead_replacesPreviousBookmark() = runBlocking {
        val repository = MockBookmarkRepository()
        val first = bookmark(pageIndexHint = 2, savedAt = 1_000)
        val second = bookmark(pageIndexHint = 5, savedAt = 2_000)

        repository.saveLastRead(first)
        repository.saveLastRead(second)

        assertEquals(second, repository.loadLastRead("book1"))
    }

    @Test
    fun namedBookmarks_areSortedNewestFirst() = runBlocking {
        val repository = MockBookmarkRepository()
        val older = bookmark(pageIndexHint = 1, savedAt = 1_000)
        val newer = bookmark(pageIndexHint = 3, savedAt = 3_000)

        repository.saveNamedBookmark(older)
        repository.saveNamedBookmark(newer)

        assertEquals(listOf(newer, older), repository.loadNamedBookmarks("book1"))
    }

    @Test
    fun delete_removesMatchingBookmarks() = runBlocking {
        val repository = MockBookmarkRepository()
        val named = bookmark(savedAt = 1_000)

        repository.saveLastRead(named)
        repository.saveNamedBookmark(named)
        repository.delete(named)

        assertNull(repository.loadLastRead("book1"))
        assertEquals(emptyList<Bookmark>(), repository.loadNamedBookmarks("book1"))
    }

    @Test
    fun loadLastRead_returnsNullWhenMissing() = runBlocking {
        assertNull(MockBookmarkRepository().loadLastRead("missing"))
    }

    private fun bookmark(
        pageIndexHint: Int = 0,
        savedAt: Long = 1_000
    ) = Bookmark(
        bookId = "book1",
        chapterIndex = 2,
        charOffset = 120,
        pageIndexHint = pageIndexHint,
        nearestAnchorId = "chapter-two",
        nearestAnchorText = "Chapter Two",
        savedAt = savedAt,
        label = null
    )
}

private class MockBookmarkRepository : BookmarkRepository {
    private val lastRead = mutableMapOf<String, Bookmark>()
    private val named = mutableListOf<Bookmark>()

    override suspend fun saveLastRead(bookmark: Bookmark) {
        lastRead[bookmark.bookId] = bookmark
    }

    override suspend fun loadLastRead(bookId: String): Bookmark? {
        return lastRead[bookId]
    }

    override suspend fun saveNamedBookmark(bookmark: Bookmark) {
        named.add(bookmark)
    }

    override suspend fun loadNamedBookmarks(bookId: String): List<Bookmark> {
        return named.filter { it.bookId == bookId }.sortedByDescending { it.savedAt }
    }

    override suspend fun delete(bookmark: Bookmark) {
        named.remove(bookmark)
        if (lastRead[bookmark.bookId] == bookmark) {
            lastRead.remove(bookmark.bookId)
        }
    }
}
