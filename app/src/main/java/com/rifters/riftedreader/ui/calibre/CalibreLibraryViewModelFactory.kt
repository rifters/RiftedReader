package com.rifters.riftedreader.ui.calibre

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.rifters.riftedreader.data.calibre.CalibreConnectionRepository
import com.rifters.riftedreader.data.calibre.CalibreContentServerRepository

class CalibreLibraryViewModelFactory(
    private val contentServerRepository: CalibreContentServerRepository,
    private val connectionRepository: CalibreConnectionRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(CalibreLibraryViewModel::class.java)) {
            return CalibreLibraryViewModel(contentServerRepository, connectionRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
