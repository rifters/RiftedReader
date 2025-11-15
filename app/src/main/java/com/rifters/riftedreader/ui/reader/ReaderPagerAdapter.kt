package com.rifters.riftedreader.ui.reader

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

class ReaderPagerAdapter(
    activity: FragmentActivity
) : FragmentStateAdapter(activity) {

    private var pageCount: Int = 0

    override fun getItemCount(): Int = pageCount

    override fun createFragment(position: Int): Fragment {
        return ReaderPageFragment.newInstance(position)
    }

    fun submitPageCount(count: Int) {
        if (pageCount == count) {
            return
        }
        pageCount = count
        notifyDataSetChanged()
    }
}
