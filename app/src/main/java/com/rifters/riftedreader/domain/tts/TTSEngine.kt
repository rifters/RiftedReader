package com.rifters.riftedreader.domain.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.UUID

/**
 * Wrapper around Android's TextToSpeech to simplify configuration and playback.
 */
class TTSEngine(private val context: Context) {
    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var onInitListener: ((Boolean) -> Unit)? = null

    fun initialize(onComplete: (Boolean) -> Unit) {
        onInitListener = onComplete
        if (tts != null) {
            onComplete(isInitialized)
            return
        }

        tts = TextToSpeech(context) { status ->
            isInitialized = status == TextToSpeech.SUCCESS
            if (isInitialized) {
                configureDefaults()
            }
            onInitListener?.invoke(isInitialized)
        }
    }

    private fun configureDefaults() {
        tts?.apply {
            language = Locale.getDefault()
            setSpeechRate(1.0f)
            setPitch(1.0f)
        }
    }

    fun speak(text: String, utteranceId: String = UUID.randomUUID().toString(), flushQueue: Boolean = false) {
        if (!isInitialized) {
            Log.w(TAG, "Attempted to speak before TTS was initialized")
            return
        }
        val queueMode = if (flushQueue) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val params = android.os.Bundle()
        tts?.speak(text, queueMode, params, utteranceId)
    }

    fun stop() {
        tts?.stop()
    }

    fun setSpeed(speed: Float) {
        if (!isInitialized) return
        tts?.setSpeechRate(speed)
    }

    fun setPitch(pitch: Float) {
        if (!isInitialized) return
        tts?.setPitch(pitch)
    }

    fun setLanguage(locale: Locale): Int {
        if (!isInitialized) return TextToSpeech.LANG_NOT_SUPPORTED
        return tts?.setLanguage(locale) ?: TextToSpeech.LANG_NOT_SUPPORTED
    }

    fun getAvailableLanguages(): Set<Locale>? = tts?.availableLanguages

    fun setOnUtteranceProgressListener(listener: UtteranceProgressListener) {
        if (!isInitialized) {
            // Defer listener attachment until init completes.
            initialize {
                if (it) {
                    tts?.setOnUtteranceProgressListener(listener)
                }
            }
        } else {
            tts?.setOnUtteranceProgressListener(listener)
        }
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        isInitialized = false
    }

    fun isReady(): Boolean = isInitialized

    companion object {
        private const val TAG = "TTSEngine"
    }
}
