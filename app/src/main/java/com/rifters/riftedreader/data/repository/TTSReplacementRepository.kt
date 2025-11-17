package com.rifters.riftedreader.data.repository

import android.content.Context
import com.rifters.riftedreader.data.preferences.TTSPreferences
import com.rifters.riftedreader.domain.tts.ParsedRule
import com.rifters.riftedreader.domain.tts.RuleType
import com.rifters.riftedreader.domain.tts.TTSReplacementParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class TTSReplacementUiType { SIMPLE, REGEX, COMMAND }

data class TTSReplacementUiItem(
    val id: Long,
    val pattern: String,
    val replacement: String,
    val type: TTSReplacementUiType,
    val enabled: Boolean
)

class TTSReplacementRepository(context: Context) {

    private val preferences = TTSPreferences(context)

    suspend fun loadRules(): List<TTSReplacementUiItem> = withContext(Dispatchers.IO) {
        val file = preferences.getReplacementRulesFile()
        if (!file.exists()) {
            return@withContext emptyList()
        }
        val lines = file.readLines()
        TTSReplacementParser.parseLines(lines).mapIndexed { index, parsedRule ->
            parsedRule.toUiItem(index.toLong())
        }
    }

    suspend fun saveRules(items: List<TTSReplacementUiItem>) = withContext(Dispatchers.IO) {
        val parsedRules = items.map { it.toParsedRule() }
        val text = TTSReplacementParser.formatRules(parsedRules)
        preferences.saveReplacementRules(text)
    }

    private fun ParsedRule.toUiItem(id: Long) = TTSReplacementUiItem(
        id = id,
        pattern = pattern,
        replacement = replacement,
        type = when (type) {
            RuleType.SIMPLE -> TTSReplacementUiType.SIMPLE
            RuleType.REGEX -> TTSReplacementUiType.REGEX
            RuleType.COMMAND -> TTSReplacementUiType.COMMAND
        },
        enabled = enabled
    )

    private fun TTSReplacementUiItem.toParsedRule() = ParsedRule(
        pattern = pattern,
        replacement = replacement,
        type = when (type) {
            TTSReplacementUiType.SIMPLE -> RuleType.SIMPLE
            TTSReplacementUiType.REGEX -> RuleType.REGEX
            TTSReplacementUiType.COMMAND -> RuleType.COMMAND
        },
        enabled = enabled
    )
}
