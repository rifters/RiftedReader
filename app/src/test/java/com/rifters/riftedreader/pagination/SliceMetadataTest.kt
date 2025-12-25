package com.rifters.riftedreader.pagination

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for SliceMetadata and PageSlice.
 * 
 * Tests cover:
 * - Slice metadata validation
 * - Finding pages by character offset
 * - Filtering slices by chapter
 * - Edge cases (empty slices, invalid ranges)
 */
class SliceMetadataTest {
    
    @Test
    fun `valid metadata passes validation`() {
        val slices = listOf(
            PageSlice(page = 0, chapter = 0, startChar = 0, endChar = 500, heightPx = 600),
            PageSlice(page = 1, chapter = 0, startChar = 500, endChar = 1000, heightPx = 600),
            PageSlice(page = 2, chapter = 1, startChar = 0, endChar = 450, heightPx = 600)
        )
        
        val metadata = SliceMetadata(
            windowIndex = 0,
            totalPages = 3,
            slices = slices
        )
        
        assertTrue(metadata.isValid())
        assertEquals(3, metadata.totalPages)
        assertEquals(3, metadata.slices.size)
    }
    
    @Test
    fun `invalid metadata fails validation - mismatched page count`() {
        val slices = listOf(
            PageSlice(page = 0, chapter = 0, startChar = 0, endChar = 500, heightPx = 600)
        )
        
        val metadata = SliceMetadata(
            windowIndex = 0,
            totalPages = 3, // Claims 3 pages but only has 1 slice
            slices = slices
        )
        
        assertFalse(metadata.isValid())
    }
    
    @Test
    fun `invalid metadata fails validation - zero pages`() {
        val metadata = SliceMetadata(
            windowIndex = 0,
            totalPages = 0,
            slices = emptyList()
        )
        
        assertFalse(metadata.isValid())
    }
    
    @Test
    fun `getSlice returns correct slice for valid page index`() {
        val slices = listOf(
            PageSlice(page = 0, chapter = 0, startChar = 0, endChar = 500, heightPx = 600),
            PageSlice(page = 1, chapter = 0, startChar = 500, endChar = 1000, heightPx = 600),
            PageSlice(page = 2, chapter = 1, startChar = 0, endChar = 450, heightPx = 600)
        )
        
        val metadata = SliceMetadata(windowIndex = 0, totalPages = 3, slices = slices)
        
        assertEquals(0, metadata.getSlice(0)?.page)
        assertEquals(1, metadata.getSlice(1)?.page)
        assertEquals(2, metadata.getSlice(2)?.page)
    }
    
    @Test
    fun `getSlice returns null for out of range index`() {
        val slices = listOf(
            PageSlice(page = 0, chapter = 0, startChar = 0, endChar = 500, heightPx = 600)
        )
        
        val metadata = SliceMetadata(windowIndex = 0, totalPages = 1, slices = slices)
        
        assertNull(metadata.getSlice(-1))
        assertNull(metadata.getSlice(1))
        assertNull(metadata.getSlice(100))
    }
    
    @Test
    fun `findPageByCharOffset finds correct page within chapter`() {
        val slices = listOf(
            PageSlice(page = 0, chapter = 0, startChar = 0, endChar = 500, heightPx = 600),
            PageSlice(page = 1, chapter = 0, startChar = 500, endChar = 1000, heightPx = 600),
            PageSlice(page = 2, chapter = 0, startChar = 1000, endChar = 1500, heightPx = 600),
            PageSlice(page = 3, chapter = 1, startChar = 0, endChar = 600, heightPx = 600)
        )
        
        val metadata = SliceMetadata(windowIndex = 0, totalPages = 4, slices = slices)
        
        // Chapter 0, various offsets
        assertEquals(0, metadata.findPageByCharOffset(0, 0))
        assertEquals(0, metadata.findPageByCharOffset(0, 250))
        assertEquals(0, metadata.findPageByCharOffset(0, 499))
        assertEquals(1, metadata.findPageByCharOffset(0, 500))
        assertEquals(1, metadata.findPageByCharOffset(0, 750))
        assertEquals(2, metadata.findPageByCharOffset(0, 1000))
        
        // Chapter 1
        assertEquals(3, metadata.findPageByCharOffset(1, 0))
        assertEquals(3, metadata.findPageByCharOffset(1, 300))
    }
    
    @Test
    fun `findPageByCharOffset returns null for non-existent chapter`() {
        val slices = listOf(
            PageSlice(page = 0, chapter = 0, startChar = 0, endChar = 500, heightPx = 600)
        )
        
        val metadata = SliceMetadata(windowIndex = 0, totalPages = 1, slices = slices)
        
        assertNull(metadata.findPageByCharOffset(99, 0))
    }
    
    @Test
    fun `findPageByCharOffset returns null for out of range offset`() {
        val slices = listOf(
            PageSlice(page = 0, chapter = 0, startChar = 0, endChar = 500, heightPx = 600)
        )
        
        val metadata = SliceMetadata(windowIndex = 0, totalPages = 1, slices = slices)
        
        // Offset 500 is beyond the endChar (0-499 is the range)
        assertNull(metadata.findPageByCharOffset(0, 500))
        assertNull(metadata.findPageByCharOffset(0, 1000))
    }
    
    @Test
    fun `getSlicesForChapter filters correctly`() {
        val slices = listOf(
            PageSlice(page = 0, chapter = 0, startChar = 0, endChar = 500, heightPx = 600),
            PageSlice(page = 1, chapter = 0, startChar = 500, endChar = 1000, heightPx = 600),
            PageSlice(page = 2, chapter = 1, startChar = 0, endChar = 600, heightPx = 600),
            PageSlice(page = 3, chapter = 2, startChar = 0, endChar = 400, heightPx = 600),
            PageSlice(page = 4, chapter = 2, startChar = 400, endChar = 800, heightPx = 600)
        )
        
        val metadata = SliceMetadata(windowIndex = 0, totalPages = 5, slices = slices)
        
        val chapter0Slices = metadata.getSlicesForChapter(0)
        assertEquals(2, chapter0Slices.size)
        assertEquals(0, chapter0Slices[0].page)
        assertEquals(1, chapter0Slices[1].page)
        
        val chapter1Slices = metadata.getSlicesForChapter(1)
        assertEquals(1, chapter1Slices.size)
        assertEquals(2, chapter1Slices[0].page)
        
        val chapter2Slices = metadata.getSlicesForChapter(2)
        assertEquals(2, chapter2Slices.size)
        assertEquals(3, chapter2Slices[0].page)
        assertEquals(4, chapter2Slices[1].page)
    }
    
    @Test
    fun `getSlicesForChapter returns empty list for non-existent chapter`() {
        val slices = listOf(
            PageSlice(page = 0, chapter = 0, startChar = 0, endChar = 500, heightPx = 600)
        )
        
        val metadata = SliceMetadata(windowIndex = 0, totalPages = 1, slices = slices)
        
        val result = metadata.getSlicesForChapter(99)
        assertTrue(result.isEmpty())
    }
    
    @Test
    fun `character offset boundary handling`() {
        // Test that endChar is exclusive (not included in range)
        val slices = listOf(
            PageSlice(page = 0, chapter = 0, startChar = 0, endChar = 100, heightPx = 600),
            PageSlice(page = 1, chapter = 0, startChar = 100, endChar = 200, heightPx = 600)
        )
        
        val metadata = SliceMetadata(windowIndex = 0, totalPages = 2, slices = slices)
        
        // Char 99 should be in page 0
        assertEquals(0, metadata.findPageByCharOffset(0, 99))
        
        // Char 100 should be in page 1 (start of next page)
        assertEquals(1, metadata.findPageByCharOffset(0, 100))
    }
}
