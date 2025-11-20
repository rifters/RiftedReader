package com.rifters.riftedreader.data.database.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Entity representing a bookmark in a book.
 * Implements Approach C: Hybrid - Chapter + Page + Character Offset
 * 
 * This approach provides:
 * - Fast restoration when font unchanged (use page number)
 * - Precise restoration when font changed (use character offset)
 * - Human-readable display with preview text
 * - Fallback mechanism if character offset fails
 */
@Entity(
    tableName = "bookmarks",
    foreignKeys = [
        ForeignKey(
            entity = BookMeta::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["bookId"]),
        Index(value = ["createdAt"])
    ]
)
data class Bookmark(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    
    // Book reference
    val bookId: String,
    
    // Position information - hybrid approach
    val chapterIndex: Int,
    val inChapterPage: Int,           // Quick approximation for when font unchanged
    val characterOffset: Int,         // Precise position for when font changed
    
    // Contextual information
    val chapterTitle: String,
    val previewText: String,          // First ~50 chars of visible text
    val percentageThrough: Float,     // Overall progress in book
    
    // Metadata
    val createdAt: Long = System.currentTimeMillis(),
    val fontSize: Float               // Font size when bookmark created
)
