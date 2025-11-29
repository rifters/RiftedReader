package com.rifters.riftedreader.pagination

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.MutableLiveData
import com.rifters.riftedreader.util.AppLogger

/**
 * Helper functions for synchronizing window count updates to the UI thread.
 * 
 * These helpers ensure that RecyclerView adapter updates happen on the
 * main thread and are properly synchronized with the paginator state.
 * 
 * All synchronization operations are logged to session_log for debugging.
 */
object WindowSyncHelpers {
    
    private const val TAG = "WindowSyncHelpers"
    private val mainHandler = Handler(Looper.getMainLooper())
    
    /**
     * Synchronize the window count from a paginator to LiveData and notify the adapter.
     * 
     * This method ensures that:
     * 1. The LiveData is updated on the main thread
     * 2. The adapter is notified of the data change
     * 3. Logging is performed for debugging
     * 
     * @param paginator The SlidingWindowPaginator containing the window count
     * @param windowCountLiveData The LiveData to update with the new window count
     * @param notifyAdapterCallback Optional callback to notify the adapter (runs on main thread)
     */
    fun syncWindowCountToUi(
        paginator: SlidingWindowPaginator,
        windowCountLiveData: MutableLiveData<Int>,
        notifyAdapterCallback: (() -> Unit)? = null
    ) {
        val windowCount = paginator.getWindowCount()
        val totalChapters = paginator.getTotalChapters()
        val chaptersPerWindow = paginator.getChaptersPerWindow()
        val previousValue = windowCountLiveData.value ?: 0
        
        AppLogger.d(TAG, "[PAGINATION_DEBUG] syncWindowCountToUi: " +
            "windowCount=$previousValue->$windowCount, " +
            "totalChapters=$totalChapters, " +
            "chaptersPerWindow=$chaptersPerWindow, " +
            "isMainThread=${Looper.myLooper() == Looper.getMainLooper()}")
        
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // Already on main thread
            windowCountLiveData.value = windowCount
            notifyAdapterCallback?.invoke()
            AppLogger.d(TAG, "[PAGINATION_DEBUG] syncWindowCountToUi: DIRECT update completed, windowCount=$windowCount")
        } else {
            // Post to main thread
            AppLogger.d(TAG, "[PAGINATION_DEBUG] syncWindowCountToUi: Posting to main thread")
            mainHandler.post {
                val currentPaginatorWindowCount = paginator.getWindowCount()
                windowCountLiveData.value = currentPaginatorWindowCount
                notifyAdapterCallback?.invoke()
                AppLogger.d(TAG, "[PAGINATION_DEBUG] syncWindowCountToUi: POSTED update completed, windowCount=$currentPaginatorWindowCount")
            }
        }
    }
    
    /**
     * Synchronize the window count from a paginator to a StateFlow-compatible callback.
     * 
     * Use this when working with Kotlin StateFlow instead of LiveData.
     * 
     * @param paginator The SlidingWindowPaginator containing the window count
     * @param updateCallback Callback to update the window count value (called on main thread)
     * @param notifyAdapterCallback Optional callback to notify the adapter (runs on main thread)
     */
    fun syncWindowCountToUiFlow(
        paginator: SlidingWindowPaginator,
        updateCallback: (Int) -> Unit,
        notifyAdapterCallback: (() -> Unit)? = null
    ) {
        val windowCount = paginator.getWindowCount()
        val totalChapters = paginator.getTotalChapters()
        val chaptersPerWindow = paginator.getChaptersPerWindow()
        
        AppLogger.d(TAG, "[PAGINATION_DEBUG] syncWindowCountToUiFlow: " +
            "windowCount=$windowCount, " +
            "totalChapters=$totalChapters, " +
            "chaptersPerWindow=$chaptersPerWindow, " +
            "isMainThread=${Looper.myLooper() == Looper.getMainLooper()}")
        
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // Already on main thread
            updateCallback(windowCount)
            notifyAdapterCallback?.invoke()
            AppLogger.d(TAG, "[PAGINATION_DEBUG] syncWindowCountToUiFlow: DIRECT callback completed, windowCount=$windowCount")
        } else {
            // Post to main thread
            AppLogger.d(TAG, "[PAGINATION_DEBUG] syncWindowCountToUiFlow: Posting callback to main thread")
            mainHandler.post {
                val currentPaginatorWindowCount = paginator.getWindowCount()
                updateCallback(currentPaginatorWindowCount)
                notifyAdapterCallback?.invoke()
                AppLogger.d(TAG, "[PAGINATION_DEBUG] syncWindowCountToUiFlow: POSTED callback completed, windowCount=$currentPaginatorWindowCount")
            }
        }
    }
    
    /**
     * Log detailed window sync state for debugging.
     * Call this when troubleshooting window count synchronization issues.
     * 
     * @param paginator The SlidingWindowPaginator to inspect
     * @param context Description of the calling context (e.g., "after TOC navigation")
     */
    fun logWindowSyncState(paginator: SlidingWindowPaginator, context: String) {
        AppLogger.d(TAG, "[PAGINATION_DEBUG] Window sync state ($context): " +
            "windowCount=${paginator.getWindowCount()}, " +
            "totalChapters=${paginator.getTotalChapters()}, " +
            "chaptersPerWindow=${paginator.getChaptersPerWindow()}, " +
            "isMainThread=${Looper.myLooper() == Looper.getMainLooper()}")
        AppLogger.d(TAG, "[PAGINATION_DEBUG] Window map: ${paginator.debugWindowMap()}")
    }
}
