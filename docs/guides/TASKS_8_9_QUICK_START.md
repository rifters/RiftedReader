# Tasks 8-9: Testing and Integration - Quick Start

**Status**: Ready to implement  
**Previous Completion**: Tasks 1-7 ✅ (Character offset APIs integrated)  
**Build Status**: ✅ Compiles successfully

---

## Task 8: Unit Tests for Character Offset

### Overview
Verify that character offset tracking works correctly across font changes, persistence operations, and restoration scenarios.

### Test Scenarios

#### 1. Offset Capture and Storage
```kotlin
@Test
fun testOffsetCaptureAndStorage() {
    // Arrange
    val viewModel = ReaderViewModel()
    val windowIndex = 2
    val pageInWindow = 5
    val characterOffset = 1247
    
    // Act
    viewModel.updateReadingPosition(windowIndex, pageInWindow, characterOffset)
    
    // Assert
    assertEquals(1247, viewModel.getSavedCharacterOffset(windowIndex))
}
```

#### 2. Offset Retrieval for Unknown Window
```kotlin
@Test
fun testOffsetRetrievalForUnknownWindow() {
    // Arrange
    val viewModel = ReaderViewModel()
    
    // Act
    val offset = viewModel.getSavedCharacterOffset(999)
    
    // Assert
    assertEquals(0, offset)  // Should return default
}
```

#### 3. Offset Clearing
```kotlin
@Test
fun testOffsetClearing() {
    // Arrange
    val viewModel = ReaderViewModel()
    viewModel.updateReadingPosition(1, 0, 500)
    
    // Act
    viewModel.clearCharacterOffset(1)
    
    // Assert
    assertEquals(0, viewModel.getSavedCharacterOffset(1))
}
```

#### 4. Multiple Window Offset Tracking
```kotlin
@Test
fun testMultipleWindowOffsets() {
    // Arrange
    val viewModel = ReaderViewModel()
    
    // Act
    viewModel.updateReadingPosition(0, 0, 100)
    viewModel.updateReadingPosition(1, 2, 500)
    viewModel.updateReadingPosition(2, 5, 1000)
    
    // Assert
    assertEquals(100, viewModel.getSavedCharacterOffset(0))
    assertEquals(500, viewModel.getSavedCharacterOffset(1))
    assertEquals(1000, viewModel.getSavedCharacterOffset(2))
}
```

### Test File Structure

Create: `app/src/test/java/com/rifters/riftedreader/CharacterOffsetPersistenceTest.kt`

```kotlin
package com.rifters.riftedreader

import com.rifters.riftedreader.ui.reader.ReaderViewModel
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class CharacterOffsetPersistenceTest {
    
    private lateinit var viewModel: ReaderViewModel
    
    @Before
    fun setup() {
        viewModel = ReaderViewModel()
    }
    
    // Add test methods here...
}
```

### Running Tests

```bash
# Run all character offset tests
./gradlew test -k CharacterOffsetPersistence

# Run with detailed output
./gradlew test --info -k CharacterOffsetPersistence

# View report
open app/build/reports/tests/testDebugUnitTest/index.html
```

---

## Task 9: Device Integration Testing

### Overview
End-to-end testing on actual device/emulator to verify complete workflow.

### Test Scenarios

#### 1. Basic Navigation with Offset Capture
**Steps**:
1. Open an EPUB book
2. Navigate to page 10
3. Read the content
4. Navigate backward to page 5
5. **Verify**: No crashes, content displays correctly

**Expected Behavior**:
- Navigation smooth
- Character offsets logged
- Content proper at each position

#### 2. Font Size Change with Position Preservation
**Steps**:
1. Open EPUB book and navigate to page 20
2. Tap Settings → Text Settings
3. Increase font size to 20sp
4. Read content (may reflow to different page)
5. Decrease font size back to 16sp
6. **Verify**: Position is approximately where it was before (same content visible)

**Expected Behavior**:
- Content visible after font change
- No jumps or disappearances
- Character offsets preserved

#### 3. Window Transition with Offset Capture
**Steps**:
1. Open large EPUB book (20+ chapters)
2. Navigate through first window of chapters
3. Continue navigating to transition to next window
4. Navigate backward to previous window
5. **Verify**: Content correct in each window, no skipped chapters

**Expected Behavior**:
- Windows transition smoothly
- Offsets captured at each position
- No window misalignment

#### 4. Bookmark Creation and Restoration
**Steps**:
1. Open book, navigate to middle (page 50 of 200)
2. Create bookmark at current position
3. Navigate to different section (page 100)
4. Tap bookmark to jump back
5. **Verify**: Returns to exact position (page 50), correct content

**Expected Behavior**:
- Bookmark created successfully
- Tapping bookmark returns to exact page
- Content matches original position

#### 5. App Restart with Position Persistence
**Steps**:
1. Open book and navigate to page 75
2. Create bookmark at current position
3. Close app completely
4. Reopen app
5. Open same book
6. Tap bookmark
7. **Verify**: Restores to page 75, correct content

**Expected Behavior**:
- Position persists across app restart
- Content loads at correct position
- No need to navigate again

### Test Checklist

```
[ ] Device/Emulator setup
    [ ] Android 11+ emulator created
    [ ] Storage permissions granted
    [ ] Test book files copied to device

[ ] Basic functionality
    [ ] App opens without crash
    [ ] Library displays books
    [ ] Can open book
    [ ] Navigation works (next/previous page)
    [ ] Controls visible and responsive

[ ] Character offset verification
    [ ] Check logcat for [CHARACTER_OFFSET] logs
    [ ] Verify offset values increasing/decreasing as expected
    [ ] Check for no error messages

[ ] Font change testing
    [ ] Increase font size
    [ ] Decrease font size
    [ ] Verify content repositions
    [ ] Check logs for offset adjustments

[ ] Bookmark functionality
    [ ] Create bookmark at position
    [ ] Navigate away
    [ ] Tap bookmark
    [ ] Verify exact position restoration

[ ] Persistence testing
    [ ] Create bookmark
    [ ] Close app
    [ ] Reopen app
    [ ] Verify bookmark exists
    [ ] Tap bookmark
    [ ] Verify position restored

[ ] Edge cases
    [ ] Very long chapters (10,000+ pages)
    [ ] Multiple quick navigations
    [ ] Font size extremes (10sp, 28sp)
    [ ] Mixed character sets/languages
```

### Debug Logging During Tests

Monitor logcat for character offset traces:

```bash
# In Android Studio, run app and filter logcat:
adb logcat | grep CHARACTER_OFFSET

# Or filter in Logcat tab:
- Filter: com.rifters.riftedreader
- Search: CHARACTER_OFFSET
```

Expected log output:
```
[CHARACTER_OFFSET] Position captured: page=5, offset=1247
[CHARACTER_OFFSET] Updated position: windowIndex=2, pageInWindow=5, offset=1247
[CHARACTER_OFFSET] Retrieved offset for windowIndex=2: offset=1247
[CHARACTER_OFFSET] Position restored with offset=1247
```

### Common Issues and Solutions

| Issue | Cause | Solution |
|-------|-------|----------|
| Position not persisting | ViewModel cleared on app close | Integrate with BookmarkManager for database persistence |
| Font size change crashes | WebView reflow causes error | Verify WebViewPaginatorBridge handles reflow gracefully |
| Bookmark not found | Database query fails | Check BookmarkRepository queries work correctly |
| Wrong position restored | Offset calculation incorrect | Verify JavaScript paginator offset calculations |
| Navigation freezes | Bridge call hangs | Check WebView readiness, add timeout handling |

---

## Integration with BookmarkManager

After Tasks 8-9 testing passes, integrate character offsets with persistent storage:

```kotlin
// In ReaderPageFragment
private suspend fun captureAndPersistPosition() {
    // ... existing capture code ...
    
    // NEW: Persist to database via BookmarkManager
    bookmarkManager.addBookmark(
        bookId = currentBook.id,
        page = currentPage,
        characterOffset = characterOffset,
        timestamp = System.currentTimeMillis()
    )
}

// On bookmark restoration
private suspend fun restoreBookmark(bookmark: Bookmark) {
    // Use character offset for stable positioning
    WebViewPaginatorBridge.goToPageWithCharacterOffset(
        binding.pageWebView,
        bookmark.characterOffset
    )
}
```

---

## Test Execution Plan

### Phase 1: Unit Tests (Task 8)
1. Create test file
2. Implement test methods
3. Run: `./gradlew test -k CharacterOffsetPersistence`
4. Verify: All tests pass

### Phase 2: Device Testing (Task 9)
1. Set up emulator/device
2. Build and install debug APK
3. Execute manual test scenarios
4. Document results
5. Fix any issues found

### Phase 3: Integration (Post-9)
1. Connect BookmarkManager
2. Add database persistence
3. Rerun device tests
4. Verify end-to-end flow

---

## Success Criteria

### Task 8 Success
- ✅ All unit tests pass
- ✅ No crashes with extreme offsets
- ✅ Correct offset values stored and retrieved
- ✅ Multiple windows tracked independently

### Task 9 Success
- ✅ App doesn't crash during testing
- ✅ Navigation smooth and responsive
- ✅ Character offset logs present and sensible
- ✅ Font size changes don't lose position
- ✅ Bookmarks restore to exact position
- ✅ Position persists across app restart

---

## Estimated Timeline

| Task | Effort | Duration |
|------|--------|----------|
| Task 8: Unit Tests | 2-3 hours | 1 day |
| Task 9: Device Tests | 3-4 hours | 1-2 days |
| Integration: BookmarkManager | 2-3 hours | 1 day |
| **Total** | **7-10 hours** | **3-4 days** |

---

## Files to Create/Modify

### Create
- `app/src/test/java/com/rifters/riftedreader/CharacterOffsetPersistenceTest.kt` (250-300 lines)
- Test case documentation (tracking results)

### Modify
- `ReaderPageFragment.kt` - Already done ✅
- `ReaderViewModel.kt` - Already done ✅
- `BookmarkManager.kt` - Will add integration code (future)

---

## Resources

- Character Offset Integration: `CHARACTER_OFFSET_INTEGRATION_COMPLETE.md`
- ReaderPageFragment location: `app/src/main/java/com/rifters/riftedreader/ui/reader/ReaderPageFragment.kt`
- ReaderViewModel location: `app/src/main/java/com/rifters/riftedreader/ui/reader/ReaderViewModel.kt`
- WebViewPaginatorBridge: `app/src/main/java/com/rifters/riftedreader/ui/reader/WebViewPaginatorBridge.kt`

---

**Next Step**: Begin Task 8 - Create CharacterOffsetPersistenceTest.kt with unit tests

