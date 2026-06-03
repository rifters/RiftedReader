package com.rifters.riftedreader.domain.reader

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

data class AnchorEntry(
    val id: String,
    val text: String,
    val level: Int,
    val charOffset: Int = 0,
    val chapterIndex: Int? = null
)

object HeadingAnchorSlugger {

    /*
     * TOC anchor punctuation removal:
     * `, ;, parentheses, periods, and slashes are stripped as punctuation.
     * \u2014 and \u2013 are em/en dashes, which are removed before whitespace
     * normalization so surrounding spaces collapse into a single hyphen.
     */
    private val punctuationToRemove = Regex("[`;\\u2014\\u2013()./]")

    fun slugify(text: String): String {
        return text
            .lowercase()
            // Remove punctuation specified by the reader TOC anchor contract.
            // \u2014 is an em dash and \u2013 is an en dash.
            .replace(punctuationToRemove, "")
            .replace(Regex("\\s+"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')
    }

    fun injectHeadingIds(html: String): String {
        val document = Jsoup.parseBodyFragment(html)
        val idCounts = mutableMapOf<String, Int>()
        document.body().select("h1, h2, h3, h4, h5, h6").forEach { heading ->
            // Rendered TOC ids must be deterministic, so source ids are intentionally replaced.
            heading.attr("id", uniqueId(slugify(heading.text()), idCounts))
        }
        return document.body().html()
    }

    fun buildAnchorMap(html: String): List<AnchorEntry> {
        val document = Jsoup.parseBodyFragment(html)
        val idCounts = mutableMapOf<String, Int>()
        document.select("h1, h2, h3, h4, h5, h6")
            .map { it.id() }
            .filter { it.isNotBlank() }
            .forEach { id -> idCounts[id] = (idCounts[id] ?: 0) + 1 }
        return document.select("h1, h2, h3, h4, h5, h6").map { heading ->
            val text = heading.text()
            val chapterSection = heading.parents().firstOrNull { it.hasAttr("data-chapter") }
            val offsetRoot = chapterSection ?: document.body()
            AnchorEntry(
                id = heading.id().ifBlank { uniqueId(slugify(text), idCounts) },
                text = text,
                level = heading.tagName().removePrefix("h").toInt(),
                charOffset = textOffsetBefore(offsetRoot, heading),
                chapterIndex = chapterSection?.attr("data-chapter")?.toIntOrNull()
            )
        }
    }

    private fun textOffsetBefore(root: Element, target: Element): Int {
        var offset = 0

        fun visit(node: Node): Boolean {
            if (node === target) {
                return true
            }
            if (node is TextNode) {
                offset += node.wholeText.length
            }
            for (child in node.childNodes()) {
                if (visit(child)) return true
            }
            return false
        }

        visit(root)
        return offset
    }

    private fun uniqueId(baseId: String, idCounts: MutableMap<String, Int>): String {
        val count = (idCounts[baseId] ?: 0) + 1
        idCounts[baseId] = count
        return if (count == 1) baseId else "$baseId-$count"
    }
}
