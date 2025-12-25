package com.rifters.riftedreader.util

import android.content.Context
import com.rifters.riftedreader.BuildConfig
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Utility for logging HTML content to disk for debugging pagination system.
 * 
 * Helps debug and understand how the sliding window pagination system generates HTML
 * by automatically dumping HTML blobs to a logs/ directory.
 * 
 * Features:
 * - Logs individual chapter HTML
 * - Logs combined window HTML for sliding window debugging
 * - Automatic logs directory creation
 * - Timestamped filenames for tracking
 * - Only active in DEBUG builds
 */
object HtmlDebugLogger {
    private const val TAG = "HtmlDebugLogger"
    private const val LOGS_DIR_NAME = "logs"
    private var logsDir: File? = null
    
    /**
     * Initialize the logger with the application context.
     * Creates the logs directory if it doesn't exist.
     * 
     * @param context Application context
     */
    fun init(context: Context) {
        if (!BuildConfig.DEBUG) return
        
        try {
            // Use getExternalFilesDir so logs are accessible via file managers
            val dir = File(context.getExternalFilesDir(null), LOGS_DIR_NAME)
            if (!dir.exists()) {
                val created = dir.mkdirs()
                AppLogger.d(TAG, "Logs directory created: $created at ${dir.absolutePath}")
            }
            logsDir = dir
            AppLogger.d(TAG, "HtmlDebugLogger initialized. Logs will be saved to: ${dir.absolutePath}")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to initialize HtmlDebugLogger", e)
        }
    }
    
    /**
     * Log individual chapter HTML content.
     * 
     * @param bookId Unique book identifier (from path or database ID)
     * @param chapterIndex Index of the chapter
     * @param html The HTML content
     * @param metadata Optional metadata to include in the log header (should include windowIndex if applicable)
     */
    fun logChapterHtml(
        bookId: String,
        chapterIndex: Int,
        html: String,
        metadata: Map<String, String> = emptyMap()
    ) {
        if (!BuildConfig.DEBUG) return
        
        val dir = logsDir
        if (dir == null) {
            AppLogger.w(TAG, "HtmlDebugLogger not initialized. Call init() first.")
            return
        }
        
        try {
            val timestamp = getTimestamp()
            val sanitizedBookId = sanitizeFileName(bookId)
            
            // TASK 4 FIX: Include windowIndex in filename (from metadata if available)
            val windowIndexSuffix = metadata["windowIndex"]?.let { "-window-$it" } ?: ""
            val fileName = "book-${sanitizedBookId}-chapter-${chapterIndex}${windowIndexSuffix}-${timestamp}.html"
            val file = File(dir, fileName)
            
            val header = buildString {
                appendLine("<!--")
                appendLine("  HTML Debug Log")
                appendLine("  Book ID: $bookId")
                appendLine("  Chapter Index: $chapterIndex")
                appendLine("  Timestamp: $timestamp")
                appendLine("  Date: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
                if (metadata.isNotEmpty()) {
                    appendLine("  Metadata:")
                    metadata.forEach { (key, value) ->
                        appendLine("    $key: $value")
                    }
                }
                appendLine("-->")
                appendLine()
            }
            
            FileWriter(file).use { writer ->
                writer.write(header)
                writer.write(html)
            }
            
            AppLogger.d(TAG, "Logged chapter HTML to: ${file.name} (${file.length()} bytes)")
        } catch (e: IOException) {
            AppLogger.e(TAG, "Failed to log chapter HTML for book=$bookId chapter=$chapterIndex", e)
        }
    }
    
    /**
     * Log combined HTML for a sliding window of chapters.
     * 
     * @param bookId Unique book identifier
     * @param windowIndex Index/position of the window (e.g., centered chapter index)
     * @param chapterIndices List of chapter indices included in this window
     * @param chapters Map of chapter index to HTML content
     * @param metadata Optional metadata to include in the log header
     */
    fun logWindowHtml(
        bookId: String,
        windowIndex: Int,
        chapterIndices: List<Int>,
        chapters: Map<Int, String>,
        metadata: Map<String, String> = emptyMap()
    ) {
        if (!BuildConfig.DEBUG) return
        
        val dir = logsDir
        if (dir == null) {
            AppLogger.w(TAG, "HtmlDebugLogger not initialized. Call init() first.")
            return
        }
        
        try {
            val timestamp = getTimestamp()
            val sanitizedBookId = sanitizeFileName(bookId)
            val fileName = "book-${sanitizedBookId}-window-${windowIndex}-${timestamp}.html"
            val file = File(dir, fileName)
            
            val header = buildString {
                appendLine("<!--")
                appendLine("  HTML Debug Log - Sliding Window")
                appendLine("  Book ID: $bookId")
                appendLine("  Window Index: $windowIndex")
                appendLine("  Chapters in Window: ${chapterIndices.joinToString(", ")}")
                appendLine("  Timestamp: $timestamp")
                appendLine("  Date: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
                if (metadata.isNotEmpty()) {
                    appendLine("  Metadata:")
                    metadata.forEach { (key, value) ->
                        appendLine("    $key: $value")
                    }
                }
                appendLine("-->")
                appendLine()
            }
            
            FileWriter(file).use { writer ->
                writer.write(header)
                writer.write("<!DOCTYPE html>\n<html>\n<head>\n")
                writer.write("<meta charset=\"utf-8\"/>\n")
                writer.write("<title>Window Debug: Chapters ${chapterIndices.joinToString(", ")}</title>\n")
                writer.write("</head>\n<body>\n")
                writer.write("<h1>Sliding Window Debug Log</h1>\n")
                writer.write("<p>Book: $bookId</p>\n")
                writer.write("<p>Window Index: $windowIndex</p>\n")
                writer.write("<p>Chapters: ${chapterIndices.joinToString(", ")}</p>\n")
                writer.write("<hr/>\n\n")
                
                // Write each chapter in order
                chapterIndices.forEach { chapterIndex ->
                    val chapterHtml = chapters[chapterIndex]
                    if (chapterHtml != null) {
                        writer.write("<!-- BEGIN CHAPTER $chapterIndex -->\n")
                        writer.write("<section id=\"chapter-$chapterIndex\">\n")
                        writer.write("<h2>Chapter $chapterIndex</h2>\n")
                        writer.write(chapterHtml)
                        writer.write("\n</section>\n")
                        writer.write("<!-- END CHAPTER $chapterIndex -->\n\n")
                    } else {
                        writer.write("<!-- CHAPTER $chapterIndex: No HTML content -->\n\n")
                    }
                }
                
                writer.write("</body>\n</html>\n")
            }
            
            AppLogger.d(TAG, "Logged window HTML to: ${file.name} (${file.length()} bytes)")
        } catch (e: IOException) {
            AppLogger.e(TAG, "Failed to log window HTML for book=$bookId window=$windowIndex", e)
        }
    }
    
    /**
     * Log the final wrapped HTML that's sent to WebView.
     * This includes all styling and JavaScript.
     * 
     * @param bookId Unique book identifier
     * @param chapterIndex Chapter index
     * @param wrappedHtml The complete HTML with styling
     * @param metadata Optional metadata to include in the log header (should include windowIndex)
     */
    fun logWrappedHtml(
        bookId: String,
        chapterIndex: Int,
        wrappedHtml: String,
        metadata: Map<String, String> = emptyMap()
    ) {
        if (!BuildConfig.DEBUG) return
        
        val dir = logsDir
        if (dir == null) {
            AppLogger.w(TAG, "HtmlDebugLogger not initialized. Call init() first.")
            return
        }
        
        try {
            val timestamp = getTimestamp()
            val sanitizedBookId = sanitizeFileName(bookId)
            
            // TASK 4 FIX: Include windowIndex in filename (from metadata if available)
            val windowIndexSuffix = metadata["windowIndex"]?.let { "-window-$it" } ?: ""
            val fileName = "book-${sanitizedBookId}-chapter-${chapterIndex}${windowIndexSuffix}-wrapped-${timestamp}.html"
            val file = File(dir, fileName)
            
            val header = buildString {
                appendLine("<!--")
                appendLine("  HTML Debug Log - WebView Wrapped")
                appendLine("  Book ID: $bookId")
                appendLine("  Chapter Index: $chapterIndex")
                appendLine("  Timestamp: $timestamp")
                appendLine("  Date: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
                appendLine("  Note: This is the final HTML sent to WebView with all styling and scripts")
                if (metadata.isNotEmpty()) {
                    appendLine("  Metadata:")
                    metadata.forEach { (key, value) ->
                        appendLine("    $key: $value")
                    }
                }
                appendLine("-->")
                appendLine()
            }
            
            FileWriter(file).use { writer ->
                writer.write(header)
                writer.write(wrappedHtml)
            }
            
            AppLogger.d(TAG, "Logged wrapped HTML to: ${file.name} (${file.length()} bytes)")
        } catch (e: IOException) {
            AppLogger.e(TAG, "Failed to log wrapped HTML for book=$bookId chapter=$chapterIndex", e)
        }
    }
    
    /**
     * Clean up old log files to prevent disk space issues.
     * Keeps only the most recent N files.
     * 
     * @param maxFiles Maximum number of log files to keep (default: 50)
     */
    fun cleanupOldLogs(maxFiles: Int = 50) {
        if (!BuildConfig.DEBUG) return
        
        val dir = logsDir ?: return
        
        try {
            val files = dir.listFiles { file -> file.extension == "html" }
            if (files != null && files.size > maxFiles) {
                // Sort by last modified time, oldest first
                val sorted = files.sortedBy { it.lastModified() }
                val toDelete = sorted.take(files.size - maxFiles)
                
                toDelete.forEach { file ->
                    val deleted = file.delete()
                    if (deleted) {
                        AppLogger.d(TAG, "Deleted old log file: ${file.name}")
                    }
                }
                
                AppLogger.d(TAG, "Cleaned up ${toDelete.size} old log files")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to cleanup old logs", e)
        }
    }
    
    /**
     * Get the logs directory path for external access.
     * Returns null if logger is not initialized.
     */
    fun getLogsDirectory(): File? = logsDir
    
    /**
     * Generate a timestamp string for filenames.
     */
    private fun getTimestamp(): String {
        return SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.getDefault()).format(Date())
    }
    
    /**
     * Sanitize a string to be safe for use in filenames.
     */
    private fun sanitizeFileName(input: String): String {
        // Take the last part of path for book ID (usually the filename)
        val name = File(input).nameWithoutExtension
        // Replace unsafe characters with underscores
        return name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .take(50) // Limit length
    }
}
