package com.rifters.riftedreader.util

import com.rifters.riftedreader.BuildConfig

/**
 * Centralized diagnostic logger with categorized logging and single toggle.
 *
 * All diagnostic logs go through this class, allowing them to be enabled/disabled
 * with a single setting. Categories help filter logs during debugging.
 *
 * **Categories:**
 * - `[Pagination]` - Window computation, chapter indexing, page counts
 * - `[TTS]` - Text-to-speech initialization, chunks, highlights
 * - `[WebViewBridge]` - JS evaluation, lifecycle safety
 * - `[Parser]` - EPUB/PDF/TXT parsing, spine analysis
 * - `[Lifecycle]` - Fragment/Activity lifecycle events
 *
 * **Usage:**
 * ```kotlin
 * // Enable diagnostics (typically from a preference)
 * DiagnosticLogger.setEnabled(true)
 *
 * // Log with category
 * DiagnosticLogger.log(DiagnosticCategory.PAGINATION, "Window count: $count")
 *
 * // Structured logging with key-value pairs
 * DiagnosticLogger.logStructured(DiagnosticCategory.TTS,
 *     "chunkIndex" to 5,
 *     "textLength" to 150
 * )
 * ```
 */
object DiagnosticLogger {

    /**
     * Categories for diagnostic logs.
     * Each category has a prefix for easy filtering.
     */
    enum class Category(val prefix: String) {
        /** Window computation, chapter indexing, page counts */
        PAGINATION("[Pagination]"),

        /** Text-to-speech initialization, chunks, highlights */
        TTS("[TTS]"),

        /** JS evaluation, WebView lifecycle safety */
        WEBVIEW_BRIDGE("[WebViewBridge]"),

        /** EPUB/PDF/TXT parsing, spine analysis */
        PARSER("[Parser]"),

        /** Fragment/Activity lifecycle events */
        LIFECYCLE("[Lifecycle]"),

        /** General diagnostics that don't fit other categories */
        GENERAL("[Diag]")
    }

    /** Whether diagnostic logging is enabled */
    @Volatile
    private var isEnabled: Boolean = BuildConfig.DEBUG

    /**
     * Enable or disable diagnostic logging.
     * When disabled, all diagnostic log calls are no-ops.
     *
     * @param enabled Whether to enable diagnostics
     */
    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        AppLogger.d(TAG, "DiagnosticLogger enabled=$enabled")
    }

    /**
     * Check if diagnostics are enabled.
     */
    fun isEnabled(): Boolean = isEnabled

    /**
     * Log a diagnostic message with category.
     *
     * @param category The diagnostic category
     * @param message The message to log
     * @param tag Optional custom tag (defaults to category-based tag)
     */
    fun log(category: Category, message: String, tag: String? = null) {
        if (!isEnabled) return

        val fullTag = tag ?: "Diagnostic"
        val fullMessage = "${category.prefix} $message"
        AppLogger.d(fullTag, fullMessage)
    }

    /**
     * Log a debug-level diagnostic message.
     */
    fun d(category: Category, message: String, tag: String? = null) {
        if (!isEnabled) return
        log(category, message, tag)
    }

    /**
     * Log an info-level diagnostic message.
     */
    fun i(category: Category, message: String, tag: String? = null) {
        if (!isEnabled) return
        val fullTag = tag ?: "Diagnostic"
        val fullMessage = "${category.prefix} $message"
        AppLogger.i(fullTag, fullMessage)
    }

    /**
     * Log a warning-level diagnostic message.
     */
    fun w(category: Category, message: String, tag: String? = null) {
        if (!isEnabled) return
        val fullTag = tag ?: "Diagnostic"
        val fullMessage = "${category.prefix} $message"
        AppLogger.w(fullTag, fullMessage)
    }

    /**
     * Log an error-level diagnostic message.
     */
    fun e(category: Category, message: String, throwable: Throwable? = null, tag: String? = null) {
        if (!isEnabled) return
        val fullTag = tag ?: "Diagnostic"
        val fullMessage = "${category.prefix} $message"
        AppLogger.e(fullTag, fullMessage, throwable)
    }

    /**
     * Log structured key-value pairs.
     *
     * @param category The diagnostic category
     * @param pairs Key-value pairs to log
     * @param tag Optional custom tag
     */
    fun logStructured(category: Category, vararg pairs: Pair<String, Any?>, tag: String? = null) {
        if (!isEnabled) return

        val kvString = pairs.joinToString(", ") { (k, v) -> "$k=$v" }
        log(category, kvString, tag)
    }

    /**
     * Log chapter counting diagnostics.
     *
     * @param source Description of the count source
     * @param spineCount Count from spine
     * @param visibleCount Count from visible chapters
     * @param windowCount Computed window count
     */
    fun logChapterCounts(
        source: String,
        spineCount: Int,
        visibleCount: Int,
        windowCount: Int
    ) {
        if (!isEnabled) return

        log(Category.PAGINATION, "ChapterCounts[$source]: " +
            "spine=$spineCount, visible=$visibleCount, windows=$windowCount")
    }

    /**
     * Log window construction timeline event.
     *
     * @param event Event description (e.g., "start", "computed", "complete")
     * @param windowIndex The window being constructed
     * @param details Additional details
     */
    fun logWindowTimeline(event: String, windowIndex: Int, details: String = "") {
        if (!isEnabled) return

        val msg = "WindowBuild[$event]: windowIndex=$windowIndex${if (details.isNotEmpty()) " $details" else ""}"
        log(Category.PAGINATION, msg)
    }

    /**
     * Log JS injection attempt.
     *
     * @param scriptName Name of the script being injected
     * @param attempt Attempt number (1-based)
     * @param result Result of the attempt ("success", "duplicate", "failed")
     */
    fun logJsInjection(scriptName: String, attempt: Int, result: String) {
        if (!isEnabled) return

        log(Category.WEBVIEW_BRIDGE, "JsInjection[$scriptName]: attempt=$attempt, result=$result")
    }

    /**
     * Log JS evaluation cancellation.
     *
     * @param reason Reason for cancellation
     * @param expression The JS expression (sanitized and truncated for security)
     */
    fun logJsEvaluationCancelled(reason: String, expression: String) {
        if (!isEnabled) return

        // Sanitize expression to avoid logging sensitive data:
        // - Truncate to 30 chars
        // - Replace potential sensitive content patterns
        val sanitized = expression
            .take(30)
            .replace(Regex("[a-zA-Z0-9_]+\\s*[=:]\\s*['\"][^'\"]+['\"]"), "***")  // Hide assignments
            .replace(Regex("token|password|secret|key|auth", RegexOption.IGNORE_CASE), "***")
        
        log(Category.WEBVIEW_BRIDGE, "JsEvalCancelled: reason=$reason, expr=$sanitized...")
    }

    /**
     * Log TTS initialization event.
     *
     * @param event Event description (e.g., "rootCreated", "chunksReady")
     * @param details Additional details
     */
    fun logTtsInit(event: String, details: String = "") {
        if (!isEnabled) return

        log(Category.TTS, "TtsInit[$event]${if (details.isNotEmpty()) ": $details" else ""}")
    }

    private const val TAG = "DiagnosticLogger"
}
