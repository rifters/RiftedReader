package com.rifters.riftedreader.ui.library

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.os.bundleOf
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.rifters.riftedreader.R
import com.rifters.riftedreader.data.database.entities.BookMeta as Book

class BookOptionsBottomSheet : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_BOOK_ID = "arg_book_id"
        private const val ARG_BOOK_TITLE = "arg_book_title"
        const val REQUEST_KEY = "book_options_request"
        const val RESULT_BOOK_ID = "result_book_id"

        fun newInstance(book: Book) = BookOptionsBottomSheet().apply {
            arguments = bundleOf(
                ARG_BOOK_ID to book.id,
                ARG_BOOK_TITLE to book.title
            )
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottom_sheet_book_options, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val bookId = requireArguments().getString(ARG_BOOK_ID).orEmpty()
        val bookTitle = requireArguments().getString(ARG_BOOK_TITLE).orEmpty()

        view.findViewById<TextView>(R.id.bookOptionsTitle).text = bookTitle
        view.findViewById<View>(R.id.rowAddToCollection).setOnClickListener {
            parentFragmentManager.setFragmentResult(
                REQUEST_KEY,
                bundleOf(RESULT_BOOK_ID to bookId)
            )
            dismiss()
        }
    }
}
