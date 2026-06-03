package com.rifters.riftedreader.ui.calibre

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.chip.Chip
import com.rifters.riftedreader.R
import com.rifters.riftedreader.data.calibre.CalibreBook
import com.rifters.riftedreader.databinding.ItemCalibreBookBinding
import com.rifters.riftedreader.databinding.ItemCalibreLoadingBinding

class CalibreBooksAdapter(
    private val onBookClick: (CalibreBook) -> Unit,
    private val onLoadMore: () -> Unit,
) : ListAdapter<CalibreLibraryItem, RecyclerView.ViewHolder>(CalibreLibraryDiffCallback()) {

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is CalibreLibraryItem.BookItem -> VIEW_TYPE_BOOK
            CalibreLibraryItem.LoadingFooter -> VIEW_TYPE_LOADING
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_BOOK) {
            BookViewHolder(ItemCalibreBookBinding.inflate(inflater, parent, false), onBookClick)
        } else {
            LoadingViewHolder(ItemCalibreLoadingBinding.inflate(inflater, parent, false), onLoadMore)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is CalibreLibraryItem.BookItem -> (holder as BookViewHolder).bind(item.book)
            CalibreLibraryItem.LoadingFooter -> (holder as LoadingViewHolder).bind()
        }
    }

    class BookViewHolder(
        private val binding: ItemCalibreBookBinding,
        private val onBookClick: (CalibreBook) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(book: CalibreBook) {
            binding.bookTitle.text = book.title
            binding.bookAuthor.text = book.authors.joinToString().ifBlank { binding.root.context.getString(R.string.unknown_author) }
            binding.bookCover.load(book.coverUrl) {
                crossfade(true)
                placeholder(R.drawable.ic_book_placeholder)
                error(R.drawable.ic_book_placeholder)
            }

            binding.formatChipGroup.removeAllViews()
            supportedFormats(book).forEach { format ->
                val chip = Chip(binding.root.context).apply {
                    text = format.name
                    isCheckable = false
                    isClickable = false
                    minHeight = 0
                }
                binding.formatChipGroup.addView(chip)
            }
            binding.formatChipGroup.isVisible = binding.formatChipGroup.childCount > 0
            binding.root.setOnClickListener { onBookClick(book) }
        }
    }

    class LoadingViewHolder(
        private val binding: ItemCalibreLoadingBinding,
        private val onLoadMore: () -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind() {
            onLoadMore()
        }
    }

    companion object {
        const val VIEW_TYPE_BOOK = 1
        const val VIEW_TYPE_LOADING = 2
    }
}

sealed class CalibreLibraryItem {
    data class BookItem(val book: CalibreBook) : CalibreLibraryItem()
    object LoadingFooter : CalibreLibraryItem()
}

class CalibreLibraryDiffCallback : DiffUtil.ItemCallback<CalibreLibraryItem>() {
    override fun areItemsTheSame(oldItem: CalibreLibraryItem, newItem: CalibreLibraryItem): Boolean {
        return when {
            oldItem is CalibreLibraryItem.BookItem && newItem is CalibreLibraryItem.BookItem -> oldItem.book.id == newItem.book.id
            oldItem is CalibreLibraryItem.LoadingFooter && newItem is CalibreLibraryItem.LoadingFooter -> true
            else -> false
        }
    }

    override fun areContentsTheSame(oldItem: CalibreLibraryItem, newItem: CalibreLibraryItem): Boolean = oldItem == newItem
}
