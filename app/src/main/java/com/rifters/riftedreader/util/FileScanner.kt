package com.rifters.riftedreader.util

import android.content.Context
import android.os.Environment
import com.rifters.riftedreader.data.database.entities.BookMeta
import com.rifters.riftedreader.data.repository.BookRepository
import com.rifters.riftedreader.domain.parser.ParserFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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
        val foundBooks = mutableListOf<BookMeta>()
        var scannedCount = 0
        
        val directories = getDefaultScanDirectories()
        
        for (directory in directories) {
            if (directory.exists() && directory.isDirectory) {
                scanDirectory(directory, foundBooks) { count ->
                    scannedCount += count
                    onProgress(scannedCount, foundBooks.size)
                }
            }
        }
        
        // Save to database
        if (foundBooks.isNotEmpty()) {
            repository.insertBooks(foundBooks)
        }
        
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
                                val metadata = parser.extractMetadata(file)
                                foundBooks.add(metadata)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Skip files that cause errors
                e.printStackTrace()
            }
        }
    }
    
    /**
     * Get default directories to scan
     */
    private fun getDefaultScanDirectories(): List<File> {
        val directories = mutableListOf<File>()
        
        // External storage
        val externalStorage = Environment.getExternalStorageDirectory()
        directories.add(File(externalStorage, "Books"))
        directories.add(File(externalStorage, "Download"))
        directories.add(File(externalStorage, "Documents"))
        
        // App-specific directory
        context.getExternalFilesDir(null)?.let { directories.add(it) }
        
        return directories.filter { it.exists() && it.isDirectory }
    }
}
