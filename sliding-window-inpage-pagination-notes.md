# Sliding-window in-page pagination design notes

These are notes based on our recent debugging and design discussion for `RiftedReader`’s WebView reader, in-page pagination, and sliding-window chapter loading.

## 1. Concepts and terminology

### WebView window vs. chapter vs. in-page

- **Window**  
  A contiguous subset of chapters that are wrapped into a single HTML document and loaded into a single WebView/fragment.  
  - Identified by a `windowIndex` (e.g. `fragmentPage = 190`).
  - Example: Window 190 might contain chapters 84–88.

- **Chapter**  
  A logical chapter in the book.  
  - Identified by a `chapterIndex` (e.g. `84`).
  - In the DOM, represented by `section[data-chapter-index="84"]`.

- **In-page index (sub-page)**  
  A “page” within the continuous content of a single window.  
  - Identified by `currentPage` / `pageInWindowIndex` (`0..pageCount-1`).
  - `pageCount` is the number of in-page slices for that window (e.g. `16`).

### DOM structure inside a window

For sliding-window mode, you have:

```html
<body>
  <section id="chapter-84" data-chapter-index="84">…</section>
  <section id="chapter-85" data-chapter-index="85">…</section>
  <section id="chapter-86" data-chapter-index="86">…</section>
  …
</body>
```

or optionally wrapped in an explicit window root:

```html
<body>
  <div id="window-root" data-window-index="190">
    <section data-chapter-index="84">…</section>
    <section data-chapter-index="85">…</section>
    …
  </div>
</body>
```

**Important:**  
One WebView window == one HTML document for that window. Inside that document you can (and should) preserve multiple `section[data-chapter-index]` elements for the chapters in that window.

## 2. What “segments” mean (and why “one HTML != one segment”)

The in-page paginator (`inpage_paginator.js`) uses an internal notion of **segments**. A “segment” is *not* the same as a WebView or HTML document:

- A segment is an internal unit the paginator uses for:
  - Page counting,
  - Chapter detection / mapping,
  - Jump to chapter.

Originally, `wrapExistingContentAsSegment()` may have:

- Wrapped *all* content into a single synthetic wrapper (one big segment),
- Effectively hiding the original `<section data-chapter-index>` structure.

The fix you were looking at:

> Preserve existing `section[data-chapter-index]` instead of wrapping everything into one segment.

means:

- Do **not** destroy or nest all sections into a single anonymous wrapper.
- Treat each `section[data-chapter-index]` as a logical unit (or at least keep them intact) so that:
  - `getCurrentChapter()` can use them,
  - `getLoadedChapters()` knows where each chapter starts/ends,
  - `jumpToChapter()` can scroll to the right place.

You still have:

- 1 WebView per window,
- 1 HTML document per window,

but potentially **multiple segments/sections** inside that one document.

This is compatible with the sliding-window approach and does *not* require multiple WebViews.

## 3. Absolute positioning and chapter detection

The merged change you described:

- Uses **absolute positioning** for chapter-related calculations:
  - `scrollLeft + getBoundingClientRect().left` for horizontal layouts,
  - Similarly with `scrollTop` / `top` for vertical.
- Applies this to:
  - `getSegmentPageCount()`,
  - `getLoadedChapters()`,
  - `jumpToChapter()`,
  - And uses viewport center in `getCurrentChapter()`.

Why this is good:

- Layout is continuous across multiple sections in the same window.
- Using bounding rects plus scroll offset gives you **scroll-independent** chapter boundaries.
- It improves:
  - “Which chapter is currently centered in the viewport?”
  - “Which pages belong to which chapter?”
  - “Where should I scroll when asked to jump to chapter X?”

This is orthogonal to the bug where you can only advance ~5 in-page pages; that bug is about state (`currentPage`), not layout.

## 4. The “only 5 pages then freeze” bug

From the logs:

- `pageCount = 16`,
- Navigation goes fine for pages 1 → 2 → 3 → 4 → 5,
- After that:
  - JS logs: `nextPage called - currentPage=4 ... goToPage - index=5, safeIndex=5`,
  - Calls `AndroidBridge.onPageChanged(page=5)`,
  - But `window.inpagePaginator.getCurrentPage()` still returns `4`,
  - Android logs `currentPage=4/16` and keeps calling `nextPage()`,
  - JS keeps calling `goToPage(5)` again and again,
  - Visually: you never move past that point.

Conclusion:

- `goToPage()` is not correctly updating the canonical `currentPage` (or updates a shadowed variable).
- `nextPage()` keeps reading `currentPage = 4` and recomputing index 5,
- `getCurrentPage()` also reads 4, so Android believes you are still on page 4.

This is a **state management bug inside `inpage_paginator.js`** and is independent of:

- TOC vs chapter structure,
- Sliding-window vs single-chapter documents,
- Absolute positioning vs older methods.

### Likely fix pattern

In `inpage_paginator.js`, ensure:

```js
let currentPage = 0; // shared state

function getCurrentPage() {
  return currentPage;
}

function goToPage(index, smooth) {
  const pageCount = getPageCount();
  const safeIndex = Math.max(0, Math.min(index, pageCount - 1));

  // THIS MUST HAPPEN:
  currentPage = safeIndex;

  const targetY = computeScrollOffsetForPage(safeIndex);
  scrollTo(targetY, smooth);

  if (AndroidBridge && AndroidBridge.onPageChanged) {
    AndroidBridge.onPageChanged(safeIndex);
  }
}

function nextPage() {
  const pageCount = getPageCount();
  const target = Math.min(currentPage + 1, pageCount - 1);
  return goToPage(target, false);
}
```

Key points:

- There must be exactly one authoritative `currentPage` variable/state.
- `goToPage` must assign `currentPage = safeIndex` on every successful navigation.
- `getCurrentPage` must return exactly that variable.
- `nextPage` / `prevPage` must use `currentPage` from that shared state.

Without this, you can repeatedly call `goToPage(5)` while internally remaining at `currentPage=4`, which is exactly what your logs show.

## 5. Interaction with TOC and front-matter

We also discussed that:

- Some books have TOC as chapter 0 (e.g. no cover).
- Others might have:
  - Cover, then TOC,
  - Or inline TOC inside a chapter.

Design options:

1. **Skip TOC/front-matter for pagination bounds**  
   - Mark sections with a semantic role when wrapping:

     ```html
     <section data-chapter-index="0" data-content-role="toc">…</section>
     <section data-chapter-index="1" data-content-role="front-matter">…</section>
     <section data-chapter-index="2" data-content-role="chapter">…</section>
     ```

   - In JS, find first `data-content-role="chapter"` and treat that as the starting point for pagination height / offsets.
   - TOC can still exist in DOM but doesn’t dominate pagination.

2. **Keep TOC out of the reading WebView**  
   - Don’t inject TOC sections into the reading HTML at all.
   - Use TOC only as data for a separate TOC UI/panel.
   - Simplifies the reader: WebView is “pure content”.

These decisions affect *which* content is paginated, but not the core bug where `currentPage` isn’t updated.

## 6. Sliding window and “will it get stuck like old ViewPager2?”

The old ViewPager2 model:

- One page ≈ one chapter.
- Advancing to the next chapter required:
  - Recognizing “last page of chapter”,
  - Calling `viewPager.setCurrentItem(position + 1)`.

Common issues were mixing:

- Page index,
- Chapter index,
- Readiness of content.

The sliding-window model separates concerns:

- **Within a window**:
  - JS handles `currentPage` and `pageCount` for in-page navigation.
- **Between windows**:
  - Kotlin/Android decides:
    - “Am I at last in-page index of this window?”
    - If yes, move to `windowIndex + 1`, inPageIndex = 0.

To avoid repeating the old problems:

- Keep indices explicit:
  - `fragmentPage` / pager position: **windowIndex**.
  - `currentPage`, `pageCount`: **in-page index and count**, per window, from JS.
  - `currentChapter`: logical chapter index from JS (metadata).
- On Android, do something like:

  ```kotlin
  if (isNext) {
      if (currentPage < pageCount - 1) {
          webViewPaginator.nextPage()
      } else {
          moveToWindow(windowIndex + 1, initialInPageIndex = 0)
      }
  } else {
      if (currentPage > 0) {
          webViewPaginator.prevPage()
      } else {
          moveToWindow(windowIndex - 1, initialInPageIndex = lastPageOfPrevWindow)
      }
  }
  ```

The “won’t progress” behavior only appears if:

- JS never advances `currentPage` (state bug), or
- Android never performs the window hand-off when at last in-page index.

It is **not** an inherent flaw of sliding windows or of preserving sections.

## 7. Summary of reassuring points

- **Preserving `section[data-chapter-index]`** is good and compatible with your sliding-window model. It improves chapter detection and TTS/bookmarking.
- **One wrapped HTML per window** is still the model; “segment” is an internal JS concept and can be multiple per document.
- **Absolute positioning** (using `getBoundingClientRect` plus scroll offsets) is appropriate for continuous layouts, especially horizontal scrolling.
- The “only 5 pages then freeze” behavior is almost certainly due to `currentPage` not being updated correctly inside `goToPage`, not because of TOC or sliding-window design.
- You can avoid “stuck at end of chapter” behavior you saw with old ViewPager2 by:
  - Clearly separating window navigation (Android) from in-page navigation (JS),
  - Using JS’s `currentPage/pageCount` as the sole authority for end-of-window decisions,
  - Keeping `windowIndex`, `chapterIndex`, and `currentPage` conceptually distinct.

These notes should help when revisiting the paginator implementation or when you’re nervous about changes around sliding windows and chapter structure.