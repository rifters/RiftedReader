package com.rifters.riftedreader.ui.library

import android.view.MotionEvent
import androidx.recyclerview.selection.ItemDetailsLookup
import androidx.recyclerview.selection.ItemKeyProvider
import androidx.recyclerview.widget.RecyclerView

class BookItemKeyProvider(
    private val adapter: BooksAdapter
) : ItemKeyProvider<String>(SCOPE_CACHED) {

    override fun getKey(position: Int): String? {
        return if (position in 0 until adapter.itemCount) {
            adapter.getBookId(position)
        } else {
            null
        }
    }

    override fun getPosition(key: String): Int {
        return adapter.getPositionForId(key)
    }
}

class BookItemDetailsLookup(
    private val recyclerView: RecyclerView
) : ItemDetailsLookup<String>() {

    override fun getItemDetails(e: MotionEvent): ItemDetails<String>? {
        val view = recyclerView.findChildViewUnder(e.x, e.y) ?: return null
        val holder = recyclerView.getChildViewHolder(view)
        return if (holder is BooksAdapter.BookViewHolder) {
            holder.getItemDetails()
        } else {
            null
        }
    }
}
