package com.rifters.riftedreader.ui.reader

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.rifters.riftedreader.R
import com.rifters.riftedreader.data.database.entities.Bookmark
import com.rifters.riftedreader.databinding.FragmentBookmarkListBinding
import kotlinx.coroutines.launch

class BookmarkListFragment : Fragment() {

    interface Listener {
        fun onBookmarkSelected(bookmark: Bookmark)
    }

    private var _binding: FragmentBookmarkListBinding? = null
    private val binding get() = _binding!!
    private val readerViewModel: ReaderViewModel by activityViewModels()
    private var listener: Listener? = null
    private var adapter: BookmarkListAdapter? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = context as? Listener
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBookmarkListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.bookmarkToolbar.setNavigationOnClickListener {
            parentFragmentManager.popBackStack()
        }

        val listAdapter = BookmarkListAdapter { bookmark ->
            listener?.onBookmarkSelected(bookmark)
        }
        adapter = listAdapter
        binding.bookmarksRecyclerView.adapter = listAdapter
        binding.bookmarksRecyclerView.setHasFixedSize(true)

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.START or ItemTouchHelper.END) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val bookmark = listAdapter.bookmarkAt(viewHolder.bindingAdapterPosition) ?: return
                readerViewModel.deleteBookmark(bookmark)
                Snackbar.make(binding.root, R.string.reader_bookmark_deleted, Snackbar.LENGTH_LONG)
                    .setAction(R.string.undo) {
                        readerViewModel.saveNamedBookmark(bookmark)
                    }
                    .show()
            }
        }).attachToRecyclerView(binding.bookmarksRecyclerView)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                readerViewModel.namedBookmarks.collect { bookmarks ->
                    listAdapter.submitList(bookmarks)
                    binding.emptyState.isVisible = bookmarks.isEmpty()
                    binding.bookmarksRecyclerView.isVisible = bookmarks.isNotEmpty()
                }
            }
        }
    }

    override fun onDestroyView() {
        adapter = null
        _binding = null
        super.onDestroyView()
    }

    override fun onDetach() {
        listener = null
        super.onDetach()
    }

    companion object {
        const val TAG = "BookmarkListFragment"
    }
}
