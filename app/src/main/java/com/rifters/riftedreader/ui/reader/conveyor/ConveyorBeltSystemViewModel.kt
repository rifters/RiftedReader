package com.rifters.riftedreader.ui.reader.conveyor

import androidx.lifecycle.ViewModel
import com.rifters.riftedreader.util.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque

class ConveyorBeltSystemViewModel : ViewModel() {
    
    companion object {
        private const val TAG = "ConveyorBeltSystemVM"
        private const val LOG_PREFIX = "[CONVEYOR_ISOLATED]"
        private const val BUFFER_SIZE = 5
        private const val CENTER_INDEX = 2
        private const val UNLOCK_WINDOW = 2
        private const val STEADY_TRIGGER_WINDOW = 3
    }
    
    private val _buffer = MutableStateFlow<List<Int>>((0..4).toList())
    val buffer: StateFlow<List<Int>> = _buffer.asStateFlow()
    
    private val _activeWindow = MutableStateFlow<Int>(0)
    val activeWindow: StateFlow<Int> = _activeWindow.asStateFlow()
    
    private val _phase = MutableStateFlow<ConveyorPhase>(ConveyorPhase.STARTUP)
    val phase: StateFlow<ConveyorPhase> = _phase.asStateFlow()
    
    private val _eventLog = MutableStateFlow<List<String>>(emptyList())
    val eventLog: StateFlow<List<String>> = _eventLog.asStateFlow()
    
    // Window count exposed as StateFlow for adapter integration
    private val _windowCount = MutableStateFlow<Int>(0)
    val windowCount: StateFlow<Int> = _windowCount.asStateFlow()
    
    // Initialization state for readiness checks
    private val _isInitialized = MutableStateFlow<Boolean>(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()
    
    private var shiftsUnlocked = false
    
    fun onWindowEntered(windowIndex: Int) {
        log("STATE", "onWindowEntered($windowIndex)")
        log("STATE", "Current: buffer=${_buffer.value}, activeWindow=${_activeWindow.value}, phase=${_phase.value}")
        log("STATE", "shiftsUnlocked=$shiftsUnlocked")
        
        when (_phase.value) {
            ConveyorPhase.STARTUP -> {
                handleStartupNavigation(windowIndex)
            }
            ConveyorPhase.STEADY -> {
                handleSteadyNavigation(windowIndex)
            }
        }
    }
    
    private fun handleStartupNavigation(windowIndex: Int) {
        log("STARTUP", "Navigating to window $windowIndex")
        
        // Check if window is in buffer
        if (windowIndex !in _buffer.value) {
            log("STARTUP", "Window $windowIndex not in buffer, ignoring")
            return
        }
        
        _activeWindow.value = windowIndex
        log("STARTUP", "activeWindow = $windowIndex")
        
        // Window 2 unlocks shifts
        if (windowIndex == UNLOCK_WINDOW) {
            shiftsUnlocked = true
            log("STARTUP", "*** SHIFTS UNLOCKED at window 2 ***")
        }
        
        // Window 3 triggers steady (only if shifts were unlocked)
        if (windowIndex == STEADY_TRIGGER_WINDOW && shiftsUnlocked) {
            transitionToSteady(windowIndex)
        }
    }
    
    private fun transitionToSteady(windowIndex: Int) {
        log("TRANSITION", "Moving to window $windowIndex triggers STEADY phase")
        
        // Shift buffer right to center on new window
        val currentBuffer = _buffer.value.toMutableList()
        
        // Calculate how many shifts needed
        val currentCenterWindow = currentBuffer[CENTER_INDEX]
        var shiftCount = windowIndex - currentCenterWindow
        
        log("TRANSITION", "Current center: $currentCenterWindow, target: $windowIndex, shifts needed: $shiftCount")
        
        while (shiftCount > 0) {
            currentBuffer.removeAt(0)
            currentBuffer.add(currentBuffer.last() + 1)
            shiftCount--
            log("SHIFT", "Shifted right: ${currentBuffer}, signal: remove ${currentBuffer[0]-1}, create ${currentBuffer.last()}")
        }
        
        _buffer.value = currentBuffer
        _activeWindow.value = windowIndex
        _phase.value = ConveyorPhase.STEADY
        
        log("TRANSITION", "*** PHASE TRANSITION: STARTUP → STEADY ***")
        log("TRANSITION", "New buffer: ${_buffer.value}, activeWindow: $windowIndex, center: ${_buffer.value[CENTER_INDEX]}")
    }
    
    private fun handleSteadyNavigation(windowIndex: Int) {
        log("STEADY", "Navigating to window $windowIndex")
        
        val currentBuffer = _buffer.value.toMutableList()
        val currentCenter = currentBuffer[CENTER_INDEX]
        
        // Check if navigating back to window 2 (revert condition)
        if (windowIndex == UNLOCK_WINDOW) {
            revertToStartup()
            return
        }
        
        // Calculate shift direction and amount
        val shiftDelta = windowIndex - currentCenter
        
        if (shiftDelta > 0) {
            // Navigate forward: shift right
            handleSteadyForward(windowIndex, currentBuffer, shiftDelta)
        } else if (shiftDelta < 0) {
            // Navigate backward: shift left
            handleSteadyBackward(windowIndex, currentBuffer, -shiftDelta)
        } else {
            log("STEADY", "Already at window $windowIndex (center)")
            _activeWindow.value = windowIndex
        }
    }
    
    private fun handleSteadyForward(windowIndex: Int, buffer: MutableList<Int>, shiftCount: Int) {
        log("STEADY_FORWARD", "Navigating to window $windowIndex, shifts: $shiftCount")
        
        repeat(shiftCount) { i ->
            val removedWindow = buffer.removeAt(0)
            val newWindow = buffer.last() + 1
            buffer.add(newWindow)
            log("SHIFT", "$i: ${buffer}, signal: remove $removedWindow, create $newWindow")
        }
        
        _buffer.value = buffer
        _activeWindow.value = windowIndex
        
        log("STEADY_FORWARD", "Final buffer: ${_buffer.value}, activeWindow: $windowIndex")
    }
    
    private fun handleSteadyBackward(windowIndex: Int, buffer: MutableList<Int>, shiftCount: Int) {
        log("STEADY_BACKWARD", "Navigating to window $windowIndex, shifts: $shiftCount")
        
        repeat(shiftCount) { i ->
            val removedWindow = buffer.removeAt(buffer.size - 1)
            val newWindow = buffer.first() - 1
            buffer.add(0, newWindow)
            log("SHIFT", "$i: ${buffer}, signal: remove $removedWindow, create $newWindow")
        }
        
        _buffer.value = buffer
        _activeWindow.value = windowIndex
        
        log("STEADY_BACKWARD", "Final buffer: ${_buffer.value}, activeWindow: $windowIndex")
    }
    
    private fun revertToStartup() {
        log("REVERT", "Hit boundary!  Navigating back to window 2")
        
        _buffer.value = (0..4).toList()
        _activeWindow.value = UNLOCK_WINDOW
        _phase.value = ConveyorPhase.STARTUP
        shiftsUnlocked = false
        
        log("REVERT", "*** PHASE TRANSITION: STEADY → STARTUP ***")
        log("REVERT", "Buffer reverted: ${_buffer.value}, activeWindow: $UNLOCK_WINDOW")
    }
    
    // Methods needed by ConveyorDebugActivity and ConveyorBeltIntegrationBridge
    fun initialize(startWindow: Int, totalWindowCount: Int) {
        log("INIT", "Initialize called: startWindow=$startWindow, totalWindowCount=$totalWindowCount")
        
        // Clamp start window to valid range
        val clampedStart = startWindow.coerceIn(0, (totalWindowCount - 1).coerceAtLeast(0))
        
        // Calculate buffer bounds
        val bufferEnd = (clampedStart + BUFFER_SIZE - 1).coerceAtMost(totalWindowCount - 1)
        val bufferStart = (bufferEnd - BUFFER_SIZE + 1).coerceAtLeast(0)
        
        // Initialize buffer from start window
        _buffer.value = (bufferStart..bufferEnd).toList()
        _activeWindow.value = clampedStart
        _phase.value = ConveyorPhase.STARTUP
        shiftsUnlocked = false
        
        // Set window count and mark as initialized
        _windowCount.value = totalWindowCount
        _isInitialized.value = true
        
        log("INIT", "Conveyor initialized: windowCount=$totalWindowCount, buffer=${_buffer.value}, isInitialized=true")
    }
    
    fun simulateNextWindow() {
        val current = _activeWindow.value
        val next = current + 1
        log("SIMULATE_NEXT", "next=$next")
        onWindowEntered(next)
    }
    
    fun simulatePreviousWindow() {
        val current = _activeWindow.value
        val prev = current - 1
        log("SIMULATE_PREV", "prev=$prev")
        if (prev >= 0) {
            onWindowEntered(prev)
        }
    }
    
    fun clearEventLog() {
        _eventLog.value = emptyList()
        log("CLEAR_LOG", "Event log cleared")
    }
    
    fun getCenterWindow(): Int? {
        val list = buffer.value
        return if (list.size > CENTER_INDEX) list[CENTER_INDEX] else null
    }
    
    private fun log(event: String, message: String) {
        val formattedMessage = "$LOG_PREFIX [$event] $message"
        AppLogger.d(TAG, formattedMessage)
        
        // Add to event log StateFlow
        val currentLog = _eventLog.value.toMutableList()
        currentLog.add(formattedMessage)
        _eventLog.value = currentLog
    }
}
