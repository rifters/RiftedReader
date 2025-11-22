# HTML Debug Logging Guide

## Overview

RiftedReader includes an automatic HTML logging system to help debug and understand how the sliding window pagination system generates HTML for books. This feature is **only active in DEBUG builds** and automatically dumps HTML blobs to a `logs/` directory.

## Purpose

The HTML debug logger helps developers:
- Inspect actual chapter HTML and understand windowing behavior
- Debug pagination issues by examining the HTML at different stages
- Understand how styling and content are combined for WebView rendering
- Track which chapters are loaded in the sliding window at any given time

## How It Works

The logger captures HTML at three critical points in the pagination pipeline:

### 1. Chapter HTML Logging (EpubParser)

When the EPUB parser extracts and processes a chapter, the raw HTML is logged.

**Location**: After parsing and image processing in `EpubParser.getPageContent()`

**Filename Format**: `book-<bookId>-chapter-<n>-<timestamp>.html`

**Includes**:
- Raw chapter HTML (after script/style removal and image processing)
- Metadata: format, content path, text length, HTML length

**Use Case**: Debug chapter parsing and image embedding issues

### 2. Window HTML Logging (ContinuousPaginator)

When the sliding window loads or shifts, a combined HTML file is created containing all chapters in the window.

**Location**: After window loading in `ContinuousPaginator.loadWindow()`

**Filename Format**: `book-<bookId>-window-<n>-<timestamp>.html`

**Includes**:
- All chapters in the current window (typically 5 chapters)
- Window metadata: total chapters, window size, global page count
- Chapter markers and boundaries for easy navigation

**Use Case**: Debug sliding window behavior and understand which chapters are loaded together

### 3. Wrapped HTML Logging (ReaderPageFragment)

When HTML is wrapped with styling and sent to the WebView, the complete final HTML is logged.

**Location**: Before WebView loading in `ReaderPageFragment.renderBaseContent()`

**Filename Format**: `book-<bookId>-chapter-<n>-wrapped-<timestamp>.html`

**Includes**:
- Complete HTML with `<head>`, `<style>`, and JavaScript
- All styling applied (colors, fonts, line height, etc.)
- Metadata: theme, text size, line height, pagination mode

**Use Case**: Debug WebView rendering and styling issues

## Log File Location

Logs are saved to the device's external files directory:

```
<External Files Dir>/logs/
```

On a real device or emulator, this typically maps to:

```
/storage/emulated/0/Android/data/com.rifters.riftedreader/files/logs/
```

You can access these files via:
- Android Studio's Device File Explorer
- ADB: `adb pull /storage/emulated/0/Android/data/com.rifters.riftedreader/files/logs/`
- Any file manager app with appropriate permissions

## Automatic Cleanup

To prevent disk space issues, the logger automatically cleans up old files:

- **When**: On application termination
- **What**: Keeps only the 50 most recent HTML log files
- **Why**: Prevents unbounded growth of log files

You can manually trigger cleanup or change the retention count:

```kotlin
HtmlDebugLogger.cleanupOldLogs(maxFiles = 100) // Keep 100 files instead of 50
```

## Log File Format

### Chapter Log

```html
<!--
  HTML Debug Log
  Book ID: /path/to/book.epub
  Chapter Index: 5
  Timestamp: 20250122-143052-123
  Date: 2025-01-22 14:30:52
  Metadata:
    format: EPUB
    contentPath: OEBPS/chapter05.xhtml
    textLength: 5432
    htmlLength: 8901
-->

<p>Chapter content here...</p>
```

### Window Log

```html
<!--
  HTML Debug Log - Sliding Window
  Book ID: /path/to/book.epub
  Window Index: 10
  Chapters in Window: 8, 9, 10, 11, 12
  Timestamp: 20250122-143052-456
  Date: 2025-01-22 14:30:52
  Metadata:
    totalChapters: 50
    windowSize: 5
    totalGlobalPages: 250
    loadedChapterCount: 5
-->

<!DOCTYPE html>
<html>
<head>...</head>
<body>
  <h1>Sliding Window Debug Log</h1>
  ...
  
  <!-- BEGIN CHAPTER 8 -->
  <section id="chapter-8">
    <h2>Chapter 8</h2>
    <p>Chapter 8 content...</p>
  </section>
  <!-- END CHAPTER 8 -->
  
  <!-- BEGIN CHAPTER 9 -->
  ...
</body>
</html>
```

### Wrapped Log

```html
<!--
  HTML Debug Log - WebView Wrapped
  Book ID: /path/to/book.epub
  Chapter Index: 10
  Timestamp: 20250122-143052-789
  Date: 2025-01-22 14:30:52
  Note: This is the final HTML sent to WebView with all styling and scripts
  Metadata:
    pageIndex: 10
    textSize: 18.0
    lineHeight: 1.5
    theme: DARK
    paginationMode: CONTINUOUS
-->

<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8"/>
  <meta name="viewport" content="...">
  <style>
    html, body {
      background-color: #1e1e1e;
      color: #e0e0e0;
      font-size: 18px;
      line-height: 1.5;
      ...
    }
  </style>
  <script src="inpage_paginator.js"></script>
</head>
<body>
  <p>Chapter content with full styling...</p>
</body>
</html>
```

## Debugging Common Issues

### Issue: Images not displaying

1. Check **Chapter Log** for image `src` attributes
2. Look for `file://` URLs or `data:` URIs
3. Verify image caching paths are correct

### Issue: Styling not applied

1. Check **Wrapped Log** for complete CSS
2. Verify theme colors are correctly applied
3. Check font size and line height values

### Issue: Wrong chapters in window

1. Check **Window Log** to see which chapters are loaded
2. Verify window indices match expected navigation
3. Check `totalChapters` and `windowSize` metadata

### Issue: Content missing or truncated

1. Compare **Chapter Log** (raw) vs **Wrapped Log** (final)
2. Check `textLength` and `htmlLength` in metadata
3. Look for script/style tag removal side effects

## API Reference

### Initialization

```kotlin
// In Application.onCreate()
HtmlDebugLogger.init(context)
```

### Manual Logging

```kotlin
// Log a single chapter
HtmlDebugLogger.logChapterHtml(
    bookId = "/path/to/book.epub",
    chapterIndex = 5,
    html = chapterHtml,
    metadata = mapOf("format" to "EPUB")
)

// Log a window of chapters
HtmlDebugLogger.logWindowHtml(
    bookId = "/path/to/book.epub",
    windowIndex = 10,
    chapterIndices = listOf(8, 9, 10, 11, 12),
    chapters = mapOf(
        8 to chapter8Html,
        9 to chapter9Html,
        ...
    ),
    metadata = mapOf("windowSize" to "5")
)

// Log wrapped HTML
HtmlDebugLogger.logWrappedHtml(
    bookId = "/path/to/book.epub",
    chapterIndex = 10,
    wrappedHtml = completeHtml,
    metadata = mapOf("theme" to "DARK")
)
```

### Cleanup

```kotlin
// Clean up old logs (keeps 50 most recent by default)
HtmlDebugLogger.cleanupOldLogs()

// Custom retention count
HtmlDebugLogger.cleanupOldLogs(maxFiles = 100)
```

### Get Logs Directory

```kotlin
val logsDir: File? = HtmlDebugLogger.getLogsDirectory()
if (logsDir != null) {
    println("Logs saved to: ${logsDir.absolutePath}")
}
```

## Performance Considerations

- **DEBUG builds only**: No overhead in release builds
- **I/O operations**: File writes are synchronous but fast for HTML files
- **Disk space**: Automatically limited to 50 files (configurable)
- **No impact on app behavior**: Logging failures don't crash the app

## Filename Sanitization

Book IDs (typically file paths) are sanitized for safe filenames:
- Special characters (`[]()!?*"'<>|` etc.) replaced with underscores
- Spaces replaced with underscores
- Limited to 50 characters
- Only alphanumeric, dots, dashes, and underscores retained

Examples:
- `/path/to/My Book [2024].epub` → `My_Book__2024_`
- `C:\Books\Book Name.epub` → `Book_Name`

## Testing

The `HtmlDebugLoggerTest` validates:
- API contracts for all logging methods
- Handling of edge cases (empty content, missing chapters, special characters)
- Large content handling
- Cleanup functionality

Run tests:
```bash
./gradlew test --tests HtmlDebugLoggerTest
```

## Troubleshooting

### Logs not appearing?

1. Verify you're running a DEBUG build
2. Check `BuildConfig.DEBUG` is true
3. Verify `HtmlDebugLogger.init()` was called
4. Check logcat for initialization messages (tag: `HtmlDebugLogger`)

### Can't find logs directory?

```bash
# Via ADB
adb shell "ls -la /storage/emulated/0/Android/data/com.rifters.riftedreader/files/logs/"

# Pull logs to your computer
adb pull /storage/emulated/0/Android/data/com.rifters.riftedreader/files/logs/ ./logs/
```

### Permission issues?

The logs directory is in the app's external files directory, which doesn't require special permissions on Android 7+. If you can't access it:
- Use Android Studio's Device File Explorer
- Use ADB as root: `adb root` then `adb pull ...`

## Future Enhancements

Possible improvements for future versions:

1. **Browser download trigger**: For web-based testing, trigger HTML download instead of file save
2. **Log viewer UI**: Built-in log viewer within the app
3. **Selective logging**: Enable/disable specific log types via settings
4. **Compression**: Compress old logs to save space
5. **Cloud sync**: Optional upload to developer-specified endpoint

## Related Files

- **Implementation**: `app/src/main/java/com/rifters/riftedreader/util/HtmlDebugLogger.kt`
- **Tests**: `app/src/test/java/com/rifters/riftedreader/HtmlDebugLoggerTest.kt`
- **Integration points**:
  - `EpubParser.kt` - Chapter HTML logging
  - `ContinuousPaginator.kt` - Window HTML logging
  - `ReaderPageFragment.kt` - Wrapped HTML logging
  - `RiftedReaderApplication.kt` - Initialization and cleanup

---

**Note**: This feature is designed for development and debugging. It should remain disabled (or no-op) in production/release builds.
