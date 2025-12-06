package com.rifters.riftedreader.ui.reader.conveyor

/**
 * Phase states for the isolated conveyor belt buffer lifecycle.
 * 
 * This enum tracks the phase of the isolated conveyor system independently
 * from any legacy streaming/window buffer code.
 * 
 * ## Phase Lifecycle
 * 
 * 1. **STARTUP**: Initial phase after buffer creation.
 *    - Buffer is loaded with 5 consecutive windows
 *    - User is at window 0 (first window in buffer)
 *    - System waits for user to reach the CENTER window (index 2)
 * 
 * 2. **STEADY**: Steady-state phase after user reaches center.
 *    - User has navigated to the center of the buffer (window at CENTER_POS)
 *    - Buffer shifts are now enabled (forward/backward)
 *    - Active window should remain centered in the buffer
 * 
 * ## Phase Transition Rules
 * 
 * - STARTUP → STEADY: Occurs when user enters window at buffer[CENTER_POS]
 * - This is a one-time transition (no going back to STARTUP)
 * 
 * @see ConveyorBeltSystemViewModel for the state machine implementation
 */
enum class ConveyorPhase {
    /**
     * Initial phase: Buffer is loaded, user hasn't entered center yet.
     * 
     * In this phase:
     * - Buffer contains 5 consecutive windows
     * - User starts at buffer[0]
     * - No buffer shifting occurs
     * - System monitors for when user reaches buffer[CENTER_POS]
     */
    STARTUP,
    
    /**
     * Steady state: User is at center, buffer shifts are enabled.
     * 
     * In this phase:
     * - Active window is kept centered at buffer[CENTER_POS]
     * - Forward navigation: drop leftmost, append new rightmost
     * - Backward navigation: drop rightmost, prepend new leftmost
     * - Buffer maintains 2 windows ahead and 2 behind
     */
    STEADY
}
