# Sliding Window Pagination - Implementation Status

## 2025-12-01 Update - WindowBufferManager Integration

- **WindowBufferManager integration complete**: The two-phase, five-window buffer lifecycle manager is now wired into the ReaderViewModel:
  - `WindowBufferManager` and `DefaultWindowAssembler` are created during continuous pagination initialization
  - Buffer is initialized with 5 windows starting from the user's current reading position
  - Phase transitions (STARTUP → STEADY) are tracked and logged
  
- **New ViewModel methods added**:
  - `onWindowBecameVisible(windowIndex)`: Called when RecyclerView scroll settles, triggers phase checks and buffer position updates
  - `maybeShiftForward(currentPage, totalPages)`: Proactively shifts buffer forward when user approaches end of window
  - `maybeShiftBackward(currentPage)`: Proactively shifts buffer backward when user approaches start of window
  - `getCachedWindowData(windowIndex)`: Returns cached WindowData from buffer if available
  
- **Cache priority order for getWindowHtml()**:
  1. WindowBufferManager cache (highest priority - managed buffer)
  2. Pre-wrapped HTML cache (initial load optimization)
  3. On-demand generation via ContinuousPaginatorWindowHtmlProvider
  
- **UI integration**:
  - `ReaderActivity`: Calls `viewModel.onWindowBecameVisible()` when RecyclerView scroll state becomes IDLE
  - `ReaderPageFragment`: Calls `viewModel.maybeShiftForward/Backward()` in `onPageChanged` callback from JS paginator
  
- **Debug logging**: All buffer operations logged with `[WINDOW_BUFFER]` prefix:
  ```bash
  adb logcat | grep "WINDOW_BUFFER"
  ```

### WindowBufferManager Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    ReaderViewModel                           │
│  ┌─────────────────────┐  ┌──────────────────────────────┐  │
│  │ WindowBufferManager │  │    DefaultWindowAssembler     │  │
│  │                     │  │                               │  │
│  │  Phase: STARTUP     │──│ Delegates to                  │  │
│  │         ↓           │  │ ContinuousPaginatorWindow     │  │
│  │  Phase: STEADY      │  │ HtmlProvider                  │  │
│  │                     │  │                               │  │
│  │  Buffer: [0,1,2,3,4]│  │ setTotalChapters()           │  │
│  │  Cache:  Map<Int,   │  │ assembleWindow()             │  │
│  │          WindowData>│  │                               │  │
│  └─────────────────────┘  └──────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

## 2025-11-29 Update - Debug Logging & Validation

- **Targeted debug logging**: Added `[PAGINATION_DEBUG]` tagged logs at key lifecycle points:
  - Before pagination runs: Log RecyclerView/item width, HTML length, measurement status
  - After pagination: Log window count, pages, first few window sizes, WebView dimensions
  - When updating adapter: Log after `notifyDataSetChanged()`, adapter item count, window count sync
  - In WebView binding: Log which HTML is loaded, confirm `onPageFinished` and `onPaginationReady` fire
  
- **Pipeline validation logging**: Each step of the pagination pipeline is now logged:
  1. RecyclerView measurement via layout change listener
  2. Window count computation in `ReaderViewModel.initializeContinuousPagination()`
  3. Adapter updates in `ReaderActivity` and `ReaderPagerAdapter`
  4. WebView HTML loading in `ReaderPageFragment.renderBaseContent()`
  5. Pagination ready callback in `PaginationBridge.onPaginationReady()`

- **Fallback logic for zero windows**: Added safeguards:
  - `ReaderPagerAdapter.getItemCount()` logs warning if zero windows
  - `ReaderViewModel.initializeContinuousPagination()` forces `windowCount=1` if zero computed for non-empty book
  - `PaginationBridge.onPaginationReady()` logs warning if zero pages reported

- **Default settings verification**: Confirmed defaults for page flipping:
  - `ReaderMode = PAGE` (page flipping enabled by default)
  - `PaginationMode = CONTINUOUS` (sliding window pagination default)
  - `continuousStreamingEnabled = true` (chapter streaming enabled by default)

- **Continuous streaming integration**: The `continuousStreamingEnabled` setting exists in `ReaderSettings` but streaming behavior is implicitly controlled by `PaginationMode.CONTINUOUS`. When in continuous mode, streaming is automatically active. The setting toggle exists for user opt-out if streaming causes issues.

### Debug Logging Tags

All debug logs use the `[PAGINATION_DEBUG]` prefix for easy filtering:
```bash
adb logcat | grep "PAGINATION_DEBUG"
```

Key log points:
- `RecyclerView layout changed` - Tracks measurement
- `Window count changed` - Tracks adapter sync
- `onBindViewHolder` - Tracks fragment binding
- `renderBaseContent` - Tracks HTML loading
- `onPageFinished` - Tracks WebView ready state
- `onPaginationReady` - Tracks JS paginator ready state
- `handleStreamingRequest` - Tracks boundary streaming

## 2025-11-20 Update

- **Pagination engine hardening**: `ContinuousPaginator` now rebalances its start/end indices so the sliding window keeps its full configured size even when the user lands near the beginning or end of a book. This avoids unexpected fragment churn and matches the behavior expected by our older tests.
- **Additional coverage**: New unit tests exercise `updateChapterPageCount`, the short-circuit path for identical counts, and `getGlobalIndexForChapterPage` before/after initialization. These make it easier to integrate WebView-reported pagination metrics with confidence.
- **Tooling setup**: Android command-line tools are now bootstrapped under `/workspaces/android-sdk` with `local.properties` pointing at that path. Gradle succeeds when run with JDK 17 (`/usr/local/sdkman/candidates/java/17.0.17-ms`), so export `JAVA_HOME` (see snippet below) until we codify this in documentation or CI.
- **DAO stubs aligned**: `TestBookMetaDao` in `LibrarySearchUseCaseTest` implements `updateReadingProgressEnhanced`, keeping search tests compiling alongside the enriched bookmark schema.
- **Boundary-aware WebView bridge**: `inpage_paginator.js` now notifies Android when readers hit the first/last WebView page, and `ReaderPageFragment` funnels those callbacks through `handleChapterBoundary`. Continuous mode can therefore advance ViewPager2 immediately, while legacy chapter-based mode still jumps to the prior chapter’s final WebView page. This removes the timing gaps that previously existed when gesture detection missed an edge case.
- **Streaming scaffolding (Phase 4 kick-off)**: The WebView paginator exposes `appendChapter`/`prependChapter` entry points, limits its in-memory deque to five chapter segments, and issues `onStreamingRequest`/`onSegmentEvicted` callbacks. On the Android side, `WebViewPaginatorBridge` now Base64-encodes HTML payloads, `ReaderPageFragment` intercepts boundary events in continuous mode to fetch adjacent chapter HTML via `ReaderViewModel.getStreamingChapterPayload`, and successful injections suppress the chapter-swap fallback. Eviction callbacks now flow through `ReaderPageFragment → ReaderViewModel → ContinuousPaginator.markChapterEvicted`, which drops the cached chapter content (unless it is the active chapter) so the sliding window stays memory-efficient and reloads evicted chapters on demand. After each streamed append/prepend, the WebView reports a per-chapter page count via `getSegmentPageCount`, letting the fragment push real measurements back into `updateChapterPaginationMetrics` for global index accuracy, and success/failure telemetry (with a basic retry) is logged for future observability.
- **Reader settings toggle & fallback UX**: A `Continuous pagination streaming (beta)` switch now lives under Reader settings, so testers can disable the sliding-window transport without downgrading the general pagination mode. When retries still fail, the fragment surfaces a long toast explaining that streaming fell back and points people to the toggle so they can opt out.
- **Streaming telemetry instrumentation**: ReaderPageFragment now logs `STREAM_START/SUCCESS/FAIL` with direction, chapter index, attempt count, and total duration. ReaderViewModel logs `STREAM_PAYLOAD_SUCCESS/FAIL` with timings for each payload request, giving us reliable latency numbers and insight into parser/cache churn.

```bash
export JAVA_HOME=/usr/local/sdkman/candidates/java/17.0.17-ms
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew test
```

### Suggestions & Next Steps

1. **Check in env guidance**: Surface the snippet above (or adopt Gradle toolchains) in `README.md` so contributors do not need to rediscover the JDK requirement.
2. **Automate SDK provisioning**: Consider a `scripts/bootstrap-android-sdk.sh` helper that installs `platforms;android-34`, `build-tools;34.0.0`, and `platform-tools` for new devcontainers/CI agents.
3. **WebView streaming focus**: With the engine stable, prioritize Phase 4 work—feeding measured chapter page counts (and eventually chunked chapter streaming) through `updateChapterPaginationMetrics` so global indices stay accurate during in-flight pagination events.

#### Phase 4 Streaming Plan (WIP)

- **Progress so far**: Streaming requests from the JavaScript paginator now stay within a single `ReaderPageFragment`. Kotlin responds by pulling the neighboring chapter’s HTML (continuous mode) and appending/prepending it via the new bridge helpers, while a `skipNextBoundaryDirection` flag guards against duplicate fallbacks. `inpage_paginator.js` enforces a max segment deque, reports per-segment page counts, and surfaces evictions back to Android; those callbacks evict the corresponding chapter from `ContinuousPaginator`’s cache so memory usage mirrors the DOM. The fragment now retries streaming once before falling back and logs structured success/failure events with duration, chapter index, and attempt counts, while measured page counts are fed into `updateChapterPaginationMetrics`. Testers can also toggle the feature off from Reader settings if they encounter persistent issues.
- **Goal**: Let a single `ReaderPageFragment` continue rendering while the WebView requests adjacent chapter chunks, reducing ViewPager churn when readers hit boundaries in continuous mode.
- **Entry point**: Reuse `PaginationBridge.onBoundaryReached`. Instead of instantly navigating via `ReaderActivity`, the fragment will (when in continuous mode) ask `ReaderViewModel` for the global page adjacent to the boundary and stream that chapter’s HTML into the existing WebView session.
- **Transport**: Extend `WebViewPaginatorBridge` with `appendChapter(html, chapterId)` / `prependChapter(...)` helpers that proxy to new JS methods on `window.inpagePaginator`. Each call hands over sanitized HTML plus metadata (chapter index, originating global page, estimated in-page count).
- **JS responsibilities**: Maintain a deque of `ChapterSegment` entries, each tagged with `data-chapter-index`. When a new segment arrives, insert it at the appropriate edge, trigger a reflow, and report revised totals back through `onPaginationReady`.
- **Back-pressure**: When the JS deque exceeds a configured limit (e.g., 3 chapters forward/back), emit a `AndroidBridge.onSegmentEvicted(chapterIndex)` callback so Kotlin can drop cached spans and mark the chapter as unloaded in `ContinuousPaginator`.
- **Error handling**: If streaming fails (parser throws, JS rejects), fall back to the existing `ReaderActivity` navigation so users never get stuck at boundaries.

## What Has Been Implemented

This PR provides the **foundation** for sliding window pagination but does **not** include full UI integration.

### ✅ Completed Components

#### 1. Core Pagination Engine
- **ContinuousPaginator** (`domain/pagination/ContinuousPaginator.kt`)
  - ✅ Sliding window management (configurable size, default: 5 chapters)
  - ✅ Global page index mapping (GlobalPageIndex ↔ PageLocation)
  - ✅ Dynamic chapter loading/unloading
  - ✅ Thread-safe operations using Mutex
  - ✅ Repagination support for font changes
  - ✅ Window state tracking (WindowInfo)

#### 2. Configuration
- **PaginationMode** enum (`domain/pagination/PaginationMode.kt`)
  - ✅ `CHAPTER_BASED`: Original behavior (default)
  - ✅ `CONTINUOUS`: New sliding window behavior
- **ReaderPreferences** integration
  - ✅ `paginationMode` field in `ReaderSettings`
  - ✅ Persisted in SharedPreferences

#### 3. Data Layer
- **BookMeta** entity extensions
  - ✅ `currentChapterIndex`: Chapter position
  - ✅ `currentInPageIndex`: Page within chapter
  - ✅ `currentCharacterOffset`: Character position for precise restoration
- **Database Migration**
  - ✅ Version 2 → 3 (adds 3 new bookmark fields)
- **BookRepository** methods
  - ✅ `updateReadingProgressEnhanced()`: Saves enhanced bookmark info
- **BookMetaDao** query
  - ✅ `updateReadingProgressEnhanced()`: Database query for enhanced bookmarks

#### 4. Testing
- **ContinuousPaginatorTest** (11 unit tests)
  - ✅ Initialization and chapter metadata loading
  - ✅ Window loading at book start, middle, end
  - ✅ Global page navigation with window shifting
  - ✅ Chapter navigation
  - ✅ Page content retrieval
  - ✅ Page location mapping
  - ✅ Total page count calculation
  - ✅ Window unloading verification

#### 5. Documentation
- **SLIDING_WINDOW_PAGINATION.md**
  - ✅ Architecture overview
  - ✅ API documentation
  - ✅ Integration guide
  - ✅ Performance considerations
  - ✅ Testing strategy

---

## ❌ Not Yet Implemented

The following components are **required** for a complete, user-facing implementation:

### 1. ViewModel Integration
- [x] Refactor `ReaderViewModel` to support both pagination modes *(completed in PR #215 and this update)*
- [x] Initialize `ContinuousPaginator` based on `paginationMode` *(completed)*
- [x] Add WindowBufferManager integration for two-phase buffer lifecycle *(completed 2025-12-01)*
- [ ] Update page state flows for global indices
- [ ] Implement repagination on font size changes
- [ ] Add position preservation logic

### 2. UI Updates
- [ ] Update `ReaderActivity` page indicator format
  - Current: "Page X of Y"
  - Needed: "Chapter X, Page Y of Z (P%)"
- [ ] Modify slider to use global page indices
- [ ] Update button navigation (next/prev page)
- [ ] Add percentage display for overall progress

### 3. Adapter Changes
- [ ] Update `ReaderPagerAdapter` to support global indices
- [ ] Create fragments for global pages vs. chapters
- [ ] Handle dynamic fragment creation/destruction

### 4. Fragment Modifications
- [x] Extend `ReaderPageFragment` for continuous mode *(partial - buffer shift calls added)*
- [ ] Support dynamic chapter loading in fragments
- [ ] Handle WebView streaming for chapter boundaries

### 5. Navigation
- [ ] Update TOC navigation to use global indices
- [ ] Update bookmark navigation (when implemented)
- [ ] Update search navigation (when implemented)

### 6. WebView Integration
- [ ] Extend `WebViewPaginatorBridge` for streaming
- [ ] Support append/remove of chapter content
- [ ] Handle seamless transitions at chapter boundaries
- [ ] Track intra-chapter pagination in continuous mode

### 7. Settings UI
- [ ] Add pagination mode toggle in settings
- [ ] Add description of continuous pagination mode
- [ ] Add warning about experimental status

### 8. Testing
- [ ] Integration tests with real EPUB files
- [ ] Memory usage tests (10-500 chapter books)
- [ ] Font size change position preservation tests
- [ ] Bookmark save/restore tests
- [ ] Performance benchmarks

---

## Why Incomplete?

This is a **major architectural change** that touches many core components. The implementation was broken into phases:

1. **Phase 1 (This PR)**: Foundation - Core logic, data structures, tests
2. **Phase 2 (Future)**: ViewModel integration and state management
3. **Phase 3 (Future)**: UI and navigation updates
4. **Phase 4 (Future)**: WebView streaming and seamless boundaries
5. **Phase 5 (Future)**: Polish, optimization, full testing

Completing all phases in a single PR would result in:
- 1000+ line changes across 20+ files
- High risk of introducing bugs
- Difficult to review
- Hard to test incrementally

---

## How to Test Current Implementation

Although the UI is not integrated, you can test the core logic:

### Run Unit Tests
```bash
./gradlew app:testDebugUnitTest --tests ContinuousPaginatorTest
```

### Test in Code
```kotlin
// In a coroutine scope
val paginator = ContinuousPaginator(bookFile, parser, windowSize = 5)
paginator.initialize()

// Load initial window
val globalPage = paginator.loadInitialWindow(chapterIndex = 0)

// Navigate
paginator.navigateToGlobalPage(10)

// Check window state
val windowInfo = paginator.getWindowInfo()
println("Loaded chapters: ${windowInfo.loadedChapterIndices}")
println("Total global pages: ${windowInfo.totalGlobalPages}")

// Get content
val content = paginator.getPageContent(globalPage)
println("Content: ${content?.text}")
```

---

## How to Enable (When Fully Implemented)

Once all phases are complete, users will enable via settings:

```kotlin
// Programmatically
readerPreferences.updateSettings { settings ->
    settings.copy(paginationMode = PaginationMode.CONTINUOUS)
}
```

Or via UI: **Settings → Reading → Pagination Mode → Continuous**

---

## Risks and Mitigations

| Risk | Mitigation |
|------|------------|
| Breaking existing pagination | Feature flag defaults to `CHAPTER_BASED` |
| Memory leaks | Comprehensive testing, window size limits |
| Poor performance with large books | Window sizing, lazy loading, disk caching |
| Position loss on font changes | Character offset tracking |
| Complex state management | Immutable data structures, clear ownership |

---

## Next Steps

To complete this feature:

1. **Continue ViewModel integration** (Phase 2)
2. **Update UI components** (Phase 3)
3. **Test with real books** (various sizes)
4. **Gather user feedback** (beta testers)
5. **Optimize performance** (profiling, benchmarks)
6. **Document for users** (help screen, tutorial)

---

## Questions?

See `SLIDING_WINDOW_PAGINATION.md` for detailed technical documentation.

For issues or questions, ping @rifters or create an issue.
