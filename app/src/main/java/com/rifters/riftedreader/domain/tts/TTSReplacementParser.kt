package com.rifters.riftedreader.domain.tts

/**
 * Shared parser for TTS replacement rules. Handles parsing and formatting
 * of the text-based rule format used by both the UI and the runtime engine.
 * 
 * Format: "pattern" "replacement"
 * - Lines starting with # are disabled
 * - Lines starting with // are comments (ignored)
 * - Patterns starting with * are regex patterns
 * - Special replacements (ttsPAUSE, ttsSKIP, ttsSTOP, ttsNEXT) are commands
 * - Quotes within patterns/replacements are escaped with \"
 */
object TTSReplacementParser {
    
    private val RULE_PATTERN = Regex("""\"((?:[^\\"]|\\.)*)\"\\s+\"((?:[^\\"]|\\.)*)\"""")
    private const val REGEX_PREFIX = "*"
    private const val DISABLED_PREFIX = "#"
    private const val COMMENT_PREFIX = "//"
    
    private val COMMANDS = setOf("ttsPAUSE", "ttsSKIP", "ttsSTOP", "ttsNEXT")
    
    /**
     * Parse a single line into a rule, or null if the line is invalid/comment
     */
    fun parseLine(line: String): ParsedRule? {
        val trimmed = line.trim()
        if (trimmed.isBlank() || trimmed.startsWith(COMMENT_PREFIX)) {
            return null
        }
        
        val isDisabled = trimmed.startsWith(DISABLED_PREFIX)
        val effective = if (isDisabled) trimmed.drop(1).trimStart() else trimmed
        
        val match = RULE_PATTERN.find(effective) ?: return null
        val rawPattern = match.groupValues[1].replace("\\\"", "\"")
        val replacement = match.groupValues[2].replace("\\\"", "\"")
        
        val type = when {
            replacement in COMMANDS -> RuleType.COMMAND
            rawPattern.startsWith(REGEX_PREFIX) -> RuleType.REGEX
            else -> RuleType.SIMPLE
        }
        
        val pattern = if (type == RuleType.REGEX) {
            rawPattern.removePrefix(REGEX_PREFIX)
        } else {
            rawPattern
        }
        
        return ParsedRule(
            pattern = pattern,
            replacement = replacement,
            type = type,
            enabled = !isDisabled
        )
    }
    
    /**
     * Parse multiple lines into a list of rules
     */
    fun parseLines(lines: List<String>): List<ParsedRule> {
        return lines.mapNotNull { parseLine(it) }
    }
    
    /**
     * Parse text into a list of rules
     */
    fun parseText(text: String): List<ParsedRule> {
        return parseLines(text.lines())
    }
    
    /**
     * Format a rule back to text format
     */
    fun formatRule(rule: ParsedRule): String {
        val patternToken = when (rule.type) {
            RuleType.REGEX -> "$REGEX_PREFIX${rule.pattern}"
            else -> rule.pattern
        }
        val prefix = if (rule.enabled) "" else DISABLED_PREFIX
        val patternEscaped = patternToken.replace("\"", "\\\"")
        val replacementEscaped = rule.replacement.replace("\"", "\\\"")
        return "${prefix}\"${patternEscaped}\" \"${replacementEscaped}\""
    }
    
    /**
     * Format multiple rules to text
     */
    fun formatRules(rules: List<ParsedRule>): String {
        return rules.joinToString(separator = "\n") { formatRule(it) }
    }
}

/**
 * Type of replacement rule
 */
enum class RuleType {
    SIMPLE,   // Plain text replacement
    REGEX,    // Regular expression replacement
    COMMAND   // TTS command (PAUSE, SKIP, STOP, NEXT)
}

/**
 * Parsed rule data
 */
data class ParsedRule(
    val pattern: String,
    val replacement: String,
    val type: RuleType,
    val enabled: Boolean
)
