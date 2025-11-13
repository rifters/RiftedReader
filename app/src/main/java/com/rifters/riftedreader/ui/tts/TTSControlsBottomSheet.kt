package com.rifters.riftedreader.ui.tts

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.slider.Slider
import com.rifters.riftedreader.R
import com.rifters.riftedreader.data.preferences.TTSPreferences
import com.rifters.riftedreader.databinding.DialogTtsControlsBinding
import com.rifters.riftedreader.domain.tts.TTSConfiguration
import com.rifters.riftedreader.domain.tts.TTSService

class TTSControlsBottomSheet : BottomSheetDialogFragment() {

    private var _binding: DialogTtsControlsBinding? = null
    private val binding get() = _binding!!

    private lateinit var preferences: TTSPreferences

    override fun onAttach(context: Context) {
        super.onAttach(context)
        preferences = TTSPreferences(context)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogTtsControlsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val currentText = arguments?.getString(ARG_TEXT).orEmpty()

        binding.speedSlider.value = preferences.speed
        binding.pitchSlider.value = preferences.pitch
        binding.highlightSwitch.isChecked = preferences.highlightSentence
        binding.autoScrollSwitch.isChecked = preferences.autoScroll

        setupSlider(binding.speedSlider) { value ->
            preferences.speed = value
            notifyService()
        }
        setupSlider(binding.pitchSlider) { value ->
            preferences.pitch = value
            notifyService()
        }

        binding.highlightSwitch.setOnCheckedChangeListener { _, isChecked ->
            preferences.highlightSentence = isChecked
            notifyService()
        }
        binding.autoScrollSwitch.setOnCheckedChangeListener { _, isChecked ->
            preferences.autoScroll = isChecked
            notifyService()
        }

        binding.playButton.setOnClickListener {
            if (currentText.isNotBlank()) {
                val configuration = currentConfiguration()
                TTSService.start(requireContext(), currentText, configuration)
            } else {
                binding.statusText.text = ""
            }
        }
        binding.pauseButton.setOnClickListener {
            TTSService.pause(requireContext())
        }
        binding.stopButton.setOnClickListener {
            TTSService.stop(requireContext())
        }

        binding.statusText.text = if (currentText.isBlank()) {
            binding.playButton.isEnabled = false
            binding.statusText.context.getString(R.string.tts_status_empty_selection)
        } else {
            binding.playButton.isEnabled = true
            binding.statusText.context.getString(R.string.tts_status_ready)
        }
    }

    private fun currentConfiguration(): TTSConfiguration = TTSConfiguration(
        speed = binding.speedSlider.value,
        pitch = binding.pitchSlider.value,
        autoScroll = binding.autoScrollSwitch.isChecked,
        highlightSentence = binding.highlightSwitch.isChecked
    )

    private fun setupSlider(slider: Slider, onValue: (Float) -> Unit) {
        slider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                onValue(value)
            }
        }
    }

    private fun notifyService() {
        TTSService.updateConfiguration(requireContext(), currentConfiguration())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_TEXT = "arg_text"

        fun show(manager: androidx.fragment.app.FragmentManager, text: String) {
            val fragment = TTSControlsBottomSheet().apply {
                arguments = bundleOf(ARG_TEXT to text)
            }
            fragment.show(manager, TTSControlsBottomSheet::class.java.simpleName)
        }
    }
}
