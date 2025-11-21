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
│  │  Bookmark    │  │   Library    │  │   WebView    │      │
│  │   Manager    │  │   Search     │  │  Paginator   │      │
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
│   ├── ARCHITECTURE.md (this file)
│   ├── LIBRERA_ANALYSIS.md
│   ├── TTS_IMPLEMENTATION_GUIDE.md
│   ├── UI_UX_DESIGN_GUIDE.md
│   ├── IMPLEMENTATION_ROADMAP.md
│   ├── QUICK_START.md
│   ├── SLIDING_WINDOW_PAGINATION.md
│   └── STAGE_6_8_TODO.md
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
├─► Android Gradle Plugin (8.2.0)
├─► Kotlin Plugin (1.9.20)
└─► KSP (1.9.20-1.0.14)
```

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

This architecture follows:
- ✅ **Clean Architecture** principles
- ✅ **SOLID** principles
- ✅ **MVVM** pattern
- ✅ **Repository** pattern
- ✅ **Single source of truth**
- ✅ **Unidirectional data flow**
- ✅ **Separation of concerns**

Each layer is independent and testable, making the codebase maintainable and scalable.
