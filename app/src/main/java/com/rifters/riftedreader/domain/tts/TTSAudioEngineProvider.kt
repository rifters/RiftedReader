package com.rifters.riftedreader.domain.tts

import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID

enum class TTSEngineType {
    SYSTEM,
    SHERPA_ONNX
}

/**
 * Common abstraction for Text-To-Speech audio synthesis engines.
 * Enables plugging in neural offline providers (e.g., Sherpa ONNX) alongside Android's system TTS.
 */
interface TTSAudioEngineProvider {
    val engineType: TTSEngineType
    fun isAvailable(): Boolean
    fun isReady(): Boolean
    fun initialize(onComplete: (Boolean) -> Unit)
    fun speak(text: String, utteranceId: String = UUID.randomUUID().toString(), flushQueue: Boolean = false)
    fun stop()
    fun setSpeed(speed: Float)
    fun setPitch(pitch: Float)
    fun setLanguage(locale: Locale): Int
    fun getAvailableLanguages(): Set<Locale>?
    fun setOnUtteranceProgressListener(listener: UtteranceProgressListener)
    fun shutdown()
}
