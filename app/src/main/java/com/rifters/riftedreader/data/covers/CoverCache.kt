package com.rifters.riftedreader.data.covers

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import java.io.File

class CoverCache private constructor(
    private val coverManager: CoverManager,
    maxEntries: Int = DEFAULT_MAX_ENTRIES
) {
    private val memoryCache = object : LruCache<String, String>(maxEntries) {}

    fun getCoverPath(bookId: String): String? {
        val cachedPath = memoryCache.get(bookId)
        if (cachedPath != null && File(cachedPath).exists()) {
            return cachedPath
        }

        val resolvedPath = coverManager.getCoverPath(bookId)
        if (resolvedPath != null) {
            memoryCache.put(bookId, resolvedPath)
        } else {
            memoryCache.remove(bookId)
        }
        return resolvedPath
    }

    fun saveCover(bookId: String, bitmap: Bitmap): String {
        val savedPath = coverManager.saveCover(bookId, bitmap)
        if (savedPath.isNotBlank()) {
            memoryCache.put(bookId, savedPath)
        } else {
            memoryCache.remove(bookId)
        }
        return savedPath
    }

    fun deleteCover(bookId: String) {
        memoryCache.remove(bookId)
        coverManager.deleteCover(bookId)
    }

    fun hasCover(bookId: String): Boolean {
        val cachedPath = memoryCache.get(bookId)
        if (cachedPath != null && File(cachedPath).exists()) {
            return true
        }
        if (cachedPath != null) {
            memoryCache.remove(bookId)
        }
        return coverManager.hasCover(bookId)
    }

    fun remember(bookId: String, coverPath: String?) {
        if (coverPath.isNullOrBlank()) {
            memoryCache.remove(bookId)
        } else {
            memoryCache.put(bookId, coverPath)
        }
    }

    companion object {
        private const val DEFAULT_MAX_ENTRIES = 128

        @Volatile
        private var instance: CoverCache? = null

        fun getInstance(context: Context): CoverCache {
            val current = instance
            if (current != null) return current
            return synchronized(this) {
                instance ?: CoverCache(CoverManager.getInstance(context.applicationContext)).also { instance = it }
            }
        }
    }
}
