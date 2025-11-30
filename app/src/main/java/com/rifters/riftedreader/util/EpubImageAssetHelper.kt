package com.rifters.riftedreader.util

import android.net.Uri
import java.io.File
import java.util.Collections

/**
 * Helper utility for managing EPUB image paths with WebViewAssetLoader.
 * 
 * WebView's allowFileAccess=false blocks file:// URLs for security reasons.
 * This helper converts file system paths to virtual HTTPS URLs that can be
 * served by WebViewAssetLoader via shouldInterceptRequest.
 * 
 * Virtual domain: https://appassets.androidplatform.net
 * Image path: /epub-images/{encoded-relative-path}
 * 
 * Example:
 * - Cache path: /storage/emulated/0/Download/.image_cache/BookTitle/chapter_1/OEBPS_image.jpg
 * - Asset URL:  https://appassets.androidplatform.net/epub-images/BookTitle/chapter_1/OEBPS_image.jpg
 * 
 * Structured logging prefixes:
 * - [ASSET_URL]: URL conversion operations
 * - [ASSET_URL_PARSE]: URL parsing operations
 * - [ASSET_URL_INVALID]: Invalid URL or path traversal attempts
 */
object EpubImageAssetHelper {
    
    private const val TAG = "EpubImageAssetHelper"
    
    /** Virtual host domain for WebViewAssetLoader */
    const val ASSET_HOST = "appassets.androidplatform.net"
    
    /** Path prefix for EPUB images */
    const val EPUB_IMAGES_PATH = "/epub-images/"
    
    /** Full base URL for EPUB images */
    const val EPUB_IMAGES_BASE_URL = "https://$ASSET_HOST$EPUB_IMAGES_PATH"
    
    // ================================================================================
    // Asset URL Parsing
    // ================================================================================
    
    /**
     * Structured parts extracted from an asset URL.
     * 
     * @param bookName The book folder name (first path segment)
     * @param chapterIndex The chapter index extracted from chapter_N folder (null if not present)
     * @param fileName The image file name (last path segment)
     */
    data class AssetParts(
        val bookName: String,
        val chapterIndex: Int?,
        val fileName: String
    )
    
    /**
     * Parse an asset URL to extract its structured components.
     * 
     * Expected URL format: https://appassets.androidplatform.net/epub-images/BookName/chapter_N/filename.ext
     * 
     * @param url The asset URL to parse
     * @return AssetParts if parsing succeeds, null if URL is invalid or doesn't match expected format
     */
    fun parseAssetUrl(url: String): AssetParts? {
        try {
            if (!url.startsWith(EPUB_IMAGES_BASE_URL)) {
                AppLogger.d(TAG, "[ASSET_URL_PARSE] URL does not match base URL: $url")
                return null
            }
            
            // Extract relative path after base URL
            val relativePath = url.removePrefix(EPUB_IMAGES_BASE_URL)
            if (relativePath.isEmpty()) {
                AppLogger.d(TAG, "[ASSET_URL_PARSE] Empty relative path: $url")
                return null
            }
            
            // Decode the path - Uri.decode may return null in test environments
            val decodedPath = Uri.decode(relativePath) ?: relativePath
            
            // Split into segments
            val segments = decodedPath.split("/").filter { it.isNotEmpty() }
            
            // Need at least 2 segments: bookName and fileName
            // Typically 3 segments: bookName, chapter_N, fileName
            if (segments.size < 2) {
                AppLogger.d(TAG, "[ASSET_URL_PARSE] Insufficient path segments (${segments.size}): $url")
                return null
            }
            
            val bookName = segments[0]
            val fileName = segments.last()
            
            // Extract chapter index if present (format: chapter_N)
            var chapterIndex: Int? = null
            if (segments.size >= 3) {
                val chapterSegment = segments[1]
                if (chapterSegment.startsWith("chapter_")) {
                    chapterIndex = chapterSegment.removePrefix("chapter_").toIntOrNull()
                }
            }
            
            val parts = AssetParts(bookName, chapterIndex, fileName)
            AppLogger.d(TAG, "[ASSET_URL_PARSE] Parsed URL: $url -> book=$bookName, chapter=$chapterIndex, file=$fileName")
            return parts
        } catch (e: Exception) {
            AppLogger.e(TAG, "[ASSET_URL_PARSE] Error parsing URL: $url", e)
            return null
        }
    }
    
    // ================================================================================
    // Diagnostics Mapping Registry
    // ================================================================================
    
    /**
     * Entry tracking the full mapping chain from original source to final asset URL.
     * Used for diagnostics and debugging image loading issues.
     * 
     * @param originalSrc The original src attribute from the EPUB HTML
     * @param resolvedPath The resolved path within the EPUB structure
     * @param cacheFile The absolute path to the cached image file
     * @param assetUrl The final asset URL served to WebView
     * @param chapterIndex The chapter index where this image appears
     */
    data class MappingEntry(
        val originalSrc: String,
        val resolvedPath: String,
        val cacheFile: String,
        val assetUrl: String,
        val chapterIndex: Int
    )
    
    // Thread-safe registry of image mappings for diagnostics
    private val registry: MutableList<MappingEntry> = Collections.synchronizedList(mutableListOf())
    
    /**
     * Record an image mapping for diagnostics traceability.
     * 
     * @param entry The mapping entry to record
     */
    fun recordMapping(entry: MappingEntry) {
        registry.add(entry)
        AppLogger.d(TAG, "[ASSET_URL] Recorded mapping: originalSrc=${entry.originalSrc}, " +
            "resolvedPath=${entry.resolvedPath}, cacheFile=${entry.cacheFile}, " +
            "assetUrl=${entry.assetUrl}, chapter=${entry.chapterIndex}")
    }
    
    /**
     * Get a snapshot of all recorded mappings for diagnostics.
     * 
     * @return Immutable copy of all recorded mappings
     */
    fun dumpMappings(): List<MappingEntry> = registry.toList()
    
    /**
     * Clear all recorded mappings. Useful for testing or when loading a new book.
     */
    fun clearMappings() {
        registry.clear()
        AppLogger.d(TAG, "[ASSET_URL] Cleared mapping registry")
    }
    
    // ================================================================================
    // MIME Type Resolution
    // ================================================================================
    
    /**
     * Guess the MIME type based on file extension.
     * 
     * @param fileName The file name or path to analyze
     * @return The MIME type string, defaults to "application/octet-stream" for unknown types
     */
    fun guessMime(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            "bmp" -> "image/bmp"
            "ico" -> "image/x-icon"
            else -> "application/octet-stream"
        }
    }
    
    // ================================================================================
    // Path Containment & Security
    // ================================================================================
    
    /**
     * Check if a path is safe and contained within the cache root.
     * Rejects paths with traversal attempts (..) or empty segments.
     * 
     * @param path The relative path to validate
     * @return true if the path is safe, false if it contains traversal or is invalid
     */
    fun isPathSafe(path: String): Boolean {
        // Check for empty path
        if (path.isEmpty()) {
            AppLogger.w(TAG, "[ASSET_URL_INVALID] Empty path rejected")
            return false
        }
        
        // Split and check each segment
        val segments = path.split("/")
        for (segment in segments) {
            // Reject empty segments (double slashes)
            if (segment.isEmpty()) {
                continue // Allow leading/trailing/multiple slashes
            }
            
            // Reject traversal attempts
            if (segment == ".." || segment == ".") {
                AppLogger.w(TAG, "[ASSET_URL_INVALID] Path traversal detected: segment='$segment' in path='$path'")
                return false
            }
        }
        
        return true
    }
    
    /**
     * Verify that a resolved file path is contained within the expected root directory.
     * Uses canonical path comparison for security.
     * 
     * @param resolvedFile The resolved file to check
     * @param rootDirectory The root directory that should contain the file
     * @return true if the file is safely contained within root, false otherwise
     */
    fun isContainedWithin(resolvedFile: File, rootDirectory: File): Boolean {
        return try {
            val canonicalFile = resolvedFile.canonicalPath
            val canonicalRoot = rootDirectory.canonicalPath
            
            // Ensure proper directory boundary check
            val rootWithSeparator = if (canonicalRoot.endsWith(File.separator)) {
                canonicalRoot
            } else {
                canonicalRoot + File.separator
            }
            
            val isContained = canonicalFile.startsWith(rootWithSeparator) || canonicalFile == canonicalRoot
            if (!isContained) {
                AppLogger.w(TAG, "[ASSET_URL_INVALID] Path escapes root: file=$canonicalFile, root=$canonicalRoot")
            }
            isContained
        } catch (e: Exception) {
            AppLogger.e(TAG, "[ASSET_URL_INVALID] Cannot resolve canonical path: file=${resolvedFile.path}", e)
            false
        }
    }
    
    // ================================================================================
    // URL Conversion
    // ================================================================================
    
    /**
     * Convert a cached image file path to a WebViewAssetLoader URL.
     * 
     * @param cachedFilePath The absolute path to the cached image file
     * @param imageCacheRoot The root directory of the image cache (e.g., /storage/.../Download/.image_cache)
     * @return The virtual HTTPS URL for WebViewAssetLoader, or the original path if conversion fails
     */
    fun toAssetUrl(cachedFilePath: String, imageCacheRoot: File): String {
        try {
            val cacheRootPath = imageCacheRoot.absolutePath
            
            // Extract relative path from cache root
            if (!cachedFilePath.startsWith(cacheRootPath)) {
                AppLogger.w(TAG, "[ASSET_URL] File path not under cache root: $cachedFilePath")
                return cachedFilePath
            }
            
            // Get relative path, removing leading slash
            var relativePath = cachedFilePath.removePrefix(cacheRootPath)
            if (relativePath.startsWith("/")) {
                relativePath = relativePath.substring(1)
            }
            
            // URL-encode each path segment to handle special characters
            // Uri.encode handles path encoding correctly and avoids URLEncoder's space-to-+ conversion
            val encodedPath = relativePath.split("/").joinToString("/") { segment ->
                // Uri.encode may return null in test environments where Android framework is mocked
                Uri.encode(segment) ?: segment
            }
            
            val assetUrl = "$EPUB_IMAGES_BASE_URL$encodedPath"
            AppLogger.d(TAG, "[ASSET_URL] Converted path to asset URL: $cachedFilePath -> $assetUrl")
            return assetUrl
        } catch (e: Exception) {
            AppLogger.e(TAG, "[ASSET_URL] Error converting path to asset URL: $cachedFilePath", e)
            return cachedFilePath
        }
    }
    
    /**
     * Convert an asset URL back to a file path.
     * 
     * @param assetUrl The virtual HTTPS URL from WebViewAssetLoader
     * @param imageCacheRoot The root directory of the image cache
     * @return The absolute path to the cached image file, or null if invalid
     */
    fun toFilePath(assetUrl: String, imageCacheRoot: File): String? {
        try {
            if (!assetUrl.startsWith(EPUB_IMAGES_BASE_URL)) {
                return null
            }
            
            // Extract and decode the relative path
            val encodedPath = assetUrl.removePrefix(EPUB_IMAGES_BASE_URL)
            val decodedPath = Uri.decode(encodedPath)
            
            // Security: validate path before constructing file
            if (!isPathSafe(decodedPath)) {
                AppLogger.w(TAG, "[ASSET_URL_INVALID] Unsafe path in URL: $assetUrl")
                return null
            }
            
            // Construct absolute path
            val absolutePath = File(imageCacheRoot, decodedPath).absolutePath
            AppLogger.d(TAG, "[ASSET_URL] Converted asset URL to path: $assetUrl -> $absolutePath")
            return absolutePath
        } catch (e: Exception) {
            AppLogger.e(TAG, "[ASSET_URL] Error converting asset URL to path: $assetUrl", e)
            return null
        }
    }
    
    /**
     * Check if a URL is an EPUB image asset URL.
     * 
     * @param url The URL to check
     * @return true if this URL should be handled by the EPUB image path handler
     */
    fun isEpubImageAssetUrl(url: String): Boolean {
        return url.startsWith(EPUB_IMAGES_BASE_URL)
    }
    
    /**
     * Get the image cache root directory for a book file.
     * This should match the cache directory structure used by EpubParser.
     * 
     * @param bookFile The EPUB book file
     * @return The root cache directory for images from this book's parent directory
     */
    fun getImageCacheRoot(bookFile: File): File {
        return File(bookFile.parentFile, ".image_cache")
    }
}
