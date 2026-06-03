package com.rifters.riftedreader.data.database.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Stable reader position saved for a book.
 *
 * charOffset is the restore source of truth when slice metadata is available;
 * pageIndexHint is only a fallback for legacy or not-yet-sliced content.
 */
data class Bookmark(
    val bookId: String,
    val chapterIndex: Int,
    val charOffset: Int,
    val pageIndexHint: Int,
    val nearestAnchorId: String,
    val nearestAnchorText: String,
    val savedAt: Long,
    val label: String? = null
)

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
        Index(value = ["bookId", "isLastRead"]),
        Index(value = ["savedAt"])
    ]
)
data class BookmarkEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val bookId: String,
    val chapterIndex: Int,
    val charOffset: Int,
    val pageIndexHint: Int,
    val nearestAnchorId: String,
    val nearestAnchorText: String,
    val savedAt: Long,
    val label: String? = null,
    val isLastRead: Boolean = false
) {
    fun toBookmark(): Bookmark = Bookmark(
        bookId = bookId,
        chapterIndex = chapterIndex,
        charOffset = charOffset,
        pageIndexHint = pageIndexHint,
        nearestAnchorId = nearestAnchorId,
        nearestAnchorText = nearestAnchorText,
        savedAt = savedAt,
        label = label
    )

    companion object {
        fun lastRead(bookmark: Bookmark): BookmarkEntity = fromBookmark(
            bookmark = bookmark,
            id = "last_read_${bookmark.bookId}",
            isLastRead = true
        )

        fun named(bookmark: Bookmark): BookmarkEntity = fromBookmark(
            bookmark = bookmark,
            id = UUID.randomUUID().toString(),
            isLastRead = false
        )

        fun fromBookmark(
            bookmark: Bookmark,
            id: String = UUID.randomUUID().toString(),
            isLastRead: Boolean
        ): BookmarkEntity = BookmarkEntity(
            id = id,
            bookId = bookmark.bookId,
            chapterIndex = bookmark.chapterIndex,
            charOffset = bookmark.charOffset,
            pageIndexHint = bookmark.pageIndexHint,
            nearestAnchorId = bookmark.nearestAnchorId,
            nearestAnchorText = bookmark.nearestAnchorText,
            savedAt = bookmark.savedAt,
            label = bookmark.label,
            isLastRead = isLastRead
        )
    }
}
