package com.rifters.riftedreader.domain.tts

sealed class TTSReplacementRule {
    abstract val isEnabled: Boolean
    abstract val pattern: String
    abstract val replacement: String

    data class SimpleRule(
        override val isEnabled: Boolean = true,
        override val pattern: String,
        override val replacement: String
    ) : TTSReplacementRule()

    data class RegexRule(
        override val isEnabled: Boolean = true,
        override val pattern: String,
        override val replacement: String,
        val regex: Regex
    ) : TTSReplacementRule()

    data class CommandRule(
        override val isEnabled: Boolean = true,
        override val pattern: String,
        override val replacement: String,
        val command: TTSCommand
    ) : TTSReplacementRule()
}

enum class TTSCommand {
    PAUSE,
    STOP,
    NEXT,
    SKIP
}

data class TTSReplacementResult(
    val text: String,
    val command: TTSCommand? = null
)
