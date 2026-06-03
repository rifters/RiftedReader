package com.rifters.riftedreader.domain.reader

import com.rifters.riftedreader.ui.reader.ReaderThemePalette
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderHtmlWrapperTypographyTest {

    @Test
    fun `wrap emits typography css custom properties`() {
        val html = ReaderHtmlWrapper.wrap(
            contentHtml = "<p>test</p>",
            config = ReaderHtmlConfig(
                textSizePx = 18f,
                lineHeightMultiplier = 1.7f,
                fontFamily = "Bookerly",
                pagePaddingPx = 20,
                palette = ReaderThemePalette(backgroundColor = 0xFFFFFF, textColor = 0x000000),
                webViewWidthPx = 1080
            )
        )

        assertTrue(html.contains("--flex-font-size: 18.0px;"))
        assertTrue(html.contains("--flex-line-height: 1.7;"))
        assertTrue(html.contains("--flex-font-family: Bookerly;"))
        assertTrue(html.contains("--flex-page-padding: 20px;"))
        assertTrue(html.contains("font-size: var(--flex-font-size);"))
        assertTrue(html.contains("line-height: var(--flex-line-height);"))
        assertTrue(html.contains("font-family: var(--flex-font-family);"))
        assertTrue(html.contains("padding: var(--flex-page-padding);"))
    }
}
