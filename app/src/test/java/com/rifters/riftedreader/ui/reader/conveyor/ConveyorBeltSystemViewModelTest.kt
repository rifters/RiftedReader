package com.rifters.riftedreader.ui.reader.conveyor

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ConveyorBeltSystemViewModel.
 * 
 * Tests the isolated conveyor belt system's:
 * - Buffer initialization
 * - Phase transitions (STARTUP → STEADY)
 * - Forward/backward buffer shifting
 * - Edge cases and boundary conditions
 */
class ConveyorBeltSystemViewModelTest {
    
    private lateinit var viewModel: ConveyorBeltSystemViewModel
    
    @Before
    fun setup() {
        viewModel = ConveyorBeltSystemViewModel()
    }
    
    // ========================================================================
    // Initialization Tests
    // ========================================================================
    
    @Test
    fun `initialize creates buffer with 5 consecutive windows starting from startWindow`() {
        viewModel.initialize(startWindow = 2, totalWindowCount = 10)
        
        val buffer = viewModel.buffer.value
        assertEquals(5, buffer.size)
        assertEquals(listOf(2, 3, 4, 5, 6), buffer)
    }
    
    @Test
    fun `initialize sets active window to first in buffer`() {
        viewModel.initialize(startWindow = 3, totalWindowCount = 10)
        
        assertEquals(3, viewModel.activeWindow.value)
    }
    
    @Test
    fun `initialize starts in STARTUP phase`() {
        viewModel.initialize(startWindow = 0, totalWindowCount = 10)
        
        assertEquals(ConveyorPhase.STARTUP, viewModel.phase.value)
    }
    
    @Test
    fun `initialize clamps start window to valid bounds at beginning`() {
        viewModel.initialize(startWindow = 0, totalWindowCount = 10)
        
        val buffer = viewModel.buffer.value
        assertEquals(listOf(0, 1, 2, 3, 4), buffer)
    }
    
    @Test
    fun `initialize clamps start window to valid bounds at end`() {
        viewModel.initialize(startWindow = 8, totalWindowCount = 10)
        
        val buffer = viewModel.buffer.value
        assertEquals(5, buffer.size)
        assertEquals(9, buffer.last())
        assertEquals(5, buffer.first())
    }
    
    @Test
    fun `initialize with negative start window clamps to 0`() {
        viewModel.initialize(startWindow = -5, totalWindowCount = 10)
        
        val buffer = viewModel.buffer.value
        assertEquals(0, buffer.first())
    }
    
    @Test
    fun `initialize with start window beyond total clamps correctly`() {
        viewModel.initialize(startWindow = 100, totalWindowCount = 10)
        
        val buffer = viewModel.buffer.value
        assertEquals(5, buffer.size)
        assertEquals(9, buffer.last())
    }
    
    // ========================================================================
    // Phase Transition Tests (STARTUP → STEADY)
    // ========================================================================
    
    @Test
    fun `window 0 in STARTUP navigates and remains in STARTUP`() {
        viewModel.initialize(startWindow = 0, totalWindowCount = 10)
        
        viewModel.onWindowEntered(0)
        assertEquals(ConveyorPhase.STARTUP, viewModel.phase.value)
        assertEquals(0, viewModel.activeWindow.value)
    }
    
    @Test
    fun `window 1 in STARTUP navigates but does not trigger transition`() {
        viewModel.initialize(startWindow = 0, totalWindowCount = 10)
        
        viewModel.onWindowEntered(1)
        assertEquals(ConveyorPhase.STARTUP, viewModel.phase.value)
        assertEquals(1, viewModel.activeWindow.value)
    }
    
    @Test
    fun `window 2 in STARTUP unlocks shifts but stays in STARTUP`() {
        viewModel.initialize(startWindow = 0, totalWindowCount = 10)
        
        viewModel.onWindowEntered(2)
        assertEquals(ConveyorPhase.STARTUP, viewModel.phase.value)
        assertEquals(2, viewModel.activeWindow.value)
    }
    
    @Test
    fun `window 3 transitions to STEADY if shifts are unlocked`() {
        viewModel.initialize(startWindow = 0, totalWindowCount = 10)
        
        // Go to window 2 to unlock
        viewModel.onWindowEntered(2)
        assertEquals(ConveyorPhase.STARTUP, viewModel.phase.value)
        
        // Go to window 3 - should trigger transition
        viewModel.onWindowEntered(3)
        assertEquals(ConveyorPhase.STEADY, viewModel.phase.value)
        assertEquals(3, viewModel.activeWindow.value)
        
        // Buffer should have shifted
        assertEquals(listOf(1, 2, 3, 4, 5), viewModel.buffer.value)
    }
    
    @Test
    fun `window 3 without unlocked shifts does not transition`() {
        viewModel.initialize(startWindow = 0, totalWindowCount = 10)
        
        // Try to go directly to window 3 (which is in buffer but shifts not unlocked)
        viewModel.onWindowEntered(3)
        assertEquals(ConveyorPhase.STARTUP, viewModel.phase.value)
        assertEquals(3, viewModel.activeWindow.value)
    }
    
    // ========================================================================
    // Event Log Tests
    // ========================================================================
    
    @Test
    fun `events are logged during initialization`() {
        viewModel.initialize(startWindow = 0, totalWindowCount = 10)
        
        val log = viewModel.eventLog.value
        assertTrue(log.isNotEmpty())
        assertTrue(log.any { it.contains("INIT") })
    }
    
    @Test
    fun `clearEventLog clears the log`() {
        viewModel.initialize(startWindow = 0, totalWindowCount = 10)
        viewModel.onWindowEntered(0)
        
        viewModel.clearEventLog()
        
        // Only the "LOG_CLEARED" message should remain
        val log = viewModel.eventLog.value
        assertTrue(log.size <= 1)
    }
    
    // ========================================================================
    // Simulation Tests
    // ========================================================================
    
    @Test
    fun `simulateNextWindow advances active window`() {
        viewModel.initialize(startWindow = 0, totalWindowCount = 10)
        
        viewModel.simulateNextWindow()
        assertEquals(1, viewModel.activeWindow.value)
        
        viewModel.simulateNextWindow()
        assertEquals(2, viewModel.activeWindow.value)
    }
    
    @Test
    fun `simulatePreviousWindow goes back active window`() {
        viewModel.initialize(startWindow = 3, totalWindowCount = 10)
        
        viewModel.simulatePreviousWindow()
        assertEquals(2, viewModel.activeWindow.value)
    }
    
    @Test
    fun `simulateNextWindow triggers phase transition when reaching center`() {
        viewModel.initialize(startWindow = 0, totalWindowCount = 10)
        
        // Navigate to center (window 2)
        viewModel.simulateNextWindow() // 0 -> 1
        viewModel.simulateNextWindow() // 1 -> 2 (center!)
        
        assertEquals(ConveyorPhase.STEADY, viewModel.phase.value)
    }
    
    // ========================================================================
    // Edge Cases
    // ========================================================================
    
    @Test
    fun `handles small book with fewer than 5 windows`() {
        viewModel.initialize(startWindow = 0, totalWindowCount = 3)
        
        val buffer = viewModel.buffer.value
        assertEquals(3, buffer.size)
        assertEquals(listOf(0, 1, 2), buffer)
    }
    
    @Test
    fun `handles single window book`() {
        viewModel.initialize(startWindow = 0, totalWindowCount = 1)
        
        val buffer = viewModel.buffer.value
        assertEquals(1, buffer.size)
        assertEquals(listOf(0), buffer)
        
        // Navigate to the only window
        viewModel.onWindowEntered(0)
        assertEquals(ConveyorPhase.STARTUP, viewModel.phase.value)
    }
    
    @Test
    fun `reinitialize resets all state`() {
        // First initialization
        viewModel.initialize(startWindow = 0, totalWindowCount = 10)
        viewModel.onWindowEntered(2) // Transition to STEADY
        assertEquals(ConveyorPhase.STEADY, viewModel.phase.value)
        
        // Reinitialize
        viewModel.initialize(startWindow = 5, totalWindowCount = 10)
        
        // Should be back to STARTUP
        assertEquals(ConveyorPhase.STARTUP, viewModel.phase.value)
        assertEquals(listOf(5, 6, 7, 8, 9), viewModel.buffer.value)
    }
    
    @Test
    fun `getCenterWindow returns the center element`() {
        viewModel.initialize(startWindow = 0, totalWindowCount = 10)
        
        // Initial buffer is [0, 1, 2, 3, 4], center is at index 2, value is 2
        assertEquals(2, viewModel.getCenterWindow())
    }
}
