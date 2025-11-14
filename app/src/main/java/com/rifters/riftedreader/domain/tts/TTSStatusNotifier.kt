package com.rifters.riftedreader.domain.tts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Lightweight event bus for propagating Text-to-Speech playback state
 * from [TTSService] to UI components without relying on the deprecated
 * [androidx.localbroadcastmanager.content.LocalBroadcastManager].
 */
object TTSStatusNotifier {

    private val _status = MutableStateFlow(TTSStatusSnapshot())
    val status: StateFlow<TTSStatusSnapshot> = _status.asStateFlow()

    fun update(snapshot: TTSStatusSnapshot) {
        _status.value = snapshot
    }

    fun reset() {
        _status.value = TTSStatusSnapshot()
    }
}

/**
 * Snapshot of the current TTS playback state that observers can consume.
 */
data class TTSStatusSnapshot(
    val state: TTSPlaybackState = TTSPlaybackState.IDLE,
    val sentenceIndex: Int = -1,
    val sentenceTotal: Int = 0
)
