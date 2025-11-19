# EPUB Cover Image Debugging Guide

## Overview
This guide explains how to use the comprehensive debug logging added to diagnose EPUB cover image rendering issues.

## Problem Statement
EPUB cover images display correctly in the library grid but show broken image placeholders when viewing the first page in the reader. This indicates the cached cover path works for the library but not for page rendering.

## Debug Logging Infrastructure

All cover-related debug logs use the `[COVER_DEBUG]` prefix for easy filtering with logcat or Android Studio.

### Filtering Logs

To view only cover-related debug logs:

```bash
# Using adb logcat
adb logcat | grep "\[COVER_DEBUG\]"

# In Android Studio Logcat
# Set filter to: [COVER_DEBUG]
```

## Debug Points and What They Tell You

### 1. Cover Path Setting (ReaderViewModel)

**Location**: `ReaderViewModel.buildPagination()`

**What to look for**:
```
[COVER_DEBUG] Setting cover path for EPUB: /storage/emulated/0/Books/.covers/sample_cover.jpg
[COVER_DEBUG] Cover file exists: true, canRead: true, size: 45632 bytes
```

**Or if no cover**:
```
[COVER_DEBUG] No cover path available for EPUB (book.coverPath is null)
```

**Diagnosis**:
- If `exists: false` → Cover wasn't extracted properly during metadata extraction
- If `canRead: false` → File permission issue
- If `size: 0` → Empty or corrupted cover file
- If path is null → Cover detection failed during book scanning

---

### 2. Cover Path Caching (EpubParser.setCoverPath)

**Location**: `EpubParser.setCoverPath()`

**What to look for**:
```
[COVER_DEBUG] setCoverPath called with: /storage/emulated/0/Books/.covers/sample_cover.jpg
[COVER_DEBUG] Cached cover file exists: true, canRead: true, absolutePath: /storage/emulated/0/Books/.covers/sample_cover.jpg
```

**Diagnosis**:
- This confirms the cover path was successfully passed to the parser
- If this log is missing, `setCoverPath()` was never called

---

### 3. Cover Image Processing (EpubParser.getPageContent)

**Location**: `EpubParser.getPageContent()` - image processing loop

#### 3a. Image Detection
```
[COVER_DEBUG] Processing image on page 0: src='../Images/cover.jpg'
[COVER_DEBUG] Resolved image path: 'OEBPS/Images/cover.jpg' (from contentDir='OEBPS/Text', originalSrc='../Images/cover.jpg')
[COVER_DEBUG] Is cover image: true (imagePath.contains('cover'): true, originalSrc.contains('cover'): true)
```

**Diagnosis**:
- Confirms image was found in HTML
- Shows path resolution working correctly
- Confirms cover detection logic

#### 3b. Cached Cover Usage (Success)
```
[COVER_DEBUG] cachedCoverPath: /storage/emulated/0/Books/.covers/sample_cover.jpg
[COVER_DEBUG] Attempting to use cached cover image from: /storage/emulated/0/Books/.covers/sample_cover.jpg
[COVER_DEBUG] Cover file details: exists=true, canRead=true, length=45632
[COVER_DEBUG] Successfully read 45632 bytes from cached cover
[COVER_DEBUG] Base64 encoded cover (length: 60843, first 50 chars: /9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgHBwc...)
[COVER_DEBUG] Setting img.src to data URI (total length: 60868)
[COVER_DEBUG] Successfully set cached cover as img.src for image: ../Images/cover.jpg
```

**Diagnosis**:
- **THIS IS SUCCESS** - Cover should display correctly
- If you see this but still get broken image, the problem is in WebView rendering

#### 3c. Cached Cover Usage (Failure)
```
[COVER_DEBUG] Cover image detected but cached file doesn't exist: /storage/emulated/0/Books/.covers/sample_cover.jpg (exists: false)
```
**Or**:
```
[COVER_DEBUG] Cover image detected but no cached cover path available
```
**Or**:
```
[COVER_DEBUG] Error using cached cover, falling back to ZIP extraction. Error: FileNotFoundException: /storage/emulated/0/Books/.covers/sample_cover.jpg
```

**Diagnosis**:
- Cover file was deleted after extraction
- Cover extraction failed but path was still recorded
- File permission changed

#### 3d. ZIP Extraction Fallback
```
[COVER_DEBUG] Attempting to load image from EPUB ZIP: OEBPS/Images/cover.jpg
[COVER_DEBUG] Found image in ZIP: OEBPS/Images/cover.jpg (size: 45123)
[COVER_DEBUG] Read 45123 bytes from ZIP entry
[COVER_DEBUG] Detected MIME type: image/jpeg
[COVER_DEBUG] Base64 encoded image from ZIP (length: 60164)
[COVER_DEBUG] Successfully set img.src from ZIP for image: ../Images/cover.jpg
```

**Diagnosis**:
- Cached cover failed, successfully fell back to ZIP extraction
- If this succeeds but image still broken, WebView issue

#### 3e. Complete Failure
```
[COVER_DEBUG] Image not found in EPUB ZIP: OEBPS/Images/cover.jpg (originalSrc: ../Images/cover.jpg)
```

**Diagnosis**:
- Path resolution is wrong
- EPUB structure doesn't match expectations
- Image really doesn't exist in EPUB

---

### 4. Cover Extraction (EpubParser.extractCoverImage)

**Location**: `EpubParser.extractCoverImage()`

**Full successful extraction sequence**:
```
[COVER_DEBUG] Starting cover image extraction for: sample.epub
[COVER_DEBUG] Method 1 (metadata): coverId='cover-image'
[COVER_DEBUG] Image path from manifest (coverId='cover-image'): 'OEBPS/Images/cover.jpg'
[COVER_DEBUG] Full image path in ZIP: 'OEBPS/Images/cover.jpg'
[COVER_DEBUG] Found image entry in ZIP (size: 45123)
[COVER_DEBUG] Read 45123 bytes from ZIP
[COVER_DEBUG] Successfully decoded bitmap: 800x1200
[COVER_DEBUG] Saving cover to: /storage/emulated/0/Books/.covers/sample_cover.jpg
[COVER_DEBUG] Cover saved successfully: /storage/emulated/0/Books/.covers/sample_cover.jpg (size: 45632 bytes)
```

**If Method 1 fails, tries Method 2**:
```
[COVER_DEBUG] Method 1 (metadata): coverId=''
[COVER_DEBUG] Method 2 (cover-image property): coverId='cover-img'
```

**If Methods 1 & 2 fail, tries Method 3**:
```
[COVER_DEBUG] Method 1 (metadata): coverId=''
[COVER_DEBUG] Method 2 (cover-image property): coverId=''
[COVER_DEBUG] Method 3 (name search): coverId='item-cover', coverItem href='Images/cover.jpg', id='item-cover'
```

**Complete failure**:
```
[COVER_DEBUG] Starting cover image extraction for: sample.epub
[COVER_DEBUG] Method 1 (metadata): coverId=''
[COVER_DEBUG] Method 2 (cover-image property): coverId=''
[COVER_DEBUG] Method 3 (name search): coverId='', coverItem href='null', id='null'
[COVER_DEBUG] No cover image found for: sample.epub
```

**Diagnosis**:
- Shows which detection method succeeded
- Common failure: EPUB doesn't follow standard cover naming conventions
- Image might exist but not marked as cover in manifest

---

## Common Issues and Solutions

### Issue 1: Cover Displays in Library but Not in Reader

**Expected logs**:
- ✅ Cover extraction succeeds
- ✅ setCoverPath called successfully
- ❌ "Cover image detected but cached file doesn't exist" in getPageContent

**Cause**: Cover file deleted between library scan and opening book

**Solution**: Verify `.covers/` directory permissions and stability

---

### Issue 2: Base64 Data URI Generated but Still Shows Broken Image

**Expected logs**:
- ✅ All logging shows success
- ✅ "Successfully set cached cover as img.src"
- ❌ WebView still shows broken image

**Cause**: WebView rendering issue, not detection/loading issue

**Possible reasons**:
1. Data URI too large (check length in logs)
2. MIME type incorrect
3. Base64 encoding corrupted
4. WebView CSP (Content Security Policy) blocking data URIs

**Solution**: Check WebView error logs, verify data URI format

---

### Issue 3: Cover Not Found During Extraction

**Expected logs**:
- ❌ All three detection methods return empty coverId
- ❌ "No cover image found for: book.epub"

**Cause**: EPUB doesn't follow standard cover conventions

**Solution**: 
1. Extract EPUB and check manifest.opf manually
2. Look for actual cover image location
3. May need to enhance detection logic

---

### Issue 4: Path Resolution Fails

**Expected logs**:
- ✅ Cover detected: `isCoverImage: true`
- ❌ "Image not found in EPUB ZIP: incorrect/path.jpg"

**Cause**: `resolveRelativePath()` logic issue

**Solution**: Check contentDir and originalSrc in logs, verify path resolution logic

---

## Testing Your Changes

### 1. Enable Debug Logging
Ensure `BuildConfig.DEBUG` is true in your debug build.

### 2. Open an EPUB
1. Open any EPUB book in the app
2. Navigate to the first page
3. Check logcat for `[COVER_DEBUG]` entries

### 3. Analyze the Log Sequence

**Expected successful sequence**:
1. Cover extraction logs during library scan
2. setCoverPath when opening book
3. Cover path setting in ReaderViewModel
4. Image processing in getPageContent
5. Successful cached cover usage OR ZIP extraction

### 4. Identify Failure Point

Work backwards from where logs stop or show failures:
- No extraction logs → Cover detection in extractCoverImage fails
- No setCoverPath logs → ReaderViewModel not passing path
- No cached cover usage → File access issue
- No successful img.src set → Path resolution or encoding issue

---

## Additional Notes

### Performance Impact
- All logging is conditional on `BuildConfig.DEBUG`
- Zero performance impact in release builds
- Logging is verbose to aid debugging

### Log Prefix
All cover-related logs use `[COVER_DEBUG]` prefix for easy filtering.

### File Paths
All file paths are logged in full (absolute paths) to make verification easier.

### Base64 Encoding
- Only first 50 characters of base64 are logged to avoid log spam
- Length is logged in full to verify successful encoding

---

## Quick Diagnostic Checklist

When debugging a cover issue:

1. ☐ Is cover extracted? (Check extractCoverImage logs)
2. ☐ Is cover path passed to parser? (Check setCoverPath logs)
3. ☐ Does cover file exist and readable? (Check file details in logs)
4. ☐ Is cover detected in page HTML? (Check isCoverImage logs)
5. ☐ Is cached cover path available? (Check cachedCoverPath logs)
6. ☐ Is base64 encoding successful? (Check encoding length logs)
7. ☐ Is img.src set successfully? (Check "Successfully set" logs)
8. ☐ If all above pass, check WebView error logs

---

## Contact

If you're still stuck after reviewing logs, include the full `[COVER_DEBUG]` log sequence in your bug report.
