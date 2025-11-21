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

Documentation is organized in the `docs/` directory by category:

### 📚 Complete Documentation ([docs/complete/](docs/complete/))

Current, finalized documentation for the project:

1. **[ARCHITECTURE.md](docs/complete/ARCHITECTURE.md)** - System Architecture (Canonical)
   - Layer diagrams and component relationships
   - Package structure and dependencies
   - Data flow and state management
   - Design patterns and best practices

2. **[DEVELOPMENT_SETUP.md](docs/complete/DEVELOPMENT_SETUP.md)** - Getting Started Guide
   - Environment setup and prerequisites
   - Building and running the project
   - Key concepts overview
   - Development workflow

3. **[LIBRERA_ANALYSIS.md](docs/complete/LIBRERA_ANALYSIS.md)** - LibreraReader Analysis
   - Format support and parsing architecture
   - Database and library management
   - UI/UX patterns and TTS features
   - Implementation recommendations

4. **[TTS_IMPLEMENTATION_GUIDE.md](docs/complete/TTS_IMPLEMENTATION_GUIDE.md)** - TTS System Guide
   - Complete TTS architecture
   - Advanced replacement/substitution system
   - Background service setup
   - Code examples and integration

5. **[UI_UX_DESIGN_GUIDE.md](docs/complete/UI_UX_DESIGN_GUIDE.md)** - UI/UX Specifications
   - Color schemes and theming
   - Screen layouts and components
   - Gesture controls and accessibility

### 📋 Planning & Roadmap ([docs/planning/](docs/planning/))

- **[IMPLEMENTATION_ROADMAP.md](docs/planning/IMPLEMENTATION_ROADMAP.md)** - 20-week development plan
- **[STAGE_6_8_TODO.md](docs/planning/STAGE_6_8_TODO.md)** - Current implementation tasks
- **[EPUB_IMPROVEMENTS.md](docs/planning/EPUB_IMPROVEMENTS.md)** - EPUB parser enhancements
- **[WEBVIEW_PAGINATOR_ENHANCEMENTS.md](docs/planning/WEBVIEW_PAGINATOR_ENHANCEMENTS.md)** - Paginator improvements

### 📊 Implementation Status ([docs/implemented/](docs/implemented/))

- **[STAGE_1-3_SUMMARY.md](docs/implemented/STAGE_1-3_SUMMARY.md)** - Initial stages completion
- **[SLIDING_WINDOW_PAGINATION_STATUS.md](docs/implemented/SLIDING_WINDOW_PAGINATION_STATUS.md)** - Pagination progress

### 🧪 Testing Guides ([docs/testing/](docs/testing/))

- **[CONTINUOUS_PAGINATOR_VERIFICATION.md](docs/testing/CONTINUOUS_PAGINATOR_VERIFICATION.md)** - Pagination testing
- **[SCROLL_GESTURE_TESTING_GUIDE.md](docs/testing/SCROLL_GESTURE_TESTING_GUIDE.md)** - Gesture testing
- **[EPUB_COVER_DEBUG_GUIDE.md](docs/testing/EPUB_COVER_DEBUG_GUIDE.md)** - Cover image debugging

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

See [IMPLEMENTATION_ROADMAP.md](docs/planning/IMPLEMENTATION_ROADMAP.md) for complete timeline.

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