package com.rifters.riftedreader.domain.tts

import android.util.Log

class TTSReplacementEngine {
    private val simpleRules = mutableListOf<TTSReplacementRule.SimpleRule>()
    private val regexRules = mutableListOf<TTSReplacementRule.RegexRule>()
    private val commandRules = mutableListOf<TTSReplacementRule.CommandRule>()

    fun applyReplacements(text: String): TTSReplacementResult {
        var result = text
        var issuedCommand: TTSCommand? = null

        commandRules.forEach { rule ->
            if (!rule.isEnabled) return@forEach
            if (result.contains(rule.pattern, ignoreCase = true)) {
                issuedCommand = rule.command
                when (rule.command) {
                    TTSCommand.SKIP -> return TTSReplacementResult("", TTSCommand.SKIP)
                    TTSCommand.STOP -> return TTSReplacementResult(result, TTSCommand.STOP)
                    TTSCommand.NEXT -> return TTSReplacementResult(result, TTSCommand.NEXT)
                    TTSCommand.PAUSE -> {
                        result = result.replace(rule.pattern, "", ignoreCase = true)
                    }
                }
            }
        }

        simpleRules.forEach { rule ->
            if (!rule.isEnabled) return@forEach
            result = result.replace(rule.pattern, rule.replacement)
        }

        regexRules.forEach { rule ->
            if (!rule.isEnabled) return@forEach
            try {
                result = rule.regex.replace(result, rule.replacement)
            } catch (ex: Exception) {
                Log.e(TAG, "Failed to apply regex rule ${rule.pattern}", ex)
            }
        }

        return TTSReplacementResult(result, issuedCommand)
    }

    fun addRuleFromText(pattern: String, replacement: String): Boolean {
        return try {
            val enabled = !pattern.startsWith(DISABLED_PREFIX) && !pattern.matches(DISABLED_PATTERN)
            val cleanPattern = if (pattern.startsWith(DISABLED_PREFIX)) pattern.drop(1) else pattern

            when (val command = replacement.toCommandOrNull()) {
                null -> {
                    if (cleanPattern.startsWith(REGEX_PREFIX)) {
                        val regex = Regex(cleanPattern.drop(1))
                        regexRules.add(
                            TTSReplacementRule.RegexRule(
                                isEnabled = enabled,
                                pattern = cleanPattern,
                                replacement = replacement,
                                regex = regex
                            )
                        )
                    } else {
                        simpleRules.add(
                            TTSReplacementRule.SimpleRule(
                                isEnabled = enabled,
                                pattern = cleanPattern,
                                replacement = replacement
                            )
                        )
                    }
                }
                else -> {
                    commandRules.add(
                        TTSReplacementRule.CommandRule(
                            isEnabled = enabled,
                            pattern = cleanPattern,
                            replacement = replacement,
                            command = command
                        )
                    )
                }
            }
            true
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to add rule $pattern -> $replacement", ex)
            false
        }
    }

    fun loadRulesFromText(text: String): Int {
        clearRules()
        var loaded = 0
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith(COMMENT_PREFIX) }
            .forEach { line ->
                parseRuleLine(line)?.let { (pattern, replacement) ->
                    if (addRuleFromText(pattern, replacement)) {
                        loaded++
                    }
                }
            }
        return loaded
    }

    fun exportRulesToText(): String {
        val builder = StringBuilder()
        getAllRules().forEach { rule ->
            val prefix = if (!rule.isEnabled) DISABLED_PREFIX else ""
            builder.append('"')
                .append(prefix)
                .append(rule.pattern)
                .append("""" """)
                .append(rule.replacement)
                .append('"')
                .append('\n')
        }
        return builder.toString()
    }

    fun getAllRules(): List<TTSReplacementRule> = simpleRules + regexRules + commandRules

    fun clearRules() {
        simpleRules.clear()
        regexRules.clear()
        commandRules.clear()
    }

    private fun parseRuleLine(line: String): Pair<String, String>? {
        val matcher = RULE_REGEX.find(line) ?: return null
        val (pattern, replacement) = matcher.destructured
        return pattern to replacement
    }

    private fun String.toCommandOrNull(): TTSCommand? = when (this) {
        CMD_PAUSE -> TTSCommand.PAUSE
        CMD_STOP -> TTSCommand.STOP
        CMD_NEXT -> TTSCommand.NEXT
        CMD_SKIP -> TTSCommand.SKIP
        else -> null
    }

    companion object {
        private const val TAG = "TTSReplacementEngine"
        private const val REGEX_PREFIX = "*"
        private const val DISABLED_PREFIX = "#"
        private const val COMMENT_PREFIX = "//"

        private const val CMD_PAUSE = "ttsPAUSE"
        private const val CMD_STOP = "ttsSTOP"
        private const val CMD_NEXT = "ttsNEXT"
        private const val CMD_SKIP = "ttsSKIP"

        private val DISABLED_PATTERN = Regex("\\w+\\d+")
        private val RULE_REGEX = Regex("\"([^\"]+)\"\\s+\"([^\"]+)\"")
    }
}
