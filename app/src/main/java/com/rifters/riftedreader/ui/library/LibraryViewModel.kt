package com.rifters.riftedreader.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rifters.riftedreader.data.database.entities.BookMeta
import com.rifters.riftedreader.data.repository.BookRepository
import com.rifters.riftedreader.domain.parser.ParserFactory
import com.rifters.riftedreader.util.FileScanner
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.File

class LibraryViewModel(
    private val repository: BookRepository,
    private val fileScanner: FileScanner
) : ViewModel() {
    
    private val _books = MutableStateFlow<List<BookMeta>>(emptyList())
    val books: StateFlow<List<BookMeta>> = _books.asStateFlow()
    
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()
    
    private val _scanProgress = MutableStateFlow(Pair(0, 0))
    val scanProgress: StateFlow<Pair<Int, Int>> = _scanProgress.asStateFlow()

    private val _events = MutableSharedFlow<LibraryEvent>()
    val events: SharedFlow<LibraryEvent> = _events
    
    // Track the active Flow collection job to cancel when needed
    private var booksCollectionJob: Job? = null
    
    init {
        loadBooks()
    }
    
    private fun loadBooks() {
        // Cancel any existing collection job to prevent memory leaks
        booksCollectionJob?.cancel()
        
        booksCollectionJob = viewModelScope.launch {
            repository.allBooks.collect { bookList ->
                _books.value = bookList
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
    
    fun searchBooks(query: String) {
        // Cancel any existing collection job to prevent race conditions and memory leaks
        booksCollectionJob?.cancel()
        
        if (query.isBlank()) {
            loadBooks()
        } else {
            booksCollectionJob = viewModelScope.launch {
                repository.searchBooks(query).collect { searchResults ->
                    _books.value = searchResults
                }
            }
        }
    }
    
    fun deleteBook(book: BookMeta) {
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
}
