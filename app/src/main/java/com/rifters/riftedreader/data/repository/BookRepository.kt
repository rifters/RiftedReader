package com.rifters.riftedreader.data.repository

import com.rifters.riftedreader.data.database.dao.BookMetaDao
import com.rifters.riftedreader.data.database.entities.BookMeta
import kotlinx.coroutines.flow.Flow

/**
 * Repository for managing book metadata
 */
class BookRepository(private val bookMetaDao: BookMetaDao) {
    
    val allBooks: Flow<List<BookMeta>> = bookMetaDao.getAllBooks()
    
    val favoriteBooks: Flow<List<BookMeta>> = bookMetaDao.getFavoriteBooks()
    
    fun getRecentBooks(limit: Int = 10): Flow<List<BookMeta>> {
        return bookMetaDao.getRecentBooks(limit)
    }

    suspend fun getAllBooksSnapshot(): List<BookMeta> {
        return bookMetaDao.getAllBooksSnapshot()
    }
    
    fun searchBooks(query: String): Flow<List<BookMeta>> {
        return bookMetaDao.searchBooks(query)
    }
    
    suspend fun getBookById(bookId: String): BookMeta? {
        return bookMetaDao.getBookById(bookId)
    }
    
    suspend fun getBookByPath(path: String): BookMeta? {
        return bookMetaDao.getBookByPath(path)
    }
    
    suspend fun insertBook(book: BookMeta) {
        bookMetaDao.insertBook(book)
    }
    
    suspend fun insertBooks(books: List<BookMeta>) {
        bookMetaDao.insertBooks(books)
    }
    
    suspend fun updateBook(book: BookMeta) {
        bookMetaDao.updateBook(book)
    }
    
    suspend fun deleteBook(book: BookMeta) {
        bookMetaDao.deleteBook(book)
    }
    
    suspend fun updateReadingProgress(bookId: String, page: Int, totalPages: Int) {
        val percent = if (totalPages > 0) (page.toFloat() / totalPages) * 100 else 0f
        bookMetaDao.updateReadingProgress(bookId, page, percent, System.currentTimeMillis())
    }
    
    suspend fun setFavorite(bookId: String, isFavorite: Boolean) {
        bookMetaDao.setFavorite(bookId, isFavorite)
    }
}
