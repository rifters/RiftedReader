# RiftedReader
A modern ebook reader for Android, inspired by LibreraReader

## Current Project Status

Current status: the reader, parser, bookmark, TOC, Calibre, and settings flows are implemented. FlexPaginator and vertical scroll mode remain feature-flagged or settings-driven.

## Dependencies

- Kotlin
- Coroutines / Flow
- Room
- AndroidX Navigation, Lifecycle, Preference, DataStore, WebKit, Security, and Media
- Material Design 3
- jsoup, Retrofit, OkHttp, Coil, Zip4j, AndroidPdfViewer
- No Hilt dependency is declared

## What is implemented

- FlexPaginator pipeline (feature-flagged, default OFF)
- Conveyor belt system (5-slot, phase-managed)
- Shared typography config (FlexSlicingConfig)
- Bookmark system (Room-backed, charOffset-stable)
- TOC anchor navigation (HeadingAnchorSlugger + jumpToAnchor)
- Vertical scroll mode (ReaderMode.PAGINATED / SCROLL)
- Re-slicing on typography changes (PaginationModeGuard-wrapped)
- Calibre Content Server integration (browse + download)
- Calibre-Web in-app browser (WebView + download intercept)
- Loading overlay, TOC panel, bookmark list, reader settings UI
- Core parsers for TXT, EPUB, PDF, HTML, FB2, and MOBI

## Known issues

- FlexPaginator is OFF by default; enable it in Reader Settings > Layout
- PreviewParser returns placeholder previews for remaining roadmap formats
- JS tests require `cd tests/js && npm install` before the first run
- `MANAGE_EXTERNAL_STORAGE` permission is being replaced
- Download notifications intentionally swallow `SecurityException` until Android 13+
  notification permission flows are fully wired
- Download notification IDs currently use `url.hashCode()`, which is acceptable for
  today's single-download flow but should be revisited before parallel download UX is added

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

6. **[HTML_DEBUG_LOGGING.md](docs/complete/HTML_DEBUG_LOGGING.md)** - HTML Debug Logging Guide
   - Automatic HTML logging for pagination debugging
   - Log file formats and locations
   - Usage guide for developers
   - Troubleshooting tips

7. **[PAGINATOR_API.md](docs/complete/PAGINATOR_API.md)** - JavaScript Paginator API
   - In-page pagination JavaScript API documentation
   - Window vs. Chapter mode explanation
   - Configuration and integration guide

8. **[STABLE_WINDOW_MODEL.md](docs/complete/STABLE_WINDOW_MODEL.md)** - Stable Window Reading Model  **⭐ NEW**
   - Immutable window architecture for predictable reading
   - 3-window memory management strategy
   - Background preloading and atomic transitions
   - Position mapping and boundary handling

9. **[JS_STREAMING_DISCIPLINE.md](docs/complete/JS_STREAMING_DISCIPLINE.md)** - JavaScript Streaming Discipline  **⭐ NEW**
   - Construction vs. Active mode enforcement
   - Streaming operation restrictions
   - Android ↔ JavaScript integration discipline
   - Testing and error prevention

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

## Carried review follow-ups

- `BookDownloadManager.notifIdFor(url)` currently uses `url.hashCode()`; follow up with a
  collision-resistant strategy before parallel downloads are surfaced in the UI
- `DownloadNotificationHelper` intentionally wraps `NotificationManagerCompat.notify(...)`
  in `runCatching` so Android 13+ devices without `POST_NOTIFICATIONS` do not crash
- `ReaderViewModel.Factory` keeps the optional `BookmarkManager` construction fallback to
  match the repository's current manual-wiring pattern
- The `"Unknown error"` fallback used for download failures should eventually move to a
  string resource for localization consistency

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
- **DI**: manual wiring
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
*Last Updated: 2026-06-04*