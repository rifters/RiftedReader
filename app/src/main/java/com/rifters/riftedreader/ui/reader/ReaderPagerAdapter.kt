package com.rifters.riftedreader.ui.reader

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.rifters.riftedreader.util.AppLogger

/**
 * Adapter for the ViewPager2 that displays reader content.
 * 
 * In continuous mode, each page represents a WINDOW (containing multiple chapters).
 * In chapter-based mode, each page represents a single chapter.
 * 
 * The adapter uses windowCountLiveData from the ViewModel's SlidingWindowPaginator
 * for deterministic pagination, falling back to windowCount StateFlow if needed.
 */
class ReaderPagerAdapter(
    activity: FragmentActivity,
    private val viewModel: ReaderViewModel
) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int {
        // Prefer windowCountLiveData from SlidingWindowPaginator for deterministic pagination
        // Fall back to windowCount StateFlow if LiveData is not yet initialized
        val count = viewModel.windowCountLiveData.value ?: viewModel.windowCount.value
        AppLogger.d("ReaderPagerAdapter", "getItemCount: $count (windows, from LiveData=${viewModel.windowCountLiveData.value != null})")
        return count
    }

    override fun createFragment(position: Int): Fragment {
        // Position is the window index, not chapter index
        AppLogger.d("ReaderPagerAdapter", "createFragment: windowIndex=$position")
        return ReaderPageFragment.newInstance(position)
    }

    override fun getItemId(position: Int): Long = position.toLong()

    override fun containsItem(itemId: Long): Boolean {
        val total = viewModel.windowCountLiveData.value ?: viewModel.windowCount.value
        return itemId >= 0 && itemId < total
    }
}
