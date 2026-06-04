package com.rifters.riftedreader.domain.parser

import com.rifters.riftedreader.data.database.entities.BookMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.Charset
import java.util.Locale

class MobiParser : BookParser {

    private val supportedExtensions = setOf("mobi", "azw", "azw3")

    override fun canParse(file: File): Boolean {
        return file.extension.lowercase(Locale.getDefault()) in supportedExtensions
    }

    override suspend fun extractMetadata(file: File): BookMeta = withContext(Dispatchers.IO) {
        val text = extractReadableText(file)
        BookMeta(
            path = file.absolutePath,
            format = "MOBI",
            size = file.length(),
            title = file.nameWithoutExtension.ifBlank { "MOBI" },
            description = text.takeIf { it.isNotBlank() }?.let {
                "Best-effort MOBI text extraction with no external dependency."
            },
            totalPages = 1
        )
    }

    override suspend fun getPageContent(file: File, page: Int): PageContent = withContext(Dispatchers.IO) {
        if (page != 0) {
            return@withContext PageContent.EMPTY
        }
        val text = extractReadableText(file)
        val displayText = text.ifBlank { "MOBI text extraction is not available for this file." }
        PageContent(
            text = displayText,
            html = "<pre>${escapeHtml(displayText)}</pre>",
            title = file.nameWithoutExtension.ifBlank { "MOBI" }
        )
    }

    override suspend fun getPageCount(file: File): Int = 1

    override suspend fun getTableOfContents(file: File): List<TocEntry> = emptyList()

    private fun extractReadableText(file: File): String {
        if (!file.exists()) return ""

        val bytes = file.readBytes()
        val decoded = decode(bytes)
        return decoded
            .replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]"), " ")
            .replace(Regex("</?(html|body|head|div|span|p|h[1-6]|br|blockquote|ul|ol|li|a|img|meta|title)[^>]*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun decode(bytes: ByteArray): String {
        return runCatching { bytes.toString(Charsets.UTF_8) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: runCatching { bytes.toString(Charsets.ISO_8859_1) }.getOrDefault("")
    }

    private fun escapeHtml(text: String): String {
        return buildString {
            text.forEach { ch ->
                when (ch) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&#39;")
                    else -> append(ch)
                }
            }
        }
    }
}
