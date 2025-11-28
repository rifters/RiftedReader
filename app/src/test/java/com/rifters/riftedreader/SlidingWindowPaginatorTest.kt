package com.rifters.riftedreader

import com.rifters.riftedreader.domain.pagination.SlidingWindowPaginator
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for SlidingWindowPaginator
 */
class SlidingWindowPaginatorTest {
    
    private lateinit var paginator: SlidingWindowPaginator
    
    @Before
    fun setup() {
        paginator = SlidingWindowPaginator(chaptersPerWindow = 5)
    }
    
    @Test
    fun `recomputeWindows calculates correct window count`() {
        // 10 chapters with 5 per window = 2 windows
        val windowCount = paginator.recomputeWindows(10)
        assertEquals(2, windowCount)
        assertEquals(2, paginator.windowCount)
    }
    
    @Test
    fun `recomputeWindows rounds up for partial windows`() {
        // 12 chapters with 5 per window = 3 windows (ceil(12/5) = 3)
        val windowCount = paginator.recomputeWindows(12)
        assertEquals(3, windowCount)
    }
    
    @Test
    fun `recomputeWindows handles single chapter`() {
        val windowCount = paginator.recomputeWindows(1)
        assertEquals(1, windowCount)
    }
    
    @Test
    fun `recomputeWindows handles zero chapters`() {
        val windowCount = paginator.recomputeWindows(0)
        assertEquals(0, windowCount)
    }
    
    @Test
    fun `getWindowRange returns correct range for first window`() {
        paginator.recomputeWindows(10)
        
        val range = paginator.getWindowRange(0)
        
        assertNotNull(range)
        assertEquals(0, range!!.first)
        assertEquals(4, range.second)
    }
    
    @Test
    fun `getWindowRange returns correct range for second window`() {
        paginator.recomputeWindows(10)
        
        val range = paginator.getWindowRange(1)
        
        assertNotNull(range)
        assertEquals(5, range!!.first)
        assertEquals(9, range.second)
    }
    
    @Test
    fun `getWindowRange handles partial last window`() {
        paginator.recomputeWindows(12)
        
        val range = paginator.getWindowRange(2)
        
        assertNotNull(range)
        assertEquals(10, range!!.first)
        assertEquals(11, range.second) // Only 2 chapters in last window
    }
    
    @Test
    fun `getWindowRange returns null for invalid index`() {
        paginator.recomputeWindows(10)
        
        assertNull(paginator.getWindowRange(-1))
        assertNull(paginator.getWindowRange(5))
    }
    
    @Test
    fun `getWindowForChapter returns correct window index`() {
        paginator.recomputeWindows(20)
        
        assertEquals(0, paginator.getWindowForChapter(0))
        assertEquals(0, paginator.getWindowForChapter(4))
        assertEquals(1, paginator.getWindowForChapter(5))
        assertEquals(1, paginator.getWindowForChapter(9))
        assertEquals(2, paginator.getWindowForChapter(10))
        assertEquals(3, paginator.getWindowForChapter(19))
    }
    
    @Test
    fun `setChaptersPerWindow updates window size`() {
        paginator.setChaptersPerWindow(3)
        paginator.recomputeWindows(10)
        
        assertEquals(4, paginator.windowCount) // ceil(10/3) = 4
        assertEquals(0, paginator.getWindowForChapter(0))
        assertEquals(1, paginator.getWindowForChapter(3))
    }
    
    @Test
    fun `debugWindowMap returns readable string`() {
        paginator.recomputeWindows(12)
        
        val debug = paginator.debugWindowMap()
        
        assertTrue(debug.contains("totalChapters=12"))
        assertTrue(debug.contains("chaptersPerWindow=5"))
        assertTrue(debug.contains("windowCount=3"))
        assertTrue(debug.contains("W0="))
        assertTrue(debug.contains("W1="))
        assertTrue(debug.contains("W2="))
    }
    
    @Test
    fun `assertWindowCountValid returns true for valid state`() {
        paginator.recomputeWindows(10)
        assertTrue(paginator.assertWindowCountValid())
    }
    
    @Test
    fun `getChaptersPerWindow returns current setting`() {
        assertEquals(5, paginator.getChaptersPerWindow())
        
        paginator.setChaptersPerWindow(3)
        assertEquals(3, paginator.getChaptersPerWindow())
    }
    
    @Test(expected = IllegalArgumentException::class)
    fun `constructor throws on invalid window size`() {
        SlidingWindowPaginator(chaptersPerWindow = 0)
    }
    
    @Test(expected = IllegalArgumentException::class)
    fun `setChaptersPerWindow throws on invalid size`() {
        paginator.setChaptersPerWindow(0)
    }
    
    @Test(expected = IllegalArgumentException::class)
    fun `recomputeWindows throws on negative chapters`() {
        paginator.recomputeWindows(-1)
    }
    
    @Test(expected = IllegalArgumentException::class)
    fun `getWindowForChapter throws on negative index`() {
        paginator.getWindowForChapter(-1)
    }
}
