package com.rifters.riftedreader.util

import android.webkit.JavascriptInterface

/**
 * JavaScript bridge to expose AppLogger to WebView JavaScript code.
 * This allows JavaScript logging to appear in Android logcat and log files.
 * 
 * Usage from JavaScript:
 *   window.AppLogger.d("TAG", "Debug message")
 *   window.AppLogger.i("TAG", "Info message")
 *   window.AppLogger.w("TAG", "Warning message")
 *   window.AppLogger.e("TAG", "Error message")
 */
class AppLoggerBridge {
    
    /**
     * Debug log from JavaScript
     */
    @JavascriptInterface
    fun d(tag: String, message: String) {
        AppLogger.d(tag, message)
    }
    
    /**
     * Error log from JavaScript
     */
    @JavascriptInterface
    fun e(tag: String, message: String) {
        AppLogger.e(tag, message)
    }
    
    /**
     * Warning log from JavaScript
     */
    @JavascriptInterface
    fun w(tag: String, message: String) {
        AppLogger.w(tag, message)
    }
    
    /**
     * Info log from JavaScript
     */
    @JavascriptInterface
    fun i(tag: String, message: String) {
        AppLogger.i(tag, message)
    }
}
