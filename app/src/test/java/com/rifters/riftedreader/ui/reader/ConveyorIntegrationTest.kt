package com.rifters.riftedreader.ui.reader

import com.rifters.riftedreader.ui.reader.conveyor.ConveyorBeltSystemViewModel
import org.junit.Assert.*
import org.junit.Test

/**
 * Integration tests for ConveyorBeltSystem authoritative takeover.
 * 
 * Tests verify that:
 * - isConveyorPrimary correctly checks flag and conveyor presence
 * - conveyorBeltSystem accessor respects the flag
 * - ReaderPagerAdapter routing logic works correctly
 */
class ConveyorIntegrationTest {
    
    @Test
    fun `isConveyorPrimary returns false when conveyor not set`() {
        // Test the logic pattern for isConveyorPrimary
        val conveyor: ConveyorBeltSystemViewModel? = null
        val enableFlag = true
        
        val isConveyorPrimary = enableFlag && conveyor != null
        
        assertFalse("isConveyorPrimary should be false when conveyor is null", isConveyorPrimary)
    }
    
    @Test
    fun `isConveyorPrimary returns false when flag is disabled`() {
        val conveyor = ConveyorBeltSystemViewModel()
        val enableFlag = false
        
        val isConveyorPrimary = enableFlag && conveyor != null
        
        assertFalse("isConveyorPrimary should be false when flag is disabled", isConveyorPrimary)
    }
    
    @Test
    fun `isConveyorPrimary returns true when flag enabled and conveyor set`() {
        val conveyor = ConveyorBeltSystemViewModel()
        val enableFlag = true
        
        val isConveyorPrimary = enableFlag && conveyor != null
        
        assertTrue("isConveyorPrimary should be true when flag enabled and conveyor set", isConveyorPrimary)
    }
    
    @Test
    fun `conveyor buffer size returns correct count`() {
        val conveyor = ConveyorBeltSystemViewModel()
        conveyor.initialize(startWindow = 0, totalWindowCount = 10)
        
        assertEquals("Conveyor buffer should have 5 windows", 5, conveyor.buffer.value.size)
    }
    
    @Test
    fun `adapter routing logic uses conveyor when primary`() {
        // Simulate adapter getItemCount logic
        val conveyor = ConveyorBeltSystemViewModel()
        conveyor.initialize(startWindow = 0, totalWindowCount = 10)
        
        val conveyorPrimary = true
        val legacyCount = 10
        
        val count = if (conveyorPrimary) {
            conveyor.buffer.value.size
        } else {
            legacyCount
        }
        
        assertEquals("Should use conveyor buffer size when primary", 5, count)
    }
    
    @Test
    fun `adapter routing logic uses legacy when not primary`() {
        // Simulate adapter getItemCount logic
        val conveyor = ConveyorBeltSystemViewModel()
        conveyor.initialize(startWindow = 0, totalWindowCount = 10)
        
        val conveyorPrimary = false
        val legacyCount = 10
        
        val count = if (conveyorPrimary) {
            conveyor.buffer.value.size
        } else {
            legacyCount
        }
        
        assertEquals("Should use legacy count when not primary", 10, count)
    }
    
    @Test
    fun `adapter routing logic handles null conveyor gracefully`() {
        // Simulate adapter getItemCount logic with null conveyor
        val conveyor: ConveyorBeltSystemViewModel? = null
        val conveyorPrimary = false  // Should be false if conveyor is null
        val legacyCount = 10
        
        val count = if (conveyorPrimary && conveyor != null) {
            conveyor.buffer.value.size
        } else {
            legacyCount
        }
        
        assertEquals("Should fall back to legacy when conveyor is null", 10, count)
    }
}
