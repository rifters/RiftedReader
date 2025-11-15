package com.rifters.riftedreader.data.repository

import com.rifters.riftedreader.data.database.dao.CollectionDao
import com.rifters.riftedreader.data.database.entities.BookCollectionCrossRef
import com.rifters.riftedreader.data.database.entities.CollectionEntity
import com.rifters.riftedreader.data.database.entities.CollectionWithBooks
import kotlinx.coroutines.flow.Flow

/**
 * Repository façade for collection management.
 *
 * LibreraReader stores collections alongside the book metadata table. We split
 * the concept into dedicated tables for easier synchronization and to support
 * Stage 7 features like smart collections and statistics.
 */
class CollectionRepository(private val collectionDao: CollectionDao) {

    val collections: Flow<List<CollectionEntity>> = collectionDao.observeCollections()

    val collectionsWithBooks: Flow<List<CollectionWithBooks>> = collectionDao.observeCollectionsWithBooks()

    suspend fun createCollection(name: String, description: String? = null): CollectionEntity {
        val collection = CollectionEntity(name = name.trim(), description = description?.trim())
        collectionDao.insertCollection(collection)
        return collection
    }

    suspend fun updateCollection(collection: CollectionEntity) {
        collectionDao.updateCollection(collection.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteCollection(collection: CollectionEntity) {
        collectionDao.deleteCrossRefsForCollection(collection.id)
        collectionDao.deleteCollection(collection)
    }

    suspend fun addBookToCollection(bookId: String, collectionId: String) {
        collectionDao.insertCrossRef(
            BookCollectionCrossRef(
                bookId = bookId,
                collectionId = collectionId
            )
        )
    }

    suspend fun removeBookFromCollection(bookId: String, collectionId: String) {
        collectionDao.deleteCrossRef(bookId, collectionId)
    }

    suspend fun isBookInCollection(bookId: String, collectionId: String): Boolean {
        return collectionDao.isBookInCollection(bookId, collectionId)
    }

    suspend fun collectionCount(): Int = collectionDao.getCollectionCount()

    suspend fun assignmentCount(): Int = collectionDao.getAssignmentsCount()

    suspend fun snapshot(): List<CollectionEntity> = collectionDao.getCollectionsSnapshot()

    suspend fun getCollectionIdsForBook(bookId: String): List<String> =
        collectionDao.getCollectionIdsForBook(bookId)
}
