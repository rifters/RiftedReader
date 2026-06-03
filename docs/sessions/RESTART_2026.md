# ⚠️ HISTORICAL — State as of 2026-05-06. See README.md for current status.

# FlexPaginator — Restart Reference (2026)

**Generated**: 2026-05-06  
**Branch**: `copilot/flex-paginator-research`  
**Purpose**: Complete state snapshot so you can resume FlexPaginator work without redoing any completed work.

---

## TL;DR

The FlexPaginator pipeline is **fully built and tested in isolation** but **zero lines of it run in the live reader**. All wiring is still TODO. The active reader still uses `minimal_paginator.js` + CSS columns. Resume work at **Phase 0 of `FLEX_PAGINATOR_INTEGRATION_ROADMAP.md`**.

---

## 1. What Is Fully Implemented (Do Not Redo)

All files below are substantive, production-quality code — not stubs.

| File | Lines | Status | What it does |
|------|-------|--------|--------------|
| `app/src/main/java/…/pagination/SliceMetadata.kt` | 89 | ✅ Complete | `PageSlice` + `SliceMetadata` data classes; `getSlice()`, `findPageByCharOffset()`, `getSlicesForChapter()`, `isValid()` |
| `app/src/main/java/…/pagination/FlexPaginator.kt` | 188 | ✅ Complete | Assembles window HTML; wraps chapters in `<section data-chapter="N">`; handles HTML and text-only content; skips empty chapters |
| `app/src/main/java/…/pagination/OffscreenSlicingWebView.kt` | 347 | ✅ Complete | Hidden 1×1 WebView; `sliceWindow()` coroutine; injects `flex_paginator.js`; 10 s timeout; parses `SliceMetadata` from JSON callback; `sanitizeCssFontFamily()` |
| `app/src/main/java/…/pagination/FlexSlicingConfig.kt` | 34 | ✅ Complete | Config data class: `viewportWidthPx`, `viewportHeightPx`, `fontSizePx`, `lineHeight`, `fontFamily`, `pagePaddingPx`; init-time validation |
| `app/src/main/assets/flex_paginator.js` | 457 | ✅ Complete | Node-walking slicing algorithm; injection guard; reads `FLEX_*` globals; dynamic height scaling from font-size/line-height; hard chapter breaks at `<section>` boundaries; calls `AndroidBridge.onSlicingComplete` / `onSlicingError` |
| `app/src/test/…/pagination/FlexPaginatorTest.kt` | 239 | ✅ Complete | 9 unit tests: invalid ranges, null returns, HTML structure, `data-chapter` attributes, text-to-HTML, empty chapters, special chars |
| `app/src/test/…/pagination/SliceMetadataTest.kt` | 193 | ✅ Complete | 13 unit tests: validation, `getSlice()`, boundary semantics, char-offset lookup, chapter filtering |

**Total: 22 / 22 unit tests passing** (last verified 2025-12-08 per `docs/architecture/FLEX_PAGINATOR_SESSION_COMPLETE.md`).

### Supporting pagination scaffolding (also complete, not FlexPaginator-specific)

- `SlidingWindowPaginator.kt` — stateless chapter→window index math, memoized
- `WindowAssembler.kt` / `DefaultWindowAssembler.kt` — interface + CSS-column-based impl (the *existing* assembler, not FlexPaginator)
- `PaginationModeGuard.kt` — race-condition guard for window build operations
- `WindowSyncHelpers.kt` — main-thread sync helpers for window count LiveData/StateFlow
- `WindowData` (inside `WindowAssembler.kt`) — already has `sliceMetadata: SliceMetadata? = null` and `isPreSliced: Boolean` fields added

---

## 2. What Is NOT Implemented (The Actual Work Remaining)

### Not in code at all
- **Feature flag** `FlexPaginatorEnabled` (default OFF) — required before any wiring
- **Wiring into `ConveyorBeltSystemViewModel`** — conveyor never calls `FlexPaginator` or `OffscreenSlicingWebView`
- **Onscreen JS navigation callbacks** — `flex_paginator.js` only calls `onSlicingComplete`/`onSlicingError`; it does **not** call `onPageChanged` or `onBoundaryReached`
- **JS tests for `flex_paginator.js`** — `tests/js/` has tests for `minimal_paginator.js` and `inpage_paginator.js` but **nothing for `flex_paginator.js`**
- **Re-slicing on typography changes** (Phases 2–3 of checklist)
- **Error recovery / rollback** (Phase 4 of checklist)
- **Loading overlay** (Phase 2 of checklist)

### Known bugs/limitations in the existing code (documented in checklist)
1. **1×1 WebView viewport** — `OffscreenSlicingWebView` creates a 1×1 pixel WebView (`LayoutParams(1, 1)` at line 43). Slicing with a 1×1 viewport will produce wrong page counts. Must be sized to real reader viewport before FlexPaginator can produce correct results.
2. **Height estimation, not DOM measurement** — `flex_paginator.js` uses constant multipliers (e.g. `PARAGRAPH_HEIGHT_PX = LINE_HEIGHT_PX * 2`) instead of `getBoundingClientRect()`. Will drift from real layout.
3. **CSS parity not guaranteed** — `injectFlexPaginatorScript()` emits minimal CSS. The onscreen reader WebView gets richer CSS from `ReaderPageFragment`'s HTML template. No contract yet ensures they match.
4. **Page-change/boundary callbacks missing in JS** — `flex_paginator.js` doesn't call `onPageChanged(pageIndex, chapterIndex, charOffset)` or `onBoundaryReached(direction)`.

---

## 3. The Active Production Reader (What FlexPaginator Must Eventually Replace)

The live reader stack — untouched by FlexPaginator:

| Component | File | Role |
|-----------|------|------|
| **`minimal_paginator.js`** | `app/src/main/assets/minimal_paginator.js` | Active JS paginator; CSS columns + scroll-snap; loaded in `ReaderPageFragment` line 1492 |
| **`PaginatorBridge.kt`** | `…/ui/reader/PaginatorBridge.kt` | Active `@JavascriptInterface`; receives `onPaginationReady`, `onBoundary` from `minimal_paginator.js` |
| **`ContinuousPaginatorWindowHtmlProvider`** | domain layer | Generates window HTML with CSS columns; called by `DefaultWindowAssembler` |
| **`ConveyorBeltSystemViewModel`** | `…/ui/reader/conveyor/ConveyorBeltSystemViewModel.kt` | 5-slot fixed buffer, `htmlCache`, window lifecycle phases; the integration target for FlexPaginator |
| **`ConveyorBeltIntegrationBridge`** | `…/ui/reader/conveyor/ConveyorBeltIntegrationBridge.kt` | Bridges `ReaderViewModel.currentWindowIndex` → conveyor; non-invasive observer |
| **`WebViewPaginatorBridge`** | `…/ui/reader/WebViewPaginatorBridge.kt` | `@Deprecated` — was the bridge for `minimal_paginator.js`; now superseded by `PaginatorBridge.kt` |

`ReaderPageFragment` injects `minimal_paginator.js` via a `<script src>` tag at line 1492. `flex_paginator.js` is **never loaded anywhere in production code**.

---

## 4. Key Planning Docs (Read These First)

| Doc | Path | What to use it for |
|-----|------|--------------------|
| **Integration Roadmap** (master) | `docs/planning/FLEX_PAGINATOR_INTEGRATION_ROADMAP.md` | Ordered phases with entry/exit criteria — start here |
| **Integration Checklist** | `docs/planning/FLEX_PAGINATOR_INTEGRATION_CHECKLIST.md` | Checkbox tracker; Phase 0 is entirely unchecked |
| **Quick Reference / API Sketch** | `docs/planning/FLEX_PAGINATOR_QUICK_REF.md` | Expected Kotlin+JS API flow |
| **Architecture** | `docs/planning/FLEX_PAGINATOR_ARCHITECTURE.md` | Parity notes and component relationships |
| **Session Complete Summary** | `docs/architecture/FLEX_PAGINATOR_SESSION_COMPLETE.md` | What was built in the Dec 2025 session (Phases 1–5 of old numbering) |
| **Settings Lock** | `docs/planning/FLEX_PAGINATOR_SETTINGS_LOCK.md` | Operation queuing design |
| **Font Size / Re-Slicing** | `docs/planning/FLEX_PAGINATOR_FONT_SIZE_CHANGES.md` | Re-slice flow on typography changes |
| **Progress Overlay** | `docs/planning/FLEX_PAGINATOR_PROGRESS_OVERLAY.md` | Loading overlay design |
| **Error Recovery** | `docs/planning/FLEX_PAGINATOR_ERROR_RECOVERY.md` | Rollback strategy |

The canonical node-walking algorithm pseudocode is also in the root-level file:  
`Flex Pagination+Hard Breaks+Node‑Walk.md`

---

## 5. Restart Implementation Plan (Ordered Steps)

Follow `FLEX_PAGINATOR_INTEGRATION_ROADMAP.md` phases in order. Do not skip Phase 0.

### Phase 0 — Viewport parity (prerequisite for correctness)

**Goal**: Make offscreen slicing produce page counts that match onscreen rendering.

1. In `ReaderPageFragment`, read real WebView dimensions after layout settles (use `ViewTreeObserver.OnGlobalLayoutListener` or `onWindowFocusChanged`). These are the viewport dimensions that matter — after padding, insets, toolbars.
2. Expose the dimensions (e.g. via a `Flow` in `ReaderViewModel` or a shared config singleton).
3. In `OffscreenSlicingWebView`, change `LayoutParams(1, 1)` → `LayoutParams(realWidth, realHeight)`.
4. Ensure `injectFlexPaginatorScript()` CSS (font, line-height, padding) is derived from the **same settings object** used to build the onscreen HTML template in `ReaderPageFragment.buildPageHtml()`.
5. Verify: pick a real EPUB chapter, compare page count from offscreen slicing vs `minimal_paginator.js`. Numbers don't need to be identical (different algorithm) but shouldn't be wildly off.

### Phase 1 — Feature flag + wiring into conveyor

1. Add `FlexPaginatorEnabled: Boolean` to reader preferences (default `false`).
2. In `ConveyorBeltSystemViewModel`, when a window is created: if flag is ON → call `FlexPaginator.assembleWindow()` then `OffscreenSlicingWebView.sliceWindow()`, validate `SliceMetadata.isValid()`, cache `WindowData` with metadata populated. If flag is OFF or slicing throws → fall back silently to existing `ContinuousPaginatorWindowHtmlProvider` path.
3. Add logging so you can see in logcat whether a window was pre-sliced or not.

### Phase 2 — JS navigation callbacks

1. Add to `flex_paginator.js`:
   - `AndroidBridge.onPageChanged(pageIndex, chapterIndex, charOffset)` — called on every page navigation
   - `AndroidBridge.onBoundaryReached(direction)` — called when at first/last page
2. Add corresponding `@JavascriptInterface` methods to a new `FlexPaginatorBridge.kt` (mirror the structure of `PaginatorBridge.kt`).
3. Connect `onBoundaryReached` to the conveyor's buffer-shift mechanism.

### Phase 3 — Write `flex_paginator.test.js`

Follow the pattern of `tests/js/inpage_paginator.test.js` (jest + jsdom). At minimum test:
- `onSlicingComplete` fires with correct page count for a known HTML snippet
- Hard breaks occur at `<section>` boundaries (N sections → at least N pages)
- `onSlicingError` fires when `#window-root` is missing
- Character offsets are monotonically non-decreasing across slices

### Phases 4–5 — Re-slicing, settings lock, overlays, error recovery

All design is already in the planning docs listed above. Implement in checklist order.

---

## 6. File Paths Quick Reference

```
app/src/main/java/com/rifters/riftedreader/
  pagination/
    FlexPaginator.kt               ← HTML assembler
    OffscreenSlicingWebView.kt     ← offscreen WebView slicer
    SliceMetadata.kt               ← PageSlice + SliceMetadata data classes
    FlexSlicingConfig.kt           ← slicing config (viewport + typography)
    WindowAssembler.kt             ← WindowData + WindowAssembler interface
    DefaultWindowAssembler.kt      ← CSS-column-based impl (existing, not flex)
    SlidingWindowPaginator.kt      ← chapter→window index math
    PaginationModeGuard.kt         ← race condition guard
    WindowSyncHelpers.kt           ← main-thread sync helpers
  ui/reader/
    ReaderPageFragment.kt          ← loads minimal_paginator.js (line 1492)
    ReaderActivity.kt              ← reader host
    ReaderViewModel.kt             ← owns ContinuousPaginator, window state
    PaginatorBridge.kt             ← active @JavascriptInterface for minimal_paginator.js
    WebViewPaginatorBridge.kt      ← @Deprecated
    conveyor/
      ConveyorBeltSystemViewModel.kt     ← 5-slot buffer, integration target
      ConveyorBeltIntegrationBridge.kt   ← non-invasive observer
      ConveyorPhase.kt
      ConveyorDebugActivity.kt

app/src/main/assets/
  flex_paginator.js      ← node-walking slicer (built, never loaded in prod)
  minimal_paginator.js   ← active CSS-column paginator
  inpage_paginator.js    ← older paginator (mostly replaced)

app/src/test/java/com/rifters/riftedreader/pagination/
  FlexPaginatorTest.kt   ← 9 tests ✅
  SliceMetadataTest.kt   ← 13 tests ✅

tests/js/
  minimal_paginator.test.js    ← JS tests for minimal_paginator.js
  inpage_paginator.test.js     ← JS tests for inpage_paginator.js
  (no flex_paginator.test.js)  ← MISSING — needs to be created

docs/planning/
  FLEX_PAGINATOR_INTEGRATION_ROADMAP.md    ← master execution plan
  FLEX_PAGINATOR_INTEGRATION_CHECKLIST.md ← checkbox tracker (Phase 0 unchecked)
  FLEX_PAGINATOR_ARCHITECTURE.md
  FLEX_PAGINATOR_QUICK_REF.md
  FLEX_PAGINATOR_SETTINGS_LOCK.md
  FLEX_PAGINATOR_FONT_SIZE_CHANGES.md
  FLEX_PAGINATOR_PROGRESS_OVERLAY.md
  FLEX_PAGINATOR_ERROR_RECOVERY.md

docs/architecture/
  FLEX_PAGINATOR_SESSION_COMPLETE.md   ← Dec 2025 session summary
  FLEX_PAGINATOR_IMPLEMENTATION.md
```
