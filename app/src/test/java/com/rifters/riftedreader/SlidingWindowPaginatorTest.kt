package com.rifters.riftedreader

import com.rifters.riftedreader.pagination.SlidingWindowPaginator
import org.junit.Assert.*
import org.junit.Test

class SlidingWindowPaginatorTest {
    
    @Test
    fun `recomputeWindows with 120 chapters returns 24 windows with 5 chapters per window`() {
        val paginator = SlidingWindowPaginator(chaptersPerWindow = 5)
        val windowCount = paginator.recomputeWindows(120)
        
        assertEquals(24, windowCount)
        assertEquals(24, paginator.getWindowCount())
        assertEquals(120, paginator.getTotalChapters())
    }
    
    @Test
    fun `recomputeWindows with partial last window`() {
        val paginator = SlidingWindowPaginator(chaptersPerWindow = 5)
        
        // 123 chapters = 24 full windows (120 chapters) + 1 partial window (3 chapters)
        assertEquals(25, paginator.recomputeWindows(123))
        
        // 62 chapters = 12 full windows (60 chapters) + 1 partial window (2 chapters)
        assertEquals(13, paginator.recomputeWindows(62))
    }
    
    @Test
    fun `getWindowRange returns correct range for first window`() {
        val paginator = SlidingWindowPaginator(chaptersPerWindow = 5)
        paginator.recomputeWindows(120)
        
        val range = paginator.getWindowRange(0)
        assertEquals(0, range.first)
        assertEquals(4, range.last)
    }
    
    @Test
    fun `getWindowRange returns correct range for middle window`() {
        val paginator = SlidingWindowPaginator(chaptersPerWindow = 5)
        paginator.recomputeWindows(120)
        
        val range = paginator.getWindowRange(10)
        assertEquals(50, range.first)
        assertEquals(54, range.last)
    }
    
    @Test
    fun `getWindowRange returns correct range for last full window`() {
        val paginator = SlidingWindowPaginator(chaptersPerWindow = 5)
        paginator.recomputeWindows(120)
        
        val range = paginator.getWindowRange(23)
        assertEquals(115, range.first)
        assertEquals(119, range.last)
    }
    
    @Test
    fun `getWindowRange handles partial last window`() {
        val paginator = SlidingWindowPaginator(chaptersPerWindow = 5)
        paginator.recomputeWindows(62) // 12 full + 1 partial
        
        val range = paginator.getWindowRange(12)
        assertEquals(60, range.first)
        assertEquals(61, range.last) // Only 2 chapters in last window
    }
    
    @Test
    fun `getWindowForChapter returns correct window`() {
        val paginator = SlidingWindowPaginator(chaptersPerWindow = 5)
        paginator.recomputeWindows(120)
        
        // Chapters 0-4 should be in window 0
        assertEquals(0, paginator.getWindowForChapter(0))
        assertEquals(0, paginator.getWindowForChapter(2))
        assertEquals(0, paginator.getWindowForChapter(4))
        
        // Chapters 5-9 should be in window 1
        assertEquals(1, paginator.getWindowForChapter(5))
        assertEquals(1, paginator.getWindowForChapter(7))
        assertEquals(1, paginator.getWindowForChapter(9))
        
        // Last chapter should be in last window
        assertEquals(23, paginator.getWindowForChapter(119))
    }
    
    @Test
    fun `getWindowForChapter handles boundaries correctly`() {
        val paginator = SlidingWindowPaginator(chaptersPerWindow = 5)
        paginator.recomputeWindows(120)
        
        // Boundary between window 0 and 1
        assertEquals(0, paginator.getWindowForChapter(4))
        assertEquals(1, paginator.getWindowForChapter(5))
        
        // Boundary between window 1 and 2
        assertEquals(1, paginator.getWindowForChapter(9))
        assertEquals(2, paginator.getWindowForChapter(10))
    }
    
    @Test
    fun `setChaptersPerWindow updates chapters per window`() {
        val paginator = SlidingWindowPaginator(chaptersPerWindow = 5)
        assertEquals(5, paginator.getChaptersPerWindow())
        
        paginator.setChaptersPerWindow(10)
        assertEquals(10, paginator.getChaptersPerWindow())
        
        // Recompute with new setting
        assertEquals(12, paginator.recomputeWindows(120))
    }
    
    @Test
    fun `debugWindowMap returns formatted string`() {
        val paginator = SlidingWindowPaginator(chaptersPerWindow = 5)
        paginator.recomputeWindows(15) // 3 windows
        
        val debug = paginator.debugWindowMap()
        assertTrue(debug.contains("totalChapters=15"))
        assertTrue(debug.contains("chaptersPerWindow=5"))
        assertTrue(debug.contains("windowCount=3"))
        assertTrue(debug.contains("Window 0: chapters 0-4"))
        assertTrue(debug.contains("Window 1: chapters 5-9"))
        assertTrue(debug.contains("Window 2: chapters 10-14"))
    }
    
    @Test
    fun `debugWindowMap before recompute returns no windows message`() {
        val paginator = SlidingWindowPaginator(chaptersPerWindow = 5)
        val debug = paginator.debugWindowMap()
        assertTrue(debug.contains("No windows computed"))
    }
    
    @Test
    fun `recomputeWindows with zero chapters returns zero windows`() {
        val paginator = SlidingWindowPaginator(chaptersPerWindow = 5)
        assertEquals(0, paginator.recomputeWindows(0))
        assertEquals(0, paginator.getWindowCount())
    }
    
    @Test(expected = IllegalArgumentException::class)
    fun `constructor throws on non-positive chaptersPerWindow`() {
        SlidingWindowPaginator(chaptersPerWindow = 0)
    }
    
    @Test(expected = IllegalArgumentException::class)
    fun `constructor throws on negative chaptersPerWindow`() {
        SlidingWindowPaginator(chaptersPerWindow = -1)
    }
    
    @Test(expected = IllegalArgumentException::class)
    fun `getWindowRange throws on negative windowIndex`() {
        val paginator = SlidingWindowPaginator(chaptersPerWindow = 5)
        paginator.recomputeWindows(120)
        paginator.getWindowRange(-1)
    }
    
    @Test(expected = IllegalArgumentException::class)
    fun `getWindowRange throws on out of bounds windowIndex`() {
        val paginator = SlidingWindowPaginator(chaptersPerWindow = 5)
        paginator.recomputeWindows(120)
        paginator.getWindowRange(24) // 24 windows, so valid indices are 0-23
    }
    
    @Test(expected = IllegalArgumentException::class)
    fun `getWindowRange throws when no chapters computed`() {
        val paginator = SlidingWindowPaginator(chaptersPerWindow = 5)
        paginator.getWindowRange(0)
    }
    
    @Test(expected = IllegalArgumentException::class)
    fun `getWindowForChapter throws on negative chapterIndex`() {
        val paginator = SlidingWindowPaginator(chaptersPerWindow = 5)
        paginator.recomputeWindows(120)
        paginator.getWindowForChapter(-1)
    }
    
    @Test(expected = IllegalArgumentException::class)
    fun `getWindowForChapter throws on out of bounds chapterIndex`() {
        val paginator = SlidingWindowPaginator(chaptersPerWindow = 5)
        paginator.recomputeWindows(120)
        paginator.getWindowForChapter(120) // 120 chapters, so valid indices are 0-119
    }
    
    @Test
    fun `works with different chaptersPerWindow values`() {
        // chaptersPerWindow = 3
        val paginator3 = SlidingWindowPaginator(chaptersPerWindow = 3)
        assertEquals(40, paginator3.recomputeWindows(120))
        assertEquals(0..2, paginator3.getWindowRange(0))
        assertEquals(3..5, paginator3.getWindowRange(1))
        
        // chaptersPerWindow = 10
        val paginator10 = SlidingWindowPaginator(chaptersPerWindow = 10)
        assertEquals(12, paginator10.recomputeWindows(120))
        assertEquals(0..9, paginator10.getWindowRange(0))
        assertEquals(10..19, paginator10.getWindowRange(1))
    }
}
