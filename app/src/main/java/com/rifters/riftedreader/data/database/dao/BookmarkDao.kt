package com.rifters.riftedreader.data.database.dao

import androidx.room.*
import com.rifters.riftedreader.data.database.entities.Bookmark
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for Bookmark operations
 */
@Dao
interface BookmarkDao {
    
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY createdAt DESC")
    fun getBookmarksForBook(bookId: String): Flow<List<Bookmark>>
    
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY createdAt DESC")
    suspend fun getBookmarksForBookSnapshot(bookId: String): List<Bookmark>
    
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY chapterIndex ASC, inChapterPage ASC")
    fun getBookmarksForBookSortedByPosition(bookId: String): Flow<List<Bookmark>>
    
    @Query("SELECT * FROM bookmarks WHERE id = :bookmarkId")
    suspend fun getBookmarkById(bookmarkId: String): Bookmark?
    
    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC LIMIT :limit")
    fun getRecentBookmarks(limit: Int = 20): Flow<List<Bookmark>>
    
    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    fun getAllBookmarks(): Flow<List<Bookmark>>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: Bookmark)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmarks(bookmarks: List<Bookmark>)
    
    @Update
    suspend fun updateBookmark(bookmark: Bookmark)
    
    @Delete
    suspend fun deleteBookmark(bookmark: Bookmark)
    
    @Query("DELETE FROM bookmarks WHERE id = :bookmarkId")
    suspend fun deleteBookmarkById(bookmarkId: String)
    
    @Query("DELETE FROM bookmarks WHERE bookId = :bookId")
    suspend fun deleteAllBookmarksForBook(bookId: String)
    
    @Query("DELETE FROM bookmarks")
    suspend fun deleteAllBookmarks()
    
    @Query("SELECT COUNT(*) FROM bookmarks WHERE bookId = :bookId")
    suspend fun getBookmarkCountForBook(bookId: String): Int
}
