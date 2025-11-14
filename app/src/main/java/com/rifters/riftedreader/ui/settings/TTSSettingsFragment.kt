package com.rifters.riftedreader.ui.settings

import android.os.Bundle
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import androidx.navigation.fragment.findNavController
import com.rifters.riftedreader.R
import com.rifters.riftedreader.data.preferences.TTSPreferences

class TTSSettingsFragment : PreferenceFragmentCompat() {

    private val ttsPreferences by lazy { TTSPreferences(requireContext()) }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences_tts, rootKey)
        activity?.title = getString(R.string.settings_tts_title)

        bindListPreference("tts_speed", ttsPreferences.speed.toString()) { value ->
            value.toFloatOrNull()?.let { ttsPreferences.speed = it }
        }
        bindListPreference("tts_pitch", ttsPreferences.pitch.toString()) { value ->
            value.toFloatOrNull()?.let { ttsPreferences.pitch = it }
        }
        bindSwitchPreference("tts_highlight", ttsPreferences.highlightSentence) { checked ->
            ttsPreferences.highlightSentence = checked
        }
        bindSwitchPreference("tts_auto_scroll", ttsPreferences.autoScroll) { checked ->
            ttsPreferences.autoScroll = checked
        }
        findPreference<Preference>(PREF_REPLACEMENTS)?.setOnPreferenceClickListener {
            findNavController().navigate(R.id.action_ttsSettingsFragment_to_ttsReplacementsFragment)
            true
        }
    }

    private fun bindListPreference(key: String, currentValue: String, onChanged: (String) -> Unit) {
        val preference = findPreference<ListPreference>(key) ?: return
        if (preference.value != currentValue) {
            preference.value = currentValue
        }
        preference.setOnPreferenceChangeListener { _, newValue ->
            (newValue as? String)?.let(onChanged)
            true
        }
    }

    private fun bindSwitchPreference(key: String, currentValue: Boolean, onChanged: (Boolean) -> Unit) {
        val preference = findPreference<SwitchPreferenceCompat>(key) ?: return
        if (preference.isChecked != currentValue) {
            preference.isChecked = currentValue
        }
        preference.setOnPreferenceChangeListener { _, newValue ->
            (newValue as? Boolean)?.let(onChanged)
            true
        }
    }

    companion object {
        private const val PREF_REPLACEMENTS = "tts_replacements"
    }
}
