package com.rifters.riftedreader.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.rifters.riftedreader.data.database.dao.BookMetaDao
import com.rifters.riftedreader.data.database.dao.BookmarkDao
import com.rifters.riftedreader.data.database.dao.CollectionDao
import com.rifters.riftedreader.data.database.entities.BookMeta
import com.rifters.riftedreader.data.database.entities.BookmarkEntity
import com.rifters.riftedreader.data.database.entities.BookCollectionCrossRef
import com.rifters.riftedreader.data.database.entities.CollectionEntity

/**
 * Main database for RiftedReader
 */
@Database(
    entities = [BookMeta::class, CollectionEntity::class, BookCollectionCrossRef::class, BookmarkEntity::class],
    version = 7,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class BookDatabase : RoomDatabase() {
    
    abstract fun bookMetaDao(): BookMetaDao
    abstract fun collectionDao(): CollectionDao
    abstract fun bookmarkDao(): BookmarkDao
    
    companion object {
        @Volatile
        private var INSTANCE: BookDatabase? = null
        
        /**
         * Migration from version 5 to 6: Add chapter visibility settings columns
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add chapter visibility settings columns to books table
                // Using nullable columns with NULL default to indicate "use global settings"
                db.execSQL("ALTER TABLE books ADD COLUMN chapterVisibilityIncludeCover INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE books ADD COLUMN chapterVisibilityIncludeFrontMatter INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE books ADD COLUMN chapterVisibilityIncludeNonLinear INTEGER DEFAULT NULL")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bookmarks RENAME TO bookmarks_old")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS bookmarks (
                        id TEXT NOT NULL PRIMARY KEY,
                        bookId TEXT NOT NULL,
                        chapterIndex INTEGER NOT NULL,
                        charOffset INTEGER NOT NULL,
                        pageIndexHint INTEGER NOT NULL,
                        nearestAnchorId TEXT NOT NULL,
                        nearestAnchorText TEXT NOT NULL,
                        savedAt INTEGER NOT NULL,
                        label TEXT,
                        isLastRead INTEGER NOT NULL,
                        FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_bookmarks_bookId ON bookmarks(bookId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_bookmarks_bookId_isLastRead ON bookmarks(bookId, isLastRead)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_bookmarks_savedAt ON bookmarks(savedAt)")
                // Legacy rows did not store heading anchors; preserve the best available
                // human-readable label while leaving nearestAnchorId empty.
                db.execSQL(
                    """
                    INSERT INTO bookmarks (
                        id,
                        bookId,
                        chapterIndex,
                        charOffset,
                        pageIndexHint,
                        nearestAnchorId,
                        nearestAnchorText,
                        savedAt,
                        label,
                        isLastRead
                    )
                    SELECT
                        id,
                        bookId,
                        chapterIndex,
                        characterOffset,
                        inChapterPage,
                        '',
                        CASE
                            WHEN chapterTitle IS NOT NULL AND chapterTitle != '' THEN chapterTitle
                            ELSE previewText
                        END,
                        createdAt,
                        NULL,
                        0
                    FROM bookmarks_old
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE bookmarks_old")
            }
        }
        
        fun getDatabase(context: Context): BookDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    BookDatabase::class.java,
                    "rifted_reader_database"
                )
                    .addMigrations(MIGRATION_5_6, MIGRATION_6_7)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
