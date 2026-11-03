package com.rifters.riftedreader

import com.ibm.icu.text.BreakIterator
import com.ibm.icu.util.ULocale
import com.rifters.riftedreader.domain.tts.SentenceBoundaryFinder
import com.rifters.riftedreader.domain.tts.SentenceSplitter
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** Uses icu4j (same engine as android.icu) since android.icu is unavailable on the JVM. */
class SentenceSplitterTest {

    private val icu4jBoundaries: SentenceBoundaryFinder = { text, locale ->
        val base = ULocale.forLocale(locale)
        val it = BreakIterator.getSentenceInstance(
            ULocale(ULocale.setKeywordValue(base.name, "ss", "standard"))
        )
        it.setText(text)
        generateSequence { it.next().takeIf { b -> b != BreakIterator.DONE } }.toList()
    }

    private fun split(text: String) = SentenceSplitter.split(text, Locale.US, icu4jBoundaries)

    private fun assertSingle(text: String) = assertEquals(listOf(text), split(text))

    @Test fun doesNotSplitAfterDr() = assertSingle("I saw Dr. Smith yesterday.")
    @Test fun doesNotSplitAfterMr() = assertSingle("Mr. Jones left early.")
    @Test fun doesNotSplitAfterEg() = assertSingle("Use a tool, e.g. this one, to do it.")
    @Test fun doesNotSplitAfterIe() = assertSingle("Pick the first, i.e. that one, please.")
    @Test fun doesNotSplitAfterVol() = assertSingle("See vol. 3 for details.")
    @Test fun doesNotSplitAfterPp() = assertSingle("Refer to pp. 45-47 for more.")
    @Test fun doesNotSplitDecimal() = assertSingle("Pi is roughly 3.14 in value.")
    @Test fun doesNotSplitDecimalWithUnit() = assertSingle("Take 2.5 mg twice daily.")
    @Test fun doesNotSplitInitials() = assertSingle("The author J. R. R. Tolkien wrote it.")
    @Test fun doesNotSplitPrice() = assertSingle("It costs $3.99 at the store.")
    @Test fun doesNotSplitMidSentenceEllipsis() = assertSingle("Well... that was unexpected, wasn't it?")

    @Test fun splitsAtPlainBoundaries() {
        assertEquals(
            listOf("It was late.", "He went home!", "Was she there?"),
            split("It was late. He went home! Was she there?")
        )
    }

    @Test fun splitsAfterAbbreviationWhenNewSentenceStarts() {
        assertEquals(
            listOf("He works for Acme Inc.", "She works elsewhere."),
            split("He works for Acme Inc. She works elsewhere.")
        )
        assertEquals(
            listOf("We met Dr. Smith.", "Then we left."),
            split("We met Dr. Smith. Then we left.")
        )
    }

    @Test fun splitsAfterNumberEndingSentence() {
        assertEquals(listOf("It cost 5.", "Then he left."), split("It cost 5. Then he left."))
    }

    @Test fun splitsAcrossNewlines() {
        assertEquals(listOf("One.", "Two."), split("One.\nTwo."))
    }

    @Test fun blankInputYieldsNoSentences() {
        assertEquals(emptyList<String>(), split(""))
        assertEquals(emptyList<String>(), split("  \n\t "))
    }

    @Test fun resultsAreTrimmedAndNonEmpty() {
        val result = split("  First one.   Second one.  ")
        assertEquals(listOf("First one.", "Second one."), result)
    }

    @Test fun noBoundariesMeansWholeTextIsOneSentence() {
        val result = SentenceSplitter.split("  no punctuation here  ", Locale.US) { _, _ -> emptyList() }
        assertEquals(listOf("no punctuation here"), result)
    }

    @Test fun textAfterLastBoundaryIsKept() {
        val result = SentenceSplitter.split("Hello. World", Locale.US) { _, _ -> listOf(7) }
        assertEquals(listOf("Hello.", "World"), result)
    }

    @Test fun nullOrBlankLanguageTagFallsBackToDefaultLocale() {
        assertEquals(Locale.getDefault(), SentenceSplitter.resolveLocale(null))
        assertEquals(Locale.getDefault(), SentenceSplitter.resolveLocale(" "))
        assertEquals(Locale.FRENCH, SentenceSplitter.resolveLocale("fr"))
    }
}
