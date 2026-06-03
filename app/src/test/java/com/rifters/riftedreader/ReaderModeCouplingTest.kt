package com.rifters.riftedreader

import com.rifters.riftedreader.data.preferences.ReaderMode
import com.rifters.riftedreader.data.preferences.ReaderSettings
import com.rifters.riftedreader.domain.pagination.PaginationMode
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderModeCouplingTest {

    @Test
    fun `default settings use paginated reader mode with continuous pagination`() {
        val settings = ReaderSettings()

        assertEquals(ReaderMode.PAGINATED, settings.mode)
        assertEquals(PaginationMode.CONTINUOUS, settings.paginationMode)
    }

    @Test
    fun `scroll reader mode can coexist with continuous pagination`() {
        val settings = ReaderSettings(
            mode = ReaderMode.SCROLL,
            paginationMode = PaginationMode.CONTINUOUS,
            flexPaginatorEnabled = true
        )

        assertEquals(ReaderMode.SCROLL, settings.mode)
        assertEquals(PaginationMode.CONTINUOUS, settings.paginationMode)
        assertEquals(true, settings.flexPaginatorEnabled)
    }

    @Test
    fun `switching reader mode does not change pagination mode`() {
        val initial = ReaderSettings(
            mode = ReaderMode.PAGINATED,
            paginationMode = PaginationMode.CONTINUOUS
        )

        val updated = initial.copy(mode = ReaderMode.SCROLL)

        assertEquals(ReaderMode.SCROLL, updated.mode)
        assertEquals(PaginationMode.CONTINUOUS, updated.paginationMode)
    }
}
