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

    private val readerPreferences: ReaderPreferences
        get() = (requireActivity() as? ReaderPreferencesOwner)?.readerPreferences
            ?: throw IllegalStateException("Parent activity must implement ReaderPreferencesOwner")

    private val actions = ReaderTapAction.values()
    private lateinit var actionLabels: Array<String>
    private lateinit var zoneInputs: Map<ReaderTapZone, MaterialAutoCompleteTextView>

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

        setupDropdowns()
        binding.resetButton.setOnClickListener { readerPreferences.resetTapActions() }

        updateSelections(readerPreferences.tapActions.value)

        readerPreferences.tapActions.collectWhileStarted(viewLifecycleOwner) { actionsMap ->
            updateSelections(actionsMap)
        }
    }

    private fun setupDropdowns() {
        zoneInputs.forEach { (zone, input) ->
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

