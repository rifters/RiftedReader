package com.rifters.riftedreader.util

import android.content.Context
import android.annotation.SuppressLint
import android.os.Build
import android.os.Environment
import com.rifters.riftedreader.data.database.entities.BookMeta
import com.rifters.riftedreader.data.repository.BookRepository
import com.rifters.riftedreader.domain.parser.ParserFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.LinkedHashSet

/**
 * Scans file system for supported book files
 */
class FileScanner(
    private val context: Context,
    private val repository: BookRepository
) {
    
    /**
     * Scan default directories for books
     */
    suspend fun scanForBooks(
        onProgress: (scanned: Int, found: Int) -> Unit = { _, _ -> }
    ): List<BookMeta> = withContext(Dispatchers.IO) {
        AppLogger.task("FileScanner", "Starting book scan", "util/FileScanner/scan")
        val startTime = System.currentTimeMillis()
        
        ParserFactory.enablePreviewParsers()
        val foundBooks = mutableListOf<BookMeta>()
        var scannedCount = 0
        
        val directories = getDefaultScanDirectories()
        AppLogger.d("FileScanner", "Scanning ${directories.size} directories")
        
        for (directory in directories) {
            if (directory.exists() && directory.isDirectory) {
                AppLogger.d("FileScanner", "Scanning directory: ${directory.absolutePath}")
                scanDirectory(directory, foundBooks) { count ->
                    scannedCount += count
                    onProgress(scannedCount, foundBooks.size)
                }
            }
        }
        
        // Save to database
        if (foundBooks.isNotEmpty()) {
            AppLogger.d("FileScanner", "Saving ${foundBooks.size} books to database")
            repository.insertBooks(foundBooks)
        }
        
        val duration = System.currentTimeMillis() - startTime
        AppLogger.performance("FileScanner", "Book scan completed: found ${foundBooks.size} books, scanned $scannedCount files", duration, "util/FileScanner/scan")
        
        foundBooks
    }
    
    /**
     * Scan a specific directory
     */
    private suspend fun scanDirectory(
        directory: File,
        foundBooks: MutableList<BookMeta>,
        onProgress: (count: Int) -> Unit
    ) {
        val files = directory.listFiles() ?: return
        
        for (file in files) {
            try {
                if (file.isDirectory) {
                    // Recursively scan subdirectories
                    scanDirectory(file, foundBooks, onProgress)
                } else if (file.isFile) {
                    onProgress(1)
                    
                    // Check if file is supported
                    if (ParserFactory.isSupported(file)) {
                        // Check if already in database
                        val existing = repository.getBookByPath(file.absolutePath)
                        if (existing == null) {
                            // Extract metadata
                            val parser = ParserFactory.getParser(file)
                            if (parser != null) {
                                AppLogger.d("FileScanner", "Found new book: ${file.name}")
                                val metadata = parser.extractMetadata(file)
                                foundBooks.add(metadata)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Skip files that cause errors
                AppLogger.w("FileScanner", "Error scanning file: ${file.name}", e)
                e.printStackTrace()
            }
        }
    }
    
    /**
     * Get default directories to scan
     */
    @SuppressLint("InlinedApi")
    private fun getDefaultScanDirectories(): List<File> {
        val directories = LinkedHashSet<File>()

        // Common public directories
        directories += Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        directories += Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            directories += Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_AUDIOBOOKS)
        }

        // Legacy folders that many readers use
        val externalStorage = Environment.getExternalStorageDirectory()
        directories += File(externalStorage, "Books")
        directories += File(externalStorage, "ebooks")
        directories += File(externalStorage, "RiftedReader")

        // App-specific storage
        context.getExternalFilesDir(null)?.let { directories += it }

        return directories.filter { it.exists() && it.isDirectory }
    }
}
