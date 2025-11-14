package com.rifters.riftedreader.ui.settings

import android.os.Bundle
import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat
import com.rifters.riftedreader.R
import com.rifters.riftedreader.data.preferences.ReaderPreferences
import com.rifters.riftedreader.data.preferences.ReaderSettings
import com.rifters.riftedreader.data.preferences.ReaderTheme
import com.rifters.riftedreader.data.preferences.ReaderMode

class ReaderSettingsFragment : PreferenceFragmentCompat() {

    private val readerPreferences by lazy { ReaderPreferences(requireContext()) }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences_reader, rootKey)
        activity?.title = getString(R.string.settings_reader_title)

        val settings = readerPreferences.settings.value
        bindListPreference("reader_text_size", settings.textSizeSp.toInt().toString()) { value ->
            val size = value.toFloatOrNull() ?: ReaderSettings().textSizeSp
            readerPreferences.updateSettings { it.copy(textSizeSp = size) }
        }
        bindListPreference("reader_line_height", settings.lineHeightMultiplier.toString()) { value ->
            val multiplier = value.toFloatOrNull() ?: ReaderSettings().lineHeightMultiplier
            readerPreferences.updateSettings { it.copy(lineHeightMultiplier = multiplier) }
        }
        bindListPreference("reader_theme", settings.theme.name) { value ->
            val theme = runCatching { ReaderTheme.valueOf(value) }.getOrDefault(settings.theme)
            readerPreferences.updateSettings { it.copy(theme = theme) }
        }
        bindListPreference("reader_mode", settings.mode.name) { value ->
            val mode = runCatching { ReaderMode.valueOf(value) }.getOrDefault(settings.mode)
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
