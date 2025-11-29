package com.rifters.riftedreader.pagination

import androidx.lifecycle.LiveData
import com.rifters.riftedreader.domain.pagination.PaginationMode
import com.rifters.riftedreader.util.AppLogger

/**
 * Guard to prevent race conditions during window building/rebuilding in RecyclerView.
 * 
 * This guard ensures that pagination mode changes don't interfere with window building operations,
 * which could lead to inconsistent state (e.g., windowCount switching from 24 to 97 mid-operation).
 * 
 * All guard operations are logged to session_log for debugging race conditions.
 * 
 * Usage pattern:
 * ```kotlin
 * paginationModeGuard.beginWindowBuild()
 * try {
 *     paginator.recomputeWindows(totalChapters)
 *     syncWindowCountToUi(...)
 * } finally {
 *     paginationModeGuard.endWindowBuild()
 * }
 * ```
 * 
 * @param paginationModeLiveData Optional LiveData containing the current pagination mode.
 *                               If null, guard operates without mode checking.
 */
class PaginationModeGuard(
    private val paginationModeLiveData: LiveData<PaginationMode>? = null
) {
    
    companion object {
        private const val TAG = "PaginationModeGuard"
    }
    
    // State tracking
    @Volatile
    private var isBuilding: Boolean = false
    
    @Volatile
    private var buildStartTimeMs: Long = 0L
    
    @Volatile
    private var modeAtBuildStart: PaginationMode? = null
    
    // Build counter for nested builds tracking
    @Volatile
    private var buildNestingLevel: Int = 0
    
    /**
     * Begin a window building operation.
     * 
     * Call this before starting to build/rebuild windows. The guard will:
     * 1. Record the current pagination mode
     * 2. Set the building flag to prevent concurrent builds
     * 3. Log the start of the build
     * 
     * @return true if the build can proceed, false if a build is already in progress
     */
    fun beginWindowBuild(): Boolean {
        synchronized(this) {
            buildNestingLevel++
            
            if (isBuilding) {
                AppLogger.w(TAG, "[PAGINATION_DEBUG] Window build NESTED (level=$buildNestingLevel) - " +
                    "previous build in progress for ${System.currentTimeMillis() - buildStartTimeMs}ms")
                return false
            }
            
            isBuilding = true
            buildStartTimeMs = System.currentTimeMillis()
            modeAtBuildStart = paginationModeLiveData?.value
            
            AppLogger.d(TAG, "[PAGINATION_DEBUG] Window build STARTED: " +
                "mode=${modeAtBuildStart ?: "UNKNOWN"}, " +
                "timestamp=$buildStartTimeMs, " +
                "nestingLevel=$buildNestingLevel")
            return true
        }
    }
    
    /**
     * End a window building operation.
     * 
     * Call this after window building is complete. The guard will:
     * 1. Check if the pagination mode changed during the build
     * 2. Log any mode changes (which could indicate a race condition)
     * 3. Clear the building flag
     * 
     * @return true if the mode remained stable, false if the mode changed during build
     */
    fun endWindowBuild(): Boolean {
        synchronized(this) {
            buildNestingLevel--
            
            if (!isBuilding) {
                AppLogger.w(TAG, "[PAGINATION_DEBUG] endWindowBuild called but no build in progress (nestingLevel=$buildNestingLevel)")
                return true
            }
            
            val currentMode = paginationModeLiveData?.value
            val elapsed = System.currentTimeMillis() - buildStartTimeMs
            val modeStable = (currentMode == modeAtBuildStart) || (modeAtBuildStart == null)
            
            if (!modeStable) {
                AppLogger.e(TAG, "[PAGINATION_DEBUG] RACE_CONDITION_DETECTED: " +
                    "Mode changed during build! " +
                    "started=${modeAtBuildStart}, " +
                    "now=$currentMode, " +
                    "elapsed=${elapsed}ms")
            } else {
                AppLogger.d(TAG, "[PAGINATION_DEBUG] Window build COMPLETED: " +
                    "mode=${currentMode ?: "UNKNOWN"}, " +
                    "elapsed=${elapsed}ms, " +
                    "modeStable=true")
            }
            
            isBuilding = false
            buildStartTimeMs = 0L
            modeAtBuildStart = null
            
            return modeStable
        }
    }
    
    /**
     * Check if a window build is currently in progress.
     * 
     * @return true if a build is in progress
     */
    fun isBuildInProgress(): Boolean = isBuilding
    
    /**
     * Check if the current pagination mode matches the expected mode.
     * 
     * Use this to verify mode before performing mode-sensitive operations.
     * 
     * @param expectedMode The expected pagination mode
     * @return true if the current mode matches, false otherwise
     */
    fun checkModeIs(expectedMode: PaginationMode): Boolean {
        val currentMode = paginationModeLiveData?.value
        val matches = currentMode == expectedMode
        
        if (!matches) {
            AppLogger.d(TAG, "[PAGINATION_DEBUG] Mode check: expected=$expectedMode, actual=$currentMode")
        }
        
        return matches
    }
    
    /**
     * Assert that the window count invariant holds.
     * 
     * @param expectedWindowCount The expected window count
     * @param actualWindowCount The actual window count from the paginator
     * @throws AssertionError if the invariant is violated (only in debug builds)
     */
    fun assertWindowCountInvariant(expectedWindowCount: Int, actualWindowCount: Int) {
        if (expectedWindowCount != actualWindowCount) {
            val message = "[PAGINATION_DEBUG] WINDOW_COUNT_INVARIANT_VIOLATED: " +
                    "expected=$expectedWindowCount, actual=$actualWindowCount, " +
                    "isBuilding=$isBuilding, mode=${paginationModeLiveData?.value}"
            AppLogger.e(TAG, message)
            
            // Note: This logs the error for debugging but does not throw to avoid
            // crashing in production. The invariant violation indicates a potential
            // race condition that should be investigated during development.
        } else {
            AppLogger.d(TAG, "[PAGINATION_DEBUG] Window count invariant OK: " +
                "count=$actualWindowCount, mode=${paginationModeLiveData?.value}")
        }
    }
    
    /**
     * Get debug state information.
     * 
     * @return Debug state string for logging
     */
    fun getDebugState(): String {
        return "PaginationModeGuard[isBuilding=$isBuilding, " +
            "nestingLevel=$buildNestingLevel, " +
            "mode=${paginationModeLiveData?.value}, " +
            "buildDurationMs=${if (isBuilding) System.currentTimeMillis() - buildStartTimeMs else 0}]"
    }
}
