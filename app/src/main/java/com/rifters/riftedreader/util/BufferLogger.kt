package com.rifters.riftedreader.util

import android.content.Context
import android.os.Build
import android.util.Log
import com.rifters.riftedreader.BuildConfig
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dedicated buffer health logger.
 *
 * Writes to its own file (separate from AppLogger's session log) so you can inspect
 * conveyor buffer stability without noise from other subsystems.
 */
object BufferLogger {
    private const val TAG = "BufferLogger"

    private var logFile: File? = null
    private var bufferedWriter: BufferedWriter? = null
    private val writeLock = Any()

    private val dateFormat: ThreadLocal<SimpleDateFormat> = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat {
            return SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        }
    }

    fun init(context: Context) {
        if (!BuildConfig.DEBUG) return

        // Keep logs alongside session logs but in a separate file.
        val dir = context.getExternalFilesDir("logs")
        if (dir != null && !dir.exists()) {
            dir.mkdirs()
        }
    }

    fun startSession(context: Context) {
        if (!BuildConfig.DEBUG) return

        closeWriter()

        val dir = context.getExternalFilesDir("logs")
        if (dir == null) {
            Log.w(TAG, "Cannot start buffer log session: externalFilesDir(logs) is null")
            return
        }

        if (!dir.exists()) {
            dir.mkdirs()
        }

        val timestamp = dateFormat.get()?.format(Date()) ?: "unknown"
        val fileName = "buffer_log_$timestamp.txt"
        logFile = File(dir, fileName)

        try {
            synchronized(writeLock) {
                bufferedWriter = BufferedWriter(FileWriter(logFile, true))
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to create buffer log file writer", e)
        }

        log(
            level = "START",
            event = "BufferSessionStart",
            message = "Buffer log session started",
            details = mapOf(
                "deviceModel" to Build.MODEL,
                "androidVersion" to Build.VERSION.RELEASE,
                "timestamp" to System.currentTimeMillis().toString()
            )
        )
    }

    fun endSession() {
        if (!BuildConfig.DEBUG) return

        log(level = "END", event = "BufferSessionEnd", message = "Buffer log session ended")
        closeWriter()
        logFile = null
    }

    fun log(
        level: String = "BUF",
        event: String,
        message: String,
        details: Map<String, String> = emptyMap()
    ) {
        if (!BuildConfig.DEBUG) return

        val ts = System.currentTimeMillis()
        val detailsStr = if (details.isEmpty()) {
            ""
        } else {
            details.entries.joinToString(prefix = " ", separator = " ") { (k, v) -> "$k=$v" }
        }

        val entry = "$ts $level $event $message$detailsStr"

        // Always mirror to logcat for quick live inspection.
        Log.d(TAG, entry)

        writeToFile(entry)
    }

    private fun writeToFile(entry: String) {
        synchronized(writeLock) {
            try {
                val writer = bufferedWriter ?: run {
                    val file = logFile ?: return
                    BufferedWriter(FileWriter(file, true)).also { bufferedWriter = it }
                }
                writer.write(entry)
                writer.newLine()
                writer.flush()
            } catch (e: IOException) {
                Log.e(TAG, "Failed to write buffer log file", e)
            }
        }
    }

    private fun closeWriter() {
        synchronized(writeLock) {
            try {
                bufferedWriter?.flush()
                bufferedWriter?.close()
            } catch (e: IOException) {
                Log.e(TAG, "Failed to close buffer log file writer", e)
            } finally {
                bufferedWriter = null
            }
        }
    }
}
