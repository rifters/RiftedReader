# RiftedReader
A modern ebook reader for Android, inspired by LibreraReader.

## Current Project Status

RiftedReader is in active development with the core reading stack already implemented. Stages 1, 2, 3, 5, 6, and 7 are complete; Stage 4 is partial; Stage 8 (Cloud & Sync) is scaffolded, with implementation gated on planning decisions documented in [docs/stage8-scaffolding.md](docs/stage8-scaffolding.md).

- ✅ Project foundation, database, library management, and core architecture
- ✅ TXT, EPUB, PDF, MOBI/AZW, AZW3/KF8, FB2, CBZ/CBR, RTF, DOCX, and HTML support
- ✅ FlexPaginator is the default pagination engine (Settings toggle; classic pagination retained as an option)
- ✅ Advanced TTS system with replacement rules, background service, notification controls, and sleep timer
- ✅ Fully configurable tap zones (3×3 grid, per-zone action mapping)
- ✅ Collections, saved searches, cover management, statistics, bookmarks, and reading progress
- ⚠️ Reader polish features like pinch-to-zoom, notes/highlights, animations, and two-page tablet mode are still pending
- 🧱 Cloud sync and OPDS/WebDAV: scaffolding packages in place, providers not yet implemented

See [docs/ROADMAP.md](docs/ROADMAP.md) for the full implementation roadmap and project status, and [docs/BACKLOG.md](docs/BACKLOG.md) for designed-but-unbuilt features with their trade-offs.

## Dependencies

- Kotlin
- Coroutines / Flow
- Room
- Manual ViewModel/repository wiring (no DI framework yet)
- AndroidX Navigation, Lifecycle, Preference, DataStore, WebKit, Security, and Media
- Material Design 3
- jsoup, Retrofit, OkHttp, Coil, Zip4j, and PDF rendering dependencies (DOCX is handled by an in-repo native parser — no Apache POI)

## What is implemented

- Library scanning and metadata indexing
- Collections, saved searches, favorites, and statistics
- Bookmark system and reading progress persistence
- Reader UI with page and scroll modes
- FlexPaginator pagination (default) on a fixed 5-slot conveyor-belt windowing system, designed to stay fast on older devices (minSdk 24 is a deliberate floor)
- Typography customization, themes, and overlay controls
- Fully configurable tap zones with per-zone action mapping
- TTS engine, replacement rules, TTS settings, and foreground playback service
- ICU BreakIterator sentence splitting for natural TTS phrasing (abbreviations, decimals, citations)
- TTS engine abstraction with a settings-level engine choice (system engine today; on-device neural engines planned)
- Core parsers for TXT, EPUB, PDF, MOBI/AZW, AZW3/KF8, FB2, CBZ/CBR, RTF, DOCX, and HTML
- Native DOCX parser with footnote/endnote support, plus a "Read footnotes" TTS setting (on by default)
- Calibre-related browsing and download flows present in the app

## Known issues / remaining work

- Pinch-to-zoom is not implemented yet
- Notes / highlights should still be treated as pending
- Calibre tabs are hidden until Calibre is enabled in Reader Settings, which makes the feature hard to discover for new users (fix planned: always-visible tabs with a setup state)
- Cloud sync, OPDS, and provider integrations require the planning decisions in docs/stage8-scaffolding.md to be settled before implementation
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
7. **[docs/history/](docs/history/)** - Archived snapshots of how the project got here. Preserved for the record; **not** current status — this README and ROADMAP.md are.

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
- FB2, RTF, DOCX (with footnotes/endnotes)
- CBZ, CBR
- HTML

### Text-to-Speech ⭐ Core Feature
- Advanced TTS with Android TextToSpeech API
- Replacement rules with simple, regex, and command types
- Special commands: SKIP, STOP, NEXT, PAUSE
- `@Voice` rule import compatibility
- Background playback service with notification and media controls
- Customizable speed and pitch
- Auto-scroll during playback
- Sleep timer
- ICU sentence splitting that respects abbreviations, decimals, and citations
- Optional footnote/endnote reading (TTS setting, on by default)

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
- FlexPaginator paged reading (default) or continuous scroll mode
- Swipe and volume-button navigation
- Configurable tap zones
- Chapter navigation and bookmarks
- Overlay controls with auto-hide

### Planned features snapshot
- Pinch-to-zoom
- Notes and highlights
- Backup and restore
- Footnote popups for EPUB
- Word-highlight TTS / karaoke mode
- On-device neural TTS voices (engine scaffolding in place)
- Calibre tab discoverability fix
- Stage 8 providers (sync / OPDS), pending planning decisions

### Modern Architecture
- MVVM + Repository pattern
- Explicit ViewModel factories and constructor wiring
- Room Database
- Coroutines + Flow
- Material Design 3

## Technology Stack

- **Language**: Kotlin
- **Minimum SDK**: 24 (Android 7.0)
- **Target SDK**: 35 (Android 15)
- **Architecture**: MVVM with Repository pattern
- **Database**: Room
- **DI**: Manual dependency wiring (factory-based)
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
*Last Updated: 2026-11-03*
