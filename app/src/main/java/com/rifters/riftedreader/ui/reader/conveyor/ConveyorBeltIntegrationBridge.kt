package com.rifters.riftedreader.ui.reader.conveyor

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.rifters.riftedreader.ui.reader.ReaderViewModel
import com.rifters.riftedreader.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Non-invasive observer that routes window visibility events to the isolated conveyor system.
 * 
 * **This bridge runs completely in parallel without modifying existing code paths.**
 * 
 * **VERSION: 2025-12-06 Cache Clear Test - Updated for fresh reload**
 * 
 * ## Purpose
 * 
 * - Listens to ReaderViewModel events without modifying its code
 * - Routes window navigation events to ConveyorBeltSystemViewModel
 * - Provides side-by-side logging to compare behavior
 * - Enables debugging of why phase transitions don't work in the integrated system
 * 
 * ## How It Works
 * 
 * 1. Observes ReaderViewModel's currentWindowIndex StateFlow
 * 2. When window changes, forwards the event to the isolated system
 * 3. Logs both systems' states for comparison
 * 
 * ## Usage
 * 
 * ```kotlin
 * // In ReaderActivity or similar
 * val bridge = ConveyorBeltIntegrationBridge().attach(viewModel, conveyorViewModel)
 * lifecycle.addObserver(bridge)
 * ```
 */
class ConveyorBeltIntegrationBridge private constructor() : DefaultLifecycleObserver {
    
    companion object {
        private const val TAG = "ConveyorBridge"
        private const val LOG_PREFIX = "[CONVEYOR_BRIDGE]"

        fun create(
            readerViewModel: ReaderViewModel,
            conveyorViewModel: ConveyorBeltSystemViewModel
        ): ConveyorBeltIntegrationBridge {
            return ConveyorBeltIntegrationBridge().apply {
                attach(readerViewModel, conveyorViewModel)
            }
        }
    }
    
    private data class Attachment(
        val readerViewModel: ReaderViewModel,
        val conveyorViewModel: ConveyorBeltSystemViewModel
    )

    private val attachmentLock = Any()
    private var scope: CoroutineScope? = null
    private var isInitialized = false
    private var lastObservedWindow: Int = -1
    private var attachment: Attachment? = null

    @Synchronized
    private fun attach(
        readerViewModel: ReaderViewModel,
        conveyorViewModel: ConveyorBeltSystemViewModel
    ): ConveyorBeltIntegrationBridge {
        synchronized(attachmentLock) {
            check(attachment == null) {
                "ConveyorBeltIntegrationBridge is already attached"
            }
            attachment = Attachment(readerViewModel, conveyorViewModel)
        }
        return this
    }
    
    /**
     * Initialize the bridge when the lifecycle starts.
     */
    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)
        if (!isAttached()) {
            log("START_OBSERVING", "Bridge not attached - skipping")
            return
        }
        startObserving()
    }
    
    /**
     * Clean up when the lifecycle stops.
     */
    override fun onStop(owner: LifecycleOwner) {
        super.onStop(owner)
        stopObserving()
    }
    
    /**
     * Start observing the ReaderViewModel for window changes.
     */
    private fun startObserving() {
        if (scope != null) {
            log("START_OBSERVING", "Already observing - skipping")
            return
        }

        val attachment = synchronized(attachmentLock) { attachment } ?: run {
            log("START_OBSERVING", "Bridge not attached - skipping")
            return
        }
        val readerViewModel = attachment.readerViewModel
        val conveyorViewModel = attachment.conveyorViewModel
        
        log("START_OBSERVING", "Starting to observe ReaderViewModel")
        
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        
        // Observe window index changes
        scope?.launch {
            readerViewModel.currentWindowIndex.collectLatest { windowIndex ->
                onWindowIndexChanged(windowIndex)
            }
        }
        
        // Initialize the isolated system with current book info
        initializeIsolatedSystem()
    }
    
    /**
     * Stop observing and clean up.
     */
    private fun stopObserving() {
        log("STOP_OBSERVING", "Stopping observation")
        scope?.cancel()
        scope = null
        isInitialized = false
        lastObservedWindow = -1
    }
    
    /**
     * Initialize the isolated conveyor system with current book state.
     */
    private fun initializeIsolatedSystem() {
        val attachment = synchronized(attachmentLock) { attachment } ?: return
        val readerViewModel = attachment.readerViewModel
        val conveyorViewModel = attachment.conveyorViewModel
        val windowCount = readerViewModel.windowCount.value
        val currentWindow = readerViewModel.currentWindowIndex.value
        
        log("INIT_ISOLATED", buildString {
            appendLine("Initializing isolated system:")
            appendLine("  windowCount=$windowCount")
            appendLine("  currentWindow=$currentWindow")
            appendLine("  paginationMode=${readerViewModel.paginationMode}")
        })
        
        if (windowCount > 0) {
            conveyorViewModel.initialize(currentWindow, windowCount)
            isInitialized = true
            log("INIT_COMPLETE", "Isolated system initialized")
        } else {
            log("INIT_PENDING", "Window count is 0 - waiting for content to load")
        }
    }
    
    /**
     * Handle window index changes from ReaderViewModel.
     */
    private fun onWindowIndexChanged(windowIndex: Int) {
        val attachment = synchronized(attachmentLock) { attachment } ?: return
        val readerViewModel = attachment.readerViewModel
        val conveyorViewModel = attachment.conveyorViewModel
        // Skip if same window or not initialized
        if (windowIndex == lastObservedWindow) {
            return
        }
        
        val previousWindow = lastObservedWindow
        lastObservedWindow = windowIndex
        
        // If not yet initialized and window count is now available, initialize
        if (!isInitialized) {
            val windowCount = readerViewModel.windowCount.value
            if (windowCount > 0) {
                conveyorViewModel.initialize(windowIndex, windowCount)
                isInitialized = true
                log("LATE_INIT", "Late initialization with windowCount=$windowCount, currentWindow=$windowIndex")
            }
            return
        }
        
        log("WINDOW_CHANGE_OBSERVED", buildString {
            appendLine("Window change detected:")
            appendLine("  oldWindow=$previousWindow")
            appendLine("  newWindow=$windowIndex")
        })
        
        // Compare states before forwarding
        logStateComparison("BEFORE_FORWARD")
        
        // Forward to isolated system
        conveyorViewModel.onWindowEntered(windowIndex)
        
        // Compare states after forwarding
        logStateComparison("AFTER_FORWARD")
    }
    
    /**
     * Log a comparison of the old WindowBufferManager state vs the isolated system.
     * 
     * WindowBufferManager has been deprecated and removed, so this now only logs
     * the ConveyorBeltSystemViewModel state.
     */
    private fun logStateComparison(label: String) {
        val attachment = synchronized(attachmentLock) { attachment } ?: return
        val conveyorViewModel = attachment.conveyorViewModel
        // WindowBufferManager has been deprecated and removed
        val oldPhase = "DEPRECATED"
        val oldActive = "N/A"
        val oldBuffer = emptyList<Int>()
        val oldCenter = "N/A"
        
        val newPhase = conveyorViewModel.phase.value.name
        val newActive = conveyorViewModel.activeWindow.value
        val newBuffer = conveyorViewModel.buffer.value
        val newCenter = conveyorViewModel.getCenterWindow()
        
        log("STATE_COMPARISON_$label", buildString {
            appendLine("=== STATE COMPARISON ===")
            appendLine("[OLD WindowBufferManager]")
            appendLine("  phase=$oldPhase")
            appendLine("  activeWindow=${oldActive ?: "NOT_INITIALIZED"}")
            appendLine("  buffer=$oldBuffer")
            appendLine("  center=${oldCenter ?: "NOT_AVAILABLE"}")
            appendLine()
            appendLine("[NEW Isolated System]")
            appendLine("  phase=$newPhase")
            appendLine("  activeWindow=$newActive")
            appendLine("  buffer=$newBuffer")
            appendLine("  center=${newCenter ?: "NOT_AVAILABLE"}")
            appendLine()
            appendLine("[DIFFERENCES]")
            // WindowBufferManager has been deprecated - skip comparisons with old system
            appendLine("  WindowBufferManager: DEPRECATED")
            appendLine("  ConveyorBeltSystem: Active")
            appendLine("  Phase: $newPhase")
            appendLine("  Active: $newActive")
            appendLine("  Buffer: $newBuffer")
            appendLine("  Center: $newCenter")
            appendLine("========================")
        })
    }
    
    /**
     * Manually trigger a state comparison log.
     * Can be called from debug UI.
     */
    fun logCurrentStateComparison() {
        logStateComparison("MANUAL_CHECK")
    }
    
    /**
     * Check if the bridge is currently observing.
     */
    fun isObserving(): Boolean = scope != null

    private fun isAttached(): Boolean =
        synchronized(attachmentLock) { attachment != null }
    
    private fun log(event: String, message: String) {
        AppLogger.d(TAG, "$LOG_PREFIX [$event] $message")
    }
}
