package com.rifters.riftedreader.domain.parser

import com.rifters.riftedreader.data.database.entities.BookMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.File
import java.util.Locale

/**
 * HTML parser inspired by LibreraReader's HtmlExtractor (`foobnix/ext/HtmlExtractor.java`).
 * Uses Jsoup to sanitize markup and extract metadata for consistent rendering.
 */
class HtmlParser : BookParser {

    private val descriptor = FormatCatalog.descriptorByExtension("html")

    override fun canParse(file: File): Boolean {
        val extension = file.extension.lowercase(Locale.getDefault())
        return descriptor?.extensions?.contains(extension) == true
    }

    override suspend fun extractMetadata(file: File): BookMeta = withContext(Dispatchers.IO) {
        val document = parseDocument(file)
        val title = document.title().ifBlank { file.nameWithoutExtension }
        val author = document.metaTag("author")
        val description = document.metaTag("description")?.takeIf { it.isNotBlank() }

        BookMeta(
            path = file.absolutePath,
            format = descriptor?.displayName ?: "HTML",
            size = file.length(),
            title = title.ifBlank { "Untitled" },
            author = author,
            description = description,
            totalPages = 1
        )
    }

    override suspend fun getPageContent(file: File, page: Int): PageContent = withContext(Dispatchers.IO) {
        val document = parseDocument(file)
        val body = document.body()
        val text = body.text()
        val html = body.html()
        PageContent(text = text, html = html)
    }

    override suspend fun getPageCount(file: File): Int = 1

    override suspend fun getTableOfContents(file: File): List<TocEntry> = withContext(Dispatchers.IO) {
        val document = parseDocument(file)
        val headings = document.select("h1, h2, h3")
        var runningIndex = 0
        headings.map { element ->
            TocEntry(
                title = element.text().ifBlank { "Section ${++runningIndex}" },
                pageNumber = 0,
                level = element.headingLevel()
            )
        }
    }

    private fun parseDocument(file: File): Document {
        return Jsoup.parse(file, null, "")
    }

    private fun Element.headingLevel(): Int {
        val tagName = tagName().lowercase(Locale.getDefault())
        return when (tagName) {
            "h1" -> 0
            "h2" -> 1
            "h3" -> 2
            else -> 0
        }
    }

    private fun Document.metaTag(name: String): String? {
        return selectFirst("meta[name=$name]")?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }
    }
}
