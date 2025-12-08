package com.rifters.riftedreader.pagination

import com.rifters.riftedreader.domain.pagination.ChapterIndex
import com.rifters.riftedreader.domain.pagination.WindowIndex

/**
 * Data class representing the result of assembling a window.
 * 
 * A window is a contiguous group of chapters combined into a single HTML document
 * for efficient memory management and smooth navigation in the reader.
 * 
 * @property html The complete HTML document containing all chapters in the window
 * @property firstChapter The first chapter index in this window (inclusive)
 * @property lastChapter The last chapter index in this window (inclusive)
 * @property windowIndex The unique index of this window
 * @property sliceMetadata Optional metadata from FlexPaginator pre-slicing (null for column-based pagination)
 */
data class WindowData(
    val html: String,
    val firstChapter: ChapterIndex,
    val lastChapter: ChapterIndex,
    val windowIndex: WindowIndex,
    val sliceMetadata: SliceMetadata? = null
) {
    /**
     * Get the range of chapter indices contained in this window.
     */
    val chapterRange: IntRange
        get() = firstChapter..lastChapter
    
    /**
     * Get the number of chapters in this window.
     */
    val chapterCount: Int
        get() = lastChapter - firstChapter + 1
    
    /**
     * Check if this window contains the given chapter.
     */
    fun containsChapter(chapterIndex: ChapterIndex): Boolean {
        return chapterIndex in firstChapter..lastChapter
    }
    
    /**
     * Check if this window has been pre-sliced by FlexPaginator.
     */
    val isPreSliced: Boolean
        get() = sliceMetadata != null && sliceMetadata.isValid()
}

/**
 * Interface for assembling a window by wrapping N chapters into one HTML document.
 * 
 * This abstraction allows different implementations for assembling windows:
 * - DefaultWindowAssembler: Uses the existing window HTML wrapping code
 * - MockWindowAssembler: For testing purposes
 * 
 * The assembled window contains CSS columns for in-page pagination.
 */
interface WindowAssembler {
    
    /**
     * Assemble a window by combining chapters into a single HTML document.
     * 
     * @param windowIndex The index of the window to assemble
     * @param firstChapter The first chapter index to include (inclusive)
     * @param lastChapter The last chapter index to include (inclusive)
     * @return WindowData containing the assembled HTML and metadata, or null if assembly fails
     */
    suspend fun assembleWindow(
        windowIndex: WindowIndex,
        firstChapter: ChapterIndex,
        lastChapter: ChapterIndex
    ): WindowData?
    
    /**
     * Check if a window can be assembled with the given parameters.
     * 
     * This allows validation before attempting the potentially expensive assembly operation.
     * 
     * @param windowIndex The window index to validate
     * @param firstChapter The first chapter index
     * @param lastChapter The last chapter index
     * @return true if the window can be assembled, false otherwise
     */
    fun canAssemble(
        windowIndex: WindowIndex,
        firstChapter: ChapterIndex,
        lastChapter: ChapterIndex
    ): Boolean
    
    /**
     * Get the total number of chapters available for assembly.
     * 
     * @return The total chapter count
     */
    fun getTotalChapters(): Int
}
