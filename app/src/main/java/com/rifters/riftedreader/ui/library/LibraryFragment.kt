package com.rifters.riftedreader.ui.library

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.rifters.riftedreader.R
import com.rifters.riftedreader.data.database.BookDatabase
import com.rifters.riftedreader.data.repository.BookRepository
import com.rifters.riftedreader.databinding.FragmentLibraryBinding
import com.rifters.riftedreader.ui.reader.ReaderActivity
import com.rifters.riftedreader.util.FileScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale
import android.provider.OpenableColumns
import android.os.Environment

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
    private val manageStoragePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        checkPermissionAndScan()
    }

    private val importBookLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            importBookFromUri(uri)
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
            showAddBooksMenu()
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

                launch {
                    viewModel.events.collect { event ->
                        handleLibraryEvent(event)
                    }
                }
            }
        }
    }
    
    private fun checkPermissionAndScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                showAllFilesAccessDialog()
                return
            }
        }

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

    private fun showAllFilesAccessDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.permission_all_files_title)
            .setMessage(R.string.permission_all_files_message)
            .setPositiveButton(R.string.permission_open_settings) { _, _ ->
                openAllFilesAccessSettings()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openAllFilesAccessSettings() {
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:${requireContext().packageName}")
        }
        manageStoragePermissionLauncher.launch(intent)
    }

    private fun showAddBooksMenu() {
        val options = arrayOf(
            getString(R.string.library_action_scan),
            getString(R.string.library_action_import)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.library_add_books_title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> checkPermissionAndScan()
                    1 -> openFilePicker()
                }
            }
            .show()
    }

    private fun openFilePicker() {
        val mimeTypes = arrayOf(
            "application/epub+zip",
            "application/pdf",
            "text/plain"
        )
        importBookLauncher.launch(mimeTypes)
    }

    private fun importBookFromUri(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { copyUriToInternalStorage(uri) }
            }

            val fileWithName = result.getOrNull()
            if (fileWithName == null) {
                Snackbar.make(binding.root, R.string.library_import_copy_failed, Snackbar.LENGTH_LONG).show()
                return@launch
            }

            val (file, displayName) = fileWithName
            viewModel.importBook(file, displayName)
        }
    }

    private fun copyUriToInternalStorage(uri: Uri): Pair<File, String> {
        val resolver = requireContext().contentResolver
        val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else null
            }

        val safeName = sanitizeFileName(displayName ?: generateFallbackName(uri))
        val destinationDir = File(requireContext().filesDir, "imports").apply { mkdirs() }
        val finalName = ensureUniqueName(destinationDir, appendExtensionIfMissing(safeName, uri))
        val destination = File(destinationDir, finalName)

        resolver.openInputStream(uri)?.use { input ->
            destination.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: throw IOException("Unable to open input stream")

        return destination to (displayName ?: destination.name)
    }

    private fun appendExtensionIfMissing(fileName: String, uri: Uri): String {
        val current = fileName
        val hasExtension = current.contains('.')
        if (hasExtension) return current
        val mime = requireContext().contentResolver.getType(uri)
        val extension = when (mime?.lowercase(Locale.getDefault())) {
            "application/epub+zip" -> "epub"
            "application/pdf" -> "pdf"
            "text/plain" -> "txt"
            else -> null
        }
        return if (extension != null) "$current.$extension" else current
    }

    private fun ensureUniqueName(directory: File, fileName: String): String {
        var candidate = fileName
        var counter = 1
        val base = fileName.substringBeforeLast('.', fileName)
        val extension = fileName.substringAfterLast('.', "")
        while (File(directory, candidate).exists()) {
            candidate = if (extension.isEmpty()) {
                "${base}_$counter"
            } else {
                "${base}_$counter.$extension"
            }
            counter++
        }
        return candidate
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\/:*?\"<>|]"), "_").trim().ifEmpty { "book" }
    }

    private fun generateFallbackName(uri: Uri): String {
        val mime = requireContext().contentResolver.getType(uri)
        val extension = when (mime?.lowercase(Locale.getDefault())) {
            "application/epub+zip" -> "epub"
            "application/pdf" -> "pdf"
            "text/plain" -> "txt"
            else -> "book"
        }
        return "import_${System.currentTimeMillis()}${if (extension == "book") "" else ".$extension"}"
    }

    private fun handleLibraryEvent(event: LibraryEvent) {
        val message = when (event) {
            is LibraryEvent.ImportSuccess -> getString(R.string.library_import_success, event.title)
            is LibraryEvent.ImportUnsupported -> getString(R.string.library_import_unsupported, event.name)
            LibraryEvent.ImportFailed -> getString(R.string.library_import_failed)
            is LibraryEvent.Duplicate -> getString(R.string.library_import_duplicate, event.title)
            is LibraryEvent.ScanCompleted -> getString(R.string.library_scan_completed, event.count)
            LibraryEvent.ScanNoNewBooks -> getString(R.string.library_scan_no_new_books)
            LibraryEvent.ScanFailed -> getString(R.string.library_scan_failed)
        }
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
