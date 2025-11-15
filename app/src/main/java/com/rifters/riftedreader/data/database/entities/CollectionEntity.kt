package com.rifters.riftedreader.data.database.entities

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Junction
import androidx.room.PrimaryKey
import androidx.room.Relation
import java.util.UUID

/**
 * Collections mirror LibreraReader's flexible grouping system (`foobnix/dao2/FileMeta`) but
 * live in a dedicated table so we can attach metadata, sort order, and cover art.
 */
@Entity(
    tableName = "collections",
    indices = [Index(value = ["name"], unique = true)]
)
data class CollectionEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String? = null,
    val coverPath: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val sortOrder: Int = 0
)

@Entity(
    tableName = "book_collection_cross_ref",
    primaryKeys = ["bookId", "collectionId"],
    indices = [
        Index(value = ["collectionId"]),
        Index(value = ["bookId"])
    ]
)
data class BookCollectionCrossRef(
    val bookId: String,
    val collectionId: String,
    val addedAt: Long = System.currentTimeMillis()
)

data class CollectionWithBooks(
    @Embedded val collection: CollectionEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = BookCollectionCrossRef::class,
            parentColumn = "collectionId",
            entityColumn = "bookId"
        )
    )
    val books: List<BookMeta>
)
