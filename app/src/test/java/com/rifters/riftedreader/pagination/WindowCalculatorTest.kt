package com.rifters.riftedreader.pagination

import com.rifters.riftedreader.domain.pagination.WindowCalculator
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for WindowCalculator.
 *
 * Tests cover:
 * - Basic window count calculation
 * - Edge cases: empty list, exact multiples, remainder windows
 * - Window range computation
 * - Chapter-to-window mapping
 * - Validation logic
 */
class WindowCalculatorTest {

    @Test
    fun `calculateWindowCount returns 0 for empty chapter list`() {
        assertEquals(0, WindowCalculator.calculateWindowCount(0, 5))
        assertEquals(0, WindowCalculator.calculateWindowCount(0, 1))
        assertEquals(0, WindowCalculator.calculateWindowCount(0, 100))
    }

    @Test
    fun `calculateWindowCount returns 1 for single chapter`() {
        assertEquals(1, WindowCalculator.calculateWindowCount(1, 5))
        assertEquals(1, WindowCalculator.calculateWindowCount(1, 1))
    }

    @Test
    fun `calculateWindowCount with exact multiple returns no remainder`() {
        // 10 chapters / 5 per window = 2 windows
        assertEquals(2, WindowCalculator.calculateWindowCount(10, 5))
        // 15 chapters / 5 per window = 3 windows
        assertEquals(3, WindowCalculator.calculateWindowCount(15, 5))
        // 100 chapters / 10 per window = 10 windows
        assertEquals(10, WindowCalculator.calculateWindowCount(100, 10))
    }

    @Test
    fun `calculateWindowCount with remainder creates extra window`() {
        // 11 chapters / 5 per window = 3 windows (5 + 5 + 1)
        assertEquals(3, WindowCalculator.calculateWindowCount(11, 5))
        // 22 chapters / 5 per window = 5 windows (5 + 5 + 5 + 5 + 2)
        assertEquals(5, WindowCalculator.calculateWindowCount(22, 5))
        // 101 chapters / 5 per window = 21 windows
        assertEquals(21, WindowCalculator.calculateWindowCount(101, 5))
        // 109 chapters / 5 per window = 22 windows
        assertEquals(22, WindowCalculator.calculateWindowCount(109, 5))
    }

    @Test
    fun `calculateWindowCount with 1 chapter per window equals chapter count`() {
        assertEquals(10, WindowCalculator.calculateWindowCount(10, 1))
        assertEquals(100, WindowCalculator.calculateWindowCount(100, 1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `calculateWindowCount throws for negative chapter count`() {
        WindowCalculator.calculateWindowCount(-1, 5)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `calculateWindowCount throws for zero chapters per window`() {
        WindowCalculator.calculateWindowCount(10, 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `calculateWindowCount throws for negative chapters per window`() {
        WindowCalculator.calculateWindowCount(10, -5)
    }

    @Test
    fun `getWindowForChapter returns correct window index`() {
        // With 5 chapters per window:
        // Chapters 0-4 -> Window 0
        // Chapters 5-9 -> Window 1
        // etc.
        assertEquals(0, WindowCalculator.getWindowForChapter(0, 5))
        assertEquals(0, WindowCalculator.getWindowForChapter(4, 5))
        assertEquals(1, WindowCalculator.getWindowForChapter(5, 5))
        assertEquals(1, WindowCalculator.getWindowForChapter(9, 5))
        assertEquals(2, WindowCalculator.getWindowForChapter(10, 5))
        assertEquals(4, WindowCalculator.getWindowForChapter(21, 5))
    }

    @Test
    fun `getWindowForChapter with 1 chapter per window`() {
        // Each chapter is its own window
        assertEquals(0, WindowCalculator.getWindowForChapter(0, 1))
        assertEquals(5, WindowCalculator.getWindowForChapter(5, 1))
        assertEquals(99, WindowCalculator.getWindowForChapter(99, 1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `getWindowForChapter throws for negative chapter index`() {
        WindowCalculator.getWindowForChapter(-1, 5)
    }

    @Test
    fun `getWindowRange returns correct range for first window`() {
        val range = WindowCalculator.getWindowRange(0, 22, 5)
        assertNotNull(range)
        assertEquals(0 to 4, range)
    }

    @Test
    fun `getWindowRange returns correct range for middle window`() {
        val range = WindowCalculator.getWindowRange(2, 22, 5)
        assertNotNull(range)
        assertEquals(10 to 14, range)
    }

    @Test
    fun `getWindowRange returns correct range for last window with remainder`() {
        // 22 chapters with 5 per window:
        // Window 4 contains chapters 20-21 (2 chapters)
        val range = WindowCalculator.getWindowRange(4, 22, 5)
        assertNotNull(range)
        assertEquals(20 to 21, range)
    }

    @Test
    fun `getWindowRange returns null for invalid window index`() {
        assertNull(WindowCalculator.getWindowRange(-1, 22, 5))
        assertNull(WindowCalculator.getWindowRange(5, 22, 5)) // Only windows 0-4 exist
        assertNull(WindowCalculator.getWindowRange(100, 22, 5))
    }

    @Test
    fun `getWindowRange returns null for empty book`() {
        assertNull(WindowCalculator.getWindowRange(0, 0, 5))
    }

    @Test
    fun `validateWindowCount returns true for correct value`() {
        assertTrue(WindowCalculator.validateWindowCount(5, 22, 5))
        assertTrue(WindowCalculator.validateWindowCount(1, 1, 5))
        assertTrue(WindowCalculator.validateWindowCount(0, 0, 5))
    }

    @Test
    fun `validateWindowCount returns false for incorrect value`() {
        // 22 chapters / 5 per window = 5 windows, not 4 or 6
        assertFalse(WindowCalculator.validateWindowCount(4, 22, 5))
        assertFalse(WindowCalculator.validateWindowCount(6, 22, 5))
    }

    @Test
    fun `debugWindowMap returns correct format for standard case`() {
        val debug = WindowCalculator.debugWindowMap(22, 5)
        assertTrue(debug.contains("totalChapters=22"))
        assertTrue(debug.contains("cpw=5"))
        assertTrue(debug.contains("windows=5"))
        assertTrue(debug.contains("W0=[0-4](5)"))
        assertTrue(debug.contains("W4=[20-21](2)"))
    }

    @Test
    fun `debugWindowMap returns empty format for zero chapters`() {
        val debug = WindowCalculator.debugWindowMap(0, 5)
        assertTrue(debug.contains("empty"))
    }

    // Test case from the problem statement: EPUB with cover+nav
    // spineAll = 109 items, visibleChapters = 101 (excluding cover+nav+non-linear)
    // With 5 chapters per window:
    // - spineAll windows = ceil(109/5) = 22
    // - visibleChapters windows = ceil(101/5) = 21
    @Test
    fun `real world EPUB case - spine vs visible chapter mismatch`() {
        // This demonstrates the root cause of WINDOW_COUNT_MISMATCH
        val spineWindowCount = WindowCalculator.calculateWindowCount(109, 5)
        val visibleWindowCount = WindowCalculator.calculateWindowCount(101, 5)

        assertEquals(22, spineWindowCount)
        assertEquals(21, visibleWindowCount)

        // The mismatch is expected - the fix is to use visibleWindowCount consistently
        assertNotEquals(spineWindowCount, visibleWindowCount)
    }
}
