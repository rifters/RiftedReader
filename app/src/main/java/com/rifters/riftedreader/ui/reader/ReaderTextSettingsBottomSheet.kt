package com.rifters.riftedreader.ui.reader

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.google.android.material.slider.Slider
import com.rifters.riftedreader.R
import com.rifters.riftedreader.data.preferences.ReaderTheme
import com.rifters.riftedreader.databinding.DialogReaderTextSettingsBinding
import com.rifters.riftedreader.util.collectWhileStarted
import kotlin.math.roundToInt

/**
 * Bottom sheet for adjusting text appearance, inspired by LibreraReader's reader settings dialog
 * (`foobnix/ui2/reader/ReaderSettingsDialog.java`, lines 90-210).
 */
class ReaderTextSettingsBottomSheet : BottomSheetDialogFragment() {

    private var _binding: DialogReaderTextSettingsBinding? = null
    private val binding get() = _binding!!

    private val settingsViewModel: ReaderSettingsViewModel by activityViewModels {
        val owner = requireActivity() as? ReaderPreferencesOwner
            ?: throw IllegalStateException("Parent activity must implement ReaderPreferencesOwner")
        ReaderSettingsViewModel.Factory(owner.readerPreferences)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogReaderTextSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupTextSizeSlider()
        setupLineHeightSlider()
        setupThemeChips()
        setupModeChips()

        settingsViewModel.settings.collectWhileStarted(viewLifecycleOwner) { settings ->
            if (binding.textSizeSlider.value.roundToInt() != settings.textSizeSp.roundToInt()) {
                binding.textSizeSlider.value = settings.textSizeSp
            }
            if (!binding.lineHeightSlider.value.equalsWithin(settings.lineHeightMultiplier)) {
                binding.lineHeightSlider.value = settings.lineHeightMultiplier
            }
            selectThemeChip(settings.theme)
            selectModeChip(settings.mode)
            binding.textSizeValue.text = getString(R.string.reader_text_size_value, settings.textSizeSp.roundToInt())
            binding.lineHeightValue.text = getString(R.string.reader_line_height_value, settings.lineHeightMultiplier)
        }
    }

    private fun setupTextSizeSlider() {
        binding.textSizeSlider.addOnChangeListener { _: Slider, value, fromUser ->
            if (fromUser) {
                settingsViewModel.updateTextSize(value)
            }
            binding.textSizeValue.text = getString(R.string.reader_text_size_value, value.roundToInt())
        }
    }

    private fun setupLineHeightSlider() {
        binding.lineHeightSlider.addOnChangeListener { _: Slider, value, fromUser ->
            if (fromUser) {
                settingsViewModel.updateLineHeight(value)
            }
            binding.lineHeightValue.text = getString(R.string.reader_line_height_value, value)
        }
    }

    private fun setupThemeChips() {
        binding.themeChipGroup.setOnCheckedStateChangeListener { group, checkedIds ->
            val id = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val chip = group.findViewById<Chip>(id)
            val theme = chip?.tag as? ReaderTheme ?: return@setOnCheckedStateChangeListener
            settingsViewModel.updateTheme(theme)
        }

        binding.themeChipLight.tag = ReaderTheme.LIGHT
        binding.themeChipDark.tag = ReaderTheme.DARK
        binding.themeChipSepia.tag = ReaderTheme.SEPIA
        binding.themeChipBlack.tag = ReaderTheme.BLACK
    }
    
    private fun setupModeChips() {
        binding.modeChipGroup.setOnCheckedStateChangeListener { group, checkedIds ->
            val id = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val chip = group.findViewById<Chip>(id)
            val mode = chip?.tag as? com.rifters.riftedreader.data.preferences.ReaderMode ?: return@setOnCheckedStateChangeListener
            settingsViewModel.updateMode(mode)
        }

        binding.modeChipScroll.tag = com.rifters.riftedreader.data.preferences.ReaderMode.SCROLL
        binding.modeChipPage.tag = com.rifters.riftedreader.data.preferences.ReaderMode.PAGE
    }

    private fun selectThemeChip(theme: ReaderTheme) {
        val targetId = when (theme) {
            ReaderTheme.LIGHT -> binding.themeChipLight.id
            ReaderTheme.DARK -> binding.themeChipDark.id
            ReaderTheme.SEPIA -> binding.themeChipSepia.id
            ReaderTheme.BLACK -> binding.themeChipBlack.id
        }
        if (binding.themeChipGroup.checkedChipId != targetId) {
            binding.themeChipGroup.check(targetId)
        }
    }
    
    private fun selectModeChip(mode: com.rifters.riftedreader.data.preferences.ReaderMode) {
        val targetId = when (mode) {
            com.rifters.riftedreader.data.preferences.ReaderMode.SCROLL -> binding.modeChipScroll.id
            com.rifters.riftedreader.data.preferences.ReaderMode.PAGE -> binding.modeChipPage.id
        }
        if (binding.modeChipGroup.checkedChipId != targetId) {
            binding.modeChipGroup.check(targetId)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun show(manager: androidx.fragment.app.FragmentManager) {
            ReaderTextSettingsBottomSheet().show(manager, "ReaderTextSettings")
        }
    }
}

private fun Float.equalsWithin(other: Float, epsilon: Float = 0.01f): Boolean = kotlin.math.abs(this - other) <= epsilon
