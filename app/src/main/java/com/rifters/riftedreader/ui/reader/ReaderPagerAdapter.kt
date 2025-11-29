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
 * Uses FragmentManager to manage fragment lifecycle within RecyclerView items.
 * Fragment tag format: "f{position}" (e.g., "f0", "f1", "f2")
 * 
 * Debug logging is included at key lifecycle points for pagination debugging:
 * - getItemCount: logs window count
 * - onBindViewHolder: logs HTML binding to WebView
 * - notifyDataSetChanged: logs adapter state after update
 */
class ReaderPagerAdapter(
    private val activity: FragmentActivity,
    private val viewModel: ReaderViewModel
) : RecyclerView.Adapter<ReaderPagerAdapter.PageViewHolder>() {

    private val fragmentManager: FragmentManager = activity.supportFragmentManager
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // Track last known item count for debugging adapter updates
    private var lastKnownItemCount: Int = 0

    override fun getItemCount(): Int {
        val count = viewModel.windowCount.value
        // [PAGINATION_DEBUG] Log window count only when it changes to avoid performance impact
        if (count != lastKnownItemCount) {
            AppLogger.d("ReaderPagerAdapter", "[PAGINATION_DEBUG] getItemCount changed: $lastKnownItemCount -> $count (windows)")
            lastKnownItemCount = count
        }
        
        // [FALLBACK] If zero windows, log warning for debugging
        if (count == 0) {
            AppLogger.w("ReaderPagerAdapter", "[PAGINATION_DEBUG] WARNING: Zero windows returned from getItemCount - book may not have loaded content")
        }
        
        return count
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_reader_page, parent, false)
        return PageViewHolder(view as ViewGroup)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        // [PAGINATION_DEBUG] Log binding with full context
        val totalWindows = viewModel.windowCount.value
        AppLogger.d("ReaderPagerAdapter", "[PAGINATION_DEBUG] onBindViewHolder: position=$position, totalWindows=$totalWindows, containerWidth=${holder.containerView.width}")
        
        val fragmentTag = "f$position"
        
        // Check if fragment already exists for this position
        val existingFragment = fragmentManager.findFragmentByTag(fragmentTag)
        
        if (existingFragment != null && existingFragment.isAdded) {
            // Fragment already exists and is added, no need to recreate
            AppLogger.d("ReaderPagerAdapter", "[PAGINATION_DEBUG] Fragment already exists for position=$position, reusing")
            return
        }
        
        // Create new fragment for this position
        val fragment = ReaderPageFragment.newInstance(position)
        AppLogger.d("ReaderPagerAdapter", "[PAGINATION_DEBUG] Creating new fragment for position=$position (windowIndex=$position)")
        
        // Capture holder position for validation in the posted transaction
        val holderPosition = position
        
        // Post the transaction to ensure we're not in a layout pass
        mainHandler.post {
            if (!activity.isFinishing && !activity.isDestroyed) {
                // Verify holder still represents the same position
                if (holder.adapterPosition == holderPosition) {
                    fragmentManager.beginTransaction().apply {
                        // Remove any existing fragment if present but not properly cleaned up
                        fragmentManager.findFragmentByTag(fragmentTag)?.let { remove(it) }
                        add(holder.containerView.id, fragment, fragmentTag)
                    }.commitAllowingStateLoss()
                    
                    AppLogger.d("ReaderPagerAdapter", "[PAGINATION_DEBUG] Fragment committed for position=$position, containerId=${holder.containerView.id}")
                } else {
                    AppLogger.d("ReaderPagerAdapter", "[PAGINATION_DEBUG] Holder position changed, skipping fragment creation for position=$position (was $holderPosition, now ${holder.adapterPosition})")
                }
            }
        }
    }

    override fun onViewRecycled(holder: PageViewHolder) {
        super.onViewRecycled(holder)
        val position = holder.adapterPosition
        AppLogger.d("ReaderPagerAdapter", "onViewRecycled: position=$position")
        
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
                            AppLogger.d("ReaderPagerAdapter", "Removed fragment for position=$position")
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
        
        AppLogger.d("ReaderPagerAdapter", 
            "[PAGINATION_DEBUG] Adapter state after update (caller=$caller): " +
            "itemCount=$itemCount, windowCount=$windowCount, paginationMode=$paginationMode"
        )
        
        // [FALLBACK] Log warning if adapter has zero items but viewModel has windows
        if (itemCount == 0 && windowCount > 0) {
            AppLogger.e("ReaderPagerAdapter", 
                "[PAGINATION_DEBUG] ERROR: itemCount=0 but windowCount=$windowCount - adapter/viewModel mismatch!"
            )
        }
        
        // Log if adapter successfully has content
        if (itemCount > 0) {
            AppLogger.d("ReaderPagerAdapter", 
                "[PAGINATION_DEBUG] Adapter has $itemCount items ready for display"
            )
        }
    }
    
    /**
     * Clean up all fragments when adapter is detached.
     * Should only be called on the main thread.
     */
    fun cleanUp() {
        AppLogger.d("ReaderPagerAdapter", "[PAGINATION_DEBUG] cleanUp called - removing all fragments")
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
    }

    init {
        // Enable stable IDs for better item animations
        setHasStableIds(true)
    }

    class PageViewHolder(val containerView: ViewGroup) : RecyclerView.ViewHolder(containerView)
}
