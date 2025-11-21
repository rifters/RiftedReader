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
        readerPreferences.updateSettings { current -> 
            // If switching to SCROLL mode, disable continuous pagination
            if (mode == com.rifters.riftedreader.data.preferences.ReaderMode.SCROLL && 
                current.paginationMode == PaginationMode.CONTINUOUS) {
                com.rifters.riftedreader.util.AppLogger.d(
                    "ReaderSettingsViewModel",
                    "Switching to SCROLL mode - disabling continuous pagination"
                )
                current.copy(
                    mode = mode, 
                    paginationMode = PaginationMode.CHAPTER_BASED
                )
            } else {
                current.copy(mode = mode)
            }
        }
    }

    fun updatePaginationMode(mode: PaginationMode) {
        readerPreferences.updateSettings { current -> 
            // If enabling continuous pagination, enforce PAGE mode
            if (mode == PaginationMode.CONTINUOUS && 
                current.mode == com.rifters.riftedreader.data.preferences.ReaderMode.SCROLL) {
                com.rifters.riftedreader.util.AppLogger.d(
                    "ReaderSettingsViewModel",
                    "Enabling continuous pagination - switching to PAGE mode"
                )
                current.copy(
                    paginationMode = mode,
                    mode = com.rifters.riftedreader.data.preferences.ReaderMode.PAGE
                )
            } else {
                current.copy(paginationMode = mode)
            }
        }
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
