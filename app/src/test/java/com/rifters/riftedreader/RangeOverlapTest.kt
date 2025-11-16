package com.rifters.riftedreader

import org.junit.Test
import org.junit.Assert.*

/**
 * Tests for range overlap detection logic.
 * This validates the fix for the memory waste bug where range.toSet() was used
 * for overlap detection with large text ranges (10,000-50,000+ characters).
 */
class RangeOverlapTest {

    /**
     * Test the old (buggy) approach using set intersection
     * This is memory inefficient for large ranges
     */
    private fun oldRangeOverlap(range1: IntRange, range2: IntRange): Boolean {
        return range1.intersect(range2.toSet()).isNotEmpty()
    }

    /**
     * Test the new (efficient) approach using direct range comparison
     * This is the fix: chunkRange.first <= range.last && range.first <= chunkRange.last
     */
    private fun newRangeOverlap(range1: IntRange, range2: IntRange): Boolean {
        return range1.first <= range2.last && range2.first <= range1.last
    }

    @Test
    fun testOverlap_CompletelyOverlapping() {
        val range1 = 0..100
        val range2 = 0..100
        assertTrue("Completely overlapping ranges should overlap", newRangeOverlap(range1, range2))
        // Verify consistency with old approach
        assertEquals(oldRangeOverlap(range1, range2), newRangeOverlap(range1, range2))
    }

    @Test
    fun testOverlap_PartialOverlap() {
        val range1 = 0..100
        val range2 = 50..150
        assertTrue("Partially overlapping ranges should overlap", newRangeOverlap(range1, range2))
        // Verify consistency with old approach
        assertEquals(oldRangeOverlap(range1, range2), newRangeOverlap(range1, range2))
    }

    @Test
    fun testOverlap_OneInsideOther() {
        val range1 = 0..100
        val range2 = 20..80
        assertTrue("Range inside another should overlap", newRangeOverlap(range1, range2))
        // Verify consistency with old approach
        assertEquals(oldRangeOverlap(range1, range2), newRangeOverlap(range1, range2))
    }

    @Test
    fun testOverlap_TouchingAtBoundary() {
        val range1 = 0..100
        val range2 = 100..200
        assertTrue("Ranges touching at boundary should overlap", newRangeOverlap(range1, range2))
        // Verify consistency with old approach
        assertEquals(oldRangeOverlap(range1, range2), newRangeOverlap(range1, range2))
    }

    @Test
    fun testNoOverlap_SeparateRanges() {
        val range1 = 0..100
        val range2 = 101..200
        assertFalse("Separate ranges should not overlap", newRangeOverlap(range1, range2))
        // Verify consistency with old approach
        assertEquals(oldRangeOverlap(range1, range2), newRangeOverlap(range1, range2))
    }

    @Test
    fun testNoOverlap_FarApart() {
        val range1 = 0..100
        val range2 = 1000..2000
        assertFalse("Far apart ranges should not overlap", newRangeOverlap(range1, range2))
        // Verify consistency with old approach
        assertEquals(oldRangeOverlap(range1, range2), newRangeOverlap(range1, range2))
    }

    @Test
    fun testOverlap_ReverseOrder() {
        val range1 = 50..150
        val range2 = 0..100
        assertTrue("Overlapping ranges (reverse order) should overlap", newRangeOverlap(range1, range2))
        // Verify consistency with old approach
        assertEquals(oldRangeOverlap(range1, range2), newRangeOverlap(range1, range2))
    }

    @Test
    fun testOverlap_LargeRanges_TypicalBookChapter() {
        // Typical book chapter: 10,000-50,000 characters
        val chunkRange = 1000..2000
        val highlightRange = 1500..1600
        assertTrue("Large ranges typical of book chapters should overlap correctly", newRangeOverlap(chunkRange, highlightRange))
        // Verify consistency with old approach
        assertEquals(oldRangeOverlap(chunkRange, highlightRange), newRangeOverlap(chunkRange, highlightRange))
    }

    @Test
    fun testOverlap_VeryLargeRanges() {
        // Very large ranges (50,000+ characters)
        val chunkRange = 0..50000
        val highlightRange = 25000..30000
        assertTrue("Very large ranges should overlap correctly", newRangeOverlap(chunkRange, highlightRange))
        // Note: We don't verify with old approach for very large ranges
        // as it would be too memory intensive
    }

    @Test
    fun testOverlap_SingleElement() {
        val range1 = 100..100
        val range2 = 100..100
        assertTrue("Single element ranges at same position should overlap", newRangeOverlap(range1, range2))
        // Verify consistency with old approach
        assertEquals(oldRangeOverlap(range1, range2), newRangeOverlap(range1, range2))
    }

    @Test
    fun testOverlap_SingleElementNoOverlap() {
        val range1 = 100..100
        val range2 = 101..101
        assertFalse("Single element ranges at different positions should not overlap", newRangeOverlap(range1, range2))
        // Verify consistency with old approach
        assertEquals(oldRangeOverlap(range1, range2), newRangeOverlap(range1, range2))
    }

    @Test
    fun testOverlap_EdgeCase_StartEqualsEnd() {
        val range1 = 50..100
        val range2 = 100..150
        assertTrue("Range where first.last equals second.first should overlap", newRangeOverlap(range1, range2))
        // Verify consistency with old approach
        assertEquals(oldRangeOverlap(range1, range2), newRangeOverlap(range1, range2))
    }
}
