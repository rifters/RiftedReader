# Pagination & JS Paginator Architecture – Planning Notes

> High-level design + intent only. This is **not** an implementation doc yet.  
> Purpose: capture current understanding and desired direction so future refactors are deliberate instead of reactive debugging.

---

## 1. Context

The reader uses:

- `WebView` + in-page JavaScript paginator
- CSS column-based horizontal pagination
- Kotlin-side managers:
  - `ReaderViewModel`
  - `ContinuousPaginator`
  - `SlidingWindowPaginator`
  - `WindowBufferManager`
  - `ReaderActivity` + `ReaderPageFragment`

Current behavior:

- Each “window” is an HTML assembly of several chapters (e.g. chapters 5–9 → window1).
- HTML is sent to the WebView.
- The JS paginator initializes and turns that HTML into horizontally scrollable “pages” using CSS columns / transforms.
- Hardware keys / gestures drive navigation, which interacts with both:
  - Kotlin window managers, and
  - the JS paginator.

Pain points observed during debugging:

- Windows like **window1** sometimes appear **blank**, even though:
  - HTML is assembled correctly,
  - window buffers are populated,
  - JS paginator reports “ready” and gives a non-zero page count.
- Logs show the pipeline as “successful,” but the **final visual result** is wrong.
- The JS paginator appears to be doing a lot of work (reflows, re-initializations) and may be over-responsible for state and navigation.

---

## 2. Hypotheses about failure modes

We want to keep these in mind when redesigning:

1. **Android view / fragment issues**
   - Correct window is logically active, but the `Fragment`/`WebView` is:
     - off-screen,
     - overlapped,
     - zero-sized,
     - or otherwise not drawing.

2. **JS pagination / DOM visibility issues**
   - DOM and HTML are correct, but:
     - `#window-root` is mis-configured,
     - CSS columns / transforms move content out of the viewport,
     - visibility/`display` toggles remain in a “hidden” state.

3. **State drift between Kotlin and JS**
   - Kotlin thinks: “we are in window1, page k”
   - JS paginator has its own notion of:
     - _window index_,
     - _current page_,
     - _root selector_ and layout state,
   - and they can get out of sync.

Key takeaway: **blank window** is not a single bug type; it’s a symptom that can originate in any of these layers.

---

## 3. Current architecture (simplified mental model)

**Inputs:**

- EPUB chapters → Kotlin parsers.
- `ContinuousPaginator` / `SlidingWindowPaginator` decide chapter-to-window mapping.
- Kotlin assembles window HTML.

**Pipeline:**

1. **Window assembly (Kotlin)**
   - Chapters → window HTML (`#window-root`, sections for each chapter).
   - Stored / cached by `WindowBufferManager`.

2. **Fragment / WebView setup (Kotlin / Android view layer)**
   - `ReaderPageFragment`:
     - Receives `windowIndex`.
     - Obtains HTML (window payload) from `ReaderViewModel`.
     - Loads HTML into `WebView`.

3. **JS paginator (in WebView)**
   - Configured with:
     - mode: WINDOW or CHAPTER,
     - window index,
     - (sometimes) chapter index,
     - root selector (e.g. `#window-root`).
   - Applies CSS columns / transforms.
   - Determines page count and current page.
   - Exposes callbacks back to Kotlin.

4. **Navigation controls**
   - Volume keys / taps / swipes:
     - Ask current fragment to navigate in-page when possible.
     - Ask Kotlin managers to move to next/prev window when at edges.

Currently, the JS paginator appears to hold **too much “knowledge”** about windows, modes, and navigation, rather than behaving as a relatively pure layout engine.

---

## 4. Desired direction

**Goal:**  
Move toward an architecture where:

- Kotlin / domain managers are the **source of truth** for:
  - windows,
  - chapters,
  - global pages,
  - navigation decisions.
- The JS paginator is a **focused layout + page-indexing engine**:
  - Given a block of HTML and a viewport, it:
    - computes pages,
    - applies column layout,
    - moves between pages when instructed.

High-level design target:

1. **Content assembly (Kotlin):**
   - Responsible for creating clean, predictable window HTML:
     - e.g. `<div id="window-root" data-window-index="N"> … </div>`
   - Does *not* handle page math or column logic.

2. **Paginator core (JS):**
   - Given `rootElement`, viewport, and options:
     - Apply column layout (CSS columns or equivalent),
     - Compute `pageCount`,
     - Manage `currentPageIndex`.
   - Exposes simple API:
     - `init(root, options)`
     - `getPageCount()`
     - `goToPage(index, { smooth })`
     - `onPageChanged(callback)`
   - Does **not** decide which window is active, or which chapters belong to which window.

3. **Navigation glue (Kotlin + minimal JS bridge):**
   - Kotlin decides:
     - “Stay in this window: paginator.goToPage(nextPageIndex)”, or
     - “We hit edge: switch to next/previous window via Kotlin’s window managers.”
   - JS reacts only to these requests, and does not own global navigation logic.

Benefits we’re aiming for:

- Easier to reason about which layer is responsible when content is blank or misaligned.
- Fewer opportunities for subtle state drift between Kotlin and JS.
- Potentially fewer heavy reflows, because we can more carefully control when we re-init layout vs reusing existing state.

---

## 5. Planning ideas and potential tasks (future work)

These **are not commitments yet**—just a rough backlog of directions to explore when doing the actual refactor design.

### 5.1. Tighten the JS paginator’s responsibility

- [ ] Inventory current paginator APIs:
  - What does it know about:
    - window indices,
    - chapters,
    - modes,
    - root selectors?
- [ ] Define a slimmer, target API (e.g., `init`, `getPageCount`, `goToPage`, `setFontSize`).
- [ ] Plan how to:
  - remove window-specific logic from JS,
  - keep it focused on layout and page indexing only.

### 5.2. Strengthen Kotlin-side ownership of navigation and windows

- [ ] Ensure `ReaderViewModel` / window managers are the **only** components that:
  - know global window indices,
  - decide when to advance between windows.
- [ ] Make sure the JS side receives only:
  - the current window’s root element,
  - high-level pagination options (not global app state).

### 5.3. Debuggability / observability improvements

- [ ] Design debug-only instrumentation:
  - per-window visual markers (background colors, banners),
  - WebView state logs (size / visibility / page index).
- [ ] Ensure we can flip paginator off (or into a simpler mode) to:
  - compare raw `window-root` HTML vs paginated output.

### 5.4. Performance considerations

- [ ] Identify where reflows are triggered:
  - font-size changes,
  - window switches,
  - TTS highlight changes.
- [ ] Define where we can:
  - reuse existing layout state,
  - avoid full re-init when only small changes occur.

---

## 6. Non-goals / constraints (for now)

To keep focus:

- We are **not** planning to:
  - introduce a custom `WebView` pool at this stage.
  - change core EPUB parsing or chapter extraction.
  - rewrite everything to a new pagination engine without clear wins.

- JL-level changes should:
  - integrate with existing `ContinuousPaginator` / `SlidingWindowPaginator` concepts,
  - remain compatible with the current window/buffer design.

---

## 7. Open questions to answer later

Things future planning should explicitly resolve:

1. **Exact division of responsibilities:**
   - Where is the clear boundary between:
     - Kotlin windows/buffers, and
     - JS paginator?

2. **How to migrate gradually:**
   - Can we introduce a “v2” paginator API and incrementally route calls through it, instead of a big-bang rewrite?

3. **Testing strategy:**
   - How do we verify that:
     - raw window HTML is correct,
     - paginator’s column layout matches expectations,
     - navigation across windows and in-page pages remains consistent?

---

**Summary:**  
These notes are a reminder that we want the JS paginator to act more like a **layout engine and page math helper** than a co-owner of navigation and global state. Kotlin/domain managers should drive windows and navigation, with clear, minimal contracts to the JS side. This is the direction for any future refactor to make pagination more predictable, debuggable, and less fragile.