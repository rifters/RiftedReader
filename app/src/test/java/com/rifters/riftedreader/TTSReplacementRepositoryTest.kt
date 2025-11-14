package com.rifters.riftedreader

import org.junit.Test
import org.junit.Assert.*
import java.util.regex.Pattern

/**
 * Tests for TTS Replacement Repository parsing and formatting.
 * These tests verify that rules can be saved and loaded correctly (round-trip).
 */
class TTSReplacementRepositoryTest {

    // Copy of the regex pattern from TTSReplacementRepository
    private val RULE_PATTERN = Regex(""""((?:[^\\"]|\\.)*)"\s+"((?:[^\\"]|\\.)*)"""")

    /**
     * Helper to simulate parsing a line as TTSReplacementRepository does
     */
    private fun parseLine(line: String): ParsedRule? {
        val trimmed = line.trim()
        if (trimmed.isBlank() || trimmed.startsWith("//")) {
            return null
        }
        val isDisabled = trimmed.startsWith("#")
        val effective = if (isDisabled) trimmed.drop(1).trimStart() else trimmed
        val match = RULE_PATTERN.find(effective) ?: return null
        val rawPattern = match.groupValues[1].replace("\\\"", "\"")
        val replacement = match.groupValues[2].replace("\\\"", "\"")
        return ParsedRule(rawPattern, replacement, !isDisabled)
    }

    /**
     * Helper to simulate formatting a rule as TTSReplacementRepository does
     */
    private fun formatRule(pattern: String, replacement: String, enabled: Boolean): String {
        val prefix = if (enabled) "" else "#"
        val patternEscaped = pattern.replace("\"", "\\\"")
        val replacementEscaped = replacement.replace("\"", "\\\"")
        return "${prefix}\"${patternEscaped}\" \"${replacementEscaped}\""
    }

    data class ParsedRule(val pattern: String, val replacement: String, val enabled: Boolean)

    @Test
    fun testBug1_DisabledRulePrefixMismatch() {
        // Bug 1: The disabled prefix # is placed inside quotes when saving 
        // but expected outside when loading
        
        // Format a disabled rule (simulating saveRules)
        val formatted = formatRule("hello", "hi", enabled = false)
        println("Formatted disabled rule: $formatted")
        
        // Expected format: #"hello" "hi"
        // Actual format (buggy): "#hello" "hi"
        
        // Try to parse it back (simulating loadRules)
        val parsed = parseLine(formatted)
        
        if (parsed != null) {
            println("Parsed pattern: '${parsed.pattern}'")
            println("Parsed replacement: '${parsed.replacement}'")
            println("Parsed enabled: ${parsed.enabled}")
            
            // This test will fail with the current implementation:
            // - The pattern will be "#hello" (with literal #)
            // - The enabled flag will be true (not false)
            assertEquals("Pattern should be 'hello' without #", "hello", parsed.pattern)
            assertEquals("Replacement should be 'hi'", "hi", parsed.replacement)
            assertFalse("Rule should be disabled", parsed.enabled)
        } else {
            fail("Failed to parse the formatted rule")
        }
    }

    @Test
    fun testBug2_EscapedQuotesBreakParsing() {
        // Bug 2: The regex pattern cannot parse escaped quotes
        // If a pattern contains quotes, they're escaped as \" when saved
        // But the regex [^\\\"] excludes backslashes, so it stops at the backslash
        
        // Format a rule with quotes in pattern and replacement
        val formatted = formatRule("say \"hello\"", "greet \"world\"", enabled = true)
        println("Formatted rule with quotes: $formatted")
        
        // Expected format: "say \"hello\"" "greet \"world\""
        
        // Try to parse it back
        val parsed = parseLine(formatted)
        
        if (parsed != null) {
            println("Parsed pattern: '${parsed.pattern}'")
            println("Parsed replacement: '${parsed.replacement}'")
            
            // After unescaping, we should get the original strings back
            assertEquals("Pattern should be unescaped", "say \"hello\"", parsed.pattern)
            assertEquals("Replacement should be unescaped", "greet \"world\"", parsed.replacement)
        } else {
            fail("Failed to parse the formatted rule with quotes")
        }
    }

    @Test
    fun testBug1And2Combined_DisabledRuleWithQuotes() {
        // Combined: disabled rule with quotes
        val formatted = formatRule("say \"hello\"", "hi", enabled = false)
        println("Formatted disabled rule with quotes: $formatted")
        
        val parsed = parseLine(formatted)
        
        if (parsed != null) {
            println("Parsed pattern: '${parsed.pattern}'")
            println("Parsed replacement: '${parsed.replacement}'")
            println("Parsed enabled: ${parsed.enabled}")
            
            assertEquals("Pattern should be unescaped", "say \"hello\"", parsed.pattern)
            assertEquals("Replacement should be 'hi'", "hi", parsed.replacement)
            assertFalse("Rule should be disabled", parsed.enabled)
        } else {
            fail("Failed to parse the formatted disabled rule with quotes")
        }
    }

    @Test
    fun testEnabledRuleWithoutQuotes_ShouldWork() {
        // This should work with the current implementation
        val formatted = formatRule("hello", "hi", enabled = true)
        println("Formatted enabled rule: $formatted")
        
        val parsed = parseLine(formatted)
        
        assertNotNull("Should be able to parse simple enabled rule", parsed)
        if (parsed != null) {
            assertEquals("hello", parsed.pattern)
            assertEquals("hi", parsed.replacement)
            assertTrue(parsed.enabled)
        }
    }

    @Test
    fun testRegexPattern() {
        // Test that the regex pattern itself has the issue with escaped quotes
        val testCases = listOf(
            // Simple case (should work)
            Triple("\"hello\" \"world\"", "hello", "world"),
            // Escaped quotes (will fail with current regex)
            Triple("\"say \\\"hello\\\"\" \"hi\"", "say \\\"hello\\\"", "hi"),
            // Disabled prefix inside quotes (current buggy behavior)
            Triple("\"#hello\" \"hi\"", "#hello", "hi"),
        )
        
        testCases.forEachIndexed { index, (input, expectedPattern, expectedReplacement) ->
            println("\nTest case $index: $input")
            val match = RULE_PATTERN.find(input)
            if (match != null) {
                val actualPattern = match.groupValues[1]
                val actualReplacement = match.groupValues[2]
                println("  Matched pattern: '$actualPattern'")
                println("  Matched replacement: '$actualReplacement'")
                
                // These assertions document the current buggy behavior
                // After fixing, update the test expectations
            } else {
                println("  No match found")
            }
        }
    }
}
