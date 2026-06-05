package com.rifters.riftedreader.domain.parser

import java.io.ByteArrayOutputStream

internal class HuffCdicDecompressor(
    huffRecord: ByteArray,
    cdicRecords: List<ByteArray>
) {

    private data class CacheEntry(
        val codeLength: Int,
        val isTerminal: Boolean,
        val maxCode: Long
    )

    private data class DictionaryEntry(
        var data: ByteArray,
        var isDecoded: Boolean
    )

    private val cacheTable: List<CacheEntry>
    private val minCode = LongArray(33)
    private val maxCode = LongArray(33)
    private val dictionary = mutableListOf<DictionaryEntry>()

    init {
        cacheTable = parseHuffRecord(huffRecord)
        cdicRecords.forEach(::parseCdicRecord)
    }

    fun decompress(input: ByteArray): ByteArray {
        if (input.isEmpty()) return ByteArray(0)

        var bitsLeft = input.size * 8
        val padded = input + ByteArray(8)
        var windowOffset = 0
        var window = readUInt64(padded, 0)
        var shift = 32
        val output = ByteArrayOutputStream(input.size * 2)

        while (true) {
            if (shift <= 0) {
                windowOffset += 4
                window = readUInt64(padded, windowOffset)
                shift += 32
            }

            val code = (window ushr shift) and UINT_MASK
            val cached = cacheTable[(code ushr 24).toInt()]
            var codeLength = cached.codeLength
            var resolvedMaxCode = cached.maxCode

            if (!cached.isTerminal) {
                while (codeLength < minCode.size && code < minCode[codeLength]) {
                    codeLength++
                }
                require(codeLength in 1..32) { "Invalid HUFF code length" }
                resolvedMaxCode = maxCode[codeLength]
            }

            shift -= codeLength
            bitsLeft -= codeLength
            if (bitsLeft < 0) break

            val dictionaryIndex = ((resolvedMaxCode - code) ushr (32 - codeLength)).toInt()
            output.write(expandDictionaryEntry(dictionaryIndex, mutableSetOf()))
        }

        return output.toByteArray()
    }

    private fun parseHuffRecord(huffRecord: ByteArray): List<CacheEntry> {
        require(hasMagic(huffRecord, "HUFF")) { "Invalid HUFF header" }
        require(huffRecord.size >= 16) { "Truncated HUFF header" }

        val cacheOffset = readUInt32(huffRecord, 8).toInt()
        val baseOffset = readUInt32(huffRecord, 12).toInt()
        require(cacheOffset >= 0 && cacheOffset + (256 * 4) <= huffRecord.size) { "Invalid HUFF cache table" }
        require(baseOffset >= 0 && baseOffset + (64 * 4) <= huffRecord.size) { "Invalid HUFF base table" }

        for (codeLength in 1..32) {
            val baseIndex = baseOffset + (codeLength - 1) * 8
            val minCodeRaw = readUInt32(huffRecord, baseIndex)
            val maxCodeRaw = readUInt32(huffRecord, baseIndex + 4)
            minCode[codeLength] = (minCodeRaw shl (32 - codeLength)) and UINT_MASK
            maxCode[codeLength] = normalizeMaxCode(maxCodeRaw, codeLength)
        }

        return List(256) { index ->
            val rawValue = readUInt32(huffRecord, cacheOffset + index * 4)
            val codeLength = (rawValue and 0x1FL).toInt()
            require(codeLength in 1..32) { "Invalid HUFF cache entry" }
            val isTerminal = rawValue and 0x80L != 0L
            require(codeLength > 8 || isTerminal) { "Short HUFF code must be terminal" }
            CacheEntry(
                codeLength = codeLength,
                isTerminal = isTerminal,
                maxCode = normalizeMaxCode(rawValue ushr 8, codeLength)
            )
        }
    }

    private fun parseCdicRecord(cdicRecord: ByteArray) {
        require(hasMagic(cdicRecord, "CDIC")) { "Invalid CDIC header" }
        require(cdicRecord.size >= 16) { "Truncated CDIC header" }

        val phrases = readUInt32(cdicRecord, 8).toInt()
        val codeWidth = readUInt32(cdicRecord, 12).toInt()
        val remaining = phrases - dictionary.size
        if (remaining <= 0) return

        val entryCount = minOf(maxEntriesForWidth(codeWidth), remaining)
        require(16 + entryCount * 2 <= cdicRecord.size) { "Truncated CDIC offsets" }

        repeat(entryCount) { index ->
            val entryOffset = 16 + readUInt16(cdicRecord, 16 + index * 2)
            require(entryOffset + 2 <= cdicRecord.size) { "Invalid CDIC entry offset" }

            val entryLength = readUInt16(cdicRecord, entryOffset)
            val isDirect = entryLength and 0x8000 != 0
            val payloadLength = entryLength and 0x7FFF
            val payloadStart = entryOffset + 2
            val payloadEnd = payloadStart + payloadLength
            require(payloadEnd <= cdicRecord.size) { "Invalid CDIC entry length" }

            dictionary += DictionaryEntry(
                data = cdicRecord.copyOfRange(payloadStart, payloadEnd),
                isDecoded = isDirect
            )
        }
    }

    private fun expandDictionaryEntry(index: Int, activeEntries: MutableSet<Int>): ByteArray {
        val entry = dictionary.getOrNull(index) ?: throw IllegalArgumentException("Invalid CDIC dictionary index")
        if (entry.isDecoded) return entry.data
        require(activeEntries.add(index)) { "Recursive CDIC loop detected" }

        val decoded = decompress(entry.data)
        activeEntries.remove(index)
        entry.data = decoded
        entry.isDecoded = true
        return decoded
    }

    private fun maxEntriesForWidth(codeWidth: Int): Int {
        require(codeWidth >= 0) { "Invalid CDIC code width" }
        return if (codeWidth >= Int.SIZE_BITS - 1) Int.MAX_VALUE else 1 shl codeWidth
    }

    private fun normalizeMaxCode(rawMaxCode: Long, codeLength: Int): Long {
        return (((rawMaxCode + 1L) shl (32 - codeLength)) - 1L) and UINT_MASK
    }

    private fun hasMagic(bytes: ByteArray, magic: String): Boolean {
        return bytes.size >= 4 && magic.indices.all { bytes[it] == magic[it].code.toByte() }
    }

    private fun readUInt16(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 8) or
            (bytes[offset + 1].toInt() and 0xFF)
    }

    private fun readUInt32(bytes: ByteArray, offset: Int): Long {
        return ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)
    }

    private fun readUInt64(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        repeat(8) { index ->
            value = (value shl 8) or (bytes[offset + index].toLong() and 0xFF)
        }
        return value
    }

    private companion object {
        const val UINT_MASK = 0xFFFF_FFFFL
    }
}
