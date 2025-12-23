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
    
    // LinkedHashMap maintains insertion order: oldest first, newest last
    // Dropping oldest = remove first key, Adding newest = put(windowIndex, true)
    private val windowCache = linkedMapOf<Int, Boolean>()
    
    // StateFlow for observers - exposes cached window indices as a list
    private val _buffer = MutableStateFlow<List<Int>>(listOf())
    val buffer: StateFlow<List<Int>> = _buffer.asStateFlow()
    
    private val _activeWindow = MutableStateFlow<Int>(0)
    val activeWindow: StateFlow<Int> = _activeWindow.asStateFlow()
    
    data class PendingShift(
        val windowsToRemove: List<Int>,
        val windowsToAdd: List<Int>,
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
     * Map a RecyclerView position (0-4) to the actual window index based on buffer state.
     * 
     * The buffer is the source of truth for which windows are at which positions.
     * Always look it up directly instead of calculating.
     * 
     * Example: if buffer = [1, 2, 3, 4, 5]
     *   Position 0 = buffer[0] = window 1
     *   Position 1 = buffer[1] = window 2
     *   Position 2 = buffer[2] = window 3 (this is what gets displayed)
     *   Position 3 = buffer[3] = window 4
     *   Position 4 = buffer[4] = window 5
     * 
     * Public method used by ReaderPagerAdapter to map positions to window indices.
     */
    fun getWindowIndexAtPosition(position: Int): Int {
        val buffer = _buffer.value
        return if (position in 0 until buffer.size) {
            buffer[position]  // Use actual buffer state as source of truth
        } else {
            // Fallback if position out of range
            position
        }
    }
    
    /**
     * Get the list of windows that should be at each position based on active window.
     * This is used to map positions to window indices for display.
     */
    private fun getExpectedWindowsForDisplay(): List<Int> {
        val activeWin = _activeWindow.value
        return listOf(
            activeWin - 2,  // position 0
            activeWin - 1,  // position 1
            activeWin,      // position 2 (center)
            activeWin + 1,  // position 3
            activeWin + 2   // position 4
        )
    }
    
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
     * Apply a buffer shift - update window cache and active window.
     * Removes old windows, adds new windows to the cache.
     */
    fun applyBufferShift(shift: PendingShift) {
        com.rifters.riftedreader.util.AppLogger.d("ConveyorBeltSystemViewModel",
            "[BUFFER_SHIFT_START] Applying buffer shift: direction=${shift.direction}, remove=${shift.windowsToRemove}, add=${shift.windowsToAdd}"
        )
        
        log("BUFFER_SHIFT", "Applying buffer shift: ${shift.direction}, removing=${shift.windowsToRemove}, adding=${shift.windowsToAdd}")
        
        // Remove old windows from cache
        shift.windowsToRemove.forEach { windowIndex ->
            windowCache.remove(windowIndex)
        }
        
        // Add new windows to cache (will be loaded asynchronously)
        shift.windowsToAdd.forEach { windowIndex ->
            windowCache[windowIndex] = true
        }
        
        // Update active window
        _activeWindow.value = shift.windowIndex
        
        // Update StateFlow with current cache keys
        _buffer.value = windowCache.keys.toList()
        
        com.rifters.riftedreader.util.AppLogger.d("ConveyorBeltSystemViewModel",
            "[BUFFER_SHIFT_UPDATE] Buffer updated: activeWindow=${shift.windowIndex}, cachedWindows=${windowCache.keys.toList()}"
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
        
        // Check if window is in cache
        if (windowIndex !in windowCache) {
            log("STARTUP", "Window $windowIndex not in cache, ignoring")
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
        
        // In STEADY, we need a centered buffer around the new active window
        // activeWindow is at position 2 (center), so we need [activeWindow-2 to activeWindow+2]
        
        // Clear old STARTUP buffer and build new STEADY buffer centered on windowIndex
        windowCache.clear()
        
        // Create a centered buffer: [windowIndex-2, windowIndex-1, windowIndex, windowIndex+1, windowIndex+2]
        for (offset in -2..2) {
            val windowIdx = windowIndex + offset
            if (windowIdx >= 0 && windowIdx <= _windowCount.value - 1) {
                windowCache[windowIdx] = true
            }
        }
        
        // Track the max window we've created
        maxLogicalWindowCreated = windowCache.keys.maxOrNull() ?: maxLogicalWindowCreated
        minLogicalWindowCreated = windowCache.keys.minOrNull() ?: minLogicalWindowCreated
        
        _activeWindow.value = windowIndex
        _phase.value = ConveyorPhase.STEADY
        _buffer.value = windowCache.keys.toList()
        
        log("TRANSITION", "*** PHASE TRANSITION: STARTUP → STEADY ***")
        log("TRANSITION", "New cache: ${windowCache.keys.toList()}, activeWindow: $windowIndex, minLogical: $minLogicalWindowCreated, maxLogical: $maxLogicalWindowCreated")
        
        // Preload all windows in the new buffer asynchronously
        preloadWindowsAsync(windowCache.keys.toList())
    }
    
    private fun handleSteadyNavigation(windowIndex: Int) {
        log("STEADY", "Navigating to window $windowIndex")
        
        val currentCenter = _activeWindow.value
        
        // Check if navigating back to window 2 (revert condition)
        if (windowIndex == UNLOCK_WINDOW) {
            revertToStartup()
            return
        }
        
        // Calculate shift direction and amount
        val shiftDelta = windowIndex - currentCenter
        
        if (shiftDelta > 0) {
            // Navigate forward: shift right
            handleSteadyForward(windowIndex, shiftDelta)
        } else if (shiftDelta < 0) {
            // Navigate backward: shift left
            handleSteadyBackward(windowIndex, -shiftDelta)
        } else {
            log("STEADY", "Already at window $windowIndex (center)")
            _activeWindow.value = windowIndex
        }
    }
    
    private fun handleSteadyForward(windowIndex: Int, shiftCount: Int) {
        log("STEADY_FORWARD", "Navigating to window $windowIndex, shifts: $shiftCount")
        
        val windowsToRemove = mutableListOf<Int>()
        val windowsToAdd = mutableListOf<Int>()
        
        repeat(shiftCount) { i ->
            // Remove the oldest window from cache
            val oldestKey = windowCache.keys.first()
            windowCache.remove(oldestKey)
            windowsToRemove.add(oldestKey)
            
            // Add next new window
            maxLogicalWindowCreated++
            val newWindow = maxLogicalWindowCreated
            windowCache[newWindow] = true
            windowsToAdd.add(newWindow)
            
            log("SHIFT", "Forward shift $i: remove $oldestKey, add $newWindow (cache now: ${windowCache.keys.toList()})")
        }
        
        log("STEADY_FORWARD", "Final cache: ${windowCache.keys.toList()}, activeWindow: $windowIndex, maxLogical: $maxLogicalWindowCreated")
        
        // Apply buffer shift immediately
        applyBufferShift(PendingShift(windowsToRemove, windowsToAdd, windowIndex, "forward"))
        log("STEADY_FORWARD", "Buffer shift APPLIED immediately - windowIndex=$windowIndex")
        
        // Execute shift: load new windows in background
        executeShiftAsync(windowsToRemove, windowsToAdd)
    }
    
    private fun handleSteadyBackward(windowIndex: Int, shiftCount: Int) {
        log("STEADY_BACKWARD", "Navigating to window $windowIndex, shifts: $shiftCount")
        
        val windowsToRemove = mutableListOf<Int>()
        val windowsToAdd = mutableListOf<Int>()
        
        repeat(shiftCount) { i ->
            // Remove the newest window from cache (last in LinkedHashMap)
            val newestKey = windowCache.keys.last()
            windowCache.remove(newestKey)
            windowsToRemove.add(newestKey)
            
            // Add previous new window (going backward)
            minLogicalWindowCreated--
            val newWindow = minLogicalWindowCreated
            windowsToAdd.add(newWindow)
            
            log("SHIFT", "Backward shift $i: remove $newestKey, add $newWindow")
        }
        
        // Rebuild cache with new windows prepended to maintain insertion order
        val newCache = linkedMapOf<Int, Boolean>()
        windowsToAdd.forEach { newCache[it] = true }  // Add new windows first (oldest)
        windowCache.forEach { (k, v) -> newCache[k] = v }  // Add existing windows
        windowCache.clear()
        windowCache.putAll(newCache)
        
        log("STEADY_BACKWARD", "Final cache: ${windowCache.keys.toList()}, activeWindow: $windowIndex, minLogical: $minLogicalWindowCreated")
        
        // Apply buffer shift immediately
        applyBufferShift(PendingShift(windowsToRemove, windowsToAdd, windowIndex, "backward"))
        log("STEADY_BACKWARD", "Buffer shift APPLIED immediately - windowIndex=$windowIndex")
        
        // Execute shift: load new windows in background
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
        
        // Reset cache to initial windows [0-4]
        windowCache.clear()
        for (i in 0..4) {
            windowCache[i] = true
        }
        
        _buffer.value = windowCache.keys.toList()
        _activeWindow.value = UNLOCK_WINDOW
        _phase.value = ConveyorPhase.STARTUP
        shiftsUnlocked = false
        maxLogicalWindowCreated = 4
        minLogicalWindowCreated = 0
        
        log("REVERT", "*** PHASE TRANSITION: STEADY → STARTUP ***")
        log("REVERT", "Cache reverted: ${windowCache.keys.toList()}, activeWindow: $UNLOCK_WINDOW")
        
        // Clear HTML cache on revert since we're going back to initial windows
        htmlCache.clear()
        log("REVERT", "HTML cache cleared")
        
        // Preload initial windows asynchronously
        preloadWindowsAsync(windowCache.keys.toList())
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
        
        // Initialize window cache (LinkedHashMap) with starting windows
        windowCache.clear()
        for (windowIndex in bufferStart..bufferEnd) {
            windowCache[windowIndex] = true
        }
        
        // Update StateFlow with current cache keys
        _buffer.value = windowCache.keys.toList()
        _activeWindow.value = clampedStart
        _phase.value = ConveyorPhase.STARTUP
        shiftsUnlocked = false
        
        // Track logical window bounds for shift calculations
        maxLogicalWindowCreated = bufferEnd
        minLogicalWindowCreated = bufferStart
        
        // Set window count and mark as initialized
        _windowCount.value = totalWindowCount
        _isInitialized.value = true
        
        // Clear any existing HTML cache
        htmlCache.clear()
        
        log("INIT", "Conveyor initialized: windowCount=$totalWindowCount, cachedWindows=${windowCache.keys.toList()}, activeWindow=$clampedStart, isInitialized=true")
        
        // Preload initial buffer windows asynchronously
        preloadWindowsAsync(windowCache.keys.toList())
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
        // Center window is always activeWindow (position 2 in the 5-window display)
        return _activeWindow.value
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
