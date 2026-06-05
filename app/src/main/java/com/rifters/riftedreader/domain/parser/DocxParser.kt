package com.rifters.riftedreader.domain.parser

import com.rifters.riftedreader.data.database.entities.BookMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFParagraph
import org.apache.poi.xwpf.usermodel.XWPFRun
import org.apache.poi.xwpf.usermodel.XWPFTable
import org.jsoup.parser.Parser
import java.io.File
import java.util.Locale
import java.util.zip.ZipInputStream

private data class ParsedDocxDocument(
    val title: String,
    val author: String?,
    val description: String?,
    val text: String,
    val html: String,
    val toc: List<TocEntry>
)

class DocxParser : BookParser {

    private val descriptor = FormatCatalog.descriptorByExtension("docx")

    override fun canParse(file: File): Boolean {
        return file.extension.lowercase(Locale.getDefault()) in (descriptor?.extensions ?: emptySet())
    }

    override suspend fun extractMetadata(file: File): BookMeta = withContext(Dispatchers.IO) {
        val fallbackTitle = file.nameWithoutExtension.ifBlank { "DOCX" }
        val parsed = parseDocument(file)
        BookMeta(
            path = file.absolutePath,
            format = descriptor?.displayName ?: "DOCX",
            size = file.length(),
            title = parsed?.title?.ifBlank { fallbackTitle } ?: fallbackTitle,
            author = parsed?.author,
            description = parsed?.description,
            totalPages = 1
        )
    }

    override suspend fun getPageContent(file: File, page: Int): PageContent = withContext(Dispatchers.IO) {
        if (page != 0) {
            return@withContext PageContent.EMPTY
        }
        val fallbackTitle = file.nameWithoutExtension.ifBlank { "DOCX" }
        val parsed = parseDocument(file)
        if (parsed == null) {
            return@withContext PageContent(text = "", html = "<p></p>", title = fallbackTitle)
        }
        PageContent(
            text = parsed.text,
            html = parsed.html,
            title = parsed.title
        )
    }

    override suspend fun getPageCount(file: File): Int = 1

    override suspend fun getTableOfContents(file: File): List<TocEntry> = withContext(Dispatchers.IO) {
        parseDocument(file)?.toc.orEmpty()
    }

    private fun parseDocument(file: File): ParsedDocxDocument? {
        if (!file.exists()) return null
        return runCatching {
            file.inputStream().buffered().use { input ->
                XWPFDocument(input).use { document ->
                    val html = StringBuilder()
                    val text = StringBuilder()
                    val toc = mutableListOf<TocEntry>()
                    val packageMetadata = readPackageMetadata(file)
                    document.bodyElements.forEach { element ->
                        when (element) {
                            is XWPFParagraph -> renderParagraph(element, html, text, toc)
                            is XWPFTable -> renderTable(element, html, text, toc)
                        }
                    }
                    val fallbackTitle = file.nameWithoutExtension.ifBlank { "DOCX" }
                    val normalizedHtml = html.toString().ifBlank { "<p></p>" }
                    val inferredTitle = toc.firstOrNull()?.title
                        ?: text.lines().firstOrNull { it.isNotBlank() }?.normalizeWhitespace()
                    ParsedDocxDocument(
                        title = packageMetadata.title
                            ?: document.properties.coreProperties.title?.trim().takeUnless { it.isNullOrBlank() }
                            ?: inferredTitle
                            ?: fallbackTitle,
                        author = packageMetadata.author
                            ?: document.properties.coreProperties.creator?.trim().takeUnless { it.isNullOrBlank() },
                        description = packageMetadata.description
                            ?: document.properties.coreProperties.description?.trim().takeUnless { it.isNullOrBlank() },
                        text = text.toString().trim(),
                        html = normalizedHtml,
                        toc = toc
                    )
                }
            }
        }.getOrNull()
    }

    private fun renderTable(
        table: XWPFTable,
        html: StringBuilder,
        text: StringBuilder,
        toc: MutableList<TocEntry>
    ) {
        table.rows.forEach { row ->
            row.tableCells.forEach { cell ->
                cell.bodyElements.forEach { element ->
                    when (element) {
                        is XWPFParagraph -> renderParagraph(element, html, text, toc)
                        is XWPFTable -> renderTable(element, html, text, toc)
                    }
                }
            }
        }
    }

    private fun renderParagraph(
        paragraph: XWPFParagraph,
        html: StringBuilder,
        text: StringBuilder,
        toc: MutableList<TocEntry>
    ) {
        val plainText = paragraph.text.normalizeWhitespace()
        val inlineHtml = paragraph.runs.joinToString(separator = "") { renderRun(it) }
            .ifBlank { plainText.escapeHtml() }
        val headingLevel = paragraph.headingLevel()
        val tag = if (headingLevel != null) "h${(headingLevel + 1).coerceAtMost(6)}" else "p"

        html.append('<').append(tag).append('>')
        html.append(if (inlineHtml.isBlank()) "<br/>" else inlineHtml)
        html.append("</").append(tag).append('>')

        if (plainText.isNotBlank()) {
            if (text.isNotEmpty()) {
                text.appendLine()
            }
            text.append(plainText)
            if (headingLevel != null) {
                toc += TocEntry(title = plainText, pageNumber = 0, level = headingLevel)
            }
        }
    }

    private fun renderRun(run: XWPFRun): String {
        val rawText = buildString {
            append(run.text())
            repeat(run.ctr?.sizeOfTabArray() ?: 0) { append('\t') }
            repeat(run.ctr?.sizeOfBrArray() ?: 0) { append('\n') }
            repeat(run.ctr?.sizeOfCrArray() ?: 0) { append('\n') }
        }
        if (rawText.isBlank()) {
            return ""
        }
        val escaped = rawText.escapeHtml()
            .replace("\n", "<br/>")
            .replace("\t", "&nbsp;&nbsp;&nbsp;&nbsp;")
        return wrapInlineFormatting(escaped, run.isBold, run.isItalic)
    }

    private fun wrapInlineFormatting(text: String, bold: Boolean, italic: Boolean): String {
        var result = text
        if (italic) {
            result = "<i>$result</i>"
        }
        if (bold) {
            result = "<b>$result</b>"
        }
        return result
    }

    private fun XWPFParagraph.headingLevel(): Int? {
        val styleName = style?.trim().orEmpty()
        if (!styleName.startsWith("Heading", ignoreCase = true)) {
            return null
        }
        val numericSuffix = styleName.removePrefix("Heading").trim()
        val level = numericSuffix.toIntOrNull() ?: return 0
        return (level - 1).coerceAtLeast(0)
    }

    private fun String.normalizeWhitespace(): String {
        return trim().replace(Regex("\\s+"), " ")
    }

    private fun String.escapeHtml(): String {
        return replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    private fun readPackageMetadata(file: File): DocxPackageMetadata {
        return runCatching {
            ZipInputStream(file.inputStream().buffered()).use { zip ->
                generateSequence { zip.nextEntry }
                    .firstOrNull { it.name == "docProps/core.xml" }
                    ?.let {
                        val xml = zip.readBytes().toString(Charsets.UTF_8)
                        parseCoreProperties(xml)
                    }
            }
        }.getOrNull() ?: DocxPackageMetadata()
    }

    private fun parseCoreProperties(xml: String): DocxPackageMetadata {
        val document = org.jsoup.Jsoup.parse(xml, "", Parser.xmlParser())
        return DocxPackageMetadata(
            title = document.selectFirst("dc|title, title")?.text()?.trim()?.takeIf { it.isNotEmpty() },
            author = document.selectFirst("dc|creator, creator")?.text()?.trim()?.takeIf { it.isNotEmpty() },
            description = document.selectFirst("dc|description, description")?.text()?.trim()?.takeIf { it.isNotEmpty() }
        )
    }
}

private data class DocxPackageMetadata(
    val title: String? = null,
    val author: String? = null,
    val description: String? = null
)
