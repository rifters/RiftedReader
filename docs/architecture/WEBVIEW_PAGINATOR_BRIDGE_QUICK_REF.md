# WebViewPaginatorBridge Quick Reference

**Status**: ✅ Ready for Integration  
**Build**: ✅ Successful  
**Compilation**: ✅ No Errors  

---

## At a Glance

The `WebViewPaginatorBridge` is a Kotlin class that handles all communication between the Android reader UI and the JavaScript HTML paginator engine.

### Location
```
app/src/main/java/com/rifters/riftedreader/ui/reader/WebViewPaginatorBridge.kt
```

### Basic Setup

```kotlin
// In ReaderPageFragment.setupWebView()
webViewPaginatorBridge = WebViewPaginatorBridge(
    webView = binding.readerWebView,
    onPageChangedCallback = ::onPageChanged,
    onPaginationReadyCallback = ::onPaginationReady,
    onChapterChangedCallback = ::onChapterChanged,
    onLineMetricsCallback = ::onLineMetrics,
    onSizeInfoCallback = ::onSizeInfo,
    onErrorCallback = ::onJavaScriptError
)
```

---

## Key Methods

### Navigation Commands

**Navigate to Page**
```kotlin
webViewPaginatorBridge?.navigateToPageJS(5)
```
→ JavaScript: `AndroidJSBridge.navigateToPage(5)`  
← Callback: `onPageChanged(page, chapter, offset)`

**Navigate to Chapter**
```kotlin
webViewPaginatorBridge?.navigateToChapterJS(3)
```
→ JavaScript: `AndroidJSBridge.navigateToChapter(3)`  
← Callback: `onChapterChanged(chapterIndex, pageInChapter)`

**Scroll by Percentage**
```kotlin
webViewPaginatorBridge?.scrollByPercentageJS(75.0f)
```
→ JavaScript: `AndroidJSBridge.scrollByPercentage(75.0)`

### Information Requests

**Get Current Page Info**
```kotlin
webViewPaginatorBridge?.getCurrentPageInfoJS()
```
← Callback: `onPageChanged(page, chapter, offset)` with current values

**Get Total Pages**
```kotlin
webViewPaginatorBridge?.getTotalPagesJS()
```
← Result available through pagination callbacks

**Get Line Metrics**
```kotlin
webViewPaginatorBridge?.getLineMetricsJS()
```
← Callback: `onLineMetrics(metricsJson)` with detailed text metrics

---

## Callback Handlers

### onPageChanged
```kotlin
private fun onPageChanged(page: Int, chapter: Int, inPageOffset: Int) {
    // Called when page changes (via navigation or scroll)
    // page: Current page in window
    // chapter: Current chapter index
    // inPageOffset: Scroll position within page (0-100%)
}
```

### onChapterChanged
```kotlin
private fun onChapterChanged(chapterIndex: Int, pageInChapter: Int) {
    // Called when chapter changes
    // chapterIndex: Index of chapter
    // pageInChapter: Which page within that chapter
}
```

### onPaginationReady
```kotlin
private fun onPaginationReady(totalPages: Int) {
    // Called when JavaScript paginator is initialized
    // totalPages: Total pages in current window/chapter
}
```

### onLineMetrics
```kotlin
private fun onLineMetrics(metricsJson: String) {
    // Called with detailed line/text metrics
    // Parse JSON to get: lineCount, linesPerPage, textDensity, etc.
}
```

### onSizeInfo
```kotlin
private fun onSizeInfo(widthPx: Int, heightPx: Int) {
    // Called with WebView dimensions
    // widthPx: Width in pixels
    // heightPx: Height in pixels
}
```

### onJavaScriptError
```kotlin
private fun onJavaScriptError(errorMessage: String) {
    // Called if JavaScript execution fails
    // errorMessage: Description of what went wrong
}
```

---

## Common Patterns

### Navigate and Wait for Callback

```kotlin
viewModelScope.launch {
    // Request page change
    webViewPaginatorBridge?.navigateToPageJS(5)
    
    // Callback will be triggered automatically
    // within onPageChanged handler
}
```

### Get Current Position

```kotlin
private fun reportCurrentPosition() {
    // Request current info (triggers callback immediately)
    webViewPaginatorBridge?.getCurrentPageInfoJS()
    // onPageChanged will be called with current values
}
```

### Handle Multiple Navigation Commands

```kotlin
// Queue multiple commands - they execute in sequence
webViewPaginatorBridge?.navigateToChapterJS(2)
// JavaScript processes, triggers callback
// Then you can queue next command
webViewPaginatorBridge?.navigateToPageJS(1)
```

---

## Thread Safety

**All methods are thread-safe** and automatically handle:
- Main thread verification
- WebView thread dispatch
- Callback routing to appropriate handlers

```kotlin
// Safe to call from any thread
thread { 
    webViewPaginatorBridge?.navigateToPageJS(5) // ✓ OK
}.start()
```

---

## Error Handling

**Automatic error handling includes**:
- Null WebView checks
- Fragment lifecycle verification
- JSON parsing with fallback
- Graceful callback failures

### To Debug Errors

Check Logcat with prefix:
```
adb logcat | grep "[JS_BRIDGE"
```

Typical error messages:
```
[JS_BRIDGE_ERROR] Failed to deserialize page change
[NAV_JS_LOG] Navigation command failed
[PAGINATION] Paginator not ready
```

---

## Logging

All operations are logged with structured prefixes:

| Prefix | Purpose |
|--------|---------|
| `[NAV_JS_LOG]` | Navigation operations |
| `[PAGINATION]` | Pagination events |
| `[METRICS]` | Metrics collection |
| `[JS_BRIDGE_ERROR]` | Error conditions |

**View logs**:
```bash
adb logcat | grep "WebViewPaginatorBridge"
```

---

## Integration Checklist

When adding to new Fragment/Activity:

- [ ] Create bridge in `onViewCreated()` or `onCreate()`
- [ ] Register all callbacks before loading HTML
- [ ] Enable JavaScript in WebView settings
- [ ] Store bridge reference as instance variable
- [ ] Call bridge methods from UI callbacks (tap, scroll, etc.)
- [ ] Handle callbacks appropriately (update UI, state, etc.)
- [ ] Test with Logcat filtering

---

## Common Issues

### Bridge calls not working?
✓ Verify WebView has JavaScript enabled
✓ Ensure HTML is loaded before calling bridge methods
✓ Check callbacks are registered

### Callbacks not firing?
✓ Verify JavaScript global object `window.AndroidJSBridge` exists
✓ Check JavaScript is returning valid JSON
✓ Verify callback method names match exactly

### JSON parsing errors?
✓ Check Logcat for actual JSON received
✓ Verify JavaScript is escaping quotes properly
✓ Add try-catch in callback for debugging

---

## Example: Complete Page Navigation Flow

```kotlin
class ReaderPageFragment : Fragment() {
    private lateinit var webViewPaginatorBridge: WebViewPaginatorBridge
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Setup bridge with callbacks
        webViewPaginatorBridge = WebViewPaginatorBridge(
            webView = binding.readerWebView,
            onPageChangedCallback = ::handlePageChanged,
            onPaginationReadyCallback = ::handlePaginationReady,
            onChapterChangedCallback = ::handleChapterChanged,
            onErrorCallback = ::handleError
        )
        
        // Load HTML
        binding.readerWebView.loadUrl("file:///android_asset/reader.html")
    }
    
    // User taps next button
    private fun onNextPageClicked() {
        currentPage++
        webViewPaginatorBridge?.navigateToPageJS(currentPage)
        // onPageChanged will be called with result
    }
    
    // Handle page change result
    private fun handlePageChanged(page: Int, chapter: Int, offset: Int) {
        currentPage = page
        currentChapter = chapter
        updateUI()
    }
    
    private fun handlePaginationReady(totalPages: Int) {
        isReady = true
        updatePageCount(totalPages)
    }
    
    private fun handleChapterChanged(chapterIndex: Int, pageInChapter: Int) {
        updateChapterUI(chapterIndex, pageInChapter)
    }
    
    private fun handleError(message: String) {
        Log.e("Reader", "JavaScript error: $message")
        showErrorToUser()
    }
}
```

---

## Next Steps

1. **Test**: Open a book and navigate pages
2. **Monitor**: Watch Logcat for logs with `[JS_BRIDGE` prefix
3. **Integrate**: Add bridge calls to your gestures/buttons
4. **Debug**: Use error callbacks to troubleshoot issues
5. **Optimize**: Adjust callback timing as needed

---

**Status**: Ready to use in production  
**Version**: 1.0  
**Last Updated**: 2025-01-14
