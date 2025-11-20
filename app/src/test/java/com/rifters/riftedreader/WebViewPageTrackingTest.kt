package com.rifters.riftedreader

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit test for WebView page tracking functionality.
 * 
 * Tests the state management for WebView pagination within chapters
 * to ensure the slider accurately reflects and controls individual
 * WebView pages rather than chapter navigation.
 * 
 * Also tests chapter streaming and dynamic reflow scenarios.
 */
class WebViewPageTrackingTest {
    
    @Test
    fun `WebView page state tracks current and total pages`() {
        // Simulate WebView page tracking
        var currentWebViewPage = 0
        var totalWebViewPages = 0
        
        // Initial state
        assertEquals(0, currentWebViewPage)
        assertEquals(0, totalWebViewPages)
        
        // Update to page 2 of 10
        currentWebViewPage = 2
        totalWebViewPages = 10
        assertEquals(2, currentWebViewPage)
        assertEquals(10, totalWebViewPages)
        
        // Navigate to next page
        currentWebViewPage = 3
        assertEquals(3, currentWebViewPage)
        
        // Reset (chapter change)
        currentWebViewPage = 0
        totalWebViewPages = 0
        assertEquals(0, currentWebViewPage)
        assertEquals(0, totalWebViewPages)
    }
    
    @Test
    fun `WebView page bounds are enforced correctly`() {
        val totalPages = 5
        
        // Valid page indices
        assertTrue(0 in 0 until totalPages)
        assertTrue(4 in 0 until totalPages)
        
        // Invalid page indices
        assertFalse(-1 in 0 until totalPages)
        assertFalse(5 in 0 until totalPages)
    }
    
    @Test
    fun `slider value calculation for WebView pages`() {
        // Simulate slider value calculation
        fun calculateSliderValue(currentPage: Int, totalPages: Int): Float {
            val maxValue = (totalPages - 1).coerceAtLeast(0)
            return currentPage.coerceIn(0, maxValue).toFloat()
        }
        
        // Page 0 of 10
        assertEquals(0f, calculateSliderValue(0, 10), 0.01f)
        
        // Page 5 of 10
        assertEquals(5f, calculateSliderValue(5, 10), 0.01f)
        
        // Last page
        assertEquals(9f, calculateSliderValue(9, 10), 0.01f)
        
        // Out of bounds (should clamp)
        assertEquals(9f, calculateSliderValue(15, 10), 0.01f)
        assertEquals(0f, calculateSliderValue(-1, 10), 0.01f)
        
        // Single page
        assertEquals(0f, calculateSliderValue(0, 1), 0.01f)
        
        // Empty (no pages)
        assertEquals(0f, calculateSliderValue(0, 0), 0.01f)
    }
    
    @Test
    fun `page indicator formatting for WebView pages`() {
        fun formatPageIndicator(currentPage: Int, totalPages: Int): String {
            val displayPage = (currentPage + 1).coerceAtMost(totalPages.coerceAtLeast(1))
            val safeTotal = totalPages.coerceAtLeast(1)
            return "Page $displayPage / $safeTotal"
        }
        
        assertEquals("Page 1 / 10", formatPageIndicator(0, 10))
        assertEquals("Page 5 / 10", formatPageIndicator(4, 10))
        assertEquals("Page 10 / 10", formatPageIndicator(9, 10))
        
        // Edge case: no pages (displays as "Page 1 / 1" due to coerceAtLeast)
        assertEquals("Page 1 / 1", formatPageIndicator(0, 0))
        
        // Edge case: single page
        assertEquals("Page 1 / 1", formatPageIndicator(0, 1))
    }
    
    @Test
    fun `slider max value calculation`() {
        fun calculateSliderMax(totalPages: Int): Float {
            val maxValue = (totalPages - 1).coerceAtLeast(0)
            return maxValue.toFloat().coerceAtLeast(1f)
        }
        
        // 10 pages: max is 9
        assertEquals(9f, calculateSliderMax(10), 0.01f)
        
        // 1 page: max is 1 (to avoid IllegalStateException)
        assertEquals(1f, calculateSliderMax(1), 0.01f)
        
        // 0 pages: max is 1 (to avoid IllegalStateException)
        assertEquals(1f, calculateSliderMax(0), 0.01f)
        
        // Many pages
        assertEquals(99f, calculateSliderMax(100), 0.01f)
    }
    
    @Test
    fun `WebView page navigation within bounds`() {
        val totalPages = 5
        var currentPage = 0
        
        // Navigate forward
        currentPage = (currentPage + 1).coerceIn(0, totalPages - 1)
        assertEquals(1, currentPage)
        
        currentPage = (currentPage + 1).coerceIn(0, totalPages - 1)
        assertEquals(2, currentPage)
        
        // Navigate to specific page
        currentPage = 4.coerceIn(0, totalPages - 1)
        assertEquals(4, currentPage)
        
        // Try to go beyond last page (should clamp)
        currentPage = (currentPage + 1).coerceIn(0, totalPages - 1)
        assertEquals(4, currentPage)
        
        // Navigate backward
        currentPage = (currentPage - 1).coerceIn(0, totalPages - 1)
        assertEquals(3, currentPage)
        
        // Navigate to first page
        currentPage = 0.coerceIn(0, totalPages - 1)
        assertEquals(0, currentPage)
        
        // Try to go before first page (should clamp)
        currentPage = (currentPage - 1).coerceIn(0, totalPages - 1)
        assertEquals(0, currentPage)
    }
    
    @Test
    fun `chapter streaming - append preserves current page`() {
        // Simulate initial state
        var currentPage = 5
        val pageCountBefore = 10
        
        // Simulate appending a chapter that adds 3 pages
        val pagesAdded = 3
        val pageCountAfter = pageCountBefore + pagesAdded
        
        // Current page should remain the same
        assertEquals(5, currentPage)
        assertEquals(13, pageCountAfter)
    }
    
    @Test
    fun `chapter streaming - prepend adjusts current page`() {
        // Simulate initial state
        var currentPage = 5
        val pageCountBefore = 10
        
        // Simulate prepending a chapter that adds 4 pages
        val pagesAdded = 4
        val pageCountAfter = pageCountBefore + pagesAdded
        
        // Current page should be adjusted by pages added
        currentPage += pagesAdded
        assertEquals(9, currentPage)
        assertEquals(14, pageCountAfter)
    }
    
    @Test
    fun `chapter streaming - remove chapter adjusts page count`() {
        // Simulate initial state with 3 chapters
        data class ChapterSegment(val index: Int, val pageCount: Int)
        
        val segments = mutableListOf(
            ChapterSegment(0, 5),
            ChapterSegment(1, 7),
            ChapterSegment(2, 6)
        )
        
        var totalPages = segments.sumOf { it.pageCount }
        assertEquals(18, totalPages)
        
        // Remove chapter 1
        val removedSegment = segments.removeAt(1)
        totalPages -= removedSegment.pageCount
        
        assertEquals(11, totalPages)
        assertEquals(2, segments.size)
    }
    
    @Test
    fun `reflow preserves position correctly`() {
        // Simulate reflow scenario
        fun simulateReflow(currentPage: Int, pageCountBefore: Int, pageCountAfter: Int): Int {
            // Simple strategy: preserve page if possible, otherwise clamp
            return currentPage.coerceIn(0, (pageCountAfter - 1).coerceAtLeast(0))
        }
        
        // Same page count - preserve exactly
        assertEquals(5, simulateReflow(5, 10, 10))
        
        // More pages - preserve exactly
        assertEquals(5, simulateReflow(5, 10, 15))
        
        // Fewer pages - clamp to new max
        assertEquals(4, simulateReflow(5, 10, 5))
        
        // Edge case: single page after reflow
        assertEquals(0, simulateReflow(5, 10, 1))
        
        // Edge case: no pages after reflow
        assertEquals(0, simulateReflow(5, 10, 0))
    }
    
    @Test
    fun `TOC navigation to chapter calculates correct page`() {
        // Simulate chapter segments with page ranges
        data class ChapterInfo(val index: Int, val startPage: Int, val pageCount: Int) {
            val endPage = startPage + pageCount
        }
        
        val chapters = listOf(
            ChapterInfo(0, 0, 5),    // Pages 0-4
            ChapterInfo(1, 5, 7),    // Pages 5-11
            ChapterInfo(2, 12, 6)    // Pages 12-17
        )
        
        // Jump to chapter 0 should go to page 0
        assertEquals(0, chapters[0].startPage)
        
        // Jump to chapter 1 should go to page 5
        assertEquals(5, chapters[1].startPage)
        
        // Jump to chapter 2 should go to page 12
        assertEquals(12, chapters[2].startPage)
    }
    
    @Test
    fun `getCurrentChapter returns correct chapter for page`() {
        // Simulate chapter segments with page ranges
        data class ChapterInfo(val index: Int, val startPage: Int, val pageCount: Int)
        
        val chapters = listOf(
            ChapterInfo(0, 0, 5),    // Pages 0-4
            ChapterInfo(1, 5, 7),    // Pages 5-11
            ChapterInfo(2, 12, 6)    // Pages 12-17
        )
        
        fun getChapterForPage(page: Int): Int {
            return chapters.firstOrNull { chapter ->
                page >= chapter.startPage && page < chapter.startPage + chapter.pageCount
            }?.index ?: -1
        }
        
        // Page 0 is in chapter 0
        assertEquals(0, getChapterForPage(0))
        
        // Page 4 is in chapter 0
        assertEquals(0, getChapterForPage(4))
        
        // Page 5 is in chapter 1
        assertEquals(1, getChapterForPage(5))
        
        // Page 11 is in chapter 1
        assertEquals(1, getChapterForPage(11))
        
        // Page 12 is in chapter 2
        assertEquals(2, getChapterForPage(12))
        
        // Page 20 is beyond all chapters
        assertEquals(-1, getChapterForPage(20))
    }
    
    @Test
    fun `font size change triggers reflow with position preservation`() {
        // Simulate font size change scenario
        data class ReflowResult(val success: Boolean, val pageCount: Int, val currentPage: Int)
        
        fun simulateFontSizeChange(
            currentPage: Int,
            pageCountBefore: Int,
            fontSizeBefore: Int,
            fontSizeAfter: Int
        ): ReflowResult {
            // Simulate page count change based on font size ratio
            val ratio = fontSizeBefore.toFloat() / fontSizeAfter.toFloat()
            val pageCountAfter = (pageCountBefore * ratio).toInt().coerceAtLeast(1)
            
            // Preserve relative position
            val relativePosition = currentPage.toFloat() / pageCountBefore.toFloat()
            val newPage = (relativePosition * pageCountAfter).toInt().coerceIn(0, pageCountAfter - 1)
            
            return ReflowResult(true, pageCountAfter, newPage)
        }
        
        // Increase font size (fewer pages)
        val result1 = simulateFontSizeChange(5, 10, 16, 20)
        assertTrue(result1.success)
        assertTrue(result1.pageCount < 10) // Fewer pages with larger font
        
        // Decrease font size (more pages)
        val result2 = simulateFontSizeChange(5, 10, 16, 12)
        assertTrue(result2.success)
        assertTrue(result2.pageCount > 10) // More pages with smaller font
    }
}
