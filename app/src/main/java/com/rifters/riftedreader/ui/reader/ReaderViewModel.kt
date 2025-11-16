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

    private val _tableOfContents = MutableStateFlow<List<com.rifters.riftedreader.domain.parser.TocEntry>>(emptyList())
    val tableOfContents: StateFlow<List<com.rifters.riftedreader.domain.parser.TocEntry>> = _tableOfContents.asStateFlow()

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
        loadTableOfContents()
    }

    private fun loadTableOfContents() {
        viewModelScope.launch {
            try {
                val toc = withContext(Dispatchers.IO) {
                    parser.getTableOfContents(bookFile)
                }
                _tableOfContents.value = toc
            } catch (e: Exception) {
                _tableOfContents.value = emptyList()
                e.printStackTrace()
            }
        }
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

        // For HTML content, split by paragraphs while preserving HTML structure
        // This is a simple approach that works for most EPUB content
        if (!content.html.isNullOrBlank()) {
            return splitHtmlContent(content)
        }

        // Preserve paragraph breaks by treating them as special markers
        val paragraphs = text.split(Regex("\n\n+"))
        val pages = mutableListOf<PageContent>()
        val builder = StringBuilder()
        
        for ((_, paragraph) in paragraphs.withIndex()) {
            val trimmedPara = paragraph.trim()
            if (trimmedPara.isEmpty()) continue
            
            // Check if adding this paragraph would exceed the page size
            val paraWithBreak = if (builder.isNotEmpty()) "\n\n$trimmedPara" else trimmedPara
            
            if (builder.isNotEmpty() && builder.length + paraWithBreak.length > TARGET_CHARS_PER_PAGE) {
                // Current page is full, save it and start a new one
                pages += PageContent(text = builder.toString().trim())
                builder.clear()
                builder.append(trimmedPara)
            } else {
                // Add paragraph to current page
                if (builder.isNotEmpty()) {
                    builder.append("\n\n")
                }
                builder.append(trimmedPara)
            }
        }

        if (builder.isNotBlank()) {
            pages += PageContent(text = builder.toString().trim())
        }

        return pages.ifEmpty { listOf(content) }
    }
    
    /**
     * Split HTML content into multiple pages while preserving HTML structure.
     * Uses a simple approach: split by character count while keeping full HTML elements.
     */
    private fun splitHtmlContent(content: PageContent): List<PageContent> {
        val html = content.html ?: return listOf(content)
        val text = content.text
        
        // If text is short enough, keep as single page
        if (text.length <= TARGET_CHARS_PER_PAGE) {
            return listOf(content)
        }
        
        // Simple approach: split text by target chars and keep the full HTML
        // Each page will render the full HTML but with the subset of text
        // This is not ideal but works as a first implementation
        val pages = mutableListOf<PageContent>()
        var startIndex = 0
        
        while (startIndex < text.length) {
            val endIndex = (startIndex + TARGET_CHARS_PER_PAGE).coerceAtMost(text.length)
            
            // Try to break at a paragraph or sentence boundary
            var breakIndex = endIndex
            if (endIndex < text.length) {
                // Look for paragraph break
                val paraBreak = text.indexOf("\n\n", endIndex - 200.coerceAtLeast(0))
                if (paraBreak in startIndex until endIndex) {
                    breakIndex = paraBreak + 2 // Include the break
                } else {
                    // Look for sentence break
                    val sentenceBreak = text.indexOfAny(charArrayOf('.', '!', '?'), endIndex - 100.coerceAtLeast(0))
                    if (sentenceBreak in startIndex until endIndex) {
                        breakIndex = sentenceBreak + 1
                    }
                }
            }
            
            val pageText = text.substring(startIndex, breakIndex).trim()
            if (pageText.isNotEmpty()) {
                // Keep the HTML but mark the text range for this page
                // The HTML will be rendered but only this text range is relevant
                pages += PageContent(text = pageText, html = html)
            }
            
            startIndex = breakIndex
        }
        
        return pages.ifEmpty { listOf(content) }
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
