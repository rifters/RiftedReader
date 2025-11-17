package com.rifters.riftedreader.data.repository

import android.content.Context
import com.rifters.riftedreader.data.preferences.TTSPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
private val RULE_PATTERN = Regex("""\"((?:[^\\"]|\\.)*)\"\\s+\"((?:[^\\"]|\\.)*)\"""")

internal enum class TTSReplacementUiType { SIMPLE, REGEX, COMMAND }

internal data class TTSReplacementUiItem(
    val id: Long,
    val pattern: String,
    val replacement: String,
    val type: TTSReplacementUiType,
    val enabled: Boolean
)

internal class TTSReplacementRepository(context: Context) {

    private val preferences = TTSPreferences(context)

    suspend fun loadRules(): List<TTSReplacementUiItem> = withContext(Dispatchers.IO) {
        val file = preferences.getReplacementRulesFile()
        if (!file.exists()) {
            return@withContext emptyList()
        }
        val lines = file.readLines()
        parseLines(lines)
    }

    suspend fun saveRules(items: List<TTSReplacementUiItem>) = withContext(Dispatchers.IO) {
        val text = items.joinToString(separator = "\n") { item ->
            val patternToken = when (item.type) {
                TTSReplacementUiType.REGEX -> "*${item.pattern}"
                else -> item.pattern
            }
            val prefix = if (item.enabled) "" else "#"
            val patternEscaped = patternToken.replace("\"", "\\\"")
            val replacementEscaped = item.replacement.replace("\"", "\\\"")
            "${prefix}\"${patternEscaped}\" \"${replacementEscaped}\""
        }
        preferences.saveReplacementRules(text)
    }

    private fun parseLines(lines: List<String>): List<TTSReplacementUiItem> {
        val items = mutableListOf<TTSReplacementUiItem>()
        var nextId = 0L
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isBlank() || trimmed.startsWith("//")) {
                continue
            }
            val isDisabled = trimmed.startsWith("#")
            val effective = if (isDisabled) trimmed.drop(1).trimStart() else trimmed
            val match = RULE_PATTERN.find(effective) ?: continue
            val rawPattern = match.groupValues[1].replace("\\\"", "\"")
            val replacement = match.groupValues[2].replace("\\\"", "\"")
            val type = when {
                replacement in COMMANDS -> TTSReplacementUiType.COMMAND
                rawPattern.startsWith("*") -> TTSReplacementUiType.REGEX
                else -> TTSReplacementUiType.SIMPLE
            }
            val pattern = if (type == TTSReplacementUiType.REGEX) rawPattern.removePrefix("*") else rawPattern
            items += TTSReplacementUiItem(
                id = nextId++,
                pattern = pattern,
                replacement = replacement,
                type = type,
                enabled = !isDisabled
            )
        }
        return items
    }

    companion object {
        private val COMMANDS = setOf("ttsPAUSE", "ttsSKIP", "ttsSTOP", "ttsNEXT")
    }
}
