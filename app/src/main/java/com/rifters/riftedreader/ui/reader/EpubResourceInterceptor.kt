package com.rifters.riftedreader.ui.reader

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebViewClient
import com.rifters.riftedreader.util.AppLogger
import com.rifters.riftedreader.util.EpubImageAssetHelper
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream

/**
 * WebView resource interceptor for handling appassets:// scheme requests.
 * 
 * This interceptor provides:
 * - Cache-first loading for EPUB images
 * - Fallback to app assets
 * - MIME type mapping
 * - Security validation against path traversal
 * 
 * Features:
 * - Bounded exponential backoff for race conditions during image caching
 * - Transparent PNG fallback for missing images
 * - Structured logging with [INTERCEPT_*] prefixes
 */
class EpubResourceInterceptor(
    private val imageCacheRoot: File
) {
    
    companion object {
        private const val TAG = "EpubResourceInterceptor"
        
        // Bounded exponential backoff delays in milliseconds
        private val BACKOFF_DELAYS_MS = longArrayOf(25L, 50L, 100L, 200L)
    }
    
    /**
     * Intercept a request and provide a response if applicable.
     * 
     * @param request The WebView resource request
     * @return WebResourceResponse if the request should be intercepted, null otherwise
     */
    fun shouldIntercept(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url?.toString() ?: return null
        
        // Only intercept EPUB image asset URLs
        if (!EpubImageAssetHelper.isEpubImageAssetUrl(url)) {
            return null
        }
        
        return interceptImageRequest(url)
    }
    
    /**
     * Intercept an EPUB image request.
     * 
     * @param url The full asset URL
     * @return WebResourceResponse with the image data or fallback
     */
    private fun interceptImageRequest(url: String): WebResourceResponse? {
        val relativePath = url.removePrefix(EpubImageAssetHelper.EPUB_IMAGES_BASE_URL)
        
        try {
            // Decode URL-encoded path
            val decodedPath = android.net.Uri.decode(relativePath)
            
            // Security: validate path
            if (!EpubImageAssetHelper.isPathSafe(decodedPath)) {
                AppLogger.w(TAG, "[INTERCEPT_BLOCKED] Path traversal rejected: $url")
                return null
            }
            
            val imageFile = File(imageCacheRoot, decodedPath)
            
            // Security: ensure resolved path is within cache root
            if (!EpubImageAssetHelper.isContainedWithin(imageFile, imageCacheRoot)) {
                AppLogger.w(TAG, "[INTERCEPT_BLOCKED] Path escapes cache: $url")
                return null
            }
            
            // Try to load with backoff for race conditions
            // Note: shouldInterceptRequest() is called on a background thread by WebView,
            // so Thread.sleep() is safe here and doesn't block the UI thread.
            var attemptIndex = 0
            while ((!imageFile.exists() || !imageFile.isFile) && attemptIndex < BACKOFF_DELAYS_MS.size) {
                val waitMs = BACKOFF_DELAYS_MS[attemptIndex]
                AppLogger.d(TAG, "[INTERCEPT_BACKOFF] url=$url attempt=${attemptIndex + 1} waitMs=$waitMs")
                
                try {
                    Thread.sleep(waitMs)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
                attemptIndex++
            }
            
            // Check if file exists after backoff
            if (!imageFile.exists() || !imageFile.isFile) {
                AppLogger.w(TAG, "[INTERCEPT_FALLBACK] Missing after backoff: $url")
                return createFallbackResponse()
            }
            
            // Determine MIME type
            val mimeType = EpubImageAssetHelper.guessMime(imageFile.name)
            
            // Create response
            val inputStream = FileInputStream(imageFile)
            val response = WebResourceResponse(mimeType, null, inputStream)
            response.responseHeaders = mapOf(
                "Cache-Control" to "max-age=3600"
            )
            
            AppLogger.d(TAG, "[INTERCEPT_SUCCESS] url=$url size=${imageFile.length()} mime=$mimeType")
            return response
            
        } catch (e: Exception) {
            AppLogger.e(TAG, "[INTERCEPT_ERROR] url=$url error=${e.message}", e)
            return createFallbackResponse()
        }
    }
    
    /**
     * Create a fallback response with a transparent PNG.
     */
    private fun createFallbackResponse(): WebResourceResponse {
        val inputStream = ByteArrayInputStream(EpubImageAssetHelper.TRANSPARENT_PNG_BYTES)
        val response = WebResourceResponse("image/png", null, inputStream)
        response.responseHeaders = mapOf(
            "Cache-Control" to "no-cache",
            "X-RiftedReader-Fallback" to "true"
        )
        return response
    }
}
