package com.rifters.riftedreader.domain.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadingAnchorSluggerTest {

    @Test
    fun `slugify matches TOC anchor examples`() {
        assertEquals("tldr", HeadingAnchorSlugger.slugify("TL;DR"))
        assertEquals("phase-0-viewport-parity", HeadingAnchorSlugger.slugify("Phase 0 — Viewport parity"))
        assertEquals("known-bugslimitations", HeadingAnchorSlugger.slugify("Known bugs/limitations"))
        assertEquals("1-what-is-fully-implemented", HeadingAnchorSlugger.slugify("1. What Is Fully Implemented"))
    }

    @Test
    fun `injectHeadingIds sets ids on all heading levels`() {
        val html = """
            <h1 id="existing">Phase 0 — Viewport parity</h1>
            <p>Body</p>
            <h6>Known bugs/limitations</h6>
            <h6>Known bugs/limitations</h6>
        """.trimIndent()

        val anchoredHtml = HeadingAnchorSlugger.injectHeadingIds(html)

        assertTrue(anchoredHtml.contains("""<h1 id="phase-0-viewport-parity">Phase 0 — Viewport parity</h1>"""))
        assertTrue(anchoredHtml.contains("""<h6 id="known-bugslimitations">Known bugs/limitations</h6>"""))
        assertTrue(anchoredHtml.contains("""<h6 id="known-bugslimitations-2">Known bugs/limitations</h6>"""))
        assertFalse(anchoredHtml.contains("""id="existing""""))
    }

    @Test
    fun `buildAnchorMap returns ordered heading anchors`() {
        val html = """
            <html><body>
                <h2 id="phase-0-viewport-parity">Phase 0 — Viewport parity</h2>
                <p>Body</p>
                <h3>1. What Is Fully Implemented</h3>
            </body></html>
        """.trimIndent()

        val anchors = HeadingAnchorSlugger.buildAnchorMap(html)

        assertEquals(
            listOf(
                AnchorEntry(id = "phase-0-viewport-parity", text = "Phase 0 — Viewport parity", level = 2),
                AnchorEntry(id = "1-what-is-fully-implemented", text = "1. What Is Fully Implemented", level = 3)
            ),
            anchors
        )
    }

    @Test
    fun `buildAnchorMap preserves rendered heading ids`() {
        val headingText = """Phase 3 — Write \flex_paginator.test.js\"""
        val html = """
            <h2 id="rendered-id">$headingText</h2>
            <h3>Rendered ID</h3>
        """.trimIndent()

        val anchors = HeadingAnchorSlugger.buildAnchorMap(html)

        assertEquals(
            listOf(
                AnchorEntry(id = "rendered-id", text = headingText, level = 2),
                AnchorEntry(id = "rendered-id-2", text = "Rendered ID", level = 3)
            ),
            anchors
        )
    }
}
