package com.rifters.riftedreader.ui.calibre

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rifters.riftedreader.data.calibre.BookFormat
import com.rifters.riftedreader.data.calibre.CalibreBook
import com.rifters.riftedreader.data.calibre.CalibreConfigException
import com.rifters.riftedreader.data.calibre.CalibreConnectionConfig
import com.rifters.riftedreader.data.calibre.CalibreConnectionRepository
import com.rifters.riftedreader.data.calibre.CalibreContentServerRepository
import com.rifters.riftedreader.data.calibre.CalibreException
import com.rifters.riftedreader.util.AppLogger
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch

class CalibreLibraryViewModel(
    private val contentServerRepository: CalibreContentServerRepository,
    private val connectionRepository: CalibreConnectionRepository,
) : ViewModel() {

    private val _libraryState = MutableStateFlow<CalibreLibraryState>(CalibreLibraryState.Idle)
    val libraryState: StateFlow<CalibreLibraryState> = _libraryState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val isContentServerEnabled: StateFlow<Boolean> = connectionRepository.configFlow()
        .map { it.contentServerEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private var preferredFormat: BookFormat = BookFormat.ANY
    private var isPageLoading = false

    init {
        observeConfig()
        observeSearch()
    }

    fun loadLibrary() {
        loadPage(offset = 0, append = false, query = _searchQuery.value)
    }

    fun loadNextPage() {
        val current = _libraryState.value as? CalibreLibraryState.Success ?: return
        if (!current.hasMore || isPageLoading) return
        loadPage(offset = current.books.size, append = true, query = _searchQuery.value)
    }

    fun search(query: String) {
        _searchQuery.value = query
    }

    fun clearSearch() {
        if (_searchQuery.value.isEmpty()) return
        _searchQuery.value = ""
        loadLibrary()
    }

    fun downloadBook(book: CalibreBook, format: BookFormat) {
        val message = "download requested: ${book.title} $format"
        Log.i(TAG, message)
        AppLogger.event(TAG, message, "ui/calibre/download")
    }

    private fun observeConfig() {
        viewModelScope.launch {
            connectionRepository.configFlow().collect { config ->
                preferredFormat = config.preferredFormat
                if (!config.contentServerEnabled) {
                    _libraryState.value = CalibreLibraryState.Disabled
                } else if (_libraryState.value is CalibreLibraryState.Disabled) {
                    _libraryState.value = CalibreLibraryState.Idle
                }
            }
        }
    }

    @OptIn(FlowPreview::class)
    private fun observeSearch() {
        viewModelScope.launch {
            _searchQuery
                .debounce(SEARCH_DEBOUNCE_MS)
                .distinctUntilChanged()
                .collect { query ->
                    loadPage(offset = 0, append = false, query = query)
                }
        }
    }

    private fun loadPage(offset: Int, append: Boolean, query: String) {
        if (isPageLoading) return
        viewModelScope.launch {
            val config = connectionRepository.loadConfig()
            preferredFormat = config.preferredFormat
            if (!config.contentServerEnabled) {
                _libraryState.value = CalibreLibraryState.Disabled
                return@launch
            }

            isPageLoading = true
            if (!append) {
                _libraryState.value = CalibreLibraryState.Loading
            }

            val result = if (query.isBlank()) {
                contentServerRepository.getLibrary(offset = offset, limit = PAGE_SIZE)
            } else {
                contentServerRepository.searchBooks(query = query.trim(), offset = offset, limit = PAGE_SIZE)
            }

            result.fold(
                onSuccess = { library ->
                    val existing = if (append) {
                        (_libraryState.value as? CalibreLibraryState.Success)?.books.orEmpty()
                    } else {
                        emptyList()
                    }
                    val books = existing + library.books
                    _libraryState.value = CalibreLibraryState.Success(
                        books = books,
                        totalCount = library.totalCount,
                        hasMore = books.size < library.totalCount,
                    )
                },
                onFailure = { throwable ->
                    _libraryState.value = CalibreLibraryState.Error(throwable.toCalibreException())
                }
            )
            isPageLoading = false
        }
    }

    fun preferredFormatFor(book: CalibreBook): BookFormat {
        val available = book.formats.map { it.uppercase() }.toSet()
        return if (preferredFormat != BookFormat.ANY && preferredFormat.name in available) {
            preferredFormat
        } else {
            supportedFormats(book).firstOrNull() ?: BookFormat.ANY
        }
    }

    private fun Throwable.toCalibreException(): CalibreException {
        return this as? CalibreException ?: CalibreConfigException(localizedMessage ?: "Unable to load Calibre library", this)
    }

    companion object {
        private const val TAG = "CalibreLibraryViewModel"
        private const val PAGE_SIZE = 50
        private const val SEARCH_DEBOUNCE_MS = 400L
    }
}

sealed class CalibreLibraryState {
    object Idle : CalibreLibraryState()
    object Loading : CalibreLibraryState()
    data class Success(
        val books: List<CalibreBook>,
        val totalCount: Int,
        val hasMore: Boolean,
    ) : CalibreLibraryState()
    data class Error(val exception: CalibreException) : CalibreLibraryState()
    object Disabled : CalibreLibraryState()
}

fun supportedFormats(book: CalibreBook): List<BookFormat> {
    val available = book.formats.map { it.uppercase() }.toSet()
    return listOf(BookFormat.EPUB, BookFormat.MOBI, BookFormat.PDF).filter { it.name in available }
}
