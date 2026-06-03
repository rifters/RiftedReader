package com.rifters.riftedreader.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.rifters.riftedreader.data.database.entities.BookmarkEntity

@Dao
interface BookmarkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(bookmark: BookmarkEntity)

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId AND isLastRead = 1 LIMIT 1")
    suspend fun loadLastRead(bookId: String): BookmarkEntity?

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId AND isLastRead = 0 ORDER BY savedAt DESC")
    suspend fun loadNamedBookmarks(bookId: String): List<BookmarkEntity>

    @Delete
    suspend fun delete(bookmark: BookmarkEntity)

    @Query(
        """
        DELETE FROM bookmarks
        WHERE bookId = :bookId
            AND chapterIndex = :chapterIndex
            AND charOffset = :charOffset
            AND pageIndexHint = :pageIndexHint
            AND nearestAnchorId = :nearestAnchorId
            AND savedAt = :savedAt
            AND isLastRead = :isLastRead
        """
    )
    suspend fun deleteMatching(
        bookId: String,
        chapterIndex: Int,
        charOffset: Int,
        pageIndexHint: Int,
        nearestAnchorId: String,
        savedAt: Long,
        isLastRead: Boolean
    )
}
