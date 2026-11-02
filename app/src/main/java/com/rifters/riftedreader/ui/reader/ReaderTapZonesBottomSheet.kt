package com.rifters.riftedreader.ui.reader

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.rifters.riftedreader.R
import com.rifters.riftedreader.data.preferences.ReaderPreferences
import com.rifters.riftedreader.databinding.DialogReaderTapZonesBinding
import com.rifters.riftedreader.util.collectWhileStarted

class ReaderTapZonesBottomSheet : BottomSheetDialogFragment() {

    private var _binding: DialogReaderTapZonesBinding? = null
    private val binding get() = _binding!!

    private val readerPreferences: ReaderPreferences by lazy {
        (requireActivity() as? ReaderPreferencesOwner)?.readerPreferences
            ?: ReaderPreferences(requireContext().applicationContext)
    }

    private val actions = ReaderTapAction.values()
    private lateinit var actionLabels: Array<String>
    private lateinit var zoneInputs: Map<ReaderTapZone, MaterialAutoCompleteTextView>
    private lateinit var gridButtons: Map<ReaderTapZone, com.google.android.material.button.MaterialButton>

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogReaderTapZonesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        actionLabels = actions.map { getString(it.labelRes()) }.toTypedArray()
        zoneInputs = mapOf(
            ReaderTapZone.TOP_LEFT to binding.zoneTopLeftInput,
            ReaderTapZone.TOP_CENTER to binding.zoneTopCenterInput,
            ReaderTapZone.TOP_RIGHT to binding.zoneTopRightInput,
            ReaderTapZone.MIDDLE_LEFT to binding.zoneMiddleLeftInput,
            ReaderTapZone.CENTER to binding.zoneCenterInput,
            ReaderTapZone.MIDDLE_RIGHT to binding.zoneMiddleRightInput,
            ReaderTapZone.BOTTOM_LEFT to binding.zoneBottomLeftInput,
            ReaderTapZone.BOTTOM_CENTER to binding.zoneBottomCenterInput,
            ReaderTapZone.BOTTOM_RIGHT to binding.zoneBottomRightInput
        )
        gridButtons = mapOf(
            ReaderTapZone.TOP_LEFT to binding.gridTopLeft,
            ReaderTapZone.TOP_CENTER to binding.gridTopCenter,
            ReaderTapZone.TOP_RIGHT to binding.gridTopRight,
            ReaderTapZone.MIDDLE_LEFT to binding.gridMiddleLeft,
            ReaderTapZone.CENTER to binding.gridCenter,
            ReaderTapZone.MIDDLE_RIGHT to binding.gridMiddleRight,
            ReaderTapZone.BOTTOM_LEFT to binding.gridBottomLeft,
            ReaderTapZone.BOTTOM_CENTER to binding.gridBottomCenter,
            ReaderTapZone.BOTTOM_RIGHT to binding.gridBottomRight
        )

        setupDropdowns()
        setupVisualGrid()
        binding.resetButton.setOnClickListener { readerPreferences.resetTapActions() }

        updateSelections(readerPreferences.tapActions.value)

        readerPreferences.tapActions.collectWhileStarted(viewLifecycleOwner) { actionsMap ->
            updateSelections(actionsMap)
        }
    }

    private fun setupVisualGrid() {
        gridButtons.forEach { (zone, button) ->
            button.setOnClickListener {
                showActionPickerDialog(zone)
            }
        }
    }

    private fun showActionPickerDialog(zone: ReaderTapZone) {
        val currentAction = readerPreferences.getTapAction(zone)
        val selectedIndex = actions.indexOf(currentAction).coerceAtLeast(0)

        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(zoneTitle(zone))
            .setSingleChoiceItems(actionLabels, selectedIndex) { dialog, which ->
                readerPreferences.updateTapAction(zone, actions[which])
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun zoneTitle(zone: ReaderTapZone): Int = when (zone) {
        ReaderTapZone.TOP_LEFT -> R.string.reader_zone_top_left
        ReaderTapZone.TOP_CENTER -> R.string.reader_zone_top_center
        ReaderTapZone.TOP_RIGHT -> R.string.reader_zone_top_right
        ReaderTapZone.MIDDLE_LEFT -> R.string.reader_zone_middle_left
        ReaderTapZone.CENTER -> R.string.reader_zone_center
        ReaderTapZone.MIDDLE_RIGHT -> R.string.reader_zone_middle_right
        ReaderTapZone.BOTTOM_LEFT -> R.string.reader_zone_bottom_left
        ReaderTapZone.BOTTOM_CENTER -> R.string.reader_zone_bottom_center
        ReaderTapZone.BOTTOM_RIGHT -> R.string.reader_zone_bottom_right
    }

    private fun setupDropdowns() {
        zoneInputs.forEach { (zone, input) ->
            input.keyListener = null
            input.setOnClickListener { input.showDropDown() }
            input.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    input.showDropDown()
                }
            }
            input.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, actionLabels))
            input.setOnItemClickListener { _, _, position, _ ->
                readerPreferences.updateTapAction(zone, actions[position])
            }
        }
    }

    private fun updateSelections(actionsMap: Map<ReaderTapZone, ReaderTapAction>) {
        zoneInputs.forEach { (zone, input) ->
            val action = actionsMap[zone] ?: ReaderTapAction.NONE
            val label = getString(action.labelRes())
            if (input.text?.toString() != label) {
                input.setText(label, false)
            }
        }
        gridButtons.forEach { (zone, button) ->
            val action = actionsMap[zone] ?: ReaderTapAction.NONE
            val shortZoneName = when (zone) {
                ReaderTapZone.TOP_LEFT -> "TL"
                ReaderTapZone.TOP_CENTER -> "Top"
                ReaderTapZone.TOP_RIGHT -> "TR"
                ReaderTapZone.MIDDLE_LEFT -> "Left"
                ReaderTapZone.CENTER -> "Center"
                ReaderTapZone.MIDDLE_RIGHT -> "Right"
                ReaderTapZone.BOTTOM_LEFT -> "BL"
                ReaderTapZone.BOTTOM_CENTER -> "Bottom"
                ReaderTapZone.BOTTOM_RIGHT -> "BR"
            }
            val shortAction = when (action) {
                ReaderTapAction.NONE -> "None"
                ReaderTapAction.BACK -> "Back"
                ReaderTapAction.TOGGLE_CONTROLS -> "Menu"
                ReaderTapAction.NEXT_PAGE -> "Next"
                ReaderTapAction.PREVIOUS_PAGE -> "Prev"
                ReaderTapAction.OPEN_SETTINGS -> "Settings"
                ReaderTapAction.START_TTS -> "TTS"
            }
            button.text = "$shortZoneName\n$shortAction"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun show(manager: androidx.fragment.app.FragmentManager) {
            ReaderTapZonesBottomSheet().show(manager, TAG)
        }

        private const val TAG = "ReaderTapZones"
    }
}

private fun ReaderTapAction.labelRes(): Int = when (this) {
    ReaderTapAction.NONE -> R.string.reader_tap_action_none
    ReaderTapAction.BACK -> R.string.reader_tap_action_back
    ReaderTapAction.TOGGLE_CONTROLS -> R.string.reader_tap_action_toggle_controls
    ReaderTapAction.NEXT_PAGE -> R.string.reader_tap_action_next_page
    ReaderTapAction.PREVIOUS_PAGE -> R.string.reader_tap_action_previous_page
    ReaderTapAction.OPEN_SETTINGS -> R.string.reader_tap_action_open_settings
    ReaderTapAction.START_TTS -> R.string.reader_tap_action_start_tts
}

