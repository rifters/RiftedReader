package com.rifters.riftedreader.domain.bookmark

import com.rifters.riftedreader.data.database.entities.Bookmark
import com.rifters.riftedreader.data.repository.BookmarkRepository
import com.rifters.riftedreader.domain.parser.PageContent
import com.rifters.riftedreader.util.BookmarkPreviewExtractor
import kotlinx.coroutines.delay

class BookmarkManager(
    private val bookmarkRepository: BookmarkRepository
) {
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
        val previewText = BookmarkPreviewExtractor.extractPreview(pageContent, characterOffset)
            ?: BookmarkPreviewExtractor.extractPreviewFromStart(pageContent)
            ?: chapterTitle

        val bookmark = Bookmark(
            bookId = bookId,
            chapterIndex = chapterIndex,
            charOffset = characterOffset,
            pageIndexHint = inChapterPage,
            nearestAnchorId = "",
            nearestAnchorText = previewText,
            savedAt = System.currentTimeMillis(),
            label = null
        )

        bookmarkRepository.saveNamedBookmark(bookmark)
        return bookmark
    }

    suspend fun restoreBookmark(
        bookmark: Bookmark,
        currentFontSize: Float,
        navigateToChapter: suspend (Int) -> Unit,
        navigateToInPagePosition: suspend (Int) -> Unit,
        scrollToCharacterOffset: suspend (Int) -> Unit
    ) {
        navigateToChapter(bookmark.chapterIndex)
        delay(300)
        if (bookmark.charOffset > 0) {
            scrollToCharacterOffset(bookmark.charOffset)
        } else {
            navigateToInPagePosition(bookmark.pageIndexHint)
        }
    }

    suspend fun getBookmarksForBook(bookId: String): List<Bookmark> {
        return bookmarkRepository.loadNamedBookmarks(bookId)
    }

    suspend fun deleteBookmark(bookmark: Bookmark) {
        bookmarkRepository.delete(bookmark)
    }
}
