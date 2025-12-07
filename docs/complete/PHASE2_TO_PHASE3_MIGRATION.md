# Migration Guide: Phase 2 → Phase 3 Paginator Bridge

**Date**: November 2025  
**Target**: Updating code to use new minimal paginator bridge

## Quick Summary

The `WebViewPaginatorBridge` has been **refactored for Phase 3**:

- ✅ Simplified from 700 lines → 335 lines
- ✅ Removed 15+ legacy methods (moved to Conveyor Belt)
- ✅ Added character offset APIs for bookmarks
- ✅ Changed object name: `inpagePaginator` → `minimalPaginator`

## Breaking Changes

### 1. JavaScript Object Name Change

**BEFORE (Phase 2):**
```javascript
window.inpagePaginator.method()
```

**AFTER (Phase 3):**
```javascript
window.minimalPaginator.method()
```

Update all Kotlin calls to use `minimalPaginator`.

### 2. Removed Methods (Moved to Conveyor Belt)

These methods no longer exist in `WebViewPaginatorBridge`:

```kotlin
// ❌ REMOVED - Use Conveyor Belt instead
bridge.appendChapter(webView, idx, html)
bridge.prependChapter(webView, idx, html)
bridge.jumpToChapter(webView, idx)
bridge.removeChapter(webView, idx)
bridge.clearAllSegments(webView)
bridge.loadWindow(webView, descriptor)
bridge.finalizeWindow(webView)
bridge.setInitialChapter(webView, idx)
bridge.getChapterBoundaries(webView)
bridge.getPageMappingInfo(webView)
bridge.navigateToEntryPosition(webView, pos)
bridge.reconfigure(webView, options)
bridge.reflow(webView)
bridge.reapplyColumns(webView)
```

**Migration:**
- If using chapter management → Update to use Conveyor Belt
- If managing window lifecycle → Coordinate with Conveyor Belt
- If getting chapter info → Ask Conveyor Belt or ContinuousPaginator

### 3. Architecture Change

**BEFORE (Phase 2 - Monolithic):**
```
WebView
  └─ inpagePaginator (3000+ lines)
      ├─ Chapter streaming
      ├─ Pagination layout
      ├─ Window management
      └─ Navigation
```

**AFTER (Phase 3 - Separated):**
```
WebView
  ├─ minimalPaginator (500 lines)
  │   ├─ Page layout
  │   └─ Navigation
  │
└─ Conveyor Belt (Android)
    ├─ Chapter management
    ├─ Window streaming
    └─ Window transitions
```

## Code Migration Examples

### Example 1: Getting Current Position

**BEFORE (Phase 2):**
```kotlin
// Could do complex mapping
val mapping = bridge.getPageMappingInfo(webView)
val pos = EntryPosition(
    mapping.chapterIndex,
    mapping.inPageIndex
)
```

**AFTER (Phase 3):**
```kotlin
// Simple page-based position (Conveyor provides context)
val page = bridge.getCurrentPage(webView)
val total = bridge.getPageCount(webView)
val progress = page.toFloat() / total

// For bookmarks - use character offsets
val charOffset = bridge.getCharacterOffsetForPage(webView, page)
```

### Example 2: Changing Font Size

**BEFORE (Phase 2):**
```kotlin
// Manual reflow management
bridge.setFontSize(webView, 18)
val reflow = ReconfigureOptions(fontSize = 18, preservePosition = true)
bridge.reconfigure(webView, reflow)
```

**AFTER (Phase 3):**
```kotlin
// Simple, direct
bridge.setFontSize(webView, 18)
// Character offset remains valid after reflow
```

### Example 3: Restoring Bookmark

**BEFORE (Phase 2):**
```kotlin
// Used page indices (fragile)
val page = bookmark.pageIndex
bridge.goToPage(webView, page)
// ⚠️ Wrong if font changed!
```

**AFTER (Phase 3):**
```kotlin
// Use character offsets (stable)
val charOffset = bookmark.charOffset
bridge.goToPageWithCharacterOffset(webView, charOffset)
// ✅ Works correctly even after font changes
```

### Example 4: Chapter Navigation

**BEFORE (Phase 2):**
```kotlin
// Direct chapter jumps
fun jumpToChapter(chapter: Int) {
    bridge.jumpToChapter(webView, chapter, smooth = true)
}
```

**AFTER (Phase 3):**
```kotlin
// Coordinate with Conveyor Belt
fun jumpToChapter(chapter: Int) {
    // Conveyor Belt handles chapter transitions
    conveyor.navigateToChapter(chapter)
    // Conveyor will call bridge.goToPage() as needed
}
```

## New APIs to Use

### Character Offset APIs (Phase 3)

These are **essential** for proper bookmark/progress handling:

```kotlin
// Get stable character offset for any page
val offset: Int = bridge.getCharacterOffsetForPage(webView, pageIndex)

// Jump to page containing a character offset
bridge.goToPageWithCharacterOffset(webView, offset)
```

**Benefits:**
- ✅ Bookmarks survive font size changes
- ✅ Progress persists across device rotation
- ✅ Character-level precision

### Simpler Navigation

```kotlin
// Basic navigation methods (unchanged)
bridge.goToPage(webView, index, smooth = false)
bridge.nextPage(webView)
bridge.prevPage(webView)
```

## Migration Checklist

For each file that used `WebViewPaginatorBridge`:

### 1. Update Method Calls

- [ ] Replace `window.inpagePaginator` → `window.minimalPaginator`
- [ ] Remove calls to deleted methods
- [ ] Update to new character offset APIs

### 2. Update Bookmark Handling

- [ ] Change from page indices to character offsets
- [ ] Update `Bookmark` entity to use `charOffset: Int`
- [ ] Update bookmark save/restore logic
- [ ] Test bookmarks across font size changes

### 3. Update Progress Tracking

- [ ] Use character offsets instead of page indices
- [ ] Test progress across font changes
- [ ] Update progress calculation to use stable offsets

### 4. Update Window Management

- [ ] Remove window lifecycle management from bridge
- [ ] Integrate with Conveyor Belt for windows
- [ ] Test window transitions

### 5. Testing

- [ ] Verify bookmark persistence
- [ ] Test font size changes
- [ ] Test device rotation
- [ ] Test chapter navigation (via Conveyor Belt)
- [ ] Check error handling for unimplemented methods

## Common Errors & Solutions

### Error: "Cannot find method appendChapter"

**Cause**: Method was removed (moved to Conveyor Belt)

**Solution**: 
```kotlin
// Don't do this anymore:
bridge.appendChapter(webView, idx, html)  // ❌

// Instead, use Conveyor Belt:
conveyor.loadChapter(idx, html)  // ✅
```

### Error: "window.inpagePaginator is undefined"

**Cause**: JavaScript object name changed

**Solution**: Update JavaScript to use `minimalPaginator`
```javascript
// Before:
window.inpagePaginator  // ❌

// After:
window.minimalPaginator  // ✅
```

### Bookmark jumps to wrong page after font change

**Cause**: Using page indices instead of character offsets

**Solution**: Store and restore character offsets
```kotlin
// Before (wrong):
val page = bookmark.pageIndex  // ❌ Changes with font

// After (correct):
val offset = bookmark.charOffset  // ✅ Stable across font changes
bridge.goToPageWithCharacterOffset(webView, offset)
```

## Files to Update

Search for these patterns and update:

1. **`inpagePaginator`** → Replace with `minimalPaginator`
   ```bash
   grep -r "inpagePaginator" app/src/main
   ```

2. **Method calls removed** → Find and replace
   ```bash
   grep -r "appendChapter\|prependChapter\|jumpToChapter" app/src/main
   ```

3. **Bookmark handling** → Update to character offsets
   ```bash
   grep -r "pageIndex\|entryPosition\|pageMapping" app/src/main
   ```

4. **Window management** → Coordinate with Conveyor Belt
   ```bash
   grep -r "loadWindow\|finalizeWindow\|WindowDescriptor" app/src/main
   ```

## Testing Strategy

### Unit Tests

Update `WebViewPaginatorBridgeTest.kt`:

```kotlin
// Test character offset APIs
@Test
fun testCharacterOffsetStableAcrossFontChange() {
    val offset1 = bridge.getCharacterOffsetForPage(webView, 5)
    bridge.setFontSize(webView, 18)  // Font change
    val offset2 = bridge.getCharacterOffsetForPage(webView, findPageByOffset(offset1))
    assertEquals(offset1, offset2)  // Should be same
}
```

### Integration Tests

Update reader tests:

```kotlin
// Test bookmark persistence
@Test
fun testBookmarkRestoresCorrectlyAfterFontChange() {
    // Load book
    // Go to page 10
    val charOffset = bridge.getCharacterOffsetForPage(webView, 10)
    
    // Change font
    bridge.setFontSize(webView, 18)
    
    // Restore bookmark
    bridge.goToPageWithCharacterOffset(webView, charOffset)
    
    // Verify we're at approximately same position
    val newPage = bridge.getCurrentPage(webView)
    val newOffset = bridge.getCharacterOffsetForPage(webView, newPage)
    assertEquals(charOffset, newOffset)  // Character position stable
}
```

## Rollback Plan

If issues arise:

1. **Switch back to Phase 2** (if needed temporarily)
   - Keep old bridge code in version control
   - Tag Phase 2 commit: `git tag phase2-backup`
   - Can revert: `git checkout phase2-backup`

2. **Disable new features** if unneeded
   - Character offset APIs optional
   - Can use page indices if needed (less robust)

3. **Incremental migration**
   - Migrate one screen at a time
   - Test each migration thoroughly
   - Don't rush

## Support

For migration help:

1. **Check documentation**: `docs/complete/MINIMAL_PAGINATOR_BRIDGE.md`
2. **Review examples**: Look at refactored code samples
3. **Check tests**: See what works in test suite
4. **Ask for help**: Create issue or discuss in team

---

**Migration estimated effort**: 2-4 hours depending on codebase size  
**Testing time**: 1-2 hours  
**Total**: ~4-6 hours

**Questions?** See MINIMAL_PAGINATOR_BRIDGE.md or ask @team
