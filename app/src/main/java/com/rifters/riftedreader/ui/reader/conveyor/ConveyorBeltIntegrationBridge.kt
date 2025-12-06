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
 * val bridge = ConveyorBeltIntegrationBridge(
 *     readerViewModel = viewModel,
 *     conveyorViewModel = conveyorViewModel
 * )
 * lifecycle.addObserver(bridge)
 * ```
 * 
 * @param readerViewModel The existing ReaderViewModel to observe (non-invasive)
 * @param conveyorViewModel The isolated conveyor system to forward events to
 */
class ConveyorBeltIntegrationBridge(
    private val readerViewModel: ReaderViewModel,
    private val conveyorViewModel: ConveyorBeltSystemViewModel
) : DefaultLifecycleObserver {
    
    companion object {
        private const val TAG = "ConveyorBridge"
        private const val LOG_PREFIX = "[CONVEYOR_BRIDGE]"
    }
    
    private var scope: CoroutineScope? = null
    private var isInitialized = false
    private var lastObservedWindow: Int = -1
    
    /**
     * Initialize the bridge when the lifecycle starts.
     */
    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)
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
     */
    private fun logStateComparison(label: String) {
        val wbm = readerViewModel.windowBufferManager
        
        // Use descriptive strings for unavailable values
        val oldPhase = wbm?.phase?.value?.name ?: "NOT_AVAILABLE"
        val oldActive = wbm?.getActiveWindowIndex()
        val oldBuffer = wbm?.getBufferedWindows() ?: emptyList()
        val oldCenter = wbm?.getCenterWindowIndex()
        
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
            if (oldPhase != newPhase) appendLine("  ❌ Phase: $oldPhase vs $newPhase")
            if (oldActive != newActive) appendLine("  ❌ Active: $oldActive vs $newActive")
            if (oldBuffer != newBuffer) appendLine("  ❌ Buffer: $oldBuffer vs $newBuffer")
            if (oldCenter != newCenter) appendLine("  ❌ Center: $oldCenter vs $newCenter")
            if (oldPhase == newPhase && oldActive == newActive && 
                oldBuffer == newBuffer && oldCenter == newCenter) {
                appendLine("  ✓ States match!")
            }
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
    
    private fun log(event: String, message: String) {
        AppLogger.d(TAG, "$LOG_PREFIX [$event] $message")
    }
}
