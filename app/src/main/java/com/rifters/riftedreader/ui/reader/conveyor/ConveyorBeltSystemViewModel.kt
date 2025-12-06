package com.rifters.riftedreader.ui.reader.conveyor

import androidx.lifecycle.ViewModel
import com.rifters.riftedreader.util.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque

/**
 * Isolated conveyor belt system ViewModel.
 * 
 * **This ViewModel runs completely parallel to the existing window management code.**
 * 
 * It has:
 * - Zero coupling to legacy streaming/window buffer code
 * - Independent phase state machine (STARTUP → STEADY transition)
 * - Independent window buffer (separate from WindowBufferManager)
 * - Comprehensive logging with `[CONVEYOR_ISOLATED]` prefix
 * 
 * ## Purpose
 * 
 * This isolated system serves as:
 * 1. **Proof of concept**: If this works correctly, the conveyor logic itself is sound
 * 2. **Debugging tool**: Side-by-side logs show exactly where old code interferes
 * 3. **Zero risk**: Doesn't modify existing code paths
 * 4. **Migration path**: Can gradually switch over once validated
 * 
 * ## Buffer Lifecycle
 * 
 * ### Phase 1: STARTUP
 * - Buffer initialized with 5 consecutive windows
 * - User starts at window 0 (buffer[0])
 * - System monitors for center window entry
 * 
 * ### Phase 2: STEADY
 * - Entered when user reaches buffer[CENTER_POS] (window at index 2 in buffer)
 * - Active window kept centered with 2 ahead, 2 behind
 * - Buffer shifting enabled
 * 
 * @see ConveyorPhase for phase definitions
 * @see ConveyorBeltIntegrationBridge for event routing
 */
class ConveyorBeltSystemViewModel : ViewModel() {
    
    companion object {
        private const val TAG = "ConveyorBeltSystemVM"
        private const val LOG_PREFIX = "[CONVEYOR_ISOLATED]"
        
        /** Number of windows in the buffer */
        const val BUFFER_SIZE = 5
        
        /** Center position in the buffer (0-indexed) */
        const val CENTER_POS = 2
    }
    
    // ========================================================================
    // Independent State - Completely separate from legacy code
    // ========================================================================
    
    /** Current phase of the isolated conveyor system */
    private val _phase = MutableStateFlow(ConveyorPhase.STARTUP)
    val phase: StateFlow<ConveyorPhase> = _phase.asStateFlow()
    
    /** Active window index in the isolated system */
    private val _activeWindow = MutableStateFlow(0)
    val activeWindow: StateFlow<Int> = _activeWindow.asStateFlow()
    
    /** Buffer contents (window indices) */
    private val _bufferContents = MutableStateFlow<List<Int>>(emptyList())
    val bufferContents: StateFlow<List<Int>> = _bufferContents.asStateFlow()
    
    /** Total windows available in the book */
    private val _totalWindows = MutableStateFlow(0)
    val totalWindows: StateFlow<Int> = _totalWindows.asStateFlow()
    
    /** Log of all events for debugging */
    private val _eventLog = MutableStateFlow<List<String>>(emptyList())
    val eventLog: StateFlow<List<String>> = _eventLog.asStateFlow()
    
    /** Flag indicating if STARTUP → STEADY transition has occurred */
    private var hasTransitionedToSteady = false
    
    /** Internal buffer storage */
    private val buffer = ArrayDeque<Int>(BUFFER_SIZE)
    
    // ========================================================================
    // Initialization
    // ========================================================================
    
    /**
     * Initialize the isolated conveyor system.
     * 
     * Creates a buffer of 5 consecutive windows starting from the given window.
     * Resets all state to clean values.
     * 
     * @param startWindow The global window index to start from
     * @param totalWindowCount Total number of windows in the book
     */
    fun initialize(startWindow: Int, totalWindowCount: Int) {
        log("INIT_START", "Initializing: startWindow=$startWindow, totalWindows=$totalWindowCount")
        
        // Reset state
        buffer.clear()
        hasTransitionedToSteady = false
        _phase.value = ConveyorPhase.STARTUP
        _totalWindows.value = totalWindowCount
        
        // Handle edge cases
        if (totalWindowCount <= 0) {
            log("INIT_EMPTY", "No windows available - empty book")
            _bufferContents.value = emptyList()
            _activeWindow.value = 0
            return
        }
        
        // Calculate valid buffer range
        val maxValidWindow = totalWindowCount - 1
        val clampedStart = startWindow.coerceIn(0, maxValidWindow)
        
        // Calculate end window, adjusting start if near end to maintain buffer size
        val endWindow = (clampedStart + BUFFER_SIZE - 1).coerceAtMost(maxValidWindow)
        val actualStart = (endWindow - BUFFER_SIZE + 1).coerceAtLeast(0)
        
        log("INIT_RANGE", "Buffer range: actualStart=$actualStart, endWindow=$endWindow")
        
        // Fill buffer
        for (windowIndex in actualStart..endWindow) {
            buffer.addLast(windowIndex)
        }
        
        // Set active window to first in buffer
        _activeWindow.value = if (buffer.isNotEmpty()) buffer.first else 0
        _bufferContents.value = buffer.toList()
        
        log("INIT_COMPLETE", buildString {
            appendLine("Initialization complete:")
            appendLine("  buffer=${buffer.toList()}")
            appendLine("  activeWindow=${_activeWindow.value}")
            appendLine("  centerWindow=${getCenterWindow()}")
            appendLine("  phase=${_phase.value}")
        })
    }
    
    // ========================================================================
    // Window Entry - Core navigation handling
    // ========================================================================
    
    /**
     * Called when the user enters/views a window.
     * 
     * This is the main entry point for navigation events. It:
     * 1. Updates the active window
     * 2. Checks for phase transition (STARTUP → STEADY)
     * 3. Logs comprehensive state for debugging
     * 
     * @param globalWindowIndex The global window index the user entered
     */
    fun onWindowEntered(globalWindowIndex: Int) {
        val previousActive = _activeWindow.value
        val previousPhase = _phase.value
        
        log("WINDOW_ENTER_START", buildString {
            appendLine("Window entry event:")
            appendLine("  entered=$globalWindowIndex")
            appendLine("  previousActive=$previousActive")
            appendLine("  currentPhase=$previousPhase")
            appendLine("  buffer=${buffer.toList()}")
        })
        
        // Update active window
        _activeWindow.value = globalWindowIndex
        
        // Check for phase transition
        if (_phase.value == ConveyorPhase.STARTUP && !hasTransitionedToSteady) {
            val centerWindow = getCenterWindow()
            
            log("PHASE_CHECK", buildString {
                appendLine("Checking phase transition:")
                appendLine("  currentWindow=$globalWindowIndex")
                appendLine("  centerWindow=$centerWindow")
                appendLine("  match=${globalWindowIndex == centerWindow}")
            })
            
            if (centerWindow != null && globalWindowIndex == centerWindow) {
                // Transition to STEADY
                hasTransitionedToSteady = true
                _phase.value = ConveyorPhase.STEADY
                
                log("PHASE_TRANSITION", buildString {
                    appendLine("*** PHASE TRANSITION: STARTUP → STEADY ***")
                    appendLine("  triggeredAt=window $globalWindowIndex")
                    appendLine("  centerWindow=$centerWindow")
                    appendLine("  buffer=${buffer.toList()}")
                })
            }
        }
        
        log("WINDOW_ENTER_COMPLETE", buildString {
            appendLine("Window entry complete:")
            appendLine("  activeWindow=${_activeWindow.value}")
            appendLine("  phase=${_phase.value}")
            appendLine("  transitioned=${previousPhase != _phase.value}")
        })
    }
    
    // ========================================================================
    // Buffer Shifting
    // ========================================================================
    
    /**
     * Shift the buffer forward.
     * 
     * Only allowed in STEADY phase. Drops leftmost window and appends new rightmost.
     * 
     * @return true if shift was performed, false if blocked (wrong phase or at boundary)
     */
    fun shiftForward(): Boolean {
        log("SHIFT_FORWARD_START", "Attempting forward shift, phase=${_phase.value}")
        
        if (_phase.value != ConveyorPhase.STEADY) {
            log("SHIFT_FORWARD_BLOCKED", "Not in STEADY phase - blocking shift")
            return false
        }
        
        if (buffer.isEmpty()) {
            log("SHIFT_FORWARD_BLOCKED", "Buffer empty - blocking shift")
            return false
        }
        
        val lastWindow = buffer.last
        val nextWindow = lastWindow + 1
        val total = _totalWindows.value
        
        if (nextWindow >= total) {
            log("SHIFT_FORWARD_BLOCKED", "At end boundary: lastWindow=$lastWindow, total=$total")
            return false
        }
        
        // Perform shift
        val droppedWindow = buffer.removeFirst()
        buffer.addLast(nextWindow)
        _bufferContents.value = buffer.toList()
        
        log("SHIFT_FORWARD_SUCCESS", buildString {
            appendLine("Forward shift complete:")
            appendLine("  dropped=$droppedWindow")
            appendLine("  appended=$nextWindow")
            appendLine("  newBuffer=${buffer.toList()}")
        })
        
        return true
    }
    
    /**
     * Shift the buffer backward.
     * 
     * Only allowed in STEADY phase. Drops rightmost window and prepends new leftmost.
     * 
     * @return true if shift was performed, false if blocked (wrong phase or at boundary)
     */
    fun shiftBackward(): Boolean {
        log("SHIFT_BACKWARD_START", "Attempting backward shift, phase=${_phase.value}")
        
        if (_phase.value != ConveyorPhase.STEADY) {
            log("SHIFT_BACKWARD_BLOCKED", "Not in STEADY phase - blocking shift")
            return false
        }
        
        if (buffer.isEmpty()) {
            log("SHIFT_BACKWARD_BLOCKED", "Buffer empty - blocking shift")
            return false
        }
        
        val firstWindow = buffer.first
        val prevWindow = firstWindow - 1
        
        if (prevWindow < 0) {
            log("SHIFT_BACKWARD_BLOCKED", "At start boundary: firstWindow=$firstWindow")
            return false
        }
        
        // Perform shift
        val droppedWindow = buffer.removeLast()
        buffer.addFirst(prevWindow)
        _bufferContents.value = buffer.toList()
        
        log("SHIFT_BACKWARD_SUCCESS", buildString {
            appendLine("Backward shift complete:")
            appendLine("  dropped=$droppedWindow")
            appendLine("  prepended=$prevWindow")
            appendLine("  newBuffer=${buffer.toList()}")
        })
        
        return true
    }
    
    // ========================================================================
    // Query Methods
    // ========================================================================
    
    /**
     * Get the window at the center position of the buffer.
     * 
     * @return The center window index, or null if buffer is too small
     */
    fun getCenterWindow(): Int? {
        val list = buffer.toList()
        return if (list.size > CENTER_POS) list[CENTER_POS] else null
    }
    
    /**
     * Check if a window is currently in the buffer.
     * 
     * @param windowIndex The window index to check
     * @return true if the window is in the buffer
     */
    fun isWindowInBuffer(windowIndex: Int): Boolean {
        return buffer.contains(windowIndex)
    }
    
    /**
     * Get current buffer contents as a list.
     * 
     * @return List of window indices currently in the buffer
     */
    fun getBufferAsList(): List<Int> = buffer.toList()
    
    /**
     * Get a comprehensive debug state string.
     * 
     * @return Multi-line debug info about the current state
     */
    fun getDebugState(): String = buildString {
        appendLine("=== CONVEYOR ISOLATED STATE ===")
        appendLine("Phase: ${_phase.value}")
        appendLine("Active Window: ${_activeWindow.value}")
        appendLine("Buffer: ${buffer.toList()}")
        appendLine("Center Window: ${getCenterWindow()}")
        appendLine("Total Windows: ${_totalWindows.value}")
        appendLine("Has Transitioned: $hasTransitionedToSteady")
        appendLine("================================")
    }
    
    /**
     * Clear the event log.
     */
    fun clearEventLog() {
        _eventLog.value = emptyList()
        log("LOG_CLEARED", "Event log cleared")
    }
    
    // ========================================================================
    // Simulation Methods (for Debug Activity)
    // ========================================================================
    
    /**
     * Simulate navigation to the next window.
     * Used by ConveyorDebugActivity for testing.
     */
    fun simulateNextWindow() {
        val current = _activeWindow.value
        val next = current + 1
        val total = _totalWindows.value
        
        log("SIMULATE_NEXT", "Simulating next: current=$current, next=$next, total=$total")
        
        if (next < total) {
            onWindowEntered(next)
            
            // Auto-shift if in steady state and approaching edge
            if (_phase.value == ConveyorPhase.STEADY) {
                val bufferList = buffer.toList()
                val positionInBuffer = bufferList.indexOf(next)
                // Shift forward if we're past center
                if (positionInBuffer >= CENTER_POS && positionInBuffer < bufferList.size - 1) {
                    // Only shift if we can maintain centering
                    if (bufferList.last() + 1 < total) {
                        shiftForward()
                    }
                }
            }
        } else {
            log("SIMULATE_NEXT_BLOCKED", "Already at last window")
        }
    }
    
    /**
     * Simulate navigation to the previous window.
     * Used by ConveyorDebugActivity for testing.
     */
    fun simulatePreviousWindow() {
        val current = _activeWindow.value
        val prev = current - 1
        
        log("SIMULATE_PREV", "Simulating prev: current=$current, prev=$prev")
        
        if (prev >= 0) {
            onWindowEntered(prev)
            
            // Auto-shift if in steady state
            if (_phase.value == ConveyorPhase.STEADY) {
                val bufferList = buffer.toList()
                val positionInBuffer = bufferList.indexOf(prev)
                // Shift backward if we're before center
                if (positionInBuffer <= CENTER_POS && positionInBuffer > 0) {
                    // Only shift if we can maintain centering
                    if (bufferList.first() - 1 >= 0) {
                        shiftBackward()
                    }
                }
            }
        } else {
            log("SIMULATE_PREV_BLOCKED", "Already at first window")
        }
    }
    
    // ========================================================================
    // Logging
    // ========================================================================
    
    private fun log(event: String, message: String) {
        val formattedMessage = "$LOG_PREFIX [$event] $message"
        
        // Log to AppLogger
        AppLogger.d(TAG, formattedMessage)
        
        // Also add to event log for UI display
        val timestamp = System.currentTimeMillis()
        val logEntry = "[$timestamp] $event: $message"
        _eventLog.value = _eventLog.value + logEntry
    }
}
