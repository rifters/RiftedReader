package com.rifters.riftedreader.ui.reader

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.rifters.riftedreader.util.AppLogger

class ReaderPagerAdapter(
    activity: FragmentActivity
) : FragmentStateAdapter(activity) {

    private var pageCount: Int = 0

    override fun getItemCount(): Int {
        AppLogger.d("ReaderPagerAdapter", "getItemCount: $pageCount")
        return pageCount
    }

    override fun createFragment(position: Int): Fragment {
        AppLogger.d("ReaderPagerAdapter", "createFragment: position=$position")
        return ReaderPageFragment.newInstance(position)
    }

    fun submitPageCount(count: Int) {
        if (pageCount == count) {
            return
        }
        AppLogger.d("ReaderPagerAdapter", "submitPageCount: $count (was $pageCount)")
        pageCount = count
        notifyDataSetChanged()
    }
}
