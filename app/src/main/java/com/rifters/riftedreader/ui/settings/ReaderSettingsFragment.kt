package com.rifters.riftedreader.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.rifters.riftedreader.R
import com.rifters.riftedreader.data.calibre.BookFormat
import com.rifters.riftedreader.data.calibre.CalibreConnectionConfig
import com.rifters.riftedreader.data.calibre.CalibreUrlValidator
import com.rifters.riftedreader.data.calibre.ConnectionTestResult
import com.rifters.riftedreader.data.calibre.DefaultCalibreConnectionRepository
import com.rifters.riftedreader.data.preferences.ChapterVisibilitySettings
import com.rifters.riftedreader.data.preferences.ReaderMode
import com.rifters.riftedreader.data.preferences.ReaderPreferences
import com.rifters.riftedreader.data.preferences.ReaderSettings
import com.rifters.riftedreader.data.preferences.ReaderTheme
import com.rifters.riftedreader.util.AppLogger
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class ReaderSettingsFragment : PreferenceFragmentCompat() {

    private val readerPreferences by lazy { ReaderPreferences(requireContext()) }
    private val calibreRepository by lazy { DefaultCalibreConnectionRepository(requireContext()) }
    private var calibreConfig: CalibreConnectionConfig? = null
    private var calibreSaveJob: Job? = null

    private val folderPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@registerForActivityResult
        persistFolderPermission(uri)
        updateCalibreConfig { it.copy(downloadDirectory = uri.toString()) }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences_reader, rootKey)
        activity?.title = getString(R.string.settings_reader_title)
        AppLogger.event("ReaderSettingsFragment", "Reader settings opened", "ui/settings/lifecycle")

        val settings = readerPreferences.settings.value
        bindCalibreSettings()
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

        bindChapterVisibilitySettings(settings.chapterVisibility)
    }

    private fun bindCalibreSettings() {
        val contentEnabled = findPreference<SwitchPreferenceCompat>(PREF_CONTENT_SERVER_ENABLED)
        val contentUrl = findPreference<EditTextPreference>(PREF_CONTENT_SERVER_URL)
        val contentUsername = findPreference<EditTextPreference>(PREF_CONTENT_SERVER_USERNAME)
        val contentPassword = findPreference<EditTextPreference>(PREF_CONTENT_SERVER_PASSWORD)
        val webEnabled = findPreference<SwitchPreferenceCompat>(PREF_CALIBRE_WEB_ENABLED)
        val webUrl = findPreference<EditTextPreference>(PREF_CALIBRE_WEB_URL)
        val downloadDirectory = findPreference<Preference>(PREF_DOWNLOAD_DIRECTORY)
        val preferredFormat = findPreference<ListPreference>(PREF_PREFERRED_FORMAT)
        val testConnection = findPreference<Preference>(PREF_TEST_CONNECTION)

        listOfNotNull(
            contentEnabled,
            contentUrl,
            contentUsername,
            contentPassword,
            webEnabled,
            webUrl,
            downloadDirectory,
            preferredFormat,
            testConnection
        ).forEach { it.isPersistent = false }

        contentUrl?.setOnBindEditTextListener { editText ->
            editText.hint = getString(R.string.settings_calibre_hint_content_server_url)
            editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        contentUsername?.setOnBindEditTextListener { editText ->
            editText.hint = getString(R.string.settings_calibre_hint_content_server_username)
        }
        contentPassword?.setOnBindEditTextListener { editText ->
            editText.hint = getString(R.string.settings_calibre_hint_password)
            editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            editText.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        webUrl?.setOnBindEditTextListener { editText ->
            editText.hint = getString(R.string.settings_calibre_hint_web_url)
            editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }

        contentEnabled?.setOnPreferenceChangeListener { _, newValue ->
            updateCalibreConfig { it.copy(contentServerEnabled = newValue as? Boolean == true) }
            true
        }
        contentUrl?.setOnPreferenceChangeListener { preference, newValue ->
            val url = (newValue as? String).orEmpty().trim()
            validateAndSaveUrl(preference, url, isContentServer = true) {
                updateCalibreConfig { it.copy(contentServerUrl = url) }
            }
        }
        contentUsername?.setOnPreferenceChangeListener { _, newValue ->
            updateCalibreConfig { it.copy(contentServerUsername = (newValue as? String).orEmpty().trim()) }
            true
        }
        contentPassword?.setOnPreferenceChangeListener { _, newValue ->
            updateCalibreConfig { it.copy(contentServerPassword = (newValue as? String).orEmpty()) }
            true
        }
        webEnabled?.setOnPreferenceChangeListener { _, newValue ->
            updateCalibreConfig { it.copy(calibreWebEnabled = newValue as? Boolean == true) }
            true
        }
        webUrl?.setOnPreferenceChangeListener { preference, newValue ->
            val url = (newValue as? String).orEmpty().trim()
            validateAndSaveUrl(preference, url, isContentServer = false) {
                updateCalibreConfig { it.copy(calibreWebUrl = url) }
            }
        }
        downloadDirectory?.setOnPreferenceClickListener {
            folderPickerLauncher.launch(null)
            true
        }
        preferredFormat?.setOnPreferenceChangeListener { _, newValue ->
            val rawValue = (newValue as? String).orEmpty()
            val format = runCatching { BookFormat.valueOf(rawValue) }.getOrElse {
                AppLogger.event("ReaderSettingsFragment", "Invalid Calibre preferred format: $rawValue", "ui/settings/calibre")
                BookFormat.ANY
            }
            updateCalibreConfig { it.copy(preferredFormat = format) }
            true
        }
        testConnection?.setOnPreferenceClickListener {
            testContentServerConnection()
            true
        }

        lifecycleScope.launch {
            val config = calibreRepository.loadConfig()
            calibreConfig = config
            renderCalibreConfig(config)
        }
    }

    private fun validateAndSaveUrl(
        preference: Preference,
        url: String,
        isContentServer: Boolean,
        save: () -> Unit
    ): Boolean {
        val error = if (isContentServer) {
            CalibreUrlValidator.validateContentServerUrl(url)
        } else {
            CalibreUrlValidator.validateCalibreWebUrl(url)
        }
        if (error != null) {
            preference.summary = error
            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
            return false
        }
        save()
        return true
    }

    private fun updateCalibreConfig(transform: (CalibreConnectionConfig) -> CalibreConnectionConfig) {
        val current = calibreConfig ?: return
        val updated = transform(current)
        calibreConfig = updated
        renderCalibreConfig(updated)
        calibreSaveJob?.cancel()
        calibreSaveJob = lifecycleScope.launch { calibreRepository.saveConfig(updated) }
    }

    private fun renderCalibreConfig(config: CalibreConnectionConfig) {
        findPreference<SwitchPreferenceCompat>(PREF_CONTENT_SERVER_ENABLED)?.isChecked = config.contentServerEnabled
        findPreference<EditTextPreference>(PREF_CONTENT_SERVER_URL)?.text = config.contentServerUrl
        findPreference<EditTextPreference>(PREF_CONTENT_SERVER_USERNAME)?.text = config.contentServerUsername
        findPreference<EditTextPreference>(PREF_CONTENT_SERVER_PASSWORD)?.apply {
            text = config.contentServerPassword
            summary = if (config.contentServerPassword.isEmpty()) null else "••••••••"
        }
        findPreference<SwitchPreferenceCompat>(PREF_CALIBRE_WEB_ENABLED)?.isChecked = config.calibreWebEnabled
        findPreference<EditTextPreference>(PREF_CALIBRE_WEB_URL)?.text = config.calibreWebUrl
        findPreference<Preference>(PREF_DOWNLOAD_DIRECTORY)?.summary = config.downloadDirectory
        findPreference<ListPreference>(PREF_PREFERRED_FORMAT)?.value = config.preferredFormat.name
        updateCalibreEnabledState(config)
    }

    private fun updateCalibreEnabledState(config: CalibreConnectionConfig) {
        listOf(PREF_CONTENT_SERVER_URL, PREF_CONTENT_SERVER_USERNAME, PREF_CONTENT_SERVER_PASSWORD, PREF_TEST_CONNECTION).forEach { key ->
            findPreference<Preference>(key)?.isEnabled = config.contentServerEnabled
        }
        findPreference<Preference>(PREF_CALIBRE_WEB_URL)?.isEnabled = config.calibreWebEnabled
    }

    private fun persistFolderPermission(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { requireContext().contentResolver.takePersistableUriPermission(uri, flags) }
    }

    private fun testContentServerConnection() {
        val testPreference = findPreference<Preference>(PREF_TEST_CONNECTION) ?: return
        testPreference.summary = getString(R.string.settings_calibre_testing)
        testPreference.widgetLayoutResource = R.layout.preference_widget_progress
        testPreference.isEnabled = false

        lifecycleScope.launch {
            calibreConfig?.let { calibreRepository.saveConfig(it) }
            val result = calibreRepository.testContentServerConnection()
            testPreference.widgetLayoutResource = 0
            testPreference.isEnabled = calibreConfig?.contentServerEnabled == true
            testPreference.summary = when (result) {
                ConnectionTestResult.Success -> getString(R.string.settings_calibre_connected)
                is ConnectionTestResult.AuthRequired -> getString(R.string.settings_calibre_auth_required)
                is ConnectionTestResult.Failed -> buildString {
                    append("❌ ")
                    append(result.message)
                    result.httpCode?.let { append(" (HTTP ").append(it).append(')') }
                }
            }
        }
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

    companion object {
        private const val PREF_CONTENT_SERVER_ENABLED = "calibre_content_server_enabled"
        private const val PREF_CONTENT_SERVER_URL = "calibre_content_server_url"
        private const val PREF_CONTENT_SERVER_USERNAME = "calibre_content_server_username"
        private const val PREF_CONTENT_SERVER_PASSWORD = "calibre_content_server_password"
        private const val PREF_TEST_CONNECTION = "calibre_test_connection"
        private const val PREF_CALIBRE_WEB_ENABLED = "calibre_web_enabled"
        private const val PREF_CALIBRE_WEB_URL = "calibre_web_url"
        private const val PREF_DOWNLOAD_DIRECTORY = "calibre_download_directory"
        private const val PREF_PREFERRED_FORMAT = "calibre_preferred_format"
    }
}
