package com.rifters.riftedreader.ui.calibre

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import coil.load
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.rifters.riftedreader.R
import com.rifters.riftedreader.data.calibre.BookFormat
import com.rifters.riftedreader.data.calibre.CalibreBook
import com.rifters.riftedreader.data.calibre.CalibreCredentialStore
import com.rifters.riftedreader.data.calibre.CalibreContentServerRepository
import com.rifters.riftedreader.data.calibre.DefaultCalibreConnectionRepository
import com.rifters.riftedreader.databinding.DialogCalibreBookDetailBinding
import com.rifters.riftedreader.databinding.FragmentCalibreLibraryBinding
import com.rifters.riftedreader.util.AppLogger
import kotlinx.coroutines.launch
import java.util.Locale

class CalibreLibraryFragment : Fragment() {

    private var _binding: FragmentCalibreLibraryBinding? = null
    private val binding get() = _binding!!

    private val connectionRepository by lazy { DefaultCalibreConnectionRepository(requireContext()) }
    private val viewModel: CalibreLibraryViewModel by viewModels {
        val credentialStore = CalibreCredentialStore(requireContext())
        val contentServerRepository = CalibreContentServerRepository(
            connectionRepository = connectionRepository,
            credentialStore = credentialStore,
        )
        CalibreLibraryViewModelFactory(contentServerRepository, connectionRepository)
    }
    private lateinit var adapter: CalibreBooksAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCalibreLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.title = getString(R.string.calibre_library_title)
        AppLogger.event("CalibreLibraryFragment", "onViewCreated", "ui/calibre/lifecycle")
        setupRecyclerView()
        setupSearch()
        setupActions()
        observeViewModel()
        viewModel.loadLibrary()
    }

    private fun setupRecyclerView() {
        adapter = CalibreBooksAdapter(
            onBookClick = ::showBookDetails,
            onLoadMore = { viewModel.loadNextPage() },
        )
        val layoutManager = GridLayoutManager(requireContext(), PHONE_GRID_SPAN).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    return if (adapter.getItemViewType(position) == CalibreBooksAdapter.VIEW_TYPE_LOADING) spanCount else 1
                }
            }
        }
        binding.booksRecyclerView.layoutManager = layoutManager
        binding.booksRecyclerView.adapter = adapter
    }

    private fun setupSearch() {
        binding.searchEditText.setText(viewModel.searchQuery.value)
        binding.searchEditText.addTextChangedListener { text ->
            val query = text?.toString().orEmpty()
            if (query != viewModel.searchQuery.value) {
                viewModel.search(query)
            }
        }
        binding.searchLayout.setEndIconOnClickListener {
            binding.searchEditText.setText("")
            viewModel.clearSearch()
        }
    }

    private fun setupActions() {
        binding.swipeRefreshLayout.setOnRefreshListener { viewModel.loadLibrary() }
        binding.retryButton.setOnClickListener { viewModel.loadLibrary() }
        binding.openSettingsButton.setOnClickListener {
            findNavController().navigate(R.id.action_calibreLibraryFragment_to_readerSettingsFragment)
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.libraryState.collect { state -> renderState(state) }
                }
                launch {
                    viewModel.searchQuery.collect { query ->
                        if (binding.searchEditText.text?.toString().orEmpty() != query) {
                            binding.searchEditText.setText(query)
                            binding.searchEditText.setSelection(query.length)
                        }
                    }
                }
            }
        }
    }

    private fun renderState(state: CalibreLibraryState) {
        binding.swipeRefreshLayout.isRefreshing = false
        binding.progressIndicator.isVisible = state is CalibreLibraryState.Loading
        binding.booksRecyclerView.isVisible = state is CalibreLibraryState.Success
        binding.emptyView.isVisible = state is CalibreLibraryState.Success && state.books.isEmpty()
        binding.errorView.isVisible = state is CalibreLibraryState.Error
        binding.disabledView.isVisible = state is CalibreLibraryState.Disabled
        binding.searchLayout.isVisible = state !is CalibreLibraryState.Disabled

        when (state) {
            CalibreLibraryState.Idle -> adapter.submitList(emptyList())
            CalibreLibraryState.Loading -> adapter.submitList(emptyList())
            is CalibreLibraryState.Success -> {
                val items = state.books.map { CalibreLibraryItem.BookItem(it) } +
                    if (state.hasMore) listOf(CalibreLibraryItem.LoadingFooter) else emptyList()
                adapter.submitList(items)
            }
            is CalibreLibraryState.Error -> {
                adapter.submitList(emptyList())
                binding.errorMessage.text = state.exception.localizedMessage ?: getString(R.string.calibre_library_error_generic)
            }
            CalibreLibraryState.Disabled -> adapter.submitList(emptyList())
        }
    }

    private fun showBookDetails(book: CalibreBook) {
        val dialog = BottomSheetDialog(requireContext())
        val sheetBinding = DialogCalibreBookDetailBinding.inflate(layoutInflater)
        dialog.setContentView(sheetBinding.root)

        sheetBinding.bookCover.load(book.coverUrl) {
            crossfade(true)
            placeholder(R.drawable.ic_book_placeholder)
            error(R.drawable.ic_book_placeholder)
        }
        sheetBinding.bookTitle.text = book.title
        sheetBinding.bookAuthor.text = book.authors.joinToString().ifBlank { getString(R.string.unknown_author) }
        sheetBinding.seriesInfo.isVisible = book.series != null
        sheetBinding.seriesInfo.text = book.series?.let { series ->
            val index = book.seriesIndex?.let { formatSeriesIndex(it) }
            if (index == null) series else getString(R.string.calibre_book_series_format, series, index)
        }

        bindTags(sheetBinding, book)
        bindFormatButtons(sheetBinding, book, dialog)
        dialog.show()
    }

    private fun bindTags(sheetBinding: DialogCalibreBookDetailBinding, book: CalibreBook) {
        sheetBinding.tagsChipGroup.removeAllViews()
        val tags = book.tags.take(MAX_VISIBLE_TAGS)
        tags.forEach { tag ->
            sheetBinding.tagsChipGroup.addView(Chip(requireContext()).apply {
                text = tag
                isCheckable = false
                isClickable = false
            })
        }
        val hiddenCount = book.tags.size - tags.size
        if (hiddenCount > 0) {
            sheetBinding.tagsChipGroup.addView(Chip(requireContext()).apply {
                text = getString(R.string.calibre_tags_more, hiddenCount)
                isCheckable = false
                isClickable = false
            })
        }
        sheetBinding.tagsChipGroup.isVisible = sheetBinding.tagsChipGroup.childCount > 0
    }

    private fun bindFormatButtons(sheetBinding: DialogCalibreBookDetailBinding, book: CalibreBook, dialog: BottomSheetDialog) {
        sheetBinding.formatsContainer.removeAllViews()
        val preferredFormat = viewModel.preferredFormatFor(book)
        supportedFormats(book).forEach { format ->
            val button = MaterialButton(requireContext(), null, buttonStyle(format == preferredFormat)).apply {
                text = getString(R.string.calibre_download_format, format.name)
                setOnClickListener {
                    viewModel.downloadBook(book, format)
                    dialog.dismiss()
                }
            }
            sheetBinding.formatsContainer.addView(button)
        }
        sheetBinding.formatsContainer.isVisible = sheetBinding.formatsContainer.childCount > 0
    }

    private fun buttonStyle(isPreferred: Boolean): Int {
        return if (isPreferred) FILLED_BUTTON_STYLE else OUTLINED_BUTTON_STYLE
    }

    private fun formatSeriesIndex(value: Double): String {
        return if (value % 1.0 == 0.0) value.toInt().toString() else String.format(Locale.US, "%.1f", value)
    }

    override fun onDestroyView() {
        binding.booksRecyclerView.adapter = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val PHONE_GRID_SPAN = 2
        private const val MAX_VISIBLE_TAGS = 3
        private val FILLED_BUTTON_STYLE = com.google.android.material.R.attr.materialButtonStyle
        private val OUTLINED_BUTTON_STYLE = com.google.android.material.R.attr.materialButtonOutlinedStyle
    }
}
