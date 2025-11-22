package com.rifters.riftedreader

import com.rifters.riftedreader.domain.pagination.SlidingWindowManager
import org.junit.Assert.assertEquals
import org.junit.Test

class SlidingWindowManagerTest {
    
    @Test
    fun `windowForChapter returns correct window for middle chapters`() {
        val manager = SlidingWindowManager(windowSize = 5)
        
        // Window 0: chapters 0-4
        assertEquals(0, manager.windowForChapter(0))
        assertEquals(0, manager.windowForChapter(2))
        assertEquals(0, manager.windowForChapter(4))
        
        // Window 1: chapters 5-9
        assertEquals(1, manager.windowForChapter(5))
        assertEquals(1, manager.windowForChapter(7))
        assertEquals(1, manager.windowForChapter(9))
        
        // Window 2: chapters 10-14
        assertEquals(2, manager.windowForChapter(10))
        assertEquals(2, manager.windowForChapter(12))
    }
    
    @Test
    fun `windowForChapter handles boundary between windows`() {
        val manager = SlidingWindowManager(windowSize = 5)
        
        // Boundary: chapter 4 -> 5
        assertEquals(0, manager.windowForChapter(4))
        assertEquals(1, manager.windowForChapter(5))
        
        // Boundary: chapter 9 -> 10
        assertEquals(1, manager.windowForChapter(9))
        assertEquals(2, manager.windowForChapter(10))
    }
    
    @Test
    fun `firstChapterInWindow returns correct first chapter`() {
        val manager = SlidingWindowManager(windowSize = 5)
        
        assertEquals(0, manager.firstChapterInWindow(0))
        assertEquals(5, manager.firstChapterInWindow(1))
        assertEquals(10, manager.firstChapterInWindow(2))
        assertEquals(15, manager.firstChapterInWindow(3))
    }
    
    @Test
    fun `lastChapterInWindow returns correct last chapter for full windows`() {
        val manager = SlidingWindowManager(windowSize = 5)
        val totalChapters = 62 // From debug logs
        
        // Window 0: chapters 0-4
        assertEquals(4, manager.lastChapterInWindow(0, totalChapters))
        
        // Window 1: chapters 5-9
        assertEquals(9, manager.lastChapterInWindow(1, totalChapters))
        
        // Window 2: chapters 10-14
        assertEquals(14, manager.lastChapterInWindow(2, totalChapters))
    }
    
    @Test
    fun `lastChapterInWindow handles incomplete final window`() {
        val manager = SlidingWindowManager(windowSize = 5)
        val totalChapters = 62 // 62 chapters = 12 full windows + 1 partial (chapters 60-61)
        
        // Last window (window 12): should only have chapters 60-61
        val lastWindow = manager.windowForChapter(totalChapters - 1)
        assertEquals(12, lastWindow)
        assertEquals(61, manager.lastChapterInWindow(lastWindow, totalChapters))
        
        // Verify it respects total chapter count
        val firstInLastWindow = manager.firstChapterInWindow(lastWindow)
        assertEquals(60, firstInLastWindow)
    }
    
    @Test
    fun `chaptersInWindow returns all chapters in window`() {
        val manager = SlidingWindowManager(windowSize = 5)
        val totalChapters = 62
        
        // Window 0: chapters 0-4
        assertEquals(listOf(0, 1, 2, 3, 4), manager.chaptersInWindow(0, totalChapters))
        
        // Window 1: chapters 5-9
        assertEquals(listOf(5, 6, 7, 8, 9), manager.chaptersInWindow(1, totalChapters))
        
        // Last window (partial): chapters 60-61
        val lastWindow = manager.windowForChapter(totalChapters - 1)
        assertEquals(listOf(60, 61), manager.chaptersInWindow(lastWindow, totalChapters))
    }
    
    @Test
    fun `totalWindows calculates correct number of windows`() {
        val manager = SlidingWindowManager(windowSize = 5)
        
        // 62 chapters with window size 5 = 13 windows (12 full + 1 partial)
        assertEquals(13, manager.totalWindows(62))
        
        // Exactly divisible case
        assertEquals(4, manager.totalWindows(20))
        
        // Single window
        assertEquals(1, manager.totalWindows(3))
        assertEquals(1, manager.totalWindows(5))
        
        // Edge case: 1 chapter
        assertEquals(1, manager.totalWindows(1))
    }
    
    @Test
    fun `works with different window sizes`() {
        // Window size of 3
        val manager3 = SlidingWindowManager(windowSize = 3)
        assertEquals(0, manager3.windowForChapter(0))
        assertEquals(0, manager3.windowForChapter(2))
        assertEquals(1, manager3.windowForChapter(3))
        assertEquals(1, manager3.windowForChapter(5))
        assertEquals(2, manager3.windowForChapter(6))
        
        // Window size of 10
        val manager10 = SlidingWindowManager(windowSize = 10)
        assertEquals(0, manager10.windowForChapter(0))
        assertEquals(0, manager10.windowForChapter(9))
        assertEquals(1, manager10.windowForChapter(10))
        assertEquals(1, manager10.windowForChapter(19))
    }
    
    @Test(expected = IllegalArgumentException::class)
    fun `windowForChapter throws on negative chapter`() {
        val manager = SlidingWindowManager(windowSize = 5)
        manager.windowForChapter(-1)
    }
    
    @Test(expected = IllegalArgumentException::class)
    fun `firstChapterInWindow throws on negative window`() {
        val manager = SlidingWindowManager(windowSize = 5)
        manager.firstChapterInWindow(-1)
    }
    
    @Test(expected = IllegalArgumentException::class)
    fun `lastChapterInWindow throws on negative window`() {
        val manager = SlidingWindowManager(windowSize = 5)
        manager.lastChapterInWindow(-1, 62)
    }
    
    @Test(expected = IllegalArgumentException::class)
    fun `constructor throws on non-positive window size`() {
        SlidingWindowManager(windowSize = 0)
    }
    
    @Test(expected = IllegalArgumentException::class)
    fun `constructor throws on negative window size`() {
        SlidingWindowManager(windowSize = -1)
    }
}
