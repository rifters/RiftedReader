package com.rifters.riftedreader.domain.tts

import android.icu.text.BreakIterator
import android.icu.util.ULocale
import java.util.Locale

/** Returns sorted break offsets (including the final one) for the text. */
typealias SentenceBoundaryFinder = (text: String, locale: Locale) -> List<Int>

/**
 * Splits text into sentences using the platform ICU sentence iterator (native since API 24).
 * Results are trimmed and never contain empty entries.
 */
object SentenceSplitter {

    fun split(
        text: String,
        languageTag: String?,
        boundaryFinder: SentenceBoundaryFinder = ::icuBoundaries
    ): List<String> = split(text, resolveLocale(languageTag), boundaryFinder)

    fun split(
        text: String,
        locale: Locale,
        boundaryFinder: SentenceBoundaryFinder = ::icuBoundaries
    ): List<String> {
        if (text.isBlank()) return emptyList()

        val boundaries = boundaryFinder(text, locale)
        val sentences = ArrayList<String>()
        var start = 0
        for (end in boundaries) {
            if (end <= start || end > text.length) continue
            addTrimmed(sentences, text.substring(start, end))
            start = end
        }
        if (start < text.length) addTrimmed(sentences, text.substring(start))

        return if (sentences.isEmpty()) listOf(text.trim()) else mergeFalseBreaks(sentences)
    }

    // ICU's abbreviation data does not cover every honorific or personal initials,
    // so rejoin sentences that end with one and are followed by a name.
    private val trailingHonorificOrInitial = Regex(
        "(?:^|\\s)(?:(?:Dr|Mr|Mrs|Ms|Mx|Prof|Sr|Jr|St|Mt|Capt|Gen|Col|Lt|Sgt|Rev|Hon|Gov|Sen|Rep)|\\p{Lu})\\.$"
    )

    private fun mergeFalseBreaks(sentences: List<String>): List<String> {
        val merged = ArrayList<String>(sentences.size)
        for (sentence in sentences) {
            val previous = merged.lastOrNull()
            if (previous != null &&
                trailingHonorificOrInitial.containsMatchIn(previous) &&
                sentence.first().isUpperCase()
            ) {
                merged[merged.lastIndex] = "$previous $sentence"
            } else {
                merged.add(sentence)
            }
        }
        return merged
    }

    fun resolveLocale(languageTag: String?): Locale =
        languageTag?.takeIf { it.isNotBlank() }
            ?.let(Locale::forLanguageTag)
            ?.takeUnless { it == Locale.ROOT }
            ?: Locale.getDefault()

    private fun addTrimmed(out: MutableList<String>, raw: String) {
        val trimmed = raw.trim()
        if (trimmed.isNotEmpty()) out.add(trimmed)
    }

    private fun icuBoundaries(text: String, locale: Locale): List<Int> {
        // "ss=standard" enables ICU's abbreviation suppressions (e.g. "Dr.", "e.g.")
        // where locale data exists; other locales fall back to the plain rules.
        val base = ULocale.forLocale(locale)
        val withSuppressions = ULocale(ULocale.setKeywordValue(base.name, "ss", "standard"))
        val iterator = BreakIterator.getSentenceInstance(withSuppressions)
        iterator.setText(text)
        val result = ArrayList<Int>()
        var boundary = iterator.next()
        while (boundary != BreakIterator.DONE) {
            result.add(boundary)
            boundary = iterator.next()
        }
        return result
    }
}
