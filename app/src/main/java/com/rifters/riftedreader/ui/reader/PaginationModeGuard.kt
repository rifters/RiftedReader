package com.rifters.riftedreader.ui.reader

import android.util.Log
import androidx.lifecycle.MutableLiveData
import com.rifters.riftedreader.domain.pagination.PaginationMode

/**
 * Guard class to manage pagination mode transitions and prevent race conditions
 * during window building operations.
 *
 * This guard prevents mode changes while windows are being rebuilt, ensuring
 * deterministic behavior with ViewPager2/RecyclerView adapters.
 *
 * @param paginationModeLiveData The LiveData holding the current pagination mode
 */
class PaginationModeGuard(
    private val paginationModeLiveData: MutableLiveData<PaginationMode>
) {

    companion object {
        private const val TAG = "PaginationModeGuard"
    }

    @Volatile
    private var isWindowBuildInProgress: Boolean = false

    /**
     * Current pagination mode from the LiveData.
     */
    val currentMode: PaginationMode
        get() = paginationModeLiveData.value ?: PaginationMode.CHAPTER_BASED

    /**
     * Whether a window build operation is currently in progress.
     */
    val isBuilding: Boolean
        get() = isWindowBuildInProgress

    /**
     * Begin a window building operation.
     * While building, mode changes will be rejected.
     */
    fun beginWindowBuild() {
        Log.d(TAG, "beginWindowBuild: starting window build (previous state: isBuilding=$isWindowBuildInProgress)")
        isWindowBuildInProgress = true
    }

    /**
     * End a window building operation.
     * Mode changes will be allowed again after this call.
     */
    fun endWindowBuild() {
        Log.d(TAG, "endWindowBuild: finishing window build")
        isWindowBuildInProgress = false
    }

    /**
     * Attempt to set a new pagination mode.
     *
     * @param newMode The new pagination mode to set
     * @return true if the mode was successfully changed, false if rejected (e.g., during window build)
     */
    fun trySetMode(newMode: PaginationMode): Boolean {
        if (isWindowBuildInProgress) {
            Log.d(TAG, "trySetMode: REJECTED mode change to $newMode (window build in progress)")
            return false
        }

        val oldMode = paginationModeLiveData.value
        if (oldMode == newMode) {
            Log.d(TAG, "trySetMode: mode already set to $newMode, no change needed")
            return true
        }

        Log.d(TAG, "trySetMode: changing mode from $oldMode to $newMode")
        paginationModeLiveData.value = newMode
        return true
    }
}
