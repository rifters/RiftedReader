package com.rifters.riftedreader.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rifters.riftedreader.data.preferences.ReaderPreferences
import com.rifters.riftedreader.data.preferences.ReaderSettings
import com.rifters.riftedreader.data.repository.BookRepository
import com.rifters.riftedreader.domain.parser.BookParser
import com.rifters.riftedreader.domain.parser.PageContent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class ReaderViewModel(
    private val bookId: String,
    private val bookFile: File,
    private val parser: BookParser,
    private val repository: BookRepository,
    readerPreferences: ReaderPreferences
) : ViewModel() {
    
    private val _content = MutableStateFlow(PageContent.EMPTY)
    val content: StateFlow<PageContent> = _content.asStateFlow()
    
    private val _currentPage = MutableStateFlow(0)
    val currentPage: StateFlow<Int> = _currentPage.asStateFlow()
    
    private val _totalPages = MutableStateFlow(0)
    val totalPages: StateFlow<Int> = _totalPages.asStateFlow()

    val readerSettings: StateFlow<ReaderSettings> = readerPreferences.settings

    class Factory(
        private val bookId: String,
        private val bookFile: File,
        private val parser: BookParser,
        private val repository: BookRepository,
        private val readerPreferences: ReaderPreferences
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ReaderViewModel::class.java)) {
                "Unknown ViewModel class ${modelClass.name}"
            }
            return ReaderViewModel(bookId, bookFile, parser, repository, readerPreferences) as T
        }
    }
    
    init {
        loadBookInfo()
    }
    
    private fun loadBookInfo() {
        viewModelScope.launch {
            try {
                val book = repository.getBookById(bookId)
                if (book != null) {
                    _currentPage.value = book.currentPage
                }
                
                val pageCount = parser.getPageCount(bookFile)
                _totalPages.value = pageCount
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    fun loadCurrentPage() {
        viewModelScope.launch {
            try {
                val pageContent = parser.getPageContent(bookFile, _currentPage.value)
                _content.value = pageContent
            } catch (e: Exception) {
                _content.value = PageContent(text = "Error loading content: ${e.message}")
                e.printStackTrace()
            }
        }
    }
    
    fun nextPage() {
        if (_currentPage.value < _totalPages.value - 1) {
            _currentPage.value += 1
            loadCurrentPage()
        }
    }
    
    fun previousPage() {
        if (_currentPage.value > 0) {
            _currentPage.value -= 1
            loadCurrentPage()
        }
    }
    
    fun goToPage(page: Int) {
        if (page in 0 until _totalPages.value) {
            _currentPage.value = page
            loadCurrentPage()
        }
    }
    
    fun saveProgress() {
        viewModelScope.launch {
            try {
                repository.updateReadingProgress(
                    bookId,
                    _currentPage.value,
                    _totalPages.value
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
