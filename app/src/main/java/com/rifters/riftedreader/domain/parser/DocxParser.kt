package com.rifters.riftedreader.domain.parser

import com.rifters.riftedreader.data.database.entities.BookMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.parser.Parser
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.InputStream
import java.util.ArrayDeque
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

private data class ParsedDocxDocument(
    val title: String,
    val author: String?,
    val description: String?,
    val text: String,
    val html: String,
    val toc: List<TocEntry>
)

private data class DocxPackageMetadata(
    val title: String? = null,
    val author: String? = null,
    val description: String? = null
)

private data class RawRun(
    val text: String,
    val isBold: Boolean,
    val isItalic: Boolean,
    val isStrike: Boolean = false,
    val isUnderline: Boolean = false
)

private class XmlNode(
    val name: String,
    val attributes: Map<String, String> = emptyMap(),
    val children: MutableList<XmlNode> = mutableListOf(),
    var text: String? = null
) {
    fun findChild(childName: String): XmlNode? = children.firstOrNull { it.name == childName }

    fun findDescendant(descendantName: String): XmlNode? {
        if (name == descendantName) return this
        for (child in children) {
            val found = child.findDescendant(descendantName)
            if (found != null) return found
        }
        return null
    }
}

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
            ZipFile(file).use { zip ->
                val html = StringBuilder()
                val text = StringBuilder()
                val toc = mutableListOf<TocEntry>()
                val packageMetadata = readPackageMetadata(zip)

                val documentEntry = zip.findEntry("word/document.xml")
                if (documentEntry != null) {
                    zip.getInputStream(documentEntry).buffered().use { input ->
                        val root = parseXmlTree(newXmlPullParser(input))
                        val body = root?.findChild("body") ?: root?.findDescendant("body")
                        body?.let { renderBodyContainer(it, html, text, toc) }
                    }
                }

                val fallbackTitle = file.nameWithoutExtension.ifBlank { "DOCX" }
                val normalizedHtml = html.toString().ifBlank { "<p></p>" }
                // If package metadata is absent, prefer the first heading and then the
                // first non-blank line as a readable title fallback for the reader UI.
                val inferredTitle = toc.firstOrNull()?.title
                    ?: text.lines().firstOrNull { it.isNotBlank() }?.normalizeWhitespace()

                ParsedDocxDocument(
                    title = packageMetadata.title
                        ?: inferredTitle
                        ?: fallbackTitle,
                    author = packageMetadata.author,
                    description = packageMetadata.description,
                    text = text.toString().trim(),
                    html = normalizedHtml,
                    toc = toc
                )
            }
        }.getOrNull()
    }

    private fun renderBodyContainer(
        container: XmlNode,
        html: StringBuilder,
        text: StringBuilder,
        toc: MutableList<TocEntry>
    ) {
        container.children.forEach { child ->
            when (child.name) {
                "p" -> renderParagraph(child, html, text, toc)
                "tbl" -> renderTable(child, html, text, toc)
                "sdt" -> {
                    val sdtContent = child.findDescendant("sdtContent")
                    if (sdtContent != null) {
                        renderBodyContainer(sdtContent, html, text, toc)
                    }
                }
                "txbxContent" -> renderBodyContainer(child, html, text, toc)
            }
        }
    }

    private fun renderTable(
        tableNode: XmlNode,
        html: StringBuilder,
        text: StringBuilder,
        toc: MutableList<TocEntry>
    ) {
        tableNode.children.filter { it.name == "tr" }.forEach { row ->
            row.children.filter { it.name == "tc" }.forEach { cell ->
                renderBodyContainer(cell, html, text, toc)
            }
        }
    }

    private fun renderParagraph(
        paragraphNode: XmlNode,
        html: StringBuilder,
        text: StringBuilder,
        toc: MutableList<TocEntry>
    ) {
        val runs = extractRuns(paragraphNode)
        val plainText = runs.joinToString(separator = "") { it.text }.normalizeWhitespace()
        val inlineHtml = runs.joinToString(separator = "") { renderRun(it) }
            .ifBlank { plainText.escapeHtml() }
        val headingLevel = paragraphNode.headingLevel()
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

        // Render text boxes nested within paragraph drawings if present
        val txbx = paragraphNode.findDescendant("txbxContent")
        if (txbx != null) {
            renderBodyContainer(txbx, html, text, toc)
        }
    }

    private fun extractRuns(node: XmlNode): List<RawRun> {
        val runs = mutableListOf<RawRun>()
        fun walk(current: XmlNode) {
            if (current.name == "r") {
                runs.add(parseRun(current))
                return
            }
            // Do not recurse into paragraph properties, nested tables, cells, or text boxes
            if (current.name == "pPr" || current.name == "tbl" || current.name == "tc" || current.name == "txbxContent") {
                return
            }
            current.children.forEach { walk(it) }
        }
        node.children.forEach { walk(it) }
        return runs
    }

    private fun parseRun(rNode: XmlNode): RawRun {
        val rPr = rNode.children.firstOrNull { it.name == "rPr" }
        val isBold = isPropertyActive(rPr?.children?.firstOrNull { it.name == "b" })
        val isItalic = isPropertyActive(rPr?.children?.firstOrNull { it.name == "i" })
        val isStrike = isPropertyActive(rPr?.children?.firstOrNull { it.name == "strike" || it.name == "dstrike" })
        val uNode = rPr?.children?.firstOrNull { it.name == "u" }
        val isUnderline = uNode != null && uNode.attributes["val"] != "none" && uNode.attributes["val"] != "0" && uNode.attributes["val"] != "false"

        val textBuilder = StringBuilder()
        rNode.children.forEach { child ->
            when (child.name) {
                "t" -> textBuilder.append(child.text.orEmpty())
                "tab" -> textBuilder.append('\t')
                "br", "cr" -> textBuilder.append('\n')
                "noBreakHyphen" -> textBuilder.append('-')
            }
        }

        return RawRun(
            text = textBuilder.toString(),
            isBold = isBold,
            isItalic = isItalic,
            isStrike = isStrike,
            isUnderline = isUnderline
        )
    }

    private fun isPropertyActive(node: XmlNode?): Boolean {
        if (node == null) return false
        val v = node.attributes["val"]
        return v == null || v == "true" || v == "1" || v == "on"
    }

    private fun renderRun(run: RawRun): String {
        val rawText = run.text
        if (rawText.isBlank()) {
            return ""
        }
        val escaped = rawText.escapeHtml()
            .replace("\n", "<br/>")
            .replace("\t", "&nbsp;&nbsp;&nbsp;&nbsp;")
        return wrapInlineFormatting(escaped, run.isBold, run.isItalic, run.isStrike, run.isUnderline)
    }

    private fun wrapInlineFormatting(
        text: String,
        bold: Boolean,
        italic: Boolean,
        strike: Boolean = false,
        underline: Boolean = false
    ): String {
        var result = text
        if (underline) {
            result = "<u>$result</u>"
        }
        if (strike) {
            result = "<s>$result</s>"
        }
        if (italic) {
            result = "<i>$result</i>"
        }
        if (bold) {
            result = "<b>$result</b>"
        }
        return result
    }

    private fun XmlNode.headingLevel(): Int? {
        val pPr = children.firstOrNull { it.name == "pPr" } ?: return null
        val styleName = pPr.children.firstOrNull { it.name == "pStyle" }?.attributes?.get("val")?.trim().orEmpty()
        if (styleName.startsWith("Heading", ignoreCase = true)) {
            val numericSuffix = styleName.drop(7).trim()
            val level = numericSuffix.toIntOrNull()
            if (level != null) {
                return (level - 1).coerceAtLeast(0)
            }
        }
        val outlineLvl = pPr.children.firstOrNull { it.name == "outlineLvl" }?.attributes?.get("val")?.trim()
        if (outlineLvl != null) {
            val lvl = outlineLvl.toIntOrNull()
            if (lvl != null && lvl in 0..8) {
                return lvl
            }
        }
        return null
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

    private fun ZipFile.findEntry(targetPath: String): ZipEntry? {
        val normalized = targetPath.replace('\\', '/').lowercase(Locale.ROOT)
        val direct = getEntry(targetPath) ?: getEntry(targetPath.replace('/', '\\'))
        if (direct != null) return direct
        return entries().asSequence().firstOrNull { entry ->
            entry.name.replace('\\', '/').lowercase(Locale.ROOT) == normalized
        }
    }

    private fun newXmlPullParser(input: InputStream): XmlPullParser {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(input, null)
        return parser
    }

    private fun parseXmlTree(parser: XmlPullParser): XmlNode? {
        val stack = ArrayDeque<XmlNode>()
        var root: XmlNode? = null

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name?.substringAfter(':').orEmpty()
                    val attrs = mutableMapOf<String, String>()
                    for (i in 0 until parser.attributeCount) {
                        val attrName = parser.getAttributeName(i).substringAfter(':')
                        attrs[attrName] = parser.getAttributeValue(i)
                    }
                    val node = XmlNode(tag, attrs)
                    if (stack.isEmpty()) {
                        root = node
                    } else {
                        stack.peek()?.children?.add(node)
                    }
                    stack.push(node)
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                    val current = stack.peek()
                    if (current != null) {
                        val existing = current.text
                        current.text = if (existing == null) parser.text else existing + parser.text
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (stack.isNotEmpty()) {
                        stack.pop()
                    }
                }
            }
            eventType = parser.next()
        }
        return root
    }

    private fun readPackageMetadata(zip: ZipFile): DocxPackageMetadata {
        val coreEntry = zip.findEntry("docProps/core.xml") ?: return DocxPackageMetadata()
        return runCatching {
            zip.getInputStream(coreEntry).buffered().use { input ->
                val xml = input.bufferedReader(Charsets.UTF_8).readText()
                parseCoreProperties(xml)
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
