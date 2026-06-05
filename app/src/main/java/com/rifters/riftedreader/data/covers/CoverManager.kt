package com.rifters.riftedreader.data.covers

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream

class CoverManager internal constructor(
    cacheDir: File
) {
    private val coversDir = File(cacheDir, COVERS_DIRECTORY_NAME)

    fun getCoverPath(bookId: String): String? {
        return runCatching {
            coverFile(bookId).takeIf { it.exists() }?.absolutePath
        }.getOrNull()
    }

    fun saveCover(bookId: String, bitmap: Bitmap): String {
        return runCatching {
            if (!coversDir.exists()) {
                coversDir.mkdirs()
            }
            val coverFile = coverFile(bookId)
            FileOutputStream(coverFile).use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
            }
            coverFile.absolutePath
        }.getOrNull().orEmpty()
    }

    fun deleteCover(bookId: String) {
        runCatching {
            val coverFile = coverFile(bookId)
            if (coverFile.exists()) {
                coverFile.delete()
            }
        }.getOrNull()
    }

    fun hasCover(bookId: String): Boolean {
        return runCatching {
            coverFile(bookId).exists()
        }.getOrNull() == true
    }

    private fun coverFile(bookId: String): File = File(coversDir, "$bookId.jpg")

    companion object {
        private const val COVERS_DIRECTORY_NAME = "covers"
        private const val JPEG_QUALITY = 90

        @Volatile
        private var instance: CoverManager? = null

        fun getInstance(context: Context): CoverManager {
            val current = instance
            if (current != null) return current
            return synchronized(this) {
                instance ?: CoverManager(context.applicationContext.cacheDir).also { instance = it }
            }
        }
    }
}
