# FlexPaginator Integration Roadmap (Master)

## Purpose

This is the **single execution roadmap** for integrating FlexPaginator into the live reader, built directly from the existing planning docs.

- It tells you **what to do next**, in what order.
- It defines **entry/exit criteria** for each phase.
- It links to the detailed planning docs for implementation specifics.

## Scope / Non-Goals

### In scope
- Wire FlexPaginator into the reader behind a **feature flag**.
- Achieve **viewport + CSS parity** between offscreen slicing and onscreen rendering.
- Implement **navigation callbacks** required for boundary detection + progress updates.
- Implement **re-slicing on typography changes** with locking, queuing, overlays.
- Implement **rollback + error recovery** so the reader never becomes unusable.

### Out of scope (until Flex is stable)
- Removing/rewriting the existing conveyor pagination (keep it as fallback).
- Any UI redesign not already described in planning.

## Source Planning Docs (authoritative)

- Core architecture: `docs/planning/FLEX_PAGINATOR_ARCHITECTURE.md`
- Master checklist (phases 1–5): `docs/planning/FLEX_PAGINATOR_INTEGRATION_CHECKLIST.md`
- Quick reference / API sketch: `docs/planning/FLEX_PAGINATOR_QUICK_REF.md`
- Settings lock + operation queuing: `docs/planning/FLEX_PAGINATOR_SETTINGS_LOCK.md`
- Font size + re-slicing flow: `docs/planning/FLEX_PAGINATOR_FONT_SIZE_CHANGES.md`
- Progress overlay design: `docs/planning/FLEX_PAGINATOR_PROGRESS_OVERLAY.md`
- Error recovery + rollback strategy: `docs/planning/FLEX_PAGINATOR_ERROR_RECOVERY.md`

## Current Repo State (starting point)

FlexPaginator Phase 1 “pipeline exists” is true, but **not production-ready**:

- ✅ Kotlin assembles window HTML and offscreen slicing returns `SliceMetadata`.
- ✅ A slicing configuration object exists (viewport + typography) and can be injected.
- ⚠️ Offscreen slicing parity is not guaranteed until we feed **real reader viewport** and **real reader typography/CSS**.
- ⚠️ Runtime callbacks (page-change/boundary) are not all implemented.
- ⚠️ JS slicing may still be estimation-based in places; it must match actual DOM layout.

Treat this roadmap as the path from “pipeline exists” → “safe to ship”.

---

## Integration Strategy (recommended)

1. **Feature flag first**: ship FlexPaginator behind a setting / build flag.
2. **Staged activation**:
   - Stage A: Flex only pre-slices and stores metadata (still render with existing behavior).
   - Stage B: Flex drives page navigation + boundary events.
   - Stage C: Flex becomes default for one format (e.g., EPUB) only.
3. **Hard fallback**: any failure drops back to the existing paginator/conveyor behavior.

---

## Phase 0 — Production Preconditions (parity + contracts)

### Goal
Make offscreen slicing produce page boundaries that match onscreen rendering.

### Entry criteria
- Existing conveyor pagination is stable.
- Flex slicing pipeline builds.

### Deliverables
- A single “reader typography + viewport” snapshot used by:
  - offscreen slicing (pre-slice)
  - onscreen WebView CSS (actual display)
- A documented **CSS parity contract** (what must be identical).

### Checklist
- [ ] Identify the **exact onscreen WebView viewport** used for reading (width/height after padding, toolbars, insets).
- [ ] Feed that viewport into offscreen slicing config.
- [ ] Identify the complete set of typography/layout inputs that affect pagination:
  - font size, line height, font family
  - page padding/margins
  - hyphenation, text-align/justification (if supported)
  - theme-dependent CSS that affects flow (avoid changing layout across themes)
- [ ] Ensure offscreen HTML uses the **same CSS generation** as onscreen.
- [ ] Define “parity verification”:
  - pick a known book + chapter
  - compare page counts and boundary positions across offscreen vs onscreen

### Exit criteria
- Offscreen and onscreen page counts match within a tight tolerance (ideally exact) for a representative sample.

### References
- `docs/planning/FLEX_PAGINATOR_ARCHITECTURE.md` (parity notes)
- `docs/planning/FLEX_PAGINATOR_INTEGRATION_CHECKLIST.md` (Phase 1 reality check)

---

## Phase 1 — Wire Flex into Window Creation (pre-slice + cache)

### Goal
When the conveyor creates a window, it can optionally:
1) assemble HTML via FlexPaginator
2) pre-slice offscreen
3) store `SliceMetadata` with the window cache

### Entry criteria
- Phase 0 parity contract exists (or you accept “tech preview” mismatch risk).

### Deliverables
- Windows have `WindowData.sliceMetadata` populated when Flex is enabled.
- Conveyor cache stores and evicts pre-sliced windows safely.
- Logging makes it obvious when a window is pre-sliced vs not.

### Checklist
- [ ] Add a runtime setting/flag: `FlexPaginatorEnabled` (default OFF).
- [ ] On window creation, if enabled:
  - [ ] assemble window HTML via FlexPaginator
  - [ ] slice via OffscreenSlicingWebView using current slicing config
  - [ ] validate SliceMetadata (reject invalid)
  - [ ] cache WindowData+SliceMetadata
- [ ] If disabled or slicing fails:
  - [ ] cache WindowData without SliceMetadata and use existing behavior

### Exit criteria
- Scrolling through several windows does not regress memory usage, eviction behavior, or stability.

### References
- `docs/planning/FLEX_PAGINATOR_ARCHITECTURE.md` (pre-slicing pipeline)
- `docs/planning/FLEX_PAGINATOR_QUICK_REF.md` (expected API flow)

---

## Phase 2 — Wire Flex into Onscreen Navigation (page + boundary events)

### Goal
Use SliceMetadata + JS navigation to make paging and boundary detection deterministic.

### Entry criteria
- Windows can be pre-sliced and cached (Phase 1).

### Deliverables
- Onscreen WebView loads window HTML and can:
  - go to page index
  - restore to (chapter, charOffset)
  - emit page changed events
  - emit reached-start/reached-end boundary events

### Checklist
- [ ] Implement/confirm JS → Kotlin callbacks for:
  - [ ] page changed (page index + current chapter/charOffset)
  - [ ] reached start boundary
  - [ ] reached end boundary
- [ ] Connect boundary events to the existing conveyor shift mechanism.
- [ ] Ensure activity-level progress saving uses a stable location tuple:
  - recommended: `(windowIndex, pageIndex, chapterIndex, charOffset)`

### Exit criteria
- You can read forward/back across multiple window shifts without:
  - broken progress
  - skipped windows
  - boundary spam
  - cache growth

### References
- `docs/planning/FLEX_PAGINATOR_QUICK_REF.md` (callbacks contract)
- `docs/complete/PAGINATOR_API.md` (general bridge patterns)

---

## Phase 3 — Typography Changes: Lock + Queue + Re-slice + Restore

### Goal
Changing font size / line height / font family triggers a controlled re-slice of buffered windows and restores the user to the same text location.

### Entry criteria
- Navigation callbacks are stable (Phase 2).

### Deliverables
- Scroll lock while settings open
- Conveyor lock while settings open / while re-slicing
- Queued operations applied once on settings close
- Re-slice buffer windows and restore position

### Checklist
- [ ] Implement locking + queuing exactly as designed:
  - [ ] scroll lock (disable interactions)
  - [ ] conveyor lock (no buffer shifts)
  - [ ] re-slice mutex (no concurrent re-slices)
  - [ ] queue combined typography changes
- [ ] Implement re-slice flow:
  - [ ] save current location tuple
  - [ ] show progress overlay
  - [ ] re-slice each buffered window
  - [ ] restore by charOffset
  - [ ] dismiss overlay + unlock

### Exit criteria
- After changing typography, the reader returns to the same sentence/spot, not just “same page”.

### References
- `docs/planning/FLEX_PAGINATOR_SETTINGS_LOCK.md`
- `docs/planning/FLEX_PAGINATOR_FONT_SIZE_CHANGES.md`
- `docs/planning/FLEX_PAGINATOR_PROGRESS_OVERLAY.md`

---

## Phase 4 — Error Recovery + Fallback

### Goal
Re-slicing failures do not break reading. The user always has a safe path forward.

### Entry criteria
- Phase 3 works in the happy-path.

### Deliverables
- Per-window rollback (keep old metadata until new metadata validated)
- Partial success behavior (some windows updated, others kept)
- Error overlay with retry/continue
- Hard fallback to existing paginator behavior for catastrophic failures

### Checklist
- [ ] Implement rollback discipline: never destroy working SliceMetadata until replacement validated.
- [ ] Surface errors and allow:
  - [ ] try again
  - [ ] continue reading
  - [ ] revert typography (optional per plan)
- [ ] Ensure conveyor locks always release even on failure.

### Exit criteria
- Simulated failures (timeout, WebView error) leave the app readable and stable.

### References
- `docs/planning/FLEX_PAGINATOR_ERROR_RECOVERY.md`

---

## Phase 5 — Ship Readiness (performance + polish)

### Goal
Make FlexPaginator shippable as a default option for at least one format.

### Entry criteria
- Phase 4 safety guarantees are in place.

### Deliverables
- Performance: re-slicing time and memory stable
- Overlay UX verified across themes
- Accessibility checks per plan
- Documentation updated

### Checklist
- [ ] Benchmark window creation + slicing
- [ ] Benchmark typography change re-slice time
- [ ] Validate across themes and font scales
- [ ] Confirm no regressions in existing conveyor logging and eviction

### Exit criteria
- FlexPaginator can be enabled by default (for a limited rollout) without major UX regressions.

### References
- `docs/planning/FLEX_PAGINATOR_INTEGRATION_CHECKLIST.md` (Phase 5)

---

## “One Page” Execution Order

If you only follow one sequence, follow this:

1) Phase 0 parity contract + real viewport/config
2) Phase 1 pre-slice on window creation + cache
3) Phase 2 callbacks + real navigation + boundary events
4) Phase 3 locks/queue/re-slice/restore
5) Phase 4 rollback + error overlay + fallback
6) Phase 5 perf + ship readiness

## Notes on Checklists

- Use `docs/planning/FLEX_PAGINATOR_INTEGRATION_CHECKLIST.md` as the detailed checkbox list.
- Use this file as the **phase gate** and “what’s next” navigator.
