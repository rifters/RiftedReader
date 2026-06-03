package com.rifters.riftedreader.domain.reader

import org.jsoup.Jsoup

data class AnchorEntry(val id: String, val text: String, val level: Int)

object HeadingAnchorSlugger {

    fun slugify(text: String): String {
        return text
            .lowercase()
            .replace(Regex("[`\\u2014\\u2013()./]"), "")
            .replace(Regex("\\s+"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')
    }

    fun injectHeadingIds(html: String): String {
        val document = Jsoup.parseBodyFragment(html)
        document.body().select("h1, h2, h3, h4, h5, h6").forEach { heading ->
            heading.attr("id", slugify(heading.text()))
        }
        return document.body().html()
    }

    fun buildAnchorMap(html: String): List<AnchorEntry> {
        val document = Jsoup.parse(html)
        return document.select("h1, h2, h3, h4, h5, h6").map { heading ->
            val text = heading.text()
            AnchorEntry(
                id = heading.id().ifBlank { slugify(text) },
                text = text,
                level = heading.tagName().removePrefix("h").toInt()
            )
        }
    }
}
