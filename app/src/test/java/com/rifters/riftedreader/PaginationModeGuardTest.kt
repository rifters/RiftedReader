package com.rifters.riftedreader

import com.rifters.riftedreader.domain.pagination.PaginationMode
import com.rifters.riftedreader.domain.pagination.PaginationModeGuard
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for PaginationModeGuard
 */
class PaginationModeGuardTest {
    
    private lateinit var guard: PaginationModeGuard
    
    @Before
    fun setup() {
        guard = PaginationModeGuard()
    }
    
    @Test
    fun `initial state is not building`() {
        assertFalse(guard.isBuilding)
    }
    
    @Test
    fun `beginWindowBuild sets building state`() {
        guard.beginWindowBuild()
        assertTrue(guard.isBuilding)
    }
    
    @Test
    fun `endWindowBuild clears building state`() {
        guard.beginWindowBuild()
        guard.endWindowBuild()
        assertFalse(guard.isBuilding)
    }
    
    @Test
    fun `beginWindowBuild returns true for first call`() {
        val isFirst = guard.beginWindowBuild()
        assertTrue(isFirst)
    }
    
    @Test
    fun `beginWindowBuild returns false for nested calls`() {
        guard.beginWindowBuild()
        val isFirst = guard.beginWindowBuild()
        assertFalse(isFirst)
    }
    
    @Test
    fun `endWindowBuild returns true for last call`() {
        guard.beginWindowBuild()
        val isLast = guard.endWindowBuild()
        assertTrue(isLast)
    }
    
    @Test
    fun `endWindowBuild returns false for nested calls`() {
        guard.beginWindowBuild()
        guard.beginWindowBuild()
        val isLast = guard.endWindowBuild()
        assertFalse(isLast)
        assertTrue(guard.isBuilding)
    }
    
    @Test
    fun `nested calls require matching end calls`() {
        guard.beginWindowBuild()
        guard.beginWindowBuild()
        guard.beginWindowBuild()
        
        guard.endWindowBuild()
        assertTrue(guard.isBuilding)
        
        guard.endWindowBuild()
        assertTrue(guard.isBuilding)
        
        guard.endWindowBuild()
        assertFalse(guard.isBuilding)
    }
    
    @Test
    fun `canChangePaginationMode returns true when not building`() {
        assertTrue(guard.canChangePaginationMode(PaginationMode.CONTINUOUS))
        assertTrue(guard.canChangePaginationMode(PaginationMode.CHAPTER_BASED))
    }
    
    @Test
    fun `canChangePaginationMode returns false when building`() {
        guard.beginWindowBuild()
        
        assertFalse(guard.canChangePaginationMode(PaginationMode.CONTINUOUS))
        assertFalse(guard.canChangePaginationMode(PaginationMode.CHAPTER_BASED))
    }
    
    @Test
    fun `tryChangePaginationMode executes callback when not building`() {
        var callbackExecuted = false
        
        val success = guard.tryChangePaginationMode(PaginationMode.CONTINUOUS) {
            callbackExecuted = true
        }
        
        assertTrue(success)
        assertTrue(callbackExecuted)
    }
    
    @Test
    fun `tryChangePaginationMode blocks callback when building`() {
        guard.beginWindowBuild()
        var callbackExecuted = false
        
        val success = guard.tryChangePaginationMode(PaginationMode.CONTINUOUS) {
            callbackExecuted = true
        }
        
        assertFalse(success)
        assertFalse(callbackExecuted)
    }
    
    @Test
    fun `withWindowBuild executes block and manages state`() {
        var blockExecuted = false
        
        guard.withWindowBuild {
            assertTrue(guard.isBuilding)
            blockExecuted = true
        }
        
        assertTrue(blockExecuted)
        assertFalse(guard.isBuilding)
    }
    
    @Test
    fun `withWindowBuild returns block result`() {
        val result = guard.withWindowBuild {
            42
        }
        assertEquals(42, result)
    }
    
    @Test
    fun `withWindowBuild clears state even on exception`() {
        try {
            guard.withWindowBuild {
                throw RuntimeException("Test exception")
            }
        } catch (e: RuntimeException) {
            // Expected
        }
        
        assertFalse(guard.isBuilding)
    }
    
    @Test
    fun `debugState returns readable string`() {
        val debug = guard.debugState()
        assertTrue(debug.contains("PaginationModeGuard"))
        assertTrue(debug.contains("isBuilding=false"))
    }
    
    @Test
    fun `debugState reflects building state`() {
        guard.beginWindowBuild()
        val debug = guard.debugState()
        assertTrue(debug.contains("isBuilding=true"))
    }
}
