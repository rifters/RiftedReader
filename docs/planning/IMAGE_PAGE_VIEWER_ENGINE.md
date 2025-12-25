# Image Page Viewer Engine Architecture

## Overview

The Image Page Viewer Engine is a core component of RiftedReader responsible for rendering and managing image-based document formats. It provides a unified interface for handling multiple formats including PDF, CBZ (Comic Book ZIP), and CBR (Comic Book RAR) files.

## Supported Formats

### PDF (Portable Document Format)
- **Description**: Universal document format supporting both text and images
- **Use Case**: Academic papers, technical documentation, ebooks
- **Key Features**: 
  - Vector and raster content
  - Multiple pages
  - Embedded metadata
  - Text layer support (when available)

### CBZ (Comic Book ZIP)
- **Description**: Comic book archive format using ZIP compression
- **Use Case**: Digital comics, manga, webtoons
- **Key Features**:
  - Multiple image files (typically JPG/PNG)
  - Lightweight compression
  - Wide reader compatibility

### CBR (Comic Book RAR)
- **Description**: Comic book archive format using RAR compression
- **Use Case**: Digital comics, manga, legacy archives
- **Key Features**:
  - Superior compression ratio compared to ZIP
  - Wide compatibility in comic reader ecosystem
  - Better for large collections

## Architecture Components

### 1. Format Detection Layer
- **Responsibility**: Identify file format based on extension and magic bytes
- **Implementation**: Validates file integrity before processing
- **Error Handling**: Provides clear feedback for unsupported or corrupted files

### 2. Document Loader
- **PDF Loader**: Utilizes PDF libraries (e.g., pdfjs, pdf.js) for rendering
- **Archive Loader**: Handles ZIP/RAR extraction and image enumeration
- **Lazy Loading**: Loads pages/images on-demand to optimize memory usage

### 3. Image Rendering Engine
- **Rasterization**: Converts PDF pages to images at specified DPI
- **Caching**: Maintains rendered page cache for performance optimization
- **Scaling**: Intelligent scaling algorithms for various screen sizes
- **Color Management**: Proper color space handling (RGB, CMYK, etc.)

### 4. Page Navigation Controller
- **Sequential Navigation**: Next/Previous page controls
- **Direct Navigation**: Jump to specific page number
- **Bookmarking**: Save reading position and bookmarks
- **Thumbnail Generation**: Quick visual preview of pages

### 5. Viewer Display Manager
- **Zoom Levels**: Multiple zoom options (fit-to-width, fit-to-page, custom)
- **Rotation**: 90° rotation support for landscape/portrait optimization
- **Panning**: Smooth pan and scroll for zoomed content
- **Dual-Page Mode**: Display two pages simultaneously (comic mode)

## Data Flow

```
User Input
    ↓
File Selection → Format Detection
    ↓
Document Loader (PDF/CBZ/CBR specific)
    ↓
Archive Extraction (if applicable)
    ↓
Page/Image Enumeration
    ↓
Image Cache Manager
    ↓
Rendering Engine
    ↓
Display Manager
    ↓
User Interface
```

## Performance Considerations

### Memory Management
- **Selective Caching**: Cache current + adjacent pages only
- **Memory Pooling**: Reuse image buffers to reduce allocation overhead
- **Garbage Collection**: Implement proper cleanup for unused resources

### Rendering Optimization
- **DPI Scaling**: Render at device DPI for native quality
- **Progressive Loading**: Show low-resolution preview while high-res loads
- **Background Processing**: Offload rendering to worker threads

### File I/O Optimization
- **Stream Reading**: Use streaming for large files
- **Indexed Archive Access**: Quick random access to archive contents
- **Decompression Efficiency**: Optimize extraction buffer sizes

## API Interface

### Core Methods

```
openDocument(filePath: string): Document
  └─ Returns: Document object with metadata

getPageCount(): number
  └─ Returns: Total number of pages

getPage(pageNumber: number): Promise<Image>
  └─ Returns: Rendered image for specified page

navigateToPage(pageNumber: number): void
  └─ Moves viewer to specified page

zoomTo(level: ZoomLevel): void
  └─ ZoomLevel: 'fit-width' | 'fit-page' | number

rotate(degrees: 90 | 180 | 270): void
  └─ Rotates current view

getThumbnail(pageNumber: number, size: number): Promise<Image>
  └─ Returns: Thumbnail image

setBookmark(pageNumber: number, label: string): void
  └─ Saves bookmark at page

getBookmarks(): Bookmark[]
  └─ Returns: All bookmarks
```

## Error Handling

### File-Level Errors
- **Corrupted File**: Detection and graceful failure notification
- **Unsupported Format**: Clear error message with supported formats list
- **File Locked**: Attempt retry with user notification
- **Permission Denied**: Clear security-related error messaging

### Rendering Errors
- **Memory Exhaustion**: Reduce cache size or alert user
- **Timeout**: Fallback to lower quality or show cached version
- **Missing Codec**: Inform user of required dependencies

## Configuration Options

```
ViewerConfig {
  maxCachePages: number         // Default: 5
  renderDPI: number             // Default: 96
  maxZoomLevel: number          // Default: 300%
  minZoomLevel: number          // Default: 50%
  enableThumbnails: boolean     // Default: true
  preloadAdjacentPages: boolean // Default: true
  compressionQuality: number    // Default: 0.85
}
```

## Extension Points

### Custom Format Support
- Plugin interface for additional archive formats
- Pluggable rendering backends
- Custom color profile support

### UI Integration
- Event system for page changes
- Progress callbacks for long operations
- Zoom and scroll event handlers

## Testing Strategy

### Unit Tests
- Format detection accuracy
- Archive enumeration correctness
- Image rendering quality validation

### Integration Tests
- Full workflow for each supported format
- Large file handling
- Memory usage under sustained load

### Performance Tests
- Rendering speed benchmarks
- Cache hit ratio analysis
- Memory footprint tracking

## Future Enhancements

- **OCR Support**: Extract text from image-based documents
- **Annotation Tools**: Add markers, highlights, notes
- **Spread Optimization**: Intelligent spread detection for comics
- **3D Flip Animation**: Page flip visualization
- **Video Format Support**: Extend to animated formats
- **Cloud Integration**: Stream large files from cloud storage

## Security Considerations

- **Malicious Archive Detection**: Scan for zip bombs and path traversal attacks
- **Memory Limits**: Enforce maximum allocation per document
- **Sandboxing**: Isolate rendering engine for untrusted content
- **Dependency Management**: Keep rendering libraries updated

## Related Documentation

- [User Interface Specifications](./UI_SPECIFICATIONS.md)
- [Performance Benchmarks](./PERFORMANCE_BENCHMARKS.md)
- [API Reference](../api/VIEWER_API.md)
- [Troubleshooting Guide](../guides/TROUBLESHOOTING.md)
