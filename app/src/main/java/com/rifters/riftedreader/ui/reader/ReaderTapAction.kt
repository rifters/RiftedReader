package com.rifters.riftedreader.ui.reader

/**
 * Supported tap actions mirroring LibreraReader's gesture assignments (see
 * rifters/LibreraReader, ui2/reader/ReaderGestures.java, lines 150-230).
 */
enum class ReaderTapAction {
    NONE,
    BACK,
    TOGGLE_CONTROLS,
    NEXT_PAGE,
    PREVIOUS_PAGE,
    OPEN_SETTINGS,
    START_TTS
}
