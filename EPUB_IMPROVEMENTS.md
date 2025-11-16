# EPUB Parser Improvements

This document describes the improvements made to the EPUB parser to address formatting, TOC, and cover image issues.

## Issues Fixed

### 1. Text Formatting Preservation

**Problem**: EPUB books were displaying as plain text with no formatting, losing paragraph breaks, headings, lists, and other HTML structure.

**Solution**: 
- Modified `EpubParser.getPageContent()` to better preserve HTML structure
- Implemented `cleanHtmlForDisplay()` method that:
  - Removes script and style tags (for security and performance)
  - Preserves all other HTML elements (paragraphs, headings, lists, emphasis, etc.)
  - Returns the cleaned HTML for rendering

**Result**: Books now display with proper formatting including:
- Paragraph breaks
- Headings (h1, h2, h3, etc.)
- Lists (ordered and unordered)
- Emphasis (bold, italic, etc.)
- Block quotes and other semantic elements

### 2. Table of Contents (TOC) Parsing

**Problem**: TOC was showing only file names (e.g., "chapter01.xhtml") instead of actual chapter titles.

**Solution**: Implemented proper TOC parsing for both EPUB 2 and EPUB 3:

#### EPUB 2 (NCX files):
- `parseNcxToc()` - Parses the NCX file referenced in the OPF
- `parseNavPoints()` - Recursively processes navigation points
- Extracts chapter titles, page mappings, and hierarchy levels

#### EPUB 3 (nav.xhtml):
- `parseNavToc()` - Parses the HTML navigation document
- `parseNavList()` - Recursively processes nested lists
- Supports the EPUB 3 navigation document specification

**Features**:
- Hierarchical chapter structure with proper nesting
- Visual indentation in the chapter list (16dp per level)
- Accurate page/chapter mapping to spine items
- Fallback to file-based TOC if parsing fails

### 3. Cover Image Extraction and Display

**Problem**: Cover images were not being extracted or displayed in the library view or as the first page.

**Solution**: Implemented `extractCoverImage()` with multiple detection methods:

#### Detection Methods (in order):
1. **Metadata**: Looks for `<meta name="cover" content="..."/>` in OPF metadata
2. **Manifest Properties**: Searches for `properties="cover-image"` in EPUB 3
3. **Common Names**: Checks for items with "cover" in ID or href
4. **Direct Files**: Falls back to common filenames (cover.jpg, cover.png, etc.)

#### Processing:
- Extracts the image from the EPUB ZIP archive
- Validates it's a valid image using BitmapFactory
- Saves to `.covers/` cache directory next to the book
- Stores path in BookMeta.coverPath for library display

**Result**: 
- Covers automatically display in library grid (BooksAdapter already has this support)
- Cover path is persisted in database
- Efficient caching prevents re-extraction on subsequent scans

## Technical Details

### File Structure
```
/path/to/books/
  ├── MyBook.epub
  └── .covers/
      └── MyBook_cover.jpg  (auto-generated)
```

### Code Changes

#### EpubParser.kt
- Added Bitmap and BitmapFactory imports for image handling
- Updated `extractMetadata()` to call `extractCoverImage()`
- Rewrote `getPageContent()` to preserve HTML structure
- Rewrote `getTableOfContents()` to parse NCX/nav files
- Added 6 new helper methods for TOC and cover extraction

#### ChaptersBottomSheet.kt
- Updated `ChapterViewHolder.bind()` to apply indentation based on `TocEntry.level`
- Indent calculation: `level * 16dp`

### Compatibility

- **EPUB 2**: Full support via NCX file parsing
- **EPUB 3**: Full support via nav.xhtml parsing
- **Fallback**: File-based TOC if no NCX/nav found
- **Error Handling**: Graceful degradation on parsing errors

## Testing

To test the improvements:

1. **Text Formatting**:
   - Open an EPUB with formatted content
   - Verify paragraphs, headings, and lists display correctly
   - Check that styling is preserved

2. **TOC**:
   - Tap the chapters icon in reader toolbar
   - Verify actual chapter titles are shown (not file names)
   - Check hierarchical indentation for nested chapters
   - Tap a chapter to verify navigation works

3. **Covers**:
   - Scan a directory with EPUB files
   - Verify covers appear in library grid
   - Check `.covers/` directory is created
   - Verify cover files are JPEG format

## Performance Considerations

- **Cover Extraction**: Only happens during metadata extraction (scanning)
- **Caching**: Covers are saved to disk and reused
- **TOC Parsing**: Performed on-demand when user opens TOC
- **HTML Cleaning**: Minimal processing, preserves most structure

## Future Enhancements

Possible future improvements:
- Support for CSS styling preservation
- Custom font embedding support  
- Image extraction and display within content
- Advanced TOC features (search, bookmarking specific sections)
- Cover image quality/size optimization options
