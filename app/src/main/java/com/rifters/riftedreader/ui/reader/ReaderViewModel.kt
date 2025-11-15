package com.rifters.riftedreader.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rifters.riftedreader.data.preferences.ReaderPreferences
import com.rifters.riftedreader.data.preferences.ReaderSettings
import com.rifters.riftedreader.data.repository.BookRepository
import com.rifters.riftedreader.domain.parser.BookParser
import com.rifters.riftedreader.domain.parser.PageContent
import com.rifters.riftedreader.domain.parser.TxtParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ReaderViewModel(
    private val bookId: String,
    private val bookFile: File,
    private val parser: BookParser,
    private val repository: BookRepository,
    readerPreferences: ReaderPreferences
) : ViewModel() {
    
    private val _pages = MutableStateFlow<List<PageContent>>(emptyList())
    val pages: StateFlow<List<PageContent>> = _pages.asStateFlow()

    private val _content = MutableStateFlow(PageContent.EMPTY)
    val content: StateFlow<PageContent> = _content.asStateFlow()

    private val _currentPage = MutableStateFlow(0)
    val currentPage: StateFlow<Int> = _currentPage.asStateFlow()

    private val _totalPages = MutableStateFlow(0)
    val totalPages: StateFlow<Int> = _totalPages.asStateFlow()

    private val _highlight = MutableStateFlow<TtsHighlight?>(null)
    val highlight: StateFlow<TtsHighlight?> = _highlight.asStateFlow()

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
        buildPagination()
    }

    private fun buildPagination() {
        viewModelScope.launch {
            try {
                val book = repository.getBookById(bookId)
                val savedPage = book?.currentPage ?: 0

                val pages = withContext(Dispatchers.IO) { generatePages() }

                _pages.value = pages
                _totalPages.value = pages.size

                val initialPage = if (pages.isNotEmpty()) {
                    savedPage.coerceIn(0, pages.lastIndex)
                } else {
                    0
                }
                _currentPage.value = initialPage
                _content.value = pages.getOrNull(initialPage) ?: PageContent.EMPTY
            } catch (e: Exception) {
                _pages.value = emptyList()
                _totalPages.value = 0
                _currentPage.value = 0
                _content.value = PageContent(text = "Error loading content: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    private suspend fun generatePages(): List<PageContent> {
        return withContext(Dispatchers.IO) {
            val pages = mutableListOf<PageContent>()
            val rawPageCount = parser.getPageCount(bookFile).coerceAtLeast(0)

            // For TXT we already paginate by fixed line counts, reuse as-is.
            if (parser is TxtParser) {
                for (index in 0 until rawPageCount) {
                    val pageContent = runCatching { parser.getPageContent(bookFile, index) }
                        .getOrDefault(PageContent.EMPTY)
                    pages += pageContent
                }
                return@withContext pages
            }

            // Generic fallback: treat each parser "page" (chapter/spine) and further split it.
            for (index in 0 until rawPageCount) {
                val chapterContent = runCatching { parser.getPageContent(bookFile, index) }
                    .getOrDefault(PageContent.EMPTY)
                pages += splitChapterContent(chapterContent)
            }

            if (pages.isEmpty()) {
                listOf(PageContent.EMPTY)
            } else {
                pages
            }
        }
    }

    private fun splitChapterContent(content: PageContent): List<PageContent> {
        val text = content.text.trim()
        if (text.isBlank()) {
            return emptyList()
        }

        if (text.length <= TARGET_CHARS_PER_PAGE) {
            return listOf(content)
        }

        val normalized = text.replace('\n', ' ').replace("\r", " ")
        val words = normalized.split(Regex("\\s+"))
        if (words.isEmpty()) {
            return listOf(content)
        }

        val pages = mutableListOf<PageContent>()
        val builder = StringBuilder()
        for (word in words) {
            if (builder.length + word.length + 1 > TARGET_CHARS_PER_PAGE && builder.isNotBlank()) {
                pages += PageContent(text = builder.toString().trim())
                builder.clear()
            }
            if (builder.isNotEmpty()) {
                builder.append(' ')
            }
            builder.append(word)
        }

        if (builder.isNotBlank()) {
            pages += PageContent(text = builder.toString().trim())
        }

        return pages
    }

    fun nextPage(): Boolean {
        val pages = _pages.value
        if (pages.isEmpty()) return false
        val current = _currentPage.value
        if (current >= pages.lastIndex) {
            return false
        }
        updateCurrentPage(current + 1)
        return true
    }

    fun previousPage(): Boolean {
        val pages = _pages.value
        if (pages.isEmpty()) return false
        val current = _currentPage.value
        if (current <= 0) {
            return false
        }
        updateCurrentPage(current - 1)
        return true
    }

    fun goToPage(page: Int): Boolean {
        val pages = _pages.value
        if (pages.isEmpty()) return false
        if (page !in 0..pages.lastIndex) {
            return false
        }
        updateCurrentPage(page)
        return true
    }

    fun hasNextPage(): Boolean {
        val pages = _pages.value
        if (pages.isEmpty()) return false
        return _currentPage.value < pages.lastIndex
    }

    fun hasPreviousPage(): Boolean {
        return _currentPage.value > 0
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

    private fun updateCurrentPage(index: Int) {
        val pages = _pages.value
        if (pages.isEmpty()) {
            _currentPage.value = 0
            _content.value = PageContent.EMPTY
            return
        }
        val safeIndex = index.coerceIn(0, pages.lastIndex)
        _currentPage.value = safeIndex
        _content.value = pages.getOrNull(safeIndex) ?: PageContent.EMPTY
    }

    fun publishHighlight(pageIndex: Int, range: IntRange?) {
        _highlight.value = TtsHighlight(pageIndex, range)
    }

    companion object {
        private const val TARGET_CHARS_PER_PAGE = 1800
    }
}

private fun StringBuilder.isNotBlank(): Boolean = this.any { !it.isWhitespace() }

data class TtsHighlight(
    val pageIndex: Int,
    val range: IntRange?
)
