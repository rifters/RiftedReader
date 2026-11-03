package com.rifters.riftedreader.domain.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.UUID

/**
 * Stub provider for Sherpa ONNX neural TTS (Stage 4 / neural voices).
 * Scaffolding only: compiles without requiring native C++ or external onnx libraries.
 * Reports isAvailable() = false until model files and runtime are provisioned.
 */
class SherpaOnnxTTSProvider(
    private val context: Context
) : TTSAudioEngineProvider {

    override val engineType: TTSEngineType = TTSEngineType.SHERPA_ONNX

    private var isInitialized = false
    private var utteranceProgressListener: UtteranceProgressListener? = null

    override fun isAvailable(): Boolean = false

    override fun isReady(): Boolean = isInitialized

    override fun initialize(onComplete: (Boolean) -> Unit) {
        Log.i(TAG, "SherpaOnnxTTSProvider initialize requested (stub - runtime not packaged)")
        isInitialized = false
        onComplete(false)
    }

    override fun speak(text: String, utteranceId: String, flushQueue: Boolean) {
        Log.w(TAG, "SherpaOnnxTTSProvider speak() invoked on stub engine - offline neural models pending")
    }

    override fun stop() {
        // No-op for stub
    }

    override fun setSpeed(speed: Float) {
        // No-op for stub
    }

    override fun setPitch(pitch: Float) {
        // No-op for stub
    }

    override fun setLanguage(locale: Locale): Int {
        return TextToSpeech.LANG_NOT_SUPPORTED
    }

    override fun getAvailableLanguages(): Set<Locale>? = emptySet()

    override fun setOnUtteranceProgressListener(listener: UtteranceProgressListener) {
        utteranceProgressListener = listener
    }

    override fun shutdown() {
        isInitialized = false
    }

    companion object {
        private const val TAG = "SherpaOnnxTTS"
    }
}
