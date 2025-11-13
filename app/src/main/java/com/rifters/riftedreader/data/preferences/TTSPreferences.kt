package com.rifters.riftedreader.data.preferences

import android.content.Context
import android.content.SharedPreferences
import java.io.File

class TTSPreferences(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val rulesDir: File = File(context.filesDir, RULES_DIR_NAME)

    init {
        if (!rulesDir.exists()) {
            rulesDir.mkdirs()
        }
        ensureDefaultRules()
    }

    var speed: Float
        get() = prefs.getFloat(KEY_SPEED, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_SPEED, value).apply()

    var pitch: Float
        get() = prefs.getFloat(KEY_PITCH, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_PITCH, value).apply()

    var autoScroll: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SCROLL, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_SCROLL, value).apply()

    var highlightSentence: Boolean
        get() = prefs.getBoolean(KEY_HIGHLIGHT, true)
        set(value) = prefs.edit().putBoolean(KEY_HIGHLIGHT, value).apply()

    fun saveReplacementRules(rules: String) {
        getReplacementRulesFile().writeText(rules)
    }

    fun loadReplacementRules(): String? {
        val file = getReplacementRulesFile()
        return if (file.exists()) file.readText() else null
    }

    fun getReplacementRulesFile(): File = File(rulesDir, DEFAULT_RULES_FILE)

    private fun ensureDefaultRules() {
        val file = getReplacementRulesFile()
        if (file.exists()) return

        runCatching {
            context.assets.open(DEFAULT_RULES_ASSET).use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "tts_preferences"
        private const val KEY_SPEED = "tts_speed"
        private const val KEY_PITCH = "tts_pitch"
        private const val KEY_AUTO_SCROLL = "tts_auto_scroll"
        private const val KEY_HIGHLIGHT = "tts_highlight"

        private const val RULES_DIR_NAME = "tts_rules"
        private const val DEFAULT_RULES_FILE = "default_rules.txt"
        private const val DEFAULT_RULES_ASSET = "sample_tts_rules.txt"
    }
}
