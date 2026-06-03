package com.rifters.riftedreader.pagination

import org.junit.Assert.assertEquals
import org.junit.Test

class FlexSlicingConfigTypographyTest {

    @Test
    fun `setDefaultTypography updates default constructor values`() {
        val original = FlexSlicingConfig.getDefaultTypography()
        try {
            FlexSlicingConfig.setDefaultTypography(
                fontSizePx = 21,
                lineHeight = 1.9f,
                fontFamily = "Bookerly",
                pagePaddingPx = 22
            )

            val config = FlexSlicingConfig()
            assertEquals(21, config.fontSizePx)
            assertEquals(1.9f, config.lineHeight)
            assertEquals("Bookerly", config.fontFamily)
            assertEquals(22, config.pagePaddingPx)
        } finally {
            FlexSlicingConfig.setDefaultTypography(
                fontSizePx = original.fontSizePx,
                lineHeight = original.lineHeight,
                fontFamily = original.fontFamily,
                pagePaddingPx = original.pagePaddingPx
            )
        }
    }
}
