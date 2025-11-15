package com.rifters.riftedreader.ui.settings

import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.navigation.fragment.findNavController
import com.rifters.riftedreader.R

class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences_main, rootKey)
        activity?.title = getString(R.string.settings_title)

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

    companion object {
        private const val PREF_READER = "settings_reader"
        private const val PREF_TTS = "settings_tts"
        private const val PREF_LIBRARY_STATS = "settings_library_statistics"
    }
}
