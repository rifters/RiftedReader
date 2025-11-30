# Sliding-Window Pagination Improvements

This document provides patterns and implementation guidance for improving RiftedReader's sliding-window pagination, based on analysis of LibreraReader's approach and our current implementation.

## Overview

The key issues to address:

1. **Measurement Discipline** - Delay loading wrapped window HTML into WebView until measured (width/height > 0)
2. **In-page Column Width Computation** - Inject inline script for column-width computation and reapply
3. **Paginator Synchronization and Batching** - Gate `getPageCount`/`getCurrentPage` behind `isReady()`
4. **Diagnostics (toggleable)** - Add developer setting for logs
5. **Fix Broken Images** - Ensure image src resolution uses correct base URL

---

## 1. Measurement Discipline

### Problem

Loading HTML into WebView before the view has been measured (width/height = 0) causes pagination calculations to fail because `viewportWidth` is 0.

### LibreraReader Pattern Reference

LibreraReader uses a lazy initialization approach in its WebView helpers (`WebViewUtils.java`):

```java
// app/src/main/java/com/foobnix/android/utils/WebViewUtils.java:45-54
web.layout(0, 0, Dips.screenMinWH(), Dips.screenMinWH());
```

Key insight: LibreraReader pre-measures the WebView layout before loading content.

### RiftedReader Implementation

#### Current Issue (ReaderPageFragment.kt)

```kotlin
// Content is loaded in renderBaseContent() which can be called before WebView is measured
binding.pageWebView.loadDataWithBaseURL(
    "file:///android_asset/", 
    wrappedHtml, 
    "text/html", 
    "UTF-8", 
    null
)
```

#### Recommended Fix

**Option A: ViewTreeObserver Approach**

```kotlin
private var pendingHtml: String? = null

private fun renderBaseContent() {
    if (_binding == null) return
    val html = latestPageHtml
    
    if (!html.isNullOrBlank()) {
        binding.pageWebView.visibility = View.VISIBLE
        binding.pageTextView.visibility = View.GONE
        
        // Check if WebView has been measured
        if (binding.pageWebView.width > 0 && binding.pageWebView.height > 0) {
            loadHtmlIntoWebView(html)
        } else {
            // Defer until measured
            pendingHtml = html
            binding.pageWebView.viewTreeObserver.addOnGlobalLayoutListener(
                object : ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        if (binding.pageWebView.width > 0 && binding.pageWebView.height > 0) {
                            binding.pageWebView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                            pendingHtml?.let { loadHtmlIntoWebView(it) }
                            pendingHtml = null
                        }
                    }
                }
            )
        }
    }
}
```

**Option B: Post-Layout with doOnLayout() Extension**

```kotlin
import androidx.core.view.doOnLayout

private fun renderBaseContent() {
    if (_binding == null) return
    val html = latestPageHtml
    
    if (!html.isNullOrBlank()) {
        binding.pageWebView.visibility = View.VISIBLE
        binding.pageTextView.visibility = View.GONE
        
        binding.pageWebView.doOnLayout { webView ->
            if (webView.width > 0 && webView.height > 0) {
                loadHtmlIntoWebView(html)
            }
        }
    }
}
```

---

## 2. In-page Column Width Computation and Reapply

### Problem

Column width must be computed dynamically based on actual viewport width. If calculated before layout or with stale values, pagination breaks.

### Current Implementation (inpage_paginator.js)

```javascript
// inpage_paginator.js:202-245
function updateColumnStyles(wrapper) {
    viewportWidth = window.innerWidth;
    const columnWidth = viewportWidth;
    
    wrapper.style.cssText = `
        display: block;
        column-width: ${columnWidth}px;
        column-gap: ${COLUMN_GAP}px;
        column-fill: auto;
        height: 100%;
        scroll-snap-align: start;
    `;
    
    // Force reflow before measurement
    wrapper.offsetHeight;
    
    // Compute page count and set wrapper width to exact multiple
    const scrollWidth = wrapper.scrollWidth;
    const pageCount = Math.max(1, Math.ceil(scrollWidth / viewportWidth));
    const exactWidth = pageCount * viewportWidth;
    
    wrapper.style.width = exactWidth + 'px';
}
```

### Recommended Improvement

Add inline script injection for column-width recomputation when viewport changes or after content loads:

```javascript
// Add to inpage_paginator.js
function recomputeColumnWidth() {
    if (!contentWrapper || !columnContainer) {
        console.warn('inpage_paginator: recomputeColumnWidth called before init');
        return false;
    }
    
    const oldViewportWidth = viewportWidth;
    viewportWidth = window.innerWidth;
    
    // Early exit if viewport hasn't changed
    if (oldViewportWidth === viewportWidth && oldViewportWidth > 0) {
        console.log('inpage_paginator: [COLUMN_WIDTH] No change needed');
        return false;
    }
    
    // Guard against zero viewport
    if (viewportWidth <= 0) {
        console.warn('inpage_paginator: [COLUMN_WIDTH] viewportWidth is 0, deferring');
        return false;
    }
    
    console.log('inpage_paginator: [COLUMN_WIDTH] Recomputing: ' + oldViewportWidth + ' -> ' + viewportWidth);
    
    // Update column styles
    updateColumnStyles(contentWrapper);
    
    return true;
}

// Call this after Android notifies that WebView dimensions are stable
window.inpagePaginator.recomputeColumnWidth = recomputeColumnWidth;
```

**Android Side - WebViewPaginatorBridge.kt:**

```kotlin
/**
 * Request column width recomputation.
 * Call this after WebView layout is confirmed (width/height > 0).
 */
fun recomputeColumnWidth(webView: WebView) {
    AppLogger.d("WebViewPaginatorBridge", "recomputeColumnWidth requested")
    mainHandler.post {
        webView.evaluateJavascript(
            "if (window.inpagePaginator && window.inpagePaginator.recomputeColumnWidth) { window.inpagePaginator.recomputeColumnWidth(); }",
            null
        )
    }
}
```

---

## 3. Paginator Synchronization and Batching

### Problem

Calling `getPageCount()` or `getCurrentPage()` before the paginator is ready returns invalid values (0 or 1), causing navigation to break.

### Current Implementation

The `isReady()` check exists but isn't always used:

```javascript
// inpage_paginator.js:1249-1257
function isReady() {
    const ready = isPaginationReady && 
                  columnContainer !== null && 
                  document.getElementById('paginator-content') !== null;
    return ready;
}
```

### Recommended Pattern: Gate All Queries Behind isReady()

**JavaScript Side:**

```javascript
function getPageCount() {
    // CRITICAL: Return safe default if not ready
    if (!isReady()) {
        console.warn('inpage_paginator: [GUARD] getPageCount called before ready');
        return 1;
    }
    // ... existing implementation
}

function getCurrentPage() {
    // CRITICAL: Return safe default if not ready
    if (!isReady()) {
        console.warn('inpage_paginator: [GUARD] getCurrentPage called before ready');
        return 0;
    }
    return currentPage;
}
```

**Android Side (already implemented in WebViewPaginatorBridge.kt):**

```kotlin
suspend fun getPageCount(webView: WebView): Int {
    return try {
        if (!isReady(webView)) {
            AppLogger.d("WebViewPaginatorBridge", "getPageCount: paginator not ready, returning 1")
            return 1
        }
        // ... query JS
    } catch (e: Exception) {
        1 // Return safe default
    }
}
```

### Batched Queries Pattern

For operations that need multiple values, batch them into a single JS call:

```kotlin
/**
 * Get pagination state in a single batched call.
 * More efficient than multiple individual queries.
 */
suspend fun getPaginationState(webView: WebView): PaginationState? {
    return try {
        val json = evaluateString(webView, """
            (function() {
                var p = window.inpagePaginator;
                if (!p || !p.isReady()) return null;
                return JSON.stringify({
                    pageCount: p.getPageCount(),
                    currentPage: p.getCurrentPage(),
                    currentChapter: p.getCurrentChapter()
                });
            })()
        """.trimIndent())
        
        if (json == "null") null else gson.fromJson(json, PaginationState::class.java)
    } catch (e: Exception) {
        null
    }
}

data class PaginationState(
    val pageCount: Int,
    val currentPage: Int,
    val currentChapter: Int
)
```

---

## 4. Diagnostics (Toggleable Developer Setting)

### Pattern

Add a developer preference for pagination debug logging.

**ReaderPreferences (data/preferences/ReaderPreferences.kt):**

```kotlin
data class ReaderPreferences(
    val textSizeSp: Float = 18f,
    val lineHeightMultiplier: Float = 1.5f,
    val theme: ReaderTheme = ReaderTheme.LIGHT,
    val mode: ReaderMode = ReaderMode.CONTINUOUS,
    // NEW: Developer/debug settings
    val paginationDebugLogging: Boolean = false
)
```

**AppLogger Enhancement (util/AppLogger.kt):**

```kotlin
object AppLogger {
    var paginationDebugEnabled: Boolean = false
    
    fun paginationDebug(tag: String, message: String) {
        if (paginationDebugEnabled) {
            Log.d("PAGINATION_DEBUG", "[$tag] $message")
        }
    }
}
```

**Usage in ReaderPageFragment.kt:**

```kotlin
// At start of pagination operations
if (AppLogger.paginationDebugEnabled) {
    AppLogger.paginationDebug("ReaderPageFragment", 
        "[PAGINATION_DEBUG] onPageFinished: windowIndex=$pageIndex, webViewSize=${webViewWidth}x${webViewHeight}"
    )
}
```

**JavaScript Side (inpage_paginator.js):**

```javascript
// Add at top of IIFE
let DEBUG_PAGINATION = false;

function debugLog(message) {
    if (DEBUG_PAGINATION) {
        console.log('inpage_paginator: [DEBUG] ' + message);
    }
}

// Expose toggle
window.inpagePaginator.setDebugMode = function(enabled) {
    DEBUG_PAGINATION = enabled;
    console.log('inpage_paginator: Debug mode ' + (enabled ? 'ENABLED' : 'DISABLED'));
};
```

---

## 5. Fix Broken Images Under Sliding Window

### Problem

When loading HTML with `loadDataWithBaseURL()`, relative image URLs may not resolve correctly, especially when the EPUB content references images relative to the chapter file location.

### LibreraReader Pattern Reference

LibreraReader extracts EPUB content to a cache directory and uses absolute file paths:

```java
// app/src/main/java/com/foobnix/ext/EpubExtractor.java:181-203
public static File extractAttachment(File bookPath, String attachmentName) {
    // ... extracts images to cache directory
    File extractMedia = new File(CacheZipUtils.ATTACHMENTS_CACHE_DIR, attachmentName);
    // ... writes to file
    return extractMedia;
}
```

### RiftedReader Solution Options

#### Option A: Content Provider for EPUB Resources

Create a ContentProvider that serves EPUB resources:

```kotlin
class EpubContentProvider : ContentProvider() {
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        // Parse URI to get book ID and resource path
        val bookId = uri.pathSegments[0]
        val resourcePath = uri.pathSegments.drop(1).joinToString("/")
        
        // Extract from EPUB and return file descriptor
        val extractedFile = epubResourceExtractor.extractResource(bookId, resourcePath)
        return ParcelFileDescriptor.open(extractedFile, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    
    // ... other required overrides
}
```

**Manifest:**
```xml
<provider
    android:name=".data.epub.EpubContentProvider"
    android:authorities="${applicationId}.epub.resources"
    android:exported="false"
    android:grantUriPermissions="true" />
```

#### Option B: Base64 Inline Images (Simple but Memory-Heavy)

For smaller books, convert images to base64 inline:

```kotlin
fun inlineImages(html: String, epubParser: EpubParser): String {
    val imgPattern = """<img[^>]+src=["']([^"']+)["'][^>]*>""".toRegex()
    return imgPattern.replace(html) { match ->
        val src = match.groupValues[1]
        if (src.startsWith("data:")) {
            match.value // Already inline
        } else {
            val imageData = epubParser.getResource(src)
            val mimeType = getMimeType(src)
            val base64 = Base64.encodeToString(imageData, Base64.NO_WRAP)
            match.value.replace(src, "data:$mimeType;base64,$base64")
        }
    }
}
```

#### Option C: Correct Base URL (Recommended)

Use the EPUB file's extracted directory as base URL:

```kotlin
// When preparing window HTML
val bookCacheDir = File(context.cacheDir, "epub_${bookId}")
val baseUrl = "file://${bookCacheDir.absolutePath}/"

// Ensure images are extracted to this directory
epubParser.extractResources(bookCacheDir)

// Load with correct base URL
binding.pageWebView.loadDataWithBaseURL(
    baseUrl,
    wrappedHtml,
    "text/html",
    "UTF-8",
    null
)
```

**In ReaderHtmlWrapper.kt, update image paths:**

```kotlin
/**
 * Fix image paths to be relative to the book's cache directory.
 */
fun fixImagePaths(html: String, chapterPath: String): String {
    val chapterDir = File(chapterPath).parent ?: ""
    
    // Fix relative paths
    return html.replace("""src=["'](?!https?://|data:|/)([^"']+)["']""".toRegex()) { match ->
        val relativePath = match.groupValues[1]
        val resolvedPath = if (chapterDir.isNotEmpty()) {
            "$chapterDir/$relativePath"
        } else {
            relativePath
        }
        """src="$resolvedPath""""
    }
}
```

---

## Implementation Priority

1. **Measurement Discipline** - Critical for preventing 0-page initialization
2. **Paginator Synchronization** - Prevents race conditions in navigation
3. **Column Width Recomputation** - Ensures correct pagination after layout
4. **Image URL Resolution** - Required for visual correctness
5. **Diagnostics** - Helpful for debugging but not critical

---

## Testing Plan

### Unit Tests

1. Test `isReady()` returns false before initialization completes
2. Test `getPageCount()` returns safe default when not ready
3. Test column width computation with various viewport sizes

### Integration Tests

1. Load EPUB with images and verify they display
2. Navigate through book and verify page counts are correct
3. Rotate device and verify pagination recalculates
4. Test font size changes preserve reading position

### Manual Testing

1. Open book immediately after app start (cold start)
2. Navigate rapidly through chapters
3. Change settings (font size, theme) while reading
4. Test on various screen sizes and orientations

---

## References

- LibreraReader source: `rifters/LibreraReader`
  - WebView utilities: `app/src/main/java/com/foobnix/android/utils/WebViewUtils.java`
  - EPUB extraction: `app/src/main/java/com/foobnix/ext/EpubExtractor.java`
  - Document controller: `app/src/main/java/com/foobnix/pdf/info/wrapper/DocumentController.java`
- RiftedReader existing docs:
  - `docs/complete/PAGINATOR_API.md`
  - `docs/complete/WINDOW_COMMUNICATION_API.md`
  - `sliding-window-inpage-pagination-notes.md`
