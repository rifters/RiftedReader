package com.rifters.riftedreader.data.repository

import com.rifters.riftedreader.data.database.dao.BookmarkDao
import com.rifters.riftedreader.data.database.entities.Bookmark
import com.rifters.riftedreader.data.database.entities.BookmarkEntity

interface BookmarkRepository {
    suspend fun saveLastRead(bookmark: Bookmark)
    suspend fun loadLastRead(bookId: String): Bookmark?
    suspend fun saveNamedBookmark(bookmark: Bookmark)
    suspend fun loadNamedBookmarks(bookId: String): List<Bookmark>
    suspend fun delete(bookmark: Bookmark)
}

class RoomBookmarkRepository(
    private val bookmarkDao: BookmarkDao
) : BookmarkRepository {
    override suspend fun saveLastRead(bookmark: Bookmark) {
        bookmarkDao.upsert(BookmarkEntity.lastRead(bookmark))
    }

    override suspend fun loadLastRead(bookId: String): Bookmark? {
        return bookmarkDao.loadLastRead(bookId)?.toBookmark()
    }

    override suspend fun saveNamedBookmark(bookmark: Bookmark) {
        bookmarkDao.upsert(BookmarkEntity.named(bookmark))
    }

    override suspend fun loadNamedBookmarks(bookId: String): List<Bookmark> {
        return bookmarkDao.loadNamedBookmarks(bookId).map { it.toBookmark() }
    }

    override suspend fun delete(bookmark: Bookmark) {
        bookmarkDao.deleteMatching(
            bookId = bookmark.bookId,
            chapterIndex = bookmark.chapterIndex,
            charOffset = bookmark.charOffset,
            savedAt = bookmark.savedAt
        )
    }
}
