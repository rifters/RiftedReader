# RiftedReader Implementation Roadmap

**Project Goal**: Create a feature-rich ebook reader for Android, inspired by LibreraReader

**Timeline**: 20-24 weeks for MVP, additional time for polish and advanced features

---

## Stage 1: Project Foundation (Week 1-2)

### Goals
- Set up Android project with modern architecture
- Establish basic project structure
- Create initial UI framework
- Set up development environment

### Tasks

#### Week 1: Project Setup

**Day 1-2: Initialize Project**
- [ ] Create new Android Studio project
  - Package: `com.rifters.riftedreader`
  - Language: Kotlin
  - Minimum SDK: 24 (Android 7.0)
  - Target SDK: 34 (Android 14)
- [ ] Set up version control (Git)
- [ ] Configure build.gradle with dependencies
- [ ] Set up CI/CD (GitHub Actions)
- [ ] Create README.md with project info

**Day 3-4: Architecture Setup**
```
app/
├── data/
│   ├── database/
│   │   ├── BookDatabase.kt
│   │   ├── dao/
│   │   └── entities/
│   ├── repository/
│   └── preferences/
├── domain/
│   ├── model/
│   ├── parser/
│   └── usecase/
├── ui/
│   ├── library/
│   ├── reader/
│   ├── settings/
│   └── common/
└── util/
```

- [ ] Create package structure
- [ ] Set up dependency injection (Hilt/Koin)
- [ ] Configure Room database
- [ ] Set up ViewModel + LiveData/Flow
- [ ] Create base classes (BaseActivity, BaseFragment, BaseViewModel)

**Day 5: Dependencies**

Add to `build.gradle.kts`:
```kotlin
dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    
    // Architecture Components
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    
    // Navigation
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.6")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.6")
    
    // Room Database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    
    // Dependency Injection (choose one)
    implementation("com.google.dagger:hilt-android:2.50")
    kapt("com.google.dagger:hilt-compiler:2.50")
    
    // Image Loading
    implementation("io.coil-kt:coil:2.5.0")
    
    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
}
```

#### Week 2: Basic UI Framework

**Day 1-3: Main UI Components**
- [ ] Create MainActivity with Navigation
- [ ] Create bottom navigation bar
- [ ] Create navigation drawer
- [ ] Set up Material Design theme
- [ ] Implement light/dark theme switching

**Day 4-5: Library Screen Skeleton**
- [ ] Create LibraryFragment
- [ ] Add RecyclerView for book grid
- [ ] Create book item layout (card view)
- [ ] Add search functionality
- [ ] Create filter/sort options UI

### Deliverables
- ✅ Working Android project
- ✅ Basic navigation structure
- ✅ Empty library screen with UI
- ✅ Theme system working
- ✅ Development environment ready

### Success Criteria
- App launches without crashes
- Navigation between screens works
- Theme switching works
- Code follows architecture patterns

---

## Stage 2: File Management & Database (Week 3-4)

### Goals
- Implement file scanning and detection
- Set up database for book metadata
- Create data models
- Implement storage permissions

### Tasks

#### Week 3: Database & Models

**Day 1-2: Data Models**

Create `BookMeta.kt`:
```kotlin
@Entity(tableName = "books")
data class BookMeta(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val path: String,
    val title: String,
    val author: String?,
    val format: String,
    val size: Long,
    val dateAdded: Long,
    val lastOpened: Long = 0,
    val currentPage: Int = 0,
    val totalPages: Int = 0,
    val coverPath: String? = null,
    val isFavorite: Boolean = false,
    @TypeConverters(StringListConverter::class)
    val tags: List<String> = emptyList()
)
```

- [ ] Create BookMeta entity
- [ ] Create BookMetaDao
- [ ] Create BookDatabase
- [ ] Create Repository pattern
- [ ] Add migrations strategy

**Day 3-5: File Scanner**
- [ ] Request storage permissions
- [ ] Implement file system scanner
- [ ] Detect book formats (by extension)
- [ ] Extract basic metadata (filename, size, date)
- [ ] Store in database
- [ ] Show scan progress
- [ ] Implement incremental updates

#### Week 4: Storage & Permissions

**Day 1-2: Permission Handling**
- [ ] Add permissions to manifest
- [ ] Create permission request flow
- [ ] Handle Android 11+ scoped storage
- [ ] Create directory picker for custom folders

**Day 3-5: Library Management**
- [ ] Implement book listing from database
- [ ] Add search functionality
- [ ] Implement filters (format, author, etc.)
- [ ] Add sort options (title, date, size)
- [ ] Create collection/tag management
- [ ] Implement favorites system

### Deliverables
- ✅ Database with books table
- ✅ File scanner working
- ✅ Books displayed in library
- ✅ Search and filter working
- ✅ Permission handling complete

### Success Criteria
- Can scan and find book files
- Books appear in library
- Search finds books correctly
- Filters work as expected
- No performance issues with 1000+ books

---

## Stage 3: Basic Parsing (Week 5-7)

### Goals
- Implement parsers for major formats
- Display book content in reader view
- Handle basic navigation

### Priority Order
1. TXT (simplest)
2. EPUB (most common)
3. PDF (most requested)

### Tasks

#### Week 5: TXT Parser & Reader View

**Day 1-2: TXT Parser**
- [ ] Create Parser interface
- [ ] Implement TxtParser
- [ ] Handle encoding detection
- [ ] Extract text content
- [ ] Split into pages/chapters

**Day 3-5: Reader View**
- [ ] Create ReaderActivity
- [ ] Implement TextView-based reading
- [ ] Add page navigation (swipe)
- [ ] Show progress indicator
- [ ] Save reading position
- [ ] Implement basic text formatting

#### Week 6: EPUB Parser

**Day 1-3: EPUB Parsing**
- [ ] Add EPUB library dependency
```kotlin
implementation("nl.siegmann.epublib:epublib-core:4.0")
// or
implementation("com.github.psiegman:epublib:4.0")
```
- [ ] Create EpubParser
- [ ] Extract metadata (title, author, cover)
- [ ] Parse spine (reading order)
- [ ] Extract chapters
- [ ] Handle images

**Day 4-5: EPUB Rendering**
- [ ] Render HTML content
- [ ] Apply CSS styles
- [ ] Handle internal links
- [ ] Display images
- [ ] Create table of contents

#### Week 7: PDF Parser

**Day 1-3: PDF Parsing**
- [ ] Add PDF library
```kotlin
implementation("com.github.barteksc:android-pdf-viewer:3.2.0-beta.1")
```
- [ ] Create PdfParser
- [ ] Extract metadata
- [ ] Extract text for search/TTS
- [ ] Generate page thumbnails

**Day 4-5: PDF Rendering**
- [ ] Implement page-based viewing
- [ ] Add zoom/pan gestures
- [ ] Optimize page rendering
- [ ] Cache rendered pages
- [ ] Add page selector

### Deliverables
- ✅ TXT books can be opened and read
- ✅ EPUB books render correctly
- ✅ PDF books display properly
- ✅ Navigation works for all formats
- ✅ Reading position saved

### Success Criteria
- All three formats open without errors
- Reading is smooth (no lag)
- Images display correctly in EPUB
- PDF zoom/pan works well
- Position saves and restores

---

## Stage 4: Enhanced Reader UI (Week 8-10)

### Goals
- Polish reader experience
- Add customization options
- Implement tap zones
- Add reading controls

### Tasks

#### Week 8: Text Customization

**Day 1-2: Font System**
- [ ] Implement font selection
- [ ] Bundle popular fonts
- [ ] Allow custom font import
- [ ] Font size adjustment
- [ ] Font weight options

**Day 3-5: Reading Settings**
- [ ] Line spacing adjustment
- [ ] Margin adjustment
- [ ] Text alignment options
- [ ] Paragraph spacing
- [ ] Hyphenation toggle
- [ ] Page background color
- [ ] Text color selection

#### Week 9: Gesture Controls

**Day 1-3: Tap Zones**
- [ ] Implement 3x3 tap grid
- [ ] Make zones configurable
- [ ] Add visual feedback
- [ ] Allow action customization
- [ ] Add gesture settings UI

**Day 4-5: Advanced Gestures**
- [ ] Swipe navigation
- [ ] Pinch to zoom (text size)
- [ ] Two-finger gestures
- [ ] Long press actions
- [ ] Volume button navigation

#### Week 10: Reading Modes & Polish

**Day 1-2: Reading Modes**
- [ ] Scroll mode (continuous)
- [ ] Page mode (paginated)
- [ ] Auto-scroll
- [ ] Two-page mode (tablets)

**Day 3-5: UI Polish**
- [ ] Overlay controls
- [ ] Auto-hide controls
- [ ] Progress bar
- [ ] Chapter navigation
- [ ] Bookmarks UI
- [ ] Notes/highlights (basic)
- [ ] Page turning animations

### Deliverables
- ✅ Highly customizable reading experience
- ✅ Smooth gesture controls
- ✅ Multiple reading modes
- ✅ Professional-looking UI

### Success Criteria
- Text is easily readable
- Gestures feel natural
- Customization persists
- Controls are intuitive
- No UI glitches

---

## Stage 5: TTS Implementation (Week 11-14) ⭐ PRIORITY

### Goals
- Implement Text-to-Speech
- Create replacement system
- Build TTS controls
- Add background service

### Tasks

#### Week 11: Basic TTS

**Day 1-2: TTS Engine**
- [ ] Implement TTSEngine class
- [ ] Initialize TextToSpeech API
- [ ] Handle voice selection
- [ ] Speed/pitch controls
- [ ] Basic speak functionality

**Day 3-5: Sentence Management**
- [ ] Text preprocessing
- [ ] Sentence splitting
- [ ] Page extraction for TTS
- [ ] Current sentence tracking
- [ ] Navigation between sentences

#### Week 12: TTS Replacement System

**Day 1-3: Replacement Engine**
- [ ] Create TTSReplacementRule classes
- [ ] Implement simple replacements
- [ ] Implement regex replacements
- [ ] Implement command system (SKIP, STOP, NEXT, PAUSE)
- [ ] Rule parsing from text

**Day 4-5: Rule Management**
- [ ] Load/save rules to file
- [ ] Create default rules set
- [ ] Import @Voice format
- [ ] Enable/disable rules
- [ ] Test replacement system

#### Week 13: TTS UI

**Day 1-3: Controls View**
- [ ] Create TTS controls overlay
- [ ] Play/pause/stop buttons
- [ ] Speed slider
- [ ] Pitch slider
- [ ] Navigation controls
- [ ] Progress display

**Day 4-5: Settings UI**
- [ ] TTS settings screen
- [ ] Voice selection
- [ ] Replacement rules editor
- [ ] Add/edit/delete rules
- [ ] Import/export rules
- [ ] Test rules interface

#### Week 14: TTS Service

**Day 1-3: Background Service**
- [ ] Create TTSService (foreground)
- [ ] Media session integration
- [ ] Notification with controls
- [ ] Handle audio focus
- [ ] Wake lock management

**Day 4-5: Integration & Testing**
- [ ] Integrate with reader
- [ ] Auto-scroll during reading
- [ ] Sentence highlighting
- [ ] Sleep timer
- [ ] Test with various books
- [ ] Bug fixes

### Deliverables
- ✅ Working TTS system
- ✅ Replacement rules working
- ✅ Background reading capability
- ✅ Professional TTS controls

### Success Criteria
- TTS reads clearly
- Replacements apply correctly
- Background reading works
- Controls are responsive
- No audio glitches

---

## Stage 6: Advanced Parsing (Week 15-16)

### Goals
- Add support for more formats
- Improve parsing quality
- Handle edge cases

### Tasks

#### Week 15: MOBI & FB2

**Day 1-3: MOBI Format**
- [ ] Add MOBI library
- [ ] Create MobiParser
- [ ] Extract metadata
- [ ] Convert to HTML
- [ ] Render content

**Day 4-5: FB2 Format**
- [ ] Create Fb2Parser (XML-based)
- [ ] Parse structure
- [ ] Extract metadata
- [ ] Handle images (base64)
- [ ] Render formatted text

#### Week 16: Comic Books & Others

**Day 1-2: CBZ/CBR**
- [ ] Create CbzCbrParser
- [ ] Extract images from archive
- [ ] Maintain page order
- [ ] Create image viewer mode
- [ ] Optimize large images

**Day 3-5: Additional Formats**
- [ ] RTF support (if needed)
- [ ] HTML support
- [ ] DOCX support (optional)
- [ ] Improve all parsers
- [ ] Bug fixes

### Deliverables
- ✅ Support for 6+ formats
- ✅ Robust parsing
- ✅ Good error handling

### Success Criteria
- All formats open reliably
- Metadata extracted correctly
- Images display properly
- Performance is acceptable

---

## Stage 7: Library Features (Week 17-18)

### Goals
- Enhance library management
- Add collections
- Improve search
- Add statistics

### Tasks

#### Week 17: Collections & Organization

**Day 1-3: Collections**
- [ ] Database schema for collections
- [ ] Create/edit/delete collections
- [ ] Add books to collections
- [ ] Collection view
- [ ] Smart collections (auto-filter)

**Day 4-5: Tags & Metadata**
- [ ] Tag system
- [ ] Bulk operations
- [ ] Import metadata from online
- [ ] Edit book details
- [ ] Cover management

#### Week 18: Search & Statistics

**Day 1-2: Advanced Search**
- [ ] Full-text search (if feasible)
- [ ] Filter combinations
- [ ] Search history
- [ ] Saved searches
- [ ] Search suggestions

**Day 3-5: Statistics**
- [ ] Reading time tracking
- [ ] Pages read counter
- [ ] Reading goals
- [ ] Statistics screen
- [ ] Export statistics

### Deliverables
- ✅ Collection system
- ✅ Enhanced search
- ✅ Reading statistics

### Success Criteria
- Collections work smoothly
- Search is fast and accurate
- Statistics are interesting
- UI is polished

---

## Stage 8: Cloud & Sync (Week 19-20) [OPTIONAL]

### Goals
- Add cloud storage support
- Implement sync
- Add OPDS catalogs

### Tasks

#### Week 19: Cloud Storage

**Day 1-3: Google Drive**
- [ ] Google Drive API setup
- [ ] Authentication
- [ ] List files
- [ ] Download books
- [ ] Upload reading progress

**Day 4-5: Dropbox / Generic WebDAV**
- [ ] Dropbox integration (or)
- [ ] WebDAV support
- [ ] File synchronization
- [ ] Conflict resolution

#### Week 20: OPDS & Sync

**Day 1-2: OPDS Catalogs**
- [ ] OPDS feed parser
- [ ] Browse catalogs
- [ ] Download books
- [ ] Search catalogs

**Day 3-5: Progress Sync**
- [ ] Sync reading positions
- [ ] Sync bookmarks/notes
- [ ] Sync settings
- [ ] Conflict handling
- [ ] Background sync

### Deliverables
- ✅ Cloud storage integration
- ✅ OPDS support
- ✅ Sync working

### Success Criteria
- Can access cloud books
- OPDS catalogs browsable
- Sync is reliable
- No data loss

---

## Post-MVP Enhancements (Week 21+)

### Polish & Testing (Week 21-22)
- [ ] Comprehensive testing
- [ ] Performance optimization
- [ ] Bug fixes
- [ ] UI/UX improvements
- [ ] Accessibility improvements
- [ ] Documentation

### Advanced Features (Week 23-24)
- [ ] Annotations system (highlights, notes)
- [ ] Dictionary integration
- [ ] Translation features
- [ ] Export/share quotes
- [ ] Social features (optional)
- [ ] AI features (optional)

### Beta Release (Week 25)
- [ ] Create beta version
- [ ] Internal testing
- [ ] Fix critical bugs
- [ ] Prepare Play Store listing
- [ ] Create promotional materials

---

## Development Best Practices

### Code Quality
- Write clean, documented code
- Follow Kotlin style guide
- Use meaningful names
- Keep functions small
- Avoid code duplication

### Testing
- Unit tests for business logic
- UI tests for critical flows
- Test on multiple devices
- Test with various book files
- Performance testing

### Version Control
- Commit frequently
- Write clear commit messages
- Use feature branches
- Code review before merge
- Tag releases

### Performance
- Profile regularly
- Optimize database queries
- Implement pagination
- Use caching wisely
- Monitor memory usage

---

## Risk Management

### Technical Risks

1. **Parsing Complexity**
   - Risk: Some formats hard to parse
   - Mitigation: Use proven libraries, start simple
   
2. **Performance Issues**
   - Risk: Slow with large books
   - Mitigation: Pagination, lazy loading, caching

3. **TTS Quality**
   - Risk: Poor pronunciation
   - Mitigation: Comprehensive replacement system

4. **Memory Constraints**
   - Risk: Out of memory errors
   - Mitigation: Proper resource management, testing

### Project Risks

1. **Scope Creep**
   - Risk: Adding too many features
   - Mitigation: Stick to roadmap, MVP first

2. **Time Overruns**
   - Risk: Tasks take longer than expected
   - Mitigation: Buffer time, prioritize ruthlessly

3. **Quality Issues**
   - Risk: Bugs in release
   - Mitigation: Testing, code review, beta testing

---

## Success Metrics

### MVP Success Criteria

1. **Functionality**
   - [ ] Opens TXT, EPUB, PDF files
   - [ ] Library management works
   - [ ] TTS with replacements works
   - [ ] Reading experience is smooth

2. **Quality**
   - [ ] Crash-free rate > 99%
   - [ ] No critical bugs
   - [ ] Performance is acceptable
   - [ ] UI is polished

3. **User Experience**
   - [ ] Intuitive navigation
   - [ ] Customizable reading
   - [ ] Reliable TTS
   - [ ] Good documentation

---

## Summary

This roadmap provides a structured path to building RiftedReader. Key priorities:

1. **Weeks 1-4**: Foundation (project setup, database, UI framework)
2. **Weeks 5-10**: Core Reading (parsers, reader UI, customization)
3. **Weeks 11-14**: TTS System (main differentiator)
4. **Weeks 15-18**: Enhanced Features (more formats, collections)
5. **Weeks 19-20**: Cloud/Sync (optional)
6. **Weeks 21-25**: Polish & Release

**Critical Path**: Foundation → Parsing → Reader UI → TTS

**Most Important**: TTS with replacement system (Weeks 11-14)

**Can Be Delayed**: Cloud sync, advanced features

Focus on building a solid MVP with excellent TTS, then iterate based on user feedback.

