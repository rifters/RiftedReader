package com.rifters.riftedreader.pagination

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.recyclerview.widget.RecyclerView

/**
 * Helper functions for synchronizing window count to UI components.
 *
 * These helpers ensure thread-safe updates to LiveData and RecyclerView adapters
 * by posting to the main thread.
 */
object WindowSyncHelpers {

    private const val TAG = "WindowSyncHelpers"
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Synchronize the window count from paginator to UI components.
     *
     * This function:
     * 1. Posts to main thread using Handler(Looper.getMainLooper())
     * 2. Sets the LiveData value
     * 3. Notifies the adapter of data changes
     *
     * @param paginator The SlidingWindowPaginator to read window count from
     * @param windowCountLiveData The LiveData to update with window count
     * @param adapter The RecyclerView adapter to notify of changes
     */
    fun syncWindowCountToUi(
        paginator: SlidingWindowPaginator,
        windowCountLiveData: MutableLiveData<Int>,
        adapter: RecyclerView.Adapter<*>
    ) {
        val windowCount = paginator.windowCount
        Log.d(TAG, "syncWindowCountToUi: windowCount=$windowCount")

        mainHandler.post {
            Log.d(TAG, "syncWindowCountToUi (main thread): setting windowCount=$windowCount")
            windowCountLiveData.value = windowCount
            adapter.notifyDataSetChanged()
        }
    }
}
