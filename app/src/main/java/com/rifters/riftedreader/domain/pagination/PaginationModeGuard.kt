package com.rifters.riftedreader.domain.pagination

import com.rifters.riftedreader.util.AppLogger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Guard that locks pagination mode during window build operations.
 *
 * This prevents race conditions where the pagination mode might change while
 * windows are being constructed, which could lead to inconsistent UI states
 * and mismatched window counts.
 *
 * Thread safety: This class uses atomic operations and is thread-safe.
 * 
 * All guard operations are logged to session_log for debugging race conditions.
 *
 * Usage:
 * ```kotlin
 * paginationModeGuard.beginWindowBuild()
 * try {
 *     // Perform window recomputation
 *     paginator.recomputeWindows(totalChapters)
 * } finally {
 *     paginationModeGuard.endWindowBuild()
 * }
 * ```
 */
class PaginationModeGuard {

    private val _isBuilding = AtomicBoolean(false)
    private val buildCount = AtomicInteger(0)
    private var buildStartTimeMs: Long = 0L

    /**
     * Whether a window build operation is currently in progress.
     */
    val isBuilding: Boolean
        get() = _isBuilding.get()

    /**
     * Begin a window build operation.
     *
     * While building, any attempts to change pagination mode should be blocked
     * or queued. Multiple nested calls are supported via a reference count.
     *
     * @return true if this is the first (outermost) call, false if nested
     */
    fun beginWindowBuild(): Boolean {
        val count = buildCount.incrementAndGet()
        val isFirst = count == 1
        if (isFirst) {
            buildStartTimeMs = System.currentTimeMillis()
            _isBuilding.set(true)
            AppLogger.d(TAG, "[PAGINATION_DEBUG] beginWindowBuild: BUILD STARTED at $buildStartTimeMs (refCount=1)")
        } else {
            AppLogger.d(TAG, "[PAGINATION_DEBUG] beginWindowBuild: NESTED call (refCount=$count)")
        }
        return isFirst
    }

    /**
     * End a window build operation.
     *
     * Call this in a finally block to ensure proper cleanup.
     *
     * @return true if this was the last (outermost) call, false if nested
     */
    fun endWindowBuild(): Boolean {
        val count = buildCount.decrementAndGet()
        val isLast = count == 0
        if (isLast) {
            _isBuilding.set(false)
            val durationMs = System.currentTimeMillis() - buildStartTimeMs
            AppLogger.d(TAG, "[PAGINATION_DEBUG] endWindowBuild: BUILD COMPLETE (durationMs=$durationMs)")
        } else {
            AppLogger.d(TAG, "[PAGINATION_DEBUG] endWindowBuild: nested call completed (remaining refCount=$count)")
        }
        return isLast
    }

    /**
     * Check if a pagination mode change is currently allowed.
     *
     * Mode changes are blocked while windows are being built.
     *
     * @param requestedMode The mode being requested
     * @return true if the change is allowed, false if blocked
     */
    fun canChangePaginationMode(requestedMode: PaginationMode): Boolean {
        val building = _isBuilding.get()
        if (building) {
            AppLogger.d(TAG, "[PAGINATION_DEBUG] canChangePaginationMode: BLOCKED - " +
                "window build in progress, requested=$requestedMode, refCount=${buildCount.get()}")
            return false
        }
        AppLogger.d(TAG, "[PAGINATION_DEBUG] canChangePaginationMode: ALLOWED - requested=$requestedMode")
        return true
    }

    /**
     * Attempt to change pagination mode, blocking if a build is in progress.
     *
     * @param requestedMode The mode to change to
     * @param onChange Callback to execute if the change is allowed
     * @return true if the change was executed, false if blocked
     */
    fun tryChangePaginationMode(requestedMode: PaginationMode, onChange: () -> Unit): Boolean {
        if (!canChangePaginationMode(requestedMode)) {
            AppLogger.w(TAG, "[PAGINATION_DEBUG] tryChangePaginationMode: REJECTED - " +
                "cannot change to $requestedMode while building")
            return false
        }
        AppLogger.d(TAG, "[PAGINATION_DEBUG] tryChangePaginationMode: ALLOWED - changing to $requestedMode")
        onChange()
        return true
    }

    /**
     * Execute a window build operation with automatic begin/end management.
     *
     * @param block The operation to execute during the build
     * @return The result of the block
     */
    inline fun <T> withWindowBuild(block: () -> T): T {
        beginWindowBuild()
        try {
            return block()
        } finally {
            endWindowBuild()
        }
    }

    /**
     * Get debug information about the current guard state.
     *
     * @return Debug string with current state
     */
    fun debugState(): String {
        val building = _isBuilding.get()
        val count = buildCount.get()
        val elapsed = if (building) System.currentTimeMillis() - buildStartTimeMs else 0L
        return "PaginationModeGuard[isBuilding=$building, refCount=$count, elapsedMs=$elapsed]"
    }
    
    /**
     * Assert that the window count invariant holds.
     * Logs error if invariant is violated.
     *
     * @param expectedWindowCount The expected window count
     * @param actualWindowCount The actual window count
     */
    fun assertWindowCountInvariant(expectedWindowCount: Int, actualWindowCount: Int) {
        if (expectedWindowCount != actualWindowCount) {
            AppLogger.e(TAG, "[PAGINATION_DEBUG] WINDOW_COUNT_INVARIANT_VIOLATED: " +
                "expected=$expectedWindowCount, actual=$actualWindowCount, " +
                "isBuilding=${_isBuilding.get()}, refCount=${buildCount.get()}")
        } else {
            AppLogger.d(TAG, "[PAGINATION_DEBUG] Window count invariant OK: count=$actualWindowCount")
        }
    }

    companion object {
        private const val TAG = "PaginationModeGuard"
    }
}
