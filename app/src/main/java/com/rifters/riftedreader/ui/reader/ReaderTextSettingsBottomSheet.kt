package com.rifters.riftedreader.ui.reader

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.google.android.material.slider.Slider
import com.rifters.riftedreader.R
import com.rifters.riftedreader.data.preferences.ReaderMode
import com.rifters.riftedreader.data.preferences.ReaderTheme
import com.rifters.riftedreader.domain.pagination.PaginationMode
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
    private val preferencesOwner
        get() = requireActivity() as? ReaderPreferencesOwner
            ?: throw IllegalStateException("Parent activity must implement ReaderPreferencesOwner")

    private val settingsViewModel: ReaderSettingsViewModel by activityViewModels {
        ReaderSettingsViewModel.Factory(preferencesOwner.readerPreferences)
    }
    private val readerViewModel: ReaderViewModel by activityViewModels()
    private val readerPreferences
        get() = preferencesOwner.readerPreferences
    private var syncingSettings = false
    private var syncedReaderMode = ReaderMode.PAGINATED

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

        setupFlexPaginatorSwitch()
        setupTextSizeSlider()
        setupLineHeightSlider()
        setupThemeChips()
        setupModeChips()
        setupPaginationChips()

        readerViewModel.readerSettings.collectWhileStarted(viewLifecycleOwner) { settings ->
            syncingSettings = true
            try {
                if (binding.flexPaginatorSwitch.isChecked != settings.flexPaginatorEnabled) {
                    binding.flexPaginatorSwitch.isChecked = settings.flexPaginatorEnabled
                }
                if (binding.textSizeSlider.value.roundToInt() != settings.textSizeSp.roundToInt()) {
                    binding.textSizeSlider.value = settings.textSizeSp
                }
                // Convert float line height (1.0-2.0) to integer slider value (10-20)
                val sliderValue = (settings.lineHeightMultiplier * 10).roundToInt().toFloat()
                if (binding.lineHeightSlider.value.roundToInt() != sliderValue.roundToInt()) {
                    binding.lineHeightSlider.value = sliderValue
                }
                selectThemeChip(settings.theme)
                syncedReaderMode = settings.mode
                selectModeChip(settings.mode)
                selectPaginationChip(settings.paginationMode)
                binding.textSizeValue.text = getString(R.string.reader_text_size_value, settings.textSizeSp.roundToInt())
                binding.lineHeightValue.text = getString(R.string.reader_line_height_value, settings.lineHeightMultiplier)
            } finally {
                syncingSettings = false
            }
        }
    }

    private fun setupFlexPaginatorSwitch() {
        binding.flexPaginatorSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (syncingSettings) return@setOnCheckedChangeListener
            if (isChecked) {
                showFlexPaginatorConfirmation()
            } else {
                com.rifters.riftedreader.util.AppLogger.userAction(
                    "ReaderTextSettingsBottomSheet",
                    "Experimental page layout disabled",
                    "ui/settings/change"
                )
                readerPreferences.updateSettings { current -> current.copy(flexPaginatorEnabled = false) }
            }
        }
    }

    private fun showFlexPaginatorConfirmation() {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.reader_flex_paginator_confirm_message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                com.rifters.riftedreader.util.AppLogger.userAction(
                    "ReaderTextSettingsBottomSheet",
                    "Experimental page layout enabled",
                    "ui/settings/change"
                )
                readerPreferences.updateSettings { current -> current.copy(flexPaginatorEnabled = true) }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                revertFlexPaginatorSwitch()
            }
            .setOnCancelListener {
                revertFlexPaginatorSwitch()
            }
            .show()
    }

    private fun revertFlexPaginatorSwitch() {
        syncingSettings = true
        try {
            binding.flexPaginatorSwitch.isChecked = false
        } finally {
            syncingSettings = false
        }
    }

    private fun setupTextSizeSlider() {
        binding.textSizeSlider.addOnChangeListener { _: Slider, value, fromUser ->
            if (fromUser) {
                com.rifters.riftedreader.util.AppLogger.userAction("ReaderTextSettingsBottomSheet", "Text size slider changed to ${value.roundToInt()}sp", "ui/settings/change")
                settingsViewModel.updateTextSize(value)
            }
            binding.textSizeValue.text = getString(R.string.reader_text_size_value, value.roundToInt())
        }
    }

    private fun setupLineHeightSlider() {
        binding.lineHeightSlider.addOnChangeListener { _: Slider, value, fromUser ->
            if (fromUser) {
                // Convert integer slider value (10-20) to float line height (1.0-2.0)
                val lineHeight = value / 10f
                com.rifters.riftedreader.util.AppLogger.userAction("ReaderTextSettingsBottomSheet", "Line height slider changed to $lineHeight", "ui/settings/change")
                settingsViewModel.updateLineHeight(lineHeight)
            }
            // Convert integer slider value to float for display
            val lineHeight = value / 10f
            binding.lineHeightValue.text = getString(R.string.reader_line_height_value, lineHeight)
        }
    }

    private fun setupThemeChips() {
        binding.themeChipGroup.setOnCheckedStateChangeListener { group, checkedIds ->
            if (syncingSettings) return@setOnCheckedStateChangeListener
            val id = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val chip = group.findViewById<Chip>(id)
            val theme = chip?.tag as? ReaderTheme ?: return@setOnCheckedStateChangeListener
            com.rifters.riftedreader.util.AppLogger.userAction("ReaderTextSettingsBottomSheet", "Theme changed to $theme", "ui/settings/change")
            settingsViewModel.updateTheme(theme)
        }

        binding.themeChipLight.tag = ReaderTheme.LIGHT
        binding.themeChipDark.tag = ReaderTheme.DARK
        binding.themeChipSepia.tag = ReaderTheme.SEPIA
        binding.themeChipBlack.tag = ReaderTheme.BLACK
    }
    
    private fun setupModeChips() {
        binding.modeChipGroup.setOnCheckedStateChangeListener { group, checkedIds ->
            if (syncingSettings) return@setOnCheckedStateChangeListener
            val id = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val chip = group.findViewById<Chip>(id)
            val mode = chip?.tag as? ReaderMode ?: return@setOnCheckedStateChangeListener
            if (syncedReaderMode == mode) return@setOnCheckedStateChangeListener
            com.rifters.riftedreader.util.AppLogger.userAction("ReaderTextSettingsBottomSheet", "Reader mode changed to $mode", "ui/settings/change")
            readerViewModel.toggleReaderMode()
        }

        binding.modeChipPage.tag = ReaderMode.PAGINATED
        binding.modeChipScroll.tag = ReaderMode.SCROLL
    }

    private fun setupPaginationChips() {
        binding.paginationChipGroup.setOnCheckedStateChangeListener { group, checkedIds ->
            if (syncingSettings) return@setOnCheckedStateChangeListener
            val id = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val chip = group.findViewById<Chip>(id)
            val mode = chip?.tag as? PaginationMode ?: return@setOnCheckedStateChangeListener
            com.rifters.riftedreader.util.AppLogger.userAction(
                "ReaderTextSettingsBottomSheet",
                "Pagination mode changed to $mode",
                "ui/settings/change"
            )
            settingsViewModel.updatePaginationMode(mode)
        }

        binding.paginationChipChapter.tag = PaginationMode.CHAPTER_BASED
        binding.paginationChipContinuous.tag = PaginationMode.CONTINUOUS
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
    
    private fun selectModeChip(mode: ReaderMode) {
        val targetId = when (mode) {
            ReaderMode.PAGINATED -> binding.modeChipPage.id
            ReaderMode.SCROLL -> binding.modeChipScroll.id
        }
        if (binding.modeChipGroup.checkedChipId != targetId) {
            binding.modeChipGroup.check(targetId)
        }
    }

    private fun selectPaginationChip(mode: PaginationMode) {
        val targetId = when (mode) {
            PaginationMode.CHAPTER_BASED -> binding.paginationChipChapter.id
            PaginationMode.CONTINUOUS -> binding.paginationChipContinuous.id
        }
        if (binding.paginationChipGroup.checkedChipId != targetId) {
            binding.paginationChipGroup.check(targetId)
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
