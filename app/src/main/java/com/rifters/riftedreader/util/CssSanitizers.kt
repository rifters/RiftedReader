package com.rifters.riftedreader.util

object CssSanitizers {
    fun sanitizeCssFontFamily(input: String, fallback: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return fallback
        val sanitized = trimmed
            .replace("\n", " ")
            .replace("\r", " ")
            .replace("\t", " ")
            .replace("\"", "")
            .replace("'", "")
            .replace(";", "")
            .trim()
        return sanitized.ifEmpty { fallback }
    }
}
