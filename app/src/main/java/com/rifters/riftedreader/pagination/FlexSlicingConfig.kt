package com.rifters.riftedreader.pagination

import com.rifters.riftedreader.util.CssSanitizers
import java.util.concurrent.atomic.AtomicReference

/**
 * Configuration for FlexPaginator offscreen slicing.
 *
 * This is intentionally UI-agnostic: Reader UI can supply the real viewport size
 * and typography settings, and the offscreen slicer uses the same values to
 * generate SliceMetadata.
 */
data class FlexSlicingConfig(
    val viewportWidthPx: Int = DEFAULT_VIEWPORT_WIDTH_PX,
    val viewportHeightPx: Int = DEFAULT_VIEWPORT_HEIGHT_PX,
    val fontSizePx: Int = getDefaultFontSizePx(),
    val lineHeight: Float = getDefaultLineHeight(),
    val fontFamily: String = getDefaultFontFamily(),
    val pagePaddingPx: Int = getDefaultPagePaddingPx()
) {
    init {
        require(viewportWidthPx > 0) { "viewportWidthPx must be > 0" }
        require(viewportHeightPx > 0) { "viewportHeightPx must be > 0" }
        require(fontSizePx > 0) { "fontSizePx must be > 0" }
        require(lineHeight > 0f) { "lineHeight must be > 0" }
        require(pagePaddingPx >= 0) { "pagePaddingPx must be >= 0" }
    }

    companion object {
        const val MIN_FONT_SIZE_PX = 1
        const val DEFAULT_VIEWPORT_WIDTH_PX = 360
        const val DEFAULT_VIEWPORT_HEIGHT_PX = 600
        const val DEFAULT_FONT_SIZE_PX = 16
        const val DEFAULT_LINE_HEIGHT = 1.6f
        const val DEFAULT_FONT_FAMILY = "sans-serif"
        const val READER_FONT_FAMILY_SERIF = "serif"
        const val DEFAULT_PAGE_PADDING_PX = 16

        private data class TypographyDefaults(
            val fontSizePx: Int,
            val lineHeight: Float,
            val fontFamily: String,
            val pagePaddingPx: Int
        )

        private val typographyDefaultsRef = AtomicReference(
            TypographyDefaults(
                fontSizePx = DEFAULT_FONT_SIZE_PX,
                lineHeight = DEFAULT_LINE_HEIGHT,
                fontFamily = DEFAULT_FONT_FAMILY,
                pagePaddingPx = DEFAULT_PAGE_PADDING_PX
            )
        )

        fun setDefaultTypography(
            fontSizePx: Int,
            lineHeight: Float,
            fontFamily: String = DEFAULT_FONT_FAMILY,
            pagePaddingPx: Int = DEFAULT_PAGE_PADDING_PX
        ) {
            require(fontSizePx >= MIN_FONT_SIZE_PX) { "fontSizePx must be >= $MIN_FONT_SIZE_PX" }
            require(lineHeight > 0f) { "lineHeight must be > 0" }
            require(pagePaddingPx >= 0) { "pagePaddingPx must be >= 0" }
            typographyDefaultsRef.set(
                TypographyDefaults(
                fontSizePx = fontSizePx,
                lineHeight = lineHeight,
                fontFamily = CssSanitizers.sanitizeCssFontFamily(fontFamily, DEFAULT_FONT_FAMILY),
                pagePaddingPx = pagePaddingPx
            )
            )
        }

        fun getDefaultTypography(): FlexSlicingConfig {
            val typography = typographyDefaultsRef.get()
            return FlexSlicingConfig(
                viewportWidthPx = DEFAULT_VIEWPORT_WIDTH_PX,
                viewportHeightPx = DEFAULT_VIEWPORT_HEIGHT_PX,
                fontSizePx = typography.fontSizePx,
                lineHeight = typography.lineHeight,
                fontFamily = typography.fontFamily,
                pagePaddingPx = typography.pagePaddingPx
            )
        }

        private fun getDefaultFontSizePx(): Int = typographyDefaultsRef.get().fontSizePx
        private fun getDefaultLineHeight(): Float = typographyDefaultsRef.get().lineHeight
        private fun getDefaultFontFamily(): String = typographyDefaultsRef.get().fontFamily
        private fun getDefaultPagePaddingPx(): Int = typographyDefaultsRef.get().pagePaddingPx
    }
}
