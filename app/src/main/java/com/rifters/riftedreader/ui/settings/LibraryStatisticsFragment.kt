package com.rifters.riftedreader.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.rifters.riftedreader.R
import com.rifters.riftedreader.data.database.BookDatabase
import com.rifters.riftedreader.data.repository.BookRepository
import com.rifters.riftedreader.data.repository.CollectionRepository
import com.rifters.riftedreader.databinding.FragmentLibraryStatisticsBinding
import com.rifters.riftedreader.domain.library.LibraryStatistics
import com.rifters.riftedreader.domain.library.LibraryStatisticsCalculator
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

class LibraryStatisticsFragment : Fragment() {

    private var _binding: FragmentLibraryStatisticsBinding? = null
    private val binding get() = _binding!!

    private lateinit var statisticsCalculator: LibraryStatisticsCalculator

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLibraryStatisticsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        activity?.title = getString(R.string.library_statistics_screen_title)

        val database = BookDatabase.getDatabase(requireContext())
        val bookRepository = BookRepository(database.bookMetaDao())
        val collectionRepository = CollectionRepository(database.collectionDao())
        statisticsCalculator = LibraryStatisticsCalculator(bookRepository, collectionRepository)

        binding.refreshButton.setOnClickListener { loadStatistics() }

        loadStatistics()
    }

    private fun loadStatistics() {
        binding.progressIndicator.isVisible = true
        binding.statsScroll.isVisible = false

        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { statisticsCalculator.compute() }
                .onSuccess { renderStatistics(it) }
                .onFailure {
                    binding.progressIndicator.isVisible = false
                    binding.statsScroll.isVisible = true
                    binding.emptyStateText.isVisible = true
                    binding.emptyStateText.text = getString(R.string.library_statistics_error)
                    binding.cardsContainer.isVisible = false
                }
        }
    }

    private fun renderStatistics(statistics: LibraryStatistics) {
        binding.progressIndicator.isVisible = false
        binding.statsScroll.isVisible = true

        binding.totalBooksValue.text = statistics.totalBooks.toString()
        binding.totalFormatsValue.text = statistics.totalFormats.toString()
        binding.totalCollectionsValue.text = statistics.totalCollections.toString()
        binding.booksInCollectionsValue.text = statistics.booksInCollections.toString()
        binding.favoritesValue.text = statistics.favoriteCount.toString()

        val averageCompletion = String.format(
            Locale.getDefault(),
            getString(R.string.library_statistics_average_completion_format),
            statistics.averageCompletion
        )
        binding.averageCompletionValue.text = averageCompletion

        val lastUpdatedValue = DateFormat.getDateTimeInstance(
            DateFormat.MEDIUM,
            DateFormat.SHORT
        ).format(Date())
        binding.lastUpdatedValue.text = getString(
            R.string.library_statistics_last_updated_format,
            lastUpdatedValue
        )

        val showEmptyState = statistics.totalBooks == 0
        binding.emptyStateText.isVisible = showEmptyState
        binding.cardsContainer.isVisible = !showEmptyState
        if (showEmptyState) {
            binding.emptyStateText.text = getString(R.string.library_statistics_empty_state)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
