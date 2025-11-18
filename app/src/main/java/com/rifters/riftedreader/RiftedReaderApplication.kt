package com.rifters.riftedreader

import android.app.Application
import com.rifters.riftedreader.util.AppLogger

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
    }
    
    override fun onTerminate() {
        AppLogger.event("Application", "RiftedReader application terminated", "app/lifecycle")
        AppLogger.endSession()
        super.onTerminate()
    }
}
