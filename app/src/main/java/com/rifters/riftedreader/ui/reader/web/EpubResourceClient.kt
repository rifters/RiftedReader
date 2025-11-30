package com.rifters.riftedreader.ui.reader.web

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import com.rifters.riftedreader.ui.reader.EpubImagePathHandler
import com.rifters.riftedreader.util.AppLogger
import com.rifters.riftedreader.util.EpubImageAssetHelper
import java.io.File

/**
 * WebView client builder for handling EPUB resource requests.
 *
 * This class provides a convenient builder pattern for creating a WebViewClient
 * pre-configured with WebViewAssetLoader for serving EPUB resources. It handles:
 *
 * - /epub-images/ - Cached EPUB images via EpubImagePathHandler
 * - /assets/ - Bundled JS/CSS via AssetsPathHandler
 *
 * Value Proposition:
 * While WebViewAssetLoader could be used directly, this builder:
 * 1. Encapsulates the path handler configuration for EPUB resources
 * 2. Provides lifecycle callbacks (onPageStarted, onPageFinished, onError)
 * 3. Ensures consistent logging across all EPUB WebViews
 * 4. Prevents configuration mistakes (enforces required imageCacheRoot)
 */
class EpubResourceClient private constructor(
    private val assetLoader: WebViewAssetLoader,
    private val imageCacheRoot: File,
    private val onPageFinished: ((WebView, String?) -> Unit)?,
    private val onPageStarted: ((WebView, String?) -> Unit)?,
    private val onResourceLoadError: ((url: String, description: String?) -> Unit)?
) {

    companion object {
        private const val TAG = "EpubResourceClient"
    }

    val webViewClient: WebViewClient = object : WebViewClient() {

        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?
        ): WebResourceResponse? {
            if (request == null || view == null) {
                return super.shouldInterceptRequest(view, request)
            }

            val response = assetLoader.shouldInterceptRequest(request.url)
            if (response != null) {
                AppLogger.d(TAG, "[INTERCEPT] Handled: ${request.url}")
                return response
            }

            return super.shouldInterceptRequest(view, request)
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
            super.onPageStarted(view, url, favicon)
            AppLogger.d(TAG, "[PAGE_STARTED] url=$url")
            onPageStarted?.let { callback ->
                view?.let { callback(it, url) }
            }
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            AppLogger.d(TAG, "[PAGE_FINISHED] url=$url")
            onPageFinished?.let { callback ->
                view?.let { callback(it, url) }
            }
        }

        @Suppress("DEPRECATION")
        override fun onReceivedError(
            view: WebView?,
            errorCode: Int,
            description: String?,
            failingUrl: String?
        ) {
            super.onReceivedError(view, errorCode, description, failingUrl)
            AppLogger.e(TAG, "[ERROR] code=$errorCode, desc=$description, url=$failingUrl", null)
            failingUrl?.let { url ->
                onResourceLoadError?.invoke(url, description)
            }
        }
    }

    class Builder(private val context: android.content.Context) {
        private var imageCacheRoot: File? = null
        private var onPageFinished: ((WebView, String?) -> Unit)? = null
        private var onPageStarted: ((WebView, String?) -> Unit)? = null
        private var onResourceLoadError: ((url: String, description: String?) -> Unit)? = null

        fun setImageCacheRoot(root: File): Builder {
            this.imageCacheRoot = root
            return this
        }

        fun setOnPageFinished(callback: (WebView, String?) -> Unit): Builder {
            this.onPageFinished = callback
            return this
        }

        fun setOnPageStarted(callback: (WebView, String?) -> Unit): Builder {
            this.onPageStarted = callback
            return this
        }

        fun setOnResourceLoadError(callback: (url: String, description: String?) -> Unit): Builder {
            this.onResourceLoadError = callback
            return this
        }

        fun build(): EpubResourceClient {
            val cacheRoot = imageCacheRoot
                ?: throw IllegalStateException("imageCacheRoot must be set")

            val assetLoader = WebViewAssetLoader.Builder()
                .setDomain(EpubImageAssetHelper.ASSET_HOST)
                .addPathHandler(
                    EpubImageAssetHelper.EPUB_IMAGES_PATH,
                    EpubImagePathHandler(cacheRoot)
                )
                .addPathHandler(
                    "/assets/",
                    WebViewAssetLoader.AssetsPathHandler(context)
                )
                .build()

            AppLogger.d(TAG, "[INIT] Built client with cacheRoot=${cacheRoot.absolutePath}")

            return EpubResourceClient(
                assetLoader = assetLoader,
                imageCacheRoot = cacheRoot,
                onPageFinished = onPageFinished,
                onPageStarted = onPageStarted,
                onResourceLoadError = onResourceLoadError
            )
        }
    }
}
