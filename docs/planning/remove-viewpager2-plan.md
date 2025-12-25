# Plan: Removing ViewPager2 and Transitioning to RecyclerView with Sliding Window Model

> **STATUS: ✅ COMPLETED** (2025-11-29)
> 
> This plan has been fully implemented. See [docs/implemented/VIEWPAGER2_REMOVAL.md](docs/implemented/VIEWPAGER2_REMOVAL.md) for implementation details.

## Problem Statement

The reliance on **ViewPager2** has caused recurring technical debt and inefficiencies in the project. While attempts have been made to integrate a **sliding window model**, the continued fallback to **ViewPager2** is redundant and limits the effectiveness of a modernized approach. This results in:
- Legacy fragments tied to ViewPager2 APIs.
- Complicated adapter logic for both window- and chapter-based pagination.
- Inconsistencies in chapter navigation and in-page content rendering.
- Poor scalability for handling large datasets.

To address this, a complete migration to **RecyclerView with a Sliding Window Model** will be implemented, removing ViewPager2 entirely from the project.

---

## Plan of Action

### 1. **Audit Current Dependencies on ViewPager2**
   - **Key Components to Evaluate:**
     - `ReaderPagerAdapter` and `ReaderPageFragment`
     - Page change callbacks, navigation observers in `ReaderActivity`
     - `ViewPager2` references in XML layouts

   - **Goal:** Identify where ViewPager2 is currently being used and map out its dependencies.

   - **Deliverable:** A comprehensive list of ViewPager2-dependent classes, methods, and tests.

---

### 2. **Abstract the Pagination Logic**
   - Extract all pagination logic from existing `ReaderPagerAdapter` and `ReaderViewModel` implementations into a shared **sliding window abstraction**.
   - Design a `SlidingWindowPaginator`:
     - Manages the lifecycle of dynamic "windows" (a window might contain multiple chapters for efficiency).
     - Only loads the currently visible window + adjacent windows in memory.
     - Exposes methods for `goToWindow`, `nextWindow`, `previousWindow`, and `jumpToChapter`.

   - **Deliverable:** A `SlidingWindowPaginator` utility, decoupled from ViewPager2 or any specific UI framework.

---

### 3. **Refactor the UI to RecyclerView**
   - Replace all instances of **ViewPager2** with **RecyclerView**, which provides better flexibility and control over data rendering.
     - Utilize linear `RecyclerView.LayoutManager` with horizontal scrolling for window-based pagination.
     - Use `DiffUtil` for efficient updates to `RecyclerView.Adapter`.

   - Modify `ReaderActivity` and related view models:
     - Use **RecyclerView.Adapter** instead of `ReaderPagerAdapter`.
     - Update fragment navigation logic to be activity-driven (instead of fragment-driven ViewPager model).

   - **Deliverable:** A `RecyclerView` implementation that supports continuous sliding windows.

---

### 4. **Modernize Fragment Management**
   - Fragment management via ViewPager2 must be removed entirely:
     - Transition to dynamic `FragmentTransaction` for attaching or detaching fragments (if fragments are still used).
     - Alternatively, use **RecyclerView.ViewHolder** to handle lightweight views instead of fragments.

   - **Deliverable:** Decoupled fragment handling logic, minimizing fragment inflation costs and lifecycle complexities.

---

### 5. **Update Navigation Logic**
   - Refactor all navigation methods (`goToChapter`, `goToPage`, etc.) to rely on **SlidingWindowPaginator** APIs.
   - Ensure compatibility for:
     - Table of Contents (TOC) navigation.
     - In-page links and search results that might reference specific chapters or pages.

   - **Deliverable:** Navigation methods that are fully adapted to the new RecyclerView + Sliding Window model.

---

### 6. **Rewrite Tests**
   - Update all ViewPager2-related tests to use new **RecyclerView-based tests**.
   - Add tests for:
     - Sliding window navigation.
     - Performance under large datasets.
     - Proper cleanup and memory handling for adjacent windows.

   - **Deliverable:** A comprehensive and up-to-date test suite.

---

### 7. **Completely Remove ViewPager2**
   - Delete all ViewPager2-related dependencies, adapters, and XML layouts.
   - Remove from `build.gradle` or `build.gradle.kts` dependencies.

   - **Deliverable:** ViewPager2 is completely removed from the project.

---

## Benefits of Transitioning to RecyclerView with Sliding Window Model

1. **Improved Performance:**
   - **RecyclerView** is optimized for large datasets, with efficient view recycling and prefetching.
   - Fragment inflation costs are eliminated (when using `ViewHolders`).

2. **Better Control:**
   - RecyclerView offers more flexibility for complex layouts like sliding windows.
   - Fine-grained control over memory usage, which is critical for handling chapters/pages dynamically.

3. **Simplified Architecture:**
   - Decoupling from ViewPager2 removes unnecessary dependencies and reduces technical debt.
   - Single responsibility principles can be followed better with separate paginator logic and UI logic.

4. **Scalability:**
   - The sliding window model ensures only necessary content is loaded, making the application scalable for books with thousands of chapters or pages.

5. **Future-Proof:**
   - ViewPager2 remains fragment-oriented, while RecyclerView is aligned with modern, lightweight view architectures.
   - Easier to integrate with other Jetpack libraries and Compose-based components in the future.

---

## Project Timeline

### Phase 1: Audit and Planning (2 weeks)
- Identify all ViewPager2 dependencies.
- Draft the new architecture and migration plan.

### Phase 2: Sliding Window Abstraction (3 weeks)
- Implement and test `SlidingWindowPaginator`.

### Phase 3: UI Migration (3 weeks)
- Replace ViewPager2 with RecyclerView.
- Refactor fragment handling.

### Phase 4: Navigation Logic Update (2 weeks)
- Adapt all navigation methods to the new model.

### Phase 5: Testing and Cleanup (2 weeks)
- Update the test suite.
- Remove ViewPager2 from the project.

---

## Conclusion

By following this plan, the project will fully transition from **ViewPager2** to a **RecyclerView-based sliding window model**, resulting in more scalable, performant, and maintainable code. This move aligns the design with modern Android development practices and eliminates the complexities brought by ViewPager2.