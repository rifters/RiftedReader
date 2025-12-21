package com.rifters.riftedreader.ui.reader

import android.os.Bundle
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
 * **Sliding Window Pagination with Buffer Management:**
 * - Each window contains exactly 5 chapters (DEFAULT_CHAPTERS_PER_WINDOW)
 * - The adapter manages a sliding 5-window buffer (BUFFER_SIZE = 5)
 * - Adapter's itemCount returns the buffer size (5), not the total window count
 * - Window index = adapter position within the buffer
 * - ConveyorBeltSystemViewModel manages the actual buffer and window shifting
 * 
 * Uses FragmentManager to manage fragment lifecycle within RecyclerView items.
 * Fragment tag format: "f{position}" (e.g., "f0", "f1", "f2")
 * 
 * Debug logging is included at key lifecycle points for pagination debugging:
 * - getItemCount: logs buffer size
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
        // Adapter manages a sliding 5-window buffer
        // The adapter's itemCount is the buffer size (5), not the total window count
        // ConveyorBeltSystemViewModel manages the buffer and window count tracking
        val bufferSize = 5
        
        // [PAGINATION_DEBUG] Log buffer size
        if (bufferSize != lastKnownItemCount) {
            AppLogger.d("ReaderPagerAdapter", "[ADAPTER] Adapter managing buffer of $bufferSize windows")
            lastKnownItemCount = bufferSize
        }
        
        return bufferSize
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_reader_page, parent, false)
        
        AppLogger.d("ReaderPagerAdapter", "[PAGINATION_DEBUG] onCreateViewHolder: " +
            "parentWidth=${parent.width}, parentHeight=${parent.height}")
        
        return PageViewHolder(view as ViewGroup)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int, payloads: List<Any>) {
        if (payloads.isNotEmpty()) {
            // Payload update: just refresh window content without full rebind
            val bundle = payloads.firstOrNull() as? Bundle
            if (bundle != null) {
                val activeWindow = bundle.getInt("activeWindow", -1)
                AppLogger.d("ReaderPagerAdapter", "[PAYLOAD_UPDATE] Updating fragment at position $position with activeWindow=$activeWindow")
                
                // Get the fragment and update its window index
                val fragmentTag = "f$position"
                val fragment = fragmentManager.findFragmentByTag(fragmentTag) as? ReaderPageFragment
                if (fragment != null) {
                    fragment.updateWindowIndex(activeWindow)
                    AppLogger.d("ReaderPagerAdapter", "[PAYLOAD_UPDATE] Fragment updated: position=$position, activeWindow=$activeWindow")
                } else {
                    AppLogger.w("ReaderPagerAdapter", "[PAYLOAD_UPDATE] Fragment not found for position=$position, falling back to full bind")
                    onBindViewHolder(holder, position)
                }
            } else {
                AppLogger.w("ReaderPagerAdapter", "[PAYLOAD_UPDATE] Invalid payload (not a Bundle), falling back to full bind")
                onBindViewHolder(holder, position)
            }
            return
        }
        
        // Fall back to full bind if no payload
        onBindViewHolder(holder, position)
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
                "position=$position, fragmentTag=$fragmentTag, isAdded=${existingFragment.isAdded}, " +
                "isVisible=${existingFragment.isVisible}")
            return
        }
        
        // Create new fragment for this position
        val fragment = ReaderPageFragment.newInstance(position)
        AppLogger.d("ReaderPagerAdapter", "[PAGINATION_DEBUG] Fragment CREATING: " +
            "position=$position (windowIndex=$position), fragmentTag=$fragmentTag, " +
            "activeFragments=${activeFragments.size}")
        
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
                        "activeFragments=${activeFragments.size}, fragmentTag=$fragmentTag [FRAGMENT_ADDED]")
                } else {
                    AppLogger.w("ReaderPagerAdapter", "[PAGINATION_DEBUG] Fragment SKIPPED (position changed): " +
                        "original=$holderPosition, current=${holder.adapterPosition} [POSITION_CHANGED]")
                }
            } else {
                AppLogger.w("ReaderPagerAdapter", "[PAGINATION_DEBUG] Fragment SKIPPED (activity finishing): " +
                    "position=$holderPosition [ACTIVITY_FINISHING]")
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
     * 
     * Note: itemCount (buffer size = 5) and windowCount (total windows) are intentionally
     * different and should not match. The adapter manages a 5-window buffer while the
     * ViewModel tracks the total window count for navigation and progress.
     */
    fun logAdapterStateAfterUpdate(caller: String) {
        val itemCount = itemCount
        val windowCount = viewModel.windowCount.value
        val paginationMode = viewModel.paginationMode
        val chaptersPerWindow = viewModel.chaptersPerWindow
        
        AppLogger.d("ReaderPagerAdapter", 
            "[PAGINATION_DEBUG] Adapter state after update (caller=$caller): " +
            "itemCount=$itemCount (buffer size), " +
            "windowCount=$windowCount (total windows), " +
            "paginationMode=$paginationMode, " +
            "chaptersPerWindow=$chaptersPerWindow, " +
            "activeFragments=${activeFragments.size}"
        )
        
        // [PAGINATION_DEBUG] Log zero items warning
        if (itemCount == 0) {
            AppLogger.e("ReaderPagerAdapter", 
                "[PAGINATION_DEBUG] ERROR: itemCount=0 - adapter has no buffer capacity!")
        }
        
        // Log success case
        if (itemCount > 0) {
            AppLogger.d("ReaderPagerAdapter", 
                "[PAGINATION_DEBUG] Adapter has $itemCount buffer slots ready " +
                "(managing sliding window from total $windowCount windows)")
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
