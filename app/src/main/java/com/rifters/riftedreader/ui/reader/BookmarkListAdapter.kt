package com.rifters.riftedreader.ui.reader

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.rifters.riftedreader.R
import com.rifters.riftedreader.data.database.entities.Bookmark
import com.rifters.riftedreader.databinding.ItemBookmarkBinding

class BookmarkListAdapter(
    private val onBookmarkClick: (Bookmark) -> Unit
) : ListAdapter<Bookmark, BookmarkListAdapter.BookmarkViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookmarkViewHolder {
        val binding = ItemBookmarkBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return BookmarkViewHolder(binding)
    }

    override fun onBindViewHolder(holder: BookmarkViewHolder, position: Int) {
        holder.bind(getItem(position), onBookmarkClick)
    }

    fun bookmarkAt(position: Int): Bookmark? {
        return currentList.getOrNull(position)
    }

    class BookmarkViewHolder(
        private val binding: ItemBookmarkBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(bookmark: Bookmark, onBookmarkClick: (Bookmark) -> Unit) {
            val context = binding.root.context
            binding.bookmarkTitle.text = bookmark.nearestAnchorText.ifBlank {
                context.getString(R.string.reader_bookmark_fallback_title, bookmark.chapterIndex + 1)
            }
            binding.bookmarkNote.text = bookmark.label.orEmpty()
            binding.bookmarkNote.isVisible = !bookmark.label.isNullOrBlank()
            binding.bookmarkSavedAt.text = DateUtils.getRelativeTimeSpanString(
                bookmark.savedAt,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS
            )
            binding.root.setOnClickListener { onBookmarkClick(bookmark) }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<Bookmark>() {
        override fun areItemsTheSame(oldItem: Bookmark, newItem: Bookmark): Boolean {
            return oldItem.bookId == newItem.bookId &&
                oldItem.chapterIndex == newItem.chapterIndex &&
                oldItem.charOffset == newItem.charOffset &&
                oldItem.savedAt == newItem.savedAt
        }

        override fun areContentsTheSame(oldItem: Bookmark, newItem: Bookmark): Boolean {
            return oldItem == newItem
        }
    }
}
