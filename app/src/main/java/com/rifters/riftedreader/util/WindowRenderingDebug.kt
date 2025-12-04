package com.rifters.riftedreader.util

import android.graphics.Color
import android.view.View
import com.rifters.riftedreader.BuildConfig

/**
 * Debug instrumentation utility for window rendering diagnostics.
 * 
 * Provides visual markers (distinct background colors per window index) and
 * enhanced logging for debugging "blank window" issues in continuous pagination mode.
 * 
 * All instrumentation is:
 * - Opt-in via `debugWindowRenderingEnabled` setting
 * - Only active in debug builds
 * - Easy to disable by toggling the setting
 * 
 * Usage:
 * - Call [applyWindowDebugBackground] in ReaderPageFragment to set distinct colors
 * - Call [logWebViewState] before loading HTML to log WebView dimensions and visibility
 * - Call [logWindowNavigationCoherence] in ReaderActivity to log navigation state
 */
object WindowRenderingDebug {
    
    private const val TAG = "WindowRenderingDebug"
    
    /**
     * Distinct debug background colors for windows 0-7.
     * Colors are semi-transparent to overlay content without hiding it.
     * Each color is clearly distinct to identify which window is visible.
     */
    private val WINDOW_DEBUG_COLORS = listOf(
        0x40FF0000, // Red (25% opacity)
        0x4000FF00, // Green (25% opacity)
        0x400000FF, // Blue (25% opacity)
        0x40FFFF00, // Yellow (25% opacity)
        0x40FF00FF, // Magenta (25% opacity)
        0x4000FFFF, // Cyan (25% opacity)
        0x40FFA500, // Orange (25% opacity)
        0x40800080  // Purple (25% opacity)
    )
    
    /**
     * Get the debug color for a specific window index.
     * Colors cycle for indices >= 8.
     * 
     * @param windowIndex The window index (0-based)
     * @return ARGB color int with 25% opacity
     */
    fun getDebugColor(windowIndex: Int): Int {
        return WINDOW_DEBUG_COLORS[windowIndex % WINDOW_DEBUG_COLORS.size]
    }
    
    /**
     * Get a human-readable color name for logging.
     */
    private fun getColorName(windowIndex: Int): String {
        return when (windowIndex % WINDOW_DEBUG_COLORS.size) {
            0 -> "RED"
            1 -> "GREEN"
            2 -> "BLUE"
            3 -> "YELLOW"
            4 -> "MAGENTA"
            5 -> "CYAN"
            6 -> "ORANGE"
            7 -> "PURPLE"
            else -> "UNKNOWN"
        }
    }
    
    /**
     * Apply a debug background color to a view based on window index.
     * Only active in debug builds when [enabled] is true.
     * 
     * @param windowIndex The window index for color selection
     * @param rootView The view to apply the background color to
     * @param enabled Whether debug window rendering is enabled (from settings)
     */
    fun applyWindowDebugBackground(windowIndex: Int, rootView: View, enabled: Boolean) {
        if (!BuildConfig.DEBUG || !enabled) {
            // Clear any previously applied debug background
            // (Use transparent to not interfere with normal theming)
            return
        }
        
        val debugColor = getDebugColor(windowIndex)
        val colorName = getColorName(windowIndex)
        
        // Apply the debug color as a foreground overlay to avoid interfering with theme background
        rootView.foreground = android.graphics.drawable.ColorDrawable(debugColor)
        
        AppLogger.d(
            TAG,
            "[DEBUG_WINDOW_BG] Applied $colorName overlay to window $windowIndex " +
            "(color=0x${Integer.toHexString(debugColor)})"
        )
    }
    
    /**
     * Clear any debug background previously applied to a view.
     * Safe to call in release builds or when debug is disabled.
     * 
     * @param rootView The view to clear the foreground from
     */
    fun clearWindowDebugBackground(rootView: View) {
        if (BuildConfig.DEBUG) {
            rootView.foreground = null
        }
    }
    
    /**
     * Log WebView visibility and state before loading HTML.
     * Helps diagnose issues where WebView is hidden or has zero dimensions.
     * 
     * @param tag Logging tag (typically the fragment class name)
     * @param windowIndex The window index being loaded
     * @param webViewWidth WebView width in pixels
     * @param webViewHeight WebView height in pixels
     * @param webViewVisibility WebView visibility constant (View.VISIBLE, etc.)
     * @param webViewAlpha WebView alpha value (0.0 - 1.0)
     * @param htmlLength Length of HTML content being loaded (or null if not loading)
     * @param isPayloadNull Whether the window payload was null
     * @param enabled Whether debug window rendering is enabled
     */
    fun logWebViewState(
        tag: String,
        windowIndex: Int,
        webViewWidth: Int,
        webViewHeight: Int,
        webViewVisibility: Int,
        webViewAlpha: Float,
        htmlLength: Int?,
        isPayloadNull: Boolean,
        enabled: Boolean
    ) {
        if (!BuildConfig.DEBUG || !enabled) return
        
        val visibilityName = when (webViewVisibility) {
            View.VISIBLE -> "VISIBLE"
            View.INVISIBLE -> "INVISIBLE"
            View.GONE -> "GONE"
            else -> "UNKNOWN($webViewVisibility)"
        }
        
        val sizeWarning = if (webViewWidth == 0 || webViewHeight == 0) " [ZERO_SIZE_WARNING]" else ""
        val payloadWarning = if (isPayloadNull) " [NULL_PAYLOAD_WARNING]" else ""
        val htmlInfo = htmlLength?.let { "htmlLength=$it" } ?: "htmlLength=N/A"
        
        AppLogger.d(
            tag,
            "[DEBUG_WEBVIEW_STATE] windowIndex=$windowIndex, " +
            "size=${webViewWidth}x${webViewHeight}$sizeWarning, " +
            "visibility=$visibilityName, alpha=$webViewAlpha, " +
            "$htmlInfo, payloadNull=$isPayloadNull$payloadWarning"
        )
    }
    
    /**
     * Log window/scroll coherence in ReaderActivity.
     * Helps distinguish between "window never becomes visible" vs "visible but not drawn".
     * 
     * @param tag Logging tag (typically "ReaderActivity")
     * @param eventType Type of navigation event (e.g., "SCROLL_SETTLE", "NEXT_WINDOW", "PREV_WINDOW")
     * @param requestedWindow The window index that was requested/navigated to
     * @param settledPosition The RecyclerView position after scroll settled
     * @param viewModelWindowIndex The current window index in ViewModel
     * @param isProgrammatic Whether this was a programmatic scroll (vs user gesture)
     * @param additionalInfo Optional additional context
     * @param enabled Whether debug window rendering is enabled
     */
    fun logWindowNavigationCoherence(
        tag: String,
        eventType: String,
        requestedWindow: Int?,
        settledPosition: Int,
        viewModelWindowIndex: Int,
        isProgrammatic: Boolean,
        additionalInfo: String? = null,
        enabled: Boolean
    ) {
        if (!BuildConfig.DEBUG || !enabled) return
        
        val coherenceStatus = when {
            requestedWindow == null -> "N/A"
            requestedWindow == settledPosition && settledPosition == viewModelWindowIndex -> "COHERENT"
            requestedWindow == settledPosition && settledPosition != viewModelWindowIndex -> "VIEWMODEL_MISMATCH"
            requestedWindow != settledPosition -> "POSITION_MISMATCH"
            else -> "UNKNOWN"
        }
        
        val scrollType = if (isProgrammatic) "PROGRAMMATIC" else "USER_GESTURE"
        val extra = additionalInfo?.let { " | $it" } ?: ""
        
        AppLogger.d(
            tag,
            "[DEBUG_NAV_COHERENCE] event=$eventType, " +
            "requested=$requestedWindow, settled=$settledPosition, vmWindow=$viewModelWindowIndex, " +
            "scrollType=$scrollType, status=$coherenceStatus$extra"
        )
    }
    
    /**
     * Generate an HTML debug banner for insertion into window HTML.
     * Shows window index and chapter range for visual identification.
     * 
     * @param windowIndex The window index
     * @param firstChapterIndex First chapter index in this window
     * @param lastChapterIndex Last chapter index in this window
     * @param enabled Whether debug window rendering is enabled
     * @return HTML string for the banner, or empty string if disabled
     */
    fun generateHtmlDebugBanner(
        windowIndex: Int,
        firstChapterIndex: Int,
        lastChapterIndex: Int,
        enabled: Boolean
    ): String {
        if (!BuildConfig.DEBUG || !enabled) return ""
        
        val colorName = getColorName(windowIndex)
        val debugColor = getDebugColor(windowIndex)
        // Convert to CSS rgba format (using 85% opacity for the banner to be visible)
        val r = (debugColor shr 16) and 0xFF
        val g = (debugColor shr 8) and 0xFF
        val b = debugColor and 0xFF
        
        val chapterRange = if (firstChapterIndex == lastChapterIndex) {
            "Ch $firstChapterIndex"
        } else {
            "Ch $firstChapterIndex-$lastChapterIndex"
        }
        
        return """
            <div style="
                position: fixed;
                top: 0;
                left: 0;
                right: 0;
                background: rgba($r, $g, $b, 0.85);
                color: #fff;
                font-size: 12px;
                padding: 4px 8px;
                font-family: monospace;
                z-index: 9999;
                text-shadow: 1px 1px 1px #000;
                pointer-events: none;
            ">
                W$windowIndex [$chapterRange] | $colorName
            </div>
        """.trimIndent()
    }
}
