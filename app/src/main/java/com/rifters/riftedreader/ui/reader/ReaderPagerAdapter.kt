package com.rifters.riftedreader.ui.reader

import android.view.View
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

        // CRITICAL: RecyclerView items all inflate the same layout ID. For FragmentManager transactions,
        // the container view ID must be unique per holder instance, otherwise fragments may attach
        // to the wrong page and render blank.
        if (view.id == View.NO_ID) {
            view.id = View.generateViewId()
        } else {
            // Even if an ID exists in XML, it will be duplicated across holders. Replace with unique.
            view.id = View.generateViewId()
        }
        
        AppLogger.d("ReaderPagerAdapter", "[PAGINATION_DEBUG] onCreateViewHolder: " +
            "parentWidth=${parent.width}, parentHeight=${parent.height}")
        
        return PageViewHolder(view as ViewGroup)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        // Get the windowId for this position from the list
        val windowId = getItem(position)
        holder.boundWindowId = windowId
        holder.boundAdapterPosition = position
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
        
        // IMPORTANT: RecyclerView may bind (and prefetch) holders that are not attached to window yet.
        // Committing a fragment transaction to a container that isn't in the view hierarchy will crash:
        // "No view found for id ... for fragment ...".
        // Only attach/replace fragments once the holder is attached to window.
        if (holder.itemView.isAttachedToWindow) {
            scheduleAttachFragment(holder, position, windowId)
        } else {
            AppLogger.d(
                "ReaderPagerAdapter",
                "[BIND_BUFFER_SNAPSHOT] Deferring fragment attach (holder not attached): position=$position, windowId=$windowId, containerId=${holder.containerView.id}"
            )
        }
    }

    override fun onViewAttachedToWindow(holder: PageViewHolder) {
        super.onViewAttachedToWindow(holder)
        val position = holder.adapterPosition
        if (position == RecyclerView.NO_POSITION) return
        val windowId = getItem(position)
        holder.boundWindowId = windowId
        holder.boundAdapterPosition = position
        scheduleAttachFragment(holder, position, windowId)
    }

    private fun scheduleAttachFragment(holder: PageViewHolder, position: Int, windowId: Int) {
        val fragmentTag = "w$windowId"
        val expectedPosition = position
        val expectedWindowId = windowId

        holder.itemView.post {
            if (activity.isFinishing || activity.isDestroyed) {
                AppLogger.w(
                    "ReaderPagerAdapter",
                    "[BIND_BUFFER_SNAPSHOT] Fragment SKIPPED (activity finishing): position=$expectedPosition windowId=$expectedWindowId"
                )
                return@post
            }

            // Holder may have been rebound/recycled by the time this runs.
            val currentPos = holder.adapterPosition
            if (currentPos != expectedPosition || currentPos == RecyclerView.NO_POSITION) {
                AppLogger.w(
                    "ReaderPagerAdapter",
                    "[BIND_BUFFER_SNAPSHOT] Fragment SKIPPED (position changed): expected=$expectedPosition current=$currentPos"
                )
                return@post
            }

            if (!holder.itemView.isAttachedToWindow) {
                AppLogger.w(
                    "ReaderPagerAdapter",
                    "[BIND_BUFFER_SNAPSHOT] Fragment SKIPPED (still not attached): position=$expectedPosition windowId=$expectedWindowId"
                )
                return@post
            }

            // Ensure list mapping hasn't changed.
            if (expectedPosition !in 0 until itemCount || getItem(expectedPosition) != expectedWindowId) {
                AppLogger.w(
                    "ReaderPagerAdapter",
                    "[BIND_BUFFER_SNAPSHOT] Fragment SKIPPED (windowId remapped): position=$expectedPosition expectedWindowId=$expectedWindowId actual=${getItem(expectedPosition)}"
                )
                return@post
            }

            // Ensure the holder's container is not already hosting a different fragment.
            fragmentManager.findFragmentById(holder.containerView.id)?.let { existingInContainer ->
                if (existingInContainer.tag != fragmentTag) {
                    AppLogger.d(
                        "ReaderPagerAdapter",
                        "[BIND_BUFFER_SNAPSHOT] Removing fragment from recycled container: containerId=${holder.containerView.id}, existingTag=${existingInContainer.tag}, newTag=$fragmentTag"
                    )
                    fragmentManager.beginTransaction()
                        .remove(existingInContainer)
                        .commitAllowingStateLoss()
                }
            }

            // Cache hit if the correct fragment is already attached to this container.
            val existingFragment = fragmentManager.findFragmentByTag(fragmentTag)
            if (existingFragment != null && existingFragment.isAdded && existingFragment.view?.parent === holder.containerView) {
                AppLogger.d(
                    "ReaderPagerAdapter",
                    "[BIND_BUFFER_SNAPSHOT] Fragment CACHE_HIT (in correct container): position=$expectedPosition, windowId=$expectedWindowId, fragmentTag=$fragmentTag, containerId=${holder.containerView.id}"
                )
                return@post
            }

            // If a stale fragment exists for this tag but is attached elsewhere, remove it.
            if (existingFragment != null && existingFragment.isAdded && existingFragment.view?.parent !== holder.containerView) {
                AppLogger.w(
                    "ReaderPagerAdapter",
                    "[BIND_BUFFER_SNAPSHOT] Fragment tag exists in different container; removing stale instance: windowId=$expectedWindowId, fragmentTag=$fragmentTag"
                )
                fragmentManager.beginTransaction()
                    .remove(existingFragment)
                    .commitAllowingStateLoss()
            }

            val fragment = ReaderPageFragment.newInstance(expectedWindowId)
            AppLogger.d(
                "ReaderPagerAdapter",
                "[BIND_BUFFER_SNAPSHOT] Fragment ATTACH: position=$expectedPosition, windowId=$expectedWindowId, fragmentTag=$fragmentTag, containerId=${holder.containerView.id}"
            )

            fragmentManager.beginTransaction()
                .replace(holder.containerView.id, fragment, fragmentTag)
                .commitAllowingStateLoss()

            activeFragments.add(expectedWindowId)

            AppLogger.d(
                "ReaderPagerAdapter",
                "[BIND_BUFFER_SNAPSHOT] Fragment COMMITTED: position=$expectedPosition, windowId=$expectedWindowId, containerId=${holder.containerView.id}, activeFragments=${activeFragments.size}, fragmentTag=$fragmentTag"
            )
        }
    }

    override fun onViewRecycled(holder: PageViewHolder) {
        super.onViewRecycled(holder)
        AppLogger.d("ReaderPagerAdapter", "[PAGINATION_DEBUG] onViewRecycled: containerId=${holder.containerView.id}")

        // Remove whatever fragment is currently attached to this recycled container.
        val fragmentInContainer = fragmentManager.findFragmentById(holder.containerView.id)
        if (fragmentInContainer != null && fragmentInContainer.isAdded) {
            val tag = fragmentInContainer.tag
            mainHandler.post {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    fragmentManager.findFragmentById(holder.containerView.id)?.let { current ->
                        fragmentManager.beginTransaction()
                            .remove(current)
                            .commitAllowingStateLoss()
                        tag?.removePrefix("w")?.toIntOrNull()?.let { activeFragments.remove(it) }
                        AppLogger.d(
                            "ReaderPagerAdapter",
                            "[PAGINATION_DEBUG] Fragment REMOVED from recycled container: containerId=${holder.containerView.id}, tag=${current.tag}"
                        )
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

    class PageViewHolder(val containerView: ViewGroup) : RecyclerView.ViewHolder(containerView) {
        var boundWindowId: Int = -1
        var boundAdapterPosition: Int = RecyclerView.NO_POSITION
    }
}
