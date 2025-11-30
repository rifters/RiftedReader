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
import com.rifters.riftedreader.data.database.entities.Bookmark
import com.rifters.riftedreader.data.database.entities.BookCollectionCrossRef
import com.rifters.riftedreader.data.database.entities.CollectionEntity

/**
 * Main database for RiftedReader
 */
@Database(
    entities = [BookMeta::class, CollectionEntity::class, BookCollectionCrossRef::class, Bookmark::class],
    version = 6,
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
        
        fun getDatabase(context: Context): BookDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    BookDatabase::class.java,
                    "rifted_reader_database"
                )
                    .addMigrations(MIGRATION_5_6)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
