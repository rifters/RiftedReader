# RiftedReader
A modern ebook reader for Android, inspired by LibreraReader.

## Current Project Status

RiftedReader is in active development with the core reading stack already implemented. Stages 1, 2, 3, 5, and 7 are complete; Stage 4 is partial; Stage 6 is nearly complete with HTML support remaining; Stage 8 (Cloud & Sync) has not started yet.

- ✅ Project foundation, database, library management, and core architecture
- ✅ TXT, EPUB, PDF, MOBI/AZW, AZW3/KF8, FB2, CBZ/CBR, RTF, and DOCX support
- ✅ Advanced TTS system with replacement rules, background service, notification controls, and sleep timer
- ✅ Collections, saved searches, cover management, statistics, bookmarks, and reading progress
- ⚠️ Reader polish features like pinch-to-zoom, full tap-zone configurability, notes/highlights, animations, and two-page tablet mode are still pending
- ❌ Cloud sync and OPDS/WebDAV integrations have not started

See [docs/ROADMAP.md](docs/ROADMAP.md) for the full implementation roadmap and project status.

## Dependencies

- Kotlin
- Coroutines / Flow
- Room
- Hilt
- AndroidX Navigation, Lifecycle, Preference, DataStore, WebKit, Security, and Media
- Material Design 3
- jsoup, Retrofit, OkHttp, Coil, Zip4j, Apache POI, and PDF rendering dependencies

## What is implemented

- Library scanning and metadata indexing
- Collections, saved searches, favorites, and statistics
- Bookmark system and reading progress persistence
- Reader UI with page and scroll modes
- Typography customization, themes, and overlay controls
- TTS engine, replacement rules, TTS settings, and foreground playback service
- Core parsers for TXT, EPUB, PDF, MOBI/AZW, AZW3/KF8, FB2, CBZ/CBR, RTF, and DOCX
- Calibre-related browsing and download flows present in the app

## Known issues / remaining work

- Pinch-to-zoom is not implemented yet
- Full tap-zone action mapping UI is still incomplete
- Notes / highlights should still be treated as pending
- HTML format support remains the final blocker for Stage 6 closure
- Cloud sync, OPDS, and provider integrations require a dedicated planning phase before implementation
- JS tests require `cd tests/js && npm install` before the first run

## Documentation

Documentation is organized in the `docs/` directory by category:

### 📚 Core Documentation

1. **[ARCHITECTURE.md](docs/complete/ARCHITECTURE.md)** - System Architecture (Canonical)
2. **[DEVELOPMENT_SETUP.md](docs/complete/DEVELOPMENT_SETUP.md)** - Getting Started Guide
3. **[LIBRERA_ANALYSIS.md](docs/complete/LIBRERA_ANALYSIS.md)** - LibreraReader Analysis
4. **[TTS_IMPLEMENTATION_GUIDE.md](docs/complete/TTS_IMPLEMENTATION_GUIDE.md)** - TTS System Guide
5. **[UI_UX_DESIGN_GUIDE.md](docs/complete/UI_UX_DESIGN_GUIDE.md)** - UI/UX Specifications
6. **[ROADMAP.md](docs/ROADMAP.md)** - Current implementation roadmap and project status

### 📋 Additional Planning & Technical Docs

- **[HTML_DEBUG_LOGGING.md](docs/complete/HTML_DEBUG_LOGGING.md)**
- **[PAGINATOR_API.md](docs/complete/PAGINATOR_API.md)**
- **[STABLE_WINDOW_MODEL.md](docs/complete/STABLE_WINDOW_MODEL.md)**
- **[JS_STREAMING_DISCIPLINE.md](docs/complete/JS_STREAMING_DISCIPLINE.md)**
- **[IMPLEMENTATION_ROADMAP.md](docs/planning/IMPLEMENTATION_ROADMAP.md)**
- **[STAGE_6_8_TODO.md](docs/planning/STAGE_6_8_TODO.md)**
- **[EPUB_IMPROVEMENTS.md](docs/planning/EPUB_IMPROVEMENTS.md)**
- **[WEBVIEW_PAGINATOR_ENHANCEMENTS.md](docs/planning/WEBVIEW_PAGINATOR_ENHANCEMENTS.md)**
- **[STAGE_1-3_SUMMARY.md](docs/implemented/STAGE_1-3_SUMMARY.md)**
- **[SLIDING_WINDOW_PAGINATION_STATUS.md](docs/implemented/SLIDING_WINDOW_PAGINATION_STATUS.md)**
- **[CONTINUOUS_PAGINATOR_VERIFICATION.md](docs/testing/CONTINUOUS_PAGINATOR_VERIFICATION.md)**
- **[SCROLL_GESTURE_TESTING_GUIDE.md](docs/testing/SCROLL_GESTURE_TESTING_GUIDE.md)**
- **[EPUB_COVER_DEBUG_GUIDE.md](docs/testing/EPUB_COVER_DEBUG_GUIDE.md)**

## Running tests

Install the JavaScript test dependencies before running the JS suite:

```bash
cd tests/js
npm install
npm test
```

Run Android unit tests from the repository root:

```bash
./gradlew :app:testDebugUnitTest
```

Run a faster Kotlin-only compile preflight from the repository root:

```bash
./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin
```

## Key Features

### Format Support
- TXT, EPUB, PDF
- MOBI, AZW, AZW3 / KF8
- FB2, RTF, DOCX
- CBZ, CBR
- HTML support planned next

### Text-to-Speech ⭐ Core Feature
- Advanced TTS with Android TextToSpeech API
- Replacement rules with simple, regex, and command types
- Special commands: SKIP, STOP, NEXT, PAUSE
- `@Voice` rule import compatibility
- Background playback service with notification and media controls
- Customizable speed and pitch
- Auto-scroll during playback
- Sleep timer

### Library Management
- Fast file scanning
- Metadata extraction
- Collections and tags
- Saved searches
- Favorites system
- Statistics and progress tracking
- Cover management

### Reading Experience
- Custom fonts and typography controls
- Multiple themes
- Page and scroll modes
- Swipe and volume-button navigation
- Chapter navigation and bookmarks
- Overlay controls with auto-hide

### Planned features snapshot
- HTML format support
- Pinch-to-zoom and better tap-zone customization
- Notes and highlights
- Backup and restore
- Footnote popups for EPUB
- Word-highlight TTS / karaoke mode

### Modern Architecture
- MVVM + Repository pattern
- Hilt dependency injection
- Room Database
- Coroutines + Flow
- Material Design 3

## Technology Stack

- **Language**: Kotlin
- **Minimum SDK**: 24 (Android 7.0)
- **Target SDK**: 34 (Android 14)
- **Architecture**: MVVM with Repository pattern
- **Database**: Room
- **DI**: Hilt
- **Async**: Coroutines + Flow
- **UI**: Material Design 3 Components

See [docs/ROADMAP.md](docs/ROADMAP.md) for the latest implementation status and roadmap details.

## Inspiration

This project is inspired by [LibreraReader](https://github.com/foobnix/LibreraReader) (GPL v3), a mature and feature-rich ebook reader. RiftedReader aims to deliver similar depth with a modern Android architecture and a stronger focus on advanced Text-to-Speech capabilities.

**Note**: RiftedReader is being built from scratch based on feature analysis, not by copying code from LibreraReader.

## License

TBD - Will be determined after initial implementation.

## Contributing

Project is under active development. Contributions are welcome, but larger changes should align with the active roadmap and supporting documentation.

## Contact

For questions or suggestions, please open an issue.

---
*Last Updated: 2026-06-05*
