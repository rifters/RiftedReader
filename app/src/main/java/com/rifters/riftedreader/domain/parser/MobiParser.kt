package com.rifters.riftedreader.domain.parser

import android.util.Log
import com.rifters.riftedreader.data.database.entities.BookMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

internal object PalmDocDecompressor {

    /**
     * Decompresses a PalmDOC LZ77-encoded byte array.
     * Encoding rules:
     *   0x00         → literal 0x00
     *   0x01–0x08    → copy next N bytes literally
     *   0x09–0x7F    → literal byte
     *   0x80–0xBF    → two-byte back-reference: distance + length
     *   0xC0–0xFF    → space (0x20) + (byte XOR 0x80)
     *
     * Back-reference decode (when first byte b is in 0x80..0xBF):
     *   next  = following byte
     *   dist  = ((b and 0x3F) shl 8 or next) ushr 3
     *   len   = (next and 0x07) + 3
     *   source index = output.size - dist
     *   Copy len bytes circularly from source (handles len > dist).
     *   Skip the reference silently if dist == 0 or source < 0.
     */
    fun decompress(input: ByteArray): ByteArray {
        val out = ArrayList<Byte>(input.size * 3)
        var i = 0
        while (i < input.size) {
            val b = input[i++].toInt() and 0xFF
            when {
                b == 0x00 -> out.add(0.toByte())
                b in 0x01..0x08 -> {
                    val end = minOf(i + b, input.size)
                    while (i < end) {
                        out.add(input[i++])
                    }
                }

                b in 0x09..0x7F -> out.add(b.toByte())
                b in 0x80..0xBF -> {
                    if (i >= input.size) break
                    val next = input[i++].toInt() and 0xFF
                    val dist = ((b and 0x3F) shl 8 or next) ushr 3
                    val len = (next and 0x07) + 3
                    val start = out.size - dist
                    if (dist > 0 && start >= 0) {
                        for (j in 0 until len) {
                            out.add(out[start + j % dist])
                        }
                    }
                }

                else -> {
                    out.add(0x20.toByte())
                    out.add((b xor 0x80).toByte())
                }
            }
        }
        return out.toByteArray()
    }
}

private data class MobiHeaderData(
    val compression: Int,
    val textRecordCount: Int,
    val encoding: String,
    val title: String?,
    val author: String?,
    val firstNonBookIndex: Int,
    val exthFlags: Int
)

private data class ParsedMobiBook(
    val header: MobiHeaderData,
    val chapters: List<Pair<String, String>>,
    val toc: List<TocEntry>
)

class MobiParser : BookParser {

    private val supportedExtensions = setOf("mobi", "azw", "azw3")

    override fun canParse(file: File): Boolean {
        return file.extension.lowercase(Locale.getDefault()) in supportedExtensions
    }

    override suspend fun extractMetadata(file: File): BookMeta = withContext(Dispatchers.IO) {
        val parsed = parseBook(file)
        val fallbackTitle = file.nameWithoutExtension.ifBlank { "MOBI" }
        BookMeta(
            path = file.absolutePath,
            format = "MOBI",
            size = file.length(),
            title = parsed?.header?.title?.ifBlank { fallbackTitle } ?: fallbackTitle,
            author = parsed?.header?.author,
            totalPages = parsed?.chapters?.size?.coerceAtLeast(1) ?: 1
        )
    }

    override suspend fun getPageContent(file: File, page: Int): PageContent = withContext(Dispatchers.IO) {
        if (page < 0) {
            return@withContext PageContent.EMPTY
        }
        val parsed = parseBook(file) ?: return@withContext PageContent.EMPTY
        val chapter = parsed.chapters.getOrNull(page)
        if (chapter == null) {
            return@withContext if (page == 0 && parsed.chapters.isEmpty()) {
                PageContent(
                    text = "",
                    html = "",
                    title = parsed.header.title ?: file.nameWithoutExtension.ifBlank { "MOBI" }
                )
            } else {
                PageContent.EMPTY
            }
        }
        val text = Jsoup.parseBodyFragment(chapter.second).text().trim()
        PageContent(
            text = text,
            html = chapter.second,
            title = chapter.first
        )
    }

    override suspend fun getPageCount(file: File): Int = withContext(Dispatchers.IO) {
        parseBook(file)?.chapters?.size?.coerceAtLeast(1) ?: 1
    }

    override suspend fun getTableOfContents(file: File): List<TocEntry> = withContext(Dispatchers.IO) {
        parseBook(file)?.toc.orEmpty()
    }

    /**
     * Returns a list of absolute byte offsets for each PalmDB record.
     * PalmDB layout:
     *   bytes 0–31:  database name (ignored)
     *   bytes 76–77: numRecords (big-endian unsigned short)
     *   bytes 78+:   record list — each entry is 8 bytes:
     *                  offset [0–3] big-endian int
     *                  attributes [4]
     *                  uniqueId [5–7]
     */
    private fun parsePalmDbHeader(raw: ByteArray): List<Int> {
        if (raw.size < 78) return emptyList()
        val buf = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
        val numRecords = buf.getShort(76).toInt() and 0xFFFF
        val offsets = mutableListOf<Int>()
        for (i in 0 until numRecords) {
            val pos = 78 + i * 8
            if (pos + 4 > raw.size) break
            offsets.add(buf.getInt(pos))
        }
        return offsets
    }

    /**
     * Parses the MOBI header from record 0 data.
     * Record 0 layout:
     *   bytes 0–15:  PalmDOC header
     *     [0–1] compression  [8–9] textRecordCount
     *   bytes 16+:  MOBI header (starts with "MOBI")
     *     [0–3]   "MOBI" magic
     *     [4–7]   headerLength
     *     [12–15] textEncoding (65001=UTF-8, 1252=windows-1252)
     *     [80–83] exthFlags (relative to MOBI header start)
     *     [84–87] firstNonBookIndex (relative to MOBI header start at +96? — read actual offset below)
     *
     * Precise field offsets within record 0 data:
     *   palmDoc[0]:  compression        (Short at rec0+0)
     *   palmDoc[8]:  textRecordCount    (Short at rec0+8)
     *   mobi[0]:     "MOBI"             (bytes rec0+16 to rec0+19)
     *   mobi[4]:     headerLength       (Int   at rec0+20)
     *   mobi[12]:    textEncoding       (Int   at rec0+28)
     *   mobi[80]:    exthFlags          (Int   at rec0+96)
     *   mobi[116]:   firstNonBookIndex  (Int   at rec0+132)
     *   mobi[84]:    fullNameOffset     (Int   at rec0+100) — offset from rec0 start
     *   mobi[88]:    fullNameLength     (Int   at rec0+104)
     *
     * EXTH block starts at: rec0 + 16 + headerLength
     * EXTH record types:
     *   100 = author, 503 = updated title (preferred title source)
     */
    private fun parseMobiHeader(rec0: ByteArray): MobiHeaderData {
        val buf = ByteBuffer.wrap(rec0).order(ByteOrder.BIG_ENDIAN)

        val compression = buf.getShort(0).toInt() and 0xFFFF
        val textRecordCount = buf.getShort(8).toInt() and 0xFFFF

        if (
            rec0.size < 20 ||
            rec0[16] != 'M'.code.toByte() ||
            rec0[17] != 'O'.code.toByte() ||
            rec0[18] != 'B'.code.toByte() ||
            rec0[19] != 'I'.code.toByte()
        ) {
            return MobiHeaderData(compression, textRecordCount, "UTF-8", null, null, Int.MAX_VALUE, 0)
        }

        val headerLength = if (rec0.size > 24) buf.getInt(20) else 0
        val textEncodingRaw = if (rec0.size > 28) buf.getInt(28) else 65001
        val encoding = if (textEncodingRaw == 1252) "windows-1252" else "UTF-8"
        val exthFlags = if (rec0.size > 96) buf.getInt(96) else 0
        val firstNonBookIndex = if (rec0.size > 132) buf.getInt(132) else Int.MAX_VALUE

        val fullNameOffset = if (rec0.size > 100) buf.getInt(100) else -1
        val fullNameLength = if (rec0.size > 104) buf.getInt(104) else 0
        val palmTitle = if (
            fullNameOffset > 0 &&
            fullNameLength > 0 &&
            fullNameOffset + fullNameLength <= rec0.size
        ) {
            String(rec0, fullNameOffset, fullNameLength, charset(encoding)).trim()
        } else {
            null
        }

        var exthTitle: String? = null
        var exthAuthor: String? = null

        val exthOffset = 16 + headerLength
        if (exthFlags and 0x40 != 0 && exthOffset >= 0 && rec0.size >= exthOffset + 12) {
            val exthId = String(rec0, exthOffset, 4, Charsets.US_ASCII)
            if (exthId == "EXTH") {
                val exthRecordCount = buf.getInt(exthOffset + 8)
                var pos = exthOffset + 12
                for (recordIndex in 0 until exthRecordCount) {
                    if (pos + 8 > rec0.size) break
                    val type = buf.getInt(pos)
                    val length = buf.getInt(pos + 4)
                    val dataLength = length - 8
                    if (dataLength > 0 && pos + 8 + dataLength <= rec0.size) {
                        val value = String(rec0, pos + 8, dataLength, Charsets.UTF_8).trim()
                        when (type) {
                            100 -> if (exthAuthor == null) exthAuthor = value
                            503 -> exthTitle = value
                        }
                    }
                    pos += length.coerceAtLeast(8)
                }
            }
        }

        val title = exthTitle?.takeIf { it.isNotBlank() } ?: palmTitle?.takeIf { it.isNotBlank() }

        return MobiHeaderData(
            compression = compression,
            textRecordCount = textRecordCount,
            encoding = encoding,
            title = title,
            author = exthAuthor?.takeIf { it.isNotBlank() },
            firstNonBookIndex = firstNonBookIndex,
            exthFlags = exthFlags
        )
    }

    /**
     * Reads raw bytes of the file, parses the PalmDB record table,
     * and decompresses text records 1..textRecordCount into a single
     * HTML string. Returns "" on any structural failure.
     *
     * Compression 1 = no decompression needed.
     * Compression 2 = PalmDOC LZ77 via PalmDocDecompressor.
     * Compression 17480 = HUFF/CDIC decompression for AZW3/KF8 text records.
     *
     * Each record's decompression is wrapped in runCatching so a single
     * corrupt record does not abort the entire extraction.
     */
    private fun extractHtml(raw: ByteArray, header: MobiHeaderData): String {
        val offsets = parsePalmDbHeader(raw)
        if (offsets.size < 2) return ""
        val huffCdicDecompressor = if (header.compression == 17480) {
            createHuffCdicDecompressor(raw, offsets, header)
        } else {
            null
        }
        if (header.compression == 17480 && huffCdicDecompressor == null) return ""

        val sb = StringBuilder()
        val lastTextRecord = minOf(header.textRecordCount, offsets.size - 1)

        for (recIndex in 1..lastTextRecord) {
            val start = offsets[recIndex]
            val end = if (recIndex + 1 < offsets.size) offsets[recIndex + 1] else raw.size
            if (start >= end || start >= raw.size) continue

            val recBytes = raw.copyOfRange(start, minOf(end, raw.size))
            val decompressed = if (header.compression == 2) {
                runCatching { PalmDocDecompressor.decompress(recBytes) }.getOrElse { ByteArray(0) }
            } else if (header.compression == 17480) {
                runCatching { huffCdicDecompressor?.decompress(recBytes) ?: ByteArray(0) }.getOrElse { ByteArray(0) }
            } else {
                recBytes
            }
            if (decompressed.isNotEmpty()) {
                sb.append(String(decompressed, charset(header.encoding)))
            }
        }

        return sb.toString()
    }

    private fun createHuffCdicDecompressor(
        raw: ByteArray,
        offsets: List<Int>,
        header: MobiHeaderData
    ): HuffCdicDecompressor? {
        val huffIndex = header.firstNonBookIndex
        if (huffIndex <= 0 || huffIndex >= offsets.size) return null

        val huffRecord = recordBytes(raw, offsets, huffIndex) ?: return null
        val cdicRecords = mutableListOf<ByteArray>()
        var recordIndex = huffIndex + 1
        while (recordIndex < offsets.size) {
            val record = recordBytes(raw, offsets, recordIndex) ?: break
            if (!hasRecordMagic(record, "CDIC")) break
            cdicRecords += record
            recordIndex++
        }
        if (cdicRecords.isEmpty()) return null

        return runCatching { HuffCdicDecompressor(huffRecord, cdicRecords) }.getOrNull()
    }

    private fun recordBytes(raw: ByteArray, offsets: List<Int>, recordIndex: Int): ByteArray? {
        val start = offsets.getOrNull(recordIndex) ?: return null
        val end = offsets.getOrNull(recordIndex + 1) ?: raw.size
        if (start < 0 || start >= end || start >= raw.size) return null
        return raw.copyOfRange(start, minOf(end, raw.size))
    }

    private fun hasRecordMagic(record: ByteArray, magic: String): Boolean {
        return record.size >= magic.length &&
            magic.indices.all { record[it] == magic[it].code.toByte() }
    }

    /**
     * Splits a jsoup Document into chapters at every <h1>, <h2>, or
     * <h3> element. Returns a list of pairs: (chapterTitle, htmlBody).
     *
     * If no headings are found, returns a single entry containing the
     * full body html. chapterTitle is the heading text, or "Chapter N"
     * as a fallback when heading text is blank.
     */
    private fun splitIntoChapters(doc: org.jsoup.nodes.Document): List<Pair<String, String>> {
        val body = doc.body() ?: return emptyList()
        val topLevelNodes = body.childNodes()
        if (topLevelNodes.isEmpty()) return emptyList()

        val chapters = mutableListOf<Pair<String, String>>()
        val html = StringBuilder()
        var currentTitle: String? = null

        fun flushChapter() {
            val chapterHtml = html.toString().trim()
            if (chapterHtml.isBlank()) return
            val fallbackTitle = "Chapter ${chapters.size + 1}"
            chapters += (currentTitle?.takeIf { it.isNotBlank() } ?: fallbackTitle) to chapterHtml
            html.setLength(0)
        }

        for (node in topLevelNodes) {
            val element = node as? org.jsoup.nodes.Element
            val tagName = element?.tagName()?.lowercase(Locale.getDefault())
            val isHeading = tagName == "h1" || tagName == "h2" || tagName == "h3"
            if (isHeading && html.isNotEmpty()) {
                flushChapter()
            }
            if (isHeading) {
                currentTitle = element?.text()?.trim().takeUnless { it.isNullOrBlank() }
            }
            html.append(node.outerHtml())
        }

        if (html.isNotEmpty()) {
            flushChapter()
        }

        if (chapters.isEmpty()) {
            val fullHtml = body.html().trim()
            if (fullHtml.isBlank()) return emptyList()
            return listOf("Chapter 1" to fullHtml)
        }

        return chapters
    }

    private fun parseBook(file: File): ParsedMobiBook? {
        if (!file.exists()) return null

        val raw = runCatching { file.readBytes() }
            .onFailure { Log.e(TAG, "Failed to read MOBI file: ${file.absolutePath}", it) }
            .getOrNull()
            ?: return null

        val offsets = parsePalmDbHeader(raw)
        if (offsets.isEmpty()) {
            Log.w(TAG, "Missing PalmDB record offsets: ${file.absolutePath}")
            return null
        }

        val rec0Start = offsets[0]
        val rec0End = offsets.getOrNull(1) ?: raw.size
        if (rec0Start < 0 || rec0Start >= rec0End || rec0End > raw.size) {
            Log.w(TAG, "Invalid MOBI header record bounds: ${file.absolutePath}")
            return null
        }

        val rec0 = raw.copyOfRange(rec0Start, rec0End)
        val header = parseMobiHeader(rec0)
        val html = extractHtml(raw, header)
        val chapters = if (html.isBlank()) {
            emptyList()
        } else {
            splitIntoChapters(Jsoup.parse(html))
        }
        val toc = buildToc(chapters)

        return ParsedMobiBook(
            header = header,
            chapters = chapters,
            toc = toc
        )
    }

    private fun buildToc(chapters: List<Pair<String, String>>): List<TocEntry> {
        return chapters.mapIndexed { index, (title, html) ->
            val tagName = Jsoup.parseBodyFragment(html).body().children().firstOrNull()
                ?.tagName()
                ?.lowercase(Locale.getDefault())
            TocEntry(
                title = title,
                pageNumber = index,
                level = when (tagName) {
                    "h1" -> 0
                    "h2" -> 1
                    "h3" -> 2
                    else -> 0
                }
            )
        }
    }

    companion object {
        private const val TAG = "MobiParser"
    }
}
