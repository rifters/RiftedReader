package com.rifters.riftedreader.ui.library

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.rifters.riftedreader.R
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class CollectionPickerBottomSheet : BottomSheetDialogFragment() {

    private var bookId: String = ""

    companion object {
        fun newInstance(bookId: String) = CollectionPickerBottomSheet().apply {
            this.bookId = bookId
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottom_sheet_collection_picker, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val libraryFragment = parentFragment as? LibraryFragment ?: run {
            dismiss()
            return
        }
        val viewModel = libraryFragment.libraryViewModel
        val selectedIds = mutableSetOf<String>()

        val recycler = view.findViewById<RecyclerView>(R.id.collectionList)
        val emptyText = view.findViewById<TextView>(R.id.collectionEmpty)
        val goButton = view.findViewById<Button>(R.id.collectionGo)
        val doneButton = view.findViewById<Button>(R.id.collectionDone)

        recycler.layoutManager = LinearLayoutManager(requireContext())

        viewLifecycleOwner.lifecycleScope.launch {
            combine(
                viewModel.collections,
                viewModel.getCollectionsForBook(bookId)
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
                        recycler.adapter = CollectionCheckAdapter(all, selectedIds.toSet()) { id, checked ->
                            if (checked) {
                                selectedIds.add(id)
                            } else {
                                selectedIds.remove(id)
                            }
                        }
                    }
                }
        }

        goButton.setOnClickListener {
            dismiss()
        }
        doneButton.setOnClickListener {
            viewModel.setBookCollections(bookId, selectedIds.toSet())
            dismiss()
        }
    }
}
