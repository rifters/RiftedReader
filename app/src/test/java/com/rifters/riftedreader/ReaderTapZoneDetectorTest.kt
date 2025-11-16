package com.rifters.riftedreader

import com.rifters.riftedreader.ui.reader.ReaderTapZone
import com.rifters.riftedreader.ui.reader.ReaderTapZoneDetector
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for ReaderTapZoneDetector to ensure accurate tap zone detection
 * with view-relative coordinates.
 */
class ReaderTapZoneDetectorTest {

    @Test
    fun `detect returns CENTER when dimensions are zero`() {
        val zone = ReaderTapZoneDetector.detect(0f, 0f, 0, 0)
        assertEquals(ReaderTapZone.CENTER, zone)
    }

    @Test
    fun `detect top-left zone with view-relative coordinates`() {
        // 1080x2400 screen, tap at (50, 100) in view coordinates
        val zone = ReaderTapZoneDetector.detect(50f, 100f, 1080, 2400)
        assertEquals(ReaderTapZone.TOP_LEFT, zone)
    }

    @Test
    fun `detect top-center zone with view-relative coordinates`() {
        // 1080x2400 screen, tap at (540, 100) in view coordinates (center horizontally, top vertically)
        val zone = ReaderTapZoneDetector.detect(540f, 100f, 1080, 2400)
        assertEquals(ReaderTapZone.TOP_CENTER, zone)
    }

    @Test
    fun `detect top-right zone with view-relative coordinates`() {
        // 1080x2400 screen, tap at (900, 100) in view coordinates
        val zone = ReaderTapZoneDetector.detect(900f, 100f, 1080, 2400)
        assertEquals(ReaderTapZone.TOP_RIGHT, zone)
    }

    @Test
    fun `detect middle-left zone with view-relative coordinates`() {
        // 1080x2400 screen, tap at (100, 1200) in view coordinates (left, center vertically)
        val zone = ReaderTapZoneDetector.detect(100f, 1200f, 1080, 2400)
        assertEquals(ReaderTapZone.MIDDLE_LEFT, zone)
    }

    @Test
    fun `detect center zone with view-relative coordinates`() {
        // 1080x2400 screen, tap at (540, 1200) in view coordinates (center)
        val zone = ReaderTapZoneDetector.detect(540f, 1200f, 1080, 2400)
        assertEquals(ReaderTapZone.CENTER, zone)
    }

    @Test
    fun `detect middle-right zone with view-relative coordinates`() {
        // 1080x2400 screen, tap at (900, 1200) in view coordinates (right, center vertically)
        val zone = ReaderTapZoneDetector.detect(900f, 1200f, 1080, 2400)
        assertEquals(ReaderTapZone.MIDDLE_RIGHT, zone)
    }

    @Test
    fun `detect bottom-left zone with view-relative coordinates`() {
        // 1080x2400 screen, tap at (100, 2200) in view coordinates
        val zone = ReaderTapZoneDetector.detect(100f, 2200f, 1080, 2400)
        assertEquals(ReaderTapZone.BOTTOM_LEFT, zone)
    }

    @Test
    fun `detect bottom-center zone with view-relative coordinates`() {
        // 1080x2400 screen, tap at (540, 2200) in view coordinates
        val zone = ReaderTapZoneDetector.detect(540f, 2200f, 1080, 2400)
        assertEquals(ReaderTapZone.BOTTOM_CENTER, zone)
    }

    @Test
    fun `detect bottom-right zone with view-relative coordinates`() {
        // 1080x2400 screen, tap at (900, 2200) in view coordinates
        val zone = ReaderTapZoneDetector.detect(900f, 2200f, 1080, 2400)
        assertEquals(ReaderTapZone.BOTTOM_RIGHT, zone)
    }

    @Test
    fun `detect boundary cases - exactly on column boundary`() {
        // Test exactly at column boundary (360 = 1080/3)
        val width = 1080
        val height = 2400
        
        // Just before first boundary should be left
        assertEquals(ReaderTapZone.TOP_LEFT, ReaderTapZoneDetector.detect(359f, 100f, width, height))
        // At boundary should be center
        assertEquals(ReaderTapZone.TOP_CENTER, ReaderTapZoneDetector.detect(360f, 100f, width, height))
        
        // Just before second boundary should be center
        assertEquals(ReaderTapZone.TOP_CENTER, ReaderTapZoneDetector.detect(719f, 100f, width, height))
        // At second boundary should be right
        assertEquals(ReaderTapZone.TOP_RIGHT, ReaderTapZoneDetector.detect(720f, 100f, width, height))
    }

    @Test
    fun `detect boundary cases - exactly on row boundary`() {
        // Test exactly at row boundary (800 = 2400/3)
        val width = 1080
        val height = 2400
        
        // Just before first boundary should be top
        assertEquals(ReaderTapZone.TOP_CENTER, ReaderTapZoneDetector.detect(540f, 799f, width, height))
        // At boundary should be middle
        assertEquals(ReaderTapZone.CENTER, ReaderTapZoneDetector.detect(540f, 800f, width, height))
        
        // Just before second boundary should be middle
        assertEquals(ReaderTapZone.CENTER, ReaderTapZoneDetector.detect(540f, 1599f, width, height))
        // At second boundary should be bottom
        assertEquals(ReaderTapZone.BOTTOM_CENTER, ReaderTapZoneDetector.detect(540f, 1600f, width, height))
    }

    @Test
    fun `detect with small view dimensions`() {
        // Test with smaller view (300x600)
        val zone = ReaderTapZoneDetector.detect(150f, 300f, 300, 600)
        assertEquals(ReaderTapZone.CENTER, zone)
    }

    @Test
    fun `detect edge cases - origin point`() {
        // (0, 0) should always be top-left
        val zone = ReaderTapZoneDetector.detect(0f, 0f, 1080, 2400)
        assertEquals(ReaderTapZone.TOP_LEFT, zone)
    }

    @Test
    fun `detect edge cases - far corner`() {
        // Bottom-right corner
        val zone = ReaderTapZoneDetector.detect(1079f, 2399f, 1080, 2400)
        assertEquals(ReaderTapZone.BOTTOM_RIGHT, zone)
    }
}
