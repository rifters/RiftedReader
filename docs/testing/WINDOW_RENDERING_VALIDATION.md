# Window/Chapter Rendering Pipeline Validation

**Purpose:** Automated testing and validation workflow to catch window/chapter rendering issues early, reducing manual debugging churn.

**Related Issue:** Automate validation of window/chapter rendering pipeline

---

## Overview

This document describes the automated testing workflow for validating the window/chapter rendering pipeline in RiftedReader's continuous pagination mode. The tests ensure that:

1. Window count calculations are consistent across all components
2. Chapter-to-window mapping is correct for all chapters
3. Window buffer manager lifecycle works correctly
4. Window HTML content contains the expected chapters

---

## Key Pipeline Components

The rendering pipeline flows through these components:

```
ReaderViewModel
    ↓
SlidingWindowPaginator (window count/range calculations)
    ↓
WindowBufferManager (5-window buffer lifecycle)
    ↓
ReaderPagerAdapter (RecyclerView adapter, item count)
    ↓
ReaderPageFragment (renders window HTML in WebView)
```

### Component Responsibilities

| Component | Responsibility |
|-----------|---------------|
| `SlidingWindowPaginator` | Deterministic window count/range calculations |
| `SlidingWindowManager` | Pure-function chapter-to-window mapping |
| `WindowBufferManager` | Runtime buffer lifecycle (STARTUP → STEADY phases) |
| `ReaderPagerAdapter` | RecyclerView adapter, fragment instantiation |
| `ReaderPageFragment` | WebView rendering, pagination callbacks |

---

## Automated Test Suite

### Test File
`app/src/test/java/com/rifters/riftedreader/WindowRenderingPipelineTest.kt`

### Test Categories

#### 1. Window Count Validation Tests
- Verify `ceil(totalChapters / chaptersPerWindow)` calculation
- Check consistency between `SlidingWindowPaginator` and `SlidingWindowManager`
- Ensure adapter item count matches ViewModel window count

#### 2. Window Range Validation Tests
- Verify window 0 contains chapters 0-4
- Verify window 1 contains chapters 5-9
- Verify last (partial) window contains correct chapters
- Verify all windows cover all chapters without gaps

#### 3. Chapter-to-Window Mapping Tests
- Verify `getWindowForChapter()` returns correct window for all chapters

#### 4. Window Buffer Manager Lifecycle Tests
- Verify buffer initializes with 5 consecutive windows
- Verify buffer starts in STARTUP phase
- Verify transition to STEADY at center position (window 2)
- Verify cached window data is available

#### 5. Window HTML Generation Tests
- Verify HTML contains section tags for all chapters in window
- Verify HTML does NOT contain chapters from adjacent windows

#### 6. Edge Case Tests
- Single chapter book (1 window)
- Exactly 5 chapters (1 full window)
- 6 chapters (2 windows, one partial)
- Zero chapters (0 windows)

---

## Running the Tests

### Run All Pipeline Validation Tests
```bash
./gradlew app:testDebugUnitTest --tests "com.rifters.riftedreader.WindowRenderingPipelineTest"
```

### Run All Unit Tests (including existing tests)
```bash
./gradlew app:testDebugUnitTest
```

### View Test Results
Test results are saved to:
- HTML: `app/build/reports/tests/testDebugUnitTest/index.html`
- XML: `app/build/test-results/testDebugUnitTest/TEST-com.rifters.riftedreader.WindowRenderingPipelineTest.xml`

---

## JavaScript Tests

The WebView pagination logic is tested separately with Jest:

```bash
cd tests/js
npm ci
npm test
```

See `tests/js/README.md` for details on JavaScript test coverage.

---

## Debugging Blank/Stuck Windows

When a window renders blank or gets stuck:

### 1. Check Window Count Mismatch
Look for these log patterns:
```
[PAGINATION_DEBUG] WINDOW_COUNT_MISMATCH: adapter itemCount=X, expected=Y
[PAGINATION_DEBUG] WARNING: getItemCount=0 - book content may not have loaded
```

### 2. Check Fragment Lifecycle
```
[PAGINATION_DEBUG] Fragment CREATING: position=X, windowIndex=X
[PAGINATION_DEBUG] Fragment COMMITTED: position=X
[PAGINATION_DEBUG] onPageFinished fired: windowIndex=X
```

### 3. Check Window HTML Generation
```
[PAGINATION_DEBUG] getWindowHtml called: windowIndex=X
[WINDOW_HTML] Received window payload: windowIndex=X, payload=NOT_NULL
[PAGINATION_DEBUG] Wrapped HTML prepared: windowIndex=X, wrappedLength=Y
```

### 4. Check Buffer Manager State
```
[PAGINATION_DEBUG] Buffer initialized: buffer=[0, 1, 2, 3, 4], activeWindow=0
[PAGINATION_DEBUG] onEnteredWindow: globalWindowIndex=X
```

---

## Common Issues and Fixes

### Issue: Window count is 0 but chapters exist
**Cause:** `recomputeWindows()` not called after loading book metadata
**Fix:** Ensure `slidingWindowPaginator.recomputeWindows(totalChapters)` is called

### Issue: Fragment shows blank WebView
**Cause:** Window HTML not retrieved from buffer
**Fix:** Check if `WindowBufferManager.getCachedWindow(windowIndex)` returns data

### Issue: Buffer manager stuck in STARTUP phase
**Cause:** `onEnteredWindow()` not being called
**Fix:** Verify RecyclerView page change callbacks are connected

### Issue: Window boundary navigation fails
**Cause:** Edge detection in `handlePagedNavigation()` incorrect
**Fix:** Check `isPaginatorInitialized` flag and WebView readiness state

---

## Key Debug Log Tags

| Tag | Purpose |
|-----|---------|
| `[PAGINATION_DEBUG]` | Window/paginator state changes |
| `[WINDOW_HTML]` | Window HTML retrieval status |
| `[FRAGMENT_ADDED]` | Fragment lifecycle events |
| `[EDGE_DEBUG]` | Navigation boundary detection |
| `[WINDOW_NAV]` | Window transition events |

---

## Related Documentation

- `docs/SLIDING_WINDOW_PAGINATION.md` - Architecture overview
- `docs/testing/CONTINUOUS_PAGINATOR_VERIFICATION.md` - ContinuousPaginator tests
- `tests/js/README.md` - JavaScript paginator tests

---

## Future Improvements

1. **Snapshot Comparisons:** Add HTML snapshot tests for regression detection
2. **Instrumented Tests:** Add Espresso tests for actual WebView rendering
3. **CI Integration:** Add test gate to PR workflow
4. **Performance Tests:** Add timing validation for window load operations

---

**Last Updated:** 2025-12-04
