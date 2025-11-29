# Deterministic Sliding Window Pagination with Race Condition Guards

## Overview

This document describes the deterministic sliding-window pagination infrastructure added in PRs #180, #181, and #182. The goal is to prevent RecyclerView/continuous pagination race conditions by introducing:

1. A single source-of-truth paginator (`SlidingWindowPaginator`)
2. Mode transition guards (`PaginationModeGuard`)
3. Thread-safe UI synchronization (`WindowSyncHelpers`)

**Note:** ViewPager2 has been removed and replaced with RecyclerView + PagerSnapHelper. See [VIEWPAGER2_REMOVAL.md](./VIEWPAGER2_REMOVAL.md) for details.

## Problem Statement

The reader component exhibited race conditions where `windowCount` could switch unexpectedly (e.g., from 24 to 97) during window building, causing inconsistent navigation state. The logs showed the app switching between CONTINUOUS and CHAPTER_BASED flows, producing mismatched window counts.

## Components Added

### 1. SlidingWindowPaginator (`pagination/SlidingWindowPaginator.kt`)

A deterministic paginator that groups chapters into windows using a configurable `chaptersPerWindow` value.

**Key Features:**
- Single source-of-truth for window computation
- Deterministic chapter-to-window mapping
- No internal state mutations during reads

**API:**
```kotlin
class SlidingWindowPaginator(
    private var chaptersPerWindow: Int = DEFAULT_CHAPTERS_PER_WINDOW
) {
    fun recomputeWindows(totalChapters: Int): Int
    fun getWindowRange(windowIndex: Int): IntRange
    fun getWindowForChapter(chapterIndex: Int): Int
    fun setChaptersPerWindow(newSize: Int)
    fun getChaptersPerWindow(): Int
    fun getWindowCount(): Int
    fun getTotalChapters(): Int
    fun debugWindowMap(): String
}
```

**Example:**
```kotlin
val paginator = SlidingWindowPaginator(chaptersPerWindow = 5)
val windowCount = paginator.recomputeWindows(totalChapters = 120)
// windowCount = 24 (ceiling of 120/5)

val windowIndex = paginator.getWindowForChapter(chapterIndex = 7)
// windowIndex = 1 (chapters 5-9 are in window 1)

val range = paginator.getWindowRange(windowIndex = 1)
// range = 5..9
```

### 2. PaginationModeGuard (`pagination/PaginationModeGuard.kt`)

A guard that prevents pagination mode changes during window building operations.

**Key Features:**
- Reference-counted for nested operations
- Logs mode changes during builds (race condition indicator)
- Optional mode validation via LiveData

**API:**
```kotlin
class PaginationModeGuard(
    private val paginationModeLiveData: LiveData<PaginationMode>? = null
) {
    fun beginWindowBuild(): Boolean
    fun endWindowBuild()
    fun trySetMode(newMode: PaginationMode): Boolean
    fun assertWindowCountInvariant(expected: Int, actual: Int)
}
```

**Usage Pattern:**
```kotlin
paginationModeGuard.beginWindowBuild()
try {
    slidingWindowPaginator.recomputeWindows(totalChapters)
    // ... window building operations
} finally {
    paginationModeGuard.endWindowBuild()
}
```

### 3. WindowSyncHelpers (`pagination/WindowSyncHelpers.kt`)

Thread-safe helpers for synchronizing window count to UI components.

**Key Features:**
- Posts updates to main thread via Handler
- Supports both LiveData and StateFlow patterns
- Logs all sync operations for debugging

**API:**
```kotlin
object WindowSyncHelpers {
    // For LiveData-based UI
    fun syncWindowCountToUi(
        paginator: SlidingWindowPaginator,
        windowCountLiveData: MutableLiveData<Int>,
        notifyAdapterCallback: (() -> Unit)? = null
    )
    
    // For StateFlow-based UI
    fun syncWindowCountToUiFlow(
        paginator: SlidingWindowPaginator,
        updateCallback: (Int) -> Unit,
        notifyAdapterCallback: (() -> Unit)? = null
    )
}
```

### 4. Tooling: `detect_viewpager_traces.sh`

A grep-based script to locate ViewPager2/pagination references for audit purposes.

**Patterns detected:**
- ViewPager2/ViewPager references
- Pagination mode flags
- Continuous streaming markers
- Window count variables

## Integration with ReaderViewModel

The `ReaderViewModel` integrates these components in its pagination initialization:

```kotlin
class ReaderViewModel(...) : ViewModel() {
    val chaptersPerWindow = SlidingWindowPaginator.DEFAULT_CHAPTERS_PER_WINDOW
    val slidingWindowPaginator = SlidingWindowPaginator(chaptersPerWindow)
    val windowCountLiveData = MutableLiveData(0)
    val paginationModeGuard = PaginationModeGuard(paginationModeLiveData = null)

    private fun initializeContinuousPagination() {
        viewModelScope.launch {
            paginationModeGuard.beginWindowBuild()
            try {
                // ... initialization
                
                val computedWindowCount = slidingWindowPaginator.recomputeWindows(totalChapters)
                _windowCount.value = computedWindowCount
                
                AppLogger.d("ReaderViewModel", "[WINDOW_BUILD] ${slidingWindowPaginator.debugWindowMap()}")
                
                // Validate invariant
                val expectedWindowCount = ceil(totalChapters.toDouble() / chaptersPerWindow).toInt()
                if (computedWindowCount != expectedWindowCount) {
                    AppLogger.e("ReaderViewModel", "[WINDOW_BUILD] Window count assertion failed!")
                }
                
                // ...
            } finally {
                paginationModeGuard.endWindowBuild()
            }
        }
    }
}
```

## Debug Logging

The system includes comprehensive logging for debugging:

- **`[WINDOW_BUILD]`** - Window map after recompute with invariant validation
- **`[STARTUP_ASSERT]`** - Initial paginationMode and windowCount on ReaderActivity creation
- **SlidingWindowPaginator** tag - Window computation logs
- **PaginationModeGuard** tag - Build state changes
- **WindowSyncHelpers** tag - UI sync operations

## Testing

Unit tests are provided for the core components:

- `SlidingWindowPaginatorTest` - 17 cases covering window computation, edge cases, and validation
- `PaginationModeGuardTest` - 16 cases covering locking, nesting, and mode change blocking

## Verification

To verify the implementation is working correctly:

1. Open a book with many chapters (e.g., 120)
2. Check logs for:
   - `SlidingWindowPaginator: totalChapters=120, chaptersPerWindow=5, windowCount=24`
   - No subsequent unexpected `windowCount` changes
3. Run `./detect_viewpager_traces.sh` to audit remaining ViewPager2 references

## Future Work

The ViewPager2 removal plan (`remove-viewpager2-plan.md`) describes transitioning to RecyclerView with the sliding window model. This infrastructure provides the foundation for that migration by:

1. Centralizing window computation logic
2. Providing guards against race conditions
3. Enabling atomic window switches

## Related Documents

- [SLIDING_WINDOW_PAGINATION_STATUS.md](./SLIDING_WINDOW_PAGINATION_STATUS.md) - Overall pagination implementation status
- [STABLE_WINDOW_MODEL.md](../complete/STABLE_WINDOW_MODEL.md) - Immutable window model design
- [JS_STREAMING_DISCIPLINE.md](../complete/JS_STREAMING_DISCIPLINE.md) - JavaScript mode enforcement

## Changelog

### PR #182 (Merged)
- Added deterministic sliding-window pagination with race condition guards
- Integrated `PaginationModeGuard` into `ReaderViewModel`
- Added ViewPager2 and RecyclerView instrumentation for gesture tracing

### PR #181 (Merged)
- Added core pagination infrastructure
- Introduced `SlidingWindowPaginator`, `PaginationModeGuard`, `WindowSyncHelpers`
- Added `detect_viewpager_traces.sh` tooling

### PR #180 (Merged)
- Initial deterministic sliding-window paginator implementation
- Added unit tests for core components
- Integrated with ReaderViewModel and ReaderActivity startup assertions
