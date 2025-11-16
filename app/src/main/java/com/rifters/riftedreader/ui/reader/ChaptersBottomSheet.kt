package com.rifters.riftedreader.ui.reader

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.rifters.riftedreader.databinding.DialogChaptersBinding
import com.rifters.riftedreader.databinding.ItemChapterBinding
import com.rifters.riftedreader.domain.parser.TocEntry

class ChaptersBottomSheet : BottomSheetDialogFragment() {

    private var _binding: DialogChaptersBinding? = null
    private val binding get() = _binding!!

    private var onChapterSelected: ((Int) -> Unit)? = null

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
        
        val chapters = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            arguments?.getParcelableArrayList(ARG_CHAPTERS, TocEntry::class.java).orEmpty()
        } else {
            @Suppress("DEPRECATION")
            arguments?.getParcelableArrayList<TocEntry>(ARG_CHAPTERS).orEmpty()
        }
        
        if (chapters.isEmpty()) {
            dismiss()
            return
        }
        
        binding.chaptersRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.chaptersRecyclerView.adapter = ChaptersAdapter(chapters) { pageNumber ->
            onChapterSelected?.invoke(pageNumber)
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_CHAPTERS = "arg_chapters"

        fun show(
            manager: androidx.fragment.app.FragmentManager,
            chapters: List<TocEntry>,
            onChapterSelected: (Int) -> Unit
        ) {
            val fragment = ChaptersBottomSheet().apply {
                arguments = bundleOf(ARG_CHAPTERS to ArrayList(chapters))
                this.onChapterSelected = onChapterSelected
            }
            fragment.show(manager, ChaptersBottomSheet::class.java.simpleName)
        }
    }
}

class ChaptersAdapter(
    private val chapters: List<TocEntry>,
    private val onChapterClick: (Int) -> Unit
) : RecyclerView.Adapter<ChaptersAdapter.ChapterViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChapterViewHolder {
        val binding = ItemChapterBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ChapterViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ChapterViewHolder, position: Int) {
        holder.bind(chapters[position])
    }

    override fun getItemCount(): Int = chapters.size

    inner class ChapterViewHolder(private val binding: ItemChapterBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(chapter: TocEntry) {
            binding.chapterTitle.text = chapter.title
            
            // Apply indentation based on level
            val basePaddingDp = 16 // 16dp base padding to match layout
            val indentDp = chapter.level * 16 // 16dp per level
            val density = binding.root.resources.displayMetrics.density
            val basePaddingPx = (basePaddingDp * density).toInt()
            val indentPx = (indentDp * density).toInt()
            binding.chapterTitle.setPadding(
                basePaddingPx + indentPx, // left padding + indent
                binding.chapterTitle.paddingTop,
                binding.chapterTitle.paddingRight,
                binding.chapterTitle.paddingBottom
            )
            
            binding.root.setOnClickListener {
                onChapterClick(chapter.pageNumber)
            }
        }
    }
}
