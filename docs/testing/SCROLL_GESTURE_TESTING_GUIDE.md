# Scroll Gesture Testing Guide

## Overview
This document describes how to test the onScroll-based gesture interception feature implemented in ReaderPageFragment.

## Feature Description
The implementation adds scroll distance-based gesture interception to handle slow/moderate horizontal swipes for in-page navigation. Previously, only fast flings were detected due to the high velocity threshold (1000f). Now both fast flings and slow swipes work correctly.

## Key Implementation Details

### Constants (Located in ReaderPageFragment.kt companion object)
- `FLING_THRESHOLD = 1000f` - Minimum velocity for fast fling detection (unchanged)
- `SCROLL_DISTANCE_THRESHOLD_RATIO = 0.25f` - 25% of viewport width triggers in-page navigation

### Tracking Variables
- `cumulativeScrollX: Float` - Accumulates horizontal scroll distance
- `scrollIntercepted: Boolean` - Prevents multiple triggers per gesture

### Detection Logic
1. **onDown**: Resets tracking variables for new gesture
2. **onScroll**: 
   - Accumulates horizontal distance (distanceX)
   - Checks if cumulative distance exceeds 25% of viewport width
   - Intercepts parent if threshold exceeded and navigates in-page
   - Falls through to ViewPager2 at page boundaries
3. **onFling**: Works as before for fast swipes
4. **ACTION_UP/ACTION_CANCEL**: Resets tracking variables

## How to Test

### Prerequisites
1. Build and install the app on a device or emulator
2. Open an EPUB book (TXT files use TextView, not WebView)
3. Enable logging (if available) to see detailed gesture information

### Test Scenarios

#### 1. Slow Horizontal Swipe (Primary Test Case)
**Objective**: Verify slow swipes trigger in-page navigation

**Steps**:
1. Open an EPUB chapter with multiple in-pages (check page counter)
2. Slowly swipe left (taking 1-2 seconds for the gesture)
3. Move your finger horizontally at least 25% of screen width
4. Observe navigation occurs within the chapter (page count changes)

**Expected Result**: 
- In-page navigation occurs
- No chapter change
- Log shows: `SCROLL_INTERCEPT: Navigating to next in-page`

**If it fails**:
- Check that you moved at least 25% of screen width
- Verify WebView is visible and ready
- Check logs for "onScroll ignored" messages

#### 2. Fast Horizontal Fling (Regression Test)
**Objective**: Verify fast swipes still work as before

**Steps**:
1. Open an EPUB chapter with multiple in-pages
2. Quickly swipe left (fast flick gesture)
3. Observe navigation occurs within the chapter

**Expected Result**:
- In-page navigation occurs
- No chapter change
- Log shows: `FLING_INTERCEPT: Navigating to next in-page [FAST_SWIPE]`

#### 3. Edge Case - At Last In-Page
**Objective**: Verify swipes fall through to ViewPager2 at page boundaries

**Steps**:
1. Navigate to the last in-page of a chapter (check page counter shows N/N)
2. Swipe left (slow or fast)
3. Observe chapter navigation occurs

**Expected Result**:
- Chapter changes (next chapter loads)
- Log shows: `SCROLL_FALLTHROUGH: At last in-page` or `FLING_FALLTHROUGH: At last in-page`

#### 4. Edge Case - At First In-Page
**Objective**: Verify swipes fall through to ViewPager2 at first page

**Steps**:
1. Ensure you're at the first in-page of a chapter (page counter shows 0/N)
2. Swipe right (slow or fast)
3. Observe chapter navigation occurs

**Expected Result**:
- Chapter changes (previous chapter loads)
- Log shows: `SCROLL_FALLTHROUGH: At first in-page` or `FLING_FALLTHROUGH: At first in-page`

#### 5. Small Jitters (Anti-Accidental Navigation)
**Objective**: Verify small movements don't trigger navigation

**Steps**:
1. Open an EPUB chapter
2. Make small horizontal movements (< 25% of screen width)
3. Release without completing a full swipe

**Expected Result**:
- No navigation occurs
- Page stays the same
- Log shows scroll distance below threshold

#### 6. Vertical Scrolling (Non-Regression)
**Objective**: Verify vertical scrolls don't interfere

**Steps**:
1. Open an EPUB chapter with scrollable content
2. Scroll vertically (up/down)
3. Observe normal vertical scrolling works

**Expected Result**:
- Content scrolls vertically
- No horizontal navigation
- onScroll returns false for vertical scrolls

#### 7. Diagonal Swipe
**Objective**: Verify horizontal-dominant diagonal swipes work

**Steps**:
1. Open an EPUB chapter
2. Swipe diagonally (more horizontal than vertical)
3. Move at least 25% of screen width horizontally

**Expected Result**:
- If abs(distanceX) > abs(distanceY), horizontal navigation occurs
- Otherwise, gesture is ignored

### Debug Logging

The implementation includes comprehensive logging with the following markers:

- `[GESTURE_START]` - Touch down event
- `[THRESHOLD_EXCEEDED]` - Scroll threshold exceeded, attempting interception
- `[EDGE_REACHED]` - At page boundary, falling through to ViewPager2
- `[FALLBACK_TO_VIEWPAGER]` - Error occurred, falling back
- `[GESTURE_END]` - Touch up event
- `[GESTURE_CANCELLED]` - Touch cancelled

### Key Log Messages to Look For

**Successful Scroll Interception**:
```
onScroll horizontal detected: page=0 cumulativeX=270.5 threshold=270.0 viewportWidth=1080 (25% of viewport)
Scroll threshold exceeded: page=0 cumulativeX=270.5 threshold=270.0 - attempting interception
SCROLL_INTERCEPT: Navigating to next in-page (1/3) within chapter page 0 [THRESHOLD_EXCEEDED]
```

**Successful Fling Interception**:
```
Detected horizontal fling: page=0 vx=-1500.0 (threshold=1000.0)
FLING_BASED navigation: page=0 currentPage=0/3, velocityX=-1500.0
FLING_INTERCEPT: Navigating to next in-page (1/3) within chapter page 0 [FAST_SWIPE]
```

**Fallthrough at Boundary**:
```
SCROLL_FALLTHROUGH: At last in-page (2/3), allowing ViewPager2 to handle chapter navigation [EDGE_REACHED]
```

**Error Handling**:
```
ERROR in scroll-based navigation for page 0: <error message> [FALLBACK_TO_VIEWPAGER]
```

## Configuration

### Adjusting Thresholds

The scroll distance threshold can be adjusted by modifying the constant in `ReaderPageFragment.kt`:

```kotlin
private const val SCROLL_DISTANCE_THRESHOLD_RATIO = 0.25f // 25% of viewport width
```

**Suggested values**:
- `0.15f` (15%) - More sensitive, easier to trigger
- `0.25f` (25%) - Default, balanced sensitivity
- `0.35f` (35%) - Less sensitive, requires larger swipe

The fling velocity threshold can also be adjusted:

```kotlin
private const val FLING_THRESHOLD = 1000f // Minimum velocity
```

**Note**: Lower values make fling detection more sensitive.

## Known Limitations

1. **WebView-only**: Feature only works with EPUB content (WebView). TXT files use TextView and don't have in-page pagination.
2. **Touch Sequence**: Proper reset relies on receiving ACTION_UP or ACTION_CANCEL events.
3. **Viewport Width**: Threshold is based on WebView width, which should match screen width in portrait mode.

## Troubleshooting

### Issue: Slow swipes not triggering navigation
**Possible causes**:
- Swipe distance < 25% of screen width
- WebView not ready (check logs for "isWebViewReady=false")
- WebView not visible (check logs for "visibility=GONE")
- Vertical scroll detected instead of horizontal

**Solutions**:
- Swipe further horizontally
- Wait for page to fully load
- Check that you're viewing EPUB content, not TXT

### Issue: Accidental navigation on small movements
**Possible causes**:
- Threshold too low

**Solutions**:
- Increase `SCROLL_DISTANCE_THRESHOLD_RATIO` to 0.30f or 0.35f

### Issue: Navigation not working at all
**Possible causes**:
- JavaScript paginator not loaded
- WebView not initialized properly

**Solutions**:
- Check logs for JavaScript errors
- Verify inpage_paginator.js is present in assets
- Check WebViewPaginatorBridge logs

## Performance Considerations

- The implementation uses coroutines for page boundary checks, which are non-blocking
- Logging is verbose for debugging but can be reduced in production
- MOVE events are logged less frequently to avoid spam
- Parent interception is only requested when threshold exceeded

## Future Enhancements

1. **Configurable Threshold**: Add scroll threshold to ReaderPreferences for user customization
2. **Visual Feedback**: Show progress indicator during slow swipe
3. **Haptic Feedback**: Vibrate on successful interception
4. **Gesture Sensitivity**: Add user setting for gesture sensitivity
