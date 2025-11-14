package com.rifters.riftedreader.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.rifters.riftedreader.R
import kotlinx.coroutines.launch

class TTSReplacementsFragment : Fragment(R.layout.fragment_tts_replacements) {

    private val viewModel: TTSReplacementsViewModel by viewModels()
    private var adapter: TTSReplacementsAdapter? = null
    private var recyclerView: RecyclerView? = null
    private var emptyView: TextView? = null
    private var fab: ExtendedFloatingActionButton? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.title = getString(R.string.tts_replacements_title)

        recyclerView = view.findViewById(R.id.replacementsRecyclerView)
        emptyView = view.findViewById(R.id.emptyView)
        fab = view.findViewById(R.id.addReplacementFab)

        val adapter = TTSReplacementsAdapter(
            onToggle = viewModel::toggleEnabled,
            onEdit = ::onEditRule,
            onDelete = ::onDeleteRule
        )
        this.adapter = adapter
        recyclerView?.adapter = adapter
        recyclerView?.setHasFixedSize(true)

        fab?.setOnClickListener { showEditorDialog(null) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.items.collect { items ->
                        adapter.submitList(items)
                        emptyView?.isVisible = items.isEmpty()
                    }
                }
                launch {
                    viewModel.events.collect { event ->
                        when (event) {
                            is TTSReplacementsUiEvent.ShowMessage -> showMessage(event.message)
                            TTSReplacementsUiEvent.ShowLoadError -> showMessage(getString(R.string.tts_replacements_load_error))
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        recyclerView = null
        emptyView = null
        fab = null
        adapter = null
        super.onDestroyView()
    }

    private fun onEditRule(item: TTSReplacementUiItem) {
        showEditorDialog(item)
    }

    private fun onDeleteRule(item: TTSReplacementUiItem) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.tts_replacements_delete)
            .setMessage(R.string.tts_replacements_delete_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.deleteRule(item.id)
                showMessage(getString(R.string.tts_replacements_deleted))
            }
            .show()
    }

    private fun showEditorDialog(item: TTSReplacementUiItem?) {
        val context = requireContext()
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_tts_replacement, null)
        val patternLayout: TextInputLayout = dialogView.findViewById(R.id.patternInputLayout)
        val patternEditText: TextInputEditText = dialogView.findViewById(R.id.patternEditText)
        val replacementLayout: TextInputLayout = dialogView.findViewById(R.id.replacementInputLayout)
        val replacementEditText: TextInputEditText = dialogView.findViewById(R.id.replacementEditText)
        val typeDropdown: MaterialAutoCompleteTextView = dialogView.findViewById(R.id.typeDropdown)
        val commandLayout: TextInputLayout = dialogView.findViewById(R.id.commandInputLayout)
        val commandDropdown: MaterialAutoCompleteTextView = dialogView.findViewById(R.id.commandDropdown)
        val enabledSwitch: SwitchMaterial = dialogView.findViewById(R.id.enabledSwitch)

        val typeOptions = listOf(
            ReplacementTypeOption(TTSReplacementUiType.SIMPLE, getString(R.string.tts_replacement_type_simple)),
            ReplacementTypeOption(TTSReplacementUiType.REGEX, getString(R.string.tts_replacement_type_regex)),
            ReplacementTypeOption(TTSReplacementUiType.COMMAND, getString(R.string.tts_replacement_type_command))
        )
        val typeAdapter = ArrayAdapter(context, android.R.layout.simple_list_item_1, typeOptions.map { it.label })
        typeDropdown.setAdapter(typeAdapter)

        val commandOptions = listOf(
            CommandOption("ttsPAUSE", getString(R.string.tts_replacement_command_pause)),
            CommandOption("ttsSKIP", getString(R.string.tts_replacement_command_skip)),
            CommandOption("ttsSTOP", getString(R.string.tts_replacement_command_stop)),
            CommandOption("ttsNEXT", getString(R.string.tts_replacement_command_next))
        )
        val commandAdapter = ArrayAdapter(context, android.R.layout.simple_list_item_1, commandOptions.map { it.label })
        commandDropdown.setAdapter(commandAdapter)

        var selectedType = item?.type ?: TTSReplacementUiType.SIMPLE
        var selectedCommandValue = item?.takeIf { it.type == TTSReplacementUiType.COMMAND }?.replacement

        fun updateTypeUi(type: TTSReplacementUiType) {
            selectedType = type
            val isCommand = type == TTSReplacementUiType.COMMAND
            commandLayout.isVisible = isCommand
            replacementLayout.isVisible = !isCommand
            replacementLayout.error = null
            if (isCommand) {
                if (selectedCommandValue == null) {
                    selectedCommandValue = commandOptions.first().value
                    commandDropdown.setText(commandOptions.first().label, false)
                } else {
                    val command = commandOptions.firstOrNull { it.value == selectedCommandValue }
                    commandDropdown.setText(command?.label ?: commandOptions.first().label, false)
                }
                commandLayout.error = null
            } else {
                selectedCommandValue = null
                commandLayout.error = null
            }
        }

        typeDropdown.setOnItemClickListener { _, _, position, _ ->
            val option = typeOptions.getOrNull(position) ?: return@setOnItemClickListener
            updateTypeUi(option.type)
        }

        commandDropdown.setOnItemClickListener { _, _, position, _ ->
            val option = commandOptions.getOrNull(position) ?: return@setOnItemClickListener
            selectedCommandValue = option.value
            commandLayout.error = null
        }

        typeDropdown.setOnClickListener { typeDropdown.showDropDown() }
        typeDropdown.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) typeDropdown.showDropDown() }
        commandDropdown.setOnClickListener { commandDropdown.showDropDown() }
        commandDropdown.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) commandDropdown.showDropDown() }

        patternEditText.setText(item?.pattern.orEmpty())
        replacementEditText.setText(item?.takeUnless { it.type == TTSReplacementUiType.COMMAND }?.replacement.orEmpty())
        enabledSwitch.isChecked = item?.enabled ?: true

        val initialTypeIndex = typeOptions.indexOfFirst { it.type == selectedType }.coerceAtLeast(0)
        typeDropdown.setText(typeOptions[initialTypeIndex].label, false)
        updateTypeUi(selectedType)

        if (selectedType == TTSReplacementUiType.COMMAND) {
            val initialCommand = commandOptions.firstOrNull { it.value == selectedCommandValue }
            commandDropdown.setText(initialCommand?.label ?: commandOptions.first().label, false)
            selectedCommandValue = initialCommand?.value ?: commandOptions.first().value
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(if (item == null) R.string.tts_replacements_add else R.string.tts_replacements_edit)
            .setView(dialogView)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .create()

        dialog.setOnShowListener {
            val positive = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            positive.setOnClickListener {
                val pattern = patternEditText.text?.toString().orEmpty().trim()
                val replacementValue = when (selectedType) {
                    TTSReplacementUiType.COMMAND -> {
                        val commandValue = selectedCommandValue ?: commandOptions.first().value
                        selectedCommandValue = commandValue
                        if (commandDropdown.text.isNullOrBlank()) {
                            val commandLabel = commandOptions.firstOrNull { it.value == commandValue }?.label
                                ?: commandOptions.first().label
                            commandDropdown.setText(commandLabel, false)
                        }
                        if (commandValue.isEmpty()) {
                            commandLayout.error = getString(R.string.tts_replacement_error_replacement_required)
                            return@setOnClickListener
                        }
                        commandLayout.error = null
                        commandValue
                    }
                    else -> {
                        val value = replacementEditText.text?.toString().orEmpty().trim()
                        if (value.isEmpty()) {
                            replacementLayout.error = getString(R.string.tts_replacement_error_replacement_required)
                            return@setOnClickListener
                        }
                        replacementLayout.error = null
                        value
                    }
                }
                if (pattern.isEmpty()) {
                    patternLayout.error = getString(R.string.tts_replacement_error_pattern_required)
                    return@setOnClickListener
                }
                patternLayout.error = null

                val enabled = enabledSwitch.isChecked
                viewModel.submitRule(item?.id, pattern, replacementValue, selectedType, enabled)
                if (item == null) {
                    showMessage(getString(R.string.tts_replacements_rule_added))
                } else {
                    showMessage(getString(R.string.tts_replacements_rule_updated))
                }
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun showMessage(message: String) {
        val root = view ?: return
        val snackbar = Snackbar.make(root, message, Snackbar.LENGTH_LONG)
        fab?.let { snackbar.setAnchorView(it) }
        snackbar.show()
    }

    private data class ReplacementTypeOption(
        val type: TTSReplacementUiType,
        val label: String
    )

    private data class CommandOption(
        val value: String,
        val label: String
    )
}
