package com.rifters.riftedreader

import com.rifters.riftedreader.domain.pagination.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for the paginator type system and configuration.
 */
class PaginatorConfigTest {
    
    @Test
    fun `PaginatorConfig creates valid window mode config`() {
        val config = PaginatorConfig(
            mode = PaginatorConfig.PaginatorMode.WINDOW,
            windowIndex = 190,
            chapterIndex = 85,
            initialInPageIndex = 5
        )
        
        assertEquals(PaginatorConfig.PaginatorMode.WINDOW, config.mode)
        assertEquals(190, config.windowIndex)
        assertEquals(85, config.chapterIndex)
        assertNull(config.rootSelector)
        assertEquals(5, config.initialInPageIndex)
        assertEquals("window", config.mode.toJsString())
    }
    
    @Test
    fun `PaginatorConfig creates valid chapter mode config`() {
        val config = PaginatorConfig(
            mode = PaginatorConfig.PaginatorMode.CHAPTER,
            windowIndex = 5,
            chapterIndex = 5,
            rootSelector = "section[data-chapter-index='5']"
        )
        
        assertEquals(PaginatorConfig.PaginatorMode.CHAPTER, config.mode)
        assertEquals(5, config.windowIndex)
        assertEquals(5, config.chapterIndex)
        assertEquals("section[data-chapter-index='5']", config.rootSelector)
        assertEquals(0, config.initialInPageIndex) // Default value
        assertEquals("chapter", config.mode.toJsString())
    }
    
    @Test
    fun `PaginatorConfig defaults initialInPageIndex to 0`() {
        val config = PaginatorConfig(
            mode = PaginatorConfig.PaginatorMode.WINDOW,
            windowIndex = 0,
            chapterIndex = null
        )
        
        assertEquals(0, config.initialInPageIndex)
    }
    
    @Test
    fun `PaginatorConfig allows null chapterIndex for window mode`() {
        val config = PaginatorConfig(
            mode = PaginatorConfig.PaginatorMode.WINDOW,
            windowIndex = 190,
            chapterIndex = null
        )
        
        assertNull(config.chapterIndex)
    }
    
    @Test
    fun `Type aliases provide semantic meaning`() {
        val windowIndex: WindowIndex = 190
        val chapterIndex: ChapterIndex = 85
        val inPageIndex: InPageIndex = 5
        val globalPageIndex: GlobalPageIndex = 2000
        
        // Type aliases have different semantic meanings even though they're Int at runtime
        // This test verifies they're properly distinct in usage
        assertNotEquals(windowIndex, chapterIndex)
        assertNotEquals(chapterIndex, inPageIndex)
        
        // But can be compared as needed
        assertTrue(windowIndex > chapterIndex)
        assertTrue(chapterIndex > inPageIndex)
    }
    
    @Test
    fun `ChapterOffset stores position data correctly`() {
        val offset = ChapterOffset(
            chapterIndex = 85,
            scrollOffset = 12000,
            elementId = "chapter-85"
        )
        
        assertEquals(85, offset.chapterIndex)
        assertEquals(12000, offset.scrollOffset)
        assertEquals("chapter-85", offset.elementId)
    }
    
    @Test
    fun `ChapterOffset allows null elementId`() {
        val offset = ChapterOffset(
            chapterIndex = 85,
            scrollOffset = 12000
        )
        
        assertNull(offset.elementId)
    }
    
    @Test
    fun `ChapterOffsetMapping stores window and offsets`() {
        val offsets = listOf(
            ChapterOffset(84, 0, "chapter-84"),
            ChapterOffset(85, 5000, "chapter-85"),
            ChapterOffset(86, 12000, "chapter-86")
        )
        
        val mapping = ChapterOffsetMapping(
            windowIndex = 190,
            offsets = offsets
        )
        
        assertEquals(190, mapping.windowIndex)
        assertEquals(3, mapping.offsets.size)
        assertEquals(84, mapping.offsets[0].chapterIndex)
        assertEquals(85, mapping.offsets[1].chapterIndex)
        assertEquals(86, mapping.offsets[2].chapterIndex)
    }
    
    @Test
    fun `ChapterOffsetMapping handles empty offsets list`() {
        val mapping = ChapterOffsetMapping(
            windowIndex = 190,
            offsets = emptyList()
        )
        
        assertEquals(190, mapping.windowIndex)
        assertTrue(mapping.offsets.isEmpty())
    }
    
    @Test
    fun `PaginatorMode toJsString converts correctly`() {
        assertEquals("window", PaginatorConfig.PaginatorMode.WINDOW.toJsString())
        assertEquals("chapter", PaginatorConfig.PaginatorMode.CHAPTER.toJsString())
    }
}
