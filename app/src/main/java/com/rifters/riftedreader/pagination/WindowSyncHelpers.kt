package com.rifters.riftedreader.pagination

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.MutableLiveData

/**
 * Helper functions for synchronizing window count updates to the UI thread.
 * 
 * These helpers ensure that ViewPager/ViewPager2 adapter updates happen on the
 * main thread and are properly synchronized with the paginator state.
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
        
        Log.d(TAG, "syncWindowCountToUi: windowCount=$windowCount, totalChapters=$totalChapters, chaptersPerWindow=$chaptersPerWindow")
        
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // Already on main thread
            windowCountLiveData.value = windowCount
            notifyAdapterCallback?.invoke()
            Log.d(TAG, "Updated windowCountLiveData on main thread: $windowCount")
        } else {
            // Post to main thread
            mainHandler.post {
                windowCountLiveData.value = windowCount
                notifyAdapterCallback?.invoke()
                Log.d(TAG, "Updated windowCountLiveData via Handler post: $windowCount")
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
        
        Log.d(TAG, "syncWindowCountToUiFlow: windowCount=$windowCount, totalChapters=$totalChapters, chaptersPerWindow=$chaptersPerWindow")
        
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // Already on main thread
            updateCallback(windowCount)
            notifyAdapterCallback?.invoke()
            Log.d(TAG, "Updated via callback on main thread: $windowCount")
        } else {
            // Post to main thread
            mainHandler.post {
                updateCallback(windowCount)
                notifyAdapterCallback?.invoke()
                Log.d(TAG, "Updated via callback via Handler post: $windowCount")
            }
        }
    }
}
