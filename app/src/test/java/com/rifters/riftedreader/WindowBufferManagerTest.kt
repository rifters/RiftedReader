package com.rifters.riftedreader

import com.rifters.riftedreader.pagination.SlidingWindowPaginator
import com.rifters.riftedreader.pagination.WindowAssembler
import com.rifters.riftedreader.pagination.WindowBufferManager
import com.rifters.riftedreader.pagination.WindowData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for WindowBufferManager.
 * 
 * Tests the two-phase, five-window buffer lifecycle:
 * - Phase 1 (STARTUP): Build buffer of 5 consecutive windows, user starts at first window
 * - Phase 2 (STEADY): Keep active window centered after reaching buffer[CENTER_POS]
 */
class WindowBufferManagerTest {
    
    private lateinit var paginator: SlidingWindowPaginator
    private lateinit var assembler: MockWindowAssembler
    private lateinit var bufferManager: WindowBufferManager
    
    @Before
    fun setup() {
        paginator = SlidingWindowPaginator(chaptersPerWindow = 5)
        assembler = MockWindowAssembler(totalChapters = 50) // 50 chapters = 10 windows
        
        // Initialize paginator with 50 chapters -> 10 windows
        paginator.recomputeWindows(50)
        
        bufferManager = WindowBufferManager(
            windowAssembler = assembler,
            paginator = paginator,
            coroutineScope = CoroutineScope(Dispatchers.Default)
        )
    }
    
    // ========================================================================
    // Initialization Tests
    // ========================================================================
    
    @Test
    fun `initialize creates buffer with 5 consecutive windows starting from startWindow`() = runBlocking {
        bufferManager.initialize(startWindow = 2)
        
        // Allow time for async preloading
        delay(100)
        
        val buffered = bufferManager.getBufferedWindows()
        assertEquals(5, buffered.size)
        assertEquals(listOf(2, 3, 4, 5, 6), buffered)
    }
    
    @Test
    fun `initialize sets active window to first in buffer`() = runBlocking {
        bufferManager.initialize(startWindow = 3)
        
        assertEquals(3, bufferManager.getActiveWindowIndex())
    }
    
    @Test
    fun `initialize starts in STARTUP phase`() = runBlocking {
        bufferManager.initialize(startWindow = 0)
        
        assertEquals(WindowBufferManager.Phase.STARTUP, bufferManager.phase.value)
    }
    
    @Test
    fun `initialize clamps start window to valid bounds at beginning`() = runBlocking {
        bufferManager.initialize(startWindow = 0)
        
        val buffered = bufferManager.getBufferedWindows()
        assertEquals(listOf(0, 1, 2, 3, 4), buffered)
    }
    
    @Test
    fun `initialize clamps start window to valid bounds at end`() = runBlocking {
        bufferManager.initialize(startWindow = 8)
        
        // Should clamp so we have 5 windows ending at window 9
        val buffered = bufferManager.getBufferedWindows()
        assertEquals(5, buffered.size)
        assertEquals(9, buffered.last())
        assertEquals(5, buffered.first())
    }
    
    @Test
    fun `initialize with negative start window clamps to 0`() = runBlocking {
        bufferManager.initialize(startWindow = -5)
        
        val buffered = bufferManager.getBufferedWindows()
        assertEquals(0, buffered.first())
    }
    
    @Test
    fun `initialize with start window beyond total clamps correctly`() = runBlocking {
        bufferManager.initialize(startWindow = 100)
        
        // 10 total windows (0-9), should clamp to valid range
        val buffered = bufferManager.getBufferedWindows()
        assertTrue(buffered.all { it in 0..9 })
        assertEquals(5, buffered.size)
    }
    
    // ========================================================================
    // Phase Transition Tests
    // ========================================================================
    
    @Test
    fun `onEnteredWindow does not transition to STEADY before reaching CENTER_POS`() = runBlocking {
        bufferManager.initialize(startWindow = 0)
        
        // Enter windows 0 and 1 (not yet at CENTER_POS=2)
        bufferManager.onEnteredWindow(0)
        assertEquals(WindowBufferManager.Phase.STARTUP, bufferManager.phase.value)
        
        bufferManager.onEnteredWindow(1)
        assertEquals(WindowBufferManager.Phase.STARTUP, bufferManager.phase.value)
    }
    
    @Test
    fun `onEnteredWindow transitions to STEADY when reaching buffer CENTER_POS`() = runBlocking {
        bufferManager.initialize(startWindow = 0)
        
        // Buffer is [0,1,2,3,4], CENTER_POS=2 -> window index 2
        bufferManager.onEnteredWindow(2)
        
        assertEquals(WindowBufferManager.Phase.STEADY, bufferManager.phase.value)
    }
    
    @Test
    fun `phase transition is one-time only`() = runBlocking {
        bufferManager.initialize(startWindow = 0)
        
        // Transition to STEADY
        bufferManager.onEnteredWindow(2)
        assertEquals(WindowBufferManager.Phase.STEADY, bufferManager.phase.value)
        
        // Re-entering center should not change anything
        bufferManager.onEnteredWindow(0)
        assertEquals(WindowBufferManager.Phase.STEADY, bufferManager.phase.value)
    }
    
    // ========================================================================
    // Forward Shift Tests
    // ========================================================================
    
    @Test
    fun `shiftForward drops leftmost and appends new rightmost`() = runBlocking {
        bufferManager.initialize(startWindow = 0)
        
        // Buffer: [0,1,2,3,4]
        val shifted = bufferManager.shiftForward()
        
        assertTrue(shifted)
        // Buffer should now be: [1,2,3,4,5]
        val buffered = bufferManager.getBufferedWindows()
        assertEquals(listOf(1, 2, 3, 4, 5), buffered)
    }
    
    @Test
    fun `shiftForward returns false at end boundary`() = runBlocking {
        bufferManager.initialize(startWindow = 5) // Buffer: [5,6,7,8,9]
        
        val shifted = bufferManager.shiftForward()
        
        assertFalse(shifted)
        // Buffer should remain unchanged
        val buffered = bufferManager.getBufferedWindows()
        assertEquals(listOf(5, 6, 7, 8, 9), buffered)
    }
    
    @Test
    fun `shiftForward removes dropped window from cache`() = runBlocking {
        bufferManager.initialize(startWindow = 0)
        delay(100) // Wait for preload
        
        // Window 0 should be cached
        val cachedBefore = bufferManager.getCachedWindow(0)
        
        bufferManager.shiftForward()
        delay(100) // Wait for any async operations
        
        // Window 0 should no longer be cached
        val cachedAfter = bufferManager.getCachedWindow(0)
        assertNull(cachedAfter)
    }
    
    @Test
    fun `multiple shiftForward calls progress buffer correctly`() = runBlocking {
        bufferManager.initialize(startWindow = 0)
        
        // Shift forward 3 times
        assertTrue(bufferManager.shiftForward())
        assertTrue(bufferManager.shiftForward())
        assertTrue(bufferManager.shiftForward())
        
        // Buffer should now be: [3,4,5,6,7]
        val buffered = bufferManager.getBufferedWindows()
        assertEquals(listOf(3, 4, 5, 6, 7), buffered)
    }
    
    // ========================================================================
    // Backward Shift Tests
    // ========================================================================
    
    @Test
    fun `shiftBackward drops rightmost and prepends new leftmost`() = runBlocking {
        bufferManager.initialize(startWindow = 5) // Buffer: [5,6,7,8,9]
        
        val shifted = bufferManager.shiftBackward()
        
        assertTrue(shifted)
        // Buffer should now be: [4,5,6,7,8]
        val buffered = bufferManager.getBufferedWindows()
        assertEquals(listOf(4, 5, 6, 7, 8), buffered)
    }
    
    @Test
    fun `shiftBackward returns false at start boundary`() = runBlocking {
        bufferManager.initialize(startWindow = 0) // Buffer: [0,1,2,3,4]
        
        val shifted = bufferManager.shiftBackward()
        
        assertFalse(shifted)
        // Buffer should remain unchanged
        val buffered = bufferManager.getBufferedWindows()
        assertEquals(listOf(0, 1, 2, 3, 4), buffered)
    }
    
    @Test
    fun `shiftBackward removes dropped window from cache`() = runBlocking {
        bufferManager.initialize(startWindow = 5)
        delay(100) // Wait for preload
        
        // Window 9 should be cached
        val cachedBefore = bufferManager.getCachedWindow(9)
        
        bufferManager.shiftBackward()
        delay(100) // Wait for any async operations
        
        // Window 9 should no longer be cached
        val cachedAfter = bufferManager.getCachedWindow(9)
        assertNull(cachedAfter)
    }
    
    @Test
    fun `multiple shiftBackward calls progress buffer correctly`() = runBlocking {
        bufferManager.initialize(startWindow = 5)
        
        // Shift backward 3 times
        assertTrue(bufferManager.shiftBackward())
        assertTrue(bufferManager.shiftBackward())
        assertTrue(bufferManager.shiftBackward())
        
        // Buffer should now be: [2,3,4,5,6]
        val buffered = bufferManager.getBufferedWindows()
        assertEquals(listOf(2, 3, 4, 5, 6), buffered)
    }
    
    // ========================================================================
    // Window Buffer Queries
    // ========================================================================
    
    @Test
    fun `isWindowInBuffer returns true for buffered windows`() = runBlocking {
        bufferManager.initialize(startWindow = 2)
        
        assertTrue(bufferManager.isWindowInBuffer(2))
        assertTrue(bufferManager.isWindowInBuffer(4))
        assertTrue(bufferManager.isWindowInBuffer(6))
    }
    
    @Test
    fun `isWindowInBuffer returns false for non-buffered windows`() = runBlocking {
        bufferManager.initialize(startWindow = 2)
        
        assertFalse(bufferManager.isWindowInBuffer(0))
        assertFalse(bufferManager.isWindowInBuffer(1))
        assertFalse(bufferManager.isWindowInBuffer(7))
    }
    
    @Test
    fun `getCenterWindowIndex returns correct window`() = runBlocking {
        bufferManager.initialize(startWindow = 0)
        
        // Buffer: [0,1,2,3,4], CENTER_POS=2 -> window 2
        assertEquals(2, bufferManager.getCenterWindowIndex())
    }
    
    // ========================================================================
    // Cache Tests
    // ========================================================================
    
    @Test
    fun `getCachedWindow returns cached data`() = runBlocking {
        bufferManager.initialize(startWindow = 0)
        delay(200) // Wait for async preloading
        
        val cached = bufferManager.getCachedWindow(0)
        assertNotNull(cached)
        assertEquals(0, cached!!.windowIndex)
    }
    
    @Test
    fun `getCachedWindow returns null for non-cached window`() = runBlocking {
        bufferManager.initialize(startWindow = 0)
        delay(100)
        
        // Window 8 is not in buffer [0,1,2,3,4]
        val cached = bufferManager.getCachedWindow(8)
        assertNull(cached)
    }
    
    // ========================================================================
    // Clear/Reset Tests
    // ========================================================================
    
    @Test
    fun `clear resets buffer and cache`() = runBlocking {
        bufferManager.initialize(startWindow = 3)
        delay(100)
        
        bufferManager.clear()
        
        assertTrue(bufferManager.getBufferedWindows().isEmpty())
        assertEquals(0, bufferManager.getCacheSize())
        assertEquals(WindowBufferManager.Phase.STARTUP, bufferManager.phase.value)
    }
    
    // ========================================================================
    // Edge Cases
    // ========================================================================
    
    @Test
    fun `handles small book with fewer than 5 windows`() = runBlocking {
        // Create a paginator for a book with only 12 chapters = 3 windows
        val smallPaginator = SlidingWindowPaginator(chaptersPerWindow = 5)
        smallPaginator.recomputeWindows(12)
        
        val smallAssembler = MockWindowAssembler(totalChapters = 12)
        val smallBufferManager = WindowBufferManager(
            windowAssembler = smallAssembler,
            paginator = smallPaginator,
            coroutineScope = CoroutineScope(Dispatchers.Default)
        )
        
        smallBufferManager.initialize(startWindow = 0)
        
        // Should have only 3 windows in buffer
        val buffered = smallBufferManager.getBufferedWindows()
        assertEquals(3, buffered.size)
        assertEquals(listOf(0, 1, 2), buffered)
    }
    
    @Test
    fun `handles single window book`() = runBlocking {
        // Create a paginator for a book with only 3 chapters = 1 window
        val singlePaginator = SlidingWindowPaginator(chaptersPerWindow = 5)
        singlePaginator.recomputeWindows(3)
        
        val singleAssembler = MockWindowAssembler(totalChapters = 3)
        val singleBufferManager = WindowBufferManager(
            windowAssembler = singleAssembler,
            paginator = singlePaginator,
            coroutineScope = CoroutineScope(Dispatchers.Default)
        )
        
        singleBufferManager.initialize(startWindow = 0)
        
        // Should have only 1 window in buffer
        val buffered = singleBufferManager.getBufferedWindows()
        assertEquals(1, buffered.size)
        assertEquals(listOf(0), buffered)
        
        // Cannot shift in either direction
        assertFalse(singleBufferManager.shiftForward())
        assertFalse(singleBufferManager.shiftBackward())
    }
    
    // ========================================================================
    // Mock WindowAssembler for testing
    // ========================================================================
    
    private class MockWindowAssembler(
        private val totalChapters: Int
    ) : WindowAssembler {
        
        override suspend fun assembleWindow(
            windowIndex: Int,
            firstChapter: Int,
            lastChapter: Int
        ): WindowData? {
            if (firstChapter < 0 || lastChapter >= totalChapters) {
                return null
            }
            
            return WindowData(
                html = "<html>Window $windowIndex: chapters $firstChapter-$lastChapter</html>",
                firstChapter = firstChapter,
                lastChapter = lastChapter,
                windowIndex = windowIndex
            )
        }
        
        override fun canAssemble(
            windowIndex: Int,
            firstChapter: Int,
            lastChapter: Int
        ): Boolean {
            return windowIndex >= 0 && 
                   firstChapter >= 0 && 
                   lastChapter >= firstChapter && 
                   lastChapter < totalChapters
        }
        
        override fun getTotalChapters(): Int = totalChapters
    }
}
