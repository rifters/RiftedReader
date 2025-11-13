package com.rifters.riftedreader.ui.library

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.rifters.riftedreader.R
import com.rifters.riftedreader.data.database.entities.BookMeta
import com.rifters.riftedreader.databinding.ItemBookBinding
import java.io.File
import java.text.DecimalFormat

class BooksAdapter(
    private val onBookClick: (BookMeta) -> Unit
) : ListAdapter<BookMeta, BooksAdapter.BookViewHolder>(BookDiffCallback()) {
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookViewHolder {
        val binding = ItemBookBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return BookViewHolder(binding, onBookClick)
    }
    
    override fun onBindViewHolder(holder: BookViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
    
    class BookViewHolder(
        private val binding: ItemBookBinding,
        private val onBookClick: (BookMeta) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {
        
        fun bind(book: BookMeta) {
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
            
            binding.root.setOnClickListener {
                onBookClick(book)
            }
        }
        
        private fun formatFileSize(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            val kb = bytes / 1024.0
            if (kb < 1024) return "${DecimalFormat("#.#").format(kb)} KB"
            val mb = kb / 1024.0
            return "${DecimalFormat("#.#").format(mb)} MB"
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
