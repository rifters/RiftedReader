package com.rifters.riftedreader.conveyor

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import android.util.Log

enum class ConveyorPhase {
    STARTUP,
    STEADY
}

class ConveyorBeltSystemViewModel : ViewModel() {
    
    companion object {
        private const val TAG = "CONVEYOR_ISOLATED"
        private const val BUFFER_SIZE = 5
        private const val CENTER_INDEX = 2
        private const val UNLOCK_WINDOW = 2
        private const val STEADY_TRIGGER_WINDOW = 3
    }
    
    private val buffer = MutableStateFlow<List<Int>>((0..4).toList())
    private val activeWindow = MutableStateFlow<Int>(0)
    private val phase = MutableStateFlow<ConveyorPhase>(ConveyorPhase.STARTUP)
    
    private var shiftsUnlocked = false
    
    val bufferState: StateFlow<List<Int>> = buffer
    val activeWindowState: StateFlow<Int> = activeWindow
    val phaseState: StateFlow<ConveyorPhase> = phase
    
    fun onWindowEntered(windowIndex: Int) {
        Log.d(TAG,"[STATE] onWindowEntered($windowIndex)")
        Log.d(TAG,"[STATE] Current: buffer=${buffer.value}, activeWindow=${activeWindow.value}, phase=${phase.value}")
        Log.d(TAG,"[STATE] shiftsUnlocked=$shiftsUnlocked")
        
        when (phase.value) {
            ConveyorPhase.STARTUP -> {
                handleStartupNavigation(windowIndex)
            }
            ConveyorPhase.STEADY -> {
                handleSteadyNavigation(windowIndex)
            }
        }
    }
    
    private fun handleStartupNavigation(windowIndex: Int) {
        Log.d(TAG,"[STARTUP] Navigating to window $windowIndex")
        
        // Check if window is in buffer
        if (windowIndex !in buffer.value) {
            Log.d(TAG,"[STARTUP] Window $windowIndex not in buffer, ignoring")
            return
        }
        
        activeWindow.value = windowIndex
        Log.d(TAG,"[STARTUP] activeWindow = $windowIndex")
        
        // Window 2 unlocks shifts
        if (windowIndex == UNLOCK_WINDOW) {
            shiftsUnlocked = true
            Log.d(TAG,"[STARTUP] *** SHIFTS UNLOCKED at window 2 ***")
        }
        
        // Window 3 triggers steady (only if shifts were unlocked)
        if (windowIndex == STEADY_TRIGGER_WINDOW && shiftsUnlocked) {
            transitionToSteady(windowIndex)
        }
    }
    
    private fun transitionToSteady(windowIndex: Int) {
        Log.d(TAG,"[TRANSITION] Moving to window $windowIndex triggers STEADY phase")
        
        // Shift buffer right to center on new window
        val currentBuffer = buffer.value. toMutableList()
        
        // Calculate how many shifts needed
        val currentCenterWindow = currentBuffer[CENTER_INDEX]
        var shiftCount = windowIndex - currentCenterWindow
        
        Log.d(TAG,"[TRANSITION] Current center: $currentCenterWindow, target: $windowIndex, shifts needed: $shiftCount")
        
        while (shiftCount > 0) {
            currentBuffer.removeAt(0)
            currentBuffer.add(currentBuffer.last() + 1)
            shiftCount--
            Log.d(TAG,"[SHIFT] Shifted right: ${currentBuffer}, signal: remove ${currentBuffer[0]-1}, create ${currentBuffer. last()}")
        }
        
        buffer.value = currentBuffer
        activeWindow.value = windowIndex
        phase.value = ConveyorPhase.STEADY
        
        Log.d(TAG,"[TRANSITION] *** PHASE TRANSITION: STARTUP → STEADY ***")
        Log.d(TAG,"[TRANSITION] New buffer: ${buffer.value}, activeWindow: $windowIndex, center: ${buffer. value[CENTER_INDEX]}")
    }
    
    private fun handleSteadyNavigation(windowIndex: Int) {
        Log.d(TAG,"[STEADY] Navigating to window $windowIndex")
        
        val currentBuffer = buffer.value.toMutableList()
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
            Log.d(TAG,"[STEADY] Already at window $windowIndex (center)")
            activeWindow.value = windowIndex
        }
    }
    
    private fun handleSteadyForward(windowIndex: Int, buffer: MutableList<Int>, shiftCount: Int) {
        Log.d(TAG,"[STEADY_FORWARD] Navigating to window $windowIndex, shifts: $shiftCount")
        
        repeat(shiftCount) { i ->
            val removedWindow = buffer.removeAt(0)
            val newWindow = buffer.last() + 1
            buffer.add(newWindow)
            Log.d(TAG,"[SHIFT] $i: ${buffer}, signal: remove $removedWindow, create $newWindow")
        }
        
        this.buffer.value = buffer
        activeWindow.value = windowIndex
        
        Log.d(TAG,"[STEADY_FORWARD] Final buffer: ${this.buffer.value}, activeWindow: $windowIndex")
    }
    
    private fun handleSteadyBackward(windowIndex: Int, buffer: MutableList<Int>, shiftCount: Int) {
        Log.d(TAG,"[STEADY_BACKWARD] Navigating to window $windowIndex, shifts: $shiftCount")
        
        repeat(shiftCount) { i ->
            val removedWindow = buffer.removeAt(buffer.size - 1)
            val newWindow = buffer.first() - 1
            buffer.add(0, newWindow)
            Log.d(TAG,"[SHIFT] $i: ${buffer}, signal: remove $removedWindow, create $newWindow")
        }
        
        this.buffer.value = buffer
        activeWindow.value = windowIndex
        
        Log.d(TAG,"[STEADY_BACKWARD] Final buffer: ${this. buffer.value}, activeWindow: $windowIndex")
    }
    
    private fun revertToStartup() {
        Log.d(TAG,"[REVERT] Hit boundary!  Navigating back to window 2")
        
        buffer.value = (0..4).toList()
        activeWindow.value = UNLOCK_WINDOW
        phase.value = ConveyorPhase.STARTUP
        shiftsUnlocked = false
        
        Log.d(TAG,"[REVERT] *** PHASE TRANSITION: STEADY → STARTUP ***")
        Log.d(TAG,"[REVERT] Buffer reverted: ${buffer.value}, activeWindow: $UNLOCK_WINDOW")
    }
    
    fun getWindowMakerSignals(): String {
        return "Remove: ${buffer.value. first()}, Create: ${buffer.value.last()}"
    }
}
