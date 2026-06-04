package com.rifters.riftedreader.ui.library

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.rifters.riftedreader.R
import com.rifters.riftedreader.data.repository.CollectionRepository
import com.rifters.riftedreader.data.database.BookDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CollectionPickerBottomSheet : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_BOOK_ID = "arg_book_id"
        const val REQUEST_KEY_MANAGE_COLLECTIONS = "collection_picker_manage_collections"

        fun newInstance(bookId: String) = CollectionPickerBottomSheet().apply {
            arguments = bundleOf(ARG_BOOK_ID to bookId)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottom_sheet_collection_picker, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val bookId = requireArguments().getString(ARG_BOOK_ID).orEmpty()
        val collectionRepository = CollectionRepository(
            BookDatabase.getDatabase(requireContext()).collectionDao()
        )
        val selectedIds = mutableSetOf<String>()

        val recycler = view.findViewById<RecyclerView>(R.id.collectionList)
        val emptyText = view.findViewById<TextView>(R.id.collectionEmpty)
        val goButton = view.findViewById<Button>(R.id.collectionGo)
        val doneButton = view.findViewById<Button>(R.id.collectionDone)
        val adapter = CollectionCheckAdapter(emptyList(), emptySet()) { id, checked ->
            if (checked) {
                selectedIds.add(id)
            } else {
                selectedIds.remove(id)
            }
        }

        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            combine(
                collectionRepository.collections,
                collectionRepository.getCollectionsForBook(bookId)
            ) { all, assigned -> all to assigned.map { it.id }.toSet() }
                .collect { (all, assignedIds) ->
                    selectedIds.clear()
                    selectedIds.addAll(assignedIds)

                    if (all.isEmpty()) {
                        recycler.visibility = View.GONE
                        doneButton.visibility = View.GONE
                        emptyText.visibility = View.VISIBLE
                        goButton.visibility = View.VISIBLE
                    } else {
                        emptyText.visibility = View.GONE
                        goButton.visibility = View.GONE
                        recycler.visibility = View.VISIBLE
                        doneButton.visibility = View.VISIBLE
                        adapter.update(all, selectedIds.toSet())
                    }
                }
        }

        goButton.setOnClickListener {
            parentFragmentManager.setFragmentResult(REQUEST_KEY_MANAGE_COLLECTIONS, bundleOf())
            dismiss()
        }
        doneButton.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    val currentIds = collectionRepository.getCollectionIdsForBook(bookId).toSet()
                    (selectedIds - currentIds).forEach { id ->
                        collectionRepository.addBookToCollection(bookId, id)
                    }
                    (currentIds - selectedIds).forEach { id ->
                        collectionRepository.removeBookFromCollection(bookId, id)
                    }
                }
                dismiss()
            }
        }
    }
}
