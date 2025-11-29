package com.rifters.riftedreader.ui.reader

import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.rifters.riftedreader.util.AppLogger

class ReaderPagerAdapter(
    private val activity: FragmentActivity,
    private val viewModel: /* ReaderViewModel type */ Any
) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int {
        // Prefer the ViewModel's window count (StateFlow/State or LiveData).
        // The branch added a windowCount LiveData and StateFlow _windowCount. Adapt to your ViewModel API:
        // - If ViewModel exposes StateFlow/State: read viewModel.windowCountState.value
        // - If ViewModel exposes LiveData: read viewModel.windowCountLiveData.value
        // Use safe fallback to 0 to avoid crashes during initialization.

        val count = try {
            // Try the StateFlow-backed value first (if viewModel.windowCount exists and is a State / StateFlow)
            // Replace the reflection below with the actual property usage in your codebase.
            when {
                // If viewModel has a public 'windowCount' State/StateFlow property:
                // (Uncomment and adapt the line below to your actual ViewModel)
                // viewModel.windowCount.value != null -> viewModel.windowCount.value
                // Else, if using LiveData:
                // viewModel.windowCountLiveData.value ?: 0
                else -> {
                    // Fallback: attempt to read LiveData-like property by reflection is not recommended;
                    // instead, change this adapter to accept the window count or access the ViewModel directly.
                    0
                }
            }
        } catch (e: Exception) {
            0
        }

        AppLogger.d("ReaderPagerAdapter", "getItemCount: $count (windows)")
        return count
    }

    // ... rest of adapter implementation ...
}
