package com.rifters.riftedreader.ui.library

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rifters.riftedreader.R
import com.rifters.riftedreader.data.database.BookDatabase
import com.rifters.riftedreader.data.repository.BookRepository
import com.rifters.riftedreader.databinding.FragmentLibraryBinding
import com.rifters.riftedreader.ui.reader.ReaderActivity
import com.rifters.riftedreader.util.FileScanner
import kotlinx.coroutines.launch

class LibraryFragment : Fragment() {
    
    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var viewModel: LibraryViewModel
    private lateinit var adapter: BooksAdapter
    
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.scanForBooks()
        } else {
            showPermissionDeniedDialog()
        }
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Initialize ViewModel
        val database = BookDatabase.getDatabase(requireContext())
        val repository = BookRepository(database.bookMetaDao())
        val fileScanner = FileScanner(requireContext(), repository)
        viewModel = LibraryViewModel(repository, fileScanner)
        
        setupRecyclerView()
        setupSearchView()
        setupFab()
        observeViewModel()
    }
    
    private fun setupRecyclerView() {
        adapter = BooksAdapter { book ->
            // Open book in reader
            val intent = Intent(requireContext(), ReaderActivity::class.java).apply {
                putExtra("BOOK_ID", book.id)
                putExtra("BOOK_PATH", book.path)
                putExtra("BOOK_TITLE", book.title)
            }
            startActivity(intent)
        }
        
        binding.booksRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.booksRecyclerView.adapter = adapter
    }
    
    private fun setupSearchView() {
        binding.searchEditText.addTextChangedListener { text ->
            viewModel.searchBooks(text.toString())
        }
    }
    
    private fun setupFab() {
        binding.scanFab.setOnClickListener {
            checkPermissionAndScan()
        }
    }
    
    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.books.collect { books ->
                        adapter.submitList(books)
                        binding.emptyView.visibility = if (books.isEmpty()) View.VISIBLE else View.GONE
                        binding.booksRecyclerView.visibility = if (books.isEmpty()) View.GONE else View.VISIBLE
                    }
                }
                
                launch {
                    viewModel.isScanning.collect { isScanning ->
                        binding.progressIndicator.visibility = if (isScanning) View.VISIBLE else View.GONE
                    }
                }
            }
        }
    }
    
    private fun checkPermissionAndScan() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        
        when {
            ContextCompat.checkSelfPermission(
                requireContext(),
                permission
            ) == PackageManager.PERMISSION_GRANTED -> {
                viewModel.scanForBooks()
            }
            shouldShowRequestPermissionRationale(permission) -> {
                showPermissionRationaleDialog(permission)
            }
            else -> {
                requestPermissionLauncher.launch(permission)
            }
        }
    }
    
    private fun showPermissionRationaleDialog(permission: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.permission_storage_title)
            .setMessage(R.string.permission_storage_message)
            .setPositiveButton(R.string.permission_grant) { _, _ ->
                requestPermissionLauncher.launch(permission)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
    
    private fun showPermissionDeniedDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.permission_denied)
            .setMessage(R.string.permission_storage_message)
            .setPositiveButton(R.string.ok, null)
            .show()
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
