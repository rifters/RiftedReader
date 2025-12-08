Flex Pagination + Hard Breaks + Node‑Walking Slicing

1. Flex Pagination

.paginator {
  display: flex;
  flex-direction: row;
  overflow-x: auto;
  scroll-snap-type: x mandatory;
  width: 100vw;
  height: 100vh;
}

.page {
  flex: 0 0 100%;
  height: 100%;
  scroll-snap-align: start;
}

Explicit page boundaries

Smooth snap navigation

Conveyor belt logic maps directly to slots

2. Hard Breaks at Chapter Boundaries

<section data-chapter="1">Chapter 1 content…</section>
<section data-chapter="2">Chapter 2 content…</section>

Each <section> forces a new slice start

Long chapters can span multiple slices

Guarantees each chapter begins on a fresh page

3. Node‑Walking Routine for Slicing

function sliceWrappedWindow(wrappedHtml, fragmentIndex) {
  const temp = document.createElement("div");
  temp.style.position = "absolute";
  temp.style.visibility = "hidden";
  temp.style.width = window.innerWidth + "px";
  temp.style.top = "-9999px";
  temp.innerHTML = wrappedHtml;
  document.body.appendChild(temp);

  const slices = [];
  let sliceIndex = 1;
  const viewportHeight = window.innerHeight;

  temp.querySelectorAll("section").forEach((section, chapterIdx) => {
    const chapterId = section.getAttribute("data-chapter") || (chapterIdx + 1);

    let currentPage = createPage(fragmentIndex, sliceIndex++, chapterId);
    let currentHeight = 0;

    section.childNodes.forEach(node => {
      const clone = node.cloneNode(true);
      temp.appendChild(clone);
      const nodeHeight = clone.scrollHeight || clone.offsetHeight || 0;
      temp.removeChild(clone);

      if (currentHeight + nodeHeight > viewportHeight) {
        slices.push(currentPage);
        currentPage = createPage(fragmentIndex, sliceIndex++, chapterId);
        currentHeight = 0;
      }

      currentPage.appendChild(clone);
      currentHeight += nodeHeight;
    });

    if (currentPage.childNodes.length > 0) {
      slices.push(currentPage);
    }
  });

  document.body.removeChild(temp);
  return slices;
}

function createPage(fragmentIndex, sliceIndex, chapterId) {
  const page = document.createElement("div");
  page.className = "page";
  page.setAttribute("data-fragment", fragmentIndex);
  page.setAttribute("data-slice", sliceIndex);
  page.setAttribute("data-chapter", chapterId);
  return page;
}

Walk DOM nodes inside each <section>

Fill slices until viewport height reached

Start new slice at chapter boundaries

Metadata: data-fragment, data-slice, data-chapter

4. Conveyor Integration

const conveyorWindows = [];
let currentWindowIndex = 0;

function loadWindow(chapters, fragmentIndex) {
  const wrappedHtml = wrapChapters(chapters);
  const slices = sliceWrappedWindow(wrappedHtml, fragmentIndex);
  conveyorWindows[fragmentIndex] = slices;
}

function shiftForward() {
  conveyorWindows.shift();
  const nextChapters = getNextFiveChapters();
  const newIndex = currentWindowIndex + 5;
  loadWindow(nextChapters, newIndex);
  currentWindowIndex++;
}

Startup: load Window 0 (chapters 1–5)

Steady state: when Window 3 is active, drop Win0 and create Win5

Each new window sliced the same way

5. Formatting Preservation

cloneNode(true) ensures bold, italics, headings, paragraphs, images, and styles remain intact

No stripping of tags — just redistribution into .page containers

Edge cases: very tall elements may need special handling

🚀 End‑to‑End Flow

Extract 5 chapters

Wrap each in <section>

Slice with node‑walking routine (hard breaks at chapter starts)

Output .page divs with metadata

Conveyor manages windows of slices

Flex paginator renders pages with snap navigation