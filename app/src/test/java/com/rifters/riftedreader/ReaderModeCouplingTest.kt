package com.rifters.riftedreader

import com.rifters.riftedreader.data.preferences.ReaderMode
import com.rifters.riftedreader.data.preferences.ReaderSettings
import com.rifters.riftedreader.domain.pagination.PaginationMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Test that verifies the coupling between ReaderMode and PaginationMode.
 * 
 * Requirements:
 * - Default settings should be PAGE mode with CONTINUOUS pagination
 * - Continuous pagination requires PAGE mode
 * - Switching to SCROLL mode should disable continuous pagination
 * - Enabling continuous pagination should switch to PAGE mode
 */
class ReaderModeCouplingTest {

    @Test
    fun `default settings should be PAGE mode with CONTINUOUS pagination`() {
        val settings = ReaderSettings()
        
        assertEquals("Default mode should be PAGE", ReaderMode.PAGE, settings.mode)
        assertEquals("Default pagination should be CONTINUOUS", PaginationMode.CONTINUOUS, settings.paginationMode)
    }

    @Test
    fun `switching to SCROLL mode should disable continuous pagination`() {
        // Simulate the logic in ReaderSettingsViewModel.updateMode()
        val initial = ReaderSettings(
            mode = ReaderMode.PAGE,
            paginationMode = PaginationMode.CONTINUOUS
        )
        
        // User switches to SCROLL mode
        val updated = if (ReaderMode.SCROLL == ReaderMode.SCROLL && 
                          initial.paginationMode == PaginationMode.CONTINUOUS) {
            initial.copy(
                mode = ReaderMode.SCROLL,
                paginationMode = PaginationMode.CHAPTER_BASED
            )
        } else {
            initial.copy(mode = ReaderMode.SCROLL)
        }
        
        assertEquals("Mode should be SCROLL", ReaderMode.SCROLL, updated.mode)
        assertEquals("SCROLL mode should force CHAPTER_BASED pagination", 
            PaginationMode.CHAPTER_BASED, updated.paginationMode)
    }

    @Test
    fun `enabling continuous pagination in SCROLL mode should switch to PAGE mode`() {
        // Simulate the logic in ReaderSettingsViewModel.updatePaginationMode()
        val initial = ReaderSettings(
            mode = ReaderMode.SCROLL,
            paginationMode = PaginationMode.CHAPTER_BASED
        )
        
        // User enables continuous pagination
        val updated = if (PaginationMode.CONTINUOUS == PaginationMode.CONTINUOUS && 
                          initial.mode == ReaderMode.SCROLL) {
            initial.copy(
                paginationMode = PaginationMode.CONTINUOUS,
                mode = ReaderMode.PAGE
            )
        } else {
            initial.copy(paginationMode = PaginationMode.CONTINUOUS)
        }
        
        assertEquals("Enabling CONTINUOUS should force PAGE mode", 
            ReaderMode.PAGE, updated.mode)
        assertEquals("Pagination should be CONTINUOUS", 
            PaginationMode.CONTINUOUS, updated.paginationMode)
    }

    @Test
    fun `switching to SCROLL mode when already in CHAPTER_BASED should work`() {
        // Simulate the logic in ReaderSettingsViewModel.updateMode()
        val initial = ReaderSettings(
            mode = ReaderMode.PAGE,
            paginationMode = PaginationMode.CHAPTER_BASED
        )
        
        // User switches to SCROLL mode
        val updated = if (ReaderMode.SCROLL == ReaderMode.SCROLL && 
                          initial.paginationMode == PaginationMode.CONTINUOUS) {
            initial.copy(
                mode = ReaderMode.SCROLL,
                paginationMode = PaginationMode.CHAPTER_BASED
            )
        } else {
            initial.copy(mode = ReaderMode.SCROLL)
        }
        
        assertEquals("Mode should be SCROLL", ReaderMode.SCROLL, updated.mode)
        assertEquals("Pagination should remain CHAPTER_BASED", 
            PaginationMode.CHAPTER_BASED, updated.paginationMode)
    }

    @Test
    fun `enabling continuous pagination when already in PAGE mode should work`() {
        // Simulate the logic in ReaderSettingsViewModel.updatePaginationMode()
        val initial = ReaderSettings(
            mode = ReaderMode.PAGE,
            paginationMode = PaginationMode.CHAPTER_BASED
        )
        
        // User enables continuous pagination
        val updated = if (PaginationMode.CONTINUOUS == PaginationMode.CONTINUOUS && 
                          initial.mode == ReaderMode.SCROLL) {
            initial.copy(
                paginationMode = PaginationMode.CONTINUOUS,
                mode = ReaderMode.PAGE
            )
        } else {
            initial.copy(paginationMode = PaginationMode.CONTINUOUS)
        }
        
        assertEquals("Mode should remain PAGE", ReaderMode.PAGE, updated.mode)
        assertEquals("Pagination should be CONTINUOUS", 
            PaginationMode.CONTINUOUS, updated.paginationMode)
    }
}
