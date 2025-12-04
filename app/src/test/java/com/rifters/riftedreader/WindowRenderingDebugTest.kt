package com.rifters.riftedreader

import com.rifters.riftedreader.util.WindowRenderingDebug
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for WindowRenderingDebug utility.
 * 
 * These tests verify:
 * - Debug color cycling for window indices
 * - HTML debug banner generation
 * - Debug utilities are properly guarded by enabled flag
 */
class WindowRenderingDebugTest {

    @Test
    fun `getDebugColor returns distinct colors for windows 0-7`() {
        val colors = (0..7).map { WindowRenderingDebug.getDebugColor(it) }
        
        // All colors should be distinct
        assertEquals("Should have 8 distinct colors", 8, colors.toSet().size)
        
        // Each color should have 25% opacity (0x40 = 64 in alpha channel)
        colors.forEach { color ->
            val alpha = (color shr 24) and 0xFF
            assertEquals("Color should have 25% opacity (alpha=64)", 64, alpha)
        }
    }

    @Test
    fun `getDebugColor cycles colors for indices 8 and above`() {
        // Colors should cycle after 8
        assertEquals(
            "Window 8 should have same color as window 0",
            WindowRenderingDebug.getDebugColor(0),
            WindowRenderingDebug.getDebugColor(8)
        )
        assertEquals(
            "Window 9 should have same color as window 1",
            WindowRenderingDebug.getDebugColor(1),
            WindowRenderingDebug.getDebugColor(9)
        )
        assertEquals(
            "Window 15 should have same color as window 7",
            WindowRenderingDebug.getDebugColor(7),
            WindowRenderingDebug.getDebugColor(15)
        )
    }

    @Test
    fun `generateHtmlDebugBanner returns empty when disabled`() {
        val banner = WindowRenderingDebug.generateHtmlDebugBanner(
            windowIndex = 0,
            firstChapterIndex = 0,
            lastChapterIndex = 4,
            enabled = false
        )
        
        assertEquals("Banner should be empty when disabled", "", banner)
    }

    @Test
    fun `generateHtmlDebugBanner includes window index and chapter range`() {
        val banner = WindowRenderingDebug.generateHtmlDebugBanner(
            windowIndex = 2,
            firstChapterIndex = 10,
            lastChapterIndex = 14,
            enabled = true
        )
        
        assertTrue("Banner should contain window index W2", banner.contains("W2"))
        assertTrue("Banner should contain chapter range", banner.contains("Ch 10-14"))
    }

    @Test
    fun `generateHtmlDebugBanner shows single chapter for same first and last`() {
        val banner = WindowRenderingDebug.generateHtmlDebugBanner(
            windowIndex = 1,
            firstChapterIndex = 5,
            lastChapterIndex = 5,
            enabled = true
        )
        
        assertTrue("Banner should contain 'Ch 5' for single chapter", banner.contains("Ch 5"))
        assertTrue("Banner should NOT contain range dash", !banner.contains("Ch 5-5"))
    }

    @Test
    fun `generateHtmlDebugBanner includes color name`() {
        // Window 0 = RED, Window 1 = GREEN, Window 2 = BLUE, etc.
        val banner0 = WindowRenderingDebug.generateHtmlDebugBanner(0, 0, 4, true)
        val banner1 = WindowRenderingDebug.generateHtmlDebugBanner(1, 5, 9, true)
        val banner2 = WindowRenderingDebug.generateHtmlDebugBanner(2, 10, 14, true)
        
        assertTrue("Window 0 banner should mention RED", banner0.contains("RED"))
        assertTrue("Window 1 banner should mention GREEN", banner1.contains("GREEN"))
        assertTrue("Window 2 banner should mention BLUE", banner2.contains("BLUE"))
    }

    @Test
    fun `generateHtmlDebugBanner has fixed positioning`() {
        val banner = WindowRenderingDebug.generateHtmlDebugBanner(0, 0, 4, true)
        
        assertTrue("Banner should have position: fixed", banner.contains("position: fixed"))
        assertTrue("Banner should have top: 0", banner.contains("top: 0"))
        assertTrue("Banner should have high z-index", banner.contains("z-index: 9999"))
    }

    @Test
    fun `debug colors are semi-transparent`() {
        val color = WindowRenderingDebug.getDebugColor(0)
        
        // Alpha should be 0x40 (25% opacity)
        val alpha = (color shr 24) and 0xFF
        assertTrue("Alpha should be 25% (64/255)", alpha == 64)
    }
}
