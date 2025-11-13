package com.rifters.riftedreader.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rifters.riftedreader.data.database.entities.BookMeta
import com.rifters.riftedreader.data.repository.BookRepository
import com.rifters.riftedreader.util.FileScanner
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
                fileScanner.scanForBooks { scanned, found ->
                    _scanProgress.value = Pair(scanned, found)
                }
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
}
