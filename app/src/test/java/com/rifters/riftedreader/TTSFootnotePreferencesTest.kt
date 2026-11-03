package com.rifters.riftedreader

import android.content.SharedPreferences
import com.rifters.riftedreader.data.preferences.TTSPreferences
import com.rifters.riftedreader.domain.tts.TtsChunkPreparer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TTSFootnotePreferencesTest {

    private class FakeSharedPreferences : SharedPreferences {
        private val map = mutableMapOf<String, Any>()

        override fun getAll(): MutableMap<String, *> = map
        override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") (map[key] as? MutableSet<String> ?: defValues)
        override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Number)?.toInt() ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Number)?.toLong() ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Number)?.toFloat() ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        inner class Editor : SharedPreferences.Editor {
            private val temp = mutableMapOf<String, Any?>()
            private var clearFlag = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor { temp[key ?: ""] = value; return this }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor { temp[key ?: ""] = values; return this }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor { temp[key ?: ""] = value; return this }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor { temp[key ?: ""] = value; return this }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor { temp[key ?: ""] = value; return this }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor { temp[key ?: ""] = value; return this }
            override fun remove(key: String?): SharedPreferences.Editor { temp[key ?: ""] = null; return this }
            override fun clear(): SharedPreferences.Editor { clearFlag = true; return this }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clearFlag) map.clear()
                for ((k, v) in temp) {
                    if (v == null) map.remove(k) else map[k] = v
                }
            }
        }
    }

    @Test
    fun testPreferenceDefaultsToOn() {
        val fakePrefs = FakeSharedPreferences()
        val ttsPrefs = TTSPreferences(fakePrefs)

        // Must default to ON (true)
        assertTrue("Read footnotes preference must default to ON", ttsPrefs.readFootnotes)
    }

    @Test
    fun testPreferenceCanBeToggled() {
        val fakePrefs = FakeSharedPreferences()
        val ttsPrefs = TTSPreferences(fakePrefs)

        ttsPrefs.readFootnotes = false
        assertFalse(ttsPrefs.readFootnotes)
        assertFalse(fakePrefs.getBoolean(TTSPreferences.KEY_READ_FOOTNOTES, true))

        ttsPrefs.readFootnotes = true
        assertTrue(ttsPrefs.readFootnotes)
        assertTrue(fakePrefs.getBoolean(TTSPreferences.KEY_READ_FOOTNOTES, false))
    }

    @Test
    fun testFootnoteSpansExcludedFromTtsChunksWhenPreferenceOff() {
        val html = """
            <p>Call me Ishmael.<span class="footnote">[Note: A biblical allusion.]</span></p>
            <p>Some years ago never mind how long precisely.<span class="footnote">[Note: Opening chapter.]</span></p>
        """.trimIndent()

        val chunks = TtsChunkPreparer.extractChunks(html, readFootnotes = false)

        assertEquals(2, chunks.size)
        // Footnote content must be excluded from spoken chunk text
        assertEquals("Call me Ishmael.", chunks[0].text)
        assertEquals("Some years ago never mind how long precisely.", chunks[1].text)
        assertFalse(chunks[0].text.contains("Note:"))
        assertFalse(chunks[1].text.contains("biblical"))
    }

    @Test
    fun testFootnoteSpansIncludedInTtsChunksWhenPreferenceOn() {
        val html = """
            <p>Call me Ishmael.<span class="footnote"> [Note: A biblical allusion.]</span></p>
        """.trimIndent()

        val chunks = TtsChunkPreparer.extractChunks(html, readFootnotes = true)

        assertEquals(1, chunks.size)
        // Footnote content must be included in spoken chunk text at end-of-paragraph position
        assertTrue(chunks[0].text.contains("Call me Ishmael."))
        assertTrue(chunks[0].text.contains("[Note: A biblical allusion.]"))
    }

    @Test
    fun testStandaloneFootnoteBlockSkippedWhenPreferenceOff() {
        val html = """
            <p>Regular paragraph.</p>
            <p class="footnote">[Note: Standalone footnote block]</p>
        """.trimIndent()

        val chunksOff = TtsChunkPreparer.extractChunks(html, readFootnotes = false)
        assertEquals(1, chunksOff.size)
        assertEquals("Regular paragraph.", chunksOff[0].text)

        val chunksOn = TtsChunkPreparer.extractChunks(html, readFootnotes = true)
        assertEquals(2, chunksOn.size)
        assertEquals("[Note: Standalone footnote block]", chunksOn[1].text)
    }

    @Test
    fun testFilterFootnotesFromPlainText() {
        val textWithNote = "A simple sentence. [Note: Footnote explanation]"
        val filtered = TtsChunkPreparer.filterFootnotesFromText(textWithNote)
        assertEquals("A simple sentence.", filtered)
    }
}
