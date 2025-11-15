package com.rifters.riftedreader.domain.library

import com.rifters.riftedreader.data.database.entities.BookMeta
import com.rifters.riftedreader.data.repository.BookRepository
import com.rifters.riftedreader.data.repository.CollectionRepository
import com.rifters.riftedreader.domain.library.SmartCollectionId
import com.rifters.riftedreader.domain.library.SmartCollections
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Filter descriptor covering Stage 7 requirements (collections, tags,
 * favorites, formats). Filtering happens in-memory for now; once Room
 * queries are extended we can move the logic into SQL.
 */
data class LibrarySearchFilters(
    val query: String = "",
    val formats: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
    val collections: Set<String> = emptySet(),
    val favoritesOnly: Boolean = false,
    val smartCollection: SmartCollectionId? = null
)

class LibrarySearchUseCase(
    private val bookRepository: BookRepository,
    private val collectionRepository: CollectionRepository
) {

    fun observe(filters: LibrarySearchFilters): Flow<List<BookMeta>> {
        return bookRepository.allBooks.combine(collectionRepository.collectionsWithBooks) { books, collectionsWithBooks ->
            // Build a map of bookId -> list of collectionIds for efficient lookup
            val bookCollectionsMap = mutableMapOf<String, MutableList<String>>()
            collectionsWithBooks.forEach { collectionWithBooks ->
                collectionWithBooks.books.forEach { book ->
                    bookCollectionsMap.getOrPut(book.id) { mutableListOf() }.add(collectionWithBooks.collection.id)
                }
            }
            
            books.filter { book ->
                val bookWithCollections = book.copy(collections = bookCollectionsMap[book.id] ?: emptyList())
                matchesQuery(bookWithCollections, filters.query) &&
                    matchesFormats(bookWithCollections, filters.formats) &&
                    matchesFavorites(bookWithCollections, filters.favoritesOnly) &&
                    matchesTags(bookWithCollections, filters.tags) &&
                        matchesCollections(bookWithCollections, filters.collections) &&
                        matchesSmartCollection(bookWithCollections, filters.smartCollection)
            }
        }
    }

    private fun matchesQuery(book: BookMeta, query: String): Boolean {
        if (query.isBlank()) return true
        val normalized = query.trim().lowercase()
        return book.title.lowercase().contains(normalized) ||
            (book.author?.lowercase()?.contains(normalized) ?: false)
    }

    private fun matchesFormats(book: BookMeta, formats: Set<String>): Boolean {
        if (formats.isEmpty()) return true
        return formats.contains(book.format.lowercase())
    }

    private fun matchesFavorites(book: BookMeta, favoritesOnly: Boolean): Boolean {
        return if (!favoritesOnly) true else book.isFavorite
    }

    private fun matchesTags(book: BookMeta, tags: Set<String>): Boolean {
        if (tags.isEmpty()) return true
        if (book.tags.isEmpty()) return false
        val normalizedTags = tags.map { it.lowercase() }.toSet()
        return book.tags.any { tag -> tag.lowercase() in normalizedTags }
    }

    private fun matchesCollections(book: BookMeta, collections: Set<String>): Boolean {
        if (collections.isEmpty()) return true
        if (book.collections.isEmpty()) return false
        return book.collections.any { collectionId -> collectionId in collections }
    }

    private fun matchesSmartCollection(book: BookMeta, smartCollectionId: SmartCollectionId?): Boolean {
        if (smartCollectionId == null) return true
        return SmartCollections.matches(smartCollectionId, book)
    }
}
