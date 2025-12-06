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
        
        val buffer = viewModel.bufferContents.value
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
        
        val buffer = viewModel.bufferContents.value
        assertEquals(listOf(0, 1, 2, 3, 4), buffer)
    }
    
    @Test
    fun `initialize clamps start window to valid bounds at end`() {
        viewModel.initialize(startWindow = 8, totalWindowCount = 10)
        
        val buffer = viewModel.bufferContents.value
        assertEquals(5, buffer.size)
        assertEquals(9, buffer.last())
        assertEquals(5, buffer.first())
    }
    
    @Test
    fun `initialize with negative start window clamps to 0`() {
        viewModel.initialize(startWindow = -5, totalWindowCount = 10)
        
        val buffer = viewModel.bufferContents.value
        assertEquals(0, buffer.first())
    }
    
    @Test
    fun `initialize with start window beyond total clamps correctly`() {
        viewModel.initialize(startWindow = 100, totalWindowCount = 10)
        
        val buffer = viewModel.bufferContents.value
        assertTrue(buffer.all { it in 0..9 })
        assertEquals(5, buffer.size)
    }
    
    @Test
    fun `initialize with empty book results in empty buffer`() {
        viewModel.initialize(startWindow = 0, totalWindowCount = 0)
        
        val buffer = viewModel.bufferContents.value
        assertTrue(buffer.isEmpty())
    }
    
    // ========================================================================
    // Phase Transition Tests
    // ========================================================================
    
    @Test
    fun `onWindowEntered does not transition to STEADY before reaching CENTER_POS`() {
        viewModel.initialize(startWindow = 0, totalWindowCount = 10)
        
        // Enter windows 0 and 1 (not yet at CENTER_POS=2)
        viewModel.onWindowEntered(0)
        assertEquals(ConveyorPhase.STARTUP, viewModel.phase.value)
        
        viewModel.onWindowEntered(1)
        assertEquals(ConveyorPhase.STARTUP, viewModel.phase.value)
    }
    
    @Test
    fun `onWindowEntered transitions to STEADY when reaching buffer CENTER_POS`() {
        viewModel.initialize(startWindow = 0, totalWindowCount = 10)
        
        // Buffer is [0,1,2,3,4], CENTER_POS=2 -> window index 2
        viewModel.onWindowEntered(2)
        
        assertEquals(ConveyorPhase.STEADY, viewModel.phase.value)
    }
    
    @Test
    fun `phase transition is one-time only`() {
        viewModel.initialize(startWindow = 0, totalWindowCount = 10)
        
        // Transition to STEADY
        viewModel.onWindowEntered(2)
        assertEquals(ConveyorPhase.STEADY, viewModel.phase.value)
        
        // Re-entering other windows should not change phase
        viewModel.onWindowEntered(0)
        assertEquals(ConveyorPhase.STEADY, viewModel.phase.value)
    }
    
    @Test
    fun `center window is correctly calculated`() {
        viewModel.initialize(startWindow = 0, totalWindowCount = 10)
        
        // Buffer: [0,1,2,3,4], CENTER_POS=2 -> window 2
        assertEquals(2, viewModel.getCenterWindow())
    }
    
    // ========================================================================
    // Forward Shift Tests
    // ========================================================================
    
    @Test
    fun `shiftForward is blocked in STARTUP phase`() {
        viewModel.initialize(startWindow = 0, totalWindowCount = 10)
        
        assertFalse(viewModel.shiftForward())
        
        // Buffer should remain unchanged
        assertEquals(listOf(0, 1, 2, 3, 4), viewModel.bufferContents.value)
    }
    
    @Test
    fun `shiftForward drops leftmost and appends new rightmost in STEADY phase`() {
        viewModel.initialize(startWindow = 0, totalWindowCount = 10)
        viewModel.onWindowEntered(2) // Transition to STEADY
        
        // Buffer: [0,1,2,3,4]
        val shifted = viewModel.shiftForward()
        
        assertTrue(shifted)
        // Buffer should now be: [1,2,3,4,5]
        assertEquals(listOf(1, 2, 3, 4, 5), viewModel.bufferContents.value)
    }
    
    @Test
    fun `shiftForward returns false at end boundary`() {
        viewModel.initialize(startWindow = 5, totalWindowCount = 10) // Buffer: [5,6,7,8,9]
        viewModel.onWindowEntered(7) // Transition to STEADY (center is 7)
        
        val shifted = viewModel.shiftForward()
        
        assertFalse(shifted)
        // Buffer should remain unchanged
        assertEquals(listOf(5, 6, 7, 8, 9), viewModel.bufferContents.value)
    }
    
    @Test
    fun `multiple shiftForward calls progress buffer correctly`() {
        viewModel.initialize(startWindow = 0, totalWindowCount = 10)
        viewModel.onWindowEntered(2) // Transition to STEADY
        
        // Shift forward 3 times
        assertTrue(viewModel.shiftForward())
        assertTrue(viewModel.shiftForward())
        assertTrue(viewModel.shiftForward())
        
        // Buffer should now be: [3,4,5,6,7]
        assertEquals(listOf(3, 4, 5, 6, 7), viewModel.bufferContents.value)
    }
    
    // ========================================================================
    // Backward Shift Tests
    // ========================================================================
    
    @Test
    fun `shiftBackward is blocked in STARTUP phase`() {
        viewModel.initialize(startWindow = 5, totalWindowCount = 10)
        
        assertFalse(viewModel.shiftBackward())
        
        // Buffer should remain unchanged
        assertEquals(listOf(5, 6, 7, 8, 9), viewModel.bufferContents.value)
    }
    
    @Test
    fun `shiftBackward drops rightmost and prepends new leftmost in STEADY phase`() {
        viewModel.initialize(startWindow = 5, totalWindowCount = 10) // Buffer: [5,6,7,8,9]
        viewModel.onWindowEntered(7) // Transition to STEADY (center is 7)
        
        val shifted = viewModel.shiftBackward()
        
        assertTrue(shifted)
        // Buffer should now be: [4,5,6,7,8]
        assertEquals(listOf(4, 5, 6, 7, 8), viewModel.bufferContents.value)
    }
    
    @Test
    fun `shiftBackward returns false at start boundary`() {
        viewModel.initialize(startWindow = 0, totalWindowCount = 10) // Buffer: [0,1,2,3,4]
        viewModel.onWindowEntered(2) // Transition to STEADY
        
        val shifted = viewModel.shiftBackward()
        
        assertFalse(shifted)
        // Buffer should remain unchanged
        assertEquals(listOf(0, 1, 2, 3, 4), viewModel.bufferContents.value)
    }
    
    @Test
    fun `multiple shiftBackward calls progress buffer correctly`() {
        viewModel.initialize(startWindow = 5, totalWindowCount = 10)
        viewModel.onWindowEntered(7) // Transition to STEADY
        
        // Shift backward 3 times
        assertTrue(viewModel.shiftBackward())
        assertTrue(viewModel.shiftBackward())
        assertTrue(viewModel.shiftBackward())
        
        // Buffer should now be: [2,3,4,5,6]
        assertEquals(listOf(2, 3, 4, 5, 6), viewModel.bufferContents.value)
    }
    
    // ========================================================================
    // Query Method Tests
    // ========================================================================
    
    @Test
    fun `isWindowInBuffer returns true for buffered windows`() {
        viewModel.initialize(startWindow = 2, totalWindowCount = 10)
        
        assertTrue(viewModel.isWindowInBuffer(2))
        assertTrue(viewModel.isWindowInBuffer(4))
        assertTrue(viewModel.isWindowInBuffer(6))
    }
    
    @Test
    fun `isWindowInBuffer returns false for non-buffered windows`() {
        viewModel.initialize(startWindow = 2, totalWindowCount = 10)
        
        assertFalse(viewModel.isWindowInBuffer(0))
        assertFalse(viewModel.isWindowInBuffer(1))
        assertFalse(viewModel.isWindowInBuffer(7))
    }
    
    @Test
    fun `getDebugState returns non-empty string`() {
        viewModel.initialize(startWindow = 0, totalWindowCount = 10)
        
        val debugState = viewModel.getDebugState()
        assertTrue(debugState.isNotEmpty())
        assertTrue(debugState.contains("Phase:"))
        assertTrue(debugState.contains("Buffer:"))
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
        
        val buffer = viewModel.bufferContents.value
        assertEquals(3, buffer.size)
        assertEquals(listOf(0, 1, 2), buffer)
    }
    
    @Test
    fun `handles single window book`() {
        viewModel.initialize(startWindow = 0, totalWindowCount = 1)
        
        val buffer = viewModel.bufferContents.value
        assertEquals(1, buffer.size)
        assertEquals(listOf(0), buffer)
        
        // Cannot shift in either direction
        viewModel.onWindowEntered(0) // Try to trigger any state
        assertFalse(viewModel.shiftForward())
        assertFalse(viewModel.shiftBackward())
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
        assertEquals(listOf(5, 6, 7, 8, 9), viewModel.bufferContents.value)
    }
}
