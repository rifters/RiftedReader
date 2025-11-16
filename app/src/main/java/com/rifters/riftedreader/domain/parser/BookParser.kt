package com.rifters.riftedreader.domain.parser

import com.rifters.riftedreader.data.database.entities.BookMeta
import java.io.File

/**
 * Interface for parsing different book formats
 */
interface BookParser {

    /**
     * Check if this parser can handle the given file
     */
    fun canParse(file: File): Boolean

    /**
     * Extract metadata from the book file
     */
    suspend fun extractMetadata(file: File): BookMeta

    /**
     * Get the content of a specific page/chapter
     */
    suspend fun getPageContent(file: File, page: Int): PageContent

    /**
     * Get the total number of pages in the book
     */
    suspend fun getPageCount(file: File): Int

    /**
     * Get the table of contents if available
     */
    suspend fun getTableOfContents(file: File): List<TocEntry>
}

/**
 * Table of contents entry
 */
@kotlinx.parcelize.Parcelize
data class TocEntry(
    val title: String,
    val pageNumber: Int,
    val level: Int = 0
) : android.os.Parcelable

/**
 * Represents both the plain text and optional formatted HTML for a page.
 */
data class PageContent(
    val text: String,
    val html: String? = null
) {
    companion object {
        val EMPTY = PageContent(text = "")
    }
}
