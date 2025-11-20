package com.rifters.riftedreader.ui.reader

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.rifters.riftedreader.util.AppLogger

class ReaderPagerAdapter(
    activity: FragmentActivity,
    private val viewModel: ReaderViewModel
) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int {
        val count = viewModel.totalPages.value
        AppLogger.d("ReaderPagerAdapter", "getItemCount: $count")
        return count
    }

    override fun createFragment(position: Int): Fragment {
        AppLogger.d("ReaderPagerAdapter", "createFragment: position=$position")
        return ReaderPageFragment.newInstance(position)
    }

    override fun getItemId(position: Int): Long = position.toLong()

    override fun containsItem(itemId: Long): Boolean {
        val total = viewModel.totalPages.value
        return itemId >= 0 && itemId < total
    }
}
