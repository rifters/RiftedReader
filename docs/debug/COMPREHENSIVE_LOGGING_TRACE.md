# Comprehensive Logging Trace for Window Update Flow

**Added in Commit**: 7d2e870  
**Purpose**: Complete tracing of buffer shift → fragment update → WebView render flow

## Overview

The complete logging flow for window navigation has been enhanced with detailed trace points. When you navigate in the reader, the system logs at each critical step so you can see the exact sequence of events that loads new window content.

## Complete Log Flow

When navigating to a new window, here's the exact log sequence you should see in logcat:

### 1. Buffer Shift Preparation (ConveyorBeltSystemViewModel)
```
[BUFFER_SHIFT_START] Applying pending buffer shift: direction=FORWARD, oldBuffer=[3,4,5,6,7], newBuffer=[4,5,6,7,8]
[BUFFER_SHIFT_UPDATE] Buffer updated: activeWindow=5, buffer=[4,5,6,7,8]
[BUFFER_SHIFT_ADAPTER] Adapter invalidated at CENTER_INDEX=2
[BUFFER_SHIFT_CALLBACK] Invoking fragment update callback with centerWindow=5
[BUFFER_SHIFT_COMPLETE] Pending buffer shift completed successfully
```

### 2. Fragment Window Update (ReaderPageFragment)
```
[FRAGMENT_UPDATE_START] Window update requested: oldWindowIndex=4 → newWindowIndex=5
[FRAGMENT_UPDATE_STATE] State reset: isPaginatorInitialized=false, currentInPageIndex=0
[FRAGMENT_UPDATE_RENDER] Calling renderBaseContent() to load new window HTML
[FRAGMENT_UPDATE_COMPLETE] Window update completed, awaiting HTML load
```

### 3. Content Rendering (ReaderPageFragment)
```
[RENDER_BASE_CONTENT_START] Starting content render for window 5
[PAGINATION_DEBUG] Loading HTML into WebView: windowIndex=5, isWebViewReady=false, webViewSize=1080x2232
[RENDER_LOAD_HTML] Window 5: Calling loadHtmlIntoWebView with wrapped HTML
[WEBVIEW_LOAD_START] Window 5: Calling loadDataWithBaseURL with wrapped HTML (45823 chars)
[WEBVIEW_LOAD_ENQUEUED] Window 5: loadDataWithBaseURL called, waiting for onPageFinished callback
```

### 4. HTML Measurement (ReaderPageFragment - if deferred)
```
[PAGINATION_DEBUG] WebView not yet measured (1080x0), deferring HTML load
[ENSURE_MEASURED_DEFER] Window 5: Deferring HTML load until WebView is measured
[ENSURE_MEASURED_CALLBACK] Window 5: WebView now measured, calling loadHtmlIntoWebView
```

### 5. WebView Loading Complete (ReaderPageFragment)
```
[WEBVIEW_PAGE_FINISHED_START] Window 5: HTML loaded in WebView
[PAGINATION_DEBUG] onPageFinished fired: windowIndex=5, url=https://appassets.androidplatform.net/, webViewSize=1080x2232
[WEBVIEW_READY] Window 5: isWebViewReady set to true
[MIN_PAGINATOR] Configured and initialized for windowIndex=5, mode=window
```

### 6. Content Ready Gate Check (ReaderActivity)
```
[CONTENT_LOADED] Checking gates: page=5 textLength=45823 hasHtml=true isWebViewReady=true isPaginatorInit=true textNonEmpty=true pendingTtsResume=false
[CONTENT_LOADED] EMITTED - page=5 textLength=45823 hasHtml=true All gates passed.
[CONTENT_LOADED] Built 312 sentence boundaries for page 5
```

---

## Log Tags Reference

### Fragment Update Tags (ReaderPageFragment)
| Tag | Meaning | Location |
|-----|---------|----------|
| `[FRAGMENT_UPDATE_START]` | Window update callback received | `updateWindow()` entry |
| `[FRAGMENT_UPDATE_STATE]` | State reset before render | `updateWindow()` state reset |
| `[FRAGMENT_UPDATE_RENDER]` | `renderBaseContent()` call | `updateWindow()` render call |
| `[FRAGMENT_UPDATE_COMPLETE]` | Update method finished | `updateWindow()` exit |

### Content Rendering Tags (ReaderPageFragment)
| Tag | Meaning | Location |
|-----|---------|----------|
| `[RENDER_BASE_CONTENT_START]` | Content render started | `renderBaseContent()` entry |
| `[RENDER_LOAD_HTML]` | HTML about to load into WebView | `renderBaseContent()` load call |
| `[WEBVIEW_LOAD_START]` | `loadDataWithBaseURL()` called | `loadHtmlIntoWebView()` entry |
| `[WEBVIEW_LOAD_ENQUEUED]` | HTML load enqueued in WebView queue | `loadHtmlIntoWebView()` exit |

### WebView Measurement Tags (ReaderPageFragment)
| Tag | Meaning | Location |
|-----|---------|----------|
| `[ENSURE_MEASURED_LOAD]` | WebView already measured, loading immediately | `ensureMeasuredAndLoadHtml()` immediate branch |
| `[ENSURE_MEASURED_DEFER]` | WebView not measured yet, deferring load | `ensureMeasuredAndLoadHtml()` defer branch |
| `[ENSURE_MEASURED_CALLBACK]` | Layout complete, loading now | `onGlobalLayout()` callback |

### WebView Lifecycle Tags (ReaderPageFragment)
| Tag | Meaning | Location |
|-----|---------|----------|
| `[WEBVIEW_PAGE_FINISHED_START]` | HTML fully loaded in WebView | `onPageFinished()` entry |
| `[WEBVIEW_READY]` | `isWebViewReady` flag set to true | `onPageFinished()` ready flag |
| `[PAGINATION_DEBUG]` | General pagination debug info | Various locations |
| `[MIN_PAGINATOR]` | Minimal paginator initialization | `onPageFinished()` paginator config |

### Buffer Shift Tags (ConveyorBeltSystemViewModel)
| Tag | Meaning | Location |
|-----|---------|----------|
| `[BUFFER_SHIFT_START]` | Buffer shift begins | `applyPendingBufferShift()` entry |
| `[BUFFER_SHIFT_UPDATE]` | Buffer and active window updated | `applyPendingBufferShift()` state update |
| `[BUFFER_SHIFT_ADAPTER]` | Adapter notified of shift | `applyPendingBufferShift()` adapter invalidate |
| `[BUFFER_SHIFT_CALLBACK]` | Fragment callback about to invoke | `applyPendingBufferShift()` callback setup |
| `[BUFFER_SHIFT_COMPLETE]` | Buffer shift fully complete | `applyPendingBufferShift()` exit |

### Content Loading Gate Tags (ReaderActivity)
| Tag | Meaning | Location |
|-----|---------|----------|
| `[CONTENT_LOADED] Checking gates` | Gate condition check | `viewModel.content.collect {}` gate check |
| `[CONTENT_LOADED] SKIPPED` | Gates not met, skipping emit | `viewModel.content.collect {}` skip branch |
| `[CONTENT_LOADED] EMITTED` | All gates passed, event emitted | `viewModel.content.collect {}` emit |
| `[CONTENT_LOADED] Built ... boundaries` | TTS boundaries computed | `viewModel.content.collect {}` TTS setup |

---

## How to Use This for Diagnosis

### Collect Logcat Output

1. **Clear logcat**: `adb logcat -c`
2. **Navigate in the reader** (forward or backward)
3. **Capture output**: `adb logcat > logcat_dump.txt`
4. **Filter by tags**: `adb logcat | grep "\[FRAGMENT_UPDATE\]\|\[RENDER_\]\|\[BUFFER_SHIFT\]\|\[CONTENT_LOADED\]\|\[WEBVIEW_"`

### Expected Sequences

#### Forward Navigation (Next Window)
```
Buffer Shift START
├─ Buffer updated to [next, next+1, next+2, next+3, next+4]
├─ Adapter invalidated
├─ Fragment callback invoked
└─ Buffer Shift COMPLETE

Fragment Update START
├─ State reset
├─ renderBaseContent() called
└─ Update COMPLETE

Content Render START
├─ HTML load START
├─ HTML load ENQUEUED
└─ Render done

WebView Measurement (if needed)
├─ Deferred OR Immediate
└─ LoadHtmlIntoWebView called

WebView Loading Complete
├─ onPageFinished fired
├─ WebView READY
└─ Paginator configured

Content Loading Gate
├─ Gates checked
├─ Gates PASSED
└─ CONTENT_LOADED EMITTED
```

#### Backward Navigation (Previous Window)
Same sequence but with `oldBuffer` showing decremented window indices.

### Troubleshooting: Missing Log Lines

If you see `[FRAGMENT_UPDATE_START]` but not `[FRAGMENT_UPDATE_RENDER]`, the fragment update is failing.

If you see `[WEBVIEW_LOAD_ENQUEUED]` but not `[WEBVIEW_PAGE_FINISHED_START]`, WebView is not completing HTML load.

If you see `[CONTENT_LOADED] SKIPPED`, one of these gates failed:
- `isWebViewReady` - WebView didn't fire onPageFinished
- `isPaginatorInitialized` - Paginator didn't initialize
- `textNonEmpty` - Content text is empty

---

## Example: Diagnosing "Window 4+ Shows Stale Content"

If window 4 shows window 3's content, look for:

1. **Buffer shift doesn't happen**: Missing `[BUFFER_SHIFT_START]` logs
2. **Fragment not updated**: Have `[BUFFER_SHIFT_COMPLETE]` but no `[FRAGMENT_UPDATE_START]`
3. **Content not rendered**: Have `[FRAGMENT_UPDATE_START]` but no `[RENDER_BASE_CONTENT_START]`
4. **WebView not loading**: Have `[RENDER_LOAD_HTML]` but no `[WEBVIEW_LOAD_ENQUEUED]`
5. **HTML not finished**: Have `[WEBVIEW_LOAD_ENQUEUED]` but no `[WEBVIEW_PAGE_FINISHED_START]`
6. **Content gate fails**: Have `onPageFinished` but `[CONTENT_LOADED] SKIPPED` with details

---

## Implementation Details

### Logging Locations

1. **ReaderPageFragment.kt**:
   - `updateWindow()` - Lines 2265-2283 (FRAGMENT_UPDATE_* tags)
   - `renderBaseContent()` - Line ~925 (RENDER_BASE_CONTENT_START)
   - `loadHtmlIntoWebView()` - Lines ~1077-1088 (RENDER_LOAD_HTML, WEBVIEW_LOAD_*)
   - `ensureMeasuredAndLoadHtml()` - Lines ~1130-1160 (ENSURE_MEASURED_*)
   - `onPageFinished()` - Lines ~232-258 (WEBVIEW_PAGE_FINISHED_*, WEBVIEW_READY)

2. **ConveyorBeltSystemViewModel.kt**:
   - `applyPendingBufferShift()` - Lines ~125-155 (BUFFER_SHIFT_* tags)

3. **ReaderActivity.kt**:
   - `viewModel.content.collect {}` - Lines ~660-720 (CONTENT_LOADED gates)

### Log Format

All logs use AppLogger with this format:
```kotlin
com.rifters.riftedreader.util.AppLogger.d("ClassName", "[TAG] Message with context")
```

This produces logcat output like:
```
2025-12-06 10:15:23.456 19424-19424 ReaderPageFragment com.rifters.riftedreader D [FRAGMENT_UPDATE_START] Window update: 4 → 5
```

---

## Next Steps

1. **Build and install**: `./gradlew assembleDebug` then install APK
2. **Enable logging**: Check that AppLogger is configured for DEBUG level
3. **Navigate and capture**: Use forward/backward navigation and capture logcat
4. **Share output**: Provide logcat dump with all these tags for diagnosis

The comprehensive logging will show exactly where each step succeeds or fails.
