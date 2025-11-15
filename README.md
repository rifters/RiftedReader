# RiftedReader
A modern ebook reader for Android, inspired by LibreraReader

## Project Status

✅ **Stage 1-3 Complete** - Android project structure, database layer, parsers, and basic UI implemented
🛠️ **Stage 6-8 Scaffolding** - Format catalogue, collections database, statistics utilities, and cloud/OPDS interfaces added

## Documentation

This project includes detailed planning and analysis documents:

### 📚 Core Documentation

1. **[LIBRERA_ANALYSIS.md](LIBRERA_ANALYSIS.md)** - Complete analysis of LibreraReader
   - Supported formats and parsing architecture
   - Database and library management
   - UI/UX patterns and design
   - File storage handling
   - Detailed TTS feature analysis
   - Dependencies and libraries
   - Implementation recommendations

2. **[TTS_IMPLEMENTATION_GUIDE.md](TTS_IMPLEMENTATION_GUIDE.md)** - TTS Implementation Guide
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
| HTML | ✅ Supported | Jsoup-based parser aligned with Librera's HtmlExtractor |
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

## Development Stages

### Stage 1: Foundation (Weeks 1-2) ✅ Complete
- [x] Project analysis and planning
- [x] Documentation creation
- [x] Project setup
- [x] Basic UI framework
- [x] Android project with Kotlin and Gradle
- [x] Material Design 3 theming
- [x] Navigation component setup

### Stage 2: File Management & Database (Weeks 3-4) ✅ Complete
- [x] Room database setup
- [x] BookMeta entity and DAO
- [x] Repository pattern implementation
- [x] File scanner for discovering books
- [x] Storage permission handling
- [x] Search and filter functionality

### Stage 3: Basic Parsing (Weeks 5-7) ✅ Complete
- [x] Parser interface design
- [x] TXT parser with encoding detection
- [x] EPUB parser with metadata extraction
- [x] PDF parser (minimal wrapper)
- [x] Reader UI with page navigation
- [x] Reading progress tracking
- [x] Gesture controls

### Stage 4: TTS Implementation (Weeks 11-14) ⭐ Next Priority
- [ ] Basic TTS engine
- [ ] Replacement system
- [ ] Background service
- [ ] TTS controls UI

### Stage 5: Enhanced Features (Weeks 15-20)
- [ ] Additional TTS polish
- [ ] Reader refinements

### Stage 6: Advanced Parsing (Weeks 15-16)
- [x] Introduced central format catalogue with status tracking
- [x] Preview parser scaffolding for MOBI, FB2, CBZ, CBR, RTF, DOCX
- [x] HTML parser implementation using Jsoup (parity with Librera's HtmlExtractor)
- [ ] Full content extraction for remaining new formats

### Stage 7: Library Features (Weeks 17-18)
- [x] Room entities & DAO for collections
- [x] Collection repository façade
- [x] Search filter use-case & statistics calculator
- [x] LibraryViewModel wired to filter state & collections flow
- [x] Persisted search filters & saved searches UI
- [x] Library statistics screen in Settings
- [x] Collections UI and smart collections
- [ ] Bulk metadata editing & cover management

### Stage 8: Cloud & Sync (Weeks 19-20)
- [x] Cloud storage provider abstraction & stub implementations
- [x] OPDS client data structures
- [ ] Google Drive / Dropbox integrations
- [ ] Progress sync & conflict resolution

See [IMPLEMENTATION_ROADMAP.md](IMPLEMENTATION_ROADMAP.md) for complete timeline.

## Inspiration

This project is inspired by [LibreraReader](https://github.com/foobnix/LibreraReader) (GPL v3), a mature and feature-rich ebook reader. RiftedReader aims to bring similar functionality with a modern codebase and architecture, with special focus on the excellent TTS replacement system that makes LibreraReader stand out.

**Note**: RiftedReader is being built from scratch based on feature analysis, not by copying code from LibreraReader.

## License

TBD - Will be determined after initial implementation

## Contributing

Project is in planning phase. Contributions will be welcome once development begins.

## Contact

For questions or suggestions, please open an issue.

---

## What Has Been Built (Stages 1-3)

### Project Structure
The Android project follows modern architecture patterns with clear separation of concerns:

```
app/src/main/java/com/rifters/riftedreader/
├── data/
│   ├── database/
│   │   ├── dao/
│   │   │   └── BookMetaDao.kt           # Database queries
│   │   ├── entities/
│   │   │   └── BookMeta.kt              # Book metadata entity
│   │   ├── BookDatabase.kt              # Room database
│   │   └── Converters.kt                # Type converters for Room
│   └── repository/
│       └── BookRepository.kt            # Data access layer
├── domain/
│   └── parser/
│       ├── BookParser.kt                # Parser interface
│       ├── TxtParser.kt                 # Plain text parser
│       ├── EpubParser.kt                # EPUB format parser
│       ├── PdfParser.kt                 # PDF format wrapper
│       └── ParserFactory.kt             # Parser selection
├── ui/
│   ├── MainActivity.kt                  # Main activity
│   ├── library/
│   │   ├── LibraryFragment.kt           # Library screen
│   │   ├── LibraryViewModel.kt          # Library logic
│   │   └── BooksAdapter.kt              # RecyclerView adapter
│   └── reader/
│       ├── ReaderActivity.kt            # Reading screen
│       └── ReaderViewModel.kt           # Reading logic
└── util/
    └── FileScanner.kt                   # File system scanner
```

### Key Features Implemented

#### 1. Database Layer (Stage 2)
- **Room Database** with BookMeta entity
- Comprehensive metadata storage: title, author, format, size, pages, progress
- DAO with queries for search, filtering, favorites
- Type converters for complex types (lists)
- Repository pattern for clean data access

#### 2. File Parsing (Stage 3)
- **TXT Parser**: Handles plain text with encoding detection (UTF-8, ISO-8859-1)
- **EPUB Parser**: Extracts metadata, spine, table of contents using JSoup
- **PDF Parser**: Minimal implementation (delegates to PDF viewer library)
- **Parser Factory**: Automatic format detection and parser selection

#### 3. User Interface (Stages 1 & 2)
- **Material Design 3** theming with light/dark mode support
- **Library Screen**: 
  - RecyclerView with book cards
  - Cover images, progress indicators
  - Search functionality
  - FAB for scanning books
- **Reader Screen**:
  - Scrollable text view
  - Page navigation (previous/next)
  - Progress slider
  - Tap-to-show controls overlay
  - Gesture detection
- **Navigation Component** setup

#### 4. File Management (Stage 2)
- **FileScanner** utility for discovering books
- Scans default directories (Books, Downloads, Documents)
- Automatic metadata extraction on scan
- Progress reporting during scan
- Incremental updates (doesn't re-scan existing books)

#### 5. Permissions
- Storage permission handling for Android 10+
- Permission request dialogs with rationale
- Graceful fallback for denied permissions

### Building the Project

This is a standard Android Gradle project. To build:

```bash
./gradlew assembleDebug
```

To run tests:
```bash
./gradlew test
```

### Dependencies Used

- **AndroidX Core & AppCompat**: Modern Android components
- **Material Design 3**: UI components
- **Room**: Local database
- **Navigation Component**: Screen navigation
- **Kotlin Coroutines**: Asynchronous operations
- **Coil**: Image loading
- **JSoup**: HTML/XML parsing (for EPUB)
- **Zip4j**: ZIP file handling
- **Android PDF Viewer**: PDF rendering
- **Gson**: JSON serialization for Room converters

---

**Last Updated**: November 14, 2025
