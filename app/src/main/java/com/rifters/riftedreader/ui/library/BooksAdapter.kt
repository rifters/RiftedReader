package com.rifters.riftedreader.ui.library

import android.graphics.Color
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.recyclerview.selection.ItemDetailsLookup
import androidx.recyclerview.selection.SelectionTracker
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.color.MaterialColors
import com.rifters.riftedreader.R
import com.rifters.riftedreader.data.database.entities.BookMeta
import com.rifters.riftedreader.databinding.ItemBookBinding
import java.io.File
import java.text.DecimalFormat

class BooksAdapter(
    private val onBookClick: (BookMeta) -> Unit
) : ListAdapter<BookMeta, BooksAdapter.BookViewHolder>(BookDiffCallback()) {

    var selectionTracker: SelectionTracker<String>? = null

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        return getBookId(position).hashCode().toLong()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookViewHolder {
        val binding = ItemBookBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return BookViewHolder(binding, onBookClick)
    }

    override fun onBindViewHolder(holder: BookViewHolder, position: Int) {
        val book = getItem(position)
        val isSelected = selectionTracker?.isSelected(book.id) ?: false
        holder.bind(book, isSelected, selectionTracker)
    }

    fun getBookId(position: Int): String {
        return getItem(position).id
    }

    fun getPositionForId(id: String): Int {
        return currentList.indexOfFirst { it.id == id }
    }

    class BookViewHolder(
        private val binding: ItemBookBinding,
        private val onBookClick: (BookMeta) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        private var currentBook: BookMeta? = null

        fun bind(book: BookMeta, isSelected: Boolean, selectionTracker: SelectionTracker<String>?) {
            currentBook = book
            binding.bookTitle.text = book.title
            binding.bookAuthor.text = book.author ?: "Unknown Author"

            val sizeText = formatFileSize(book.size)
            val progressText = if (book.percentComplete > 0) {
                "${book.percentComplete.toInt()}%"
            } else {
                "Not started"
            }
            binding.bookInfo.text = "${book.format} • $sizeText • $progressText"

            binding.readingProgress.progress = book.percentComplete.toInt()

            // Load cover image if available
            if (book.coverPath != null && File(book.coverPath).exists()) {
                binding.bookCover.load(File(book.coverPath)) {
                    crossfade(true)
                    placeholder(R.drawable.ic_book_placeholder)
                    error(R.drawable.ic_book_placeholder)
                }
            } else {
                binding.bookCover.setImageResource(R.drawable.ic_book_placeholder)
            }

            val selectedStrokeColor = MaterialColors.getColor(
                binding.root,
                com.google.android.material.R.attr.colorPrimary
            )
            if (isSelected) {
                binding.root.strokeColor = selectedStrokeColor
                binding.root.strokeWidth = binding.root.resources.getDimensionPixelSize(R.dimen.library_selection_stroke)
            } else {
                binding.root.strokeColor = Color.TRANSPARENT
                binding.root.strokeWidth = 0
            }

            binding.root.setOnClickListener {
                onBookClick(book)
            }

            binding.root.setOnLongClickListener {
                selectionTracker?.select(book.id)
                true
            }
        }

        fun getItemDetails(): ItemDetailsLookup.ItemDetails<String> {
            return object : ItemDetailsLookup.ItemDetails<String>() {
                override fun getSelectionKey(): String? = currentBook?.id

                override fun getPosition(): Int = this@BookViewHolder.adapterPosition

                override fun inSelectionHotspot(e: MotionEvent): Boolean = false
            }
        }

        private fun formatFileSize(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            val kb = bytes / 1024.0
            if (kb < 1024) return "${DecimalFormat("#.##").format(kb)} KB"
            val mb = kb / 1024.0
            return "${DecimalFormat("#.##").format(mb)} MB"
        }
    }

    class BookDiffCallback : DiffUtil.ItemCallback<BookMeta>() {
        override fun areItemsTheSame(oldItem: BookMeta, newItem: BookMeta): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: BookMeta, newItem: BookMeta): Boolean {
            return oldItem == newItem
        }
    }
}
