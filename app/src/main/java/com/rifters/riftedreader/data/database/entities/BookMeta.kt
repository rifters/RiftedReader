package com.rifters.riftedreader.data.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.rifters.riftedreader.data.database.Converters
import java.util.UUID

/**
 * Entity representing book metadata stored in the database
 */
@Entity(tableName = "books")
@TypeConverters(Converters::class)
data class BookMeta(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    
    // File information
    val path: String,
    val format: String,
    val size: Long,
    val dateAdded: Long = System.currentTimeMillis(),
    val lastOpened: Long = 0,
    
    // Book metadata
    val title: String,
    val author: String? = null,
    val publisher: String? = null,
    val year: String? = null,
    val language: String? = null,
    val description: String? = null,
    
    // Reading progress
    val currentPage: Int = 0,
    val totalPages: Int = 0,
    val percentComplete: Float = 0f,
    
    // Visual
    val coverPath: String? = null,
    
    // Organization
    val isFavorite: Boolean = false,
    val tags: List<String> = emptyList(),
    val collections: List<String> = emptyList()
)
