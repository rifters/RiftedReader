package com.rifters.riftedreader.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.rifters.riftedreader.data.database.entities.BookCollectionCrossRef
import com.rifters.riftedreader.data.database.entities.CollectionEntity
import com.rifters.riftedreader.data.database.entities.CollectionWithBooks
import kotlinx.coroutines.flow.Flow

/**
 * Collection DAO mirrors LibreraReader's collection/tag capabilities (see
 * LIBRERA_ANALYSIS.md §2) but keeps the schema normalized for Room.
 */
@Dao
interface CollectionDao {

    @Transaction
    @Query("SELECT * FROM collections ORDER BY sortOrder ASC, name COLLATE NOCASE ASC")
    fun observeCollectionsWithBooks(): Flow<List<CollectionWithBooks>>

    @Query("SELECT * FROM collections ORDER BY sortOrder ASC, name COLLATE NOCASE ASC")
    fun observeCollections(): Flow<List<CollectionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCollection(collection: CollectionEntity)

    @Update
    suspend fun updateCollection(collection: CollectionEntity)

    @Delete
    suspend fun deleteCollection(collection: CollectionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCrossRef(crossRef: BookCollectionCrossRef)

    @Query("DELETE FROM book_collection_cross_ref WHERE bookId = :bookId AND collectionId = :collectionId")
    suspend fun deleteCrossRef(bookId: String, collectionId: String)

    @Query("DELETE FROM book_collection_cross_ref WHERE collectionId = :collectionId")
    suspend fun deleteCrossRefsForCollection(collectionId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM book_collection_cross_ref WHERE bookId = :bookId AND collectionId = :collectionId)")
    suspend fun isBookInCollection(bookId: String, collectionId: String): Boolean

    @Query("SELECT COUNT(*) FROM collections")
    suspend fun getCollectionCount(): Int

    @Query("SELECT COUNT(*) FROM book_collection_cross_ref")
    suspend fun getAssignmentsCount(): Int

    @Query("SELECT * FROM collections")
    suspend fun getCollectionsSnapshot(): List<CollectionEntity>
}
