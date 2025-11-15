package com.rifters.riftedreader.ui.library

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.core.content.ContextCompat
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.selection.SelectionPredicates
import androidx.recyclerview.selection.SelectionTracker
import androidx.recyclerview.selection.StorageStrategy
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.rifters.riftedreader.R
import com.rifters.riftedreader.data.database.BookDatabase
import com.rifters.riftedreader.data.database.entities.CollectionEntity
import com.rifters.riftedreader.data.database.entities.BookMeta
import com.rifters.riftedreader.data.preferences.LibraryPreferences
import com.rifters.riftedreader.data.repository.BookRepository
import com.rifters.riftedreader.data.repository.CollectionRepository
import com.rifters.riftedreader.databinding.DialogMetadataEditorBinding
import com.rifters.riftedreader.databinding.DialogTextInputBinding
import com.rifters.riftedreader.databinding.FragmentLibraryBinding
import com.rifters.riftedreader.domain.library.BookMetadataUpdate
import com.rifters.riftedreader.domain.library.FavoriteUpdate
import com.rifters.riftedreader.domain.library.LibrarySearchFilters
import com.rifters.riftedreader.domain.library.SavedLibrarySearch
import com.rifters.riftedreader.domain.library.SmartCollectionId
import com.rifters.riftedreader.domain.library.SmartCollectionSnapshot
import com.rifters.riftedreader.domain.library.TagsUpdateMode
import com.rifters.riftedreader.domain.parser.FormatCatalog
import com.rifters.riftedreader.ui.reader.ReaderActivity
import com.rifters.riftedreader.util.FileScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale

class LibraryFragment : Fragment() {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: LibraryViewModel
    private lateinit var adapter: BooksAdapter
    private var favoritesMenuItem: MenuItem? = null
    private var cachedCollections: List<CollectionEntity> = emptyList()
    private val formatChips = linkedMapOf<String, Chip>()
    private lateinit var addSavedSearchChip: Chip
    private val savedSearchChips = mutableMapOf<String, Chip>()
    private var cachedSavedSearches: List<SavedLibrarySearch> = emptyList()
    private val smartCollectionChips = mutableMapOf<SmartCollectionId, Chip>()
    private var updatingFilters = false
    private var selectionTracker: SelectionTracker<String>? = null
    private var selectionActionMode: ActionMode? = null

    private val selectionActionModeCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            mode.menuInflater.inflate(R.menu.library_selection_menu, menu)
            selectionActionMode = mode
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            return when (item.itemId) {
                R.id.menu_edit_metadata -> {
                    val selected = getSelectedBooks()
                    if (selected.isNotEmpty()) {
                        showMetadataEditorDialog(selected)
                    }
                    true
                }
                R.id.menu_select_all -> {
                    selectAllBooks()
                    true
                }
                else -> false
            }
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            selectionActionMode = null
            selectionTracker?.clearSelection()
        }
    }

    private val selectionObserver = object : SelectionTracker.SelectionObserver<String>() {
        override fun onSelectionChanged() {
            super.onSelectionChanged()
            updateSelectionUi()
        }
    }

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
        uri?.let { importBookFromUri(it) }
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

        val database = BookDatabase.getDatabase(requireContext())
        val repository = BookRepository(database.bookMetaDao())
        val collectionRepository = CollectionRepository(database.collectionDao())
        val fileScanner = FileScanner(requireContext(), repository)
        val libraryPreferences = LibraryPreferences(requireContext())
        viewModel = LibraryViewModel(repository, collectionRepository, fileScanner, libraryPreferences)

        setupRecyclerView()
        setupSearchView()
        setupFilterControls()
        setupSmartCollectionControls()
        setupSavedSearchControls()
        setupFab()
        setupMenu()
        observeViewModel()
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        selectionTracker?.onRestoreInstanceState(savedInstanceState)
        updateSelectionUi()
    }

    private fun setupRecyclerView() {
        adapter = BooksAdapter { book ->
            val intent = Intent(requireContext(), ReaderActivity::class.java).apply {
                putExtra("BOOK_ID", book.id)
                putExtra("BOOK_PATH", book.path)
                putExtra("BOOK_TITLE", book.title)
            }
            startActivity(intent)
        }

        binding.booksRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.booksRecyclerView.adapter = adapter

        val tracker = SelectionTracker.Builder(
            SELECTION_ID,
            binding.booksRecyclerView,
            BookItemKeyProvider(adapter),
            BookItemDetailsLookup(binding.booksRecyclerView),
            StorageStrategy.createStringStorage()
        ).withSelectionPredicate(SelectionPredicates.createSelectAnything())
            .build()

        tracker.addObserver(selectionObserver)
        selectionTracker = tracker
        adapter.selectionTracker = tracker
    }

    private fun setupSearchView() {
        binding.searchEditText.setText(viewModel.filters.value.query)
        binding.searchEditText.setSelection(binding.searchEditText.text?.length ?: 0)
        binding.searchEditText.addTextChangedListener { text ->
            val query = text?.toString().orEmpty()
            if (query != viewModel.filters.value.query) {
                viewModel.updateSearchQuery(query)
            }
        }
    }

    private fun setupFilterControls() {
        binding.favoritesChip.setOnCheckedChangeListener { _, isChecked ->
            if (!updatingFilters) {
                viewModel.setFavoritesOnly(isChecked)
            }
        }

        binding.collectionsChip.setOnClickListener {
            showCollectionsDialog()
        }

        binding.collectionsChip.setOnLongClickListener {
            showManageCollectionsDialog()
            true
        }

        setupFormatChips()
    }

    private fun setupSmartCollectionControls() {
        binding.smartCollectionsLabel.isVisible = false
        binding.smartCollectionsSection.isVisible = false
        binding.smartCollectionsChipGroup.clearCheck()
        smartCollectionChips.clear()
        binding.smartCollectionsChipGroup.removeAllViews()
    }

    private fun setupSavedSearchControls() {
        addSavedSearchChip = Chip(requireContext()).apply {
            text = getString(R.string.library_saved_search_add)
            isCheckable = false
            isClickable = true
            chipIcon = ContextCompat.getDrawable(requireContext(), android.R.drawable.ic_input_add)
            setEnsureMinTouchTargetSize(false)
            setOnClickListener { showSaveSearchDialog() }
        }
        binding.savedSearchChipGroup.removeAllViews()
        binding.savedSearchChipGroup.addView(addSavedSearchChip)
    }

    private fun renderSavedSearches(savedSearches: List<SavedLibrarySearch>) {
        cachedSavedSearches = savedSearches
        savedSearchChips.clear()
        val group = binding.savedSearchChipGroup
        group.removeAllViews()
        group.addView(addSavedSearchChip)

        savedSearches.forEach { search ->
            val chip = Chip(requireContext()).apply {
                text = search.name
                isCheckable = true
                isClickable = true
                isChecked = search.filters == viewModel.filters.value
                setEnsureMinTouchTargetSize(false)
                setOnClickListener { viewModel.applySavedSearch(search.id) }
                setOnLongClickListener {
                    showSavedSearchOptionsDialog(search)
                    true
                }
            }
            savedSearchChips[search.id] = chip
            group.addView(chip)
        }

        updateSavedSearchChipSelection(viewModel.filters.value)
    }

    private fun renderSmartCollections(snapshots: List<SmartCollectionSnapshot>) {
        val selected = viewModel.filters.value.smartCollection
        val shouldShow = snapshots.any { it.count > 0 } || selected != null

        binding.smartCollectionsSection.isVisible = shouldShow
        binding.smartCollectionsLabel.isVisible = shouldShow

        smartCollectionChips.clear()
        val group = binding.smartCollectionsChipGroup
        group.removeAllViews()

        if (!shouldShow) {
            return
        }

        snapshots.forEach { snapshot ->
            val chip = createSmartCollectionChip(snapshot.id)
            chip.text = formatSmartCollectionLabel(snapshot.id, snapshot.count)
            val enabled = snapshot.count > 0 || selected == snapshot.id
            chip.isEnabled = enabled
            chip.alpha = if (enabled) 1f else 0.5f
            smartCollectionChips[snapshot.id] = chip
            group.addView(chip)
        }

        updatingFilters = true
        smartCollectionChips.forEach { (id, chip) ->
            chip.isChecked = id == selected
        }
        updatingFilters = false
    }

    private fun createSmartCollectionChip(id: SmartCollectionId): Chip {
        return Chip(requireContext()).apply {
            this.id = View.generateViewId()
            isCheckable = true
            setEnsureMinTouchTargetSize(false)
            setOnCheckedChangeListener { _, isChecked ->
                if (updatingFilters) return@setOnCheckedChangeListener
                if (isChecked) {
                    viewModel.toggleSmartCollection(id)
                } else if (viewModel.filters.value.smartCollection == id) {
                    viewModel.clearSmartCollection()
                }
            }
        }
    }

    private fun formatSmartCollectionLabel(id: SmartCollectionId, count: Int): String {
        val title = when (id) {
            SmartCollectionId.RECENTLY_OPENED -> getString(R.string.library_smart_recent_title)
            SmartCollectionId.IN_PROGRESS -> getString(R.string.library_smart_in_progress_title)
            SmartCollectionId.COMPLETED -> getString(R.string.library_smart_completed_title)
            SmartCollectionId.NOT_STARTED -> getString(R.string.library_smart_not_started_title)
        }
        return getString(R.string.library_smart_chip_format, title, count)
    }

    private fun updateSavedSearchChipSelection(filters: LibrarySearchFilters) {
        cachedSavedSearches.forEach { search ->
            savedSearchChips[search.id]?.isChecked = search.filters == filters
        }
    }

    private fun setupFormatChips() {
        formatChips.clear()
        binding.formatChipGroup.removeAllViews()
        binding.formatChipGroup.contentDescription = getString(R.string.library_filter_formats_help)

        val descriptors = (FormatCatalog.supportedDescriptors() + FormatCatalog.inProgressDescriptors())
            .sortedBy { it.displayName }

        descriptors.forEach { descriptor ->
            val chip = Chip(requireContext()).apply {
                text = descriptor.displayName
                isCheckable = true
                tag = descriptor.id
                chipIcon = null
                setOnCheckedChangeListener { _, _ ->
                    if (!updatingFilters) {
                        syncFormatFiltersFromUi()
                    }
                }
            }
            formatChips[descriptor.id] = chip
            binding.formatChipGroup.addView(chip)
        }
    }

    private fun syncFormatFiltersFromUi() {
        val selected = formatChips.filterValues { it.isChecked }.keys.toSet()
        viewModel.setFormats(selected)
    }

    private fun setupMenu() {
        val menuHost: MenuHost = requireActivity()
        menuHost.addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.library_menu, menu)
                favoritesMenuItem = menu.findItem(R.id.menu_filter_favorites)?.apply {
                    isChecked = viewModel.filters.value.favoritesOnly
                }
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return when (menuItem.itemId) {
                    R.id.menu_settings -> {
                        findNavController().navigate(R.id.action_libraryFragment_to_settingsFragment)
                        true
                    }
                    R.id.menu_filter_favorites -> {
                        viewModel.toggleFavoritesOnly()
                        true
                    }
                    else -> false
                }
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
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
                        pruneSelection(books)
                    }
                }
                
                launch {
                    viewModel.collections.collect { collections ->
                        cachedCollections = collections
                        updateCollectionsChipLabel(viewModel.filters.value.collections)
                    }
                }

                launch {
                    viewModel.savedSearches.collect { saved ->
                        renderSavedSearches(saved)
                    }
                }

                launch {
                    viewModel.filters.collect { filters ->
                        updatingFilters = true
                        favoritesMenuItem?.isChecked = filters.favoritesOnly
                        binding.favoritesChip.isChecked = filters.favoritesOnly
                        formatChips.forEach { (id, chip) ->
                            chip.isChecked = id in filters.formats
                        }
                        smartCollectionChips.forEach { (id, chip) ->
                            chip.isChecked = id == filters.smartCollection
                        }
                        updatingFilters = false
                        updateCollectionsChipLabel(filters.collections)

                        val currentQuery = binding.searchEditText.text?.toString().orEmpty()
                        if (currentQuery != filters.query) {
                            binding.searchEditText.setText(filters.query)
                            binding.searchEditText.setSelection(filters.query.length)
                        }

                        updateSavedSearchChipSelection(filters)
                    }
                }

                launch {
                    viewModel.smartCollections.collect { snapshots ->
                        renderSmartCollections(snapshots)
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        selectionTracker?.onSaveInstanceState(outState)
    }

    private fun updateCollectionsChipLabel(selectedIds: Set<String>) {
        val count = selectedIds.size
        binding.collectionsChip.text = if (count == 0) {
            getString(R.string.library_filter_collections)
        } else {
            getString(R.string.library_filter_collections_count, count)
        }
    }

    private fun showSaveSearchDialog() {
        showSavedSearchNameDialog(
            title = getString(R.string.library_saved_search_save_title)
        ) { name ->
            viewModel.saveCurrentFiltersAsSearch(name)
        }
    }

    private fun showSavedSearchOptionsDialog(search: SavedLibrarySearch) {
        val options = arrayOf(
            getString(R.string.library_saved_search_overwrite),
            getString(R.string.library_saved_search_rename),
            getString(R.string.library_saved_search_delete)
        )

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(search.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> viewModel.overwriteSavedSearch(search.id)
                    1 -> showSavedSearchNameDialog(
                        title = getString(R.string.library_saved_search_rename_title),
                        initialValue = search.name
                    ) { newName ->
                        viewModel.renameSavedSearch(search.id, newName)
                    }
                    2 -> showDeleteSavedSearchDialog(search)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showDeleteSavedSearchDialog(search: SavedLibrarySearch) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.library_saved_search_delete_title)
            .setMessage(getString(R.string.library_saved_search_delete_confirm, search.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.deleteSavedSearch(search.id)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showSavedSearchNameDialog(
        title: String,
        initialValue: String = "",
        onSubmit: (String) -> Unit
    ) {
        val dialogBinding = DialogTextInputBinding.inflate(layoutInflater)
        dialogBinding.textInputLayout.hint = getString(R.string.library_saved_search_name_hint)
        dialogBinding.textInputEditText.setText(initialValue)
        dialogBinding.textInputEditText.setSelection(dialogBinding.textInputEditText.text?.length ?: 0)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveButton.setOnClickListener {
                val name = dialogBinding.textInputEditText.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    dialogBinding.textInputLayout.error = getString(R.string.library_saved_search_empty_name)
                } else {
                    dialogBinding.textInputLayout.error = null
                    onSubmit(name)
                    dialog.dismiss()
                }
            }
        }

        dialogBinding.textInputEditText.doOnTextChanged { text, _, _, _ ->
            if (!text.isNullOrEmpty()) {
                dialogBinding.textInputLayout.error = null
            }
        }

        dialog.show()
    }

    private fun showCollectionsDialog() {
        val collections = cachedCollections
        if (collections.isEmpty()) {
            showCreateCollectionDialog()
            return
        }

        val selectedIds = viewModel.filters.value.collections
        val names = collections.map { it.name }.toTypedArray()
        val checked = BooleanArray(collections.size) { index -> selectedIds.contains(collections[index].id) }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.library_filter_collections_title)
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(R.string.apply) { _, _ ->
                val chosen = mutableSetOf<String>()
                collections.indices.forEach { index ->
                    if (checked[index]) {
                        chosen += collections[index].id
                    }
                }
                viewModel.setCollections(chosen)
            }
            .setNeutralButton(R.string.clear) { _, _ ->
                viewModel.setCollections(emptySet())
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showManageCollectionsDialog() {
        val collections = cachedCollections
        if (collections.isEmpty()) {
            showCreateCollectionDialog()
            return
        }

        val options = ArrayList<String>(collections.size + 1).apply {
            addAll(collections.map { it.name })
            add(getString(R.string.library_collection_new))
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.library_manage_collections_title)
            .setItems(options.toTypedArray()) { _, which ->
                if (which == collections.size) {
                    showCreateCollectionDialog()
                } else {
                    showCollectionOptionsDialog(collections[which])
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showCollectionOptionsDialog(collection: CollectionEntity) {
        val items = arrayOf(
            getString(R.string.library_collection_rename),
            getString(R.string.library_collection_delete)
        )

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(collection.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showRenameCollectionDialog(collection)
                    1 -> showDeleteCollectionDialog(collection)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showCreateCollectionDialog() {
        showCollectionNameDialog(
            title = getString(R.string.library_collection_new)
        ) { name ->
            viewModel.createCollection(name)
        }
    }

    private fun showRenameCollectionDialog(collection: CollectionEntity) {
        showCollectionNameDialog(
            title = getString(R.string.library_collection_rename),
            initialValue = collection.name
        ) { name ->
            viewModel.renameCollection(collection, name)
        }
    }

    private fun showCollectionNameDialog(
        title: String,
        initialValue: String = "",
        onSubmit: (String) -> Unit
    ) {
        val dialogBinding = DialogTextInputBinding.inflate(layoutInflater)
        dialogBinding.textInputLayout.hint = getString(R.string.library_collection_name_hint)
        dialogBinding.textInputEditText.setText(initialValue)
        dialogBinding.textInputEditText.setSelection(dialogBinding.textInputEditText.text?.length ?: 0)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveButton.setOnClickListener {
                val name = dialogBinding.textInputEditText.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    dialogBinding.textInputLayout.error = getString(R.string.library_collection_empty_name)
                } else {
                    dialogBinding.textInputLayout.error = null
                    onSubmit(name)
                    dialog.dismiss()
                }
            }
        }

        dialogBinding.textInputEditText.doOnTextChanged { text, _, _, _ ->
            if (!text.isNullOrEmpty()) {
                dialogBinding.textInputLayout.error = null
            }
        }

        dialog.show()
    }

    private fun showDeleteCollectionDialog(collection: CollectionEntity) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.library_collection_delete)
            .setMessage(getString(R.string.library_collection_delete_confirm, collection.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.deleteCollection(collection)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
    
    private fun checkPermissionAndScan() {
        // For Android 11+ (API 30+), we need MANAGE_EXTERNAL_STORAGE to scan for documents
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                showAllFilesAccessDialog()
                return
            }
            // Permission granted, proceed with scan
            viewModel.scanForBooks()
            return
        }

        // For Android 10 and below, use READ_EXTERNAL_STORAGE
        val permission = Manifest.permission.READ_EXTERNAL_STORAGE
        
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
        val mimeTypes = FormatCatalog.importMimeTypes(includePreview = true).ifEmpty {
            setOf("application/epub+zip", "application/pdf", "text/plain")
        }
        importBookLauncher.launch(mimeTypes.toTypedArray())
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


    private fun pruneSelection(books: List<BookMeta>) {
        val tracker = selectionTracker ?: return
        if (!tracker.hasSelection()) return
        val availableIds = books.mapTo(mutableSetOf()) { it.id }
        val iterator = tracker.selection.iterator()
        val staleIds = mutableListOf<String>()
        while (iterator.hasNext()) {
            val id = iterator.next()
            if (id !in availableIds) {
                staleIds += id
            }
        }
        if (staleIds.isNotEmpty()) {
            staleIds.forEach { tracker.deselect(it) }
        }
    }

    private fun updateSelectionUi() {
        val tracker = selectionTracker ?: return
        val count = tracker.selection.size()
        if (count == 0) {
            selectionActionMode?.finish()
            return
        }

        if (selectionActionMode == null) {
            val activity = requireActivity() as AppCompatActivity
            selectionActionMode = activity.startSupportActionMode(selectionActionModeCallback)
        }

        selectionActionMode?.title = resources.getQuantityString(
            R.plurals.library_selection_count,
            count,
            count
        )
    }

    private fun selectAllBooks() {
        val tracker = selectionTracker ?: return
        val ids = adapter.currentList.map { it.id }
        tracker.setItemsSelected(ids, true)
    }

    private fun getSelectedBooks(): List<BookMeta> {
        val tracker = selectionTracker ?: return emptyList()
        if (!tracker.hasSelection()) return emptyList()
        val selectedIds = mutableSetOf<String>()
        val iterator = tracker.selection.iterator()
        while (iterator.hasNext()) {
            selectedIds += iterator.next()
        }
        if (selectedIds.isEmpty()) return emptyList()
        return adapter.currentList.filter { it.id in selectedIds }
    }

    private fun showMetadataEditorDialog(selectedBooks: List<BookMeta>) {
        val editorBinding = DialogMetadataEditorBinding.inflate(layoutInflater)
        val summary = if (selectedBooks.size == 1) {
            getString(R.string.library_metadata_edit_single, selectedBooks.first().title)
        } else {
            getString(R.string.library_metadata_edit_multiple, selectedBooks.size)
        }
        editorBinding.metadataSummary.text = summary

        editorBinding.titleInputLayout.isEnabled = false
        editorBinding.authorInputLayout.isEnabled = false
        editorBinding.tagsInputLayout.isEnabled = false
        editorBinding.clearAuthorCheckBox.isEnabled = false
        editorBinding.tagsModeGroup.isEnabled = false
        editorBinding.tagsModeGroup.check(editorBinding.tagsModeReplaceButton.id)
        editorBinding.favoriteGroup.check(editorBinding.favoriteNoChangeButton.id)

        editorBinding.updateTitleCheckBox.setOnCheckedChangeListener { _, isChecked ->
            editorBinding.titleInputLayout.isEnabled = isChecked
            if (!isChecked) {
                editorBinding.titleInputLayout.error = null
            } else if (selectedBooks.size == 1) {
                editorBinding.titleEditText.setText(selectedBooks.first().title)
                editorBinding.titleEditText.setSelection(editorBinding.titleEditText.text?.length ?: 0)
            }
        }

        editorBinding.updateAuthorCheckBox.setOnCheckedChangeListener { _, isChecked ->
            editorBinding.authorInputLayout.isEnabled = isChecked && !editorBinding.clearAuthorCheckBox.isChecked
            editorBinding.clearAuthorCheckBox.isEnabled = isChecked
            if (!isChecked) {
                editorBinding.authorInputLayout.error = null
                editorBinding.clearAuthorCheckBox.isChecked = false
            } else if (selectedBooks.size == 1) {
                editorBinding.authorEditText.setText(selectedBooks.first().author.orEmpty())
                editorBinding.authorEditText.setSelection(editorBinding.authorEditText.text?.length ?: 0)
            }
        }

        editorBinding.clearAuthorCheckBox.setOnCheckedChangeListener { _, isChecked ->
            editorBinding.authorInputLayout.isEnabled = editorBinding.updateAuthorCheckBox.isChecked && !isChecked
            if (isChecked) {
                editorBinding.authorEditText.setText("")
                editorBinding.authorInputLayout.error = null
            }
        }

        editorBinding.updateTagsCheckBox.setOnCheckedChangeListener { _, isChecked ->
            editorBinding.tagsInputLayout.isEnabled = isChecked
            editorBinding.tagsModeGroup.isEnabled = isChecked
            if (!isChecked) {
                editorBinding.tagsInputLayout.error = null
            } else if (selectedBooks.size == 1 && selectedBooks.first().tags.isNotEmpty()) {
                editorBinding.tagsEditText.setText(selectedBooks.first().tags.joinToString(", "))
            }
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.library_metadata_edit_title)
            .setView(editorBinding.root)
            .setPositiveButton(R.string.apply, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positive.setOnClickListener {
                val update = buildMetadataUpdate(editorBinding) ?: return@setOnClickListener
                val ids = selectedBooks.map { it.id }.toSet()
                viewModel.updateBookMetadata(ids, update)
                selectionActionMode?.finish()
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun buildMetadataUpdate(editorBinding: DialogMetadataEditorBinding): BookMetadataUpdate? {
        var hasChanges = false
        var title: String? = null
        var author: String? = null
        var clearAuthor = false
        var tags: List<String>? = null
        var tagsMode = TagsUpdateMode.REPLACE

        if (editorBinding.updateTitleCheckBox.isChecked) {
            val value = editorBinding.titleEditText.text?.toString()?.trim().orEmpty()
            if (value.isEmpty()) {
                editorBinding.titleInputLayout.error = getString(R.string.library_metadata_title_required)
                return null
            }
            editorBinding.titleInputLayout.error = null
            title = value
            hasChanges = true
        }

        if (editorBinding.updateAuthorCheckBox.isChecked) {
            if (editorBinding.clearAuthorCheckBox.isChecked) {
                editorBinding.authorInputLayout.error = null
                clearAuthor = true
                hasChanges = true
            } else {
                val value = editorBinding.authorEditText.text?.toString()?.trim().orEmpty()
                if (value.isEmpty()) {
                    editorBinding.authorInputLayout.error = getString(R.string.library_metadata_author_required)
                    return null
                }
                editorBinding.authorInputLayout.error = null
                author = value
                hasChanges = true
            }
        }

        if (editorBinding.updateTagsCheckBox.isChecked) {
            val input = editorBinding.tagsEditText.text?.toString().orEmpty()
            val normalized = input.split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            tagsMode = if (editorBinding.tagsModeGroup.checkedButtonId == editorBinding.tagsModeAddButton.id) {
                TagsUpdateMode.APPEND
            } else {
                TagsUpdateMode.REPLACE
            }
            if (tagsMode == TagsUpdateMode.APPEND && normalized.isEmpty()) {
                editorBinding.tagsInputLayout.error = getString(R.string.library_metadata_tags_required)
                return null
            }
            editorBinding.tagsInputLayout.error = null
            tags = normalized
            hasChanges = true
        }

        val favoriteUpdate = when (editorBinding.favoriteGroup.checkedButtonId) {
            editorBinding.favoriteMarkButton.id -> FavoriteUpdate.Favorite
            editorBinding.favoriteUnmarkButton.id -> FavoriteUpdate.NotFavorite
            else -> FavoriteUpdate.NoChange
        }
        if (favoriteUpdate != FavoriteUpdate.NoChange) {
            hasChanges = true
        }

        if (!hasChanges) {
            Snackbar.make(binding.root, R.string.library_metadata_no_changes, Snackbar.LENGTH_SHORT).show()
            return null
        }

        return BookMetadataUpdate(
            title = title,
            author = author,
            clearAuthor = clearAuthor,
            tags = tags,
            tagsMode = tagsMode,
            favorite = favoriteUpdate
        )
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
            is LibraryEvent.MetadataUpdated -> getString(R.string.library_metadata_update_success, event.count)
            LibraryEvent.MetadataUpdateFailed -> getString(R.string.library_metadata_update_failed)
            is LibraryEvent.CollectionCreated -> getString(R.string.library_collection_created, event.name)
            is LibraryEvent.CollectionRenamed -> getString(R.string.library_collection_renamed, event.name)
            is LibraryEvent.CollectionDeleted -> getString(R.string.library_collection_deleted, event.name)
            is LibraryEvent.CollectionNameConflict -> getString(R.string.library_collection_exists)
            LibraryEvent.CollectionOperationFailed -> getString(R.string.library_collection_operation_failed)
            is LibraryEvent.SavedSearchCreated -> getString(R.string.library_saved_search_created, event.name)
            is LibraryEvent.SavedSearchApplied -> getString(R.string.library_saved_search_applied, event.name)
            is LibraryEvent.SavedSearchUpdated -> getString(R.string.library_saved_search_updated, event.name)
            is LibraryEvent.SavedSearchDeleted -> getString(R.string.library_saved_search_deleted, event.name)
            is LibraryEvent.SavedSearchNameConflict -> getString(R.string.library_saved_search_name_conflict, event.name)
            LibraryEvent.SavedSearchOperationFailed -> getString(R.string.library_saved_search_operation_failed)
        }
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        selectionActionMode?.finish()
        selectionActionMode = null
        selectionTracker = null
        adapter.selectionTracker = null
        _binding = null
        favoritesMenuItem = null
        formatChips.clear()
        cachedCollections = emptyList()
        savedSearchChips.clear()
        cachedSavedSearches = emptyList()
    }

    companion object {
        private const val SELECTION_ID = "library_selection"
    }

}
