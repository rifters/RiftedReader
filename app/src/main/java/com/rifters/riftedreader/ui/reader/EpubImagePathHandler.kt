package com.rifters.riftedreader.ui.reader

import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import com.rifters.riftedreader.util.AppLogger
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException

/**
 * Custom PathHandler for WebViewAssetLoader that serves cached EPUB images.
 *
 * This handler intercepts requests to /epub-images/ and serves the corresponding
 * files from the image cache directory on disk.
 * 
 * ISSUE 3 FIX: Added retry logic to handle race conditions where EpubParser
 * may still be caching images when WebView requests them.
 *
 * @param imageCacheRoot The root directory of the image cache
 */
class EpubImagePathHandler(
    private val imageCacheRoot: File
) : WebViewAssetLoader.PathHandler {

    companion object {
        private const val TAG = "EpubImagePathHandler"
        
        // ISSUE 3 FIX: Retry configuration for handling cache races
        private const val MAX_RETRIES = 4
        private const val RETRY_DELAY_MS = 50L
    }

    override fun handle(path: String): WebResourceResponse? {
        try {
            // Decode URL-encoded path segments
            val decodedPath = android.net.Uri.decode(path)

            // Construct the full file path
            val imageFile = File(imageCacheRoot, decodedPath)

            AppLogger.d(TAG, "Handling request for path: $path -> ${imageFile.absolutePath}")

            // Security check: ensure the resolved path is within the cache root
            // canonicalPath can throw IOException if path is invalid
            val canonicalImagePath: String
            val canonicalCacheRoot: String
            try {
                canonicalImagePath = imageFile.canonicalPath
                canonicalCacheRoot = imageCacheRoot.canonicalPath
            } catch (e: java.io.IOException) {
                AppLogger.w(TAG, "Security: Invalid path, cannot resolve canonical path: $path")
                return null
            }
            
            // Ensure path is within cache root with proper directory boundary check
            // Without File.separator, "/cache-attacker/file" would match "/cache" prefix
            val cacheRootWithSeparator = if (canonicalCacheRoot.endsWith(File.separator)) {
                canonicalCacheRoot
            } else {
                canonicalCacheRoot + File.separator
            }
            if (!canonicalImagePath.startsWith(cacheRootWithSeparator) && canonicalImagePath != canonicalCacheRoot) {
                AppLogger.w(TAG, "Security: Attempted path traversal attack: $path")
                return null
            }

            // ISSUE 3 FIX: Add retry logic if file not found
            // This handles the race condition where EpubParser is still caching images
            // NOTE: Thread.sleep() is safe here because WebViewAssetLoader.PathHandler.handle()
            // is called on a background thread, not the main/UI thread
            var retryCount = 0
            while ((!imageFile.exists() || !imageFile.isFile) && retryCount < MAX_RETRIES) {
                retryCount++
                AppLogger.d(TAG, "Image file not found, retrying ($retryCount/$MAX_RETRIES): ${imageFile.absolutePath}")
                try {
                    Thread.sleep(RETRY_DELAY_MS)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            
            // Check if file exists after retries
            if (!imageFile.exists() || !imageFile.isFile) {
                if (retryCount > 0) {
                    AppLogger.w(TAG, "Image file not found after $retryCount retries: ${imageFile.absolutePath}")
                } else {
                    AppLogger.w(TAG, "Image file not found: ${imageFile.absolutePath}")
                }
                return null
            }
            
            // Log successful retry if applicable
            if (retryCount > 0) {
                AppLogger.d(TAG, "Image file found after $retryCount retries: ${imageFile.absolutePath}")
            }

            // Determine MIME type based on file extension
            val mimeType = getMimeType(imageFile.name)

            // Return the file as a WebResourceResponse
            val inputStream = FileInputStream(imageFile)
            val response = WebResourceResponse(mimeType, null, inputStream)

            // Add caching headers for better performance
            // No CORS header needed since images are loaded within the same virtual domain
            response.responseHeaders = mapOf(
                "Cache-Control" to "max-age=3600"
            )

            AppLogger.d(TAG, "Serving image: ${imageFile.name} (${imageFile.length()} bytes, $mimeType)")
            return response

        } catch (e: FileNotFoundException) {
            AppLogger.w(TAG, "File not found for path: $path")
            return null
        } catch (e: SecurityException) {
            AppLogger.e(TAG, "Security exception accessing path: $path", e)
            return null
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error handling path: $path", e)
            return null
        }
    }

    private fun getMimeType(fileName: String): String {
        return when (fileName.substringAfterLast('.', "").lowercase()) {
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
}
