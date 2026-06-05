package com.rifters.riftedreader.domain.library

import com.rifters.riftedreader.data.database.entities.BookMeta
import com.rifters.riftedreader.data.repository.BookRepository
import com.rifters.riftedreader.data.repository.CollectionRepository
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LibraryStatistics(
    val totalBooks: Int,
    val totalFormats: Int,
    val totalCollections: Int,
    val booksInCollections: Int,
    val favoriteCount: Int,
    val averageCompletion: Float,
    val formatDistribution: Map<String, Int>,
    val readingProgressBreakdown: Map<String, Int>
)

class LibraryStatisticsCalculator(
    private val bookRepository: BookRepository,
    private val collectionRepository: CollectionRepository
) {

    suspend fun compute(): LibraryStatistics = withContext(Dispatchers.Default) {
        val books = bookRepository.getAllBooksSnapshot()
        val collections = collectionRepository.snapshot()
        val assignmentCount = collectionRepository.assignmentCount()

        calculate(
            books = books,
            totalCollections = collections.size,
            assignmentCount = assignmentCount
        )
    }

    companion object {
        internal const val READING_PROGRESS_UNREAD = "unread"
        internal const val READING_PROGRESS_IN_PROGRESS = "in_progress"
        internal const val READING_PROGRESS_COMPLETED = "completed"
        private const val UNKNOWN_FORMAT = "Unknown"

        internal fun calculate(
            books: List<BookMeta>,
            totalCollections: Int,
            assignmentCount: Int
        ): LibraryStatistics {
            val formatDistribution = books
                .groupingBy { normalizeFormat(it.format) }
                .eachCount()
                .toList()
                .sortedWith(compareByDescending { it.second }.thenBy { it.first })
                .toMap(linkedMapOf())

            val averageCompletion = books.takeIf { it.isNotEmpty() }
                ?.map { it.percentComplete }
                ?.average()
                ?.toFloat()
                ?: 0f

            return LibraryStatistics(
                totalBooks = books.size,
                totalFormats = formatDistribution.size,
                totalCollections = totalCollections,
                booksInCollections = assignmentCount,
                favoriteCount = books.count { it.isFavorite },
                averageCompletion = averageCompletion,
                formatDistribution = formatDistribution,
                readingProgressBreakdown = linkedMapOf(
                    READING_PROGRESS_UNREAD to books.count { it.percentComplete <= 0f },
                    READING_PROGRESS_IN_PROGRESS to books.count {
                        it.percentComplete > 0f && it.percentComplete < 100f
                    },
                    READING_PROGRESS_COMPLETED to books.count { it.percentComplete >= 100f }
                )
            )
        }

        private fun normalizeFormat(format: String): String {
            return format.trim()
                .takeIf { it.isNotEmpty() }
                ?.uppercase(Locale.ROOT)
                ?: UNKNOWN_FORMAT
        }
    }
}
