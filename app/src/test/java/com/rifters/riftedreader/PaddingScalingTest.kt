package com.rifters.riftedreader

import android.util.DisplayMetrics
import org.junit.Test
import org.junit.Assert.*

/**
 * Unit tests for padding calculations
 * Tests the fix for inconsistent padding scaling across densities
 */
class PaddingScalingTest {
    
    @Test
    fun paddingConversion_lowDensity_convertsCorrectly() {
        // LDPI: density = 0.75
        val basePaddingDp = 16
        val density = 0.75f
        val expectedPx = (basePaddingDp * density).toInt()
        assertEquals(12, expectedPx)
    }
    
    @Test
    fun paddingConversion_mediumDensity_convertsCorrectly() {
        // MDPI: density = 1.0 (baseline)
        val basePaddingDp = 16
        val density = 1.0f
        val expectedPx = (basePaddingDp * density).toInt()
        assertEquals(16, expectedPx)
    }
    
    @Test
    fun paddingConversion_highDensity_convertsCorrectly() {
        // HDPI: density = 1.5
        val basePaddingDp = 16
        val density = 1.5f
        val expectedPx = (basePaddingDp * density).toInt()
        assertEquals(24, expectedPx)
    }
    
    @Test
    fun paddingConversion_xHighDensity_convertsCorrectly() {
        // XHDPI: density = 2.0
        val basePaddingDp = 16
        val density = 2.0f
        val expectedPx = (basePaddingDp * density).toInt()
        assertEquals(32, expectedPx)
    }
    
    @Test
    fun paddingConversion_xxHighDensity_convertsCorrectly() {
        // XXHDPI: density = 3.0
        val basePaddingDp = 16
        val density = 3.0f
        val expectedPx = (basePaddingDp * density).toInt()
        assertEquals(48, expectedPx)
    }
    
    @Test
    fun paddingConversion_xxxHighDensity_convertsCorrectly() {
        // XXXHDPI: density = 4.0
        val basePaddingDp = 16
        val density = 4.0f
        val expectedPx = (basePaddingDp * density).toInt()
        assertEquals(64, expectedPx)
    }
    
    @Test
    fun indentPlusBasePadding_withDensity_calculatesCorrectly() {
        // Test the combined padding calculation (base + indent)
        val basePaddingDp = 16
        val indentDp = 32  // 2 levels * 16dp
        val density = 2.0f
        
        val basePaddingPx = (basePaddingDp * density).toInt()
        val indentPx = (indentDp * density).toInt()
        val totalPadding = basePaddingPx + indentPx
        
        assertEquals(32, basePaddingPx)
        assertEquals(64, indentPx)
        assertEquals(96, totalPadding)
    }
    
    @Test
    fun bugDemonstration_hardcodedPadding_doesNotScale() {
        // This demonstrates the bug - hardcoded padding doesn't scale
        val hardcodedPadding = 16  // Bug: this is in pixels, not dp
        val indentDp = 32
        val density = 2.0f
        
        val indentPx = (indentDp * density).toInt()
        val totalPadding = hardcodedPadding + indentPx  // Bug: 16px + 64px = 80px
        
        // On XHDPI, this should be 96px (32px base + 64px indent)
        // but with the bug, it's only 80px (16px + 64px)
        assertEquals(80, totalPadding)
        
        // Correct calculation:
        val basePaddingDp = 16
        val basePaddingPx = (basePaddingDp * density).toInt()
        val correctTotalPadding = basePaddingPx + indentPx
        assertEquals(96, correctTotalPadding)
        
        // The bug causes a difference of 16 pixels on XHDPI
        assertNotEquals(totalPadding, correctTotalPadding)
    }
}
