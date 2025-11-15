package com.rifters.riftedreader.domain.library

import com.rifters.riftedreader.data.database.entities.BookMeta

/**
 * Smart collection descriptors mirror LibreraReader's dynamic shelves (see
 * LIBRERA_ANALYSIS.md §2 "Library Features" – Recent books, Favorites, etc.)
 * but keep the implementation focused on Kotlin data classes. Each descriptor
 * defines an identifier and a predicate that determines whether a book should
 * appear in that collection.
 */
object SmartCollections {

    private const val RECENT_WINDOW_MILLIS = 1000L * 60L * 60L * 24L * 14L // 14 days

    private val definitions: List<SmartCollectionDefinition> = listOf(
        SmartCollectionDefinition(
            id = SmartCollectionId.RECENTLY_OPENED,
            predicate = { book, now ->
                book.lastOpened > 0 && (now - book.lastOpened) <= RECENT_WINDOW_MILLIS
            }
        ),
        SmartCollectionDefinition(
            id = SmartCollectionId.IN_PROGRESS,
            predicate = { book, _ ->
                book.totalPages > 0 && book.percentComplete > 0f && book.percentComplete < 99.5f
            }
        ),
        SmartCollectionDefinition(
            id = SmartCollectionId.COMPLETED,
            predicate = { book, _ ->
                book.totalPages > 0 && book.percentComplete >= 99.5f
            }
        ),
        SmartCollectionDefinition(
            id = SmartCollectionId.NOT_STARTED,
            predicate = { book, _ ->
                book.totalPages == 0 || book.percentComplete <= 0.01f
            }
        )
    )

    fun definitions(): List<SmartCollectionDefinition> = definitions

    fun definition(id: SmartCollectionId): SmartCollectionDefinition? =
        definitions.firstOrNull { it.id == id }

    fun matches(id: SmartCollectionId, book: BookMeta, now: Long = System.currentTimeMillis()): Boolean {
        val definition = definition(id) ?: return false
        return definition.predicate(book, now)
    }

    fun snapshot(books: List<BookMeta>, now: Long = System.currentTimeMillis()): List<SmartCollectionSnapshot> {
        return definitions.map { definition ->
            val count = books.count { definition.predicate(it, now) }
            SmartCollectionSnapshot(id = definition.id, count = count)
        }
    }
}

enum class SmartCollectionId {
    RECENTLY_OPENED,
    IN_PROGRESS,
    COMPLETED,
    NOT_STARTED
}

data class SmartCollectionDefinition(
    val id: SmartCollectionId,
    val predicate: (BookMeta, Long) -> Boolean
)

data class SmartCollectionSnapshot(
    val id: SmartCollectionId,
    val count: Int
)
