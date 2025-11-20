# Sliding Window Pagination - Implementation Guide

## Overview

This document describes the implementation of a sliding window pagination system for RiftedReader, which presents the entire book as a single continuous array of pages while keeping only a small window of chapters in memory.

## Architecture

### Core Components

#### 1. ContinuousPaginator (`domain/pagination/ContinuousPaginator.kt`)

The core pagination engine that manages the sliding window of chapters.

**Key Features:**
- Maintains a configurable window of chapters (default: 5 chapters)
- Maps global page indices to (chapter, inPageIndex) pairs
- Dynamically loads and unloads chapters as the user navigates
- Thread-safe using Kotlin coroutines and Mutex

**Public API:**
```kotlin
suspend fun initialize() // Initialize paginator, load chapter metadata
suspend fun loadInitialWindow(chapterIndex: Int): GlobalPageIndex
suspend fun navigateToGlobalPage(globalPageIndex: GlobalPageIndex): PageLocation?
suspend fun navigateToChapter(chapterIndex: Int, inPageIndex: Int = 0): GlobalPageIndex
suspend fun getPageContent(globalPageIndex: GlobalPageIndex): PageContent?
suspend fun getCurrentGlobalPage(): GlobalPageIndex
suspend fun getTotalGlobalPages(): Int
suspend fun getPageLocation(globalPageIndex: GlobalPageIndex): PageLocation?
suspend fun repaginate(): GlobalPageIndex
suspend fun getWindowInfo(): WindowInfo
```

**Data Types:**
- `GlobalPageIndex`: Type alias for Int - global page index across all chapters
- `PageLocation`: Data class containing (globalPageIndex, chapterIndex, inPageIndex)
- `WindowInfo`: Information about the currently loaded window

#### 2. PaginationMode (`domain/pagination/PaginationMode.kt`)

Enum defining the pagination strategy:
- `CHAPTER_BASED`: Original behavior, each chapter is a ViewPager page
- `CONTINUOUS`: New behavior, sliding window with global page indices

#### 3. Enhanced BookMeta

Extended the `BookMeta` entity to support precise bookmark restoration:

```kotlin
data class BookMeta(
    // ... existing fields ...
    
    // Legacy chapter-based progress
    val currentPage: Int = 0,
    val totalPages: Int = 0,
    val percentComplete: Float = 0f,
    
    // Enhanced bookmark for continuous pagination
    val currentChapterIndex: Int = 0,
    val currentInPageIndex: Int = 0,
    val currentCharacterOffset: Int = 0,
    
    // ... other fields ...
)
```

**Character Offset**: Stores the character position within the chapter content for precise position restoration even after font size changes.

## Integration Points

### Required Changes for Full Integration

#### 1. ReaderViewModel

The ReaderViewModel needs to support both pagination modes:

```kotlin
class ReaderViewModel(
    private val bookId: String,
    private val bookFile: File,
    private val parser: BookParser,
    private val repository: BookRepository,
    readerPreferences: ReaderPreferences
) : ViewModel() {
    
    // Add paginator instance
    private var continuousPaginator: ContinuousPaginator? = null
    
    // Current pagination mode
    private val paginationMode: PaginationMode 
        get() = readerPreferences.settings.value.paginationMode
    
    init {
        if (paginationMode == PaginationMode.CONTINUOUS) {
            initializeContinuousPagination()
        } else {
            buildPagination() // Existing chapter-based pagination
        }
    }
    
    private fun initializeContinuousPagination() {
        viewModelScope.launch {
            continuousPaginator = ContinuousPaginator(bookFile, parser, windowSize = 5)
            continuousPaginator?.initialize()
            
            // Load initial window based on saved bookmark
            val book = repository.getBookById(bookId)
            val startChapter = book?.currentChapterIndex ?: 0
            
            val globalPage = continuousPaginator?.loadInitialWindow(startChapter) ?: 0
            
            // Update UI state
            updateForGlobalPage(globalPage)
        }
    }
    
    private suspend fun updateForGlobalPage(globalPageIndex: GlobalPageIndex) {
        val location = continuousPaginator?.getPageLocation(globalPageIndex)
        if (location != null) {
            val content = continuousPaginator?.getPageContent(globalPageIndex)
            if (content != null) {
                _content.value = content
                _currentPage.value = globalPageIndex
                _totalPages.value = continuousPaginator?.getTotalGlobalPages() ?: 0
            }
        }
    }
}
```

#### 2. ReaderActivity Page Indicator

Update the page indicator to show "Chapter X, Page Y of Z":

```kotlin
private fun updatePageIndicator(globalPageIndex: Int) {
    viewLifecycleOwner.lifecycleScope.launch {
        val location = viewModel.getPageLocation(globalPageIndex)
        if (location != null) {
            val chapterNumber = location.chapterIndex + 1
            val inPageNumber = location.inPageIndex + 1
            val totalPagesInChapter = viewModel.getChapterPageCount(location.chapterIndex)
            
            binding.pageIndicator.text = getString(
                R.string.reader_page_indicator_with_chapter,
                chapterNumber,
                inPageNumber,
                totalPagesInChapter
            )
            
            // Secondary: show overall progress
            val totalPages = viewModel.getTotalGlobalPages()
            val percent = ((globalPageIndex + 1) * 100f / totalPages).toInt()
            binding.percentIndicator.text = "$percent%"
        }
    }
}
```

#### 3. ReaderPagerAdapter

The adapter needs to work with global page indices in continuous mode:

```kotlin
class ReaderPagerAdapter(
    activity: FragmentActivity,
    private val viewModel: ReaderViewModel
) : FragmentStateAdapter(activity) {
    
    private val paginationMode: PaginationMode
        get() = viewModel.paginationMode
    
    override fun getItemCount(): Int {
        return when (paginationMode) {
            PaginationMode.CHAPTER_BASED -> viewModel.chapterCount
            PaginationMode.CONTINUOUS -> viewModel.totalGlobalPages
        }
    }
    
    override fun createFragment(position: Int): Fragment {
        return when (paginationMode) {
            PaginationMode.CHAPTER_BASED -> {
                // Existing behavior: position = chapter index
                ReaderPageFragment.newInstance(position)
            }
            PaginationMode.CONTINUOUS -> {
                // New behavior: position = global page index
                val location = viewModel.getPageLocationSync(position)
                ReaderPageFragment.newInstanceContinuous(location)
            }
        }
    }
}
```

#### 4. ReaderPageFragment

The fragment needs to support dynamic chapter loading:

```kotlin
companion object {
    fun newInstanceContinuous(location: PageLocation): ReaderPageFragment {
        return ReaderPageFragment().apply {
            arguments = Bundle().apply {
                putInt(ARG_GLOBAL_PAGE_INDEX, location.globalPageIndex)
                putInt(ARG_CHAPTER_INDEX, location.chapterIndex)
                putInt(ARG_IN_PAGE_INDEX, location.inPageIndex)
            }
        }
    }
}
```

#### 5. TOC Navigation

Update TOC navigation to use global page indices:

```kotlin
private fun openChapters() {
    val chapters = viewModel.tableOfContents.value
    if (chapters.isEmpty()) return
    
    ChaptersBottomSheet.show(supportFragmentManager, chapters) { chapterIndex ->
        when (viewModel.paginationMode) {
            PaginationMode.CHAPTER_BASED -> {
                viewModel.goToPage(chapterIndex)
            }
            PaginationMode.CONTINUOUS -> {
                viewLifecycleOwner.lifecycleScope.launch {
                    val globalPage = viewModel.navigateToChapter(chapterIndex)
                    viewModel.goToPage(globalPage)
                }
            }
        }
    }
}
```

#### 6. Font Size Change Handling

Implement repagination on font size change:

```kotlin
fun handleFontSizeChange() {
    viewLifecycleOwner.lifecycleScope.launch {
        when (viewModel.paginationMode) {
            PaginationMode.CHAPTER_BASED -> {
                // Existing behavior - WebView handles internally
            }
            PaginationMode.CONTINUOUS -> {
                // Save current position
                val currentGlobalPage = viewModel.getCurrentGlobalPage()
                val currentLocation = viewModel.getPageLocation(currentGlobalPage)
                
                // Trigger repagination
                val newGlobalPage = viewModel.repaginate()
                
                // Navigate to new position (best-effort)
                viewModel.goToPage(newGlobalPage)
                
                showToast("Position restored after font change")
            }
        }
    }
}
```

## Database Migration

The BookDatabase version was incremented from 2 to 3. The migration adds three new fields:
- `currentChapterIndex` (INT, default: 0)
- `currentInPageIndex` (INT, default: 0)
- `currentCharacterOffset` (INT, default: 0)

Since `fallbackToDestructiveMigration()` is used, the database will be recreated on first run with the new schema.

**For production**: Implement proper migration:

```kotlin
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("""
            ALTER TABLE books ADD COLUMN currentChapterIndex INTEGER NOT NULL DEFAULT 0
        """)
        database.execSQL("""
            ALTER TABLE books ADD COLUMN currentInPageIndex INTEGER NOT NULL DEFAULT 0
        """)
        database.execSQL("""
            ALTER TABLE books ADD COLUMN currentCharacterOffset INTEGER NOT NULL DEFAULT 0
        """)
    }
}

// In getDatabase()
.addMigrations(MIGRATION_2_3)
```

## Testing Strategy

### Unit Tests

`ContinuousPaginatorTest` covers:
- ✅ Initialization
- ✅ Window loading at various positions (start, middle, end)
- ✅ Navigation between global pages
- ✅ Chapter navigation
- ✅ Page content retrieval
- ✅ Window shifting and unloading
- ✅ Total page count
- ✅ Page location mapping

### Integration Tests (TODO)

Needed:
- Test with real EPUB files (10-500 chapters)
- Test memory usage during long navigation sessions
- Test font size changes and position preservation
- Test bookmark save/restore across app restarts
- Test TOC navigation
- Test performance with large books

## Performance Considerations

### Memory Management

The window size is configurable (default: 5 chapters). For typical books:
- **Small book** (10 chapters): All chapters fit in window
- **Medium book** (50-100 chapters): Window covers ~5-10% of book
- **Large book** (200-500 chapters): Window covers ~1-2.5% of book

### Optimization Opportunities

1. **Lazy Chapter Parsing**: Currently, full chapter content is loaded into memory. Could optimize to:
   - Store only metadata initially
   - Load content on-demand when chapter enters viewport
   - Use weak references for recently accessed content

2. **Preemptive Loading**: Load chapters in the direction of user navigation:
   - If user is reading forward, prioritize loading next chapters
   - If user is reading backward, prioritize loading previous chapters

3. **Disk Caching**: Cache parsed chapter content to disk to avoid re-parsing:
   - Use content hash as cache key
   - Invalidate cache on font size/theme changes

## Known Limitations

1. **WebView Pagination**: Current implementation treats each chapter as a single page. Full integration requires:
   - Extend ContinuousPaginator to track intra-chapter pagination
   - Update WebViewPaginatorBridge to support streaming content
   - Handle chapter boundaries seamlessly in WebView

2. **Character Offset**: Currently stored but not yet used for position restoration. Requires:
   - Parser support for character offsets
   - WebView integration to scroll to character position

3. **Bookmark UI**: No user-facing bookmark management yet. Needs:
   - Bookmark list screen
   - Add/delete bookmark actions
   - Jump to bookmark functionality

## Feature Flag

The `PaginationMode` setting in `ReaderPreferences` allows toggling between modes:

```kotlin
// Enable continuous pagination
readerPreferences.updateSettings { settings ->
    settings.copy(paginationMode = PaginationMode.CONTINUOUS)
}

// Disable (revert to chapter-based)
readerPreferences.updateSettings { settings ->
    settings.copy(paginationMode = PaginationMode.CHAPTER_BASED)
}
```

Default: `CHAPTER_BASED` (original behavior)

## Future Enhancements

1. **Smart Window Sizing**: Adapt window size based on:
   - Available memory
   - Chapter sizes
   - User navigation patterns

2. **Chapter Prefetching**: Background loading of likely-to-be-accessed chapters

3. **Multi-Book Windows**: If user has multiple books open, share window budget across them

4. **Analytics**: Track:
   - Average window shift frequency
   - Memory usage patterns
   - User navigation patterns

## Rollback Strategy

If issues arise:
1. Default remains `CHAPTER_BASED` - no user impact
2. Users can disable via settings (once UI is added)
3. Database fields are additive - can be ignored if not using continuous mode
4. Code paths are isolated - can be removed without affecting chapter-based mode

## References

- Original issue: #[issue-number]
- Design discussion: [link to discussion]
- LibreraReader analysis: `LIBRERA_ANALYSIS.md`
- Architecture documentation: `ARCHITECTURE.md`
