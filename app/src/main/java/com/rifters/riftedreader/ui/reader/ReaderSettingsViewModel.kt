package com.rifters.riftedreader.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.rifters.riftedreader.data.preferences.ReaderPreferences
import com.rifters.riftedreader.data.preferences.ReaderSettings
import com.rifters.riftedreader.data.preferences.ReaderTheme
import com.rifters.riftedreader.domain.pagination.PaginationMode
import kotlinx.coroutines.flow.StateFlow

class ReaderSettingsViewModel(
    private val readerPreferences: ReaderPreferences
) : ViewModel() {

    val settings: StateFlow<ReaderSettings> = readerPreferences.settings

    fun updateTextSize(size: Float) {
        readerPreferences.updateSettings { current -> current.copy(textSizeSp = size) }
    }

    fun updateLineHeight(multiplier: Float) {
        readerPreferences.updateSettings { current -> current.copy(lineHeightMultiplier = multiplier) }
    }

    fun updateTheme(theme: ReaderTheme) {
        readerPreferences.updateSettings { current -> current.copy(theme = theme) }
    }
    
    fun updateMode(mode: com.rifters.riftedreader.data.preferences.ReaderMode) {
        readerPreferences.updateSettings { current -> current.copy(mode = mode) }
    }

    fun updatePaginationMode(mode: PaginationMode) {
        readerPreferences.updateSettings { current -> current.copy(paginationMode = mode) }
    }

    class Factory(private val readerPreferences: ReaderPreferences) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ReaderSettingsViewModel::class.java)) {
                "Unknown ViewModel class ${modelClass.name}"
            }
            return ReaderSettingsViewModel(readerPreferences) as T
        }
    }
}
