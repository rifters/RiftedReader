package com.rifters.riftedreader.ui.library

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.CheckBox
import androidx.recyclerview.widget.RecyclerView
import com.rifters.riftedreader.R
import com.rifters.riftedreader.data.database.entities.CollectionEntity

class CollectionCheckAdapter(
    private var all: List<CollectionEntity>,
    private var checked: Set<String>,
    private val onToggle: (id: String, checked: Boolean) -> Unit
) : RecyclerView.Adapter<CollectionCheckAdapter.CollectionCheckViewHolder>() {

    fun update(
        all: List<CollectionEntity>,
        checked: Set<String>
    ) {
        this.all = all
        this.checked = checked
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CollectionCheckViewHolder {
        val checkBox = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_collection_check, parent, false) as CheckBox
        return CollectionCheckViewHolder(checkBox)
    }

    override fun onBindViewHolder(holder: CollectionCheckViewHolder, position: Int) {
        holder.bind(all[position])
    }

    override fun getItemCount(): Int = all.size

    inner class CollectionCheckViewHolder(
        private val checkBox: CheckBox
    ) : RecyclerView.ViewHolder(checkBox) {

        fun bind(collection: CollectionEntity) {
            checkBox.setOnCheckedChangeListener(null)
            checkBox.text = collection.name
            checkBox.isChecked = collection.id in checked
            checkBox.setOnCheckedChangeListener { _, isChecked ->
                onToggle(collection.id, isChecked)
            }
        }
    }
}
