package com.rifters.riftedreader.ui.reader

import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.rifters.riftedreader.R
import com.rifters.riftedreader.ui.reader.conveyor.ConveyorPhase
import com.rifters.riftedreader.util.AppLogger

/**
 * Adapter for the RecyclerView in the reader screen using ListAdapter with DiffUtil.
 * 
 * In continuous mode, each page represents a window containing multiple chapters.
 * In chapter-based mode, each page represents a single chapter.
 * 
 * **Sliding Window Pagination with Buffer Management:**
 * - Each window contains exactly 5 chapters (DEFAULT_CHAPTERS_PER_WINDOW)
 * - The adapter manages a sliding 5-window buffer (BUFFER_SIZE = 5)
 * - Adapter uses windowId (Int) from buffer as stable IDs
 * - ConveyorBeltSystemViewModel manages the actual buffer and window shifting
 * - submitList(bufferIds) drives all UI updates via DiffUtil
 * 
 * Uses FragmentManager to manage fragment lifecycle within RecyclerView items.
 * Fragment tag format: "w{windowId}" (e.g., "w0", "w1", "w2") - tagged by windowId not position
 * 
 * Debug logging is included at key lifecycle points for pagination debugging:
 * - getItemCount: logs buffer size
 * - onBindViewHolder: logs HTML binding to WebView
 * - submitList: logs adapter state after update
 * - All logs are written to session_log_*.txt
 */
class ReaderPagerAdapter(
    private val activity: FragmentActivity,
    private val viewModel: ReaderViewModel
) : ListAdapter<Int, ReaderPagerAdapter.PageViewHolder>(WindowIdDiffCallback()) {

    private val fragmentManager: FragmentManager = activity.supportFragmentManager
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // Track last known item count for debugging adapter updates
    private var lastKnownItemCount: Int = 0
    
    // Track active fragments for debugging
    private val activeFragments = mutableSetOf<Int>()
    
    // DiffUtil callback for comparing windowIds
    private class WindowIdDiffCallback : DiffUtil.ItemCallback<Int>() {
        override fun areItemsTheSame(oldItem: Int, newItem: Int): Boolean {
            // Items are the same if they have the same windowId
            return oldItem == newItem
        }
        
        override fun areContentsTheSame(oldItem: Int, newItem: Int): Boolean {
            // Content is always considered the same for a given windowId
            // (HTML content doesn't change for a windowId, only the mapping changes)
            return true
        }
    }
    
    /**
     * Get windowId at a specific adapter position.
     * @return windowId or -1 if position is out of bounds
     */
    fun getWindowIdAtPosition(position: Int): Int {
        return if (position in 0 until itemCount) {
            getItem(position)
        } else {
            -1
        }
    }
    
    /**
     * Get adapter position for a given windowId.
     * @return position or -1 if windowId not found in current list
     */
    fun getPositionForWindowId(windowId: Int): Int {
        return currentList.indexOf(windowId)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_reader_page, parent, false)
        
        AppLogger.d("ReaderPagerAdapter", "[PAGINATION_DEBUG] onCreateViewHolder: " +
            "parentWidth=${parent.width}, parentHeight=${parent.height}")
        
        return PageViewHolder(view as ViewGroup)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        // Get the windowId for this position from the list
        val windowId = getItem(position)
        val totalWindows = viewModel.windowCount.value
        
        // [BIND_BUFFER_SNAPSHOT] Enhanced binding logging with conveyor state
        AppLogger.d("ReaderPagerAdapter", "[BIND_BUFFER_SNAPSHOT] onBindViewHolder: "
 +
            "position=$position, windowId=$windowId, " +
            "totalWindows=$totalWindows, " +
            "activeWindow=${viewModel.conveyorBeltSystem?.activeWindow?.value}, " +
            "phase=${viewModel.conveyorBeltSystem?.phase?.value}, " +
            "buffer=${viewModel.conveyorBeltSystem?.buffer?.value}, " +
            "containerWidth=${holder.containerView.width}, " +
            "containerHeight=${holder.containerView.height}")
        
        // Use windowId for fragment tag (not position)
        val fragmentTag = "w$windowId"
        
        // Check if fragment already exists for this windowId
        val existingFragment = fragmentManager.findFragmentByTag(fragmentTag)
        
        if (existingFragment != null && existingFragment.isAdded) {
            // Fragment already exists and is added - this is a cache hit
            // No need to recreate, adapter just rebound the same windowId to this position
            AppLogger.d("ReaderPagerAdapter", "[BIND_BUFFER_SNAPSHOT] Fragment CACHE_HIT: " +
                "position=$position, windowId=$windowId, fragmentTag=$fragmentTag, isAdded=${existingFragment.isAdded}, " +
                "isVisible=${existingFragment.isVisible}")
            return
        }
        
        // Create new fragment for this windowId
        val fragment = ReaderPageFragment.newInstance(windowId)
        AppLogger.d("ReaderPagerAdapter", "[BIND_BUFFER_SNAPSHOT] Fragment CACHE_MISS: " +
            "position=$position, windowId=$windowId, fragmentTag=$fragmentTag, " +
            "activeFragments=${activeFragments.size}, creating new fragment")
        
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
                            AppLogger.d("ReaderPagerAdapter", "[BIND_BUFFER_SNAPSHOT] Removing stale fragment: " +
                                "fragmentTag=$fragmentTag")
                            remove(it) 
                        }
                        add(holder.containerView.id, fragment, fragmentTag)
                    }.commitAllowingStateLoss()
                    
                    // Track active fragment
                    activeFragments.add(windowId)
                    
                    AppLogger.d("ReaderPagerAdapter", "[BIND_BUFFER_SNAPSHOT] Fragment COMMITTED: " +
                        "position=$position, windowId=$windowId, containerId=${holder.containerView.id}, " +
                        "activeFragments=${activeFragments.size}, fragmentTag=$fragmentTag")
                } else {
                    AppLogger.w("ReaderPagerAdapter", "[BIND_BUFFER_SNAPSHOT] Fragment SKIPPED (position changed): " +
                        "original=$holderPosition, current=${holder.adapterPosition}")
                }
            } else {
                AppLogger.w("ReaderPagerAdapter", "[BIND_BUFFER_SNAPSHOT] Fragment SKIPPED (activity finishing): " +
                    "position=$holderPosition")
            }
        }
    }

    override fun onViewRecycled(holder: PageViewHolder) {
        super.onViewRecycled(holder)
        val position = holder.adapterPosition
        
        AppLogger.d("ReaderPagerAdapter", "[PAGINATION_DEBUG] onViewRecycled: position=$position")
        
        // Only remove fragment if position is valid
        if (position != RecyclerView.NO_POSITION && position < itemCount) {
            val windowId = getItem(position)
            val fragmentTag = "w$windowId"
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
                            activeFragments.remove(windowId)
                            
                            AppLogger.d("ReaderPagerAdapter", "[PAGINATION_DEBUG] Fragment REMOVED: " +
                                "position=$position, windowId=$windowId, activeFragments=${activeFragments.size}")
                        }
                    }
                }
            }
        }
    }

    override fun getItemId(position: Int): Long {
        // Use windowId as stable ID (not position)
        val windowId = getItem(position)
        return windowId.toLong()
    }
    
    /**
     * Get the fragment at the given position if it exists.
     * Queries FragmentManager directly to ensure accurate state.
     */
    fun getFragmentAtPosition(position: Int): Fragment? {
        if (position < 0 || position >= itemCount) return null
        val windowId = getItem(position)
        return fragmentManager.findFragmentByTag("w$windowId")
    }
    
    /**
     * Get the fragment for a given windowId if it exists.
     */
    fun getFragmentForWindowId(windowId: Int): Fragment? {
        return fragmentManager.findFragmentByTag("w$windowId")
    }
    
    /**
     * Get debug information about active fragments.
     * 
     * @return Debug info string
     */
    fun getActiveFragmentsDebugInfo(): String {
        return "ActiveFragments[count=${activeFragments.size}, windowIds=$activeFragments]"
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
        
        // Iterate through all windowIds in the current list
        currentList.forEach { windowId ->
            val fragmentTag = "w$windowId"
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
