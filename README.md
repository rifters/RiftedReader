# RiftedReader
A modern ebook reader for Android, inspired by LibreraReader

## Current Project Status

The project is in an advanced stage, with a focus on finalizing a custom, high-performance reader core and refining key features before moving to cloud integration.

### ⭐ **Current Focus: Reader Core**

*   **Sliding Window Pagination**: We are actively implementing a custom **sliding window pagination** engine for the reader. This innovative approach replaces standard components like `ViewPager2` to provide a more responsive, memory-efficient, and bug-free reading experience, especially for large documents.

### ✅ **Completed & Nearing Completion**

*   **Core Architecture**: A modern MVVM architecture using Kotlin, Coroutines, Hilt, and Room is fully implemented.
*   **Advanced Text-to-Speech (TTS)**: The complete TTS engine is implemented, including the sophisticated text/regex replacement system and background service. *(Needs final testing)*.
*   **Advanced Library & UI**: The database and UI for managing collections, saved searches, and viewing library statistics are implemented. *(Needs final review and polish)*.
*   **Core Parsers**: Parsers for **TXT, EPUB, PDF, and HTML** are complete and fully integrated.
*   **User Interface**: The app uses Material Design 3 and includes a functional library screen and reader framework.

### 🟡 **In Progress / Scaffolding**

*   **Advanced Parsers**: The application can identify a wide range of formats (MOBI, FB2, CBZ, etc.) via a `FormatCatalog`, but full content extraction is not yet implemented. These are currently handled by a `PreviewParser`.

### 🔜 **Next Major Goals**

*   **Finalize Reader Core**: Complete and integrate the new sliding window paginator.
*   **Implement Advanced Parsers**: Develop full content extraction for MOBI, FB2, and other planned formats.
*   **Cloud Sync**: Begin development of cloud provider integrations (Google Drive, Dropbox).

## Documentation

This project includes detailed planning and analysis documents:

### 📚 Core Documentation

1. **[LIBRERA_ANALYSIS.md](docs/LIBRERA_ANALYSIS.md)** - Complete analysis of LibreraReader
   - Supported formats and parsing architecture
   - Database and library management
   - UI/UX patterns and design
   - File storage handling
   - Detailed TTS feature analysis
   - Dependencies and libraries
   - Implementation recommendations

2. **[TTS_IMPLEMENTATION_GUIDE.md](docs/TTS_IMPLEMENTATION_GUIDE.md)** - TTS Implementation Guide
   - Complete TTS system architecture
   - Advanced replacement/substitution system
   - Code examples and implementation details
   - Background service setup
   - Testing strategies
   - Integration checklist

3. **[UI_UX_DESIGN_GUIDE.md](UI_UX_DESIGN_GUIDE.md)** - UI/UX Design Specifications
   - Color schemes and theming
   - Screen layouts and components
   - Gesture controls
   - Responsive design patterns
   - Accessibility features
   - Animation specifications

4. **[IMPLEMENTATION_ROADMAP.md](IMPLEMENTATION_ROADMAP.md)** - Development Roadmap
   - 20-week staged implementation plan
   - Week-by-week breakdown
   - Deliverables and success criteria
   - Risk management
   - Best practices

## Key Features (Planned)

### Format Support
- PDF, EPUB, EPUB3, MOBI, AZW, AZW3
- FB2, TXT, RTF, HTML
- CBZ, CBR (Comic books)
- DOC, DOCX

| Format | Status | Notes |
| --- | --- | --- |
| TXT | ✅ Supported | Streaming parser in `TxtParser` |
| EPUB | ✅ Supported | Spine & metadata extraction via JSoup |
| PDF | ✅ Supported | AndroidPdfViewer wrapper |
| MOBI / AZW / AZW3 | 🟡 In Progress | Preview parser + roadmap for libmobi integration |
| FB2 | 🟡 In Progress | XML preview parser scaffolding |
| CBZ | 🟡 In Progress | Archive preview parser scaffolding |
| HTML | ✅ Supported | Jsoup-based parser aligned with Librera''s HtmlExtractor |
| CBR, RTF, DOCX | 🔜 Planned | Format descriptors tracked via `FormatCatalog` |

### Text-to-Speech ⭐ Core Feature
- Advanced TTS with Android TextToSpeech API
- **Sophisticated replacement system**:
  - Simple text replacements
  - Regular expression support
  - Special commands (SKIP, STOP, NEXT, PAUSE)
  - @Voice Reader compatibility
- Background reading with notification controls
- Customizable speed and pitch
- Sentence highlighting
- Auto-scroll during reading

### Library Management
- Fast file scanning
- Metadata extraction
- Collections and tags
- Collections data model & repository ready for UI wiring
- Advanced search and filtering
- Library statistics calculator (totals, completion averages)
- Reading progress tracking
- Favorites system

### Reading Experience
- Highly customizable (fonts, colors, spacing)
- Multiple themes (Light, Dark, Sepia, Black OLED)
- Gesture-based navigation
- Configurable tap zones
- Page and scroll modes
- Bookmarks and highlights

### Cloud & Sync (Scaffolding)
- Cloud provider abstraction matching Google Drive / Dropbox roadmap
- Stub Google Drive provider for integration testing
- OPDS client data structures for catalog browsing

### Modern Architecture
- Clean Architecture (MVVM)
- Kotlin Coroutines and Flow
- Room Database
- Material Design 3
- Jetpack Compose (future consideration)

## Technology Stack

- **Language**: Kotlin
- **Minimum SDK**: 24 (Android 7.0)
- **Target SDK**: 34 (Android 14)
- **Architecture**: MVVM with Repository pattern
- **Database**: Room
- **DI**: Hilt
- **Async**: Coroutines + Flow
- **UI**: Material Design 3 Components

See [IMPLEMENTATION_ROADMAP.md](IMPLEMENTATION_ROADMAP.md) for complete timeline.

## Inspiration

This project is inspired by [LibreraReader](https://github.com/foobnix/LibreraReader) (GPL v3), a mature and feature-rich ebook reader. RiftedReader aims to bring similar functionality with a modern tech stack and architecture.

**Note**: RiftedReader is being built from scratch based on feature analysis, not by copying code from LibreraReader.

## License

TBD - Will be determined after initial implementation

## Contributing

Project is in planning phase. Contributions will be welcome once development begins.

## Contact

For questions or suggestions, please open an issue.

---
*Last Updated: 2025-11-20*