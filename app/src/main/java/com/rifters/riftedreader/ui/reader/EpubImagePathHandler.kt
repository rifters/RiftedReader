package com.rifters.riftedreader.ui.reader

import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import com.rifters.riftedreader.util.AppLogger
import com.rifters.riftedreader.util.EpubImageAssetHelper
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException

/**
 * Custom PathHandler for WebViewAssetLoader that serves cached EPUB images.
 *
 * This handler intercepts requests to /epub-images/ and serves the corresponding
 * files from the image cache directory on disk.
 * 
 * Features:
 * - Bounded exponential backoff for race conditions (50ms, 100ms, 200ms, 400ms, 800ms)
 * - 1x1 transparent PNG fallback for missing images
 * - Security validation against path traversal attacks
 * - Structured logging with prefixes: [ASSET_REQUEST], [ASSET_BACKOFF], [ASSET_RESPONSE], [ASSET_FALLBACK]
 *
 * @param imageCacheRoot The root directory of the image cache
 */
class EpubImagePathHandler(
    private val imageCacheRoot: File
) : WebViewAssetLoader.PathHandler {

    companion object {
        private const val TAG = "EpubImagePathHandler"
        
        // Bounded exponential backoff delays in milliseconds
        // Total wait time: 50 + 100 + 200 + 400 + 800 = 1550ms (~1.55s)
        private val BACKOFF_DELAYS_MS = longArrayOf(50L, 100L, 200L, 400L, 800L)
        
        // Transparent PNG fallback - using centralized constant from EpubImageAssetHelper
        // (Previously duplicated locally - now using centralized constant for consistency)
    }

    override fun handle(path: String): WebResourceResponse? {
        // Parse asset URL to extract components for logging
        val fullUrl = "${EpubImageAssetHelper.EPUB_IMAGES_BASE_URL}$path"
        val assetParts = EpubImageAssetHelper.parseAssetUrl(fullUrl)
        val bookName = assetParts?.bookName ?: "unknown"
        val chapterStr = assetParts?.chapterIndex?.toString() ?: "unknown"
        val fileName = assetParts?.fileName ?: path.substringAfterLast('/')
        
        try {
            // Decode URL-encoded path segments
            val decodedPath = android.net.Uri.decode(path)
            
            // Security check: validate path is safe before proceeding
            if (!EpubImageAssetHelper.isPathSafe(decodedPath)) {
                AppLogger.w(TAG, "[ASSET_URL_INVALID] Path traversal rejected: url=$fullUrl")
                return null
            }

            // Construct the full file path
            val imageFile = File(imageCacheRoot, decodedPath)

            // Security check: ensure the resolved path is within the cache root
            if (!EpubImageAssetHelper.isContainedWithin(imageFile, imageCacheRoot)) {
                AppLogger.w(TAG, "[ASSET_URL_INVALID] Path escapes cache root: url=$fullUrl, resolved=${imageFile.absolutePath}")
                return null
            }

            // Log initial request
            val fileExists = imageFile.exists() && imageFile.isFile
            AppLogger.d(TAG, "[ASSET_REQUEST] url=$fullUrl book=$bookName chapter=$chapterStr file=$fileName exists=$fileExists attempt=0")

            // Bounded exponential backoff if file not found
            // This handles race conditions where EpubParser is still caching images
            // NOTE: Thread.sleep() is safe here because WebViewAssetLoader.PathHandler.handle()
            // is called on a background thread, not the main/UI thread
            var attemptIndex = 0
            while ((!imageFile.exists() || !imageFile.isFile) && attemptIndex < BACKOFF_DELAYS_MS.size) {
                val waitMs = BACKOFF_DELAYS_MS[attemptIndex]
                AppLogger.d(TAG, "[ASSET_BACKOFF] url=$fullUrl attempt=${attemptIndex + 1} waitMs=$waitMs")
                
                try {
                    Thread.sleep(waitMs)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    AppLogger.w(TAG, "[ASSET_BACKOFF] Interrupted during backoff for url=$fullUrl")
                    break
                }
                
                attemptIndex++
                
                // Log retry attempt
                val existsNow = imageFile.exists() && imageFile.isFile
                AppLogger.d(TAG, "[ASSET_REQUEST] url=$fullUrl book=$bookName chapter=$chapterStr file=$fileName exists=$existsNow attempt=$attemptIndex")
            }
            
            // Check if file exists after backoff
            if (!imageFile.exists() || !imageFile.isFile) {
                AppLogger.w(TAG, "[ASSET_FALLBACK] url=$fullUrl reason=MISSING_AFTER_BACKOFF attempts=$attemptIndex")
                return createFallbackResponse()
            }
            
            // Log if backoff was needed
            if (attemptIndex > 0) {
                AppLogger.d(TAG, "[ASSET_REQUEST] File found after $attemptIndex backoff attempts: ${imageFile.absolutePath}")
            }

            // Determine MIME type using helper
            val mimeType = EpubImageAssetHelper.guessMime(imageFile.name)

            // Return the file as a WebResourceResponse
            val inputStream = FileInputStream(imageFile)
            val fileSize = imageFile.length()
            val response = WebResourceResponse(mimeType, null, inputStream)

            // Add caching headers for better performance
            // No CORS header needed since images are loaded within the same virtual domain
            response.responseHeaders = mapOf(
                "Cache-Control" to "max-age=3600"
            )

            AppLogger.d(TAG, "[ASSET_RESPONSE] url=$fullUrl bytes=$fileSize mime=$mimeType status=OK")
            return response

        } catch (e: FileNotFoundException) {
            AppLogger.w(TAG, "[ASSET_FALLBACK] url=$fullUrl reason=FILE_NOT_FOUND")
            return createFallbackResponse()
        } catch (e: SecurityException) {
            AppLogger.e(TAG, "[ASSET_URL_INVALID] Security exception for url=$fullUrl", e)
            return null
        } catch (e: Exception) {
            AppLogger.e(TAG, "[ASSET_FALLBACK] url=$fullUrl reason=EXCEPTION error=${e.message}", e)
            return createFallbackResponse()
        }
    }
    
    /**
     * Create a fallback response with a 1x1 transparent PNG.
     * 
     * Design decision: Returns HTTP 200 with valid image data instead of 404/204 because:
     * 1. WebView displays broken image icons for non-200 responses, degrading UX
     * 2. The transparent PNG is invisible, so layout remains stable (especially for intrinsic sizing)
     * 3. The [ASSET_FALLBACK] logs clearly identify which images failed for debugging
     * 4. The X-RiftedReader-Fallback header allows programmatic detection if needed
     * 
     * Alternative approaches (404, error image) were rejected because they cause visible
     * layout shifts or broken image indicators that disrupt the reading experience.
     */
    private fun createFallbackResponse(): WebResourceResponse {
        val inputStream = ByteArrayInputStream(EpubImageAssetHelper.TRANSPARENT_PNG_BYTES)
        val response = WebResourceResponse("image/png", null, inputStream)
        
        // Return valid 200 response with transparent image
        // Custom header indicates fallback was used (for debugging/monitoring)
        response.responseHeaders = mapOf(
            "Cache-Control" to "no-cache",
            "X-RiftedReader-Fallback" to "true"
        )
        
        return response
    }
}
