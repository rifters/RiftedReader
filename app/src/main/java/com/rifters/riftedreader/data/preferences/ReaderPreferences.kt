package com.rifters.riftedreader.data.preferences

import android.content.Context
import androidx.core.content.edit
import com.rifters.riftedreader.domain.pagination.PaginationMode
import com.rifters.riftedreader.ui.reader.ReaderTapAction
import com.rifters.riftedreader.ui.reader.ReaderTapZone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val DEFAULT_TEXT_SIZE_SP = 16f
private const val DEFAULT_LINE_HEIGHT_MULTIPLIER = 1.3f

data class ReaderSettings(
    val textSizeSp: Float = DEFAULT_TEXT_SIZE_SP,
    val lineHeightMultiplier: Float = DEFAULT_LINE_HEIGHT_MULTIPLIER,
    val theme: ReaderTheme = ReaderTheme.LIGHT,
    val mode: ReaderMode = ReaderMode.SCROLL,
    val paginationMode: PaginationMode = PaginationMode.CONTINUOUS,
    val continuousStreamingEnabled: Boolean = true
)

enum class ReaderTheme {
    LIGHT,
    DARK,
    SEPIA,
    BLACK
}

enum class ReaderMode {
    SCROLL,
    PAGE
}

class ReaderPreferences(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<ReaderSettings> = _settings.asStateFlow()

    private val _tapActions = MutableStateFlow(readTapActions())
    val tapActions: StateFlow<Map<ReaderTapZone, ReaderTapAction>> = _tapActions.asStateFlow()

    fun updateSettings(transform: (ReaderSettings) -> ReaderSettings) {
        val updated = transform(_settings.value)
        saveSettings(updated)
        _settings.value = updated
    }

    private fun readSettings(): ReaderSettings {
        val size = prefs.getFloat(KEY_TEXT_SIZE, DEFAULT_TEXT_SIZE_SP)
        val lineHeight = prefs.getFloat(KEY_LINE_HEIGHT, DEFAULT_LINE_HEIGHT_MULTIPLIER)
        val themeName = prefs.getString(KEY_THEME, ReaderTheme.LIGHT.name) ?: ReaderTheme.LIGHT.name
        val theme = runCatching { ReaderTheme.valueOf(themeName) }.getOrDefault(ReaderTheme.LIGHT)
        val modeName = prefs.getString(KEY_MODE, ReaderMode.SCROLL.name) ?: ReaderMode.SCROLL.name
        val mode = runCatching { ReaderMode.valueOf(modeName) }.getOrDefault(ReaderMode.SCROLL)
        val paginationModeName = prefs.getString(KEY_PAGINATION_MODE, PaginationMode.CONTINUOUS.name) 
            ?: PaginationMode.CONTINUOUS.name
        val paginationMode = runCatching { PaginationMode.valueOf(paginationModeName) }
            .getOrDefault(PaginationMode.CONTINUOUS)
        val streamingEnabled = prefs.getBoolean(KEY_CONTINUOUS_STREAMING, true)
        return ReaderSettings(size, lineHeight, theme, mode, paginationMode, streamingEnabled)
    }

    private fun saveSettings(settings: ReaderSettings) {
        prefs.edit {
            putFloat(KEY_TEXT_SIZE, settings.textSizeSp)
            putFloat(KEY_LINE_HEIGHT, settings.lineHeightMultiplier)
            putString(KEY_THEME, settings.theme.name)
            putString(KEY_MODE, settings.mode.name)
            putString(KEY_PAGINATION_MODE, settings.paginationMode.name)
            putBoolean(KEY_CONTINUOUS_STREAMING, settings.continuousStreamingEnabled)
        }
    }

    fun updateTapAction(zone: ReaderTapZone, action: ReaderTapAction) {
        val updated = _tapActions.value.toMutableMap().apply { put(zone, action) }
        saveTapActions(updated)
        _tapActions.value = updated
    }

    fun resetTapActions() {
        val defaults = defaultTapActions()
        saveTapActions(defaults)
        _tapActions.value = defaults
    }

    private fun readTapActions(): Map<ReaderTapZone, ReaderTapAction> {
        val raw = prefs.getString(KEY_TAP_ACTIONS, null)
        if (raw.isNullOrBlank()) return defaultTapActions()
        val parsed = raw.split(ENTRY_SEPARATOR)
            .mapNotNull { entry ->
                val parts = entry.split(VALUE_SEPARATOR)
                if (parts.size != 2) return@mapNotNull null
                val zone = runCatching { ReaderTapZone.valueOf(parts[0]) }.getOrNull()
                val action = runCatching { ReaderTapAction.valueOf(parts[1]) }.getOrNull()
                if (zone != null && action != null) zone to action else null
            }
            .toMap()
        if (parsed.isEmpty()) return defaultTapActions()
        return defaultTapActions().toMutableMap().apply { putAll(parsed) }
    }

    private fun saveTapActions(map: Map<ReaderTapZone, ReaderTapAction>) {
        val encoded = map.entries.joinToString(ENTRY_SEPARATOR) { "${it.key.name}$VALUE_SEPARATOR${it.value.name}" }
        prefs.edit { putString(KEY_TAP_ACTIONS, encoded) }
    }

    fun getTapAction(zone: ReaderTapZone): ReaderTapAction = _tapActions.value[zone] ?: defaultTapActions()[zone]!!

    companion object {
        private const val PREFS_NAME = "reader_preferences"
        private const val KEY_TEXT_SIZE = "text_size_sp"
        private const val KEY_LINE_HEIGHT = "line_height_multiplier"
        private const val KEY_THEME = "reader_theme"
        private const val KEY_MODE = "reader_mode"
        private const val KEY_PAGINATION_MODE = "pagination_mode"
        private const val KEY_CONTINUOUS_STREAMING = "continuous_streaming_enabled"
        private const val KEY_TAP_ACTIONS = "reader_tap_actions"

        private const val ENTRY_SEPARATOR = "|"
        private const val VALUE_SEPARATOR = ":"

        fun defaultTapActions(): Map<ReaderTapZone, ReaderTapAction> = mapOf(
            ReaderTapZone.TOP_LEFT to ReaderTapAction.BACK,
            ReaderTapZone.TOP_CENTER to ReaderTapAction.TOGGLE_CONTROLS,
            ReaderTapZone.TOP_RIGHT to ReaderTapAction.OPEN_SETTINGS,
            ReaderTapZone.MIDDLE_LEFT to ReaderTapAction.PREVIOUS_PAGE,
            ReaderTapZone.CENTER to ReaderTapAction.TOGGLE_CONTROLS,
            ReaderTapZone.MIDDLE_RIGHT to ReaderTapAction.NEXT_PAGE,
            ReaderTapZone.BOTTOM_LEFT to ReaderTapAction.PREVIOUS_PAGE,
            ReaderTapZone.BOTTOM_CENTER to ReaderTapAction.START_TTS,
            ReaderTapZone.BOTTOM_RIGHT to ReaderTapAction.NEXT_PAGE
        )
    }
}
