package com.rifters.riftedreader.domain.pagination

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Helpers for atomically syncing paginator state to UI components.
 *
 * These helpers ensure that window count updates are performed atomically on the
 * main thread to prevent race conditions between state changes and adapter updates.
 */
object WindowSyncHelpers {

    private const val TAG = "WindowSyncHelpers"
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Atomically sync the paginator's windowCount to a MutableLiveData and notify
     * the adapter on the main thread.
     *
     * This method ensures:
     * 1. The LiveData update happens on the main thread
     * 2. The adapter.notifyDataSetChanged() is called after the LiveData update
     * 3. Both operations happen atomically within the same main thread dispatch
     *
     * @param paginator The SlidingWindowPaginator to read windowCount from
     * @param windowCountLiveData The MutableLiveData to update
     * @param adapter The RecyclerView.Adapter to notify (typically a ViewPager2 adapter)
     */
    fun syncWindowCountToUi(
        paginator: SlidingWindowPaginator,
        windowCountLiveData: MutableLiveData<Int>,
        adapter: RecyclerView.Adapter<*>
    ) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // Already on main thread, read and execute directly
            val windowCount = paginator.windowCount
            Log.d(TAG, "syncWindowCountToUi: direct update with windowCount=$windowCount")
            performSync(windowCount, windowCountLiveData, adapter)
        } else {
            // Post to main thread - read inside the runnable to avoid stale data
            Log.d(TAG, "syncWindowCountToUi: scheduling update on main thread")
            mainHandler.post {
                val windowCount = paginator.windowCount
                Log.d(TAG, "syncWindowCountToUi: executing update with windowCount=$windowCount")
                performSync(windowCount, windowCountLiveData, adapter)
            }
        }
    }

    /**
     * Atomically sync the paginator's windowCount to a MutableStateFlow and notify
     * the adapter on the main thread.
     *
     * This variant uses Kotlin StateFlow instead of LiveData.
     *
     * @param paginator The SlidingWindowPaginator to read windowCount from
     * @param windowCountStateFlow The MutableStateFlow to update
     * @param adapter The RecyclerView.Adapter to notify (typically a ViewPager2 adapter)
     */
    fun syncWindowCountToUiStateFlow(
        paginator: SlidingWindowPaginator,
        windowCountStateFlow: MutableStateFlow<Int>,
        adapter: RecyclerView.Adapter<*>
    ) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // Already on main thread, read and execute directly
            val windowCount = paginator.windowCount
            Log.d(TAG, "syncWindowCountToUiStateFlow: direct update with windowCount=$windowCount")
            performSyncStateFlow(windowCount, windowCountStateFlow, adapter)
        } else {
            // Post to main thread - read inside the runnable to avoid stale data
            Log.d(TAG, "syncWindowCountToUiStateFlow: scheduling update on main thread")
            mainHandler.post {
                val windowCount = paginator.windowCount
                Log.d(TAG, "syncWindowCountToUiStateFlow: executing update with windowCount=$windowCount")
                performSyncStateFlow(windowCount, windowCountStateFlow, adapter)
            }
        }
    }

    /**
     * Convenience method to sync just the StateFlow without adapter notification.
     * Useful when the adapter already observes the StateFlow for changes.
     *
     * @param paginator The SlidingWindowPaginator to read windowCount from
     * @param windowCountStateFlow The MutableStateFlow to update
     */
    fun syncWindowCountOnly(
        paginator: SlidingWindowPaginator,
        windowCountStateFlow: MutableStateFlow<Int>
    ) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            val windowCount = paginator.windowCount
            Log.d(TAG, "syncWindowCountOnly: direct update with windowCount=$windowCount")
            windowCountStateFlow.value = windowCount
        } else {
            Log.d(TAG, "syncWindowCountOnly: scheduling update on main thread")
            mainHandler.post {
                val windowCount = paginator.windowCount
                Log.d(TAG, "syncWindowCountOnly: executing update with windowCount=$windowCount")
                windowCountStateFlow.value = windowCount
            }
        }
    }

    private fun performSync(
        windowCount: Int,
        liveData: MutableLiveData<Int>,
        adapter: RecyclerView.Adapter<*>
    ) {
        val oldValue = liveData.value ?: 0
        liveData.value = windowCount
        Log.d(TAG, "performSync: LiveData updated $oldValue -> $windowCount, notifying adapter")
        adapter.notifyDataSetChanged()
    }

    private fun performSyncStateFlow(
        windowCount: Int,
        stateFlow: MutableStateFlow<Int>,
        adapter: RecyclerView.Adapter<*>
    ) {
        val oldValue = stateFlow.value
        stateFlow.value = windowCount
        Log.d(TAG, "performSyncStateFlow: StateFlow updated $oldValue -> $windowCount, notifying adapter")
        adapter.notifyDataSetChanged()
    }
}
