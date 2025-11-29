package com.rifters.riftedreader.ui.reader

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.rifters.riftedreader.util.AppLogger

/**
 * Adapter for the ViewPager2 in the reader screen.
 * 
 * In continuous mode, each page represents a window containing multiple chapters.
 * In chapter-based mode, each page represents a single chapter.
 */
class ReaderPagerAdapter(
    private val activity: FragmentActivity,
    private val viewModel: ReaderViewModel
) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int {
        val count = viewModel.windowCount.value
        AppLogger.d("ReaderPagerAdapter", "getItemCount: $count (windows)")
        return count
    }

    override fun createFragment(position: Int): Fragment {
        AppLogger.d("ReaderPagerAdapter", "createFragment: position=$position")
        return ReaderPageFragment.newInstance(position)
    }
    
    override fun getItemId(position: Int): Long {
        // Each window has a unique ID based on position
        return position.toLong()
    }
    
    override fun containsItem(itemId: Long): Boolean {
        // Check if the item is within the current window count
        return itemId >= 0 && itemId < viewModel.windowCount.value
    }
}
