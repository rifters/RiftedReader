package com.rifters.riftedreader.domain.library

import com.rifters.riftedreader.data.database.entities.BookMeta
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryStatisticsCalculatorTest {

    @Test
    fun calculate_buildsFormatAndReadingProgressBreakdowns() {
        val statistics = LibraryStatisticsCalculator.calculate(
            books = listOf(
                book(id = "1", format = "epub", percentComplete = 0f),
                book(id = "2", format = " EPUB ", percentComplete = 42f, isFavorite = true),
                book(id = "3", format = "pdf", percentComplete = 100f),
                book(id = "4", format = "", percentComplete = 100f)
            ),
            totalCollections = 3,
            assignmentCount = 5
        )

        assertEquals(4, statistics.totalBooks)
        assertEquals(3, statistics.totalFormats)
        assertEquals(3, statistics.totalCollections)
        assertEquals(5, statistics.booksInCollections)
        assertEquals(1, statistics.favoriteCount)
        assertEquals(60.5f, statistics.averageCompletion)
        assertEquals(
            linkedMapOf(
                "EPUB" to 2,
                "PDF" to 1,
                "Unknown" to 1
            ),
            statistics.formatDistribution
        )
        assertEquals(
            linkedMapOf(
                LibraryStatisticsCalculator.READING_PROGRESS_UNREAD to 1,
                LibraryStatisticsCalculator.READING_PROGRESS_IN_PROGRESS to 1,
                LibraryStatisticsCalculator.READING_PROGRESS_COMPLETED to 2
            ),
            statistics.readingProgressBreakdown
        )
    }

    @Test
    fun calculate_returnsZeroedBreakdownsForEmptyLibrary() {
        val statistics = LibraryStatisticsCalculator.calculate(
            books = emptyList(),
            totalCollections = 0,
            assignmentCount = 0
        )

        assertEquals(0, statistics.totalBooks)
        assertEquals(0, statistics.totalFormats)
        assertEquals(0, statistics.favoriteCount)
        assertEquals(0f, statistics.averageCompletion)
        assertEquals(emptyMap<String, Int>(), statistics.formatDistribution)
        assertEquals(
            linkedMapOf(
                LibraryStatisticsCalculator.READING_PROGRESS_UNREAD to 0,
                LibraryStatisticsCalculator.READING_PROGRESS_IN_PROGRESS to 0,
                LibraryStatisticsCalculator.READING_PROGRESS_COMPLETED to 0
            ),
            statistics.readingProgressBreakdown
        )
    }

    private fun book(
        id: String,
        format: String,
        percentComplete: Float,
        isFavorite: Boolean = false
    ) = BookMeta(
        id = id,
        path = "/books/$id",
        title = "Book $id",
        format = format,
        size = 1024L,
        percentComplete = percentComplete,
        isFavorite = isFavorite
    )
}
