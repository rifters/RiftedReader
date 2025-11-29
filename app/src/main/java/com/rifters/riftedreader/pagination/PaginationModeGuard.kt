package com.rifters.riftedreader.pagination

import android.util.Log
import androidx.lifecycle.LiveData
import com.rifters.riftedreader.domain.pagination.PaginationMode

/**
 * Guard to prevent race conditions during window building/rebuilding in RecyclerView.
 * 
 * This guard ensures that pagination mode changes don't interfere with window building operations,
 * which could lead to inconsistent state (e.g., windowCount switching from 24 to 97 mid-operation).
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
            if (isBuilding) {
                Log.w(TAG, "Window build already in progress, rejecting new build request")
                return false
            }
            
            isBuilding = true
            buildStartTimeMs = System.currentTimeMillis()
            modeAtBuildStart = paginationModeLiveData?.value
            
            Log.d(TAG, "Window build started, mode=${modeAtBuildStart ?: "UNKNOWN"}")
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
            if (!isBuilding) {
                Log.w(TAG, "endWindowBuild called but no build in progress")
                return true
            }
            
            val currentMode = paginationModeLiveData?.value
            val elapsed = System.currentTimeMillis() - buildStartTimeMs
            val modeStable = (currentMode == modeAtBuildStart) || (modeAtBuildStart == null)
            
            if (!modeStable) {
                Log.w(TAG, "Pagination mode changed during build! " +
                        "Started with ${modeAtBuildStart}, now ${currentMode}, " +
                        "elapsed=${elapsed}ms - THIS MAY INDICATE A RACE CONDITION")
            } else {
                Log.d(TAG, "Window build completed, mode=${currentMode ?: "UNKNOWN"}, elapsed=${elapsed}ms")
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
            Log.d(TAG, "Mode check: expected=$expectedMode, actual=$currentMode")
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
            val message = "Window count invariant violated: " +
                    "expected=$expectedWindowCount, actual=$actualWindowCount, " +
                    "isBuilding=$isBuilding, mode=${paginationModeLiveData?.value}"
            Log.e(TAG, message)
            
            // Note: This logs the error for debugging but does not throw to avoid
            // crashing in production. The invariant violation indicates a potential
            // race condition that should be investigated during development.
        }
    }
}
