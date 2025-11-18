package com.rifters.riftedreader.util

import android.content.Context
import android.os.Build
import android.util.Log
import com.rifters.riftedreader.BuildConfig
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.util.UUID

/**
 * Application logger with session tracking and categorized logging
 * Provides task, event, user action, and performance logging with hierarchical tags
 */
object AppLogger {
    private const val DEFAULT_TAG = "AppLogger"
    private var logFile: File? = null
    private var sessionId: String? = null
    private var sessionMetadata: JSONObject? = null

    // Counters
    private var taskCount = 0
    private var eventCount = 0
    private var userActionCount = 0
    private var performanceCount = 0
    private var totalPerformanceTime = 0L

    // Tag breakdown
    private val tagCounts = mutableMapOf<String, Int>()

    /**
     * Initialize the logger with a specific log file
     */
    fun init(context: Context, fileName: String = LoggerConfig.defaultLogFile) {
        if (BuildConfig.DEBUG && LoggerConfig.enableFileLogging) {
            val dir = context.getExternalFilesDir("logs")
            if (dir != null && !dir.exists()) {
                dir.mkdirs()
            }
            logFile = File(dir, fileName)
        }
    }

    /**
     * Start a new logging session with metadata
     */
    fun startSession(context: Context, epubFileName: String? = null) {
        if (!BuildConfig.DEBUG) return
        
        sessionId = UUID.randomUUID().toString()
        sessionMetadata = JSONObject().apply {
            put("sessionId", sessionId)
            epubFileName?.let { put("epubFile", it) }
            put("appVersion", BuildConfig.VERSION_NAME)
            put("deviceModel", Build.MODEL)
            put("androidVersion", Build.VERSION.RELEASE)
            put("timestamp", System.currentTimeMillis())
        }
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

        // Reset counters
        taskCount = 0
        eventCount = 0
        userActionCount = 0
        performanceCount = 0
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
     * Convenience function for debugging with tag
     */
    fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG && LoggerConfig.enableLogcat) {
            Log.d(tag, message)
        }
    }

    /**
     * Convenience function for info logging with tag
     */
    fun i(tag: String, message: String) {
        if (BuildConfig.DEBUG && LoggerConfig.enableLogcat) {
            Log.i(tag, message)
        }
    }

    /**
     * Convenience function for warning with tag
     */
    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG && LoggerConfig.enableLogcat) {
            if (throwable != null) {
                Log.w(tag, message, throwable)
            } else {
                Log.w(tag, message)
            }
        }
    }

    /**
     * Convenience function for error with tag
     */
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG && LoggerConfig.enableLogcat) {
            if (throwable != null) {
                Log.e(tag, message, throwable)
            } else {
                Log.e(tag, message)
            }
        }
    }

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
