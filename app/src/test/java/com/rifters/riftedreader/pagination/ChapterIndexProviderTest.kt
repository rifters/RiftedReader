package com.rifters.riftedreader.pagination

import com.rifters.riftedreader.data.preferences.ChapterVisibilitySettings
import com.rifters.riftedreader.domain.pagination.ChapterIndexProvider
import com.rifters.riftedreader.domain.pagination.ChapterInfo
import com.rifters.riftedreader.domain.pagination.ChapterType
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ChapterIndexProvider.
 *
 * Tests cover:
 * - Chapter type filtering (NAV, COVER excluded by default)
 * - UI index to spine index mapping
 * - Window computation with visible chapters
 * - Non-linear item handling
 * - ChapterVisibilitySettings integration
 */
class ChapterIndexProviderTest {

    private lateinit var provider: ChapterIndexProvider

    @Before
    fun setup() {
        provider = ChapterIndexProvider(chaptersPerWindow = 5)
    }

    @Test
    fun `empty chapter list produces zero windows`() {
        provider.setChapters(emptyList())

        assertEquals(0, provider.spineCount)
        assertEquals(0, provider.visibleChapterCount)
        assertEquals(0, provider.getWindowCount())
    }

    @Test
    fun `NAV chapters are excluded from visible chapters`() {
        val chapters = listOf(
            ChapterInfo(0, "nav.xhtml", ChapterType.NAV),
            ChapterInfo(1, "chapter1.xhtml", ChapterType.CONTENT),
            ChapterInfo(2, "chapter2.xhtml", ChapterType.CONTENT)
        )

        provider.setChapters(chapters)

        assertEquals(3, provider.spineCount)
        assertEquals(2, provider.visibleChapterCount)
        assertEquals(1, provider.getWindowCount())
    }

    @Test
    fun `COVER chapters are excluded from visible chapters`() {
        val chapters = listOf(
            ChapterInfo(0, "cover.xhtml", ChapterType.COVER),
            ChapterInfo(1, "chapter1.xhtml", ChapterType.CONTENT),
            ChapterInfo(2, "chapter2.xhtml", ChapterType.CONTENT)
        )

        provider.setChapters(chapters)

        assertEquals(3, provider.spineCount)
        assertEquals(2, provider.visibleChapterCount)
    }

    @Test
    fun `FRONT_MATTER chapters are included by default`() {
        val chapters = listOf(
            ChapterInfo(0, "title.xhtml", ChapterType.FRONT_MATTER),
            ChapterInfo(1, "copyright.xhtml", ChapterType.FRONT_MATTER),
            ChapterInfo(2, "chapter1.xhtml", ChapterType.CONTENT)
        )

        provider.setChapters(chapters)

        assertEquals(3, provider.spineCount)
        assertEquals(3, provider.visibleChapterCount)
    }

    @Test
    fun `NON_LINEAR chapters are excluded by default`() {
        val chapters = listOf(
            ChapterInfo(0, "chapter1.xhtml", ChapterType.CONTENT),
            ChapterInfo(1, "chapter2.xhtml", ChapterType.CONTENT),
            ChapterInfo(2, "notes.xhtml", ChapterType.NON_LINEAR, isLinear = false)
        )

        provider.setChapters(chapters)

        assertEquals(3, provider.spineCount)
        assertEquals(2, provider.visibleChapterCount)
    }

    @Test
    fun `NON_LINEAR chapters included when setting enabled`() {
        val chapters = listOf(
            ChapterInfo(0, "chapter1.xhtml", ChapterType.CONTENT),
            ChapterInfo(1, "chapter2.xhtml", ChapterType.CONTENT),
            // NON_LINEAR chapter with isLinear=true (edge case: included when flag enabled)
            ChapterInfo(2, "notes.xhtml", ChapterType.NON_LINEAR, isLinear = true)
        )

        provider.setChapters(chapters, includeNonLinear = true)

        assertEquals(3, provider.spineCount)
        assertEquals(3, provider.visibleChapterCount)
    }

    @Test
    fun `UI index to spine index mapping is correct`() {
        val chapters = listOf(
            ChapterInfo(0, "cover.xhtml", ChapterType.COVER),
            ChapterInfo(1, "nav.xhtml", ChapterType.NAV),
            ChapterInfo(2, "chapter1.xhtml", ChapterType.CONTENT),
            ChapterInfo(3, "chapter2.xhtml", ChapterType.CONTENT),
            ChapterInfo(4, "notes.xhtml", ChapterType.NON_LINEAR)
        )

        provider.setChapters(chapters)

        // Visible chapters are indices 2 and 3
        assertEquals(2, provider.uiIndexToSpineIndex(0))  // UI 0 -> Spine 2
        assertEquals(3, provider.uiIndexToSpineIndex(1))  // UI 1 -> Spine 3
        assertEquals(-1, provider.uiIndexToSpineIndex(2)) // Out of range
        assertEquals(-1, provider.uiIndexToSpineIndex(-1)) // Invalid
    }

    @Test
    fun `spine index to UI index mapping is correct`() {
        val chapters = listOf(
            ChapterInfo(0, "cover.xhtml", ChapterType.COVER),
            ChapterInfo(1, "nav.xhtml", ChapterType.NAV),
            ChapterInfo(2, "chapter1.xhtml", ChapterType.CONTENT),
            ChapterInfo(3, "chapter2.xhtml", ChapterType.CONTENT),
            ChapterInfo(4, "notes.xhtml", ChapterType.NON_LINEAR)
        )

        provider.setChapters(chapters)

        // COVER and NAV are not visible
        assertEquals(-1, provider.spineIndexToUiIndex(0)) // Cover not visible
        assertEquals(-1, provider.spineIndexToUiIndex(1)) // NAV not visible
        assertEquals(0, provider.spineIndexToUiIndex(2))  // Spine 2 -> UI 0
        assertEquals(1, provider.spineIndexToUiIndex(3))  // Spine 3 -> UI 1
        assertEquals(-1, provider.spineIndexToUiIndex(4)) // NON_LINEAR not visible
    }

    @Test
    fun `window count based on visible chapters`() {
        // Create a book with EPUB-like structure
        val chapters = mutableListOf<ChapterInfo>()
        chapters.add(ChapterInfo(0, "cover.xhtml", ChapterType.COVER))
        chapters.add(ChapterInfo(1, "nav.xhtml", ChapterType.NAV))
        // Add 10 content chapters
        for (i in 2..11) {
            chapters.add(ChapterInfo(i, "chapter${i-1}.xhtml", ChapterType.CONTENT))
        }
        chapters.add(ChapterInfo(12, "notes.xhtml", ChapterType.NON_LINEAR))

        provider.setChapters(chapters)

        // 13 spine items, 10 visible (content chapters only)
        assertEquals(13, provider.spineCount)
        assertEquals(10, provider.visibleChapterCount)

        // With 5 chapters per window: 10 visible / 5 = 2 windows
        assertEquals(2, provider.getWindowCount())

        // Spine window count would be different: 13 / 5 = 3 windows
        assertEquals(3, provider.getWindowCountForSpine())
    }

    @Test
    fun `getWindowRange returns correct visible chapter range`() {
        val chapters = mutableListOf<ChapterInfo>()
        chapters.add(ChapterInfo(0, "cover.xhtml", ChapterType.COVER))
        // Add 12 content chapters
        for (i in 1..12) {
            chapters.add(ChapterInfo(i, "chapter$i.xhtml", ChapterType.CONTENT))
        }

        provider.setChapters(chapters)

        // Window 0 should have visible chapters 0-4 (UI indices)
        val window0 = provider.getWindowRange(0)
        assertNotNull(window0)
        assertEquals(0 to 4, window0)

        // Window 1 should have visible chapters 5-9
        val window1 = provider.getWindowRange(1)
        assertNotNull(window1)
        assertEquals(5 to 9, window1)

        // Window 2 should have visible chapters 10-11 (remainder)
        val window2 = provider.getWindowRange(2)
        assertNotNull(window2)
        assertEquals(10 to 11, window2)
    }

    @Test
    fun `getSpineIndicesForWindow returns correct spine indices`() {
        val chapters = listOf(
            ChapterInfo(0, "cover.xhtml", ChapterType.COVER),
            ChapterInfo(1, "chapter1.xhtml", ChapterType.CONTENT),
            ChapterInfo(2, "chapter2.xhtml", ChapterType.CONTENT),
            ChapterInfo(3, "chapter3.xhtml", ChapterType.CONTENT),
            ChapterInfo(4, "chapter4.xhtml", ChapterType.CONTENT),
            ChapterInfo(5, "chapter5.xhtml", ChapterType.CONTENT),
            ChapterInfo(6, "chapter6.xhtml", ChapterType.CONTENT)
        )

        provider.setChapters(chapters)

        // Window 0: visible chapters 0-4 -> spine indices 1-5
        val window0Spine = provider.getSpineIndicesForWindow(0)
        assertEquals(listOf(1, 2, 3, 4, 5), window0Spine)

        // Window 1: visible chapter 5 -> spine index 6
        val window1Spine = provider.getSpineIndicesForWindow(1)
        assertEquals(listOf(6), window1Spine)
    }

    @Test
    fun `isSpineIndexVisible returns correct result`() {
        val chapters = listOf(
            ChapterInfo(0, "cover.xhtml", ChapterType.COVER),
            ChapterInfo(1, "chapter1.xhtml", ChapterType.CONTENT),
            ChapterInfo(2, "notes.xhtml", ChapterType.NON_LINEAR)
        )

        provider.setChapters(chapters)

        assertFalse(provider.isSpineIndexVisible(0)) // Cover
        assertTrue(provider.isSpineIndexVisible(1))  // Content
        assertFalse(provider.isSpineIndexVisible(2)) // Non-linear
    }

    @Test
    fun `getChapterTypeCounts returns accurate counts`() {
        val chapters = listOf(
            ChapterInfo(0, "cover.xhtml", ChapterType.COVER),
            ChapterInfo(1, "nav.xhtml", ChapterType.NAV),
            ChapterInfo(2, "title.xhtml", ChapterType.FRONT_MATTER),
            ChapterInfo(3, "chapter1.xhtml", ChapterType.CONTENT),
            ChapterInfo(4, "chapter2.xhtml", ChapterType.CONTENT),
            ChapterInfo(5, "chapter3.xhtml", ChapterType.CONTENT),
            ChapterInfo(6, "notes.xhtml", ChapterType.NON_LINEAR),
            ChapterInfo(7, "glossary.xhtml", ChapterType.NON_LINEAR)
        )

        provider.setChapters(chapters)

        val counts = provider.getChapterTypeCounts()
        assertEquals(1, counts[ChapterType.COVER])
        assertEquals(1, counts[ChapterType.NAV])
        assertEquals(1, counts[ChapterType.FRONT_MATTER])
        assertEquals(3, counts[ChapterType.CONTENT])
        assertEquals(2, counts[ChapterType.NON_LINEAR])
    }

    // Test case from the problem statement: 109 spine items, 101 visible
    @Test
    fun `real world EPUB case - solves window count mismatch`() {
        val chapters = mutableListOf<ChapterInfo>()

        // Add typical EPUB structure
        chapters.add(ChapterInfo(0, "cover.xhtml", ChapterType.COVER))
        chapters.add(ChapterInfo(1, "nav.xhtml", ChapterType.NAV))
        chapters.add(ChapterInfo(2, "title.xhtml", ChapterType.FRONT_MATTER))

        // Add 101 content chapters (spine indices 3-103)
        for (i in 3..103) {
            chapters.add(ChapterInfo(i, "chapter${i-2}.xhtml", ChapterType.CONTENT))
        }

        // Add 5 non-linear items (spine indices 104-108)
        for (i in 104..108) {
            chapters.add(ChapterInfo(i, "notes${i-103}.xhtml", ChapterType.NON_LINEAR))
        }

        provider.setChapters(chapters)

        // Verify counts
        assertEquals(109, provider.spineCount)
        // Visible = 1 front matter + 101 content = 102
        assertEquals(102, provider.visibleChapterCount)

        // Window count based on visible chapters: ceil(102/5) = 21
        assertEquals(21, provider.getWindowCount())

        // This is the consistent window count that should be used everywhere
        // It differs from ceil(109/5) = 22 which caused the original mismatch
    }
    
    // ==== ChapterVisibilitySettings tests ====
    
    @Test
    fun `setChaptersWithVisibility with default settings matches setChapters default behavior`() {
        val chapters = listOf(
            ChapterInfo(0, "cover.xhtml", ChapterType.COVER),
            ChapterInfo(1, "nav.xhtml", ChapterType.NAV),
            ChapterInfo(2, "title.xhtml", ChapterType.FRONT_MATTER),
            ChapterInfo(3, "chapter1.xhtml", ChapterType.CONTENT),
            ChapterInfo(4, "notes.xhtml", ChapterType.NON_LINEAR)
        )
        
        val defaultSettings = ChapterVisibilitySettings.DEFAULT
        provider.setChaptersWithVisibility(chapters, defaultSettings)
        
        // Default: includeCover=false, includeFrontMatter=true, includeNonLinear=false
        // Visible: FRONT_MATTER + CONTENT = 2
        assertEquals(5, provider.spineCount)
        assertEquals(2, provider.visibleChapterCount)
        
        // Verify visibility settings are stored
        assertEquals(defaultSettings, provider.currentVisibilitySettings)
    }
    
    @Test
    fun `setChaptersWithVisibility with includeCover true shows cover`() {
        val chapters = listOf(
            ChapterInfo(0, "cover.xhtml", ChapterType.COVER),
            ChapterInfo(1, "chapter1.xhtml", ChapterType.CONTENT),
            ChapterInfo(2, "chapter2.xhtml", ChapterType.CONTENT)
        )
        
        val settings = ChapterVisibilitySettings(
            includeCover = true,
            includeFrontMatter = true,
            includeNonLinear = false
        )
        provider.setChaptersWithVisibility(chapters, settings)
        
        assertEquals(3, provider.spineCount)
        assertEquals(3, provider.visibleChapterCount)
        assertTrue(provider.isSpineIndexVisible(0)) // Cover is now visible
    }
    
    @Test
    fun `setChaptersWithVisibility with includeFrontMatter false hides front matter`() {
        val chapters = listOf(
            ChapterInfo(0, "title.xhtml", ChapterType.FRONT_MATTER),
            ChapterInfo(1, "copyright.xhtml", ChapterType.FRONT_MATTER),
            ChapterInfo(2, "chapter1.xhtml", ChapterType.CONTENT)
        )
        
        val settings = ChapterVisibilitySettings(
            includeCover = false,
            includeFrontMatter = false,
            includeNonLinear = false
        )
        provider.setChaptersWithVisibility(chapters, settings)
        
        assertEquals(3, provider.spineCount)
        assertEquals(1, provider.visibleChapterCount) // Only CONTENT
        assertFalse(provider.isSpineIndexVisible(0)) // Title page hidden
        assertFalse(provider.isSpineIndexVisible(1)) // Copyright hidden
        assertTrue(provider.isSpineIndexVisible(2))  // Chapter visible
    }
    
    @Test
    fun `setChaptersWithVisibility with includeNonLinear true shows non-linear content`() {
        val chapters = listOf(
            ChapterInfo(0, "chapter1.xhtml", ChapterType.CONTENT),
            ChapterInfo(1, "notes.xhtml", ChapterType.NON_LINEAR),
            ChapterInfo(2, "glossary.xhtml", ChapterType.NON_LINEAR)
        )
        
        val settings = ChapterVisibilitySettings(
            includeCover = false,
            includeFrontMatter = true,
            includeNonLinear = true
        )
        provider.setChaptersWithVisibility(chapters, settings)
        
        assertEquals(3, provider.spineCount)
        assertEquals(3, provider.visibleChapterCount) // All visible
        assertTrue(provider.isSpineIndexVisible(1)) // Notes visible
        assertTrue(provider.isSpineIndexVisible(2)) // Glossary visible
    }
    
    @Test
    fun `setChaptersWithVisibility with all options enabled shows everything except NAV`() {
        val chapters = listOf(
            ChapterInfo(0, "cover.xhtml", ChapterType.COVER),
            ChapterInfo(1, "nav.xhtml", ChapterType.NAV),
            ChapterInfo(2, "title.xhtml", ChapterType.FRONT_MATTER),
            ChapterInfo(3, "chapter1.xhtml", ChapterType.CONTENT),
            ChapterInfo(4, "notes.xhtml", ChapterType.NON_LINEAR)
        )
        
        val settings = ChapterVisibilitySettings(
            includeCover = true,
            includeFrontMatter = true,
            includeNonLinear = true
        )
        provider.setChaptersWithVisibility(chapters, settings)
        
        assertEquals(5, provider.spineCount)
        assertEquals(4, provider.visibleChapterCount) // All except NAV
        assertTrue(provider.isSpineIndexVisible(0))  // Cover visible
        assertFalse(provider.isSpineIndexVisible(1)) // NAV always hidden
        assertTrue(provider.isSpineIndexVisible(2))  // Front matter visible
        assertTrue(provider.isSpineIndexVisible(3))  // Content visible
        assertTrue(provider.isSpineIndexVisible(4))  // Non-linear visible
    }
    
    @Test
    fun `updateVisibilitySettings changes visible chapters dynamically`() {
        val chapters = listOf(
            ChapterInfo(0, "cover.xhtml", ChapterType.COVER),
            ChapterInfo(1, "chapter1.xhtml", ChapterType.CONTENT),
            ChapterInfo(2, "notes.xhtml", ChapterType.NON_LINEAR)
        )
        
        // Start with defaults
        provider.setChaptersWithVisibility(chapters, ChapterVisibilitySettings.DEFAULT)
        assertEquals(1, provider.visibleChapterCount) // Only CONTENT
        
        // Update to include cover
        provider.updateVisibilitySettings(
            ChapterVisibilitySettings(includeCover = true, includeFrontMatter = true, includeNonLinear = false)
        )
        assertEquals(2, provider.visibleChapterCount) // COVER + CONTENT
        assertTrue(provider.isSpineIndexVisible(0))
        
        // Update to include non-linear
        provider.updateVisibilitySettings(
            ChapterVisibilitySettings(includeCover = true, includeFrontMatter = true, includeNonLinear = true)
        )
        assertEquals(3, provider.visibleChapterCount) // All
    }
    
    @Test
    fun `getHiddenChapterCounts returns correct hidden counts`() {
        val chapters = listOf(
            ChapterInfo(0, "cover.xhtml", ChapterType.COVER),
            ChapterInfo(1, "nav.xhtml", ChapterType.NAV),
            ChapterInfo(2, "title.xhtml", ChapterType.FRONT_MATTER),
            ChapterInfo(3, "chapter1.xhtml", ChapterType.CONTENT),
            ChapterInfo(4, "notes.xhtml", ChapterType.NON_LINEAR),
            ChapterInfo(5, "glossary.xhtml", ChapterType.NON_LINEAR)
        )
        
        // With default settings (cover=false, frontMatter=true, nonLinear=false)
        provider.setChaptersWithVisibility(chapters, ChapterVisibilitySettings.DEFAULT)
        
        val hiddenCounts = provider.getHiddenChapterCounts()
        
        assertEquals(1, hiddenCounts[ChapterType.COVER])     // Cover hidden
        assertEquals(1, hiddenCounts[ChapterType.NAV])       // NAV always hidden
        assertEquals(2, hiddenCounts[ChapterType.NON_LINEAR]) // Both non-linear hidden
        assertNull(hiddenCounts[ChapterType.FRONT_MATTER])   // Front matter visible (not in hidden counts)
        assertNull(hiddenCounts[ChapterType.CONTENT])        // Content visible (not in hidden counts)
    }
    
    @Test
    fun `visibility settings affect window count correctly`() {
        val chapters = mutableListOf<ChapterInfo>()
        chapters.add(ChapterInfo(0, "cover.xhtml", ChapterType.COVER))
        // Add 10 content chapters
        for (i in 1..10) {
            chapters.add(ChapterInfo(i, "chapter$i.xhtml", ChapterType.CONTENT))
        }
        // Add 2 non-linear chapters
        chapters.add(ChapterInfo(11, "notes.xhtml", ChapterType.NON_LINEAR))
        chapters.add(ChapterInfo(12, "glossary.xhtml", ChapterType.NON_LINEAR))
        
        // Default: 10 content visible -> 2 windows (ceil(10/5))
        provider.setChaptersWithVisibility(chapters, ChapterVisibilitySettings.DEFAULT)
        assertEquals(10, provider.visibleChapterCount)
        assertEquals(2, provider.getWindowCount())
        
        // Include cover: 11 visible -> 3 windows (ceil(11/5))
        provider.updateVisibilitySettings(
            ChapterVisibilitySettings(includeCover = true, includeFrontMatter = true, includeNonLinear = false)
        )
        assertEquals(11, provider.visibleChapterCount)
        assertEquals(3, provider.getWindowCount())
        
        // Include everything: 13 visible -> 3 windows (ceil(13/5))
        provider.updateVisibilitySettings(
            ChapterVisibilitySettings(includeCover = true, includeFrontMatter = true, includeNonLinear = true)
        )
        assertEquals(13, provider.visibleChapterCount)
        assertEquals(3, provider.getWindowCount())
    }
    
    @Test
    fun `currentVisibilitySettings returns the active settings`() {
        val chapters = listOf(
            ChapterInfo(0, "chapter1.xhtml", ChapterType.CONTENT)
        )
        
        val customSettings = ChapterVisibilitySettings(
            includeCover = true,
            includeFrontMatter = false,
            includeNonLinear = true
        )
        
        provider.setChaptersWithVisibility(chapters, customSettings)
        
        assertEquals(customSettings, provider.currentVisibilitySettings)
        assertEquals(true, provider.currentVisibilitySettings.includeCover)
        assertEquals(false, provider.currentVisibilitySettings.includeFrontMatter)
        assertEquals(true, provider.currentVisibilitySettings.includeNonLinear)
    }
}
