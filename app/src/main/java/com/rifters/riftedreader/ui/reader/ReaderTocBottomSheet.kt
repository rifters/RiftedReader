package com.rifters.riftedreader.ui.reader

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
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

        val entries = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            arguments?.getParcelableArrayList(ARG_ENTRIES, AnchorEntry::class.java).orEmpty()
        } else {
            @Suppress("DEPRECATION")
            arguments?.getParcelableArrayList<AnchorEntry>(ARG_ENTRIES).orEmpty()
        }

        if (entries.isEmpty()) {
            dismiss()
            return
        }

        binding.chaptersTitle.setText(R.string.reader_toc)
        binding.chaptersRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.chaptersRecyclerView.adapter = ReaderTocAdapter(entries) { entry ->
            parentFragmentManager.setFragmentResult(
                REQUEST_KEY,
                bundleOf(RESULT_ENTRY to entry)
            )
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val REQUEST_KEY = "reader_toc_selection"
        const val RESULT_ENTRY = "reader_toc_entry"
        private const val ARG_ENTRIES = "arg_toc_entries"

        fun show(
            manager: androidx.fragment.app.FragmentManager,
            entries: List<AnchorEntry>
        ) {
            if (entries.isEmpty()) return

            ReaderTocBottomSheet().apply {
                arguments = bundleOf(ARG_ENTRIES to ArrayList(entries))
            }.show(manager, ReaderTocBottomSheet::class.java.simpleName)
        }
    }
}

private class ReaderTocAdapter(
    private val entries: List<AnchorEntry>,
    private val onEntryClick: (AnchorEntry) -> Unit
) : RecyclerView.Adapter<ReaderTocAdapter.TocViewHolder>() {

    private companion object {
        const val BASE_PADDING_DP = 16
        const val INDENT_PER_LEVEL_DP = 16
    }

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

            val indentDp = (entry.level - 1).coerceAtLeast(0) * INDENT_PER_LEVEL_DP
            val density = binding.root.resources.displayMetrics.density
            binding.chapterTitle.setPadding(
                ((BASE_PADDING_DP + indentDp) * density).toInt(),
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
