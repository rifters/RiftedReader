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

    fun loadRulesFromText(text: String): Int {
        clearRules()
        var loaded = 0
        
        TTSReplacementParser.parseText(text).forEach { parsedRule ->
            if (addParsedRule(parsedRule)) {
                loaded++
            }
        }
        
        return loaded
    }

    private fun addParsedRule(parsed: ParsedRule): Boolean {
        return try {
            when (parsed.type) {
                RuleType.SIMPLE -> {
                    simpleRules.add(
                        TTSReplacementRule.SimpleRule(
                            isEnabled = parsed.enabled,
                            pattern = parsed.pattern,
                            replacement = parsed.replacement
                        )
                    )
                }
                RuleType.REGEX -> {
                    val regex = Regex(parsed.pattern)
                    regexRules.add(
                        TTSReplacementRule.RegexRule(
                            isEnabled = parsed.enabled,
                            pattern = parsed.pattern,
                            replacement = parsed.replacement,
                            regex = regex
                        )
                    )
                }
                RuleType.COMMAND -> {
                    val command = parsed.replacement.toCommandOrNull()
                    if (command != null) {
                        commandRules.add(
                            TTSReplacementRule.CommandRule(
                                isEnabled = parsed.enabled,
                                pattern = parsed.pattern,
                                replacement = parsed.replacement,
                                command = command
                            )
                        )
                    } else {
                        Log.w(TAG, "Unknown command: ${parsed.replacement}")
                        return false
                    }
                }
            }
            true
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to add rule ${parsed.pattern} -> ${parsed.replacement}", ex)
            false
        }
    }

    fun exportRulesToText(): String {
        val parsedRules = getAllRules().map { rule ->
            ParsedRule(
                pattern = rule.pattern,
                replacement = rule.replacement,
                type = when (rule) {
                    is TTSReplacementRule.SimpleRule -> RuleType.SIMPLE
                    is TTSReplacementRule.RegexRule -> RuleType.REGEX
                    is TTSReplacementRule.CommandRule -> RuleType.COMMAND
                },
                enabled = rule.isEnabled
            )
        }
        return TTSReplacementParser.formatRules(parsedRules)
    }

    fun getAllRules(): List<TTSReplacementRule> = simpleRules + regexRules + commandRules

    fun clearRules() {
        simpleRules.clear()
        regexRules.clear()
        commandRules.clear()
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
        
        private const val CMD_PAUSE = "ttsPAUSE"
        private const val CMD_STOP = "ttsSTOP"
        private const val CMD_NEXT = "ttsNEXT"
        private const val CMD_SKIP = "ttsSKIP"
    }
}
