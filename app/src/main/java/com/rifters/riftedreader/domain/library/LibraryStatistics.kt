package com.rifters.riftedreader.domain.library

import com.rifters.riftedreader.data.repository.BookRepository
import com.rifters.riftedreader.data.repository.CollectionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LibraryStatistics(
    val totalBooks: Int,
    val totalFormats: Int,
    val totalCollections: Int,
    val booksInCollections: Int,
    val favoriteCount: Int,
    val averageCompletion: Float
)

class LibraryStatisticsCalculator(
    private val bookRepository: BookRepository,
    private val collectionRepository: CollectionRepository
) {

    suspend fun compute(): LibraryStatistics = withContext(Dispatchers.Default) {
        val books = bookRepository.getAllBooksSnapshot()
        val collections = collectionRepository.snapshot()
        val assignmentCount = collectionRepository.assignmentCount()

        val totalBooks = books.size
        val totalFormats = books.map { it.format.lowercase() }.toSet().size
        val totalCollections = collections.size
        val favoriteCount = books.count { it.isFavorite }
        val averageCompletion = books.takeIf { it.isNotEmpty() }
            ?.map { it.percentComplete }
            ?.average()
            ?.toFloat()
            ?: 0f

        LibraryStatistics(
            totalBooks = totalBooks,
            totalFormats = totalFormats,
            totalCollections = totalCollections,
            booksInCollections = assignmentCount,
            favoriteCount = favoriteCount,
            averageCompletion = averageCompletion
        )
    }
}
