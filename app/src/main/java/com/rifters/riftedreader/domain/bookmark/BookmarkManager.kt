package com.rifters.riftedreader.domain.bookmark

import com.rifters.riftedreader.data.database.entities.Bookmark
import com.rifters.riftedreader.data.repository.BookmarkRepository
import com.rifters.riftedreader.domain.parser.BookParser
import com.rifters.riftedreader.domain.parser.PageContent
import com.rifters.riftedreader.util.BookmarkPreviewExtractor
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.abs

/**
 * Manages bookmark creation and restoration.
 * Implements Approach C: Hybrid bookmark system with chapter + page + character offset.
 * 
 * Example usage:
 * ```
 * val manager = BookmarkManager(bookmarkRepository)
 * val bookmark = manager.createBookmark(
 *     bookId = "book-123",
 *     chapterIndex = 10,
 *     inChapterPage = 5,
 *     characterOffset = 2584,
 *     chapterTitle = "Chapter 11: The Discovery",
 *     pageContent = currentPageContent,
 *     percentageThrough = 0.42f,
 *     fontSize = 16.0f
 * )
 * ```
 */
class BookmarkManager(
    private val bookmarkRepository: BookmarkRepository
) {
    
    /**
     * Create a bookmark at the current reading position.
     *
     * @param bookId The ID of the book
     * @param chapterIndex Current chapter index
     * @param inChapterPage Current page within the chapter
     * @param characterOffset Character offset within the chapter
     * @param chapterTitle Title of the current chapter
     * @param pageContent Current page content for preview extraction
     * @param percentageThrough Overall progress through the book (0.0-1.0)
     * @param fontSize Current font size setting
     * @return The created bookmark
     */
    suspend fun createBookmark(
        bookId: String,
        chapterIndex: Int,
        inChapterPage: Int,
        characterOffset: Int,
        chapterTitle: String,
        pageContent: PageContent,
        percentageThrough: Float,
        fontSize: Float
    ): Bookmark {
        // Extract preview text from current position
        val previewText = BookmarkPreviewExtractor.extractPreview(pageContent, characterOffset)
            ?: BookmarkPreviewExtractor.extractPreviewFromStart(pageContent)
            ?: "No preview available"
        
        val bookmark = Bookmark(
            bookId = bookId,
            chapterIndex = chapterIndex,
            inChapterPage = inChapterPage,
            characterOffset = characterOffset,
            chapterTitle = chapterTitle,
            previewText = previewText,
            percentageThrough = percentageThrough,
            createdAt = System.currentTimeMillis(),
            fontSize = fontSize
        )
        
        bookmarkRepository.createBookmark(bookmark)
        return bookmark
    }
    
    /**
     * Restore a bookmark using a two-phase approach:
     * Phase 1: Quick navigation to approximate page
     * Phase 2: Use character offset if font size changed, otherwise use page number
     *
     * @param bookmark The bookmark to restore
     * @param currentFontSize Current font size setting
     * @param navigateToChapter Callback to navigate to a specific chapter
     * @param navigateToInPagePosition Callback to navigate to a specific page within chapter
     * @param scrollToCharacterOffset Callback to scroll to a specific character offset
     */
    suspend fun restoreBookmark(
        bookmark: Bookmark,
        currentFontSize: Float,
        navigateToChapter: suspend (Int) -> Unit,
        navigateToInPagePosition: suspend (Int) -> Unit,
        scrollToCharacterOffset: suspend (Int) -> Unit
    ) {
        // Phase 1: Quick navigation to the chapter
        navigateToChapter(bookmark.chapterIndex)
        
        // Wait for chapter to load (can be adjusted based on performance)
        delay(300)
        
        // Phase 2: Determine if font size changed
        val fontSizeChanged = abs(currentFontSize - bookmark.fontSize) > 0.1f
        
        if (!fontSizeChanged) {
            // Font size same, use page number (fast)
            navigateToInPagePosition(bookmark.inChapterPage)
        } else {
            // Font size changed, use character offset (precise)
            scrollToCharacterOffset(bookmark.characterOffset)
        }
    }
    
    /**
     * Get all bookmarks for a specific book
     */
    suspend fun getBookmarksForBook(bookId: String): List<Bookmark> {
        return bookmarkRepository.getBookmarksForBookSnapshot(bookId)
    }
    
    /**
     * Delete a bookmark
     */
    suspend fun deleteBookmark(bookmark: Bookmark) {
        bookmarkRepository.deleteBookmark(bookmark)
    }
    
    /**
     * Delete a bookmark by ID
     */
    suspend fun deleteBookmarkById(bookmarkId: String) {
        bookmarkRepository.deleteBookmarkById(bookmarkId)
    }
}
