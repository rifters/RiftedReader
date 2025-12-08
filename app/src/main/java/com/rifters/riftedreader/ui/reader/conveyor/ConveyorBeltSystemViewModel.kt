package com.rifters.riftedreader.ui.reader.conveyor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rifters.riftedreader.domain.pagination.ContinuousPaginator
import com.rifters.riftedreader.domain.pagination.ContinuousPaginatorWindowHtmlProvider
import com.rifters.riftedreader.domain.pagination.SlidingWindowManager
import com.rifters.riftedreader.util.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    
    // HTML loading dependencies (set via setHtmlLoadingDependencies)
    private var continuousPaginator: ContinuousPaginator? = null
    private var slidingWindowManager: SlidingWindowManager? = null
    private var bookId: String? = null
    
    // HTML cache for loaded windows
    private val htmlCache = mutableMapOf<Int, String>()
    
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
    
    /**
     * Set dependencies needed for HTML loading.
     * Must be called before window HTML loading can work.
     */
    fun setHtmlLoadingDependencies(
        paginator: ContinuousPaginator,
        windowManager: SlidingWindowManager,
        bookId: String
    ) {
        this.continuousPaginator = paginator
        this.slidingWindowManager = windowManager
        this.bookId = bookId
        log("INIT", "HTML loading dependencies set: bookId=$bookId")
    }
    
    /**
     * Get cached HTML for a window, if available.
     */
    fun getCachedWindowHtml(windowIndex: Int): String? {
        return htmlCache[windowIndex]
    }
    
    /**
     * Load HTML for a window and cache it.
     * This is called when a window enters the buffer or becomes visible.
     */
    private fun loadWindowHtml(windowIndex: Int) {
        val paginator = continuousPaginator
        val windowManager = slidingWindowManager
        val bookId = bookId
        
        if (paginator == null || windowManager == null || bookId == null) {
            log("HTML_LOAD", "Cannot load window $windowIndex: dependencies not set")
            return
        }
        
        // Skip if already cached
        if (htmlCache.containsKey(windowIndex)) {
            log("HTML_LOAD", "Window $windowIndex already cached")
            return
        }
        
        log("HTML_LOAD", "Loading HTML for window $windowIndex")
        
        viewModelScope.launch {
            try {
                val provider = ContinuousPaginatorWindowHtmlProvider(paginator, windowManager)
                val html = provider.getWindowHtml(bookId, windowIndex)
                
                if (html != null) {
                    htmlCache[windowIndex] = html
                    log("HTML_LOAD", "Window $windowIndex loaded: ${html.length} chars")
                } else {
                    log("HTML_LOAD", "Window $windowIndex: HTML provider returned null")
                }
            } catch (e: Exception) {
                log("HTML_LOAD", "Failed to load window $windowIndex: ${e.message}")
                AppLogger.e(TAG, "Error loading window $windowIndex", e)
            }
        }
    }
    
    /**
     * Preload HTML for multiple windows asynchronously.
     * This is called after buffer shifts to prepare upcoming windows.
     */
    private fun preloadWindows(windowIndices: List<Int>) {
        val paginator = continuousPaginator
        val windowManager = slidingWindowManager
        val bookId = bookId
        
        if (paginator == null || windowManager == null || bookId == null) {
            log("PRELOAD", "Cannot preload windows: dependencies not set")
            return
        }
        
        val toPreload = windowIndices.filter { !htmlCache.containsKey(it) }
        if (toPreload.isEmpty()) {
            log("PRELOAD", "All requested windows already cached")
            return
        }
        
        log("PRELOAD", "Preloading ${toPreload.size} windows: $toPreload")
        
        viewModelScope.launch {
            toPreload.forEach { windowIndex ->
                try {
                    val provider = ContinuousPaginatorWindowHtmlProvider(paginator, windowManager)
                    val html = provider.getWindowHtml(bookId, windowIndex)
                    
                    if (html != null) {
                        htmlCache[windowIndex] = html
                        log("PRELOAD", "Window $windowIndex preloaded: ${html.length} chars")
                    } else {
                        log("PRELOAD", "Window $windowIndex: HTML provider returned null")
                    }
                } catch (e: Exception) {
                    log("PRELOAD", "Failed to preload window $windowIndex: ${e.message}")
                    AppLogger.e(TAG, "Error preloading window $windowIndex", e)
                }
            }
        }
    }
    
    fun onWindowEntered(windowIndex: Int) {
        log("STATE", "onWindowEntered($windowIndex)")
        log("STATE", "Current: buffer=${_buffer.value}, activeWindow=${_activeWindow.value}, phase=${_phase.value}")
        log("STATE", "shiftsUnlocked=$shiftsUnlocked")
        
        // Load HTML for the window that was just entered
        loadWindowHtml(windowIndex)
        
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
        
        val windowsToRemove = mutableListOf<Int>()
        val windowsToAdd = mutableListOf<Int>()
        
        while (shiftCount > 0) {
            val removedWindow = currentBuffer.removeAt(0)
            val newWindow = currentBuffer.last() + 1
            currentBuffer.add(newWindow)
            windowsToRemove.add(removedWindow)
            windowsToAdd.add(newWindow)
            shiftCount--
            log("SHIFT", "Shifted right: ${currentBuffer}, signal: remove $removedWindow, create $newWindow")
        }
        
        _buffer.value = currentBuffer
        _activeWindow.value = windowIndex
        _phase.value = ConveyorPhase.STEADY
        
        log("TRANSITION", "*** PHASE TRANSITION: STARTUP → STEADY ***")
        log("TRANSITION", "New buffer: ${_buffer.value}, activeWindow: $windowIndex, center: ${_buffer.value[CENTER_INDEX]}")
        
        // Execute shift: drop old windows from cache, preload new windows
        executeShift(windowsToRemove, windowsToAdd)
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
        
        val windowsToRemove = mutableListOf<Int>()
        val windowsToAdd = mutableListOf<Int>()
        
        repeat(shiftCount) { i ->
            val removedWindow = buffer.removeAt(0)
            val newWindow = buffer.last() + 1
            buffer.add(newWindow)
            windowsToRemove.add(removedWindow)
            windowsToAdd.add(newWindow)
            log("SHIFT", "$i: ${buffer}, signal: remove $removedWindow, create $newWindow")
        }
        
        _buffer.value = buffer
        _activeWindow.value = windowIndex
        
        log("STEADY_FORWARD", "Final buffer: ${_buffer.value}, activeWindow: $windowIndex")
        
        // Execute shift: drop old windows from cache, preload new windows
        executeShift(windowsToRemove, windowsToAdd)
    }
    
    private fun handleSteadyBackward(windowIndex: Int, buffer: MutableList<Int>, shiftCount: Int) {
        log("STEADY_BACKWARD", "Navigating to window $windowIndex, shifts: $shiftCount")
        
        val windowsToRemove = mutableListOf<Int>()
        val windowsToAdd = mutableListOf<Int>()
        
        repeat(shiftCount) { i ->
            val removedWindow = buffer.removeAt(buffer.size - 1)
            val newWindow = buffer.first() - 1
            buffer.add(0, newWindow)
            windowsToRemove.add(removedWindow)
            windowsToAdd.add(newWindow)
            log("SHIFT", "$i: ${buffer}, signal: remove $removedWindow, create $newWindow")
        }
        
        _buffer.value = buffer
        _activeWindow.value = windowIndex
        
        log("STEADY_BACKWARD", "Final buffer: ${_buffer.value}, activeWindow: $windowIndex")
        
        // Execute shift: drop old windows from cache, preload new windows
        executeShift(windowsToRemove, windowsToAdd)
    }
    
    /**
     * Execute a buffer shift by removing old windows from cache and preloading new windows.
     */
    private fun executeShift(windowsToRemove: List<Int>, windowsToAdd: List<Int>) {
        // Remove old windows from cache
        windowsToRemove.forEach { windowIndex ->
            if (htmlCache.remove(windowIndex) != null) {
                log("SHIFT_EXEC", "Dropped window $windowIndex from cache")
            }
        }
        
        // Preload new windows
        if (windowsToAdd.isNotEmpty()) {
            preloadWindows(windowsToAdd)
        }
    }
    
    private fun revertToStartup() {
        log("REVERT", "Hit boundary!  Navigating back to window 2")
        
        _buffer.value = (0..4).toList()
        _activeWindow.value = UNLOCK_WINDOW
        _phase.value = ConveyorPhase.STARTUP
        shiftsUnlocked = false
        
        log("REVERT", "*** PHASE TRANSITION: STEADY → STARTUP ***")
        log("REVERT", "Buffer reverted: ${_buffer.value}, activeWindow: $UNLOCK_WINDOW")
        
        // Clear HTML cache on revert since we're going back to initial windows
        htmlCache.clear()
        log("REVERT", "HTML cache cleared")
        
        // Preload initial windows
        preloadWindows(_buffer.value)
    }
    
    // Methods needed by ConveyorDebugActivity and ConveyorBeltIntegrationBridge
    fun initialize(startWindow: Int, totalWindowCount: Int) {
        log("INIT", "Initialize called: startWindow=$startWindow, totalWindowCount=$totalWindowCount")
        
        // Clamp start window to valid range
        val clampedStart = startWindow.coerceIn(0, (totalWindowCount - 1).coerceAtLeast(0))
        
        // Calculate buffer bounds - try to center the buffer around the start window
        // This ensures we have windows both before and after the start position when possible
        val centerOffset = BUFFER_SIZE / 2
        var bufferStart = (clampedStart - centerOffset).coerceAtLeast(0)
        var bufferEnd = (bufferStart + BUFFER_SIZE - 1).coerceAtMost(totalWindowCount - 1)
        
        // Adjust bufferStart if we hit the end boundary and have room to shift left
        if (bufferEnd - bufferStart + 1 < BUFFER_SIZE && totalWindowCount >= BUFFER_SIZE) {
            bufferStart = (bufferEnd - BUFFER_SIZE + 1).coerceAtLeast(0)
        }
        
        // Initialize buffer from start window
        _buffer.value = (bufferStart..bufferEnd).toList()
        _activeWindow.value = clampedStart
        _phase.value = ConveyorPhase.STARTUP
        shiftsUnlocked = false
        
        // Set window count and mark as initialized
        _windowCount.value = totalWindowCount
        _isInitialized.value = true
        
        // Clear any existing HTML cache
        htmlCache.clear()
        
        log("INIT", "Conveyor initialized: windowCount=$totalWindowCount, buffer=${_buffer.value}, isInitialized=true")
        
        // Preload initial buffer windows
        preloadWindows(_buffer.value)
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
