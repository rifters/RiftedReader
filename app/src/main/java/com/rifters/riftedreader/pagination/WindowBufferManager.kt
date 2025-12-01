package com.rifters.riftedreader.pagination

import com.rifters.riftedreader.domain.pagination.WindowIndex
import com.rifters.riftedreader.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * Two-phase, five-window buffer lifecycle manager for sliding-window pagination.
 * 
 * This manager implements the buffer lifecycle as shown in the reference diagram:
 * 
 * ## Phase 1: STARTUP
 * At startup, create 5 consecutive windows up front with the user starting in Window 1 (index 0).
 * Buffer contains windows [start..start+4] clamped to bounds.
 * 
 * ## Phase 2: STEADY
 * After the reader reaches Window 3 (buffer[CENTER_POS] where CENTER_POS=2), enter steady state.
 * In steady state, keep the active window centered (at position 2) with two windows ahead and two behind.
 * 
 * - On forward progression: drop the leftmost window and append a new rightmost window
 * - On backward progression: drop the rightmost window and prepend a new leftmost window
 * 
 * ## Thread Safety
 * Uses Mutex for buffer operations and ConcurrentHashMap for cache access.
 * StateFlow for phase observation.
 * 
 * @param windowAssembler The WindowAssembler used to create window HTML content
 * @param paginator The SlidingWindowPaginator for window count and range calculations
 * @param coroutineScope Scope for launching preload coroutines
 */
class WindowBufferManager(
    private val windowAssembler: WindowAssembler,
    private val paginator: SlidingWindowPaginator,
    private val coroutineScope: CoroutineScope
) {
    
    companion object {
        private const val TAG = "WindowBufferManager"
        
        /** Number of windows in the buffer */
        const val BUFFER_SIZE = 5
        
        /** Center position in the buffer (0-indexed) */
        const val CENTER_POS = 2
    }
    
    /**
     * Buffer lifecycle phase.
     */
    enum class Phase {
        /** Initial phase: building 5 consecutive windows, user starts at first window */
        STARTUP,
        
        /** Steady state: keep active window centered with 2 ahead and 2 behind */
        STEADY
    }
    
    // Current phase exposed as StateFlow
    private val _phase = MutableStateFlow(Phase.STARTUP)
    val phase: StateFlow<Phase> = _phase.asStateFlow()
    
    // Buffer of window indices (ArrayDeque for efficient add/remove at both ends)
    private val buffer = ArrayDeque<WindowIndex>(BUFFER_SIZE)
    
    // Cache of preloaded window data
    private val windowCache = ConcurrentHashMap<WindowIndex, WindowData>()
    
    // Currently active window index
    @Volatile
    private var activeWindowIndex: WindowIndex = 0
    
    // Lock for buffer operations
    private val bufferMutex = Mutex()
    
    // Track if we've entered steady state (for one-time transition)
    @Volatile
    private var hasEnteredSteadyState = false
    
    /**
     * Initialize the buffer with 5 consecutive windows starting from the given window.
     * 
     * This is called during startup (Phase 1). The buffer is built with windows
     * [startWindow..startWindow+4], clamped to book bounds. The active window
     * is set to the first window in the buffer, and all 5 windows are preloaded.
     * 
     * @param startWindow The global window index to start from
     */
    suspend fun initialize(startWindow: WindowIndex) {
        bufferMutex.withLock {
            AppLogger.d(TAG, "[PAGINATION_DEBUG] initialize: startWindow=$startWindow")
            
            // Clear any existing state
            buffer.clear()
            windowCache.clear()
            hasEnteredSteadyState = false
            _phase.value = Phase.STARTUP
            
            val totalWindows = paginator.getWindowCount()
            
            // Calculate valid window bounds (window indices are 0-based)
            val maxValidWindowIndex = (totalWindows - 1).coerceAtLeast(0)
            
            // Clamp start window to valid bounds
            val clampedStart = startWindow.coerceIn(0, maxValidWindowIndex)
            
            // Calculate the window range for the buffer
            // In STARTUP phase, we fill 5 consecutive windows starting from clampedStart
            // but adjust if we're near the end to maintain BUFFER_SIZE windows when possible
            val endWindow = (clampedStart + BUFFER_SIZE - 1).coerceAtMost(maxValidWindowIndex)
            val actualStart = (endWindow - BUFFER_SIZE + 1).coerceAtLeast(0)
            
            AppLogger.d(TAG, "[PAGINATION_DEBUG] Building buffer: " +
                "totalWindows=$totalWindows, requestedStart=$startWindow, " +
                "actualRange=$actualStart-$endWindow")
            
            // Fill buffer with window indices
            for (windowIndex in actualStart..endWindow) {
                buffer.addLast(windowIndex)
            }
            
            // Set active window to the first window in buffer (user starts here)
            // Note: If buffer is empty after initialization, this indicates a book with 0 windows
            // (no chapters), which is a valid edge case handled by returning early in that case
            if (buffer.isEmpty()) {
                AppLogger.w(TAG, "[PAGINATION_DEBUG] Buffer is empty after initialization " +
                    "(totalWindows=$totalWindows) - book may have no content")
                activeWindowIndex = 0
                return@withLock
            }
            activeWindowIndex = buffer.first
            
            AppLogger.d(TAG, "[PAGINATION_DEBUG] Buffer initialized: " +
                "buffer=${buffer.toList()}, activeWindow=$activeWindowIndex, phase=${_phase.value}")
            
            // Preload all windows in the buffer
            preloadAllBufferedWindows()
        }
    }
    
    /**
     * Called when the user enters a window.
     * 
     * In STARTUP phase, checks if the window index equals buffer[CENTER_POS].
     * If so, transitions to STEADY phase (one-time transition).
     * 
     * @param globalWindowIndex The global window index the user entered
     */
    suspend fun onEnteredWindow(globalWindowIndex: WindowIndex) {
        bufferMutex.withLock {
            AppLogger.d(TAG, "[PAGINATION_DEBUG] onEnteredWindow: globalWindowIndex=$globalWindowIndex, " +
                "currentPhase=${_phase.value}, buffer=${buffer.toList()}")
            
            // Update active window
            activeWindowIndex = globalWindowIndex
            
            // Check for STARTUP -> STEADY transition
            if (_phase.value == Phase.STARTUP && !hasEnteredSteadyState) {
                val centerWindowIndex = getCenterWindowIndex()
                if (centerWindowIndex != null && globalWindowIndex == centerWindowIndex) {
                    AppLogger.d(TAG, "[PAGINATION_DEBUG] Transitioning to STEADY phase: " +
                        "entered window $globalWindowIndex equals buffer[CENTER_POS]=$centerWindowIndex")
                    hasEnteredSteadyState = true
                    _phase.value = Phase.STEADY
                }
            }
            
            AppLogger.d(TAG, "[PAGINATION_DEBUG] After onEnteredWindow: phase=${_phase.value}")
        }
    }
    
    /**
     * Shift the buffer forward (for forward navigation).
     * 
     * Drops the leftmost window and appends a new rightmost window (if within bounds).
     * Preloads the newly appended window. Keeps the active window centered.
     * 
     * @return true if the shift was performed, false if at the end boundary
     */
    suspend fun shiftForward(): Boolean {
        bufferMutex.withLock {
            AppLogger.d(TAG, "[PAGINATION_DEBUG] shiftForward: buffer=${buffer.toList()}, " +
                "activeWindow=$activeWindowIndex")
            
            if (buffer.isEmpty()) {
                AppLogger.w(TAG, "[PAGINATION_DEBUG] shiftForward: buffer is empty")
                return false
            }
            
            val totalWindows = paginator.getWindowCount()
            val lastBufferedWindow = buffer.last
            
            // Check if we can append a new window
            val nextWindowIndex = lastBufferedWindow + 1
            if (nextWindowIndex >= totalWindows) {
                AppLogger.d(TAG, "[PAGINATION_DEBUG] shiftForward: at end boundary, " +
                    "lastBuffered=$lastBufferedWindow, totalWindows=$totalWindows")
                return false
            }
            
            // Drop leftmost window
            val droppedWindow = buffer.removeFirst()
            
            // Remove dropped window from cache
            val removed = windowCache.remove(droppedWindow)
            AppLogger.d(TAG, "[PAGINATION_DEBUG] shiftForward: dropped window $droppedWindow " +
                "(cached=${removed != null})")
            
            // Append new rightmost window
            buffer.addLast(nextWindowIndex)
            
            AppLogger.d(TAG, "[PAGINATION_DEBUG] shiftForward complete: buffer=${buffer.toList()}, " +
                "appended=$nextWindowIndex")
            
            // Preload the newly appended window
            preloadWindow(nextWindowIndex)
            
            return true
        }
    }
    
    /**
     * Shift the buffer backward (for backward navigation).
     * 
     * Drops the rightmost window and prepends a new leftmost window (if within bounds).
     * Preloads the newly prepended window. Keeps the active window centered.
     * 
     * @return true if the shift was performed, false if at the start boundary
     */
    suspend fun shiftBackward(): Boolean {
        bufferMutex.withLock {
            AppLogger.d(TAG, "[PAGINATION_DEBUG] shiftBackward: buffer=${buffer.toList()}, " +
                "activeWindow=$activeWindowIndex")
            
            if (buffer.isEmpty()) {
                AppLogger.w(TAG, "[PAGINATION_DEBUG] shiftBackward: buffer is empty")
                return false
            }
            
            val firstBufferedWindow = buffer.first
            
            // Check if we can prepend a new window
            val prevWindowIndex = firstBufferedWindow - 1
            if (prevWindowIndex < 0) {
                AppLogger.d(TAG, "[PAGINATION_DEBUG] shiftBackward: at start boundary, " +
                    "firstBuffered=$firstBufferedWindow")
                return false
            }
            
            // Drop rightmost window
            val droppedWindow = buffer.removeLast()
            
            // Remove dropped window from cache
            val removed = windowCache.remove(droppedWindow)
            AppLogger.d(TAG, "[PAGINATION_DEBUG] shiftBackward: dropped window $droppedWindow " +
                "(cached=${removed != null})")
            
            // Prepend new leftmost window
            buffer.addFirst(prevWindowIndex)
            
            AppLogger.d(TAG, "[PAGINATION_DEBUG] shiftBackward complete: buffer=${buffer.toList()}, " +
                "prepended=$prevWindowIndex")
            
            // Preload the newly prepended window
            preloadWindow(prevWindowIndex)
            
            return true
        }
    }
    
    /**
     * Get the cached WindowData for a window index.
     * 
     * @param windowIndex The window index to look up
     * @return The cached WindowData, or null if not in cache
     */
    fun getCachedWindow(windowIndex: WindowIndex): WindowData? {
        return windowCache[windowIndex]
    }
    
    /**
     * Check if a window is in the buffer.
     * 
     * @param windowIndex The window index to check
     * @return true if the window is in the buffer
     */
    fun isWindowInBuffer(windowIndex: WindowIndex): Boolean {
        return buffer.contains(windowIndex)
    }
    
    /**
     * Get the current buffer contents as a list.
     * 
     * @return List of window indices currently in the buffer
     */
    fun getBufferedWindows(): List<WindowIndex> {
        return buffer.toList()
    }
    
    /**
     * Get the current active window index.
     * 
     * @return The active window index
     */
    fun getActiveWindowIndex(): WindowIndex {
        return activeWindowIndex
    }
    
    /**
     * Get the window index at the center position of the buffer.
     * 
     * @return The center window index, or null if buffer has fewer elements
     */
    fun getCenterWindowIndex(): WindowIndex? {
        val list = buffer.toList()
        return if (list.size > CENTER_POS) list[CENTER_POS] else null
    }
    
    /**
     * Get the current cache size.
     * 
     * @return Number of windows currently cached
     */
    fun getCacheSize(): Int {
        return windowCache.size
    }
    
    /**
     * Clear all cached windows and reset the buffer.
     */
    suspend fun clear() {
        bufferMutex.withLock {
            AppLogger.d(TAG, "[PAGINATION_DEBUG] clear: clearing buffer and cache")
            buffer.clear()
            windowCache.clear()
            activeWindowIndex = 0
            hasEnteredSteadyState = false
            _phase.value = Phase.STARTUP
        }
    }
    
    /**
     * Get debug information about the current state.
     * 
     * @return Debug string for logging
     */
    fun getDebugInfo(): String {
        return "WindowBufferManager[" +
            "phase=${_phase.value}, " +
            "buffer=${buffer.toList()}, " +
            "activeWindow=$activeWindowIndex, " +
            "cacheSize=${windowCache.size}, " +
            "cachedWindows=${windowCache.keys.toList()}" +
            "]"
    }
    
    // ========================================================================
    // Private helpers
    // ========================================================================
    
    /**
     * Preload all windows currently in the buffer.
     * Called during initialization to ensure all 5 windows are ready.
     */
    private fun preloadAllBufferedWindows() {
        AppLogger.d(TAG, "[PAGINATION_DEBUG] preloadAllBufferedWindows: buffer=${buffer.toList()}")
        
        for (windowIndex in buffer) {
            preloadWindow(windowIndex)
        }
    }
    
    /**
     * Preload a single window asynchronously.
     * 
     * @param windowIndex The window index to preload
     */
    private fun preloadWindow(windowIndex: WindowIndex) {
        // Skip if already cached
        if (windowCache.containsKey(windowIndex)) {
            AppLogger.d(TAG, "[PAGINATION_DEBUG] preloadWindow: window $windowIndex already cached")
            return
        }
        
        AppLogger.d(TAG, "[PAGINATION_DEBUG] preloadWindow: starting preload for window $windowIndex")
        
        coroutineScope.launch(Dispatchers.IO) {
            try {
                // Get the window range - note that this returns IntRange (not Pair)
                // and throws exceptions if invalid, so we catch those
                val range: IntRange = try {
                    paginator.getWindowRange(windowIndex)
                } catch (e: IllegalArgumentException) {
                    AppLogger.w(TAG, "[PAGINATION_DEBUG] preloadWindow: invalid window range " +
                        "for window $windowIndex: ${e.message}")
                    return@launch
                }
                
                val windowData = windowAssembler.assembleWindow(
                    windowIndex = windowIndex,
                    firstChapter = range.first,
                    lastChapter = range.last
                )
                
                if (windowData != null) {
                    windowCache[windowIndex] = windowData
                    AppLogger.d(TAG, "[PAGINATION_DEBUG] preloadWindow: cached window $windowIndex " +
                        "(htmlLength=${windowData.html.length})")
                } else {
                    AppLogger.w(TAG, "[PAGINATION_DEBUG] preloadWindow: assembler returned null " +
                        "for window $windowIndex")
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "[PAGINATION_DEBUG] preloadWindow: failed for window $windowIndex", e)
            }
        }
    }
}
