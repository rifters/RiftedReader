package com.rifters.riftedreader.pagination

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
        const val DEFAULT_VIEWPORT_WIDTH_PX = 360
        const val DEFAULT_VIEWPORT_HEIGHT_PX = 600
        const val DEFAULT_FONT_SIZE_PX = 16
        const val DEFAULT_LINE_HEIGHT = 1.6f
        const val DEFAULT_FONT_FAMILY = "serif"
        const val DEFAULT_PAGE_PADDING_PX = 16

        @Volatile
        private var defaultFontSizePx: Int = DEFAULT_FONT_SIZE_PX
        @Volatile
        private var defaultLineHeight: Float = DEFAULT_LINE_HEIGHT
        @Volatile
        private var defaultFontFamily: String = DEFAULT_FONT_FAMILY
        @Volatile
        private var defaultPagePaddingPx: Int = DEFAULT_PAGE_PADDING_PX

        fun setDefaultTypography(
            fontSizePx: Int,
            lineHeight: Float,
            fontFamily: String = DEFAULT_FONT_FAMILY,
            pagePaddingPx: Int = DEFAULT_PAGE_PADDING_PX
        ) {
            require(fontSizePx > 0) { "fontSizePx must be > 0" }
            require(lineHeight > 0f) { "lineHeight must be > 0" }
            require(pagePaddingPx >= 0) { "pagePaddingPx must be >= 0" }
            defaultFontSizePx = fontSizePx
            defaultLineHeight = lineHeight
            defaultFontFamily = fontFamily.trim().ifEmpty { DEFAULT_FONT_FAMILY }
            defaultPagePaddingPx = pagePaddingPx
        }

        fun getDefaultTypography(): FlexSlicingConfig {
            return FlexSlicingConfig(
                viewportWidthPx = DEFAULT_VIEWPORT_WIDTH_PX,
                viewportHeightPx = DEFAULT_VIEWPORT_HEIGHT_PX,
                fontSizePx = defaultFontSizePx,
                lineHeight = defaultLineHeight,
                fontFamily = defaultFontFamily,
                pagePaddingPx = defaultPagePaddingPx
            )
        }

        private fun getDefaultFontSizePx(): Int = defaultFontSizePx
        private fun getDefaultLineHeight(): Float = defaultLineHeight
        private fun getDefaultFontFamily(): String = defaultFontFamily
        private fun getDefaultPagePaddingPx(): Int = defaultPagePaddingPx
    }
}
