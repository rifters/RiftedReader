package com.rifters.riftedreader.util

import android.net.Uri
import java.io.File

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
 */
object EpubImageAssetHelper {
    
    /** Virtual host domain for WebViewAssetLoader */
    const val ASSET_HOST = "appassets.androidplatform.net"
    
    /** Path prefix for EPUB images */
    const val EPUB_IMAGES_PATH = "/epub-images/"
    
    /** Full base URL for EPUB images */
    const val EPUB_IMAGES_BASE_URL = "https://$ASSET_HOST$EPUB_IMAGES_PATH"
    
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
                AppLogger.w("EpubImageAssetHelper", "File path not under cache root: $cachedFilePath")
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
            AppLogger.d("EpubImageAssetHelper", "Converted path to asset URL: $cachedFilePath -> $assetUrl")
            return assetUrl
        } catch (e: Exception) {
            AppLogger.e("EpubImageAssetHelper", "Error converting path to asset URL: $cachedFilePath", e)
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
            
            // Construct absolute path
            val absolutePath = File(imageCacheRoot, decodedPath).absolutePath
            AppLogger.d("EpubImageAssetHelper", "Converted asset URL to path: $assetUrl -> $absolutePath")
            return absolutePath
        } catch (e: Exception) {
            AppLogger.e("EpubImageAssetHelper", "Error converting asset URL to path: $assetUrl", e)
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
