# Testing Guide - Pagination Freeze Fix

## Quick Test Procedure

### Prerequisites
1. Build and install the updated app with the fix
2. Enable logging (logcat or app logs) to see diagnostic messages
3. Open a book with at least 5-6 pages in continuous reading mode

### Test Case 1: Navigate to Page 5 (Primary Bug Test)
**Steps**:
1. Open a book and go to the first page
2. Navigate to page 5 using the UI (slider, buttons, or gestures)
3. Observe the logs and behavior

**Expected Results**:
- ✅ Navigation completes smoothly without oscillation
- ✅ Reader stays on page 5 without bouncing to page 4
- ✅ Logs show consistent `appliedColumnWidth` values

**Log Verification**:
```
MIN_PAGINATOR:DIAGNOSTIC goToPage(5) START - appliedColumnWidth=411px, viewportWidth=411px
MIN_PAGINATOR:DIAGNOSTIC goToPage calculation: page=5, scrollPos=2055px (5 * 411px)
MIN_PAGINATOR:NAV goToPage(5) -> 5, smooth=false
```

**Failure Signs** (if bug still exists):
- ❌ Page oscillates between 4 and 5
- ❌ Different appliedColumnWidth values in different log entries
- ❌ Scroll calculation shows mismatched values

### Test Case 2: Scroll and Snap
**Steps**:
1. Navigate to any page in the middle of the book
2. Manually scroll (drag) between pages
3. Release and let it snap to the nearest page
4. Observe logs

**Expected Results**:
- ✅ Smooth snap to nearest page boundary
- ✅ No unexpected page jumps
- ✅ Logs show correct page calculation

**Log Verification**:
```
MIN_PAGINATOR:DIAGNOSTIC Scroll event - scrollLeft=2054.9px, appliedColumnWidth=411px
MIN_PAGINATOR:DIAGNOSTIC Scroll page calculation: round(2054.9 / 411) = 5, clamped to 5
MIN_PAGINATOR:DIAGNOSTIC snapToNearestPage START - appliedColumnWidth=411px, viewportWidth=411px
MIN_PAGINATOR:DIAGNOSTIC snapToNearestPage calculation: scrollLeft=2054.9px, targetPage=5 (floor(2054.9 / 411)), clampedPage=5
```

### Test Case 3: Font Size Change (Reflow Test)
**Steps**:
1. Navigate to any page (e.g., page 3)
2. Change font size (increase or decrease)
3. Verify you stay on approximately the same reading position
4. Navigate to other pages after font change
5. Observe logs

**Expected Results**:
- ✅ Position preserved after font change
- ✅ Page count recalculates correctly
- ✅ Navigation works smoothly after reflow
- ✅ Logs show appliedColumnWidth update during reflow

**Log Verification**:
```
MIN_PAGINATOR:DIAGNOSTIC REFLOW START - appliedColumnWidth=411px, viewportWidth=411px
MIN_PAGINATOR:LAYOUT_VERIFY columnWidth=411px set, [...] state.appliedColumnWidth updated to 411px
MIN_PAGINATOR:DIAGNOSTIC REFLOW AFTER LAYOUT - appliedColumnWidth=411px (was 411px), viewportWidth=411px
MIN_PAGINATOR:REFLOW Complete - pageCount=N, currentPage=3
```

### Test Case 4: Image-Heavy Content
**Steps**:
1. Open a book with images (EPUB with illustrations)
2. Navigate to a page with images
3. Wait for images to load
4. Navigate to other pages
5. Observe logs for recompute operations

**Expected Results**:
- ✅ Page count stable after images load
- ✅ No unexpected page jumps when images finish loading
- ✅ Logs show recompute operations with consistent width

**Log Verification**:
```
MIN_PAGINATOR:IMAGE_LOADING Detected N images, monitoring load events
MIN_PAGINATOR:IMAGE_LOADED Image 1/N loaded
MIN_PAGINATOR:ALL_IMAGES_LOADED Final recompute after all images loaded
MIN_PAGINATOR:DIAGNOSTIC buildCharacterOffsets - pageCount=N, appliedColumnWidth=411px, textLength=XXXXX chars
```

### Test Case 5: Orientation Change
**Steps**:
1. Open a book in portrait orientation
2. Navigate to a page in the middle (e.g., page 4)
3. Rotate device to landscape
4. Verify position is preserved
5. Navigate to other pages
6. Observe logs

**Expected Results**:
- ✅ Page count recalculates for new viewport
- ✅ Reading position preserved (approximately)
- ✅ Navigation works smoothly in new orientation
- ✅ Logs show appliedColumnWidth update for new viewport width

## Diagnostic Log Tags to Monitor

| Tag | Purpose | When to See It |
|-----|---------|----------------|
| `DIAGNOSTIC` | Detailed width tracking | All navigation, scroll, snap operations |
| `LAYOUT_VERIFY` | Confirm state.appliedColumnWidth update | After CSS column width is applied |
| `REFLOW` | Layout recalculation | Font change, orientation change, image loads |
| `SNAP` | Page boundary snapping | After manual scroll ends |
| `NAV` | Navigation operations | goToPage, nextPage, prevPage calls |
| `ERROR` | Width validation failures | Only if appliedColumnWidth <= 0 (shouldn't happen) |

## Common Issues and Solutions

### Issue: Still seeing oscillation
**Check**:
1. Verify the PRIMARY FIX is in the deployed build (line 756)
2. Check logs for consistent appliedColumnWidth values
3. Look for ERROR logs indicating invalid width

**If oscillation persists**:
- Logs should show different appliedColumnWidth values → bug not fixed
- Logs show same width values → different issue (report details)

### Issue: Logs too verbose
**Solution**:
- DIAGNOSTIC logs are intentional for debugging
- Filter logs: `adb logcat | grep "MIN_PAGINATOR:DIAGNOSTIC"`
- Can be disabled in future if performance impact is measured

### Issue: Pages jump unexpectedly
**Check**:
1. Look for RECOMPUTE logs → page count may have changed
2. Check IMAGE_LOADED logs → images loading may cause reflow
3. Verify appliedColumnWidth stays consistent before/after jumps

## Success Criteria

The fix is successful if:
1. ✅ No oscillation when navigating to any page (especially page 5)
2. ✅ Consistent appliedColumnWidth values in all DIAGNOSTIC logs
3. ✅ Smooth navigation after font changes or orientation changes
4. ✅ No ERROR logs related to invalid width (appliedColumnWidth <= 0)
5. ✅ Page snapping works correctly after manual scrolling

## Reporting Results

When reporting test results, please include:
1. Test case performed
2. Actual behavior (success/failure)
3. Relevant log excerpts (especially DIAGNOSTIC, LAYOUT_VERIFY, REFLOW)
4. Device/emulator details (screen width, Android version)
5. Book format and characteristics (page count, has images, etc.)

## Quick Log Filter Commands

```bash
# All paginator logs
adb logcat | grep "MIN_PAGINATOR"

# Only diagnostic logs
adb logcat | grep "MIN_PAGINATOR:DIAGNOSTIC"

# Only errors
adb logcat | grep "MIN_PAGINATOR:ERROR"

# Navigation and snap logs
adb logcat | grep -E "MIN_PAGINATOR:(NAV|SNAP|DIAGNOSTIC)"

# Reflow operations
adb logcat | grep -E "MIN_PAGINATOR:(REFLOW|LAYOUT)"
```

## Performance Note

The DIAGNOSTIC logs add verbosity but should have minimal performance impact since:
- They only log at key decision points (not in tight loops)
- They use efficient string formatting
- They route through AppLogger which handles buffering

If performance becomes a concern, logs can be conditionally compiled for debug builds only.
