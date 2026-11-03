package com.rifters.riftedreader.ui.settings

import android.os.Bundle
import android.view.View
import androidx.core.content.ContextCompat
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.navigation.fragment.findNavController
import com.rifters.riftedreader.R
import com.rifters.riftedreader.data.preferences.AppTheme
import com.rifters.riftedreader.data.preferences.AppThemePreferences

class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences_main, rootKey)
        activity?.title = getString(R.string.settings_title)

        val appThemePrefs = AppThemePreferences(requireContext())
        findPreference<ListPreference>(PREF_APP_THEME)?.let { pref ->
            pref.value = appThemePrefs.appTheme.name
            pref.setOnPreferenceChangeListener { _, newValue ->
                val newTheme = AppTheme.fromString(newValue as? String)
                appThemePrefs.appTheme = newTheme
                true
            }
        }

        findPreference<Preference>(PREF_READER)?.setOnPreferenceClickListener {
            findNavController().navigate(R.id.action_settingsFragment_to_readerSettingsFragment)
            true
        }

        findPreference<Preference>(PREF_TTS)?.setOnPreferenceClickListener {
            findNavController().navigate(R.id.action_settingsFragment_to_ttsSettingsFragment)
            true
        }

        findPreference<Preference>(PREF_LIBRARY_STATS)?.setOnPreferenceClickListener {
            findNavController().navigate(R.id.action_settingsFragment_to_libraryStatisticsFragment)
            true
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val backgroundColor = ContextCompat.getColor(requireContext(), R.color.app_background)
        view.setBackgroundColor(backgroundColor)
        listView.setBackgroundColor(backgroundColor)
    }

    companion object {
        private const val PREF_APP_THEME = "app_theme"
        private const val PREF_READER = "settings_reader"
        private const val PREF_TTS = "settings_tts"
        private const val PREF_LIBRARY_STATS = "settings_library_statistics"
    }
}
