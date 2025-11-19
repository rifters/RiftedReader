# Debug Logging for EPUB Cover Image Rendering Issue

## Summary

This PR adds comprehensive debug logging to help diagnose why EPUB cover images display correctly in the library but show broken image placeholders in the reader.

## Changes Made

### 1. Enhanced Debug Logging

Added detailed logging throughout the EPUB cover processing pipeline with `[COVER_DEBUG]` prefix:

#### Files Modified:
- **`app/src/main/java/com/rifters/riftedreader/ui/reader/ReaderViewModel.kt`**
  - Logs cover path setting with file existence, permissions, and size
  - Logs when no cover path is available

- **`app/src/main/java/com/rifters/riftedreader/domain/parser/EpubParser.kt`**
  - Enhanced `setCoverPath()` with file accessibility logging
  - Added extensive logging to `getPageContent()` image processing:
    - Image detection and path resolution
    - Cover image identification logic
    - Cached cover file operations
    - Base64 encoding details
    - ZIP extraction fallback
    - Exception handling with stack traces
  - Added detailed logging to `extractCoverImage()`:
    - All three detection methods
    - Image path resolution
    - ZIP operations
    - Bitmap decoding
    - File save operations

- **`app/build.gradle.kts`**
  - Added `testOptions { unitTests { isReturnDefaultValues = true } }` to support Android Log mocking in unit tests

### 2. Test Coverage

- **New file**: `app/src/test/java/com/rifters/riftedreader/EpubCoverDebugLoggingTest.kt`
  - 5 test cases validating logging infrastructure
  - Tests cover detection logic with various scenarios
  - Includes edge cases and case-insensitive detection tests
  - All 90 unit tests pass

### 3. Documentation

- **New file**: `docs/EPUB_COVER_DEBUG_GUIDE.md`
  - Comprehensive debugging guide for developers
  - Explains all debug points and their meanings
  - Documents expected log sequences for success and failure scenarios
  - Includes common issues and solutions
  - Provides quick diagnostic checklist

## How to Use

### For Developers Debugging Cover Issues:

1. **Enable Debug Build**
   - Ensure you're running a debug build (BuildConfig.DEBUG = true)

2. **Filter Logcat**
   ```bash
   adb logcat | grep "\[COVER_DEBUG\]"
   ```
   Or in Android Studio Logcat, filter by: `[COVER_DEBUG]`

3. **Follow the Debug Guide**
   - Open `docs/EPUB_COVER_DEBUG_GUIDE.md`
   - Compare actual logs to expected sequences
   - Use diagnostic checklist to identify failure point

4. **Typical Log Sequence**
   ```
   [COVER_DEBUG] Starting cover image extraction...
   [COVER_DEBUG] Cover saved successfully...
   [COVER_DEBUG] Setting cover path for EPUB...
   [COVER_DEBUG] Processing image on page 0...
   [COVER_DEBUG] Successfully set cached cover as img.src...
   ```

### Debug Points Coverage:

✅ **Cover Extraction** (during library scan)
- Detection method success/failure
- File operations
- Save location and size

✅ **Cover Path Transfer** (when opening book)
- Path setting in ReaderViewModel
- Path caching in EpubParser

✅ **Cover Image Processing** (during page rendering)
- Image detection in HTML
- Path resolution
- Cached cover usage vs ZIP extraction
- Base64 encoding
- Setting data URI in img.src

## Performance Impact

- **Debug Builds**: Full logging enabled
- **Release Builds**: Zero impact (all logging is conditional on BuildConfig.DEBUG)

## Testing

All tests pass:
```bash
./gradlew test
# BUILD SUCCESSFUL
# 90 tests completed
```

## Next Steps

When a user reports a cover issue:

1. Ask them to provide logs filtered by `[COVER_DEBUG]`
2. Use the guide to analyze the log sequence
3. Identify where the process fails:
   - Extraction failure → Cover detection issue
   - Path transfer failure → ViewModel/Parser communication issue  
   - Processing failure → File access or encoding issue
   - WebView rendering failure → Data URI or CSP issue

## Files Changed

```
app/build.gradle.kts                                                      |  6 ++
app/src/main/java/com/rifters/riftedreader/domain/parser/EpubParser.kt   | 72 ++++++++++++++++++++++++++++
app/src/main/java/com/rifters/riftedreader/ui/reader/ReaderViewModel.kt  |  5 ++
app/src/test/java/com/rifters/riftedreader/EpubCoverDebugLoggingTest.kt  | 104 ++++++++++++++++++++++++++++++++++++++++
docs/EPUB_COVER_DEBUG_GUIDE.md                                            | 313 +++++++++++++++++++++++++++++++++++++++++++++++++++
```

Total: 5 files changed, 500 insertions(+)

## Related Documentation

- Main debugging guide: `docs/EPUB_COVER_DEBUG_GUIDE.md`
- LibreraReader analysis: `LIBRERA_ANALYSIS.md` 
- EPUB improvements doc: `EPUB_IMPROVEMENTS.md`

## Issue Reference

This PR addresses the debugging requirements for the issue:
> Debug EPUB cover image rendering issue on first page (broken image placeholder)

The logging infrastructure added will help identify exactly where in the cover processing pipeline the failure occurs.
