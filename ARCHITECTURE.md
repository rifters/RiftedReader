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
│  │  Repository  │  │   Manager    │  │    Engine    │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
│                                                               │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │  Collection  │  │   Reading    │  │ Replacement  │      │
│  │   Manager    │  │   Session    │  │    Engine    │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
└─────────┬───────────────────┬───────────────────┬───────────┘
          │                   │                   │
┌─────────▼───────────────────▼───────────────────▼───────────┐
│                         Data Layer                           │
│                                                               │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │     Room     │  │    Parsers   │  │    Prefs     │      │
│  │   Database   │  │ (TXT, EPUB,  │  │  Storage     │      │
│  │              │  │  PDF, MOBI)  │  │              │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
│                                                               │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │     File     │  │    Cache     │  │    Cloud     │      │
│  │   Scanner    │  │   Manager    │  │    Storage   │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
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
│  Manager    │
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
│   Reader    │  9. Display content
│  Activity   │
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
│                    Parser Interface                     │
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
    │ Encoding │     │   ZIP    │    │  MuPDF   │
    │ Detector │     │ Extractor│    │  Library │
    └──────────┘     └──────────┘    └──────────┘
                           │
                           ▼
                     ┌──────────┐
                     │   HTML   │
                     │  Parser  │
                     └──────────┘

Interface methods:
- canParse(path: String): Boolean
- extractMetadata(path: String): BookMeta
- extractContent(path: String): BookContent
- extractChapter(path: String, index: Int): String
- getTableOfContents(path: String): List<Chapter>
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
│   │   │   │   ├── data/
│   │   │   │   │   ├── database/
│   │   │   │   │   │   ├── BookDatabase.kt
│   │   │   │   │   │   ├── dao/
│   │   │   │   │   │   │   ├── BookMetaDao.kt
│   │   │   │   │   │   │   ├── BookmarkDao.kt
│   │   │   │   │   │   │   └── CollectionDao.kt
│   │   │   │   │   │   └── entity/
│   │   │   │   │   │       ├── BookMeta.kt
│   │   │   │   │   │       ├── Bookmark.kt
│   │   │   │   │   │       └── Collection.kt
│   │   │   │   │   ├── repository/
│   │   │   │   │   │   ├── BookRepository.kt
│   │   │   │   │   │   └── CollectionRepository.kt
│   │   │   │   │   └── preferences/
│   │   │   │   │       ├── AppPreferences.kt
│   │   │   │   │       └── TTSPreferences.kt
│   │   │   │   ├── domain/
│   │   │   │   │   ├── model/
│   │   │   │   │   │   ├── BookContent.kt
│   │   │   │   │   │   └── Chapter.kt
│   │   │   │   │   ├── parser/
│   │   │   │   │   │   ├── Parser.kt (interface)
│   │   │   │   │   │   ├── ParserManager.kt
│   │   │   │   │   │   ├── TxtParser.kt
│   │   │   │   │   │   ├── EpubParser.kt
│   │   │   │   │   │   ├── PdfParser.kt
│   │   │   │   │   │   └── MobiParser.kt
│   │   │   │   │   ├── tts/
│   │   │   │   │   │   ├── TTSEngine.kt
│   │   │   │   │   │   ├── TTSReplacementEngine.kt
│   │   │   │   │   │   ├── TTSReplacementRule.kt
│   │   │   │   │   │   ├── TTSService.kt
│   │   │   │   │   │   ├── TTSStateManager.kt
│   │   │   │   │   │   └── TTSNotification.kt
│   │   │   │   │   └── usecase/
│   │   │   │   │       ├── ScanLibraryUseCase.kt
│   │   │   │   │       └── ExtractMetadataUseCase.kt
│   │   │   │   ├── ui/
│   │   │   │   │   ├── library/
│   │   │   │   │   │   ├── LibraryFragment.kt
│   │   │   │   │   │   ├── LibraryViewModel.kt
│   │   │   │   │   │   ├── BookAdapter.kt
│   │   │   │   │   │   └── BookItemView.kt
│   │   │   │   │   ├── reader/
│   │   │   │   │   │   ├── ReaderActivity.kt
│   │   │   │   │   │   ├── ReaderViewModel.kt
│   │   │   │   │   │   ├── PageView.kt
│   │   │   │   │   │   └── ControlsOverlay.kt
│   │   │   │   │   ├── tts/
│   │   │   │   │   │   ├── TTSControlsFragment.kt
│   │   │   │   │   │   ├── TTSSettingsFragment.kt
│   │   │   │   │   │   └── ReplacementRulesEditor.kt
│   │   │   │   │   ├── settings/
│   │   │   │   │   │   └── SettingsFragment.kt
│   │   │   │   │   └── common/
│   │   │   │   │       ├── BaseActivity.kt
│   │   │   │   │       ├── BaseFragment.kt
│   │   │   │   │       └── BaseViewModel.kt
│   │   │   │   └── util/
│   │   │   │       ├── Extensions.kt
│   │   │   │       ├── Constants.kt
│   │   │   │       └── FileUtils.kt
│   │   │   ├── res/
│   │   │   │   ├── layout/
│   │   │   │   ├── values/
│   │   │   │   ├── drawable/
│   │   │   │   └── navigation/
│   │   │   └── AndroidManifest.xml
│   │   └── test/
│   │       └── java/com/rifters/riftedreader/
│   │           ├── parser/
│   │           ├── tts/
│   │           └── repository/
│   └── build.gradle.kts
│
├── docs/
│   ├── LIBRERA_ANALYSIS.md
│   ├── TTS_IMPLEMENTATION_GUIDE.md
│   ├── UI_UX_DESIGN_GUIDE.md
│   ├── IMPLEMENTATION_ROADMAP.md
│   ├── QUICK_START.md
│   └── ARCHITECTURE.md (this file)
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
    │   └─► Text Settings (Bottom Sheet)
    │
    └─► SettingsFragment
        │
        ├─► Reading Settings
        ├─► TTS Settings
        ├─► Library Settings
        ├─► Appearance Settings
        └─► About
```

---

## Dependency Graph

```
┌────────────────────────────────────────────────────────┐
│                   External Libraries                    │
└────────────────────────────────────────────────────────┘

Android Jetpack
├─► Core KTX
├─► AppCompat
├─► Fragment KTX
├─► Lifecycle (ViewModel, LiveData)
├─► Navigation
├─► Room (Runtime, KTX, Compiler)
└─► WorkManager

Dependency Injection
└─► Hilt (Android, Compiler)

Reactive
└─► Coroutines (Core, Android)

UI
├─► Material Components
├─► ConstraintLayout
├─► RecyclerView
└─► ViewPager2

Image Loading
└─► Coil

Parsing Libraries
├─► PdfBox / MuPDF (PDF)
├─► Epublib (EPUB)
├─► Jsoup (HTML)
└─► Apache POI (DOCX) [optional]

Testing
├─► JUnit
├─► MockK
├─► Espresso
└─► Robolectric [optional]
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
