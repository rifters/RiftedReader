package com.rifters.riftedreader.ui.library

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.rifters.riftedreader.R
import com.rifters.riftedreader.data.database.entities.BookMeta as Book

class BookOptionsBottomSheet : BottomSheetDialogFragment() {

    var onAddToCollection: (Book) -> Unit = {}
    private lateinit var book: Book

    companion object {
        fun newInstance(book: Book) = BookOptionsBottomSheet().apply {
            this.book = book
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottom_sheet_book_options, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<TextView>(R.id.bookOptionsTitle).text = book.title
        view.findViewById<View>(R.id.rowAddToCollection).setOnClickListener {
            dismiss()
            onAddToCollection(book)
        }
    }
}
