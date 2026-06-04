package com.rifters.riftedreader.ui.library

import android.database.sqlite.SQLiteConstraintException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rifters.riftedreader.data.database.entities.BookMeta as Book
import com.rifters.riftedreader.data.database.entities.CollectionEntity
import com.rifters.riftedreader.data.preferences.LibraryPreferences
import com.rifters.riftedreader.data.repository.BookmarkRepository
import com.rifters.riftedreader.data.repository.BookRepository
import com.rifters.riftedreader.data.repository.CollectionRepository
import com.rifters.riftedreader.domain.library.BookMetadataUpdate
import com.rifters.riftedreader.domain.library.FavoriteUpdate
import com.rifters.riftedreader.domain.library.LibrarySearchFilters
import com.rifters.riftedreader.domain.library.LibrarySearchUseCase
import com.rifters.riftedreader.domain.library.SavedLibrarySearch
import com.rifters.riftedreader.domain.library.SmartCollectionId
import com.rifters.riftedreader.domain.library.SmartCollectionSnapshot
import com.rifters.riftedreader.domain.library.SmartCollections
import com.rifters.riftedreader.domain.library.TagsUpdateMode
import com.rifters.riftedreader.domain.parser.ParserFactory
import com.rifters.riftedreader.util.FileScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class BookWithProgress(
    val book: Book,
    val lastReadChapterIndex: Int,
    val lastReadCharOffset: Int,
    val lastOpenedTimestamp: Long,
    val totalChapters: Int
)

class LibraryViewModel(
    private val repository: BookRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val collectionRepository: CollectionRepository,
    private val fileScanner: FileScanner,
    private val libraryPreferences: LibraryPreferences
) : ViewModel() {

    private val searchUseCase = LibrarySearchUseCase(repository, collectionRepository)

    private val _books = MutableStateFlow<List<Book>>(emptyList())
    val books: StateFlow<List<Book>> = _books.asStateFlow()

    private val chapterCountCache = ConcurrentHashMap<String, Int>()

    val continueReadingBooks: StateFlow<List<BookWithProgress>> = repository.allBooks
        .map { allBooks -> buildContinueReadingBooks(allBooks) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    private val _collections = MutableStateFlow<List<CollectionEntity>>(emptyList())
    val collections: StateFlow<List<CollectionEntity>> = _collections.asStateFlow()

    private val _filters = MutableStateFlow(libraryPreferences.readFilters())
    val filters: StateFlow<LibrarySearchFilters> = _filters.asStateFlow()

    private val _smartCollections = MutableStateFlow(SmartCollections.snapshot(emptyList()))
    val smartCollections: StateFlow<List<SmartCollectionSnapshot>> = _smartCollections.asStateFlow()

    private val _availableTags = MutableStateFlow<List<String>>(emptyList())
    private val _activeTagFilter = MutableStateFlow<String?>(null)
    val activeTagFilter: StateFlow<String?> = _activeTagFilter.asStateFlow()
    val availableTags: StateFlow<List<String>> = _availableTags.asStateFlow()

    val savedSearches: StateFlow<List<SavedLibrarySearch>> = libraryPreferences.savedSearches

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _scanProgress = MutableStateFlow(Pair(0, 0))
    val scanProgress: StateFlow<Pair<Int, Int>> = _scanProgress.asStateFlow()

    private val _events = MutableSharedFlow<LibraryEvent>()
    val events: SharedFlow<LibraryEvent> = _events

    init {
        observeBooks()
        observeCollections()
        observeAvailableTags()
        observeSmartCollections()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeBooks() {
        viewModelScope.launch {
            _filters.combine(_activeTagFilter) { filters, tag ->
                filters.copy(tags = listOfNotNull(tag).toSet())
            }.flatMapLatest { filters ->
                searchUseCase.observe(filters)
            }.collectLatest { bookList ->
                _books.value = bookList
            }
        }
    }

    private fun observeCollections() {
        viewModelScope.launch {
            collectionRepository.collections.collectLatest { value ->
                _collections.value = value
            }
        }
    }

    private fun observeSmartCollections() {
        viewModelScope.launch {
            repository.allBooks.collectLatest { allBooks ->
                val snapshot = SmartCollections.snapshot(allBooks)
                _smartCollections.value = snapshot

                val selected = _filters.value.smartCollection
                if (selected != null) {
                    val hasMatches = snapshot.firstOrNull { it.id == selected }?.count?.let { it > 0 } == true
                    if (!hasMatches) {
                        updateFilters { current -> current.copy(smartCollection = null) }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeAvailableTags() {
        viewModelScope.launch {
            repository.allBooks
                .mapLatest { allBooks ->
                    withContext(Dispatchers.Default) {
                        extractUniqueTagsWithPreferredCasing(allBooks)
                    }
                }
                .distinctUntilChanged()
                .collectLatest { extractedTags ->
                    _availableTags.value = extractedTags
                }
        }
    }
    
    fun scanForBooks() {
        viewModelScope.launch {
            _isScanning.value = true
            try {
                val added = fileScanner.scanForBooks { scanned, found ->
                    _scanProgress.value = Pair(scanned, found)
                }
                if (added.isEmpty()) {
                    _events.emit(LibraryEvent.ScanNoNewBooks)
                } else {
                    _events.emit(LibraryEvent.ScanCompleted(added.size))
                }
            } catch (e: Exception) {
                _events.emit(LibraryEvent.ScanFailed)
            } finally {
                _isScanning.value = false
            }
        }
    }

    fun updateSearchQuery(query: String) = updateFilters { current -> current.copy(query = query) }

    fun setFormats(formats: Set<String>) = updateFilters { current -> current.copy(formats = formats) }

    fun setCollections(collectionIds: Set<String>) = updateFilters { current -> current.copy(collections = collectionIds) }

    fun setTags(tags: Set<String>) = updateFilters { current -> current.copy(tags = tags) }

    fun setTagFilter(tag: String?) {
        _activeTagFilter.value = tag
    }

    fun setFavoritesOnly(enabled: Boolean) = updateFilters { current -> current.copy(favoritesOnly = enabled) }

    fun toggleFavoritesOnly() = updateFilters { current -> current.copy(favoritesOnly = !current.favoritesOnly) }

    fun toggleSmartCollection(id: SmartCollectionId) = updateFilters { current ->
        val newSelection = if (current.smartCollection == id) null else id
        current.copy(smartCollection = newSelection)
    }

    fun clearSmartCollection() = updateFilters { current ->
        if (current.smartCollection == null) current else current.copy(smartCollection = null)
    }

    fun saveCurrentFiltersAsSearch(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            viewModelScope.launch { _events.emit(LibraryEvent.SavedSearchOperationFailed) }
            return
        }
        viewModelScope.launch {
            runCatching { libraryPreferences.addSavedSearch(trimmed, _filters.value) }
                .onSuccess { search -> _events.emit(LibraryEvent.SavedSearchCreated(search.name)) }
                .onFailure { throwable ->
                    if (throwable is IllegalArgumentException) {
                        _events.emit(LibraryEvent.SavedSearchNameConflict(trimmed))
                    } else {
                        _events.emit(LibraryEvent.SavedSearchOperationFailed)
                    }
                }
        }
    }

    fun applySavedSearch(id: String) {
        val search = libraryPreferences.getSavedSearch(id) ?: return
        val filters = search.filters
        libraryPreferences.saveFilters(filters)
        if (_filters.value != filters) {
            _filters.value = filters
        }
        viewModelScope.launch { _events.emit(LibraryEvent.SavedSearchApplied(search.name)) }
    }

    fun overwriteSavedSearch(id: String) {
        viewModelScope.launch {
            val result = runCatching { libraryPreferences.updateSavedSearch(id, filters = _filters.value) }
            val updated = result.getOrNull()
            when {
                updated != null -> _events.emit(LibraryEvent.SavedSearchUpdated(updated.name))
                result.exceptionOrNull() is IllegalArgumentException -> {
                    val existing = libraryPreferences.getSavedSearch(id)
                    if (existing != null) {
                        _events.emit(LibraryEvent.SavedSearchNameConflict(existing.name))
                    } else {
                        _events.emit(LibraryEvent.SavedSearchOperationFailed)
                    }
                }
                else -> _events.emit(LibraryEvent.SavedSearchOperationFailed)
            }
        }
    }

    fun renameSavedSearch(id: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) {
            viewModelScope.launch { _events.emit(LibraryEvent.SavedSearchOperationFailed) }
            return
        }
        viewModelScope.launch {
            val result = runCatching { libraryPreferences.updateSavedSearch(id, name = trimmed) }
            val updated = result.getOrNull()
            when {
                updated != null -> _events.emit(LibraryEvent.SavedSearchUpdated(updated.name))
                result.exceptionOrNull() is IllegalArgumentException -> _events.emit(LibraryEvent.SavedSearchNameConflict(trimmed))
                else -> _events.emit(LibraryEvent.SavedSearchOperationFailed)
            }
        }
    }

    fun deleteSavedSearch(id: String) {
        viewModelScope.launch {
            val removed = libraryPreferences.deleteSavedSearch(id)
            if (removed != null) {
                _events.emit(LibraryEvent.SavedSearchDeleted(removed.name))
            } else {
                _events.emit(LibraryEvent.SavedSearchOperationFailed)
            }
        }
    }

    fun createCollection(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            viewModelScope.launch { _events.emit(LibraryEvent.CollectionOperationFailed) }
            return
        }
        viewModelScope.launch {
            try {
                val collection = collectionRepository.createCollection(trimmed)
                _events.emit(LibraryEvent.CollectionCreated(collection.name))
            } catch (e: SQLiteConstraintException) {
                _events.emit(LibraryEvent.CollectionNameConflict(trimmed))
            } catch (e: Exception) {
                _events.emit(LibraryEvent.CollectionOperationFailed)
            }
        }
    }

    fun renameCollection(collection: CollectionEntity, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) {
            viewModelScope.launch { _events.emit(LibraryEvent.CollectionOperationFailed) }
            return
        }
        viewModelScope.launch {
            try {
                collectionRepository.updateCollection(collection.copy(name = trimmed))
                _events.emit(LibraryEvent.CollectionRenamed(trimmed))
            } catch (e: SQLiteConstraintException) {
                _events.emit(LibraryEvent.CollectionNameConflict(trimmed))
            } catch (e: Exception) {
                _events.emit(LibraryEvent.CollectionOperationFailed)
            }
        }
    }

    fun deleteCollection(collection: CollectionEntity) {
        viewModelScope.launch {
            try {
                collectionRepository.deleteCollection(collection)
                updateFilters { current ->
                    if (collection.id in current.collections) {
                        current.copy(collections = current.collections - collection.id)
                    } else {
                        current
                    }
                }
                _events.emit(LibraryEvent.CollectionDeleted(collection.name))
            } catch (e: Exception) {
                _events.emit(LibraryEvent.CollectionOperationFailed)
            }
        }
    }

    fun updateBookMetadata(bookIds: Set<String>, update: BookMetadataUpdate) {
        if (bookIds.isEmpty()) return
        val selectedBooks = books.value.filter { it.id in bookIds }
        if (selectedBooks.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                selectedBooks.forEach { book ->
                    var updated = book

                    update.title?.trim()?.takeIf { it.isNotEmpty() }?.let { title ->
                        updated = updated.copy(title = title)
                    }

                    when {
                        update.clearAuthor -> updated = updated.copy(author = null)
                        update.author != null -> {
                            val trimmed = update.author.trim().takeIf { it.isNotEmpty() }
                            updated = updated.copy(author = trimmed)
                        }
                    }

                    update.tags?.let { tags ->
                        val normalized = tags.map { it.trim() }.filter { it.isNotEmpty() }
                        val newTags = when (update.tagsMode) {
                            TagsUpdateMode.REPLACE -> normalized
                            TagsUpdateMode.APPEND -> (book.tags + normalized)
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                                .distinct()
                        }
                        updated = updated.copy(tags = newTags)
                    }

                    when (update.favorite) {
                        FavoriteUpdate.Favorite -> updated = updated.copy(isFavorite = true)
                        FavoriteUpdate.NotFavorite -> updated = updated.copy(isFavorite = false)
                        FavoriteUpdate.NoChange -> {}
                    }

                    if (updated != book) {
                        repository.updateBook(updated)
                    }
                }
            }.onSuccess {
                _events.emit(LibraryEvent.MetadataUpdated(selectedBooks.size))
            }.onFailure {
                _events.emit(LibraryEvent.MetadataUpdateFailed)
            }
        }
    }

    private fun updateFilters(transform: (LibrarySearchFilters) -> LibrarySearchFilters) {
        _filters.update { current ->
            val updated = transform(current)
            libraryPreferences.saveFilters(updated)
            updated
        }
    }

    private suspend fun buildContinueReadingBooks(allBooks: List<Book>): List<BookWithProgress> {
        return withContext(Dispatchers.IO) {
            buildList {
                for (book in allBooks) {
                    val bookmark = bookmarkRepository.loadLastRead(book.id)
                    val hasProgress = book.lastOpened > 0L ||
                        bookmark != null ||
                        book.currentChapterIndex > 0 ||
                        book.currentCharacterOffset > 0 ||
                        book.percentComplete > 0f
                    if (!hasProgress) continue

                    add(
                        BookWithProgress(
                            book = book,
                            lastReadChapterIndex = bookmark?.chapterIndex ?: book.currentChapterIndex,
                            lastReadCharOffset = bookmark?.charOffset ?: book.currentCharacterOffset,
                            lastOpenedTimestamp = maxOf(book.lastOpened, bookmark?.savedAt ?: 0L),
                            totalChapters = resolveTotalChapters(book)
                        )
                    )
                }
            }.sortedByDescending { it.lastOpenedTimestamp }
        }
    }

    private suspend fun resolveTotalChapters(book: Book): Int {
        chapterCountCache[book.id]?.let { return it }
        if (book.totalPages > 0) {
            return book.totalPages.also { chapterCountCache[book.id] = it }
        }

        val totalChapters = runCatching {
            val bookFile = File(book.path)
            val parser = ParserFactory.getParser(bookFile)
            parser?.getPageCount(bookFile)
        }.getOrNull()
            ?.coerceAtLeast(0)
            ?: 0

        chapterCountCache[book.id] = totalChapters
        return totalChapters
    }
    
    fun deleteBook(book: Book) {
        viewModelScope.launch {
            repository.deleteBook(book)
        }
    }

    fun importBook(file: File, displayName: String? = null) {
        viewModelScope.launch {
            try {
                val parser = ParserFactory.getParser(file)
                if (parser == null) {
                    file.delete()
                    _events.emit(LibraryEvent.ImportUnsupported(displayName ?: file.name))
                    return@launch
                }

                val existing = repository.getBookByPath(file.absolutePath)
                if (existing != null) {
                    file.delete()
                    _events.emit(LibraryEvent.Duplicate(existing.title.ifBlank { displayName ?: existing.path }))
                    return@launch
                }

                val metadata = withContext(Dispatchers.IO) { parser.extractMetadata(file) }
                repository.insertBook(metadata)
                _events.emit(LibraryEvent.ImportSuccess(metadata.title.ifBlank { displayName ?: file.name }))
            } catch (e: Exception) {
                file.delete()
                _events.emit(LibraryEvent.ImportFailed)
            }
        }
    }
}

sealed interface LibraryEvent {
    data class ImportSuccess(val title: String) : LibraryEvent
    data class ImportUnsupported(val name: String) : LibraryEvent
    object ImportFailed : LibraryEvent
    data class Duplicate(val title: String) : LibraryEvent
    data class ScanCompleted(val count: Int) : LibraryEvent
    object ScanNoNewBooks : LibraryEvent
    object ScanFailed : LibraryEvent
    data class MetadataUpdated(val count: Int) : LibraryEvent
    object MetadataUpdateFailed : LibraryEvent
    data class CollectionCreated(val name: String) : LibraryEvent
    data class CollectionRenamed(val name: String) : LibraryEvent
    data class CollectionDeleted(val name: String) : LibraryEvent
    data class CollectionNameConflict(val name: String) : LibraryEvent
    object CollectionOperationFailed : LibraryEvent
    data class SavedSearchCreated(val name: String) : LibraryEvent
    data class SavedSearchApplied(val name: String) : LibraryEvent
    data class SavedSearchUpdated(val name: String) : LibraryEvent
    data class SavedSearchDeleted(val name: String) : LibraryEvent
    data class SavedSearchNameConflict(val name: String) : LibraryEvent
    object SavedSearchOperationFailed : LibraryEvent
}
