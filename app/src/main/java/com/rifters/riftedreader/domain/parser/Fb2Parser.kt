package com.rifters.riftedreader.domain.parser

import android.text.TextUtils
import com.rifters.riftedreader.data.database.entities.BookMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Locale

class Fb2Parser : BookParser {

    companion object {
        private val BASE64_WHITESPACE_REGEX = Regex("\\s+")
    }

    override fun canParse(file: File): Boolean {
        val name = file.name.lowercase(Locale.getDefault())
        return name.endsWith(".fb2") || name.endsWith(".fb2.zip")
    }

    override suspend fun extractMetadata(file: File): BookMeta = withContext(Dispatchers.IO) {
        val source = loadSource(file)
        val metadata = source?.let { parseMetadata(it.bytes) }
        val sections = source?.let { parseSections(it.bytes) } ?: emptyList()

        BookMeta(
            path = file.absolutePath,
            format = "FB2",
            size = file.length(),
            title = metadata?.title?.ifBlank { file.nameWithoutExtension } ?: file.nameWithoutExtension,
            author = metadata?.author,
            publisher = metadata?.publisher,
            year = metadata?.year,
            language = metadata?.language,
            description = metadata?.description,
            totalPages = sections.size.coerceAtLeast(1)
        )
    }

    override suspend fun getPageContent(file: File, page: Int): PageContent = withContext(Dispatchers.IO) {
        val source = loadSource(file) ?: return@withContext PageContent.EMPTY
        val sections = parseSections(source.bytes)
        val chapter = sections.getOrNull(page) ?: return@withContext fallbackPage(file, page, sections)
        PageContent(
            text = chapter.text,
            html = chapter.html,
            title = chapter.title
        )
    }

    override suspend fun getPageCount(file: File): Int = withContext(Dispatchers.IO) {
        val source = loadSource(file) ?: return@withContext 1
        parseSections(source.bytes).size.coerceAtLeast(1)
    }

    override suspend fun getTableOfContents(file: File): List<TocEntry> = withContext(Dispatchers.IO) {
        val source = loadSource(file) ?: return@withContext emptyList()
        parseSections(source.bytes).flatMap { it.tocEntries }
    }

    private fun loadSource(file: File): Fb2Source? {
        return if (file.name.lowercase(Locale.getDefault()).endsWith(".fb2.zip")) {
            ZipFile(file).use { zip ->
                val header = zip.fileHeaders.firstOrNull { !it.isDirectory && isFb2Entry(it.fileName) }
                    ?: return null
                zip.getInputStream(header).use { input ->
                    Fb2Source(input.readBytes())
                }
            }
        } else {
            if (!file.exists()) return null
            Fb2Source(file.readBytes())
        }
    }

    private fun parseMetadata(bytes: ByteArray): Fb2Metadata {
        val parser = newParser(bytes)
        var title: String? = null
        var authorParts = mutableListOf<String>()
        var publisher: String? = null
        var year: String? = null
        var language: String? = null
        var description: String? = null
        var inTitleInfo = false
        var inAuthor = false
        var currentFirstName: String? = null
        var currentMiddleName: String? = null
        var currentLastName: String? = null
        var currentNickName: String? = null

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "title-info" -> inTitleInfo = true
                    "author" -> if (inTitleInfo) {
                        inAuthor = true
                        currentFirstName = null
                        currentMiddleName = null
                        currentLastName = null
                        currentNickName = null
                    }
                    "book-title" -> if (inTitleInfo) title = readElementText(parser).takeIf { it.isNotBlank() }
                    "lang" -> if (inTitleInfo && language == null) language = readElementText(parser).takeIf { it.isNotBlank() }
                    "publisher" -> if (parser.depth > 0 && publisher == null) publisher = readElementText(parser).takeIf { it.isNotBlank() }
                    "year" -> if (year == null) year = readElementText(parser).takeIf { it.isNotBlank() }
                    "annotation" -> if (description == null) description = readElementText(parser).takeIf { it.isNotBlank() }
                    "first-name" -> if (inAuthor) currentFirstName = readElementText(parser).takeIf { it.isNotBlank() }
                    "middle-name" -> if (inAuthor) currentMiddleName = readElementText(parser).takeIf { it.isNotBlank() }
                    "last-name" -> if (inAuthor) currentLastName = readElementText(parser).takeIf { it.isNotBlank() }
                    "nickname" -> if (inAuthor) currentNickName = readElementText(parser).takeIf { it.isNotBlank() }
                }

                XmlPullParser.END_TAG -> when (parser.name) {
                    "author" -> {
                        if (inAuthor) {
                            val author = listOfNotNull(
                                currentFirstName,
                                currentMiddleName,
                                currentLastName,
                                currentNickName
                            ).joinToString(" ").trim()
                            if (author.isNotBlank()) {
                                authorParts += author
                            }
                        }
                        inAuthor = false
                    }
                    "title-info" -> inTitleInfo = false
                }
            }
            parser.next()
        }

        return Fb2Metadata(
            title = title,
            author = authorParts.joinToString(", ").ifBlank { null },
            publisher = publisher,
            year = year,
            language = language,
            description = description
        )
    }

    private fun parseSections(bytes: ByteArray): List<Fb2Chapter> {
        val parser = newParser(bytes)
        val binaries = collectBinaries(bytes)
        val chapters = mutableListOf<Fb2Chapter>()
        var inBody = false
        var pageIndex = 0

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "body" -> inBody = true
                    "section" -> if (inBody) {
                        chapters += readSection(parser, pageIndex, 0, binaries)
                        pageIndex += 1
                    }
                }

                XmlPullParser.END_TAG -> if (parser.name == "body") {
                    inBody = false
                }
            }
            parser.next()
        }

        return if (chapters.isEmpty()) {
            val fallbackText = String(bytes, Charsets.UTF_8).trim()
            listOf(
                Fb2Chapter(
                    title = "FB2",
                    text = fallbackText,
                    html = "<pre>${TextUtils.htmlEncode(fallbackText)}</pre>",
                    tocEntries = listOf(TocEntry(title = "FB2", pageNumber = 0))
                )
            )
        } else {
            chapters
        }
    }

    private fun readSection(
        parser: XmlPullParser,
        pageIndex: Int,
        level: Int,
        binaries: Map<String, Fb2Binary>
    ): Fb2Chapter {
        parser.require(XmlPullParser.START_TAG, null, "section")

        val text = StringBuilder()
        val html = StringBuilder()
        val tocEntries = mutableListOf<TocEntry>()
        var sectionTitle: String? = null
        var ended = false

        html.append("<section>")

        while (!ended && parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "title" -> {
                        val titleText = readElementText(parser).normalizeWhitespace()
                        if (sectionTitle.isNullOrBlank()) {
                            sectionTitle = titleText
                        }
                        if (titleText.isNotBlank()) {
                            html.append("<h").append((level + 1).coerceAtMost(6)).append(">")
                                .append(TextUtils.htmlEncode(titleText))
                                .append("</h").append((level + 1).coerceAtMost(6)).append(">")
                        }
                    }

                    "p", "subtitle", "v", "text-author", "cite" -> {
                        val blockText = readElementText(parser).normalizeWhitespace()
                        if (blockText.isNotBlank()) {
                            text.appendLine(blockText)
                            html.append("<p>").append(TextUtils.htmlEncode(blockText)).append("</p>")
                        }
                    }

                    "image" -> {
                        html.append(renderImage(parser, binaries))
                    }

                    "empty-line" -> {
                        text.appendLine()
                        html.append("<br/>")
                    }

                    "section" -> {
                        val child = readSection(parser, pageIndex, level + 1, binaries)
                        if (child.title.isNotBlank()) {
                            text.appendLine(child.title)
                        }
                        if (child.text.isNotBlank()) {
                            text.appendLine(child.text)
                        }
                        html.append(child.html)
                        tocEntries += child.tocEntries
                    }

                    else -> skipElement(parser)
                }

                XmlPullParser.END_TAG -> if (parser.name == "section") {
                    ended = true
                }

                XmlPullParser.TEXT -> {
                    val raw = parser.text?.trim().orEmpty()
                    if (raw.isNotBlank()) {
                        text.appendLine(raw)
                        html.append(TextUtils.htmlEncode(raw))
                    }
                }
            }
        }

        html.append("</section>")

        val title = sectionTitle?.takeIf { it.isNotBlank() } ?: "Section ${pageIndex + 1}"
        tocEntries.add(0, TocEntry(title = title, pageNumber = pageIndex, level = level))

        return Fb2Chapter(
            title = title,
            text = text.toString().trim(),
            html = html.toString(),
            tocEntries = tocEntries
        )
    }

    private fun renderImage(parser: XmlPullParser, binaries: Map<String, Fb2Binary>): String {
        val href = parser.getAttributeValue(null, "href")
            ?: parser.getAttributeValue("http://www.w3.org/1999/xlink", "href")
            ?: parser.getAttributeValue(null, "l:href")
            ?: parser.getAttributeValue(null, "xlink:href")
            ?: return ""
        val id = href.removePrefix("#")
        val binary = binaries[id] ?: return "<div class=\"fb2-image fb2-image-missing\">[image missing]</div>"
        return "<img src=\"data:${binary.contentType};base64,${binary.base64}\" alt=\"${TextUtils.htmlEncode(id)}\" />"
    }

    private fun collectBinaries(bytes: ByteArray): Map<String, Fb2Binary> {
        val parser = newParser(bytes)
        val binaries = linkedMapOf<String, Fb2Binary>()

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "binary") {
                val id = parser.getAttributeValue(null, "id")?.takeIf { it.isNotBlank() }
                if (id != null) {
                    val contentType = parser.getAttributeValue(null, "content-type")?.takeIf { it.isNotBlank() } ?: "image/*"
                    val base64 = readElementText(parser).replace(BASE64_WHITESPACE_REGEX, "")
                    if (base64.isNotBlank()) {
                        binaries[id] = Fb2Binary(contentType, base64)
                    }
                }
            }
            parser.next()
        }

        return binaries
    }

    private fun readElementText(parser: XmlPullParser): String {
        val builder = StringBuilder()
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth += 1
                XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> builder.append(parser.text)
                XmlPullParser.END_TAG -> depth -= 1
            }
        }
        return builder.toString()
    }

    private fun skipElement(parser: XmlPullParser) {
        var depth = 1
        while (depth > 0 && parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> depth += 1
                XmlPullParser.END_TAG -> depth -= 1
            }
        }
    }

    private fun newParser(bytes: ByteArray): XmlPullParser {
        val factory = XmlPullParserFactory.newInstance().apply {
            isNamespaceAware = true
        }
        return factory.newPullParser().apply {
            setInput(ByteArrayInputStream(bytes), null)
        }
    }

    private fun fallbackPage(file: File, page: Int, sections: List<Fb2Chapter>): PageContent {
        val text = if (sections.isNotEmpty()) {
            sections.joinToString("\n\n") { it.text }
        } else {
            file.nameWithoutExtension
        }
        return PageContent(
            text = text,
            html = "<pre>${TextUtils.htmlEncode(text)}</pre>",
            title = file.nameWithoutExtension.ifBlank { "FB2" }
        )
    }

    private fun isFb2Entry(name: String): Boolean {
        val lower = name.lowercase(Locale.getDefault())
        return lower.endsWith(".fb2") || lower.endsWith(".xml")
    }

    private fun String.normalizeWhitespace(): String {
        return trim().replace(Regex("\\s+"), " ")
    }

    private class Fb2Source(val bytes: ByteArray)

    private data class Fb2Metadata(
        val title: String?,
        val author: String?,
        val publisher: String?,
        val year: String?,
        val language: String?,
        val description: String?
    )

    private data class Fb2Binary(
        val contentType: String,
        val base64: String
    )

    private data class Fb2Chapter(
        val title: String,
        val text: String,
        val html: String,
        val tocEntries: List<TocEntry>
    )
}
