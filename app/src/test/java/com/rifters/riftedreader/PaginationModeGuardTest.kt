package com.rifters.riftedreader

import com.rifters.riftedreader.pagination.PaginationModeGuard
import org.junit.Assert.*
import org.junit.Test

class PaginationModeGuardTest {
    
    @Test
    fun `beginWindowBuild returns true on first call`() {
        val guard = PaginationModeGuard()
        assertTrue(guard.beginWindowBuild())
    }
    
    @Test
    fun `beginWindowBuild returns false when build already in progress`() {
        val guard = PaginationModeGuard()
        assertTrue(guard.beginWindowBuild())
        assertFalse(guard.beginWindowBuild())
    }
    
    @Test
    fun `endWindowBuild returns true when no mode change`() {
        val guard = PaginationModeGuard()
        guard.beginWindowBuild()
        assertTrue(guard.endWindowBuild())
    }
    
    @Test
    fun `endWindowBuild allows new build after completion`() {
        val guard = PaginationModeGuard()
        
        // First build
        assertTrue(guard.beginWindowBuild())
        assertTrue(guard.endWindowBuild())
        
        // Second build should be allowed
        assertTrue(guard.beginWindowBuild())
        assertTrue(guard.endWindowBuild())
    }
    
    @Test
    fun `isBuildInProgress returns correct state`() {
        val guard = PaginationModeGuard()
        
        assertFalse(guard.isBuildInProgress())
        
        guard.beginWindowBuild()
        assertTrue(guard.isBuildInProgress())
        
        guard.endWindowBuild()
        assertFalse(guard.isBuildInProgress())
    }
    
    @Test
    fun `endWindowBuild without beginWindowBuild returns true`() {
        val guard = PaginationModeGuard()
        // Should handle gracefully and return true
        assertTrue(guard.endWindowBuild())
    }
    
    @Test
    fun `assertWindowCountInvariant does not throw on matching counts`() {
        val guard = PaginationModeGuard()
        // Should not throw
        guard.assertWindowCountInvariant(24, 24)
    }
    
    @Test
    fun `assertWindowCountInvariant logs on mismatching counts`() {
        val guard = PaginationModeGuard()
        // This should log an error but not throw (for production safety)
        guard.assertWindowCountInvariant(24, 97)
        // If we got here without exception, the test passes
    }
    
    @Test
    fun `guard can be used in try-finally pattern`() {
        val guard = PaginationModeGuard()
        
        var workDone = false
        
        if (guard.beginWindowBuild()) {
            try {
                workDone = true
            } finally {
                guard.endWindowBuild()
            }
        }
        
        assertTrue(workDone)
        assertFalse(guard.isBuildInProgress())
    }
    
    @Test
    fun `guard protects against concurrent builds`() {
        val guard = PaginationModeGuard()
        
        var firstBuildStarted = false
        var secondBuildStarted = false
        
        if (guard.beginWindowBuild()) {
            firstBuildStarted = true
            
            // Try to start another build while first is in progress
            if (guard.beginWindowBuild()) {
                secondBuildStarted = true
            }
            
            guard.endWindowBuild()
        }
        
        assertTrue(firstBuildStarted)
        assertFalse(secondBuildStarted)
    }
}
