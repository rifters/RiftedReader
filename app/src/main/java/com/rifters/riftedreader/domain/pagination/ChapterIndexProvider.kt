package com.rifters.riftedreader.domain.pagination

import com.rifters.riftedreader.data.preferences.ChapterVisibilitySettings
import com.rifters.riftedreader.util.AppLogger

/**
 * Metadata about a chapter in the spine, including its type classification.
 *
 * @property spineIndex Original index in the EPUB spine (0-based)
 * @property href Path to the chapter content file
 * @property type Classification of this chapter
 * @property isLinear Whether this item has linear="yes" (or implied) in spine
 * @property title Optional title extracted from TOC or filename
 */
data class ChapterInfo(
    val spineIndex: Int,
    val href: String,
    val type: ChapterType,
    val isLinear: Boolean = true,
    val title: String? = null
)

/**
 * Provides unified chapter indexing with mapping between different chapter views.
 *
 * **Problem Solved:**
 * EPUB packages often contain more spine items than user-facing chapters:
 * - Cover XHTML (sometimes not in TOC)
 * - Navigation document (nav.xhtml - provides TOC but isn't a chapter itself)
 * - Front matter (copyright, dedication) excluded from TOC
 * - Non-linear items (linear="no") like notes or glossary
 *
 * This leads to mismatches (e.g., spine has 109 items, TOC shows 101 entries),
 * causing WINDOW_COUNT_MISMATCH errors when window calculations use different counts.
 *
 * **Solution:**
 * ChapterIndexProvider maintains two lists:
 * 1. `spineAll` - All spine items in reading order
 * 2. `visibleChapters` - Filtered subset for user-facing UI
 *
 * It provides mapping functions to convert between:
 * - UI index (what user sees in chapter list/progress) ↔ spine index
 * - TTS traversal index (for speech) ↔ spine index
 * - Window index ↔ chapter range
 *
 * **Usage:**
 * ```kotlin
 * val provider = ChapterIndexProvider(chaptersPerWindow = 5)
 * provider.setChapters(parsedChapters)
 *
 * // Get visible chapter count for UI
 * val visibleCount = provider.visibleChapterCount
 *
 * // Map UI selection to spine
 * val spineIdx = provider.uiIndexToSpineIndex(userSelectedChapter)
 *
 * // Get correct window count
 * val windowCount = provider.getWindowCount()
 * ```
 *
 * @param chaptersPerWindow Number of chapters per pagination window (default: 5)
 */
class ChapterIndexProvider(
    private val chaptersPerWindow: Int = SlidingWindowPaginator.DEFAULT_CHAPTERS_PER_WINDOW
) {
    private var _spineAll: List<ChapterInfo> = emptyList()
    private var _visibleChapters: List<ChapterInfo> = emptyList()
    
    // Cached mappings for fast lookup
    private var _uiToSpineMap: Map<Int, Int> = emptyMap()
    private var _spineToUiMap: Map<Int, Int> = emptyMap()
    
    // Current visibility settings (for logging and debugging)
    private var _currentVisibilitySettings: ChapterVisibilitySettings = ChapterVisibilitySettings.DEFAULT

    /** All spine items in reading order */
    val spineAll: List<ChapterInfo> get() = _spineAll

    /** Visible chapters for UI (excludes NAV, COVER, NON_LINEAR by default) */
    val visibleChapters: List<ChapterInfo> get() = _visibleChapters

    /** Count of all spine items */
    val spineCount: Int get() = _spineAll.size

    /** Count of visible chapters (for UI display) */
    val visibleChapterCount: Int get() = _visibleChapters.size
    
    /** Current visibility settings */
    val currentVisibilitySettings: ChapterVisibilitySettings get() = _currentVisibilitySettings

    init {
        require(chaptersPerWindow > 0) { "chaptersPerWindow must be positive, got: $chaptersPerWindow" }
    }

    /**
     * Set the chapter list and build mapping indices using legacy boolean parameter.
     *
     * Call this after parsing the EPUB OPF file.
     *
     * @param chapters List of all spine items with their classifications
     * @param includeNonLinear Whether to include non-linear items in visible chapters
     */
    fun setChapters(chapters: List<ChapterInfo>, includeNonLinear: Boolean = false) {
        // Convert legacy parameter to ChapterVisibilitySettings
        val settings = ChapterVisibilitySettings(
            includeCover = false,
            includeFrontMatter = true,
            includeNonLinear = includeNonLinear
        )
        setChaptersWithVisibility(chapters, settings)
    }
    
    /**
     * Set the chapter list and build mapping indices using visibility settings.
     *
     * Call this after parsing the EPUB OPF file.
     *
     * @param chapters List of all spine items with their classifications
     * @param visibilitySettings User-configurable visibility settings
     */
    fun setChaptersWithVisibility(
        chapters: List<ChapterInfo>,
        visibilitySettings: ChapterVisibilitySettings
    ) {
        _spineAll = chapters.toList()
        _currentVisibilitySettings = visibilitySettings

        // Build visible chapters list based on user settings
        _visibleChapters = _spineAll.filter { chapter ->
            when (chapter.type) {
                ChapterType.NAV -> false // Always exclude navigation document
                ChapterType.COVER -> visibilitySettings.includeCover
                ChapterType.NON_LINEAR -> visibilitySettings.includeNonLinear
                ChapterType.FRONT_MATTER -> visibilitySettings.includeFrontMatter
                ChapterType.CONTENT -> true // Always include main content
            }
        }

        // Build bidirectional mappings
        val uiToSpine = mutableMapOf<Int, Int>()
        val spineToUi = mutableMapOf<Int, Int>()

        _visibleChapters.forEachIndexed { uiIndex, chapter ->
            uiToSpine[uiIndex] = chapter.spineIndex
            spineToUi[chapter.spineIndex] = uiIndex
        }

        _uiToSpineMap = uiToSpine
        _spineToUiMap = spineToUi

        AppLogger.d(TAG, "[Pagination] ChapterIndexProvider updated: " +
            "spineAll=${_spineAll.size}, visible=${_visibleChapters.size}, " +
            "cpw=$chaptersPerWindow")
        logChapterBreakdown()
        logVisibilitySettings()
    }
    
    /**
     * Update visibility settings and rebuild the visible chapters list.
     * 
     * Use this to dynamically change which chapters are visible without reloading
     * the entire spine.
     *
     * @param visibilitySettings New visibility settings to apply
     */
    fun updateVisibilitySettings(visibilitySettings: ChapterVisibilitySettings) {
        if (_spineAll.isEmpty()) {
            AppLogger.w(TAG, "[Pagination] Cannot update visibility - no chapters loaded")
            return
        }
        
        AppLogger.d(TAG, "[Pagination] Updating visibility settings: " +
            "cover=${visibilitySettings.includeCover}, " +
            "frontMatter=${visibilitySettings.includeFrontMatter}, " +
            "nonLinear=${visibilitySettings.includeNonLinear}")
        
        setChaptersWithVisibility(_spineAll, visibilitySettings)
    }

    /**
     * Convert a UI index (user-facing) to spine index.
     *
     * @param uiIndex The visible chapter index (0-based)
     * @return The corresponding spine index, or -1 if invalid
     */
    fun uiIndexToSpineIndex(uiIndex: Int): Int {
        return _uiToSpineMap[uiIndex] ?: -1
    }

    /**
     * Convert a spine index to UI index.
     *
     * @param spineIndex The spine item index (0-based)
     * @return The corresponding UI index, or -1 if not visible
     */
    fun spineIndexToUiIndex(spineIndex: Int): Int {
        return _spineToUiMap[spineIndex] ?: -1
    }

    /**
     * Get the window index for a given UI chapter index.
     *
     * @param uiChapterIndex The visible chapter index
     * @return Window index containing this chapter
     */
    fun getWindowForUiChapter(uiChapterIndex: Int): Int {
        return WindowCalculator.getWindowForChapter(uiChapterIndex, chaptersPerWindow)
    }

    /**
     * Get the window count based on visible chapters.
     *
     * @return Number of windows needed for all visible chapters
     */
    fun getWindowCount(): Int {
        return WindowCalculator.calculateWindowCount(_visibleChapters.size, chaptersPerWindow)
    }

    /**
     * Get the window count based on all spine items.
     *
     * Use this for modes where all content should be navigable.
     *
     * @return Number of windows needed for all spine items
     */
    fun getWindowCountForSpine(): Int {
        return WindowCalculator.calculateWindowCount(_spineAll.size, chaptersPerWindow)
    }

    /**
     * Get chapter range for a window index (using visible chapters).
     *
     * @param windowIndex The window index (0-based)
     * @return Pair of (firstUiIndex, lastUiIndex) or null if invalid
     */
    fun getWindowRange(windowIndex: Int): Pair<Int, Int>? {
        return WindowCalculator.getWindowRange(windowIndex, _visibleChapters.size, chaptersPerWindow)
    }

    /**
     * Get spine indices for a window index.
     *
     * @param windowIndex The window index
     * @return List of spine indices in this window, empty if invalid
     */
    fun getSpineIndicesForWindow(windowIndex: Int): List<Int> {
        val range = getWindowRange(windowIndex) ?: return emptyList()
        return (range.first..range.second).mapNotNull { uiIndex ->
            uiIndexToSpineIndex(uiIndex).takeIf { it >= 0 }
        }
    }

    /**
     * Check if a spine index corresponds to a visible chapter.
     *
     * @param spineIndex The spine index to check
     * @return true if this index is visible to users
     */
    fun isSpineIndexVisible(spineIndex: Int): Boolean {
        return _spineToUiMap.containsKey(spineIndex)
    }

    /**
     * Get chapter info by spine index.
     *
     * @param spineIndex The spine index
     * @return ChapterInfo or null if not found
     */
    fun getChapterBySpineIndex(spineIndex: Int): ChapterInfo? {
        return _spineAll.find { it.spineIndex == spineIndex }
    }

    /**
     * Get chapter info by UI index.
     *
     * @param uiIndex The visible chapter index
     * @return ChapterInfo or null if not found
     */
    fun getChapterByUiIndex(uiIndex: Int): ChapterInfo? {
        return _visibleChapters.getOrNull(uiIndex)
    }

    /**
     * Get diagnostic information about chapter counts.
     *
     * @return Map of chapter types to their counts
     */
    fun getChapterTypeCounts(): Map<ChapterType, Int> {
        return _spineAll.groupBy { it.type }.mapValues { it.value.size }
    }
    
    /**
     * Get counts of hidden chapters by type for diagnostic purposes.
     *
     * @return Map of chapter types to their hidden counts (not shown due to visibility settings)
     */
    fun getHiddenChapterCounts(): Map<ChapterType, Int> {
        val allCounts = getChapterTypeCounts()
        val visibleByType = _visibleChapters.groupBy { it.type }.mapValues { it.value.size }
        
        return ChapterType.values().associate { type ->
            val total = allCounts[type] ?: 0
            val visible = visibleByType[type] ?: 0
            type to (total - visible)
        }.filterValues { it > 0 }
    }

    /**
     * Generate debug string for logging.
     */
    fun getDebugInfo(): String {
        val typeCounts = getChapterTypeCounts()
        val hiddenCounts = getHiddenChapterCounts()
        val windowCount = getWindowCount()
        val spineWindowCount = getWindowCountForSpine()

        return buildString {
            appendLine("=== ChapterIndexProvider Debug ===")
            appendLine("Spine total: ${_spineAll.size}")
            appendLine("Visible chapters: ${_visibleChapters.size}")
            appendLine("Chapters per window: $chaptersPerWindow")
            appendLine("Window count (visible): $windowCount")
            appendLine("Window count (spine): $spineWindowCount")
            appendLine("Visibility settings:")
            appendLine("  includeCover: ${_currentVisibilitySettings.includeCover}")
            appendLine("  includeFrontMatter: ${_currentVisibilitySettings.includeFrontMatter}")
            appendLine("  includeNonLinear: ${_currentVisibilitySettings.includeNonLinear}")
            appendLine("Type breakdown (all/hidden):")
            ChapterType.values().forEach { type ->
                val total = typeCounts[type] ?: 0
                val hidden = hiddenCounts[type] ?: 0
                appendLine("  $type: $total (hidden: $hidden)")
            }
            appendLine("Window map: ${WindowCalculator.debugWindowMap(_visibleChapters.size, chaptersPerWindow)}")
            appendLine("===================================")
        }
    }

    private fun logChapterBreakdown() {
        val typeCounts = getChapterTypeCounts()
        AppLogger.d(TAG, "[Pagination] Chapter breakdown: " +
            "CONTENT=${typeCounts[ChapterType.CONTENT] ?: 0}, " +
            "COVER=${typeCounts[ChapterType.COVER] ?: 0}, " +
            "NAV=${typeCounts[ChapterType.NAV] ?: 0}, " +
            "FRONT_MATTER=${typeCounts[ChapterType.FRONT_MATTER] ?: 0}, " +
            "NON_LINEAR=${typeCounts[ChapterType.NON_LINEAR] ?: 0}")
    }
    
    private fun logVisibilitySettings() {
        val hiddenCounts = getHiddenChapterCounts()
        val totalHidden = hiddenCounts.values.sum()
        
        AppLogger.d(TAG, "[Pagination] Visibility settings: " +
            "cover=${_currentVisibilitySettings.includeCover}, " +
            "frontMatter=${_currentVisibilitySettings.includeFrontMatter}, " +
            "nonLinear=${_currentVisibilitySettings.includeNonLinear}")
        
        if (totalHidden > 0) {
            AppLogger.d(TAG, "[Pagination] Hidden chapters: $totalHidden total " +
                "(${hiddenCounts.entries.joinToString { "${it.key}=${it.value}" }})")
        }
    }

    companion object {
        private const val TAG = "ChapterIndexProvider"
    }
}
