> **Note:** This is the canonical architecture document for RiftedReader.  
> Last updated: 2025‑11‑21
> 
# RiftedReader Architecture Diagram

This document provides visual representations of the system architecture.

---

## System Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                       Presentation Layer                     │
│                                                               │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │   Library    │  │    Reader    │  │     TTS      │      │
│  │   Fragment   │  │   Activity   │  │   Controls   │      │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘      │
│         │                  │                  │               │
│  ┌──────▼───────┐  ┌──────▼───────┐  ┌──────▼───────┐      │
│  │   Library    │  │    Reader    │  │     TTS      │      │
│  │  ViewModel   │  │  ViewModel   │  │  ViewModel   │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
└─────────┬───────────────────┬───────────────────┬───────────┘
          │                   │                   │
┌─────────▼───────────────────▼───────────────────▼───────────┐
│                        Domain Layer                          │
│                                                               │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │    Book      │  │    Parser    │  │     TTS      │      │
│  │  Repository  │  │   Factory    │  │    Engine    │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
│                                                               │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │  Collection  │  │  Continuous  │  │ Replacement  │      │
│  │   Manager    │  │  Paginator   │  │    Engine    │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
│                                                               │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │  Bookmark    │  │   Library    │  │   Stable     │      │
│  │   Manager    │  │   Search     │  │   Window     │      │
│  │              │  │              │  │   Manager    │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
│                                                               │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │   WebView    │  │  Sliding     │  │   Window     │      │
│  │  Paginator   │  │   Window     │  │   HTML       │      │
│  │              │  │  Manager     │  │  Provider    │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
└─────────┬───────────────────┬───────────────────┬───────────┘
          │                   │                   │
┌─────────▼───────────────────▼───────────────────▼───────────┐
│                         Data Layer                           │
│                                                               │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │     Room     │  │    Parsers   │  │    Prefs     │      │
│  │   Database   │  │ (TXT, EPUB,  │  │  Storage     │      │
│  │              │  │     PDF)     │  │              │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
│                                                               │
│  ┌──────────────┐  ┌──────────────┐                         │
│  │     File     │  │     TTS      │                         │
│  │   Scanner    │  │ Replacement  │                         │
│  └──────────────┘  └──────────────┘                         │
└───────────────────────────────────────────────────────────────┘
```

---

## Data Flow: Opening a Book

```
User taps book
      │
      ▼
┌─────────────┐
│  Library    │  1. User selects book
│  Fragment   │
└─────┬───────┘
      │ onBookClick(bookId)
      ▼
┌─────────────┐
│  Library    │  2. Emit navigation event
│  ViewModel  │
└─────┬───────┘
      │ navigateToReader(bookId)
      ▼
┌─────────────┐
│   Reader    │  3. Launch reader
│  Activity   │
└─────┬───────┘
      │ onCreate()
      ▼
┌─────────────┐
│   Reader    │  4. Load book data
│  ViewModel  │
└─────┬───────┘
      │ loadBook(bookId)
      ▼
┌─────────────┐
│    Book     │  5. Get book metadata
│ Repository  │
└─────┬───────┘
      │ getBookById()
      ▼
┌─────────────┐
│    Room     │  6. Query database
│  Database   │
└─────┬───────┘
      │ BookMeta
      ▼
┌─────────────┐
│   Parser    │  7. Parse book content
│  Factory    │
└─────┬───────┘
      │ selectParser(format)
      ▼
┌─────────────┐
│   Format    │  8. Extract content
│   Parser    │     (TXT/EPUB/PDF)
└─────┬───────┘
      │ BookContent
      ▼
┌─────────────┐
│   Reader    │  9. Display content with ViewPager2
│  Activity   │     or Continuous Pagination
└─────────────┘
```

---

## TTS System Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                      TTS Components                          │
└─────────────────────────────────────────────────────────────┘

┌──────────────┐       ┌──────────────┐       ┌──────────────┐
│  TTS         │       │ Replacement  │       │   TTS        │
│  Controls    │◄─────►│   Engine     │◄─────►│  Service     │
│  View        │       │              │       │ (Background) │
└──────┬───────┘       └──────┬───────┘       └──────┬───────┘
       │                      │                       │
       │                      │                       │
       │ Commands             │ Process               │ Speak
       │ (Play/Pause)         │ Text                  │
       │                      │                       │
       ▼                      ▼                       ▼
┌──────────────┐       ┌──────────────┐       ┌──────────────┐
│   State      │       │    Rules     │       │   Android    │
│  Manager     │       │   Storage    │       │     TTS      │
└──────────────┘       └──────────────┘       └──────────────┘

                       Notification
                       ┌────────────┐
                       │  ◀  ⏸  ▶  │
                       │   Controls │
                       └────────────┘
```

### TTS Processing Pipeline

```
Book Content
     │
     ▼
┌─────────────────┐
│ Text Extractor  │  Extract text from current page/chapter
└────────┬────────┘
         │ Raw text
         ▼
┌─────────────────┐
│    Sentence     │  Split into sentences
│    Splitter     │
└────────┬────────┘
         │ List<String>
         ▼
┌─────────────────┐
│  Replacement    │  For each sentence:
│    Engine       │  1. Apply simple replacements
└────────┬────────┘  2. Apply regex replacements
         │           3. Check for commands
         │ Processed sentence + Command?
         ▼
┌─────────────────┐
│  Command        │  Handle command:
│  Processor      │  - SKIP: Next sentence
└────────┬────────┘  - STOP: End reading
         │           - NEXT: Next page
         │           - PAUSE: Add pause
         │ Final text
         ▼
┌─────────────────┐
│   TTS Engine    │  Speak the text
└────────┬────────┘
         │ onUtteranceCompleted
         ▼
    Next sentence
```

---

## Database Schema

```
┌─────────────────────────────────────────────────────────┐
│                        books                             │
├─────────────────────────────────────────────────────────┤
│  id              : String (UUID, Primary Key)           │
│  path            : String (File path)                   │
│  title           : String                               │
│  author          : String?                              │
│  format          : String (PDF, EPUB, etc.)            │
│  size            : Long (bytes)                         │
│  dateAdded       : Long (timestamp)                     │
│  lastOpened      : Long (timestamp)                     │
│  currentPage     : Int                                  │
│  totalPages      : Int                                  │
│  currentChapterIndex  : Int (for continuous mode)      │
│  currentInPageIndex   : Int (for continuous mode)      │
│  currentCharacterOffset : Int (for continuous mode)    │
│  coverPath       : String? (Thumbnail path)            │
│  isFavorite      : Boolean                              │
│  tags            : List<String> (JSON)                  │
│  series          : String?                              │
│  seriesIndex     : Int?                                 │
│  language        : String?                              │
│  publisher       : String?                              │
│  yearPublished   : Int?                                 │
│  isbn            : String?                              │
│  rating          : Float?                               │
└─────────────────────────────────────────────────────────┘
              │
              │ 1:N
              ▼
┌─────────────────────────────────────────────────────────┐
│                     bookmarks                            │
├─────────────────────────────────────────────────────────┤
│  id              : Int (Primary Key, Auto)              │
│  bookId          : String (Foreign Key)                 │
│  pageNumber      : Int                                  │
│  position        : Int (Character offset)               │
│  note            : String?                              │
│  dateCreated     : Long (timestamp)                     │
└─────────────────────────────────────────────────────────┘

              │ 1:N
              ▼
┌─────────────────────────────────────────────────────────┐
│                    highlights                            │
├─────────────────────────────────────────────────────────┤
│  id              : Int (Primary Key, Auto)              │
│  bookId          : String (Foreign Key)                 │
│  pageNumber      : Int                                  │
│  startPosition   : Int                                  │
│  endPosition     : Int                                  │
│  highlightedText : String                               │
│  color           : Int                                  │
│  note            : String?                              │
│  dateCreated     : Long (timestamp)                     │
└─────────────────────────────────────────────────────────┘

              │ N:N
              ▼
┌─────────────────────────────────────────────────────────┐
│                   collections                            │
├─────────────────────────────────────────────────────────┤
│  id              : Int (Primary Key, Auto)              │
│  name            : String                               │
│  description     : String?                              │
│  coverPath       : String?                              │
│  dateCreated     : Long (timestamp)                     │
└─────────────────────────────────────────────────────────┘
              │
              │ N:N
              ▼
┌─────────────────────────────────────────────────────────┐
│              book_collection_join                        │
├─────────────────────────────────────────────────────────┤
│  bookId          : String (Foreign Key)                 │
│  collectionId    : Int (Foreign Key)                    │
│  PRIMARY KEY (bookId, collectionId)                     │
└─────────────────────────────────────────────────────────┘
```

---

## Parser Architecture

```
┌────────────────────────────────────────────────────────┐
│                    BookParser Interface                 │
└────────────────────────────────────────────────────────┘
                           │
                           │ implements
          ┌────────────────┼────────────────┐
          │                │                │
          ▼                ▼                ▼
    ┌──────────┐     ┌──────────┐    ┌──────────┐
    │   TXT    │     │   EPUB   │    │   PDF    │
    │  Parser  │     │  Parser  │    │  Parser  │
    └──────────┘     └──────────┘    └──────────┘
          │                │                │
          ▼                ▼                ▼
    ┌──────────┐     ┌──────────┐    ┌──────────┐
    │ Encoding │     │   ZIP    │    │ Android  │
    │ Detector │     │(Zip4j)   │    │   PDF    │
    └──────────┘     └──────────┘    │  Viewer  │
                           │          └──────────┘
                           ▼
                     ┌──────────┐
                     │   HTML   │
                     │ (JSoup)  │
                     └──────────┘

          ┌────────────────┐
          │  ParserFactory │  Format detection
          └────────────────┘  and parser selection

          ┌────────────────┐
          │ FormatCatalog  │  Supported formats
          └────────────────┘  registry

Interface methods:
- parseMetadata(file: File): BookMeta
- parseChapters(file: File): List<Chapter>
- parseChapterContent(file: File, index: Int): String
```

---

## File Structure

```
RiftedReader/
│
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/rifters/riftedreader/
│   │   │   │   ├── RiftedReaderApplication.kt
│   │   │   │   ├── data/
│   │   │   │   │   ├── database/
│   │   │   │   │   │   ├── BookDatabase.kt
│   │   │   │   │   │   ├── Converters.kt
│   │   │   │   │   │   ├── dao/
│   │   │   │   │   │   │   ├── BookMetaDao.kt
│   │   │   │   │   │   │   ├── BookmarkDao.kt
│   │   │   │   │   │   │   └── CollectionDao.kt
│   │   │   │   │   │   └── entities/
│   │   │   │   │   │       ├── BookMeta.kt
│   │   │   │   │   │       ├── Bookmark.kt
│   │   │   │   │   │       └── CollectionEntity.kt
│   │   │   │   │   ├── preferences/
│   │   │   │   │   │   ├── LibraryPreferences.kt
│   │   │   │   │   │   ├── ReaderPreferences.kt
│   │   │   │   │   │   └── TTSPreferences.kt
│   │   │   │   │   └── repository/
│   │   │   │   │       ├── BookRepository.kt
│   │   │   │   │       ├── BookmarkRepository.kt
│   │   │   │   │       ├── CollectionRepository.kt
│   │   │   │   │       └── TTSReplacementRepository.kt
│   │   │   │   ├── domain/
│   │   │   │   │   ├── bookmark/
│   │   │   │   │   │   └── BookmarkManager.kt
│   │   │   │   │   ├── library/
│   │   │   │   │   │   ├── BookMetadataUpdate.kt
│   │   │   │   │   │   ├── LibrarySearch.kt
│   │   │   │   │   │   ├── LibraryStatistics.kt
│   │   │   │   │   │   ├── SavedLibrarySearch.kt
│   │   │   │   │   │   └── SmartCollections.kt
│   │   │   │   │   ├── pagination/
│   │   │   │   │   │   ├── ContinuousPaginator.kt
│   │   │   │   │   │   └── PaginationMode.kt
│   │   │   │   │   ├── parser/
│   │   │   │   │   │   ├── BookParser.kt
│   │   │   │   │   │   ├── EpubParser.kt
│   │   │   │   │   │   ├── FormatCatalog.kt
│   │   │   │   │   │   ├── HtmlParser.kt
│   │   │   │   │   │   ├── ParserFactory.kt
│   │   │   │   │   │   ├── PdfParser.kt
│   │   │   │   │   │   ├── PreviewParser.kt
│   │   │   │   │   │   └── TxtParser.kt
│   │   │   │   │   └── tts/
│   │   │   │   │       ├── TTSEngine.kt
│   │   │   │   │       ├── TTSReplacementEngine.kt
│   │   │   │   │       ├── TTSReplacementParser.kt
│   │   │   │   │       ├── TTSReplacementRule.kt
│   │   │   │   │       ├── TTSService.kt
│   │   │   │   │       ├── TTSState.kt
│   │   │   │   │       └── TTSStatusNotifier.kt
│   │   │   │   ├── ui/
│   │   │   │   │   ├── MainActivity.kt
│   │   │   │   │   ├── library/
│   │   │   │   │   │   ├── BooksAdapter.kt
│   │   │   │   │   │   ├── LibraryFragment.kt
│   │   │   │   │   │   ├── LibrarySelection.kt
│   │   │   │   │   │   └── LibraryViewModel.kt
│   │   │   │   │   ├── reader/
│   │   │   │   │   │   ├── ChaptersBottomSheet.kt
│   │   │   │   │   │   ├── DebugWebView.kt
│   │   │   │   │   │   ├── ReaderActivity.kt
│   │   │   │   │   │   ├── ReaderControlsManager.kt
│   │   │   │   │   │   ├── ReaderPageFragment.kt
│   │   │   │   │   │   ├── ReaderPagerAdapter.kt
│   │   │   │   │   │   ├── ReaderPreferencesOwner.kt
│   │   │   │   │   │   ├── ReaderSettingsViewModel.kt
│   │   │   │   │   │   ├── ReaderTapAction.kt
│   │   │   │   │   │   ├── ReaderTapZone.kt
│   │   │   │   │   │   ├── ReaderTapZonesBottomSheet.kt
│   │   │   │   │   │   ├── ReaderTextSettingsBottomSheet.kt
│   │   │   │   │   │   ├── ReaderThemePalette.kt
│   │   │   │   │   │   ├── ReaderViewModel.kt
│   │   │   │   │   │   └── WebViewPaginatorBridge.kt
│   │   │   │   │   ├── settings/
│   │   │   │   │   │   ├── LibraryStatisticsFragment.kt
│   │   │   │   │   │   ├── ReaderSettingsFragment.kt
│   │   │   │   │   │   ├── SettingsFragment.kt
│   │   │   │   │   │   ├── TTSReplacementsAdapter.kt
│   │   │   │   │   │   ├── TTSReplacementsFragment.kt
│   │   │   │   │   │   ├── TTSReplacementsViewModel.kt
│   │   │   │   │   │   └── TTSSettingsFragment.kt
│   │   │   │   │   └── tts/
│   │   │   │   │       └── TTSControlsBottomSheet.kt
│   │   │   │   └── util/
│   │   │   │       ├── AppLogger.kt
│   │   │   │       ├── BookmarkPreviewExtractor.kt
│   │   │   │       ├── FileScanner.kt
│   │   │   │       ├── FlowExtensions.kt
│   │   │   │       └── LoggerConfig.kt
│   │   │   ├── res/
│   │   │   │   ├── layout/
│   │   │   │   ├── values/
│   │   │   │   ├── drawable/
│   │   │   │   ├── navigation/
│   │   │   │   └── xml/
│   │   │   └── AndroidManifest.xml
│   │   └── test/
│   │       └── java/com/rifters/riftedreader/
│   │           ├── ContinuousPaginatorTest.kt
│   │           ├── BookmarkRestorationTest.kt
│   │           ├── TTSReplacementRepositoryTest.kt
│   │           └── ... (other tests)
│   └── build.gradle.kts
│
├── docs/
│   ├── complete/
│   │   ├── ARCHITECTURE.md (this file)
│   │   ├── DEVELOPMENT_SETUP.md
│   │   ├── LIBRERA_ANALYSIS.md
│   │   ├── TTS_IMPLEMENTATION_GUIDE.md
│   │   └── UI_UX_DESIGN_GUIDE.md
│   ├── planning/
│   │   ├── EPUB_IMPROVEMENTS.md
│   │   ├── IMPLEMENTATION_ROADMAP.md
│   │   ├── STAGE_6_8_TODO.md
│   │   └── WEBVIEW_PAGINATOR_ENHANCEMENTS.md
│   ├── implemented/
│   │   ├── SLIDING_WINDOW_PAGINATION_STATUS.md
│   │   └── STAGE_1-3_SUMMARY.md
│   ├── testing/
│   │   ├── CONTINUOUS_PAGINATOR_VERIFICATION.md
│   │   ├── EPUB_COVER_DEBUG_GUIDE.md
│   │   └── SCROLL_GESTURE_TESTING_GUIDE.md
│   ├── historical/
│   │   └── SLIDING_WINDOW_PAGINATION.md
│   └── meta/
│       ├── DOCUMENTATION_RETRIEVED.md
│       └── PR_SUMMARY.md
│
├── .gitignore
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

---

## Navigation Flow

```
App Launch
    │
    ▼
MainActivity
    │
    ├─► LibraryFragment (default)
    │   │
    │   ├─► Book Details Bottom Sheet
    │   │   └─► Edit Metadata
    │   │
    │   ├─► Collections View
    │   │   └─► Collection Details
    │   │
    │   └─► Search Results
    │
    ├─► ReaderActivity
    │   │
    │   ├─► TTS Controls (Bottom Sheet)
    │   │   └─► TTS Settings
    │   │       └─► Replacement Rules Editor
    │   │
    │   ├─► Table of Contents (Bottom Sheet)
    │   │
    │   ├─► Bookmarks (Bottom Sheet)
    │   │
    │   ├─► Text Settings (Bottom Sheet)
    │   │
    │   └─► Tap Zones Configuration (Bottom Sheet)
    │
    └─► SettingsFragment
        │
        ├─► Reader Settings
        ├─► TTS Settings
        ├─► Library Settings
        ├─► Library Statistics
        └─► About
```

---

## Dependency Graph

```
┌────────────────────────────────────────────────────────┐
│                   External Libraries                    │
└────────────────────────────────────────────────────────┘

Android Jetpack
├─► Core KTX (1.12.0)
├─► AppCompat (1.6.1)
├─► Fragment KTX (implicit)
├─► Lifecycle (ViewModel, LiveData, Runtime) (2.7.0)
├─► Navigation (Fragment, UI) (2.7.6)
├─► Room (Runtime, KTX, Compiler) (2.6.1)
├─► Media (1.6.0)
├─► Preference KTX (1.2.1)
└─► ViewPager2 (1.0.0)

Reactive
└─► Coroutines (Core, Android) (1.7.3)

UI Components
├─► Material Components (1.11.0)
├─► ConstraintLayout (2.1.4)
└─► RecyclerView Selection (1.1.0)

Image Loading
└─► Coil (2.5.0)

Parsing Libraries
├─► Android PDF Viewer (3.2.0-beta.1)
├─► JSoup (1.17.2) - HTML/XML parsing for EPUB
└─► Zip4j (2.11.5) - ZIP handling for EPUB and CBZ

Utilities
└─► Gson (2.10.1) - JSON parsing for Room converters

Testing
├─► JUnit (4.13.2)
├─► Coroutines Test (1.7.3)
├─► AndroidX Test Extensions (1.1.5)
├─► Espresso Core (3.5.1)
└─► Room Testing (2.6.1)

Build Tools
├─► Android Gradle Plugin (8.5.2)
├─► Kotlin Plugin (1.9.24)
├─► KSP (1.9.24-1.0.20)
├─► Gradle (8.7)
└─► NDK (28.0.13004108) - for 16 KB page size support
```

---

## Native Library Requirements (16 KB Page Size)

Starting with Android 15 (API 35), devices may use 16 KB page sizes instead of the traditional
4 KB. All native libraries (.so files) must be built with 16 KB page alignment to ensure
compatibility.

### Current Native Libraries

The following native libraries are included via the `android-pdf-viewer` dependency:
- `libc++_shared.so` - C++ standard library
- `libjniPdfium.so` - JNI bindings for PDFium
- `libmodft2.so` - FreeType font library
- `libmodpdfium.so` - PDFium PDF rendering library
- `libmodpng.so` - PNG image library

### Configuration

The project is configured for 16 KB page size compatibility:

1. **NDK Version**: r28+ (28.0.13004108) - includes native 16 KB page support
2. **Target SDK**: 35 (Android 15+)
3. **Gradle Plugin**: 8.5.2+ - supports flexible page sizes
4. **CMake Arguments**: `-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON`
5. **JNI Libs Packaging**: `useLegacyPackaging = false`

### Building Native Code

When adding custom native code, use the following linker flags:

```cmake
# In CMakeLists.txt
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")
```

Or in `build.gradle.kts`:
```kotlin
externalNativeBuild {
    cmake {
        arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
    }
}
```

### Verification

To verify native library alignment, use:
```bash
readelf -l <library>.so | grep -A1 LOAD
```

LOAD segments should be aligned to 0x4000 (16 KB) offsets.

### Third-Party Libraries

Third-party libraries with native code must provide 16 KB compatible builds. When evaluating
new dependencies:
1. Check if the library uses native code
2. Verify the library is updated for Android 15+ compatibility
3. Test on 16 KB page size emulators/devices

**Reference**: https://developer.android.com/16kb-page-size

---

## State Management

```
┌────────────────────────────────────────────────────────┐
│                    State Classes                        │
└────────────────────────────────────────────────────────┘

sealed class LibraryState {
    object Loading : LibraryState()
    data class Success(val books: List<BookMeta>) : LibraryState()
    data class Error(val message: String) : LibraryState()
    object Empty : LibraryState()
}

sealed class ReaderState {
    object Loading : ReaderState()
    data class Success(val content: BookContent) : ReaderState()
    data class Error(val message: String) : ReaderState()
}

sealed class TTSState {
    object Idle : TTSState()
    object Playing : TTSState()
    object Paused : TTSState()
    data class Error(val message: String) : TTSState()
}

Flow:
ViewModel
    │
    ├─► StateFlow<LibraryState>
    │   └─► Observed by Fragment
    │
    ├─► StateFlow<ReaderState>
    │   └─► Observed by Activity
    │
    └─► StateFlow<TTSState>
        └─► Observed by TTS Controls
```

---

## Pagination System

The reader supports two pagination modes:

### 1. Chapter-Based Pagination (Default)
- Each chapter is displayed as a ViewPager2 page
- Simple navigation between chapters
- Chapter boundaries are explicit
- Original implementation

### 2. Continuous Pagination (Experimental)
Powered by the **Sliding Window Pagination Engine** (`ContinuousPaginator`):

```
┌─────────────────────────────────────────────────────────┐
│              Continuous Pagination Flow                  │
└─────────────────────────────────────────────────────────┘

Book with N chapters
      │
      ▼
┌──────────────────┐
│ ContinuousPaginator│  Manages sliding window of chapters
└────────┬─────────┘
         │ Maintains: Window of 5 chapters (configurable)
         │ Tracks: Global page indices across all chapters
         │
         ▼
┌──────────────────┐
│  ViewPager2 +    │  Presents global pages as seamless flow
│ ReaderPagerAdapter│  Chapter boundaries transparent to user
└────────┬─────────┘
         │
         ▼
┌──────────────────┐
│WebViewPaginator  │  In-page navigation within each chapter
│     Bridge       │  Coordinate with ContinuousPaginator
└──────────────────┘

Key Features:
- Entire book appears as continuous page array
- Only ~5 chapters loaded in memory at once
- Chapters dynamically loaded/unloaded as user navigates
- Precise bookmark restoration with character offsets
- Supports font size changes with position preservation
```

**Database Support:**
The BookMeta entity includes fields for continuous pagination:
- `currentChapterIndex` - Current chapter in the book
- `currentInPageIndex` - Page within current chapter
- `currentCharacterOffset` - Character position for precise restoration

See `SLIDING_WINDOW_PAGINATION.md` for detailed implementation guide.

---

## Chapter Indexing System

### The Problem: EPUB Spine vs TOC Mismatch

EPUB files contain a **spine** that lists all readable content items in order, and a **TOC** (table of contents) that lists user-visible chapters. These often differ:

```
Example EPUB Structure:
├── cover.xhtml          (spine only - not in TOC)
├── nav.xhtml            (provides TOC - not a chapter itself)
├── title.xhtml          (front matter - may not be in TOC)
├── chapter1.xhtml       ← User sees as Chapter 1
├── chapter2.xhtml       ← User sees as Chapter 2
├── ...
├── notes.xhtml          (linear="no" - appendix)
└── glossary.xhtml       (linear="no" - appendix)

Spine count: 109 items
TOC visible: 101 entries
Difference: 8 items (cover + nav + non-linear items)
```

This mismatch causes `WINDOW_COUNT_MISMATCH` errors when different parts of the code use different counts for window calculation:
- `ceil(109/5) = 22 windows` (spine-based)
- `ceil(101/5) = 21 windows` (TOC-based)

### The Solution: ChapterIndexProvider

The `ChapterIndexProvider` class maintains two lists:

1. **spineAll**: All spine items in reading order (for parser operations)
2. **visibleChapters**: Filtered subset for UI (excludes NAV, COVER, NON_LINEAR by default)

```kotlin
// Single source of truth for chapter counts
val provider = ChapterIndexProvider(chaptersPerWindow = 5)
provider.setChapters(parsedChapters)

// Use visible count for UI (progress, window count)
val windowCount = provider.getWindowCount()  // Uses visibleChapters

// Map between UI index and spine index
val spineIdx = provider.uiIndexToSpineIndex(userSelection)
val uiIdx = provider.spineIndexToUiIndex(parserResult)
```

### Chapter Type Classification

| Type | Description | Included by Default |
|------|-------------|---------------------|
| `CONTENT` | Main readable chapters | ✅ Yes |
| `FRONT_MATTER` | Title, copyright, dedication | ✅ Yes |
| `COVER` | Cover page/image | ❌ No |
| `NAV` | Navigation document | ❌ No |
| `NON_LINEAR` | Footnotes, glossary (linear="no") | ❌ No (configurable) |

### Window Calculation

The `WindowCalculator` provides pure, stateless window computation:

```kotlin
// Always use visible chapter count for UI windowing
val visibleCount = provider.visibleChapterCount
val windowCount = WindowCalculator.calculateWindowCount(visibleCount, chaptersPerWindow)

// Map chapter to window
val window = WindowCalculator.getWindowForChapter(chapterIndex, chaptersPerWindow)

// Get chapters in a window
val range = WindowCalculator.getWindowRange(windowIndex, visibleCount, chaptersPerWindow)
```

### Implementation Guidelines

1. **UI Layer**: Always use `visibleChapterCount` and `getWindowCount()`
2. **Parser Layer**: Use `spineAll` for content extraction
3. **Progress Display**: Use UI indices, convert with `spineIndexToUiIndex()`
4. **TTS Traversal**: Use visible chapters, skip NAV/COVER automatically
5. **Validation**: Use `WindowCalculator.validateWindowCount()` to detect mismatches

### Chapter Visibility Settings

Users can control which chapter types are visible through Settings → Reader → Chapter visibility:

| Setting | Description | Default |
|---------|-------------|---------|
| Include cover page | Show cover XHTML in reading sequence | ❌ Off |
| Include front matter | Show title, copyright, dedication | ✅ On |
| Include supplemental content | Show footnotes, endnotes, glossaries | ❌ Off |
| Reset to defaults | Restore default visibility | N/A |

**Note**: Navigation documents (NAV) are **always** excluded regardless of settings.

#### Dynamic Updates

Visibility changes take effect immediately while reading:

1. User toggles a setting in `ReaderSettingsFragment`
2. `ReaderPreferences.updateSettings()` updates the `ChapterVisibilitySettings`
3. `ReaderViewModel` observes the change via `distinctUntilChanged()` flow
4. `ChapterIndexProvider.updateVisibilitySettings()` rebuilds visible chapters
5. Window count is recomputed: `windowCount = ceil(visibleChapters / chaptersPerWindow)`
6. UI adapters are notified via `notifyDataSetChanged()`

```kotlin
// In ReaderViewModel
private fun observeVisibilitySettingsChanges() {
    viewModelScope.launch {
        readerPreferences.settings
            .map { it.chapterVisibility }
            .distinctUntilChanged()
            .collect { handleVisibilitySettingsChange(it) }
    }
}
```

#### Diagnostic Logging

Visibility changes emit diagnostic logs with the `[VISIBILITY]` prefix:

```
[VISIBILITY] Settings changed: cover=true, frontMatter=true, nonLinear=false
[VISIBILITY] Recomputing windows: spineCount=109, visibleChapters=103, windowCount=21
```

---

## Thread Management

```
Main Thread (UI)
    │
    └─► ViewModels
         │
         ├─► Coroutine Scope (viewModelScope)
         │   │
         │   ├─► IO Dispatcher
         │   │   ├─► Database operations
         │   │   ├─► File operations
         │   │   └─► Network operations
         │   │
         │   ├─► Default Dispatcher
         │   │   ├─► Parsing operations
         │   │   ├─► Text processing
         │   │   └─► Heavy computations
         │   │
         │   └─► Main Dispatcher
         │       └─► UI updates (via Flow/LiveData)
         │
         └─► Repository
             │
             └─► Coroutine Scope
                 ├─► suspend functions
                 └─► Flow emissions
```

---

## Stable Sliding Window Reading Model

The Stable Window Reading Model provides an immutable, predictable reading experience with efficient memory management. See [STABLE_WINDOW_MODEL.md](./STABLE_WINDOW_MODEL.md) for complete details.

### Key Components

```
┌────────────────────────────────────────────────────────┐
│                 StableWindowManager                    │
│                                                        │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐│
│  │ prevWindow   │  │activeWindow  │  │ nextWindow   ││
│  │ (WindowSnap) │  │ (WindowSnap) │  │ (WindowSnap) ││
│  │              │  │              │  │              ││
│  │ LOADED       │  │ ACTIVE       │  │ LOADED       ││
│  │ Ready        │  │ Immutable    │  │ Ready        ││
│  └──────────────┘  └──────────────┘  └──────────────┘│
│                                                        │
│  Progress: 78% ━━━━━━━━━━━━━━━━━━━━━━━━━━━━●━━━━━━  │
│             (Threshold: 75% - preload triggered)      │
└────────────────────────────────────────────────────────┘
```

### Core Principles

1. **Immutable Active Window**: The active window snapshot remains stable during reading - no append/prepend mutations
2. **Background Construction**: New windows (prev/next) are loaded in background using mutable `WindowState`
3. **Atomic Transitions**: Window switches happen atomically through explicit navigation calls
4. **3-Window Policy**: Maximum 3 windows in memory (prev, active, next)
5. **Progress-Based Preloading**: Adjacent windows preloaded at 75% threshold

### Window Lifecycle

```
Background Construction → Ready (Snapshot) → Active → Cached → Dropped
        (Mutable)            (Immutable)    (Reading)  (Memory)
```

### Data Model

```kotlin
// Immutable snapshot (active window)
data class WindowSnapshot(
    val windowIndex: WindowIndex,
    val firstChapterIndex: ChapterIndex,
    val lastChapterIndex: ChapterIndex,
    val chapters: List<WindowChapterData>,
    val totalPages: Int,
    val htmlContent: String?,
    val loadState: WindowLoadState
)

// Mutable state (background construction)
data class WindowState(
    val windowIndex: WindowIndex,
    var loadState: WindowLoadState,
    val chapters: MutableList<WindowChapterData>
)
```

### JavaScript Integration Discipline

The JavaScript paginator enforces strict construction vs. active mode discipline:

**Construction Mode** (Background):
- ✅ `appendChapter()` allowed
- ✅ `prependChapter()` allowed
- Building window content

**Active Mode** (Reading):
- ❌ `appendChapter()` forbidden (throws error)
- ❌ `prependChapter()` forbidden (throws error)
- ✅ `goToPage()` allowed (navigation only)
- ✅ `finalizeWindow()` to lock down content

See [JS_STREAMING_DISCIPLINE.md](./JS_STREAMING_DISCIPLINE.md) for complete details.

---

This architecture follows:
- ✅ **Clean Architecture** principles
- ✅ **SOLID** principles
- ✅ **MVVM** pattern
- ✅ **Repository** pattern
- ✅ **Single source of truth**
- ✅ **Unidirectional data flow**
- ✅ **Separation of concerns**

Each layer is independent and testable, making the codebase maintainable and scalable.
