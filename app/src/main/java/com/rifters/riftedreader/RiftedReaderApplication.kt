package com.rifters.riftedreader

import android.app.Application
import com.rifters.riftedreader.util.AppLogger
import com.rifters.riftedreader.util.HtmlDebugLogger

/**
 * Application class for RiftedReader
 * Handles app-wide initialization
 */
class RiftedReaderApplication : Application() {
    
    override fun onCreate() {
        super.onCreate()
        
        // Initialize logger
        AppLogger.init(this)
        AppLogger.startSession(this)
        AppLogger.event("Application", "RiftedReader application started", "app/lifecycle")
        
        // Initialize HTML debug logger for pagination debugging
        HtmlDebugLogger.init(this)
    }
    
    override fun onTerminate() {
        AppLogger.event("Application", "RiftedReader application terminated", "app/lifecycle")
        AppLogger.endSession()
        
        // Clean up old HTML debug logs
        HtmlDebugLogger.cleanupOldLogs(maxFiles = 50)
        
        super.onTerminate()
    }
}
