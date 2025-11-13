package com.rifters.riftedreader.ui.reader

/**
 * Represents the nine tap regions used for navigation and overlay toggling.
 * Based on LibreraReader's configurable tap zones (rifters/LibreraReader, ui2/reader/ReaderGestures.java, lines 55-140).
 */
enum class ReaderTapZone {
    TOP_LEFT,
    TOP_CENTER,
    TOP_RIGHT,
    MIDDLE_LEFT,
    CENTER,
    MIDDLE_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_CENTER,
    BOTTOM_RIGHT
}

object ReaderTapZoneDetector {

    fun detect(x: Float, y: Float, width: Int, height: Int): ReaderTapZone {
        if (width == 0 || height == 0) return ReaderTapZone.CENTER

        val columnWidth = width / 3f
        val rowHeight = height / 3f

        val column = when {
            x < columnWidth -> 0
            x < columnWidth * 2 -> 1
            else -> 2
        }

        val row = when {
            y < rowHeight -> 0
            y < rowHeight * 2 -> 1
            else -> 2
        }

        return when (row) {
            0 -> when (column) {
                0 -> ReaderTapZone.TOP_LEFT
                1 -> ReaderTapZone.TOP_CENTER
                else -> ReaderTapZone.TOP_RIGHT
            }

            1 -> when (column) {
                0 -> ReaderTapZone.MIDDLE_LEFT
                1 -> ReaderTapZone.CENTER
                else -> ReaderTapZone.MIDDLE_RIGHT
            }

            else -> when (column) {
                0 -> ReaderTapZone.BOTTOM_LEFT
                1 -> ReaderTapZone.BOTTOM_CENTER
                else -> ReaderTapZone.BOTTOM_RIGHT
            }
        }
    }
}
