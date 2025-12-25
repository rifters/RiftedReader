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
    val fontSizePx: Int = DEFAULT_FONT_SIZE_PX,
    val lineHeight: Float = DEFAULT_LINE_HEIGHT,
    val fontFamily: String = DEFAULT_FONT_FAMILY,
    val pagePaddingPx: Int = DEFAULT_PAGE_PADDING_PX
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
        const val DEFAULT_FONT_FAMILY = "sans-serif"
        const val DEFAULT_PAGE_PADDING_PX = 16
    }
}
