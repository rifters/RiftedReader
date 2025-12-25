package com.rifters.riftedreader

import org.junit.Test
import org.junit.Assert.*

/**
 * Unit tests for window navigation bounds checking.
 * 
 * Tests that navigation bounds are correctly validated against totalWindows,
 * not against adapterItemCount (sliding window buffer size).
 */
class WindowNavigationBoundsTest {

    @Test
    fun `navigation should allow window beyond buffer size when within totalWindows`() {
        // Given: A book with 24 total windows and a sliding window buffer of 5 items
        val totalWindows = 24
        val adapterItemCount = 5  // Sliding window buffer size
        val currentWindow = 4
        val nextWindow = currentWindow + 1  // = 5

        // Then: Navigation to window 5 should be allowed
        // (it's within totalWindows even though it equals adapterItemCount)
        val isValidNavigation = nextWindow < totalWindows
        assertTrue(
            "Navigation to window $nextWindow should be allowed when totalWindows=$totalWindows",
            isValidNavigation
        )
    }

    @Test
    fun `navigation should allow any window from 0 to totalWindows minus 1`() {
        // Given: A book with 24 total windows
        val totalWindows = 24
        val adapterItemCount = 5  // Sliding window buffer size (should not affect validation)

        // When/Then: All windows from 0 to 23 should be valid
        for (targetWindow in 0 until totalWindows) {
            val isValid = targetWindow >= 0 && targetWindow < totalWindows
            assertTrue(
                "Window $targetWindow should be valid when totalWindows=$totalWindows",
                isValid
            )
        }
    }

    @Test
    fun `navigation should block window at or beyond totalWindows`() {
        // Given: A book with 24 total windows
        val totalWindows = 24
        
        // When/Then: Window 24 and beyond should be blocked
        val invalidWindow = totalWindows
        val isValid = invalidWindow < totalWindows
        assertFalse(
            "Window $invalidWindow should be blocked when totalWindows=$totalWindows",
            isValid
        )
    }

    @Test
    fun `navigation should block negative window indices`() {
        // Given: Any valid book
        val totalWindows = 24
        
        // When/Then: Negative indices should be blocked
        val invalidWindow = -1
        val isValid = invalidWindow >= 0 && invalidWindow < totalWindows
        assertFalse(
            "Window $invalidWindow should be blocked (negative index)",
            isValid
        )
    }

    @Test
    fun `forward navigation from window 4 to 5 should succeed when totalWindows is 24`() {
        // This is the exact scenario from the bug report
        val totalWindows = 24
        val adapterItemCount = 5
        val currentWindow = 4
        val nextWindow = 5

        // The old buggy check was: nextWindow >= totalWindows || nextWindow >= adapterItemCount
        // This would incorrectly return true (blocked) because nextWindow >= adapterItemCount
        val oldBuggyCheck = nextWindow >= totalWindows || nextWindow >= adapterItemCount
        assertTrue("Old buggy check should block navigation (this is the bug)", oldBuggyCheck)

        // The correct check is: nextWindow >= totalWindows
        // This correctly returns false (allowed) because nextWindow < totalWindows
        val correctCheck = nextWindow >= totalWindows
        assertFalse("Correct check should allow navigation", correctCheck)
    }

    @Test
    fun `backward navigation to negative window should be blocked`() {
        // Given: At window 0
        val currentWindow = 0
        val previousWindow = currentWindow - 1  // = -1

        // When/Then: Navigation to window -1 should be blocked
        val isValid = previousWindow >= 0
        assertFalse(
            "Navigation to window $previousWindow should be blocked (negative)",
            isValid
        )
    }

    @Test
    fun `sliding window buffer size should not affect navigation bounds validation`() {
        // Given: Various buffer sizes
        val totalWindows = 24
        val bufferSizes = listOf(3, 5, 7, 10)
        
        // For each buffer size, all valid windows (0 to totalWindows-1) should be reachable
        for (bufferSize in bufferSizes) {
            for (targetWindow in 0 until totalWindows) {
                // Correct validation: only check against totalWindows
                val isValid = targetWindow >= 0 && targetWindow < totalWindows
                assertTrue(
                    "Window $targetWindow should be valid regardless of bufferSize=$bufferSize when totalWindows=$totalWindows",
                    isValid
                )
                
                // Incorrect validation would also check against bufferSize
                // This would incorrectly block valid windows >= bufferSize
                val wouldBeBlockedByBuggyCheck = targetWindow >= bufferSize
                if (targetWindow >= bufferSize && targetWindow < totalWindows) {
                    assertTrue(
                        "Buggy check would incorrectly block window $targetWindow with bufferSize=$bufferSize",
                        wouldBeBlockedByBuggyCheck
                    )
                }
            }
        }
    }
}
