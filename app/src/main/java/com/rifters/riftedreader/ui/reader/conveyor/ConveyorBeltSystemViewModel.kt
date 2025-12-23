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
    
    // Reference to adapter for triggering item refreshes after buffer shifts
    private var pagerAdapter: com.rifters.riftedreader.ui.reader.ReaderPagerAdapter? = null
    
    // Callback to notify ReaderViewModel when to recenter the view after buffer shift
    // Called to reset currentWindowIndex back to CENTER_BUFFER so RecyclerView scrolls back to center
    private var onBufferShiftedCallback: ((newCenterWindow: Int) -> Unit)? = null
    
    // Track the highest logical window index we've created (for calculating next windows during shifts)
    // This allows us to properly wrap indices in circular buffer
    private var maxLogicalWindowCreated: Int = 4
    
    // Track the lowest logical window index we've created (for handling backward shifts)
    private var minLogicalWindowCreated: Int = 0
    
    private val _buffer = MutableStateFlow<List<Int>>((0..4).toList())
    val buffer: StateFlow<List<Int>> = _buffer.asStateFlow()
    
    private val _activeWindow = MutableStateFlow<Int>(0)
    val activeWindow: StateFlow<Int> = _activeWindow.asStateFlow()
    
    data class PendingShift(
        val buffer: MutableList<Int>,
        val windowIndex: Int,
        val direction: String  // "forward" or "backward"
    )
    
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
     * Set the pager adapter for triggering UI updates during buffer shifts.
     */
    fun setPagerAdapter(adapter: com.rifters.riftedreader.ui.reader.ReaderPagerAdapter) {
        this.pagerAdapter = adapter
        log("INIT", "Pager adapter set for UI refresh notifications")
    }
    
    /**
     * Set callback to update fragment with new window after buffer shift.
     * Called with the new CENTER buffer window to display.
     */
    fun setOnBufferShiftedCallback(callback: (newCenterWindow: Int) -> Unit) {
        this.onBufferShiftedCallback = callback
        log("INIT", "Buffer shifted callback set")
    }
    
    /**
     * Apply a buffer shift - update buffer, update active window, and refresh adapter.
     * Can be called either immediately or deferred until content loads.
     */
    fun applyBufferShift(shift: PendingShift) {
        com.rifters.riftedreader.util.AppLogger.d("ConveyorBeltSystemViewModel",
            "[BUFFER_SHIFT_START] Applying buffer shift: direction=${shift.direction}, newBuffer=${shift.buffer}"
        )
        
        log("BUFFER_SHIFT", "Applying buffer shift: ${shift.direction}, buffer=${shift.buffer}")
        
        // Update buffer and active window
        _buffer.value = shift.buffer
        _activeWindow.value = shift.windowIndex
        
        com.rifters.riftedreader.util.AppLogger.d("ConveyorBeltSystemViewModel",
            "[BUFFER_SHIFT_UPDATE] Buffer updated: activeWindow=${shift.windowIndex}, buffer=${shift.buffer}"
        )
        
        // Now trigger the adapter refresh
        pagerAdapter?.invalidatePositionDueToBufferShift(CENTER_INDEX)
        
        com.rifters.riftedreader.util.AppLogger.d("ConveyorBeltSystemViewModel",
            "[BUFFER_SHIFT_ADAPTER] Adapter invalidated at CENTER_INDEX=$CENTER_INDEX"
        )
        
        log("BUFFER_SHIFT", "Buffer shift applied and adapter notified")
        
        com.rifters.riftedreader.util.AppLogger.d("ConveyorBeltSystemViewModel",
            "[BUFFER_SHIFT_COMPLETE] Buffer shift completed successfully"
        )
    }
    
    /**
     * Get cached HTML for a window, if available.
     */
    fun getCachedWindowHtml(windowIndex: Int): String? {
        return htmlCache[windowIndex]
    }
    
    /**
     * Load HTML for a window and cache it synchronously.
     * 
     * This is a suspend function used during buffer shifts to load HTML for newly added windows.
     * Made suspend to ensure the loading operation completes properly within the coroutine context.
     * This fixes the issue where buffer shifts would update state but not generate HTML.
     */
    private suspend fun loadWindowHtmlSync(windowIndex: Int) {
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
        
        log("HTML_LOAD", "Loading HTML for window $windowIndex (SYNC)")
        
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
    
    /**
     * Load HTML for a window asynchronously (for background preloading).
     * Use loadWindowHtmlSync for critical path loading during shifts.
     */
    private fun loadWindowHtmlAsync(windowIndex: Int) {
        viewModelScope.launch {
            loadWindowHtmlSync(windowIndex)
        }
    }
    
    /**
     * Preload HTML for multiple windows asynchronously.
     * This is called after buffer shifts to prepare upcoming windows.
     */
    private fun preloadWindowsAsync(windowIndices: List<Int>) {
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
        
        log("PRELOAD", "Preloading ${toPreload.size} windows: $toPreload (ASYNC)")
        
        viewModelScope.launch {
            toPreload.forEach { windowIndex ->
                loadWindowHtmlSync(windowIndex)
            }
        }
    }
    
    /**
     * Load HTML for multiple windows synchronously.
     * This is the critical path for buffer shifts - ensures HTML is ready before windows are visible.
     */
    private suspend fun loadWindowsSync(windowIndices: List<Int>) {
        val paginator = continuousPaginator
        val windowManager = slidingWindowManager
        val bookId = bookId
        
        if (paginator == null || windowManager == null || bookId == null) {
            log("LOAD_SYNC", "Cannot load windows: dependencies not set")
            return
        }
        
        val toLoad = windowIndices.filter { !htmlCache.containsKey(it) }
        if (toLoad.isEmpty()) {
            log("LOAD_SYNC", "All requested windows already cached")
            return
        }
        
        log("LOAD_SYNC", "Loading ${toLoad.size} windows SYNCHRONOUSLY: $toLoad")
        
        toLoad.forEach { windowIndex ->
            loadWindowHtmlSync(windowIndex)
        }
        
        log("LOAD_SYNC", "Synchronous load complete for windows: $toLoad")
    }
    
    fun onWindowEntered(windowIndex: Int) {
        log("STATE", "onWindowEntered($windowIndex)")
        log("STATE", "Current: buffer=${_buffer.value}, activeWindow=${_activeWindow.value}, phase=${_phase.value}")
        log("STATE", "shiftsUnlocked=$shiftsUnlocked")
        
        // Trigger background HTML loading for the window user just entered
        // This is opportunistic preloading - if HTML isn't cached yet, load it now
        loadWindowHtmlAsync(windowIndex)
        
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
            // Increment maxLogicalWindowCreated to get next window ID
            maxLogicalWindowCreated++
            val newWindow = maxLogicalWindowCreated
            currentBuffer.add(newWindow)
            windowsToRemove.add(removedWindow)
            windowsToAdd.add(newWindow)
            shiftCount--
            log("SHIFT", "Shifted right: ${currentBuffer}, signal: remove $removedWindow, create $newWindow (maxLogical=$maxLogicalWindowCreated)")
        }
        
        _buffer.value = currentBuffer
        _activeWindow.value = windowIndex
        _phase.value = ConveyorPhase.STEADY
        
        log("TRANSITION", "*** PHASE TRANSITION: STARTUP → STEADY ***")
        log("TRANSITION", "New buffer: ${_buffer.value}, activeWindow: $windowIndex, center: ${_buffer.value[CENTER_INDEX]}, maxLogical: $maxLogicalWindowCreated")
        
        // Execute shift: drop old windows from cache, load new windows
        // Launch in viewModelScope to avoid blocking UI thread
        executeShiftAsync(windowsToRemove, windowsToAdd)
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
            // Increment maxLogicalWindowCreated to get next window ID
            maxLogicalWindowCreated++
            val newWindow = maxLogicalWindowCreated
            buffer.add(newWindow)
            windowsToRemove.add(removedWindow)
            windowsToAdd.add(newWindow)
            log("SHIFT", "$i: ${buffer}, signal: remove $removedWindow, create $newWindow (maxLogical=$maxLogicalWindowCreated)")
        }
        
        log("STEADY_FORWARD", "Final buffer: ${buffer}, activeWindow: $windowIndex, maxLogical: $maxLogicalWindowCreated")
        
        // Apply buffer shift immediately
        // invalidatePositionDueToBufferShift() removes the old fragment and forces rebind,
        // so there's no race condition - the fresh fragment will load the correct window's HTML
        applyBufferShift(PendingShift(buffer, windowIndex, "forward"))
        log("STEADY_FORWARD", "Buffer shift APPLIED immediately - windowIndex=$windowIndex")
        
        // Execute shift: drop old windows from cache, load new windows in background
        // The HTML loading happens asynchronously. User is currently at windowIndex and must
        // swipe multiple times before reaching the newly added windows, giving time for HTML to load.
        executeShiftAsync(windowsToRemove, windowsToAdd)
    }
    
    private fun handleSteadyBackward(windowIndex: Int, buffer: MutableList<Int>, shiftCount: Int) {
        log("STEADY_BACKWARD", "Navigating to window $windowIndex, shifts: $shiftCount")
        
        val windowsToRemove = mutableListOf<Int>()
        val windowsToAdd = mutableListOf<Int>()
        
        repeat(shiftCount) { i ->
            val removedWindow = buffer.removeAt(buffer.size - 1)
            // Decrement minLogicalWindowCreated to get next window ID going backward
            minLogicalWindowCreated--
            val newWindow = minLogicalWindowCreated
            buffer.add(0, newWindow)
            windowsToRemove.add(removedWindow)
            windowsToAdd.add(newWindow)
            log("SHIFT", "$i: ${buffer}, signal: remove $removedWindow, create $newWindow (minLogical=$minLogicalWindowCreated)")
        }
        
        log("STEADY_BACKWARD", "Final buffer: ${buffer}, activeWindow: $windowIndex, minLogical: $minLogicalWindowCreated")
        
        // Apply buffer shift immediately
        // invalidatePositionDueToBufferShift() removes the old fragment and forces rebind,
        // so there's no race condition - the fresh fragment will load the correct window's HTML
        applyBufferShift(PendingShift(buffer, windowIndex, "backward"))
        log("STEADY_BACKWARD", "Buffer shift APPLIED immediately - windowIndex=$windowIndex")
        
        // Execute shift: drop old windows from cache, load new windows in background
        // The HTML loading happens asynchronously. User is currently at windowIndex and must
        // swipe multiple times before reaching the newly added windows, giving time for HTML to load.
        executeShiftAsync(windowsToRemove, windowsToAdd)
    }
    
    /**
     * Execute a buffer shift by removing old windows from cache and loading new windows.
     * 
     * This launches a coroutine to load HTML asynchronously to avoid blocking the UI thread.
     * The HTML loading completes in the background, and by the time the user navigates to
     * the newly added windows, the HTML will be cached and ready.
     * 
     * If HTML is not ready when requested (user swipes very quickly), getWindowHtml() in
     * ReaderViewModel will generate it on-demand as a fallback.
     */
    private fun executeShiftAsync(windowsToRemove: List<Int>, windowsToAdd: List<Int>) {
        // Remove old windows from cache immediately (synchronous)
        windowsToRemove.forEach { windowIndex ->
            if (htmlCache.remove(windowIndex) != null) {
                log("SHIFT_EXEC", "Dropped window $windowIndex from cache")
            }
        }
        
        // Load new windows asynchronously to avoid blocking UI thread
        // By the time user navigates to these windows, HTML should be ready
        if (windowsToAdd.isNotEmpty()) {
            log("SHIFT_EXEC", "Loading ${windowsToAdd.size} new windows: $windowsToAdd")
            viewModelScope.launch {
                loadWindowsSync(windowsToAdd)
            }
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
        
        // Preload initial windows asynchronously
        preloadWindowsAsync(_buffer.value)
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
        
        // Preload initial buffer windows asynchronously
        preloadWindowsAsync(_buffer.value)
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
