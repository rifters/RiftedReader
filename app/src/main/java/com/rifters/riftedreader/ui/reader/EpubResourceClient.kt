package com.rifters.riftedreader.ui.reader

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import com.rifters.riftedreader.util.AppLogger
import com.rifters.riftedreader.util.EpubImageAssetHelper
import java.io.File

/**
 * WebViewClient that intercepts requests for EPUB resources.
 * 
 * Combines WebViewAssetLoader for app assets with EpubResourceInterceptor for
 * cached EPUB images, providing a unified resource loading solution.
 * 
 * Features:
 * - Asset loading via WebViewAssetLoader (for JS, CSS, fonts)
 * - EPUB image loading via EpubResourceInterceptor with cache+fallback
 * - Structured logging with [RESOURCE_*] prefixes
 * 
 * Usage:
 * ```kotlin
 * val client = EpubResourceClient(context, imageCacheRoot) { view, url ->
 *     // Handle page load completion
 * }
 * webView.webViewClient = client
 * ```
 */
class EpubResourceClient(
    context: Context,
    imageCacheRoot: File,
    private val onPageFinished: ((WebView?, String?) -> Unit)? = null
) : WebViewClient() {
    
    companion object {
        private const val TAG = "EpubResourceClient"
    }
    
    // Asset loader for app assets (JS, CSS, fonts)
    private val assetLoader: WebViewAssetLoader = WebViewAssetLoader.Builder()
        .setDomain(EpubImageAssetHelper.ASSET_HOST)
        .addPathHandler(
            EpubImageAssetHelper.EPUB_IMAGES_PATH,
            EpubImagePathHandler(imageCacheRoot)
        )
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .build()
    
    // Resource interceptor for EPUB images
    private val resourceInterceptor = EpubResourceInterceptor(imageCacheRoot)
    
    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        if (request == null || view == null) {
            return super.shouldInterceptRequest(view, request)
        }
        
        val url = request.url?.toString() ?: return super.shouldInterceptRequest(view, request)
        
        // First try the asset loader (handles both assets and EPUB images via EpubImagePathHandler)
        val assetResponse = assetLoader.shouldInterceptRequest(request.url)
        if (assetResponse != null) {
            AppLogger.d(TAG, "[RESOURCE_INTERCEPT] WebViewAssetLoader handled: $url")
            return assetResponse
        }
        
        // Fallback to resource interceptor for any remaining EPUB image requests
        val interceptorResponse = resourceInterceptor.shouldIntercept(request)
        if (interceptorResponse != null) {
            AppLogger.d(TAG, "[RESOURCE_INTERCEPT] EpubResourceInterceptor handled: $url")
            return interceptorResponse
        }
        
        return super.shouldInterceptRequest(view, request)
    }
    
    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        AppLogger.d(TAG, "[RESOURCE_PAGE_FINISHED] url=$url")
        onPageFinished?.invoke(view, url)
    }
    
    override fun onReceivedError(
        view: WebView?,
        errorCode: Int,
        description: String?,
        failingUrl: String?
    ) {
        super.onReceivedError(view, errorCode, description, failingUrl)
        AppLogger.e(TAG, "[RESOURCE_ERROR] code=$errorCode desc=$description url=$failingUrl")
    }
}
