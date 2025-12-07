package com.rifters.riftedreader.pagination

import com.rifters.riftedreader.domain.pagination.ChapterIndex
import com.rifters.riftedreader.domain.pagination.InPageIndex
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
 * **This is the authoritative runtime window manager for continuous pagination mode.**
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
 * ## Ownership Model
 * 
 * WindowBufferManager is the **single source of truth** for:
 * - Which windows exist and are cached (via windowCache)
 * - The active window state (via activeWindow StateFlow)
 * - The current reading position (via currentPosition StateFlow)
 * - Buffer phase and lifecycle transitions
 * 
 * Other components should rely on this manager's StateFlows for window state, not maintain
 * their own window tracking logic.
 * 
 * ## Thread Safety
 * Uses Mutex for buffer operations and ConcurrentHashMap for cache access.
 * StateFlow for phase observation.
 * 
 * @param windowAssembler The WindowAssembler used to create window HTML content
 * @param paginator The SlidingWindowPaginator for window count and range calculations
 * @param coroutineScope Scope for launching preload coroutines
 * @param preloadConfig Configuration for preloading behavior (optional)
 * 
 * @see com.rifters.riftedreader.domain.pagination.legacy.StableWindowManager Conceptual ancestor (deprecated)
 */
class WindowBufferManager(
    private val windowAssembler: WindowAssembler,
    private val paginator: SlidingWindowPaginator,
    private val coroutineScope: CoroutineScope,
    private val preloadConfig: PreloadConfig = PreloadConfig()
) {
    
    companion object {
        private const val TAG = "WindowBufferManager"
        
        /** Number of windows in the buffer */
        const val BUFFER_SIZE = 5
        
        /** Center position in the buffer (0-indexed) */
        const val CENTER_POS = 2
        
        /** Threshold for considering progress at the forward boundary (nearly at end) */
        const val FORWARD_BOUNDARY_THRESHOLD = 0.99
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
    
    /**
     * Configuration for window preloading behavior.
     * 
     * @property forwardThreshold Progress (0.0-1.0) at which to trigger forward preloading
     * @property backwardThreshold Progress (0.0-1.0) at which to trigger backward preloading
     */
    data class PreloadConfig(
        val forwardThreshold: Double = 0.75,
        val backwardThreshold: Double = 0.25
    ) {
        init {
            require(forwardThreshold in 0.0..1.0) { 
                "forwardThreshold must be between 0.0 and 1.0, got: $forwardThreshold" 
            }
            require(backwardThreshold in 0.0..1.0) { 
                "backwardThreshold must be between 0.0 and 1.0, got: $backwardThreshold" 
            }
        }
    }
    
    /**
     * Represents the user's current position within a window.
     * 
     * This is the authoritative position state - UI components should observe
     * this rather than maintaining their own position tracking.
     * 
     * @property windowIndex The current window index
     * @property chapterIndex The current chapter index within the window
     * @property inPageIndex The current page index within the chapter (0-based)
     * @property progress Progress through the current window (0.0-1.0)
     */
    data class Position(
        val windowIndex: WindowIndex,
        val chapterIndex: ChapterIndex,
        val inPageIndex: InPageIndex,
        val progress: Double
    ) {
        init {
            require(progress in 0.0..1.0) {
                "progress must be between 0.0 and 1.0, got: $progress"
            }
        }
        
        /**
         * Check if forward preload should be triggered based on progress.
         */
        fun shouldPreloadForward(threshold: Double): Boolean = progress >= threshold
        
        /**
         * Check if backward preload should be triggered based on progress.
         */
        fun shouldPreloadBackward(threshold: Double): Boolean = progress <= threshold
    }
    
    // Current phase exposed as StateFlow
    private val _phase = MutableStateFlow(Phase.STARTUP)
    val phase: StateFlow<Phase> = _phase.asStateFlow()
    
    // Active window data exposed as StateFlow - UI can observe this
    private val _activeWindow = MutableStateFlow<WindowData?>(null)
    
    /**
     * The currently active window data.
     * 
     * This is the authoritative source for the active window state. UI components
     * should observe this StateFlow rather than querying the cache directly.
     */
    val activeWindow: StateFlow<WindowData?> = _activeWindow.asStateFlow()
    
    // Current position exposed as StateFlow - UI can observe this
    private val _currentPosition = MutableStateFlow<Position?>(null)
    
    /**
     * The current reading position within the active window.
     * 
     * This is updated when:
     * - JS reports page changes via onPageChanged
     * - Window transitions occur
     * - Position is explicitly updated via updatePosition()
     * 
     * UI components should observe this StateFlow for position updates.
     */
    val currentPosition: StateFlow<Position?> = _currentPosition.asStateFlow()
    
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
            AppLogger.d(TAG, "[BUFFER_INIT] ENTRY: initialize(startWindow=$startWindow)")
            
            // Clear any existing state
            buffer.clear()
            windowCache.clear()
            hasEnteredSteadyState = false
            _phase.value = Phase.STARTUP
            
            val totalWindows = paginator.getWindowCount()
            AppLogger.d(TAG, "[BUFFER_INIT] Initializing buffer: totalWindows=$totalWindows")
            
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
                _activeWindow.value = null
                _currentPosition.value = null
                return@withLock
            }
            activeWindowIndex = buffer.first
            
            AppLogger.d(TAG, "[PAGINATION_DEBUG] Buffer initialized: " +
                "buffer=${buffer.toList()}, activeWindow=$activeWindowIndex, phase=${_phase.value}")
            
            // Preload all windows in the buffer
            preloadAllBufferedWindows()
            
            // Update active window StateFlow after preload is initiated
            // The actual data will be available once preload completes
            updateActiveWindowStateFlow()
            AppLogger.d(TAG, "[BUFFER_INIT] EXIT: Buffer initialization complete")
        }
    }
    
    /**
     * Update the active window StateFlow based on current activeWindowIndex.
     * 
     * This should be called whenever the active window changes.
     */
    private fun updateActiveWindowStateFlow() {
        val windowData = windowCache[activeWindowIndex]
        _activeWindow.value = windowData
        
        if (windowData != null) {
            AppLogger.d(TAG, "[PAGINATION_DEBUG] Updated _activeWindow: windowIndex=$activeWindowIndex, " +
                "htmlLength=${windowData.html.length}")
        } else {
            AppLogger.d(TAG, "[PAGINATION_DEBUG] Active window $activeWindowIndex not yet cached")
        }
    }
    
    /**
     * Called when the user enters a window.
     * 
     * In STARTUP phase, checks if the window index equals buffer[CENTER_POS].
     * If so, transitions to STEADY phase (one-time transition).
     * 
     * Also updates the activeWindow StateFlow with the current window data.
     * 
     * @param globalWindowIndex The global window index the user entered
     */
    suspend fun onEnteredWindow(globalWindowIndex: WindowIndex) {
        bufferMutex.withLock {
            AppLogger.d(TAG, "[WINDOW_ENTRY] ENTRY: onEnteredWindow(globalWindowIndex=$globalWindowIndex)")
            AppLogger.d(TAG, "[PAGINATION_DEBUG] onEnteredWindow: globalWindowIndex=$globalWindowIndex, " +
                "currentPhase=${_phase.value}, buffer=${buffer.toList()}")
            AppLogger.d(TAG, "[WINDOW_ENTRY] Current state: phase=${_phase.value}, buffer=${buffer.toList()}, " +
                "activeWindow=$activeWindowIndex, centerWindow=${getCenterWindowIndex()}")
            
            // Update active window
            activeWindowIndex = globalWindowIndex
            
            // Update active window StateFlow
            updateActiveWindowStateFlow()
            
            // Check for STARTUP -> STEADY transition
            if (_phase.value == Phase.STARTUP && !hasEnteredSteadyState) {
                val centerWindowIndex = getCenterWindowIndex()
                AppLogger.d(TAG, "[PHASE_TRANS_DEBUG] Checking transition: centerWindow=$centerWindowIndex, globalWindow=$globalWindowIndex, match=${centerWindowIndex == globalWindowIndex}")
                AppLogger.d(TAG, "[WINDOW_ENTRY] Phase transition check: STARTUP->STEADY candidate at window=$globalWindowIndex (center=$centerWindowIndex)")
                if (centerWindowIndex != null && globalWindowIndex == centerWindowIndex) {
                    val oldPhase = _phase.value
                    hasEnteredSteadyState = true
                    _phase.value = Phase.STEADY
                    AppLogger.d(TAG, "[WINDOW_ENTRY] *** PHASE FLIPPED: STARTUP -> STEADY at window $globalWindowIndex ***")
                    AppLogger.d(TAG, "[CONVEYOR] *** PHASE TRANSITION STARTUP -> STEADY ***\n" +
                        "  Entered center window ($globalWindowIndex) of buffer\n" +
                        "  buffer=${buffer.toList()}\n" +
                        "  activeWindow=$globalWindowIndex\n" +
                        "  Now entering steady state with 2 windows ahead and 2 behind")
                } else if (_phase.value == Phase.STARTUP) {
                    AppLogger.d(TAG, "[WINDOW_ENTRY] Still in STARTUP phase: window=$globalWindowIndex is not center (center=$centerWindowIndex)")
                }
            }
            
            AppLogger.d(TAG, "[WINDOW_ENTRY] EXIT: onEnteredWindow complete, phase now=${_phase.value}")
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
            if (buffer.isEmpty()) {
                AppLogger.w(TAG, "[CONVEYOR] shiftForward: buffer is empty")
                return false
            }
            
            val totalWindows = paginator.getWindowCount()
            val lastBufferedWindow = buffer.last
            val oldBuffer = buffer.toList()
            
            // Check if we can append a new window
            val nextWindowIndex = lastBufferedWindow + 1
            if (nextWindowIndex >= totalWindows) {
                AppLogger.d(TAG, "[CONVEYOR] shiftForward: at end boundary, " +
                    "lastBuffered=$lastBufferedWindow, totalWindows=$totalWindows")
                return false
            }
            
            // Drop leftmost window
            val droppedWindow = buffer.removeFirst()
            
            // Remove dropped window from cache
            val removed = windowCache.remove(droppedWindow)
            
            // Append new rightmost window
            buffer.addLast(nextWindowIndex)
            
            AppLogger.d(TAG, "[CONVEYOR] *** SHIFT FORWARD ***\n" +
                "  oldBuffer=$oldBuffer\n" +
                "  newBuffer=${buffer.toList()}\n" +
                "  droppedWindow=$droppedWindow (was in cache: ${removed != null})\n" +
                "  newlyCreated=$nextWindowIndex (preloading...)\n" +
                "  activeWindow=$activeWindowIndex\n" +
                "  cacheSize=${windowCache.size} (after drop)")
            
            // Preload the newly appended window
            AppLogger.d(TAG, "[BUFFER_SHIFT] *** SHIFT FORWARD: window=$nextWindowIndex appended ***")
            AppLogger.d(TAG, "[BUFFER_SHIFT] Preloading newly appended window $nextWindowIndex")
            preloadWindow(nextWindowIndex)
            AppLogger.d(TAG, "[BUFFER_SHIFT] Shift forward complete: buffer now ${buffer.toList()}")
            
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
            if (buffer.isEmpty()) {
                AppLogger.w(TAG, "[CONVEYOR] shiftBackward: buffer is empty")
                return false
            }
            
            val firstBufferedWindow = buffer.first
            val oldBuffer = buffer.toList()
            
            // Check if we can prepend a new window
            val prevWindowIndex = firstBufferedWindow - 1
            if (prevWindowIndex < 0) {
                AppLogger.d(TAG, "[CONVEYOR] shiftBackward: at start boundary, " +
                    "firstBuffered=$firstBufferedWindow")
                return false
            }
            
            // Drop rightmost window
            val droppedWindow = buffer.removeLast()
            
            // Remove dropped window from cache
            val removed = windowCache.remove(droppedWindow)
            
            // Prepend new leftmost window
            buffer.addFirst(prevWindowIndex)
            
            AppLogger.d(TAG, "[CONVEYOR] *** SHIFT BACKWARD ***\n" +
                "  oldBuffer=$oldBuffer\n" +
                "  newBuffer=${buffer.toList()}\n" +
                "  droppedWindow=$droppedWindow (was in cache: ${removed != null})\n" +
                "  newlyCreated=$prevWindowIndex (preloading...)\n" +
                "  activeWindow=$activeWindowIndex\n" +
                "  cacheSize=${windowCache.size} (after drop)")
            
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
            _activeWindow.value = null
            _currentPosition.value = null
        }
    }
    
    /**
     * Update the current reading position.
     * 
     * Called when JS reports page/chapter changes via onPageChanged callback.
     * This updates the _currentPosition StateFlow and may trigger preloading
     * if the user has progressed past the configured thresholds.
     * 
     * @param chapterIndex Current chapter being viewed
     * @param inPageIndex Current page within the window (0-based)
     * @param totalPagesInWindow Total pages in the current window (for progress calculation)
     */
    suspend fun updatePosition(
        chapterIndex: ChapterIndex,
        inPageIndex: InPageIndex,
        totalPagesInWindow: Int
    ) {
        bufferMutex.withLock {
            // Use explicit double division to ensure floating-point precision
            val progress = if (totalPagesInWindow > 0) {
                (inPageIndex.toDouble() / totalPagesInWindow.toDouble()).coerceIn(0.0, 1.0)
            } else {
                0.0
            }
            
            val position = Position(
                windowIndex = activeWindowIndex,
                chapterIndex = chapterIndex,
                inPageIndex = inPageIndex,
                progress = progress
            )
            
            _currentPosition.value = position
            
            AppLogger.d(TAG, "[PAGINATION_DEBUG] updatePosition: window=$activeWindowIndex, " +
                "chapter=$chapterIndex, inPage=$inPageIndex/$totalPagesInWindow, progress=${"%.2f".format(progress * 100)}%")
            
            // Check for preload triggers based on progress
            if (_phase.value == Phase.STEADY) {
                if (position.shouldPreloadForward(preloadConfig.forwardThreshold)) {
                    AppLogger.d(TAG, "[PAGINATION_DEBUG] Progress ${"%.1f".format(progress * 100)}% >= " +
                        "${"%.1f".format(preloadConfig.forwardThreshold * 100)}% - forward preload may be needed")
                }
                if (position.shouldPreloadBackward(preloadConfig.backwardThreshold)) {
                    AppLogger.d(TAG, "[PAGINATION_DEBUG] Progress ${"%.1f".format(progress * 100)}% <= " +
                        "${"%.1f".format(preloadConfig.backwardThreshold * 100)}% - backward preload may be needed")
                }
            }
        }
    }
    
    /**
     * Check if the current position is at a window boundary.
     * 
     * This is useful for determining when to transition to the next/previous window.
     * 
     * @param direction The navigation direction to check
     * @return true if at the boundary in the specified direction
     */
    fun isAtWindowBoundary(direction: NavigationDirection): Boolean {
        val position = _currentPosition.value ?: return false
        // Verify we have an active window
        _activeWindow.value ?: return false
        
        return when (direction) {
            NavigationDirection.FORWARD -> {
                // At boundary if we're on the last page (nearly at end)
                position.progress >= FORWARD_BOUNDARY_THRESHOLD
            }
            NavigationDirection.BACKWARD -> {
                // At boundary if we're on the first page
                position.inPageIndex == 0
            }
        }
    }
    
    /**
     * Check if there's a next window available.
     * 
     * Checks both: (1) there's another window beyond current in the global window set,
     * AND (2) the next window is not beyond what can be loaded.
     * 
     * @return true if there's a window after the current active window
     */
    fun hasNextWindow(): Boolean {
        val totalWindows = paginator.getWindowCount()
        // FIX #3: Check both global boundary and buffer boundary
        // Can only shift forward if: activeWindow < totalWindows-1 AND nextWindow not beyond buffer
        val canShiftGlobally = activeWindowIndex < totalWindows - 1
        val canShiftFromBuffer = buffer.isNotEmpty() && activeWindowIndex == buffer.last - 1
        // If we're not at the last window in the buffer, next window exists
        val hasNextInBuffer = buffer.isNotEmpty() && activeWindowIndex < buffer.last
        
        return canShiftGlobally && (canShiftFromBuffer || hasNextInBuffer)
    }
    
    /**
     * Check if there's a previous window available.
     * 
     * Checks both: (1) there's a previous window (index > 0) AND 
     * (2) the previous window is in the buffer or can be loaded.
     * 
     * @return true if there's a window before the current active window
     */
    fun hasPreviousWindow(): Boolean {
        // FIX #3: Check both global boundary and buffer boundary
        // Can only shift backward if: activeWindow > 0 AND prevWindow is available
        val canShiftGlobally = activeWindowIndex > 0
        val canShiftFromBuffer = buffer.isNotEmpty() && activeWindowIndex > buffer.first
        // If we're not at the first window in the buffer, previous window exists
        val hasPrevInBuffer = buffer.isNotEmpty() && activeWindowIndex > buffer.first
        
        return canShiftGlobally && (hasPrevInBuffer)
    }
    
    /**
     * Navigation direction for boundary checks.
     */
    enum class NavigationDirection {
        FORWARD,
        BACKWARD
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
                    
                    // If this is the active window, update the StateFlow
                    if (windowIndex == activeWindowIndex && _activeWindow.value == null) {
                        _activeWindow.value = windowData
                        AppLogger.d(TAG, "[PAGINATION_DEBUG] preloadWindow: updated _activeWindow " +
                            "for newly cached active window $windowIndex")
                    }
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
