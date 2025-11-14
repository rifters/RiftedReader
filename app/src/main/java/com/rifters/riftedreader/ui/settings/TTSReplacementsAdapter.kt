package com.rifters.riftedreader.ui.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.switchmaterial.SwitchMaterial
import com.rifters.riftedreader.R

internal class TTSReplacementsAdapter(
    private val onToggle: (id: Long, enabled: Boolean) -> Unit,
    private val onEdit: (TTSReplacementUiItem) -> Unit,
    private val onDelete: (TTSReplacementUiItem) -> Unit
) : ListAdapter<TTSReplacementUiItem, TTSReplacementsAdapter.ViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_tts_replacement, parent, false)
        return ViewHolder(view, onToggle, onEdit, onDelete)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        itemView: View,
        private val onToggle: (id: Long, enabled: Boolean) -> Unit,
        private val onEdit: (TTSReplacementUiItem) -> Unit,
        private val onDelete: (TTSReplacementUiItem) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val patternText: TextView = itemView.findViewById(R.id.patternText)
        private val replacementText: TextView = itemView.findViewById(R.id.replacementText)
        private val enabledSwitch: SwitchMaterial = itemView.findViewById(R.id.enabledSwitch)
        private val typeChip: Chip = itemView.findViewById(R.id.typeChip)
        private val statusChip: Chip = itemView.findViewById(R.id.statusChip)
        private val editButton: ImageButton = itemView.findViewById(R.id.editButton)
        private val deleteButton: ImageButton = itemView.findViewById(R.id.deleteButton)

        fun bind(item: TTSReplacementUiItem) {
            patternText.text = item.pattern
            replacementText.text = item.replacement

            enabledSwitch.setOnCheckedChangeListener(null)
            enabledSwitch.isChecked = item.enabled
            enabledSwitch.setOnCheckedChangeListener { _, isChecked ->
                if (adapterPosition != RecyclerView.NO_POSITION) {
                    onToggle(item.id, isChecked)
                }
            }

            val resources = itemView.resources
            val typeLabel = when (item.type) {
                TTSReplacementUiType.SIMPLE -> resources.getString(R.string.tts_replacement_type_simple)
                TTSReplacementUiType.REGEX -> resources.getString(R.string.tts_replacement_type_regex)
                TTSReplacementUiType.COMMAND -> resources.getString(R.string.tts_replacement_type_command)
            }
            typeChip.text = typeLabel

            val isDisabled = !item.enabled
            statusChip.isVisible = isDisabled
            if (isDisabled) {
                statusChip.text = resources.getString(R.string.tts_replacement_disabled_label)
            }

            editButton.setOnClickListener {
                if (adapterPosition != RecyclerView.NO_POSITION) {
                    onEdit(item)
                }
            }
            deleteButton.setOnClickListener {
                if (adapterPosition != RecyclerView.NO_POSITION) {
                    onDelete(item)
                }
            }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<TTSReplacementUiItem>() {
            override fun areItemsTheSame(oldItem: TTSReplacementUiItem, newItem: TTSReplacementUiItem): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(oldItem: TTSReplacementUiItem, newItem: TTSReplacementUiItem): Boolean {
                return oldItem == newItem
            }
        }
    }
}
