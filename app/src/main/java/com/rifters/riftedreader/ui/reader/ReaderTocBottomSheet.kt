package com.rifters.riftedreader.ui.reader

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.rifters.riftedreader.R
import com.rifters.riftedreader.databinding.DialogChaptersBinding
import com.rifters.riftedreader.databinding.ItemChapterBinding
import com.rifters.riftedreader.domain.reader.AnchorEntry

class ReaderTocBottomSheet : BottomSheetDialogFragment() {

    private var _binding: DialogChaptersBinding? = null
    private val binding get() = _binding!!

    private var entries: List<AnchorEntry> = emptyList()
    private var onEntrySelected: ((AnchorEntry) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogChaptersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (entries.isEmpty()) {
            dismiss()
            return
        }

        binding.chaptersTitle.setText(R.string.reader_toc)
        binding.chaptersRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.chaptersRecyclerView.adapter = ReaderTocAdapter(entries) { entry ->
            onEntrySelected?.invoke(entry)
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun show(
            manager: androidx.fragment.app.FragmentManager,
            entries: List<AnchorEntry>,
            onEntrySelected: (AnchorEntry) -> Unit
        ) {
            if (entries.isEmpty()) return

            ReaderTocBottomSheet().apply {
                this.entries = entries
                this.onEntrySelected = onEntrySelected
            }.show(manager, ReaderTocBottomSheet::class.java.simpleName)
        }
    }
}

private class ReaderTocAdapter(
    private val entries: List<AnchorEntry>,
    private val onEntryClick: (AnchorEntry) -> Unit
) : RecyclerView.Adapter<ReaderTocAdapter.TocViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TocViewHolder {
        val binding = ItemChapterBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return TocViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TocViewHolder, position: Int) {
        holder.bind(entries[position])
    }

    override fun getItemCount(): Int = entries.size

    inner class TocViewHolder(private val binding: ItemChapterBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: AnchorEntry) {
            binding.chapterTitle.text = entry.text

            val basePaddingDp = 16
            val indentDp = (entry.level - 1).coerceAtLeast(0) * 16
            val density = binding.root.resources.displayMetrics.density
            binding.chapterTitle.setPadding(
                ((basePaddingDp + indentDp) * density).toInt(),
                binding.chapterTitle.paddingTop,
                binding.chapterTitle.paddingRight,
                binding.chapterTitle.paddingBottom
            )

            binding.root.setOnClickListener {
                onEntryClick(entry)
            }
        }
    }
}
