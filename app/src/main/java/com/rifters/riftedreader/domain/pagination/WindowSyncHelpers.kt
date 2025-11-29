package com.rifters.riftedreader.domain.pagination

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.MutableLiveData
import androidx.recyclerview.widget.RecyclerView
import com.rifters.riftedreader.util.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Helpers for atomically syncing paginator state to UI components.
 *
 * These helpers ensure that window count updates are performed atomically on the
 * main thread to prevent race conditions between state changes and adapter updates.
 * 
 * All synchronization operations are logged to session_log for debugging.
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
     * @param adapter The RecyclerView.Adapter to notify
     */
    fun syncWindowCountToUi(
        paginator: SlidingWindowPaginator,
        windowCountLiveData: MutableLiveData<Int>,
        adapter: RecyclerView.Adapter<*>
    ) {
        val previousValue = windowCountLiveData.value ?: 0
        
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // Already on main thread, read and execute directly
            val windowCount = paginator.windowCount
            AppLogger.d(TAG, "[PAGINATION_DEBUG] syncWindowCountToUi: DIRECT " +
                "windowCount=$previousValue->$windowCount, " +
                "totalChapters=${paginator.getTotalChapters()}, " +
                "chaptersPerWindow=${paginator.getChaptersPerWindow()}")
            performSync(windowCount, windowCountLiveData, adapter)
        } else {
            // Post to main thread - read inside the runnable to avoid stale data
            AppLogger.d(TAG, "[PAGINATION_DEBUG] syncWindowCountToUi: POSTING to main thread " +
                "(current windowCount=$previousValue)")
            mainHandler.post {
                val windowCount = paginator.windowCount
                AppLogger.d(TAG, "[PAGINATION_DEBUG] syncWindowCountToUi: POSTED update " +
                    "windowCount=$previousValue->$windowCount")
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
     * @param adapter The RecyclerView.Adapter to notify
     */
    fun syncWindowCountToUiStateFlow(
        paginator: SlidingWindowPaginator,
        windowCountStateFlow: MutableStateFlow<Int>,
        adapter: RecyclerView.Adapter<*>
    ) {
        val previousValue = windowCountStateFlow.value
        
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // Already on main thread, read and execute directly
            val windowCount = paginator.windowCount
            AppLogger.d(TAG, "[PAGINATION_DEBUG] syncWindowCountToUiStateFlow: DIRECT " +
                "windowCount=$previousValue->$windowCount, " +
                "totalChapters=${paginator.getTotalChapters()}, " +
                "chaptersPerWindow=${paginator.getChaptersPerWindow()}")
            performSyncStateFlow(windowCount, windowCountStateFlow, adapter)
        } else {
            // Post to main thread - read inside the runnable to avoid stale data
            AppLogger.d(TAG, "[PAGINATION_DEBUG] syncWindowCountToUiStateFlow: POSTING to main thread " +
                "(current windowCount=$previousValue)")
            mainHandler.post {
                val windowCount = paginator.windowCount
                AppLogger.d(TAG, "[PAGINATION_DEBUG] syncWindowCountToUiStateFlow: POSTED update " +
                    "windowCount=$previousValue->$windowCount")
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
        val previousValue = windowCountStateFlow.value
        
        if (Looper.myLooper() == Looper.getMainLooper()) {
            val windowCount = paginator.windowCount
            AppLogger.d(TAG, "[PAGINATION_DEBUG] syncWindowCountOnly: DIRECT " +
                "windowCount=$previousValue->$windowCount")
            windowCountStateFlow.value = windowCount
        } else {
            AppLogger.d(TAG, "[PAGINATION_DEBUG] syncWindowCountOnly: POSTING to main thread")
            mainHandler.post {
                val windowCount = paginator.windowCount
                AppLogger.d(TAG, "[PAGINATION_DEBUG] syncWindowCountOnly: POSTED update " +
                    "windowCount=$previousValue->$windowCount")
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
        AppLogger.d(TAG, "[PAGINATION_DEBUG] performSync: LiveData $oldValue->$windowCount, notifying adapter")
        adapter.notifyDataSetChanged()
        AppLogger.d(TAG, "[PAGINATION_DEBUG] performSync: adapter.notifyDataSetChanged() called, " +
            "itemCount=${adapter.itemCount}")
    }

    private fun performSyncStateFlow(
        windowCount: Int,
        stateFlow: MutableStateFlow<Int>,
        adapter: RecyclerView.Adapter<*>
    ) {
        val oldValue = stateFlow.value
        stateFlow.value = windowCount
        AppLogger.d(TAG, "[PAGINATION_DEBUG] performSyncStateFlow: StateFlow $oldValue->$windowCount, notifying adapter")
        adapter.notifyDataSetChanged()
        AppLogger.d(TAG, "[PAGINATION_DEBUG] performSyncStateFlow: adapter.notifyDataSetChanged() called, " +
            "itemCount=${adapter.itemCount}")
    }
    
    /**
     * Log detailed sync state for debugging window count issues.
     * 
     * @param paginator The SlidingWindowPaginator to inspect
     * @param context Description of the calling context
     */
    fun logSyncState(paginator: SlidingWindowPaginator, context: String) {
        AppLogger.d(TAG, "[PAGINATION_DEBUG] Sync state ($context): " +
            "windowCount=${paginator.windowCount}, " +
            "totalChapters=${paginator.getTotalChapters()}, " +
            "chaptersPerWindow=${paginator.getChaptersPerWindow()}, " +
            "isMainThread=${Looper.myLooper() == Looper.getMainLooper()}")
        AppLogger.d(TAG, "[PAGINATION_DEBUG] Window map: ${paginator.debugWindowMap()}")
    }
}
