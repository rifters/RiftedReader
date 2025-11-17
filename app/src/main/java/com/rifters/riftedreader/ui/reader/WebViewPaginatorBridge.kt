package com.rifters.riftedreader.ui.reader

import android.os.Handler
import android.os.Looper
import android.webkit.WebView

/**
 * Bridge for communicating with the in-page paginator JavaScript API.
 * 
 * Provides convenient Kotlin functions to interact with the inpagePaginator
 * JavaScript object injected into WebView content.
 */
object WebViewPaginatorBridge {
    
    private val mainHandler = Handler(Looper.getMainLooper())
    
    /**
     * Set the font size for the paginated content.
     * This will trigger a reflow of the columns.
     * This method does not suspend - it fires and forgets.
     * 
     * @param webView The WebView containing the paginated content
     * @param px The font size in pixels
     */
    fun setFontSize(webView: WebView, px: Int) {
        mainHandler.post {
            webView.evaluateJavascript(
                "window.inpagePaginator.setFontSize($px)",
                null
            )
        }
    }
}
