package com.rifters.riftedreader.domain.pagination.legacy

import com.rifters.riftedreader.domain.pagination.ChapterIndex
import com.rifters.riftedreader.domain.pagination.InPageIndex
import com.rifters.riftedreader.domain.pagination.SlidingWindowManager
import com.rifters.riftedreader.domain.pagination.WindowChapterData
import com.rifters.riftedreader.domain.pagination.WindowHtmlProvider
import com.rifters.riftedreader.domain.pagination.WindowIndex
import com.rifters.riftedreader.domain.pagination.WindowLoadState
import com.rifters.riftedreader.domain.pagination.WindowPosition
import com.rifters.riftedreader.domain.pagination.WindowPreloadConfig
import com.rifters.riftedreader.domain.pagination.WindowSnapshot
import com.rifters.riftedreader.domain.pagination.WindowState

import com.rifters.riftedreader.domain.parser.BookParser
import com.rifters.riftedreader.domain.parser.PageContent
import com.rifters.riftedreader.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * **DEPRECATED**: This class is superseded by `WindowBufferManager`.
 * 
 * StableWindowManager was the original design for managing stable sliding windows,
 * but the actual runtime implementation is now handled by `WindowBufferManager` which:
 * - Implements a 5-window buffer with two-phase lifecycle (STARTUP, STEADY)
 * - Provides StateFlow-based window and position observation
 * - Integrates with `SlidingWindowPaginator` for deterministic window computations
 * 
 * **Migration Guide:**
 * - Replace `StableWindowManager` usage with `WindowBufferManager`
 * - Use `WindowBufferManager.activeWindow` for active window state
 * - Use `WindowBufferManager.currentPosition` for position tracking
 * - Use `WindowBufferManager.updatePosition()` for position updates from JS
 * 
 * The types defined in the pagination package (`WindowSnapshot`, `WindowPosition`, `WindowState`,
 * `WindowChapterData`, `WindowPreloadConfig`, `WindowLoadState`) remain useful and are
 * not deprecated. They are used by both the legacy and current implementations.
 * 
 * @see com.rifters.riftedreader.pagination.WindowBufferManager The authoritative runtime window manager
 * @see com.rifters.riftedreader.pagination.SlidingWindowPaginator For window/chapter mapping
 * 
 * ---
 * 
 * **Original Documentation (for reference):**
 * 
 * Manages stable sliding windows for the reading experience.
 * 
 * This manager enforces the following principles:
 * 1. **Immutable Active Window**: The active window snapshot remains stable during reading.
 *    No append/prepend operations mutate the active window.
 * 2. **Background Construction**: New windows (prev/next) are constructed in the background
 *    and only become active through atomic transitions.
 * 3. **3-Window Policy**: Maintains at most 3 windows in memory (prev, active, next).
 * 4. **Preload Triggers**: Automatically preloads adjacent windows when user reaches
 *    configured progress threshold.
 * 5. **Atomic Transitions**: Window switches happen atomically with position remapping.
 * 
 * @property bookFile The book file to read from
 * @property parser The parser for reading book content
 * @property windowHtmlProvider Provider for generating window HTML
 * @property config Configuration for preloading behavior
 */
@Deprecated(
    message = "Use WindowBufferManager instead. This class is kept for reference but is no longer the authoritative runtime window manager.",
    replaceWith = ReplaceWith(
        "WindowBufferManager",
        "com.rifters.riftedreader.pagination.WindowBufferManager"
    ),
    level = DeprecationLevel.WARNING
)
class StableWindowManager(
    private val bookFile: File,
    private val parser: BookParser,
    private val windowHtmlProvider: WindowHtmlProvider,
    private val config: WindowPreloadConfig = WindowPreloadConfig()
) {
    private val mutex = Mutex()
    
    // Total number of chapters in the book
    private var totalChapters: Int = 0
    
    // Window size (chapters per window)
    private val windowSize: Int = SlidingWindowManager.DEFAULT_WINDOW_SIZE
    
    // The three windows: previous, active, next
    private var _prevWindow = MutableStateFlow<WindowSnapshot?>(null)
    val prevWindow: StateFlow<WindowSnapshot?> = _prevWindow.asStateFlow()
    
    private var _activeWindow = MutableStateFlow<WindowSnapshot?>(null)
    val activeWindow: StateFlow<WindowSnapshot?> = _activeWindow.asStateFlow()
    
    private var _nextWindow = MutableStateFlow<WindowSnapshot?>(null)
    val nextWindow: StateFlow<WindowSnapshot?> = _nextWindow.asStateFlow()
    
    // Current position within the active window
    private var _currentPosition = MutableStateFlow<WindowPosition?>(null)
    val currentPosition: StateFlow<WindowPosition?> = _currentPosition.asStateFlow()
    
    // Window manager for calculating window boundaries
    private val windowManager = SlidingWindowManager(windowSize)
    
    /**
     * Initialize the window manager.
     * Must be called before any other operations.
     */
    suspend fun initialize() = mutex.withLock {
        AppLogger.d(TAG, "Initializing StableWindowManager")
        
        totalChapters = withContext(Dispatchers.IO) {
            parser.getPageCount(bookFile)
        }
        
        AppLogger.d(TAG, "Total chapters: $totalChapters")
    }
    
    /**
     * Load the initial window centered on the given chapter.
     * 
     * @param chapterIndex The chapter to start reading from
     * @param inPageIndex The page within the chapter to start from
     * @return The loaded active window snapshot
     */
    suspend fun loadInitialWindow(
        chapterIndex: ChapterIndex,
        inPageIndex: InPageIndex = 0
    ): WindowSnapshot = mutex.withLock {
        val safeChapterIndex = chapterIndex.coerceIn(0, totalChapters - 1)
        val windowIndex = windowManager.windowForChapter(safeChapterIndex)
        
        AppLogger.d(TAG, "Loading initial window $windowIndex for chapter $safeChapterIndex")
        
        // Load the active window
        val activeSnapshot = loadWindow(windowIndex)
        _activeWindow.value = activeSnapshot
        
        // Log window enter event
        AppLogger.logWindowEnter(
            windowIndex = windowIndex,
            chapters = activeSnapshot.chapters.map { it.chapterIndex },
            navigationMode = "INITIAL_LOAD"
        )
        
        // Update the global window state for logging
        AppLogger.updateWindowState(
            windowIndex = windowIndex,
            chapters = activeSnapshot.chapters.map { it.chapterIndex },
            navigationMode = "INITIAL_LOAD"
        )
        
        // Update position (use internal version to avoid deadlock - we already hold the mutex)
        updatePositionInternal(windowIndex, safeChapterIndex, inPageIndex)
        
        // Trigger preloading of adjacent windows in the background
        preloadAdjacentWindows(windowIndex)
        
        AppLogger.d(TAG, "Initial window loaded successfully")
        return activeSnapshot
    }
    
    /**
     * Update the current reading position within the active window.
     * Triggers preloading if needed.
     * 
     * @param chapterIndex Current chapter being read
     * @param inPageIndex Current page within chapter
     */
    suspend fun updatePosition(
        windowIndex: WindowIndex,
        chapterIndex: ChapterIndex,
        inPageIndex: InPageIndex
    ) = mutex.withLock {
        updatePositionInternal(windowIndex, chapterIndex, inPageIndex)
    }
    
    /**
     * Internal version of updatePosition that does NOT acquire the mutex.
     * Must only be called from within a mutex.withLock block.
     * 
     * This exists to prevent deadlock when updatePosition needs to be called
     * from other methods that already hold the mutex (e.g., loadInitialWindow,
     * navigateToNextWindow, navigateToPrevWindow).
     */
    private suspend fun updatePositionInternal(
        windowIndex: WindowIndex,
        chapterIndex: ChapterIndex,
        inPageIndex: InPageIndex
    ) {
        val active = _activeWindow.value
        if (active == null || !active.containsChapter(chapterIndex)) {
            AppLogger.w(TAG, "Position update for chapter $chapterIndex not in active window")
            return
        }
        
        // Calculate progress through the window
        val chapter = active.getChapter(chapterIndex)
        if (chapter == null) {
            AppLogger.w(TAG, "Chapter $chapterIndex not found in active window")
            return
        }
        
        val currentPageInWindow = chapter.startPage + inPageIndex
        val progress = currentPageInWindow.toDouble() / active.totalPages.coerceAtLeast(1)
        
        val position = WindowPosition(windowIndex, chapterIndex, inPageIndex, progress)
        _currentPosition.value = position
        
        // Check if we should preload adjacent windows
        if (position.shouldPreloadNext(config.preloadThreshold)) {
            AppLogger.d(TAG, "Progress ${progress * 100}% - triggering next window preload")
            preloadNextWindow(windowIndex)
        }
        
        if (position.shouldPreloadPrev(config.preloadThreshold)) {
            AppLogger.d(TAG, "Progress ${progress * 100}% - triggering prev window preload")
            preloadPrevWindow(windowIndex)
        }
    }
    
    /**
     * Navigate to the next window.
     * This is an atomic operation that switches activeWindow → nextWindow.
     * 
     * @return The new active window snapshot, or null if at end
     */
    suspend fun navigateToNextWindow(): WindowSnapshot? = mutex.withLock {
        val current = _activeWindow.value ?: run {
            AppLogger.w(TAG, "Cannot navigate to next window: no active window")
            AppLogger.logBoundaryCondition(
                boundaryType = "END",
                windowIndex = -1,
                chapterIndex = -1,
                action = "BLOCKED_NO_ACTIVE_WINDOW"
            )
            return null
        }
        
        val next = _nextWindow.value
        if (next == null || !next.isReady) {
            AppLogger.w(TAG, "Cannot navigate to next window: next window not ready")
            AppLogger.logBoundaryCondition(
                boundaryType = "END",
                windowIndex = current.windowIndex,
                chapterIndex = current.lastChapterIndex,
                action = "BLOCKED_NEXT_NOT_READY"
            )
            return null
        }
        
        AppLogger.d(TAG, "Navigating from window ${current.windowIndex} to ${next.windowIndex}")
        
        // Log window exit event
        AppLogger.logWindowExit(
            windowIndex = current.windowIndex,
            chapters = current.chapters.map { it.chapterIndex },
            direction = "NEXT",
            targetWindowIndex = next.windowIndex
        )
        
        // Log navigation event
        AppLogger.logNavigation(
            eventType = "WINDOW_SWITCH",
            fromWindowIndex = current.windowIndex,
            toWindowIndex = next.windowIndex,
            fromChapterIndex = current.lastChapterIndex,
            toChapterIndex = next.firstChapterIndex,
            additionalInfo = mapOf(
                "direction" to "NEXT",
                "fromChapters" to current.chapters.map { it.chapterIndex }.joinToString(","),
                "toChapters" to next.chapters.map { it.chapterIndex }.joinToString(",")
            )
        )
        
        // Atomic switch: drop prev, active → prev, next → active
        _prevWindow.value = current
        _activeWindow.value = next
        _nextWindow.value = null
        
        // Log window enter event
        AppLogger.logWindowEnter(
            windowIndex = next.windowIndex,
            chapters = next.chapters.map { it.chapterIndex },
            navigationMode = "USER_NAVIGATION",
            previousWindowIndex = current.windowIndex
        )
        
        // Update the global window state for logging
        AppLogger.updateWindowState(
            windowIndex = next.windowIndex,
            chapters = next.chapters.map { it.chapterIndex },
            navigationMode = "USER_NAVIGATION"
        )
        
        // Update position to start of new active window (use internal version to avoid deadlock)
        updatePositionInternal(
            next.windowIndex,
            next.firstChapterIndex,
            0
        )
        
        // Preload new next window
        preloadNextWindow(next.windowIndex)
        
        AppLogger.d(TAG, "Successfully navigated to window ${next.windowIndex}")
        return next
    }
    
    /**
     * Navigate to the previous window.
     * This is an atomic operation that switches activeWindow → prevWindow.
     * 
     * @return The new active window snapshot, or null if at beginning
     */
    suspend fun navigateToPrevWindow(): WindowSnapshot? = mutex.withLock {
        val current = _activeWindow.value ?: run {
            AppLogger.w(TAG, "Cannot navigate to prev window: no active window")
            AppLogger.logBoundaryCondition(
                boundaryType = "START",
                windowIndex = -1,
                chapterIndex = -1,
                action = "BLOCKED_NO_ACTIVE_WINDOW"
            )
            return null
        }
        
        val prev = _prevWindow.value
        if (prev == null || !prev.isReady) {
            AppLogger.w(TAG, "Cannot navigate to prev window: prev window not ready")
            AppLogger.logBoundaryCondition(
                boundaryType = "START",
                windowIndex = current.windowIndex,
                chapterIndex = current.firstChapterIndex,
                action = "BLOCKED_PREV_NOT_READY"
            )
            return null
        }
        
        AppLogger.d(TAG, "Navigating from window ${current.windowIndex} to ${prev.windowIndex}")
        
        // Log window exit event
        AppLogger.logWindowExit(
            windowIndex = current.windowIndex,
            chapters = current.chapters.map { it.chapterIndex },
            direction = "PREV",
            targetWindowIndex = prev.windowIndex
        )
        
        // Log navigation event
        AppLogger.logNavigation(
            eventType = "WINDOW_SWITCH",
            fromWindowIndex = current.windowIndex,
            toWindowIndex = prev.windowIndex,
            fromChapterIndex = current.firstChapterIndex,
            toChapterIndex = prev.lastChapterIndex,
            additionalInfo = mapOf(
                "direction" to "PREV",
                "fromChapters" to current.chapters.map { it.chapterIndex }.joinToString(","),
                "toChapters" to prev.chapters.map { it.chapterIndex }.joinToString(",")
            )
        )
        
        // Atomic switch: drop next, active → next, prev → active
        _nextWindow.value = current
        _activeWindow.value = prev
        _prevWindow.value = null
        
        // Log window enter event
        AppLogger.logWindowEnter(
            windowIndex = prev.windowIndex,
            chapters = prev.chapters.map { it.chapterIndex },
            navigationMode = "USER_NAVIGATION",
            previousWindowIndex = current.windowIndex
        )
        
        // Update the global window state for logging
        AppLogger.updateWindowState(
            windowIndex = prev.windowIndex,
            chapters = prev.chapters.map { it.chapterIndex },
            navigationMode = "USER_NAVIGATION"
        )
        
        // Update position to end of new active window (last page of last chapter)
        // Use internal version to avoid deadlock - we already hold the mutex
        val lastChapter = prev.chapters.lastOrNull()
        if (lastChapter != null) {
            updatePositionInternal(
                prev.windowIndex,
                lastChapter.chapterIndex,
                lastChapter.pageCount - 1
            )
        }
        
        // Preload new prev window
        preloadPrevWindow(prev.windowIndex)
        
        AppLogger.d(TAG, "Successfully navigated to window ${prev.windowIndex}")
        return prev
    }
    
    /**
     * Get the current window position.
     */
    fun getCurrentPosition(): WindowPosition? {
        return _currentPosition.value
    }
    
    /**
     * Check if we're at the boundary of the active window.
     * Logs boundary conditions for debugging.
     */
    suspend fun isAtWindowBoundary(direction: NavigationDirection): Boolean = mutex.withLock {
        val position = _currentPosition.value ?: return false
        val active = _activeWindow.value ?: return false
        
        val isAtBoundary = when (direction) {
            NavigationDirection.NEXT -> {
                // At last page of last chapter in window
                position.chapterIndex == active.lastChapterIndex &&
                    position.inPageIndex >= (active.getChapter(position.chapterIndex)?.pageCount ?: 1) - 1
            }
            NavigationDirection.PREV -> {
                // At first page of first chapter in window
                position.chapterIndex == active.firstChapterIndex &&
                    position.inPageIndex == 0
            }
        }
        
        if (isAtBoundary) {
            val boundaryType = if (direction == NavigationDirection.NEXT) "WINDOW_END" else "WINDOW_START"
            AppLogger.logBoundaryCondition(
                boundaryType = boundaryType,
                windowIndex = active.windowIndex,
                chapterIndex = position.chapterIndex,
                action = "BOUNDARY_DETECTED"
            )
        }
        
        return isAtBoundary
    }
    
    /**
     * Preload both adjacent windows (if not already loaded).
     */
    private suspend fun preloadAdjacentWindows(windowIndex: WindowIndex) {
        preloadPrevWindow(windowIndex)
        preloadNextWindow(windowIndex)
    }
    
    /**
     * Preload the next window in the background.
     */
    private suspend fun preloadNextWindow(currentWindowIndex: WindowIndex) {
        // Check if already loaded
        if (_nextWindow.value != null) {
            return
        }
        
        val nextWindowIndex = currentWindowIndex + 1
        val totalWindows = windowManager.totalWindows(totalChapters)
        
        if (nextWindowIndex >= totalWindows) {
            AppLogger.d(TAG, "No next window to preload (at end)")
            return
        }
        
        AppLogger.d(TAG, "Preloading next window $nextWindowIndex")
        val snapshot = loadWindow(nextWindowIndex)
        _nextWindow.value = snapshot
    }
    
    /**
     * Preload the previous window in the background.
     */
    private suspend fun preloadPrevWindow(currentWindowIndex: WindowIndex) {
        // Check if already loaded
        if (_prevWindow.value != null) {
            return
        }
        
        if (currentWindowIndex <= 0) {
            AppLogger.d(TAG, "No prev window to preload (at beginning)")
            return
        }
        
        val prevWindowIndex = currentWindowIndex - 1
        AppLogger.d(TAG, "Preloading prev window $prevWindowIndex")
        val snapshot = loadWindow(prevWindowIndex)
        _prevWindow.value = snapshot
    }
    
    /**
     * Load a complete window and return an immutable snapshot.
     * This performs the actual I/O and HTML generation.
     */
    private suspend fun loadWindow(windowIndex: WindowIndex): WindowSnapshot {
        AppLogger.d(TAG, "Loading window $windowIndex")
        
        // Create mutable state for construction
        val firstChapter = windowManager.firstChapterInWindow(windowIndex)
        val lastChapter = windowManager.lastChapterInWindow(windowIndex, totalChapters)
        val state = WindowState(windowIndex, firstChapter, lastChapter)
        
        state.loadState = WindowLoadState.LOADING
        
        try {
            // Load all chapters in this window
            val chapters = (firstChapter..lastChapter).map { chapterIndex ->
                loadChapterData(chapterIndex)
            }
            
            chapters.forEach { state.addChapter(it) }
            
            // Generate HTML for the entire window
            val htmlContent = withContext(Dispatchers.IO) {
                windowHtmlProvider.generateWindowHtml(
                    windowIndex = windowIndex,
                    firstChapterIndex = firstChapter,
                    lastChapterIndex = lastChapter,
                    bookFile = bookFile,
                    parser = parser
                )
            }
            
            state.markLoaded()
            
            // Convert to immutable snapshot
            val snapshot = state.toSnapshot(htmlContent)
            AppLogger.d(TAG, "Window $windowIndex loaded: ${snapshot.chapters.size} chapters, ${snapshot.totalPages} pages")
            return snapshot
            
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to load window $windowIndex", e)
            state.markError(e.message ?: "Unknown error")
            return state.toSnapshot()
        }
    }
    
    /**
     * Load data for a single chapter.
     */
    private suspend fun loadChapterData(chapterIndex: ChapterIndex): WindowChapterData {
        return withContext(Dispatchers.IO) {
            val content = parser.getPageContent(bookFile, chapterIndex)
            val title = content.title ?: "Chapter ${chapterIndex + 1}"
            
            // For now, use a simple page count estimate
            // In a full implementation, this would come from WebView measurements
            val estimatedPageCount = 1
            
            WindowChapterData(
                chapterIndex = chapterIndex,
                title = title,
                pageCount = estimatedPageCount,
                startPage = 0, // Will be recalculated when building snapshot
                content = content
            )
        }
    }
    
    companion object {
        private const val TAG = "StableWindowManager"
    }
}

/**
 * Navigation direction for boundary checks.
 */
enum class NavigationDirection {
    NEXT,
    PREV
}
