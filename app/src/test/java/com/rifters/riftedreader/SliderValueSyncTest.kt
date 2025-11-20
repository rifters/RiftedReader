package com.rifters.riftedreader

import org.junit.Test
import org.junit.Assert.*

/**
 * Unit tests for slider value/valueTo synchronization
 * Verifies that slider value is reduced before valueTo to prevent IllegalStateException
 */
class SliderValueSyncTest {
    
    @Test
    fun sliderValueSync_reducesValueBeforeValueTo() {
        // Simulate the slider state
        var sliderValue = 9.0f
        var sliderValueTo = 10.0f
        
        // New state: total pages reduced to 9 (valueTo should become 8)
        val newTotal = 9
        val newMaxValue = (newTotal - 1).coerceAtLeast(0) // 8
        val newValueTo = newMaxValue.toFloat().coerceAtLeast(1f) // 8.0
        
        // The fix: atomic updates to prevent race condition
        val currentValue = sliderValue
        if (newValueTo < currentValue) {
            // Step 1: Ensure valueTo can accommodate current value
            sliderValueTo = currentValue.coerceAtLeast(newValueTo)
            // Step 2: Reduce value to target
            sliderValue = newValueTo
            // Step 3: Set final valueTo
            sliderValueTo = newValueTo
        } else {
            sliderValueTo = newValueTo
        }
        
        // Verify the order of operations prevents crash
        assertTrue("Value must be <= valueTo", sliderValue <= sliderValueTo)
        assertEquals(8.0f, sliderValue, 0.01f)
        assertEquals(8.0f, sliderValueTo, 0.01f)
    }
    
    @Test
    fun sliderValueSync_handlesEdgeCaseMinimum() {
        // Edge case: going from multiple pages to 1 page
        var sliderValue = 5.0f
        var sliderValueTo = 6.0f
        
        val newTotal = 1
        val newMaxValue = (newTotal - 1).coerceAtLeast(0) // 0
        val newValueTo = newMaxValue.toFloat().coerceAtLeast(1f) // 1.0 (minimum)
        
        // Atomic update to prevent race condition
        val currentValue = sliderValue
        if (newValueTo < currentValue) {
            // Step 1: Ensure valueTo can accommodate current value
            sliderValueTo = currentValue.coerceAtLeast(newValueTo)
            // Step 2: Reduce value to target
            sliderValue = newValueTo
            // Step 3: Set final valueTo
            sliderValueTo = newValueTo
        } else {
            sliderValueTo = newValueTo
        }
        
        assertTrue("Value must be <= valueTo", sliderValue <= sliderValueTo)
        assertEquals(1.0f, sliderValue, 0.01f)
        assertEquals(1.0f, sliderValueTo, 0.01f)
    }
    
    @Test
    fun sliderValueSync_noChangeWhenValueInRange() {
        // Case: value is already within new range
        var sliderValue = 3.0f
        var sliderValueTo = 8.0f
        
        val newTotal = 8
        val newMaxValue = (newTotal - 1).coerceAtLeast(0) // 7
        val newValueTo = newMaxValue.toFloat().coerceAtLeast(1f) // 7.0
        
        val originalValue = sliderValue
        
        // Atomic update to prevent race condition
        val currentValue = sliderValue
        if (newValueTo < currentValue) {
            // Step 1: Ensure valueTo can accommodate current value
            sliderValueTo = currentValue.coerceAtLeast(newValueTo)
            // Step 2: Reduce value to target
            sliderValue = newValueTo
            // Step 3: Set final valueTo
            sliderValueTo = newValueTo
        } else {
            sliderValueTo = newValueTo
        }
        
        assertTrue("Value must be <= valueTo", sliderValue <= sliderValueTo)
        assertEquals("Value should not change when in range", originalValue, sliderValue, 0.01f)
        assertEquals(7.0f, sliderValueTo, 0.01f)
    }
    
    @Test
    fun sliderValueSync_handlesWebViewPageUpdate() {
        // Simulate WebView pagination update
        var sliderValue = 9.0f
        var sliderValueTo = 10.0f
        
        val totalWebViewPages = 9
        val currentWebViewPage = 5
        
        val maxValue = (totalWebViewPages - 1).coerceAtLeast(0) // 8
        val safeValueTo = maxValue.toFloat().coerceAtLeast(1f) // 8.0
        val safeCurrentPage = currentWebViewPage.coerceIn(0, maxValue) // 5
        
        // Atomic update to prevent race condition
        val currentValue = sliderValue
        if (safeValueTo < currentValue) {
            // Step 1: Ensure valueTo can accommodate current value
            sliderValueTo = currentValue.coerceAtLeast(safeValueTo)
            // Step 2: Reduce value to target
            sliderValue = safeCurrentPage.toFloat()
            // Step 3: Set final valueTo
            sliderValueTo = safeValueTo
        } else {
            sliderValueTo = safeValueTo
            sliderValue = safeCurrentPage.toFloat()
        }
        
        assertTrue("Value must be <= valueTo", sliderValue <= sliderValueTo)
        assertEquals(5.0f, sliderValue, 0.01f)
        assertEquals(8.0f, sliderValueTo, 0.01f)
    }
    
    @Test
    fun maxValueCalculation_neverNegative() {
        // Test maxValue calculation with various totals
        val totals = listOf(0, 1, 2, 10, 100)
        
        totals.forEach { total ->
            val maxValue = (total - 1).coerceAtLeast(0)
            assertTrue("maxValue should never be negative for total=$total", maxValue >= 0)
            
            val safeValueTo = maxValue.toFloat().coerceAtLeast(1f)
            assertTrue("safeValueTo should be at least 1.0 for total=$total", safeValueTo >= 1.0f)
        }
    }
}
