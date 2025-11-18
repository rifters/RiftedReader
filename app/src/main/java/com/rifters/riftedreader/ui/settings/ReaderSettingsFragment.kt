package com.rifters.riftedreader.ui.settings

import android.os.Bundle
import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat
import com.rifters.riftedreader.R
import com.rifters.riftedreader.data.preferences.ReaderPreferences
import com.rifters.riftedreader.data.preferences.ReaderSettings
import com.rifters.riftedreader.data.preferences.ReaderTheme
import com.rifters.riftedreader.data.preferences.ReaderMode
import com.rifters.riftedreader.util.AppLogger

class ReaderSettingsFragment : PreferenceFragmentCompat() {

    private val readerPreferences by lazy { ReaderPreferences(requireContext()) }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences_reader, rootKey)
        activity?.title = getString(R.string.settings_reader_title)
        AppLogger.event("ReaderSettingsFragment", "Reader settings opened", "ui/settings/lifecycle")

        val settings = readerPreferences.settings.value
        bindListPreference("reader_text_size", settings.textSizeSp.toInt().toString()) { value ->
            val size = value.toFloatOrNull() ?: ReaderSettings().textSizeSp
            AppLogger.event("ReaderSettingsFragment", "Text size changed to ${size}sp", "ui/settings/change")
            readerPreferences.updateSettings { it.copy(textSizeSp = size) }
        }
        bindListPreference("reader_line_height", settings.lineHeightMultiplier.toString()) { value ->
            val multiplier = value.toFloatOrNull() ?: ReaderSettings().lineHeightMultiplier
            AppLogger.event("ReaderSettingsFragment", "Line height changed to $multiplier", "ui/settings/change")
            readerPreferences.updateSettings { it.copy(lineHeightMultiplier = multiplier) }
        }
        bindListPreference("reader_theme", settings.theme.name) { value ->
            val theme = runCatching { ReaderTheme.valueOf(value) }.getOrDefault(settings.theme)
            AppLogger.event("ReaderSettingsFragment", "Theme changed to $theme", "ui/settings/change")
            readerPreferences.updateSettings { it.copy(theme = theme) }
        }
        bindListPreference("reader_mode", settings.mode.name) { value ->
            val mode = runCatching { ReaderMode.valueOf(value) }.getOrDefault(settings.mode)
            AppLogger.event("ReaderSettingsFragment", "Reader mode changed to $mode", "ui/settings/change")
            readerPreferences.updateSettings { it.copy(mode = mode) }
        }
    }

    private fun bindListPreference(key: String, currentValue: String, onChanged: (String) -> Unit) {
        val preference = findPreference<ListPreference>(key) ?: return
        if (preference.value != currentValue) {
            preference.value = currentValue
        }
        preference.setOnPreferenceChangeListener { _, newValue ->
            (newValue as? String)?.let { onChanged(it) }
            true
        }
    }
}
