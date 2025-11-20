package com.rifters.riftedreader.data.repository

import com.rifters.riftedreader.data.database.dao.BookmarkDao
import com.rifters.riftedreader.data.database.entities.Bookmark
import kotlinx.coroutines.flow.Flow

/**
 * Repository for managing bookmarks.
 * Handles business logic for creating, retrieving, and restoring bookmarks.
 */
open class BookmarkRepository(private val bookmarkDao: BookmarkDao) {
    
    /**
     * Get all bookmarks for a specific book, ordered by creation time (most recent first)
     */
    fun getBookmarksForBook(bookId: String): Flow<List<Bookmark>> {
        return bookmarkDao.getBookmarksForBook(bookId)
    }
    
    /**
     * Get bookmarks for a book sorted by their position in the book
     */
    fun getBookmarksForBookSortedByPosition(bookId: String): Flow<List<Bookmark>> {
        return bookmarkDao.getBookmarksForBookSortedByPosition(bookId)
    }
    
    /**
     * Get a snapshot of bookmarks for a book (not reactive)
     */
    open suspend fun getBookmarksForBookSnapshot(bookId: String): List<Bookmark> {
        return bookmarkDao.getBookmarksForBookSnapshot(bookId)
    }
    
    /**
     * Get a specific bookmark by ID
     */
    open suspend fun getBookmarkById(bookmarkId: String): Bookmark? {
        return bookmarkDao.getBookmarkById(bookmarkId)
    }
    
    /**
     * Get recent bookmarks across all books
     */
    fun getRecentBookmarks(limit: Int = 20): Flow<List<Bookmark>> {
        return bookmarkDao.getRecentBookmarks(limit)
    }
    
    /**
     * Get all bookmarks across all books
     */
    fun getAllBookmarks(): Flow<List<Bookmark>> {
        return bookmarkDao.getAllBookmarks()
    }
    
    /**
     * Create a new bookmark
     */
    open suspend fun createBookmark(bookmark: Bookmark) {
        bookmarkDao.insertBookmark(bookmark)
    }
    
    /**
     * Update an existing bookmark
     */
    open suspend fun updateBookmark(bookmark: Bookmark) {
        bookmarkDao.updateBookmark(bookmark)
    }
    
    /**
     * Delete a bookmark
     */
    open suspend fun deleteBookmark(bookmark: Bookmark) {
        bookmarkDao.deleteBookmark(bookmark)
    }
    
    /**
     * Delete a bookmark by ID
     */
    open suspend fun deleteBookmarkById(bookmarkId: String) {
        bookmarkDao.deleteBookmarkById(bookmarkId)
    }
    
    /**
     * Delete all bookmarks for a specific book
     */
    open suspend fun deleteAllBookmarksForBook(bookId: String) {
        bookmarkDao.deleteAllBookmarksForBook(bookId)
    }
    
    /**
     * Get the count of bookmarks for a book
     */
    open suspend fun getBookmarkCountForBook(bookId: String): Int {
        return bookmarkDao.getBookmarkCountForBook(bookId)
    }
}
