package com.rifters.riftedreader.ui.reader

import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.RecyclerView
import com.rifters.riftedreader.R
import com.rifters.riftedreader.util.AppLogger

/**
 * Adapter for the RecyclerView in the reader screen.
 * 
 * In continuous mode, each page represents a window containing multiple chapters.
 * In chapter-based mode, each page represents a single chapter.
 * 
 * **Sliding Window Pagination:**
 * - Each window contains exactly 5 chapters (DEFAULT_CHAPTERS_PER_WINDOW)
 * - The adapter's itemCount equals the total window count
 * - Window index = adapter position
 * 
 * Uses FragmentManager to manage fragment lifecycle within RecyclerView items.
 * Fragment tag format: "f{position}" (e.g., "f0", "f1", "f2")
 * 
 * Debug logging is included at key lifecycle points for pagination debugging:
 * - getItemCount: logs window count
 * - onBindViewHolder: logs HTML binding to WebView
 * - notifyDataSetChanged: logs adapter state after update
 * - All logs are written to session_log_*.txt
 */
class ReaderPagerAdapter(
    private val activity: FragmentActivity,
    private val viewModel: ReaderViewModel
) : RecyclerView.Adapter<ReaderPagerAdapter.PageViewHolder>() {

    private val fragmentManager: FragmentManager = activity.supportFragmentManager
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // Track last known item count for debugging adapter updates
    private var lastKnownItemCount: Int = 0
    
    // Track active fragments for debugging
    private val activeFragments = mutableSetOf<Int>()
    
    // Flag to track if window count mismatch warning has been logged (emit only once per session)
    private var windowMismatchWarningLogged: Boolean = false

    override fun getItemCount(): Int {
        // ISSUE 1 FIX: Derive window count from SlidingWindowPaginator's window count, NOT from TOC length
        // This ensures adapter/VM use the same source of truth for chapter count
        val count = viewModel.slidingWindowPaginator.getWindowCount()
        
        // [PAGINATION_DEBUG] Log window count changes with detailed context
        if (count != lastKnownItemCount) {
            AppLogger.d("ReaderPagerAdapter", "[PAGINATION_DEBUG] getItemCount CHANGED: " +
                "$lastKnownItemCount -> $count windows, " +
                "paginationMode=${viewModel.paginationMode}, " +
                "chaptersPerWindow=${viewModel.chaptersPerWindow}")
            lastKnownItemCount = count
        }
        
        // [PAGINATION_DEBUG] Warn if zero windows (pagination may have failed)
        if (count == 0) {
            AppLogger.w("ReaderPagerAdapter", "[PAGINATION_DEBUG] WARNING: getItemCount=0 " +
                "- book content may not have loaded or pagination failed")
        }
        
        // [PAGINATION_DEBUG] Validate window count matches expected calculation (emit warning only once per session)
        // Use visible chapter count (accounts for hidden chapters like cover, NAV, non-linear)
        val visibleChapterCount = viewModel.visibleChapterCount
        val spineCount = viewModel.chapterIndexProvider.spineCount
        if (visibleChapterCount > 0 && viewModel.isContinuousMode && !windowMismatchWarningLogged) {
            val expectedWindows = kotlin.math.ceil(visibleChapterCount.toDouble() / viewModel.chaptersPerWindow).toInt()
            if (count != expectedWindows && count > 0) {
                // Log both actual and expected counts in a single diagnostic message
                AppLogger.w("ReaderPagerAdapter", "[PAGINATION_DEBUG] WINDOW_COUNT_MISMATCH (one-time): " +
                    "actual=$count, expected=$expectedWindows based on visible chapters " +
                    "(visibleChapters=$visibleChapterCount, spineAll=$spineCount, perWindow=${viewModel.chaptersPerWindow})")
                windowMismatchWarningLogged = true
            }
        }
        
        return count
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_reader_page, parent, false)
        
        AppLogger.d("ReaderPagerAdapter", "[PAGINATION_DEBUG] onCreateViewHolder: " +
            "parentWidth=${parent.width}, parentHeight=${parent.height}")
        
        return PageViewHolder(view as ViewGroup)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        val totalWindows = viewModel.windowCount.value
        val currentWindowIndex = viewModel.currentWindowIndex.value
        
        // [PAGINATION_DEBUG] Enhanced binding logging
        AppLogger.d("ReaderPagerAdapter", "[PAGINATION_DEBUG] onBindViewHolder: " +
            "position=$position (windowIndex), " +
            "totalWindows=$totalWindows, " +
            "currentWindowIndex=$currentWindowIndex, " +
            "containerWidth=${holder.containerView.width}, " +
            "containerHeight=${holder.containerView.height}")
        
        val fragmentTag = "f$position"
        
        // Check if fragment already exists for this position
        val existingFragment = fragmentManager.findFragmentByTag(fragmentTag)
        
        if (existingFragment != null && existingFragment.isAdded) {
            // Fragment already exists and is added, no need to recreate
            AppLogger.d("ReaderPagerAdapter", "[PAGINATION_DEBUG] Fragment REUSED: " +
                "position=$position, fragmentTag=$fragmentTag")
            return
        }
        
        // Create new fragment for this position
        val fragment = ReaderPageFragment.newInstance(position)
        AppLogger.d("ReaderPagerAdapter", "[PAGINATION_DEBUG] Fragment CREATING: " +
            "position=$position (windowIndex=$position), fragmentTag=$fragmentTag")
        
        // Capture holder position for validation in the posted transaction
        val holderPosition = position
        
        // Post the transaction to ensure we're not in a layout pass
        mainHandler.post {
            if (!activity.isFinishing && !activity.isDestroyed) {
                // Verify holder still represents the same position
                if (holder.adapterPosition == holderPosition) {
                    fragmentManager.beginTransaction().apply {
                        // Remove any existing fragment if present but not properly cleaned up
                        fragmentManager.findFragmentByTag(fragmentTag)?.let { 
                            AppLogger.d("ReaderPagerAdapter", "[PAGINATION_DEBUG] Removing stale fragment: " +
                                "fragmentTag=$fragmentTag")
                            remove(it) 
                        }
                        add(holder.containerView.id, fragment, fragmentTag)
                    }.commitAllowingStateLoss()
                    
                    // Track active fragment
                    activeFragments.add(position)
                    
                    AppLogger.d("ReaderPagerAdapter", "[PAGINATION_DEBUG] Fragment COMMITTED: " +
                        "position=$position, containerId=${holder.containerView.id}, " +
                        "activeFragments=${activeFragments.size}")
                } else {
                    AppLogger.d("ReaderPagerAdapter", "[PAGINATION_DEBUG] Fragment SKIPPED (position changed): " +
                        "original=$holderPosition, current=${holder.adapterPosition}")
                }
            }
        }
    }

    override fun onViewRecycled(holder: PageViewHolder) {
        super.onViewRecycled(holder)
        val position = holder.adapterPosition
        
        AppLogger.d("ReaderPagerAdapter", "[PAGINATION_DEBUG] onViewRecycled: position=$position")
        
        // Only remove fragment if position is valid
        if (position != RecyclerView.NO_POSITION) {
            val fragmentTag = "f$position"
            val fragment = fragmentManager.findFragmentByTag(fragmentTag)
            if (fragment != null && fragment.isAdded) {
                // Post to ensure we're not in a layout pass
                mainHandler.post {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        // Re-check fragment state after post
                        val currentFragment = fragmentManager.findFragmentByTag(fragmentTag)
                        if (currentFragment != null && currentFragment.isAdded) {
                            fragmentManager.beginTransaction()
                                .remove(currentFragment)
                                .commitAllowingStateLoss()
                            
                            // Track removal
                            activeFragments.remove(position)
                            
                            AppLogger.d("ReaderPagerAdapter", "[PAGINATION_DEBUG] Fragment REMOVED: " +
                                "position=$position, activeFragments=${activeFragments.size}")
                        }
                    }
                }
            }
        }
    }

    override fun getItemId(position: Int): Long {
        // Each window has a unique ID based on position
        return position.toLong()
    }
    
    /**
     * Get the fragment at the given position if it exists.
     * Queries FragmentManager directly to ensure accurate state.
     */
    fun getFragmentAtPosition(position: Int): Fragment? {
        return fragmentManager.findFragmentByTag("f$position")
    }
    
    /**
     * Log adapter state after data set change for debugging.
     * Call this method after notifyDataSetChanged to track pagination updates.
     */
    fun logAdapterStateAfterUpdate(caller: String) {
        val itemCount = itemCount
        val windowCount = viewModel.windowCount.value
        val paginationMode = viewModel.paginationMode
        val chaptersPerWindow = viewModel.chaptersPerWindow
        
        AppLogger.d("ReaderPagerAdapter", 
            "[PAGINATION_DEBUG] Adapter state after update (caller=$caller): " +
            "itemCount=$itemCount, " +
            "windowCount=$windowCount, " +
            "paginationMode=$paginationMode, " +
            "chaptersPerWindow=$chaptersPerWindow, " +
            "activeFragments=${activeFragments.size}"
        )
        
        // [PAGINATION_DEBUG] Log mismatch warning
        if (itemCount != windowCount) {
            AppLogger.e("ReaderPagerAdapter", 
                "[PAGINATION_DEBUG] ERROR: itemCount/windowCount MISMATCH: " +
                "itemCount=$itemCount, windowCount=$windowCount")
        }
        
        // [PAGINATION_DEBUG] Log zero items warning
        if (itemCount == 0 && windowCount > 0) {
            AppLogger.e("ReaderPagerAdapter", 
                "[PAGINATION_DEBUG] ERROR: itemCount=0 but windowCount=$windowCount - " +
                "adapter/viewModel mismatch!")
        }
        
        // Log success case
        if (itemCount > 0) {
            AppLogger.d("ReaderPagerAdapter", 
                "[PAGINATION_DEBUG] Adapter has $itemCount windows ready for display " +
                "(each with $chaptersPerWindow chapters)")
        }
    }
    
    /**
     * Get debug information about active fragments.
     * 
     * @return Debug info string
     */
    fun getActiveFragmentsDebugInfo(): String {
        return "ActiveFragments[count=${activeFragments.size}, positions=$activeFragments]"
    }
    
    /**
     * Clean up all fragments when adapter is detached.
     * Should only be called on the main thread.
     */
    fun cleanUp() {
        AppLogger.d("ReaderPagerAdapter", "[PAGINATION_DEBUG] cleanUp: " +
            "removing all fragments (activeFragments=${activeFragments.size})")
        
        if (activity.isFinishing || activity.isDestroyed) return
        
        val transaction = fragmentManager.beginTransaction()
        var hasFragmentsToRemove = false
        
        // Iterate through all fragments that match our tag pattern
        // Use a safe iteration approach by checking each position
        val count = try { itemCount } catch (e: Exception) { 0 }
        
        for (position in 0 until count) {
            val fragmentTag = "f$position"
            fragmentManager.findFragmentByTag(fragmentTag)?.let { 
                transaction.remove(it)
                hasFragmentsToRemove = true
            }
        }
        
        if (hasFragmentsToRemove) {
            transaction.commitAllowingStateLoss()
        }
        
        activeFragments.clear()
        AppLogger.d("ReaderPagerAdapter", "[PAGINATION_DEBUG] cleanUp: completed")
    }

    init {
        // Enable stable IDs for better item animations
        setHasStableIds(true)
        
        AppLogger.d("ReaderPagerAdapter", "[PAGINATION_DEBUG] Adapter initialized: " +
            "paginationMode=${viewModel.paginationMode}, " +
            "chaptersPerWindow=${viewModel.chaptersPerWindow}")
    }

    class PageViewHolder(val containerView: ViewGroup) : RecyclerView.ViewHolder(containerView)
}
