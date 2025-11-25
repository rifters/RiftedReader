package com.rifters.riftedreader.util

import android.content.Context
import android.os.Build
import android.util.Log
import com.rifters.riftedreader.BuildConfig
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Application logger with session tracking and categorized logging.
 * 
 * Provides task, event, user action, performance, and window navigation logging
 * with hierarchical tags. All debug output is written to both Logcat and the
 * session log file.
 * 
 * Session log files use timestamp-based naming (session_log_YYYYMMDD_HHmmss.txt)
 * and each new session creates a new file, allowing for session-specific debugging.
 */
object AppLogger {
    private const val DEFAULT_TAG = "AppLogger"
    private var logFile: File? = null
    private var logsDir: File? = null
    private var sessionId: String? = null
    private var sessionMetadata: JSONObject? = null
    
    // Current logical state for window navigation logging
    private var currentWindowState: WindowLogState? = null

    // Counters
    private var taskCount = 0
    private var eventCount = 0
    private var userActionCount = 0
    private var performanceCount = 0
    private var windowEventCount = 0
    private var totalPerformanceTime = 0L

    // Tag breakdown
    private val tagCounts = mutableMapOf<String, Int>()

    /**
     * Initialize the logger with the application context.
     * Creates the logs directory if it doesn't exist.
     * 
     * Note: The actual log file is created when startSession() is called,
     * using a timestamp-based filename.
     */
    fun init(context: Context, fileName: String = LoggerConfig.defaultLogFile) {
        if (BuildConfig.DEBUG && LoggerConfig.enableFileLogging) {
            val dir = context.getExternalFilesDir("logs")
            if (dir != null && !dir.exists()) {
                dir.mkdirs()
            }
            logsDir = dir
            // Create initial log file (will be replaced on startSession)
            logFile = File(dir, fileName)
        }
    }

    /**
     * Start a new logging session with metadata.
     * 
     * Creates a new timestamped log file (session_log_YYYYMMDD_HHmmss.txt).
     * Each session gets its own log file for easier debugging.
     * 
     * @param context Application context for file access
     * @param epubFileName Optional name of the book being read
     */
    fun startSession(context: Context, epubFileName: String? = null) {
        if (!BuildConfig.DEBUG) return
        
        sessionId = UUID.randomUUID().toString()
        
        // Create timestamped log file
        if (LoggerConfig.enableFileLogging) {
            val dir = logsDir ?: context.getExternalFilesDir("logs")
            if (dir != null) {
                if (!dir.exists()) {
                    dir.mkdirs()
                }
                logsDir = dir
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val fileName = "session_log_$timestamp.txt"
                logFile = File(dir, fileName)
            }
        }
        
        sessionMetadata = JSONObject().apply {
            put("sessionId", sessionId)
            epubFileName?.let { put("epubFile", it) }
            put("appVersion", BuildConfig.VERSION_NAME)
            put("deviceModel", Build.MODEL)
            put("androidVersion", Build.VERSION.RELEASE)
            put("timestamp", System.currentTimeMillis())
        }
        
        // Reset window state
        currentWindowState = null
        
        logEvent("SessionStart", "New session started with metadata")
    }

    /**
     * End the current logging session and log summary
     */
    fun endSession() {
        if (!BuildConfig.DEBUG || sessionId == null) return
        
        try {
            val summary = JSONObject().apply {
                put("sessionId", sessionId)
                put("tasks", taskCount)
                put("events", eventCount)
                put("userActions", userActionCount)
                put("windowEvents", windowEventCount)
                put("performanceLogs", performanceCount)
                put("avgPerformanceTimeMs", if (performanceCount > 0) totalPerformanceTime / performanceCount else 0)
                put("tagBreakdown", JSONObject(tagCounts as Map<*, *>))
            }
            
            logEvent("SessionSummary", summary.toString())
        } catch (e: Exception) {
            Log.e(DEFAULT_TAG, "Failed to create session summary", e)
        }

        logEvent("SessionEnd", "Session ended")
        sessionId = null
        sessionMetadata = null
        currentWindowState = null

        // Reset counters
        taskCount = 0
        eventCount = 0
        userActionCount = 0
        performanceCount = 0
        windowEventCount = 0
        totalPerformanceTime = 0
        tagCounts.clear()
    }

    private fun writeToFile(entry: String) {
        if (!LoggerConfig.enableFileLogging) return
        val file = logFile ?: return
        
        try {
            FileWriter(file, true).use { writer ->
                writer.write("$entry\n")
            }
        } catch (e: IOException) {
            Log.e(DEFAULT_TAG, "Failed to write log file", e)
        }
    }

    /**
     * Log a task execution
     */
    fun task(tag: String, message: String, contextTag: String = "") {
        taskCount++
        incrementTag(contextTag)
        logCustom("TASK", tag, message, contextTag)
    }

    /**
     * Log an event
     */
    fun event(tag: String, message: String, contextTag: String = "") {
        eventCount++
        incrementTag(contextTag)
        logCustom("EVENT", tag, message, contextTag)
    }

    /**
     * Log a user action
     */
    fun userAction(tag: String, message: String, contextTag: String = "") {
        userActionCount++
        incrementTag(contextTag)
        logCustom("USER_ACTION", tag, message, contextTag)
    }

    /**
     * Log performance measurement
     */
    fun performance(tag: String, message: String, durationMs: Long, contextTag: String = "") {
        performanceCount++
        totalPerformanceTime += durationMs
        incrementTag(contextTag)
        logCustom("PERFORMANCE", tag, "$message | Duration: ${durationMs}ms", contextTag)
    }

    private fun logCustom(level: String, tag: String, message: String, contextTag: String) {
        if (!BuildConfig.DEBUG) return
        
        val entry = "$level/${tag.ifEmpty { DEFAULT_TAG }}: $message${if (contextTag.isNotEmpty()) " [tag=$contextTag]" else ""}"
        
        if (LoggerConfig.enableLogcat) {
            Log.d(DEFAULT_TAG, entry)
        }
        writeToFile(entry)
    }

    private fun incrementTag(contextTag: String) {
        if (contextTag.isEmpty()) return
        
        tagCounts[contextTag] = tagCounts.getOrDefault(contextTag, 0) + 1

        // Hierarchical support
        if (contextTag.contains("/")) {
            val parts = contextTag.split("/")
            val hierarchy = StringBuilder()
            parts.forEachIndexed { index, part ->
                if (index > 0) hierarchy.append("/")
                hierarchy.append(part)
                val partial = hierarchy.toString()
                tagCounts[partial] = tagCounts.getOrDefault(partial, 0) + 1
            }
        }
    }

    /**
     * Log a generic event with details
     */
    fun logEvent(eventName: String, details: String) {
        if (!BuildConfig.DEBUG) return
        
        try {
            val event = JSONObject().apply {
                put("sessionId", sessionId ?: "no-session")
                put("event", eventName)
                put("details", details)
                put("timestamp", System.currentTimeMillis())
                sessionMetadata?.let { put("metadata", it) }
            }

            val jsonLog = event.toString()
            if (LoggerConfig.enableLogcat) {
                Log.d(DEFAULT_TAG, "EVENT: $jsonLog")
            }
            writeToFile(jsonLog)
        } catch (e: Exception) {
            Log.e(DEFAULT_TAG, "Failed to log event", e)
        }
    }

    /**
     * Convenience function for debugging with tag.
     * Outputs to both Logcat and session log file.
     */
    fun d(tag: String, message: String) {
        if (!BuildConfig.DEBUG) return
        
        if (LoggerConfig.enableLogcat) {
            Log.d(tag, message)
        }
        writeToFile("DEBUG/$tag: $message")
    }

    /**
     * Convenience function for info logging with tag.
     * Outputs to both Logcat and session log file.
     */
    fun i(tag: String, message: String) {
        if (!BuildConfig.DEBUG) return
        
        if (LoggerConfig.enableLogcat) {
            Log.i(tag, message)
        }
        writeToFile("INFO/$tag: $message")
    }

    /**
     * Convenience function for warning with tag.
     * Outputs to both Logcat and session log file.
     */
    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (!BuildConfig.DEBUG) return
        
        if (LoggerConfig.enableLogcat) {
            if (throwable != null) {
                Log.w(tag, message, throwable)
            } else {
                Log.w(tag, message)
            }
        }
        val logMessage = if (throwable != null) {
            "WARN/$tag: $message | Exception: ${throwable.message}"
        } else {
            "WARN/$tag: $message"
        }
        writeToFile(logMessage)
    }

    /**
     * Convenience function for error with tag.
     * Outputs to both Logcat and session log file.
     */
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (!BuildConfig.DEBUG) return
        
        if (LoggerConfig.enableLogcat) {
            if (throwable != null) {
                Log.e(tag, message, throwable)
            } else {
                Log.e(tag, message)
            }
        }
        val logMessage = if (throwable != null) {
            "ERROR/$tag: $message | Exception: ${throwable.message}"
        } else {
            "ERROR/$tag: $message"
        }
        writeToFile(logMessage)
    }

    // ================================================================================
    // Window Navigation Logging - Debug methods for sliding window pagination
    // ================================================================================
    
    /**
     * Log when entering a window with full logical state.
     * 
     * @param windowIndex The index of the window being entered
     * @param chapters List of chapter indices contained in this window
     * @param navigationMode Current navigation mode (e.g., "USER_SWIPE", "PRELOAD", "JUMP")
     * @param previousWindowIndex Optional previous window index for transition logging
     */
    fun logWindowEnter(
        windowIndex: Int,
        chapters: List<Int>,
        navigationMode: String = "UNKNOWN",
        previousWindowIndex: Int? = null
    ) {
        if (!BuildConfig.DEBUG) return
        
        windowEventCount++
        incrementTag("window/enter")
        
        // Update current window state
        currentWindowState = WindowLogState(
            windowIndex = windowIndex,
            chapters = chapters,
            navigationMode = navigationMode
        )
        
        try {
            val event = JSONObject().apply {
                put("sessionId", sessionId ?: "no-session")
                put("event", "WINDOW_ENTER")
                put("windowIndex", windowIndex)
                put("chapters", chapters.joinToString(","))
                put("chapterCount", chapters.size)
                put("navigationMode", navigationMode)
                previousWindowIndex?.let { put("previousWindowIndex", it) }
                put("timestamp", System.currentTimeMillis())
                currentWindowState?.let { state ->
                    put("logicalState", JSONObject().apply {
                        put("activeWindowIndex", state.windowIndex)
                        put("chaptersList", state.chapters.joinToString(","))
                        put("navigationMode", state.navigationMode)
                    })
                }
            }
            
            val jsonLog = event.toString()
            if (LoggerConfig.enableLogcat) {
                Log.d(DEFAULT_TAG, "WINDOW_ENTER: windowIndex=$windowIndex, chapters=${chapters.joinToString(",")}, mode=$navigationMode")
            }
            writeToFile(jsonLog)
        } catch (e: Exception) {
            Log.e(DEFAULT_TAG, "Failed to log window enter", e)
        }
    }
    
    /**
     * Log when exiting a window with full logical state.
     * 
     * @param windowIndex The index of the window being exited
     * @param chapters List of chapter indices contained in this window
     * @param direction Direction of exit ("NEXT", "PREV", "JUMP")
     * @param targetWindowIndex The window being navigated to
     */
    fun logWindowExit(
        windowIndex: Int,
        chapters: List<Int>,
        direction: String,
        targetWindowIndex: Int
    ) {
        if (!BuildConfig.DEBUG) return
        
        windowEventCount++
        incrementTag("window/exit")
        
        try {
            val event = JSONObject().apply {
                put("sessionId", sessionId ?: "no-session")
                put("event", "WINDOW_EXIT")
                put("windowIndex", windowIndex)
                put("chapters", chapters.joinToString(","))
                put("direction", direction)
                put("targetWindowIndex", targetWindowIndex)
                put("timestamp", System.currentTimeMillis())
                currentWindowState?.let { state ->
                    put("logicalState", JSONObject().apply {
                        put("activeWindowIndex", state.windowIndex)
                        put("chaptersList", state.chapters.joinToString(","))
                        put("navigationMode", state.navigationMode)
                    })
                }
            }
            
            val jsonLog = event.toString()
            if (LoggerConfig.enableLogcat) {
                Log.d(DEFAULT_TAG, "WINDOW_EXIT: windowIndex=$windowIndex, direction=$direction, target=$targetWindowIndex")
            }
            writeToFile(jsonLog)
        } catch (e: Exception) {
            Log.e(DEFAULT_TAG, "Failed to log window exit", e)
        }
    }
    
    /**
     * Log navigation events with full logical state.
     * 
     * @param eventType Type of navigation (e.g., "CHAPTER_SWITCH", "PAGE_TURN", "BOUNDARY_REACHED")
     * @param fromWindowIndex Current window index
     * @param toWindowIndex Target window index (may be same as fromWindowIndex)
     * @param fromChapterIndex Current chapter index
     * @param toChapterIndex Target chapter index
     * @param fromPageIndex Current page index within chapter
     * @param toPageIndex Target page index within chapter
     * @param additionalInfo Optional additional info map
     */
    fun logNavigation(
        eventType: String,
        fromWindowIndex: Int,
        toWindowIndex: Int,
        fromChapterIndex: Int,
        toChapterIndex: Int,
        fromPageIndex: Int = 0,
        toPageIndex: Int = 0,
        additionalInfo: Map<String, Any>? = null
    ) {
        if (!BuildConfig.DEBUG) return
        
        windowEventCount++
        incrementTag("window/navigation")
        
        try {
            val event = JSONObject().apply {
                put("sessionId", sessionId ?: "no-session")
                put("event", "NAVIGATION")
                put("eventType", eventType)
                put("fromWindowIndex", fromWindowIndex)
                put("toWindowIndex", toWindowIndex)
                put("fromChapterIndex", fromChapterIndex)
                put("toChapterIndex", toChapterIndex)
                put("fromPageIndex", fromPageIndex)
                put("toPageIndex", toPageIndex)
                put("isWindowSwitch", fromWindowIndex != toWindowIndex)
                put("isChapterSwitch", fromChapterIndex != toChapterIndex)
                put("timestamp", System.currentTimeMillis())
                additionalInfo?.forEach { (key, value) -> put(key, value) }
                currentWindowState?.let { state ->
                    put("logicalState", JSONObject().apply {
                        put("activeWindowIndex", state.windowIndex)
                        put("chaptersList", state.chapters.joinToString(","))
                        put("navigationMode", state.navigationMode)
                    })
                }
            }
            
            val jsonLog = event.toString()
            if (LoggerConfig.enableLogcat) {
                Log.d(DEFAULT_TAG, "NAVIGATION: type=$eventType, window=$fromWindowIndex->$toWindowIndex, chapter=$fromChapterIndex->$toChapterIndex")
            }
            writeToFile(jsonLog)
        } catch (e: Exception) {
            Log.e(DEFAULT_TAG, "Failed to log navigation", e)
        }
    }
    
    /**
     * Log window boundary conditions (edge cases).
     * 
     * @param boundaryType Type of boundary ("START", "END", "CHAPTER_START", "CHAPTER_END")
     * @param windowIndex Current window index
     * @param chapterIndex Current chapter index
     * @param action Action taken or to be taken
     */
    fun logBoundaryCondition(
        boundaryType: String,
        windowIndex: Int,
        chapterIndex: Int,
        action: String
    ) {
        if (!BuildConfig.DEBUG) return
        
        windowEventCount++
        incrementTag("window/boundary")
        
        try {
            val event = JSONObject().apply {
                put("sessionId", sessionId ?: "no-session")
                put("event", "BOUNDARY_CONDITION")
                put("boundaryType", boundaryType)
                put("windowIndex", windowIndex)
                put("chapterIndex", chapterIndex)
                put("action", action)
                put("timestamp", System.currentTimeMillis())
                currentWindowState?.let { state ->
                    put("logicalState", JSONObject().apply {
                        put("activeWindowIndex", state.windowIndex)
                        put("chaptersList", state.chapters.joinToString(","))
                        put("navigationMode", state.navigationMode)
                    })
                }
            }
            
            val jsonLog = event.toString()
            if (LoggerConfig.enableLogcat) {
                Log.d(DEFAULT_TAG, "BOUNDARY: type=$boundaryType, window=$windowIndex, chapter=$chapterIndex, action=$action")
            }
            writeToFile(jsonLog)
        } catch (e: Exception) {
            Log.e(DEFAULT_TAG, "Failed to log boundary condition", e)
        }
    }
    
    /**
     * Update the current logical window state.
     * Call this when the window state changes to ensure accurate state logging.
     * 
     * @param windowIndex Current active window index
     * @param chapters List of chapters in the active window
     * @param navigationMode Current navigation mode
     */
    fun updateWindowState(windowIndex: Int, chapters: List<Int>, navigationMode: String) {
        currentWindowState = WindowLogState(windowIndex, chapters, navigationMode)
    }
    
    /**
     * Get the current session ID for external use.
     */
    fun getSessionId(): String? = sessionId
    
    /**
     * Get the current log file path for external reference.
     */
    fun getLogFilePath(): String? = logFile?.absolutePath

    /**
     * Measure and log performance of a block of code
     */
    inline fun <T> measurePerformance(tag: String, message: String, contextTag: String = "", block: () -> T): T {
        val startTime = System.currentTimeMillis()
        try {
            return block()
        } finally {
            val duration = System.currentTimeMillis() - startTime
            performance(tag, message, duration, contextTag)
        }
    }
}

/**
 * Data class to track the current window logical state for debug logging.
 */
data class WindowLogState(
    val windowIndex: Int,
    val chapters: List<Int>,
    val navigationMode: String
)
