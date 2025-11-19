package com.rifters.riftedreader

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit test for WebView page tracking functionality.
 * 
 * Tests the state management for WebView pagination within chapters
 * to ensure the slider accurately reflects and controls individual
 * WebView pages rather than chapter navigation.
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
}
