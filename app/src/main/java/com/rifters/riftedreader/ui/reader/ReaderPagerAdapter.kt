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
 * The adapter uses windowCount which:
 * - In continuous mode: totalChapters / windowSize (rounded up)
 * - In chapter-based mode: equals totalChapters (one window per chapter)
 */
class ReaderPagerAdapter(
    activity: FragmentActivity,
    private val viewModel: ReaderViewModel
) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int {
        // Use windowCount instead of totalPages
        // This ensures proper window-based pagination
        val count = viewModel.windowCount.value
        AppLogger.d("ReaderPagerAdapter", "getItemCount: $count (windows)")
        return count
    }

    override fun createFragment(position: Int): Fragment {
        // Position is the window index, not chapter index
        AppLogger.d("ReaderPagerAdapter", "createFragment: windowIndex=$position")
        return ReaderPageFragment.newInstance(position)
    }

    override fun getItemId(position: Int): Long = position.toLong()

    override fun containsItem(itemId: Long): Boolean {
        val total = viewModel.windowCount.value
        return itemId >= 0 && itemId < total
    }
}
