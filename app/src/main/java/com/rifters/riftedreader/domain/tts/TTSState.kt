package com.rifters.riftedreader.domain.tts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TTSPlaybackState {
    IDLE,
    PLAYING,
    PAUSED,
    STOPPED
}

data class TTSConfiguration(
    val speed: Float = 1.0f,
    val pitch: Float = 1.0f,
    val autoScroll: Boolean = true,
    val highlightSentence: Boolean = true
)

data class TTSState(
    val playbackState: TTSPlaybackState = TTSPlaybackState.IDLE,
    val currentSentenceIndex: Int = 0,
    val totalSentences: Int = 0,
    val configuration: TTSConfiguration = TTSConfiguration()
)

class TTSStateManager {
    private val _state = MutableStateFlow(TTSState())
    val state: StateFlow<TTSState> = _state.asStateFlow()

    fun updatePlaybackState(newState: TTSPlaybackState) {
        _state.value = _state.value.copy(playbackState = newState)
    }

    fun updateSentenceProgress(index: Int, total: Int) {
        _state.value = _state.value.copy(
            currentSentenceIndex = index,
            totalSentences = total
        )
    }

    fun updateConfiguration(configuration: TTSConfiguration) {
        _state.value = _state.value.copy(configuration = configuration)
    }

    fun reset() {
        _state.value = TTSState()
    }
}
