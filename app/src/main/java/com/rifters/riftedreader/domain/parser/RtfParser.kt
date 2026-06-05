package com.rifters.riftedreader.domain.parser

import com.rifters.riftedreader.data.database.entities.BookMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.Charset
import java.util.Locale

private data class ParsedRtfDocument(
    val title: String,
    val author: String?,
    val text: String,
    val html: String
)

private data class RtfState(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val ignorable: Boolean = false,
    val unicodeSkipCount: Int = 1
)

private fun String.escapeHtml(): String {
    return replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}

private class RtfHtmlAccumulator {
    private val html = StringBuilder()
    private val text = StringBuilder()
    private var paragraphOpen = false
    private var boldApplied = false
    private var italicApplied = false

    fun appendText(value: String, state: RtfState) {
        if (value.isEmpty()) return
        ensureParagraph()
        syncFormatting(state)
        html.append(value.escapeHtml().replace("\n", "<br/>").replace("\t", "&nbsp;&nbsp;&nbsp;&nbsp;"))
        text.append(value)
    }

    fun appendSymbol(value: String, state: RtfState) {
        appendText(value, state)
    }

    fun paragraphBreak() {
        if (paragraphOpen) {
            syncFormatting(RtfState())
            html.append("</p>")
            paragraphOpen = false
        }
        val currentText = text.toString()
        if (currentText.isNotEmpty() && !currentText.endsWith("\n")) {
            text.appendLine()
        }
    }

    fun syncFormatting(state: RtfState) {
        if (italicApplied && !state.italic) {
            html.append("</i>")
            italicApplied = false
        }
        if (boldApplied && !state.bold) {
            html.append("</b>")
            boldApplied = false
        }
        if (!boldApplied && state.bold) {
            html.append("<b>")
            boldApplied = true
        }
        if (!italicApplied && state.italic) {
            html.append("<i>")
            italicApplied = true
        }
    }

    fun build(): Pair<String, String> {
        if (paragraphOpen) {
            syncFormatting(RtfState())
            html.append("</p>")
            paragraphOpen = false
        }
        val normalizedHtml = html.toString().ifBlank { "<p></p>" }
        return normalizedHtml to text.toString().trim()
    }

    private fun ensureParagraph() {
        if (!paragraphOpen) {
            html.append("<p>")
            paragraphOpen = true
        }
    }
}

class RtfParser : BookParser {

    private val supportedExtensions = setOf("rtf")
    private val windows1252 = Charset.forName("windows-1252")

    override fun canParse(file: File): Boolean {
        return file.extension.lowercase(Locale.getDefault()) in supportedExtensions
    }

    override suspend fun extractMetadata(file: File): BookMeta = withContext(Dispatchers.IO) {
        val fallbackTitle = file.nameWithoutExtension.ifBlank { "RTF" }
        val parsed = parseDocument(file)
        BookMeta(
            path = file.absolutePath,
            format = "RTF",
            size = file.length(),
            title = parsed?.title?.ifBlank { fallbackTitle } ?: fallbackTitle,
            author = parsed?.author,
            totalPages = 1
        )
    }

    override suspend fun getPageContent(file: File, page: Int): PageContent = withContext(Dispatchers.IO) {
        if (page != 0) {
            return@withContext PageContent.EMPTY
        }
        val fallbackTitle = file.nameWithoutExtension.ifBlank { "RTF" }
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

    override suspend fun getTableOfContents(file: File): List<TocEntry> = emptyList()

    private fun parseDocument(file: File): ParsedRtfDocument? {
        if (!file.exists()) return null
        return runCatching {
            val source = file.readBytes().toString(Charsets.ISO_8859_1)
            val accumulator = RtfHtmlAccumulator()
            val stack = ArrayDeque<RtfState>()
            var state = RtfState()
            var index = 0
            var pendingUnicodeFallback = 0

            while (index < source.length) {
                val current = source[index]
                when (current) {
                    '{' -> {
                        stack.addLast(state.copy())
                        index += 1
                    }

                    '}' -> {
                        state = stack.removeLastOrNull() ?: RtfState()
                        accumulator.syncFormatting(state)
                        index += 1
                    }

                    '\\' -> {
                        val parsed = parseControl(source, index, state, pendingUnicodeFallback)
                        state = parsed.state
                        pendingUnicodeFallback = parsed.pendingUnicodeFallback
                        when (parsed.output) {
                            ControlOutput.PARAGRAPH_BREAK -> accumulator.paragraphBreak()
                            is ControlOutput.TEXT -> if (!state.ignorable && pendingUnicodeFallback == 0) {
                                accumulator.appendSymbol(parsed.output.value, state)
                            }
                            ControlOutput.NONE -> Unit
                        }
                        index = parsed.nextIndex
                    }

                    '\r', '\n' -> {
                        index += 1
                    }

                    else -> {
                        if (pendingUnicodeFallback > 0) {
                            pendingUnicodeFallback -= 1
                        } else if (!state.ignorable) {
                            accumulator.appendText(current.toString(), state)
                        }
                        index += 1
                    }
                }
            }

            val (html, text) = accumulator.build()
            val fallbackTitle = extractInfoField(source, "title") ?: file.nameWithoutExtension.ifBlank { "RTF" }
            ParsedRtfDocument(
                title = fallbackTitle,
                author = extractInfoField(source, "author"),
                text = text,
                html = html
            )
        }.getOrNull()
    }

    private fun parseControl(
        source: String,
        startIndex: Int,
        currentState: RtfState,
        pendingUnicodeFallback: Int
    ): ParsedControl {
        val nextIndex = startIndex + 1
        if (nextIndex >= source.length) {
            return ParsedControl(startIndex + 1, currentState, ControlOutput.NONE, pendingUnicodeFallback)
        }

        val symbol = source[nextIndex]
        return when {
            symbol == '\\' || symbol == '{' || symbol == '}' -> {
                ParsedControl(nextIndex + 1, currentState, ControlOutput.TEXT(symbol.toString()), pendingUnicodeFallback)
            }

            symbol == '\'' && nextIndex + 2 < source.length -> {
                val hex = source.substring(nextIndex + 1, nextIndex + 3)
                val decoded = byteArrayOf(hex.toInt(16).toByte()).toString(windows1252)
                ParsedControl(nextIndex + 3, currentState, ControlOutput.TEXT(decoded), pendingUnicodeFallback)
            }

            !symbol.isLetter() -> {
                val output = when (symbol) {
                    '~' -> ControlOutput.TEXT(" ")
                    '-' -> ControlOutput.TEXT("-")
                    '_' -> ControlOutput.TEXT("—")
                    '*' -> ControlOutput.NONE
                    else -> ControlOutput.NONE
                }
                val updatedState = if (symbol == '*') currentState.copy(ignorable = true) else currentState
                ParsedControl(nextIndex + 1, updatedState, output, pendingUnicodeFallback)
            }

            else -> parseControlWord(source, nextIndex, currentState, pendingUnicodeFallback)
        }
    }

    private fun parseControlWord(
        source: String,
        startIndex: Int,
        currentState: RtfState,
        pendingUnicodeFallback: Int
    ): ParsedControl {
        var cursor = startIndex
        while (cursor < source.length && source[cursor].isLetter()) {
            cursor += 1
        }
        val word = source.substring(startIndex, cursor)

        var sign = 1
        if (cursor < source.length && source[cursor] == '-') {
            sign = -1
            cursor += 1
        }
        val numberStart = cursor
        while (cursor < source.length && source[cursor].isDigit()) {
            cursor += 1
        }
        val numericValue = if (cursor > numberStart) {
            source.substring(numberStart, cursor).toInt() * sign
        } else {
            null
        }
        if (cursor < source.length && source[cursor] == ' ') {
            cursor += 1
        }

        val updatedState = when (word) {
            "b" -> currentState.copy(bold = numericValue != 0)
            "i" -> currentState.copy(italic = numericValue != 0)
            "plain" -> currentState.copy(bold = false, italic = false)
            "pard" -> currentState.copy(bold = false, italic = false)
            "uc" -> currentState.copy(unicodeSkipCount = numericValue ?: 1)
            in IGNORED_DESTINATIONS -> currentState.copy(ignorable = true)
            else -> currentState
        }

        val output = when (word) {
            "par", "line" -> ControlOutput.PARAGRAPH_BREAK
            "tab" -> ControlOutput.TEXT("\t")
            "u" -> ControlOutput.TEXT(decodeUnicode(numericValue ?: 0))
            "emdash" -> ControlOutput.TEXT("—")
            "endash" -> ControlOutput.TEXT("–")
            "bullet" -> ControlOutput.TEXT("•")
            else -> ControlOutput.NONE
        }
        val nextPendingUnicode = if (word == "u") updatedState.unicodeSkipCount else pendingUnicodeFallback
        return ParsedControl(cursor, updatedState, output, nextPendingUnicode)
    }

    private fun decodeUnicode(value: Int): String {
        val normalized = if (value < 0) 65536 + value else value
        return normalized.toChar().toString()
    }

    private fun extractInfoField(source: String, field: String): String? {
        val regex = Regex("""\\(?:${Regex.escape(field)})(?:\s+)?([^\\{}]+)""")
        return regex.find(source)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private data class ParsedControl(
        val nextIndex: Int,
        val state: RtfState,
        val output: ControlOutput,
        val pendingUnicodeFallback: Int
    )

    private sealed interface ControlOutput {
        data object NONE : ControlOutput
        data object PARAGRAPH_BREAK : ControlOutput
        data class TEXT(val value: String) : ControlOutput
    }

    private companion object {
        private val IGNORED_DESTINATIONS = setOf(
            "fonttbl",
            "colortbl",
            "stylesheet",
            "info",
            "pict",
            "object",
            "header",
            "headerl",
            "headerr",
            "footer",
            "footerl",
            "footerr",
            "fldinst"
        )
    }
}
