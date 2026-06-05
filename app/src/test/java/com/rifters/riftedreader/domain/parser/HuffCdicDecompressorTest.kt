package com.rifters.riftedreader.domain.parser

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class HuffCdicDecompressorTest {

    @Test
    fun decompressesSingleEntryFixture() {
        val fixture = HuffCdicTestFixtures.singleEntryFixture("Hello HUFF")
        val decompressor = HuffCdicDecompressor(fixture.huffRecord, listOf(fixture.cdicRecord))

        assertArrayEquals("Hello HUFF".toByteArray(Charsets.UTF_8), decompressor.decompress(fixture.compressedRecord))
    }

    @Test
    fun returnsEmptyForEmptyInput() {
        val fixture = HuffCdicTestFixtures.singleEntryFixture("ignored")
        val decompressor = HuffCdicDecompressor(fixture.huffRecord, listOf(fixture.cdicRecord))

        assertEquals(0, decompressor.decompress(ByteArray(0)).size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMalformedHuffMagic() {
        val fixture = HuffCdicTestFixtures.singleEntryFixture("ignored")
        HuffCdicDecompressor(HuffCdicTestFixtures.withInvalidMagic(fixture.huffRecord), listOf(fixture.cdicRecord))
    }

    @Test
    fun expandsRecursiveDictionaryEntry() {
        val fixture = HuffCdicTestFixtures.recursiveFixture("A")
        val decompressor = HuffCdicDecompressor(fixture.huffRecord, listOf(fixture.cdicRecord))

        assertArrayEquals("A".toByteArray(Charsets.UTF_8), decompressor.decompress(fixture.compressedRecord))
    }
}

internal object HuffCdicTestFixtures {

    data class Fixture(
        val huffRecord: ByteArray,
        val cdicRecord: ByteArray,
        val compressedRecord: ByteArray
    )

    fun singleEntryFixture(output: String): Fixture {
        val outputBytes = output.toByteArray(Charsets.UTF_8)
        return Fixture(
            huffRecord = buildHuffRecord(mapOf(0x00 to 0)),
            cdicRecord = buildCdicRecord(
                phrases = 1,
                codeWidth = 0,
                entries = listOf((0x8000 or outputBytes.size) to outputBytes)
            ),
            compressedRecord = byteArrayOf(0x00)
        )
    }

    fun recursiveFixture(output: String): Fixture {
        val outputBytes = output.toByteArray(Charsets.UTF_8)
        return Fixture(
            huffRecord = buildHuffRecord(mapOf(0x00 to 0, 0x80 to 1)),
            cdicRecord = buildCdicRecord(
                phrases = 2,
                codeWidth = 1,
                entries = listOf(
                    (0x8000 or outputBytes.size) to outputBytes,
                    1 to byteArrayOf(0x00)
                )
            ),
            compressedRecord = byteArrayOf(0x80.toByte())
        )
    }

    fun withInvalidMagic(record: ByteArray): ByteArray {
        return "BAD!".toByteArray(Charsets.US_ASCII) + record.copyOfRange(4, record.size)
    }

    private fun buildHuffRecord(indexByTopByte: Map<Int, Int>): ByteArray {
        val cacheOffset = 16
        val baseOffset = cacheOffset + 256 * 4
        val bytes = ByteArray(baseOffset + 64 * 4)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

        bytes[0] = 'H'.code.toByte()
        bytes[1] = 'U'.code.toByte()
        bytes[2] = 'F'.code.toByte()
        bytes[3] = 'F'.code.toByte()
        buffer.putInt(4, 0x18)
        buffer.putInt(8, cacheOffset)
        buffer.putInt(12, baseOffset)

        repeat(256) { topByte ->
            val entryIndex = indexByTopByte[topByte] ?: 0
            val rawMaxCode = if (entryIndex == 0) topByte else topByte + entryIndex
            buffer.putInt(cacheOffset + topByte * 4, (rawMaxCode shl 8) or 0x88)
        }

        return bytes
    }

    private fun buildCdicRecord(
        phrases: Int,
        codeWidth: Int,
        entries: List<Pair<Int, ByteArray>>
    ): ByteArray {
        val offsetsSize = entries.size * 2
        val entryBuffer = ByteArrayOutputStream()
        val offsets = mutableListOf<Int>()

        entries.forEach { (lengthWord, payload) ->
            offsets += offsetsSize + entryBuffer.size()
            entryBuffer.write(lengthWord shr 8)
            entryBuffer.write(lengthWord and 0xFF)
            entryBuffer.write(payload)
        }

        val bytes = ByteArray(16 + offsetsSize + entryBuffer.size())
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        bytes[0] = 'C'.code.toByte()
        bytes[1] = 'D'.code.toByte()
        bytes[2] = 'I'.code.toByte()
        bytes[3] = 'C'.code.toByte()
        buffer.putInt(4, 0x10)
        buffer.putInt(8, phrases)
        buffer.putInt(12, codeWidth)
        offsets.forEachIndexed { index, offset ->
            buffer.putShort(16 + index * 2, offset.toShort())
        }
        val payload = entryBuffer.toByteArray()
        System.arraycopy(payload, 0, bytes, 16 + offsetsSize, payload.size)
        return bytes
    }
}
