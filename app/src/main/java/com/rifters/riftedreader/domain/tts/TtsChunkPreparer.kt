package com.rifters.riftedreader.domain.tts

import org.jsoup.Jsoup

data class TtsPreparedChunk(
    val index: Int,
    val text: String,
    val startPosition: Int
)

object TtsChunkPreparer {
    const val BLOCK_SELECTORS = "p, li, blockquote, h1, h2, h3, h4, h5, h6, pre, article, section"

    /**
     * Extracts TTS chunks from HTML.
     * When [readFootnotes] is false, elements with class 'footnote' are stripped so notes
     * are never spoken. When true, footnotes are included in their end-of-paragraph position.
     */
    fun extractChunks(html: String, readFootnotes: Boolean): List<TtsPreparedChunk> {
        val document = Jsoup.parseBodyFragment(html)
        val nodes = document.select(BLOCK_SELECTORS)
        val chunks = mutableListOf<TtsPreparedChunk>()
        var index = 0
        var currentPos = 0

        for (node in nodes) {
            if (!readFootnotes && (node.hasClass("footnote") || node.parents().hasClass("footnote"))) {
                continue
            }
            val text = if (!readFootnotes) {
                val clone = node.clone()
                clone.select(".footnote").remove()
                clone.text()
            } else {
                node.text()
            }.replace(Regex("\\s+"), " ").trim()

            if (text.isNotBlank()) {
                chunks.add(TtsPreparedChunk(index = index, text = text, startPosition = currentPos))
                currentPos += text.length + 1
                index++
            }
        }
        return chunks
    }

    /**
     * Strips footnote annotations "[Note: ...]" from plain text when footnotes are disabled.
     */
    fun filterFootnotesFromText(text: String): String {
        return text.replace(Regex("""\s*\[Note:\s*[^\]]*\]"""), "").trim()
    }
}
