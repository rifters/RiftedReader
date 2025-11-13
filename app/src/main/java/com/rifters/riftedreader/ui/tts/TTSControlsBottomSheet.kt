package com.rifters.riftedreader.ui.tts

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.core.os.bundleOf
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.slider.Slider
import com.rifters.riftedreader.R
import com.rifters.riftedreader.data.preferences.TTSPreferences
import com.rifters.riftedreader.databinding.DialogTtsControlsBinding
import com.rifters.riftedreader.domain.tts.TTSConfiguration
import com.rifters.riftedreader.domain.tts.TTSEngine
import com.rifters.riftedreader.domain.tts.TTSService
import java.util.Locale

class TTSControlsBottomSheet : BottomSheetDialogFragment() {

    private var _binding: DialogTtsControlsBinding? = null
    private val binding get() = _binding!!

    private lateinit var preferences: TTSPreferences
    private var ttsEngine: TTSEngine? = null
    private var voiceOptions: List<VoiceOption> = emptyList()
    private var selectedLanguageTag: String? = null

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

        selectedLanguageTag = preferences.languageTag
        binding.speedSlider.value = preferences.speed
        binding.pitchSlider.value = preferences.pitch
        binding.highlightSwitch.isChecked = preferences.highlightSentence
        binding.autoScrollSwitch.isChecked = preferences.autoScroll
        binding.voiceInputLayout.isEnabled = false

        setupVoiceDropdown()

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

    private fun setupVoiceDropdown() {
        binding.voiceDropdown.apply {
            keyListener = null
            setOnClickListener { showDropDown() }
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    showDropDown()
                }
            }
        }
        val engine = TTSEngine(requireContext())
        ttsEngine = engine
        engine.initialize { success ->
            if (!isAdded) {
                engine.shutdown()
                return@initialize
            }
            val locales = if (success) engine.getAvailableLanguages().orEmpty() else emptySet()
            val options = buildVoiceOptions(locales)
            voiceOptions = options
            val labels = options.map { it.displayName }
            val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, labels)
            binding.voiceDropdown.post {
                binding.voiceDropdown.setAdapter(adapter)
                val initialIndex = options.indexOfFirst { it.languageTag == selectedLanguageTag }
                    .takeIf { it >= 0 } ?: 0
                selectedLanguageTag = options.getOrNull(initialIndex)?.languageTag
                binding.voiceDropdown.setText(options.getOrNull(initialIndex)?.displayName.orEmpty(), false)
                binding.voiceInputLayout.isEnabled = options.size > 1
            }
            binding.voiceDropdown.setOnItemClickListener { _, _, position, _ ->
                val option = options.getOrNull(position)
                selectedLanguageTag = option?.languageTag
                preferences.languageTag = selectedLanguageTag
                notifyService()
            }
        }
    }

    private fun buildVoiceOptions(locales: Set<Locale>): List<VoiceOption> {
        val options = mutableListOf(VoiceOption(null, getString(R.string.tts_voice_default)))
        val seen = mutableSetOf<String>()
        locales.sortedBy { it.getDisplayName(Locale.getDefault()).lowercase(Locale.getDefault()) }
            .forEach { locale ->
                val tag = locale.toLanguageTag()
                if (seen.add(tag)) {
                    val displayName = locale.getDisplayName(locale).replaceFirstChar { ch ->
                        if (ch.isLowerCase()) ch.titlecase(locale) else ch.toString()
                    }
                    options += VoiceOption(tag, displayName)
                }
            }
        return options
    }

    private fun currentConfiguration(): TTSConfiguration = TTSConfiguration(
        speed = binding.speedSlider.value,
        pitch = binding.pitchSlider.value,
        autoScroll = binding.autoScrollSwitch.isChecked,
        highlightSentence = binding.highlightSwitch.isChecked,
        languageTag = selectedLanguageTag
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
        ttsEngine?.shutdown()
        ttsEngine = null
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

    private data class VoiceOption(val languageTag: String?, val displayName: String)
}
