package com.rifters.riftedreader.domain.parser

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MobiParserTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val parser = MobiParser()

    @Test
    fun parsesPalmDocMetadataPagesAndToc() = runBlocking {
        val file = createMobiFile(
            name = "sample.mobi",
            compression = 2,
            title = "Sample MOBI",
            author = "Test Author",
            textRecords = listOf(
                "<html><body><h1>Intro</h1><p>Hello world.</p>",
                "<h2>Second</h2><p>Next part.</p>",
                "<h3>Third</h3><p>Final part.</p></body></html>"
            )
        )

        val metadata = parser.extractMetadata(file)
        assertEquals("Sample MOBI", metadata.title)
        assertEquals("Test Author", metadata.author)
        assertEquals("MOBI", metadata.format)
        assertEquals(3, metadata.totalPages)

        assertEquals(3, parser.getPageCount(file))

        val page0 = parser.getPageContent(file, 0)
        assertEquals("Intro", page0.title)
        assertTrue(page0.text.contains("Hello world."))
        assertTrue(page0.html?.contains("<h1>Intro</h1>") == true)

        val page1 = parser.getPageContent(file, 1)
        assertEquals("Second", page1.title)
        assertTrue(page1.text.contains("Next part."))
        assertTrue(page1.html?.contains("<h2>Second</h2>") == true)

        val page2 = parser.getPageContent(file, 2)
        assertEquals("Third", page2.title)
        assertTrue(page2.text.contains("Final part."))
        assertTrue(page2.html?.contains("<h3>Third</h3>") == true)

        val toc = parser.getTableOfContents(file)
        assertEquals(listOf("Intro", "Second", "Third"), toc.map { it.title })
        assertEquals(listOf(0, 1, 2), toc.map { it.pageNumber })
        assertEquals(listOf(0, 1, 2), toc.map { it.level })

        assertEquals(PageContent.EMPTY, parser.getPageContent(file, 3))
    }

    private fun createMobiFile(
        name: String,
        compression: Int,
        title: String,
        author: String,
        textRecords: List<String>
    ): File {
        val file = tempFolder.newFile(name)
        file.writeBytes(buildMobiBytes(compression, title, author, textRecords))
        return file
    }

    private fun buildMobiBytes(
        compression: Int,
        title: String,
        author: String,
        textRecords: List<String>
    ): ByteArray {
        val headerLength = 132
        val authorBytes = author.toByteArray(Charsets.UTF_8)
        val titleBytes = title.toByteArray(Charsets.UTF_8)
        val exthLength = 12 + 8 + authorBytes.size + 8 + titleBytes.size
        val record0 = ByteArray(16 + headerLength + exthLength)
        val record0Buffer = ByteBuffer.wrap(record0).order(ByteOrder.BIG_ENDIAN)

        record0Buffer.putShort(0, compression.toShort())
        record0Buffer.putShort(8, textRecords.size.toShort())
        record0[16] = 'M'.code.toByte()
        record0[17] = 'O'.code.toByte()
        record0[18] = 'B'.code.toByte()
        record0[19] = 'I'.code.toByte()
        record0Buffer.putInt(20, headerLength)
        record0Buffer.putInt(28, 65001)
        record0Buffer.putInt(96, 0x40)
        record0Buffer.putInt(132, textRecords.size + 1)

        val exthOffset = 16 + headerLength
        record0[exthOffset] = 'E'.code.toByte()
        record0[exthOffset + 1] = 'X'.code.toByte()
        record0[exthOffset + 2] = 'T'.code.toByte()
        record0[exthOffset + 3] = 'H'.code.toByte()
        record0Buffer.putInt(exthOffset + 4, exthLength)
        record0Buffer.putInt(exthOffset + 8, 2)

        var exthPos = exthOffset + 12
        writeExthRecord(record0, exthPos, 100, authorBytes)
        exthPos += 8 + authorBytes.size
        writeExthRecord(record0, exthPos, 503, titleBytes)

        val textRecordBytes = textRecords.map { it.toByteArray(Charsets.US_ASCII) }
        val numRecords = 1 + textRecordBytes.size
        val palmHeaderSize = 78 + numRecords * 8

        val offsets = mutableListOf<Int>()
        var nextOffset = palmHeaderSize
        offsets += nextOffset
        nextOffset += record0.size
        textRecordBytes.forEach { bytes ->
            offsets += nextOffset
            nextOffset += bytes.size
        }

        val raw = ByteArray(nextOffset)
        val rawBuffer = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
        rawBuffer.putShort(76, numRecords.toShort())
        offsets.forEachIndexed { index, offset ->
            rawBuffer.putInt(78 + index * 8, offset)
        }

        System.arraycopy(record0, 0, raw, offsets[0], record0.size)
        textRecordBytes.forEachIndexed { index, bytes ->
            System.arraycopy(bytes, 0, raw, offsets[index + 1], bytes.size)
        }

        return raw
    }

    private fun writeExthRecord(target: ByteArray, offset: Int, type: Int, data: ByteArray) {
        val buffer = ByteBuffer.wrap(target).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(offset, type)
        buffer.putInt(offset + 4, 8 + data.size)
        System.arraycopy(data, 0, target, offset + 8, data.size)
    }
}
