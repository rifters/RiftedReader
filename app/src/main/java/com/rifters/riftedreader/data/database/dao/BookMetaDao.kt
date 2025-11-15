package com.rifters.riftedreader.data.database.dao

import androidx.room.*
import com.rifters.riftedreader.data.database.entities.BookMeta
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for BookMeta
 */
@Dao
interface BookMetaDao {
    
    @Query("SELECT * FROM books ORDER BY lastOpened DESC")
    fun getAllBooks(): Flow<List<BookMeta>>

    @Query("SELECT * FROM books ORDER BY title COLLATE NOCASE ASC")
    suspend fun getAllBooksSnapshot(): List<BookMeta>
    
    @Query("SELECT * FROM books WHERE id = :bookId")
    suspend fun getBookById(bookId: String): BookMeta?
    
    @Query("SELECT * FROM books WHERE path = :path")
    suspend fun getBookByPath(path: String): BookMeta?
    
    @Query("SELECT * FROM books WHERE isFavorite = 1 ORDER BY title ASC")
    fun getFavoriteBooks(): Flow<List<BookMeta>>
    
    @Query("SELECT * FROM books ORDER BY lastOpened DESC LIMIT :limit")
    fun getRecentBooks(limit: Int = 10): Flow<List<BookMeta>>
    
    @Query("SELECT * FROM books WHERE title LIKE '%' || :query || '%' OR author LIKE '%' || :query || '%'")
    fun searchBooks(query: String): Flow<List<BookMeta>>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBook(book: BookMeta)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBooks(books: List<BookMeta>)
    
    @Update
    suspend fun updateBook(book: BookMeta)
    
    @Delete
    suspend fun deleteBook(book: BookMeta)
    
    @Query("DELETE FROM books WHERE id = :bookId")
    suspend fun deleteBookById(bookId: String)
    
    @Query("DELETE FROM books")
    suspend fun deleteAllBooks()
    
    @Query("UPDATE books SET currentPage = :page, percentComplete = :percent, lastOpened = :timestamp WHERE id = :bookId")
    suspend fun updateReadingProgress(bookId: String, page: Int, percent: Float, timestamp: Long)
    
    @Query("UPDATE books SET isFavorite = :isFavorite WHERE id = :bookId")
    suspend fun setFavorite(bookId: String, isFavorite: Boolean)
}
