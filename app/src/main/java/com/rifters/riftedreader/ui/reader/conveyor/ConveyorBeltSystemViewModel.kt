package com.rifters.riftedreader.ui.reader.conveyor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rifters.riftedreader.domain.pagination.ContinuousPaginator
import com.rifters.riftedreader.domain.pagination.ContinuousPaginatorWindowHtmlProvider
import com.rifters.riftedreader.domain.pagination.SlidingWindowManager
import com.rifters.riftedreader.util.AppLogger
import com.rifters.riftedreader.util.BufferLogger
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
    
    // ========================================================================
    // CONVEYOR-BELT SLOT-BASED BUFFER ARCHITECTURE
    // ========================================================================
    // Fixed 5-slot buffer with explicit slot-to-index mapping
    // - offset: maps slot[0] to real window index
    // - slots: array where slots[i] = offset + i (real window index)
    // - htmlCache: LRU cache keyed by real window index
    // ========================================================================
    
    // Offset for slot mapping: slots[0] = offset
    private var offset: Int = 0
    
    // Fixed 5-slot array: slots[i] = offset + i
    private val slots = IntArray(BUFFER_SIZE) { 0 }
    
    // HTML cache for loaded windows (keyed by real window index)
    private val htmlCache = mutableMapOf<Int, String>()
    
    // Total window count (set during initialization)
    private var totalWindowCount: Int = 0
    
    // StateFlow for observers - exposes slots as a list
    private val _buffer = MutableStateFlow<List<Int>>(listOf())
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

    private fun bufferSnapshot(): String {
        return "offset=$offset buffer=${getValidBuffer()} activeWindow=${_activeWindow.value} phase=${_phase.value} shiftsUnlocked=$shiftsUnlocked cacheKeys=${htmlCache.keys.sorted()}"
    }
    
    /**
     * Map a RecyclerView position (0-4) to the actual window index using slot mapping.
     * 
     * Uses the fixed slots array where slots[i] = offset + i.
     * This is the explicit slot-to-window mapping as per conveyor-belt architecture.
     * 
     * Example: if offset = 5, slots = [5, 6, 7, 8, 9]
     *   Position 0 → window 5
     *   Position 1 → window 6
     *   Position 2 → window 7 (center)
     *   Position 3 → window 8
     *   Position 4 → window 9
     * 
     * Public method used by ReaderPagerAdapter to map positions to window indices.
     * 
     * @param position RecyclerView position (0-4)
     * @return Real window index at this position, or 0 if invalid
     */
    fun getWindowIndexAtPosition(position: Int): Int {
        if (position !in 0 until BUFFER_SIZE) {
            log("GET_WINDOW_AT_POS", "Position $position out of range [0, $BUFFER_SIZE), returning 0")
            return 0  // Safe fallback
        }
        
        val windowIndex = slots[position]
        if (windowIndex < 0 || windowIndex >= totalWindowCount) {
            // Invalid slot (can happen for books with < 5 windows)
            log("GET_WINDOW_AT_POS", "Slot at position $position has invalid window $windowIndex, returning 0")
            return 0  // Safe fallback
        }
        
        return windowIndex
    }
    
    /**
     * Find the RecyclerView position for a specific window using slot mapping.
     * This is the reverse lookup - asking which position a window is at.
     * 
     * @param windowIndex The window to find
     * @return The position in the RecyclerView (0-4), or -1 if not in buffer
     */
    fun getPositionForWindow(windowIndex: Int): Int {
        for (i in 0 until BUFFER_SIZE) {
            if (slots[i] == windowIndex) {
                return i
            }
        }
        return -1
    }
    
    /**
     * Display a window by its NAME (window index).
     * The window should already be in the buffer (in one of the 5 slots).
     * We just select it as active and return its position.
     * 
     * @return The RecyclerView position where this window is, or -1 if not found
     */
    fun displayWindow(windowIndex: Int): Int {
        // Trigger background HTML loading for this window
        onWindowEntered(windowIndex)
        
        // Set this window as active
        _activeWindow.value = windowIndex

        BufferLogger.log(
            event = "WIN_CHANGE",
            message = "activeWindow=$windowIndex",
            details = mapOf("snapshot" to bufferSnapshot())
        )
        
        // Get position of this window in the buffer
        val position = getPositionForWindow(windowIndex)
        
        log("DISPLAY_WINDOW", "Window $windowIndex is active at position=$position")
        
        return position
    }
    
    /**
     * Shift the buffer forward by incrementing offset.
     * Recomputes all slots and triggers prefetch for new windows.
     * 
     * Example: offset=5, slots=[5,6,7,8,9]
     *   After shiftForward(1): offset=6, slots=[6,7,8,9,10]
     * 
     * @param count Number of positions to shift (typically 1)
     */
    fun shiftForward(count: Int) {
        if (count <= 0) return
        
        val oldOffset = offset
        val oldBuffer = getValidBuffer()
        
        // Increment offset (bounded by totalWindowCount)
        val maxOffset = (totalWindowCount - BUFFER_SIZE).coerceAtLeast(0)
        offset = (offset + count).coerceAtMost(maxOffset)
        
        // Recompute slots
        updateSlots()
        
        val newBuffer = getValidBuffer()
        
        log("BUFFER_SHIFT", 
            "[BUFFER_SHIFT] Forward shift: count=$count, " +
            "oldOffset=$oldOffset, newOffset=$offset, " +
            "oldBuffer=$oldBuffer, newBuffer=$newBuffer")
        
        // Update StateFlow (triggers DiffUtil in adapter)
        _buffer.value = newBuffer
        
        // Prefetch new windows that appeared in buffer
        val newWindows = newBuffer.filter { it !in oldBuffer }
        val removedWindows = oldBuffer.filter { it !in newBuffer }

        if (newWindows.isNotEmpty() || removedWindows.isNotEmpty()) {
            BufferLogger.log(
                event = "BUFFER_MUTATION",
                message = "shift=FORWARD count=$count",
                details = mapOf(
                    "added" to newWindows.toString(),
                    "removed" to removedWindows.toString(),
                    "snapshot" to bufferSnapshot()
                )
            )
        }
        if (newWindows.isNotEmpty()) {
            log("PREFETCH", "Prefetching new windows after forward shift: $newWindows")
            preloadWindowsAsync(newWindows)
        }
    }
    
    /**
     * Shift the buffer backward by decrementing offset (min 0).
     * Recomputes all slots and triggers prefetch for new windows.
     * 
     * Example: offset=5, slots=[5,6,7,8,9]
     *   After shiftBackward(1): offset=4, slots=[4,5,6,7,8]
     * 
     * @param count Number of positions to shift (typically 1)
     */
    fun shiftBackward(count: Int) {
        if (count <= 0) return
        
        val oldOffset = offset
        val oldBuffer = getValidBuffer()
        
        // Decrement offset (min 0)
        offset = (offset - count).coerceAtLeast(0)
        
        // Recompute slots
        updateSlots()
        
        val newBuffer = getValidBuffer()
        
        log("BUFFER_SHIFT",
            "[BUFFER_SHIFT] Backward shift: count=$count, " +
            "oldOffset=$oldOffset, newOffset=$offset, " +
            "oldBuffer=$oldBuffer, newBuffer=$newBuffer")
        
        // Update StateFlow (triggers DiffUtil in adapter)
        _buffer.value = newBuffer
        
        // Prefetch new windows that appeared in buffer
        val newWindows = newBuffer.filter { it !in oldBuffer }
        val removedWindows = oldBuffer.filter { it !in newBuffer }

        if (newWindows.isNotEmpty() || removedWindows.isNotEmpty()) {
            BufferLogger.log(
                event = "BUFFER_MUTATION",
                message = "shift=BACKWARD count=$count",
                details = mapOf(
                    "added" to newWindows.toString(),
                    "removed" to removedWindows.toString(),
                    "snapshot" to bufferSnapshot()
                )
            )
        }
        if (newWindows.isNotEmpty()) {
            log("PREFETCH", "Prefetching new windows after backward shift: $newWindows")
            preloadWindowsAsync(newWindows)
        }
    }
    
    /**
     * Update slots array based on current offset.
     * Only includes valid windows (bounded by totalWindowCount).
     */
    private fun updateSlots() {
        for (i in 0 until BUFFER_SIZE) {
            val windowIndex = offset + i
            slots[i] = if (windowIndex < totalWindowCount) windowIndex else -1
        }
    }
    
    /**
     * Get the current valid buffer (only windows within bounds).
     * Filters out -1 entries for books with fewer than 5 windows.
     */
    private fun getValidBuffer(): List<Int> {
        return slots.filter { it >= 0 && it < totalWindowCount }
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
        log("STATE", "Current: offset=$offset, buffer=${getValidBuffer()}, activeWindow=${_activeWindow.value}, phase=${_phase.value}")
        log("STATE", "shiftsUnlocked=$shiftsUnlocked")
        
        // Trigger background HTML loading for the window user just entered
        // This is opportunistic preloading - if HTML isn't cached yet, load it now
        loadWindowHtmlAsync(windowIndex)
        
        // Update active window
        _activeWindow.value = windowIndex
        
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
        val position = getPositionForWindow(windowIndex)
        if (position == -1) {
            log("STARTUP", "Window $windowIndex not in buffer, ignoring")
            return
        }
        
        log("STARTUP", "Window $windowIndex at position $position")
        
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
        // Calculate new offset to center the buffer on windowIndex
        val centerOffset = BUFFER_SIZE / 2
        val newOffset = (windowIndex - centerOffset).coerceAtLeast(0)
        val maxOffset = (totalWindowCount - BUFFER_SIZE).coerceAtLeast(0)
        offset = newOffset.coerceAtMost(maxOffset)
        
        // Update slots
        updateSlots()
        
        _activeWindow.value = windowIndex
        _phase.value = ConveyorPhase.STEADY
        _buffer.value = getValidBuffer()
        
        log("TRANSITION", "*** PHASE TRANSITION: STARTUP → STEADY ***")
        log("TRANSITION", "New buffer: offset=$offset, buffer=${getValidBuffer()}, activeWindow=$windowIndex")
        
        // Preload all windows in the new buffer asynchronously
        preloadWindowsAsync(getValidBuffer())
    }
    
    private fun handleSteadyNavigation(windowIndex: Int) {
        log("STEADY_NAV", "STEADY phase: ensuring window $windowIndex is in buffer")
        
        // Check if navigating back to window 2 (revert condition)
        if (windowIndex == UNLOCK_WINDOW) {
            revertToStartup()
            return
        }
        
        // Get position of window in buffer
        val position = getPositionForWindow(windowIndex)
        
        if (position == -1) {
            // Window not in buffer - this shouldn't happen but handle gracefully
            log("STEADY_NAV", "Window $windowIndex not in buffer (unexpected), recentering buffer")
            
            // Recenter buffer on this window
            val centerOffset = BUFFER_SIZE / 2
            val newOffset = (windowIndex - centerOffset).coerceAtLeast(0)
            val maxOffset = (totalWindowCount - BUFFER_SIZE).coerceAtLeast(0)
            offset = newOffset.coerceAtMost(maxOffset)
            updateSlots()
            _buffer.value = getValidBuffer()
            
            log("STEADY_NAV", "Buffer recentered: offset=$offset, buffer=${getValidBuffer()}")
            preloadWindowsAsync(getValidBuffer())
        }
        
        log("STEADY_NAV", "Buffer: offset=$offset, buffer=${getValidBuffer()}, position=$position")
    }
    
    private fun revertToStartup() {
        log("REVERT", "Hit boundary! Navigating back to window 2")
        
        // Reset to initial buffer [0-4]
        offset = 0
        updateSlots()
        
        _buffer.value = getValidBuffer()
        _activeWindow.value = UNLOCK_WINDOW
        _phase.value = ConveyorPhase.STARTUP
        shiftsUnlocked = false
        
        log("REVERT", "*** PHASE TRANSITION: STEADY → STARTUP ***")
        log("REVERT", "Buffer reverted: offset=$offset, buffer=${getValidBuffer()}, activeWindow=$UNLOCK_WINDOW")
        
        // Clear HTML cache on revert since we're going back to initial windows
        htmlCache.clear()
        log("REVERT", "HTML cache cleared")
        
        // Preload initial windows asynchronously
        preloadWindowsAsync(getValidBuffer())
    }
    
    // Methods needed by ConveyorDebugActivity and ConveyorBeltIntegrationBridge
    fun initialize(startWindow: Int, totalWindowCount: Int) {
        log("INIT", "Initialize called: startWindow=$startWindow, totalWindowCount=$totalWindowCount")
        
        this.totalWindowCount = totalWindowCount
        
        // Clamp start window to valid range
        val clampedStart = startWindow.coerceIn(0, (totalWindowCount - 1).coerceAtLeast(0))
        
        // Calculate initial offset - try to center the buffer around the start window
        // This ensures we have windows both before and after the start position when possible
        val centerOffset = BUFFER_SIZE / 2
        var initialOffset = (clampedStart - centerOffset).coerceAtLeast(0)
        val maxOffset = (totalWindowCount - BUFFER_SIZE).coerceAtLeast(0)
        initialOffset = initialOffset.coerceAtMost(maxOffset)
        
        // Set offset and compute slots
        offset = initialOffset
        updateSlots()
        
        // Update StateFlow with valid buffer
        _buffer.value = getValidBuffer()
        _activeWindow.value = clampedStart
        _phase.value = ConveyorPhase.STARTUP
        shiftsUnlocked = false
        
        // Set window count and mark as initialized
        _windowCount.value = totalWindowCount
        _isInitialized.value = true
        
        // Clear any existing HTML cache
        htmlCache.clear()
        
        log("INIT", "Conveyor initialized: windowCount=$totalWindowCount, offset=$offset, " +
            "buffer=${getValidBuffer()}, activeWindow=$clampedStart, isInitialized=true")
        
        // Preload initial buffer windows asynchronously
        preloadWindowsAsync(getValidBuffer())
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
        // Get the windowId at the center position (index 2) of the 5-window buffer
        val centerWindowIndex = slots[CENTER_INDEX]
        
        // Return null if center slot is invalid (can happen for books with < 5 windows)
        return if (centerWindowIndex >= 0 && centerWindowIndex < totalWindowCount) {
            centerWindowIndex
        } else {
            null
        }
    }
    
    /**
     * Get the current offset value for logging/debugging.
     * The offset maps slot[0] to the real window index.
     */
    fun getOffset(): Int = offset
    
    private fun log(event: String, message: String) {
        val formattedMessage = "$LOG_PREFIX [$event] $message"
        AppLogger.d(TAG, formattedMessage)
        
        // Add to event log StateFlow
        val currentLog = _eventLog.value.toMutableList()
        currentLog.add(formattedMessage)
        _eventLog.value = currentLog
    }
}
