# LibreraReader Analysis and Feature Study

**Date**: November 13, 2025  
**Source Repository**: foobnix/LibreraReader  
**Target Repository**: rifters/RiftedReader

## Executive Summary

LibreraReader is a mature, feature-rich ebook reader for Android with extensive format support, advanced TTS capabilities, and comprehensive library management. This document provides a detailed analysis to guide the implementation of similar features in RiftedReader.

---

## 1. Supported Formats and Parsing Implementation

### 1.1 Supported Formats

LibreraReader supports the following formats:
- **PDF** - Portable Document Format
- **EPUB, EPUB3** - Electronic Publication (reflowable)
- **MOBI, AZW, AZW3** - Amazon Kindle formats
- **DjVu** - Scanned document format
- **FB2** - FictionBook 2.0 (XML-based)
- **TXT** - Plain text
- **RTF** - Rich Text Format
- **CBZ, CBR** - Comic Book Archive (ZIP/RAR)
- **DOC, DOCX** - Microsoft Word formats
- **HTML** - Web pages
- **ODT** - OpenDocument Text
- **OPDS** - Online catalogs

### 1.2 Parsing Architecture

**Location**: `app/src/main/java/com/foobnix/ext/`

LibreraReader uses a modular extraction architecture with separate extractor classes for each format:

#### Key Extractor Classes:

1. **EpubExtractor.java** (~33KB)
   - Handles EPUB/EPUB3 parsing
   - Extracts metadata, cover images, TOC
   - Handles CSS styles and fonts
   - Processes spine for reading order

2. **Fb2Extractor.java** (~47KB)
   - Largest extractor, handles FB2 format
   - XML-based parsing
   - Comprehensive metadata extraction
   - Supports images embedded in base64

3. **PdfExtract.java** (~2.8KB)
   - Wrapper around MuPDF library
   - Extracts text and metadata
   - Handles page-based navigation

4. **MobiExtract.java** (~3.2KB)
   - Uses libmobi library
   - Converts MOBI to HTML internally
   - Extracts metadata and cover

5. **CbzCbrExtractor.java** (~15.8KB)
   - Handles comic book archives
   - Extracts images from ZIP/RAR
   - Maintains page order

6. **DocxExtractor.java** (~3.7KB)
   - Uses Apache POI or mammoth library
   - Converts to HTML for rendering

7. **TxtExtract.java** (~5.6KB)
   - Handles encoding detection
   - Simple line-based parsing

8. **HtmlExtractor.java** (~8.3KB)
   - JSoup-based HTML parsing
   - Cleans and sanitizes HTML

9. **RtfExtract.java** (~9.5KB)
   - RTF to HTML conversion
   - Handles basic formatting

10. **DjvuExtract.java** (~2.2KB)
    - Native library wrapper
    - Page-based image extraction

### 1.3 Common Extraction Pattern

All extractors implement similar methods:
```java
// Pseudo-code pattern
class FormatExtractor {
    public static EbookMeta getBookMetaInformation(String path) {
        // Extract: title, author, series, year, publisher, ISBN
        // Extract: page count, cover image
        // Extract: language, genre, description
    }
    
    public static String getBookOverview(String path) {
        // First few pages for preview
    }
    
    public static String getBookText(String path) {
        // Full book text extraction
    }
}
```

### 1.4 Metadata Model

**EbookMeta.java** contains:
- Title, Author, Series, Sequence
- Publisher, Year, Language
- ISBN, Genre, Keywords
- File path, size, date
- Cover image path
- Page count, percent read
- Annotations and bookmarks

---

## 2. Library and Collections Management

### 2.1 Database Architecture

**Location**: `app/src/main/java/com/foobnix/dao2/`

LibreraReader uses **GreenDAO** ORM for database management:

#### Core Database Classes:

1. **FileMeta.java** (~8KB)
   - Primary entity for book metadata
   - Fields: id, path, title, author, size, date
   - Reading progress: percent, page number
   - Tags, collections, rating
   - Generated DAO methods

2. **DaoMaster.java** & **DaoSession.java**
   - GreenDAO generated classes
   - Database initialization and session management

3. **DatabaseUpgradeHelper.java** (~5KB)
   - Handles schema migrations
   - Version upgrades

### 2.2 Library Features

Based on code structure, LibreraReader supports:

1. **Collections/Tags System**
   - Custom collections
   - Tag-based organization
   - Series grouping

2. **Search and Filtering**
   - Full-text search across metadata
   - Filter by: author, series, format, tags
   - Recent files
   - Favorites

3. **File Scanning**
   - Automatic folder scanning
   - Metadata extraction on import
   - Thumbnail generation
   - Cache management

4. **Views**
   - Grid view with covers
   - List view with details
   - Recent books
   - Folder-based browsing

---

## 3. UI Look and Feel

### 3.1 Main UI Components

**Location**: `app/src/main/java/com/foobnix/ui2/`

#### Key UI Patterns:

1. **Main Screen**
   - Bottom navigation or tabs
   - Library grid/list view
   - Search bar
   - Filter/sort options

2. **Reader Screen**
   - Full-screen reading
   - Gesture controls (tap zones)
   - Overlay controls (auto-hide)
   - Progress bar
   - Page numbers

3. **Navigation Drawer**
   - Library sections
   - Collections
   - Settings
   - About/Help

4. **Settings**
   - Reading preferences
   - TTS settings
   - UI customization
   - Sync options

### 3.2 Design Principles

From documentation and code:
- **Material Design** components
- **Day/Night themes** support
- **Customizable** reading experience
- **Gesture-based** navigation
- **Minimal, distraction-free** reading mode

### 3.3 Color Schemes and Themes

- Multiple pre-defined themes
- Custom color selection
- Sepia, night, black OLED modes
- Adjustable brightness overlay

---

## 4. File Storage Handling

### 4.1 Storage Strategy

**Location**: `app/src/main/java/com/foobnix/sys/`

1. **Local Storage**
   - Internal app storage for cache
   - External storage for books
   - Scoped storage (Android 10+) support

2. **Cache Management**
   - Extracted book content cache
   - Thumbnail cache
   - Font cache
   - Automatic cleanup of old cache

3. **File Organization**
   ```
   /storage/emulated/0/
   ├── Books/
   │   ├── Author Name/
   │   │   └── Book Title.epub
   ├── LibreraReader/
   │   ├── cache/
   │   ├── covers/
   │   ├── profiles/
   │   └── backups/
   ```

4. **Cloud Integration**
   - Google Drive support
   - Dropbox support
   - Custom WebDAV
   - OPDS catalogs

### 4.2 Import/Export

- Import from various sources
- Export reading progress
- Backup/restore settings
- Share books via intent

---

## 5. TTS (Text-to-Speech) Features - DETAILED

### 5.1 TTS Architecture

**Location**: `app/src/main/java/com/foobnix/tts/`

This is one of LibreraReader's most sophisticated features.

#### Core TTS Classes:

1. **TTSEngine.java** (~19KB)
   - Core TTS logic
   - Text preprocessing
   - Sentence parsing
   - Speed/pitch control
   - Voice selection

2. **TTSService.java** (~29KB)
   - Android Service for background reading
   - Foreground service with notification
   - Media session integration
   - Audio focus management
   - Wake lock handling

3. **TTSControlsView.java** (~12KB)
   - UI controls overlay
   - Play/pause/stop buttons
   - Speed control slider
   - Timer functionality
   - Track navigation

4. **TTSNotification.java** (~11KB)
   - Persistent notification
   - Media controls in notification
   - Lock screen controls

5. **TTSTracks.java** (~3.6KB)
   - Chapter/section tracking
   - Progress management

### 5.2 TTS Replacement System ⭐ KEY FEATURE

**Documentation**: `docs/faq/tts-replacements/index.md`

This is a powerful feature for customizing TTS pronunciation:

#### Replacement Types:

1. **Simple Text Replacement**
   ```
   "Lib." -> "Librera"
   ```

2. **Stress Marks**
   ```
   "Librera" -> "Libréra"
   ```

3. **Regular Expressions**
   ```
   "*(L|l)ib." -> "$1ibrera"
   ```

4. **Character Skipping**
   ```
   "*[()"«»*""/[]]" -> ""
   ```

5. **Pause Insertion**
   ```
   "*[?!:;–|—|―]" -> ","
   ```

#### Special TTS Commands:

- **ttsPAUSE** - Add pause after text
- **ttsSTOP** - Stop reading if text found
- **ttsNEXT** - Go to next page if text found
- **ttsSKIP** - Skip sentence if text found

#### Implementation Details:

```java
// Pseudo-code from TTSEngine.java
class TTSEngine {
    // Apply replacements before TTS
    String applyReplacements(String text) {
        // 1. Apply simple replacements
        // 2. Apply RegExp replacements
        // 3. Apply special commands
        // 4. Handle skip/pause/stop/next
        return processedText;
    }
    
    // Check for disabled rules
    boolean isRuleDisabled(String rule) {
        return rule.startsWith("#") || rule.matches("\\w+\\d+");
    }
}
```

#### Replacement File Format:

Supports **@Voice Reader** format:
```
" text " "replacement"
*"regexp pattern" "replacement"
#"disabled rule" "replacement"
```

### 5.3 TTS Features Summary

1. **Voice Control**
   - System TTS engine selection
   - Voice selection per language
   - Speed adjustment (0.5x - 3.0x)
   - Pitch adjustment

2. **Reading Control**
   - Play/pause/stop
   - Next/previous sentence
   - Next/previous page
   - Jump to chapter

3. **Auto Features**
   - Auto-scroll during reading
   - Auto-page turn
   - Auto-stop at chapter end
   - Sleep timer

4. **Advanced**
   - Sentence highlighting
   - Word highlighting
   - Reading from selection
   - Background reading

5. **PDF Specific**
   - Skip cropped areas
   - Skip headers/footers
   - Region-based reading

---

## 6. Key Dependencies and Libraries

From `README.md` and build files:

### Core Libraries:

1. **MuPDF** (AGPL License)
   - PDF, XPS, EPUB rendering
   - Native C library
   - JNI bindings

2. **ebookdroid**
   - Document viewing framework

3. **djvulibre**
   - DjVu format support

4. **junrar**
   - RAR archive support

5. **libmobi**
   - MOBI format parsing

6. **GreenDAO**
   - ORM for database

7. **Glide**
   - Image loading and caching

8. **jsoup**
   - HTML parsing

9. **commons-compress**
   - Archive handling

10. **EventBus**
    - Inter-component communication

11. **OkHttp3**
    - Network requests

12. **rtfparserkit**
    - RTF parsing

13. **java-mammoth**
    - DOCX to HTML conversion

14. **zip4j**
    - ZIP handling

---

## 7. Implementation Recommendations for RiftedReader

### 7.1 Stage 1: Foundation (Weeks 1-2)

**Priority: HIGH**

1. **Project Setup**
   - Create Android project with Kotlin
   - Set up build system (Gradle)
   - Configure dependencies
   - Set up version control

2. **Basic UI Framework**
   - Material Design 3 components
   - Navigation component
   - ViewModel + LiveData/Flow
   - Room database setup

3. **File Model**
   ```kotlin
   @Entity(tableName = "books")
   data class BookMeta(
       @PrimaryKey val id: String,
       val path: String,
       val title: String,
       val author: String,
       val format: String,
       val coverPath: String?,
       val pageCount: Int,
       val currentPage: Int,
       val lastOpened: Long,
       val isFavorite: Boolean,
       val tags: List<String>
   )
   ```

### 7.2 Stage 2: Core Parsing (Weeks 3-5)

**Priority: HIGH**

Implement parsers in this order:
1. **TXT** - Simplest, good for testing
2. **EPUB** - Most common ebook format
3. **PDF** - Most requested
4. **MOBI** - Popular Kindle format
5. **FB2** - Good for testing XML parsing
6. Others as needed

**Recommended Libraries**:
- PDF: AndroidPdfViewer or PdfBox
- EPUB: epublib or custom ZIP parser
- MOBI: jmobilib or convert to EPUB

### 7.3 Stage 3: Library Management (Weeks 6-7)

**Priority: MEDIUM-HIGH**

1. **Database Layer**
   - Use Room instead of GreenDAO
   - Implement DAOs for CRUD operations
   - Add search queries
   - Implement collections/tags

2. **File Scanner**
   - Background worker for scanning
   - Progress notifications
   - Incremental updates

3. **UI Views**
   - RecyclerView with GridLayoutManager
   - RecyclerView with LinearLayoutManager
   - Search and filter UI
   - Sort options

### 7.4 Stage 4: Reader UI (Weeks 8-10)

**Priority: HIGH**

1. **Reading Experience**
   - ViewPager2 for page navigation
   - Gesture detector for controls
   - Overlay UI with auto-hide
   - Progress tracking

2. **Customization**
   - Font selection
   - Text size adjustment
   - Line spacing
   - Margins
   - Themes

### 7.5 Stage 5: TTS Implementation (Weeks 11-14) ⭐

**Priority: MEDIUM-HIGH** (User requested focus)

This is a complex but highly valuable feature.

1. **Basic TTS (Week 11)**
   ```kotlin
   class TTSEngine(context: Context) {
       private val tts: TextToSpeech
       
       fun speak(text: String) {
           tts.speak(text, TextToSpeech.QUEUE_ADD, null, null)
       }
       
       fun setSpeed(speed: Float) {
           tts.setSpeechRate(speed)
       }
   }
   ```

2. **TTS Service (Week 12)**
   ```kotlin
   class TTSService : Service() {
       // Foreground service
       // Media session
       // Notification
       // Background reading
   }
   ```

3. **TTS Controls (Week 13)**
   ```kotlin
   class TTSControlsView : View {
       // Play/pause/stop
       // Speed slider
       // Navigation
       // Timer
   }
   ```

4. **TTS Replacements (Week 14)** ⭐⭐⭐
   ```kotlin
   class TTSReplacementEngine {
       private val simpleRules = mutableMapOf<String, String>()
       private val regexRules = mutableListOf<Pair<Regex, String>>()
       private val commandRules = mutableMapOf<String, TTSCommand>()
       
       fun applyReplacements(text: String): String {
           var result = text
           
           // Apply simple replacements
           simpleRules.forEach { (pattern, replacement) ->
               result = result.replace(pattern, replacement)
           }
           
           // Apply regex replacements
           regexRules.forEach { (regex, replacement) ->
               result = regex.replace(result, replacement)
           }
           
           return result
       }
       
       fun loadRulesFromFile(file: File) {
           file.readLines().forEach { line ->
               parseLine(line)
           }
       }
       
       private fun parseLine(line: String) {
           when {
               line.startsWith("#") -> return // Disabled
               line.startsWith("*") -> parseRegexRule(line)
               else -> parseSimpleRule(line)
           }
       }
   }
   
   enum class TTSCommand {
       PAUSE, STOP, NEXT, SKIP
   }
   ```

### 7.6 Stage 6: Storage and Cloud (Weeks 15-16)

**Priority: MEDIUM**

1. **Local Storage**
   - Implement file organization
   - Cache management
   - Thumbnail generation

2. **Cloud Integration** (Optional)
   - Google Drive API
   - Dropbox API
   - Generic WebDAV

### 7.7 Stage 7: Advanced Features (Weeks 17-20)

**Priority: LOW-MEDIUM**

1. **Annotations**
   - Highlights
   - Notes
   - Bookmarks

2. **Dictionary**
   - Text selection
   - Dictionary lookup
   - Translation

3. **Statistics**
   - Reading time
   - Pages read
   - Reading speed

### 7.8 Testing Strategy

Throughout all stages:

1. **Unit Tests**
   - Parser tests with sample files
   - Database operations
   - TTS replacement logic

2. **Integration Tests**
   - File scanning
   - Reading flow
   - TTS playback

3. **UI Tests**
   - Navigation
   - Reading experience
   - Settings persistence

---

## 8. Technical Considerations

### 8.1 Android Requirements

- **Minimum SDK**: 24 (Android 7.0)
- **Target SDK**: 34 (Android 14)
- **Architecture**: MVVM with Repository pattern
- **Language**: Kotlin (preferred) or Java

### 8.2 Performance

- **Lazy Loading**: Load book content on demand
- **Caching**: Cache extracted text and images
- **Background Processing**: Use WorkManager for heavy tasks
- **Memory Management**: Release resources when not in use

### 8.3 Permissions

Required permissions:
```xml
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
```

### 8.4 Licensing

LibreraReader is GPL v3. If using any of their code directly:
- RiftedReader must also be GPL v3
- Must attribute LibreraReader
- Must make source available

**Recommendation**: Implement features from scratch based on this analysis, not by copying code.

---

## 9. UI/UX Design Mockup Recommendations

### 9.1 Library Screen

```
┌─────────────────────────────────┐
│  ☰  RiftedReader        🔍 ⋮   │
├─────────────────────────────────┤
│  Recent | All | Authors | Tags  │
├─────────────────────────────────┤
│  ┌─────┐ ┌─────┐ ┌─────┐       │
│  │Cover│ │Cover│ │Cover│       │
│  │ [1] │ │ [2] │ │ [3] │       │
│  └─────┘ └─────┘ └─────┘       │
│  Title 1  Title 2  Title 3      │
│                                  │
│  ┌─────┐ ┌─────┐ ┌─────┐       │
│  │Cover│ │Cover│ │Cover│       │
│  │ [4] │ │ [5] │ │ [6] │       │
│  └─────┘ └─────┘ └─────┘       │
│  Title 4  Title 5  Title 6      │
│                                  │
├─────────────────────────────────┤
│  📚  🔊  ⚙️                     │
└─────────────────────────────────┘
```

### 9.2 Reader Screen

```
┌─────────────────────────────────┐
│                                  │ ← Tap top: Show controls
│   Chapter Title                  │
│                                  │
│   Lorem ipsum dolor sit amet,    │
│   consectetur adipiscing elit.   │
│   Sed do eiusmod tempor          │
│   incididunt ut labore et        │
│   dolore magna aliqua.           │
│                                  │
│   Ut enim ad minim veniam,       │
│   quis nostrud exercitation      │
│   ullamco laboris nisi ut        │
│   aliquip ex ea commodo          │
│   consequat.                     │
│                                  │
├─────────────────────────────────┤
│ ▓▓▓▓▓▓▓▓░░░░░░░░░░░░  45%       │ ← Progress bar
│ ◀ | ▶  🔊  ☀️  Aa  ⋮          │ ← Quick controls
└─────────────────────────────────┘
     ↑       ↑    ↑   ↑   ↑
     Nav    TTS  Bright Font More
```

### 9.3 TTS Controls

```
┌─────────────────────────────────┐
│     Text-to-Speech Player        │
├─────────────────────────────────┤
│  Chapter 5: The Journey Begins   │
│                                  │
│  🔊 "Lorem ipsum dolor sit..."   │
│                                  │
│  ◀◀  ▶/⏸  ▶▶            ⏹     │
│                                  │
│  Speed: ──●────── 1.2x          │
│  Pitch: ────●──── 1.0x          │
│                                  │
│  ⏱️ Sleep Timer: 30 min         │
│  📝 Replacements: ON (12 rules) │
│                                  │
│  [ Close ]  [ Settings ]         │
└─────────────────────────────────┘
```

---

## 10. Critical Success Factors

### 10.1 Must-Have Features

1. **Reliable Parsing** - Books must open and display correctly
2. **Smooth Reading** - No lag, smooth page turns
3. **TTS Quality** - Clear pronunciation, good control
4. **Library Management** - Easy to find and organize books

### 10.2 Nice-to-Have Features

1. Cloud sync
2. OPDS catalogs
3. Advanced annotations
4. Statistics and reading goals

### 10.3 Differentiation Opportunities

Ways RiftedReader could improve on LibreraReader:

1. **Modern UI** - Use Material Design 3, modern animations
2. **Better TTS UI** - More intuitive replacement rules editor
3. **AI Features** - Smart summaries, chapter detection
4. **Social Features** - Reading challenges, book clubs
5. **Cross-Platform** - Android tablet optimization, potential iOS version

---

## 11. Development Timeline Estimate

**Total Duration**: ~20 weeks (5 months) for MVP

### Milestones:

- **Week 4**: Basic app with TXT support
- **Week 8**: EPUB and PDF support, basic library
- **Week 12**: Reader UI complete, basic TTS
- **Week 16**: Advanced TTS with replacements
- **Week 20**: Polish, testing, beta release

### Team Size:
- 1-2 developers (full-time)
- Part-time: 6-12 months

---

## 12. Next Steps

### Immediate Actions:

1. **Set up Android project**
   ```bash
   # Create new Android project
   # Package: com.rifters.riftedreader
   # Minimum SDK: 24
   # Language: Kotlin
   ```

2. **Add core dependencies**
   ```kotlin
   // build.gradle.kts
   dependencies {
       // Room for database
       implementation("androidx.room:room-runtime:2.6.1")
       kapt("androidx.room:room-compiler:2.6.1")
       
       // For PDF
       implementation("com.github.barteksc:android-pdf-viewer:3.2.0-beta.1")
       
       // For EPUB
       implementation("nl.siegmann.epublib:epublib-core:4.0")
       
       // Material Design
       implementation("com.google.android.material:material:1.11.0")
       
       // Other essentials
       implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
       implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
   }
   ```

3. **Create basic structure**
   ```
   app/src/main/java/com/rifters/riftedreader/
   ├── data/
   │   ├── database/
   │   ├── model/
   │   └── repository/
   ├── domain/
   │   ├── parser/
   │   └── tts/
   ├── ui/
   │   ├── library/
   │   ├── reader/
   │   └── settings/
   └── util/
   ```

4. **Start with TXT parser** (simplest format)
   - Read file
   - Detect encoding
   - Display in UI

---

## 13. Resources and References

### Documentation:
- LibreraReader FAQ: https://librera.mobi/faq/
- LibreraReader GitHub: https://github.com/foobnix/LibreraReader
- MuPDF: https://mupdf.com/
- Android TTS: https://developer.android.com/reference/android/speech/tts/TextToSpeech

### Similar Projects:
- ReadEra (closed source)
- Moon+ Reader (closed source)
- FBReader (GPL)
- Cool Reader (GPL)

### Libraries to Consider:
- PDF: PDFBox, MuPDF, PdfiumAndroid
- EPUB: epublib, FolioReader
- Database: Room, ObjectBox, Realm

---

## Conclusion

LibreraReader is a comprehensive, mature ebook reader with excellent TTS features. The TTS replacement system is particularly sophisticated and should be a key focus for RiftedReader. By following a staged approach and prioritizing core features first, RiftedReader can build a solid foundation and gradually add advanced features.

The key to success will be:
1. **Solid parsing** for common formats (TXT, EPUB, PDF)
2. **Excellent TTS implementation** with replacement system
3. **Modern, clean UI** that's easy to use
4. **Reliable library management** for organizing books

Start small, test thoroughly, and iterate based on user feedback.

---

**Document Version**: 1.0  
**Last Updated**: November 13, 2025  
**Prepared By**: GitHub Copilot Analysis Agent
