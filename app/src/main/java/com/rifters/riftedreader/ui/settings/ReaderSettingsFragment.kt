package com.rifters.riftedreader.ui.settings

import android.os.Bundle
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.rifters.riftedreader.R
import com.rifters.riftedreader.data.preferences.ChapterVisibilitySettings
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
        bindSwitchPreference(
            key = "reader_continuous_streaming",
            currentValue = settings.continuousStreamingEnabled
        ) { isEnabled ->
            AppLogger.event(
                "ReaderSettingsFragment",
                "Continuous streaming toggled to $isEnabled",
                "ui/settings/change"
            )
            readerPreferences.updateSettings { it.copy(continuousStreamingEnabled = isEnabled) }
        }
        
        // Bind chapter visibility settings
        bindChapterVisibilitySettings(settings.chapterVisibility)
    }
    
    private fun bindChapterVisibilitySettings(visibility: ChapterVisibilitySettings) {
        // Include cover
        bindSwitchPreference(
            key = "reader_include_cover",
            currentValue = visibility.includeCover
        ) { includeCover ->
            AppLogger.event(
                "ReaderSettingsFragment",
                "Include cover toggled to $includeCover",
                "ui/settings/chapter_visibility"
            )
            readerPreferences.updateSettings { settings ->
                settings.copy(
                    chapterVisibility = settings.chapterVisibility.copy(includeCover = includeCover)
                )
            }
        }
        
        // Include front matter
        bindSwitchPreference(
            key = "reader_include_front_matter",
            currentValue = visibility.includeFrontMatter
        ) { includeFrontMatter ->
            AppLogger.event(
                "ReaderSettingsFragment",
                "Include front matter toggled to $includeFrontMatter",
                "ui/settings/chapter_visibility"
            )
            readerPreferences.updateSettings { settings ->
                settings.copy(
                    chapterVisibility = settings.chapterVisibility.copy(includeFrontMatter = includeFrontMatter)
                )
            }
        }
        
        // Include non-linear content
        bindSwitchPreference(
            key = "reader_include_non_linear",
            currentValue = visibility.includeNonLinear
        ) { includeNonLinear ->
            AppLogger.event(
                "ReaderSettingsFragment",
                "Include non-linear toggled to $includeNonLinear",
                "ui/settings/chapter_visibility"
            )
            readerPreferences.updateSettings { settings ->
                settings.copy(
                    chapterVisibility = settings.chapterVisibility.copy(includeNonLinear = includeNonLinear)
                )
            }
        }
        
        // Reset to defaults
        findPreference<Preference>("reader_reset_visibility")?.setOnPreferenceClickListener {
            AppLogger.event(
                "ReaderSettingsFragment",
                "Resetting chapter visibility to defaults",
                "ui/settings/chapter_visibility"
            )
            readerPreferences.updateSettings { settings ->
                settings.copy(chapterVisibility = ChapterVisibilitySettings.DEFAULT)
            }
            // Update the UI to reflect the reset
            updateVisibilityPreferencesUI(ChapterVisibilitySettings.DEFAULT)
            true
        }
    }
    
    private fun updateVisibilityPreferencesUI(visibility: ChapterVisibilitySettings) {
        findPreference<SwitchPreferenceCompat>("reader_include_cover")?.isChecked = visibility.includeCover
        findPreference<SwitchPreferenceCompat>("reader_include_front_matter")?.isChecked = visibility.includeFrontMatter
        findPreference<SwitchPreferenceCompat>("reader_include_non_linear")?.isChecked = visibility.includeNonLinear
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

    private fun bindSwitchPreference(key: String, currentValue: Boolean, onChanged: (Boolean) -> Unit) {
        val preference = findPreference<SwitchPreferenceCompat>(key) ?: return
        if (preference.isChecked != currentValue) {
            preference.isChecked = currentValue
        }
        preference.setOnPreferenceChangeListener { _, newValue ->
            (newValue as? Boolean)?.let { onChanged(it) }
            true
        }
    }
}
