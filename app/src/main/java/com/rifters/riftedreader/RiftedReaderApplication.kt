package com.rifters.riftedreader

import android.app.Application
import com.rifters.riftedreader.util.AppLogger
import com.rifters.riftedreader.util.HtmlDebugLogger

/**
 * Application class for RiftedReader
 * Handles app-wide initialization
 */
class RiftedReaderApplication : Application() {

    private var previousExceptionHandler: Thread.UncaughtExceptionHandler? = null
    
    override fun onCreate() {
        super.onCreate()

        installUncaughtExceptionLogger()
        
        // Initialize logger
        AppLogger.init(this)
        AppLogger.startSession(this)
        AppLogger.event("Application", "RiftedReader application started", "app/lifecycle")
        
        // Initialize HTML debug logger for pagination debugging
        HtmlDebugLogger.init(this)
    }

    private fun installUncaughtExceptionLogger() {
        if (previousExceptionHandler != null) return

        previousExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                AppLogger.e(
                    "Crash",
                    "Uncaught exception on thread=${thread.name} (${thread.id})",
                    throwable
                )
                AppLogger.event("Crash", "Process will terminate", "app/crash")
                AppLogger.endSession()
            } catch (_: Throwable) {
                // Best-effort logging only.
            } finally {
                previousExceptionHandler?.uncaughtException(thread, throwable)
            }
        }
    }
    
    override fun onTerminate() {
        AppLogger.event("Application", "RiftedReader application terminated", "app/lifecycle")
        AppLogger.endSession()
        
        // Clean up old HTML debug logs
        HtmlDebugLogger.cleanupOldLogs(maxFiles = 50)
        
        super.onTerminate()
    }
}
