# RiftedReader Implementation Roadmap

**Project Goal:** Feature-rich Android ebook reader with best-in-class TTS support, inspired by LibreraReader
**Language:** Kotlin | **Min SDK:** 24 (Android 7.0) | **Target SDK:** 34 (Android 14)
**Package:** `com.rifters.riftedreader`
**Revised:** June 5, 2026

---

## Current Status Summary

> **Test Suite:** 456 JVM unit tests passing | androidTest suite bootstrapped and compiling

| Stage | Description | Status |
| --- | --- | --- |
| **Stage 1** | Project Foundation | ✅ COMPLETE |
| **Stage 2** | File Management & Database | ✅ COMPLETE |
| **Stage 3** | Basic Parsing (TXT, EPUB, PDF) | ✅ COMPLETE |
| **Stage 4** | Enhanced Reader UI | ⚠️ PARTIAL |
| **Stage 5** | TTS System | ✅ COMPLETE |
| **Stage 6** | Advanced Format Parsing | ⚠️ PARTIAL (HTML pending) |
| **Stage 7** | Library Features | ✅ COMPLETE |
| **Stage 8** | Cloud & Sync | ❌ NOT STARTED |
| **Post-MVP** | Polish, Annotations, Release | ❌ NOT STARTED |

---

## Stage 1: Project Foundation ✅ COMPLETE

- ✅ Android Studio project initialized (`com.rifters.riftedreader`, Kotlin, SDK 24/34)
- ✅ Version control (Git) configured
- ✅ `build.gradle.kts` with all core dependencies
- ✅ CI/CD (GitHub Actions)
- ✅ `README.md`
- ✅ Full package architecture: `data/`, `domain/`, `ui/`, `util/`
- ✅ Dependency injection (Hilt)
- ✅ Room database configured
- ✅ ViewModel + Flow architecture
- ✅ Base classes (`BaseActivity`, `BaseFragment`, `BaseViewModel`)
- ✅ `MainActivity` with Navigation component
- ✅ Bottom navigation bar
- ✅ Material Design theme (light/dark switching)
- ✅ `LibraryFragment` with RecyclerView book grid
- ✅ Search, filter, sort UI

---

## Stage 2: File Management & Database ✅ COMPLETE

- ✅ `BookMeta` entity (Room) with id, path, title, author, format, size, dates, progress, coverPath, isFavorite, tags
- ✅ `BookMetaDao` with full CRUD + filter queries
- ✅ `BookDatabase` with migrations strategy
- ✅ Repository pattern (`BookRepository`)
- ✅ Storage permission handling (Android 11+ scoped storage)
- ✅ File system scanner with incremental updates
- ✅ Format detection by extension
- ✅ Metadata extraction (filename, size, date)
- ✅ Search, filter (format, author), sort (title, date, size)
- ✅ Collections / tag management
- ✅ Favorites system

---

## Stage 3: Basic Parsing (TXT, EPUB, PDF) ✅ COMPLETE

Note: the original `android-pdf-viewer` dependency was removed (abandoned upstream) and replaced with a working implementation.

- ✅ Parser interface / `ParserFactory`
- ✅ `TxtParser` — encoding detection, page splitting
- ✅ `ReaderActivity` with page navigation, progress, position save
- ✅ `EpubParser` — metadata, spine, chapters, images, HTML rendering, CSS, TOC
- ✅ `PdfParser` — metadata, text extraction, page thumbnails, zoom/pan, page rendering
- ✅ Reading position persisted and restored for all three formats

---

## Stage 4: Enhanced Reader UI ⚠️ PARTIAL

> **Blocking stage closure:** gesture-based zoom, full tap zone configurability, notes/highlights, page turning animations, tablet two-page mode.

### Completed

- ✅ Font selection (bundled fonts + custom import)
- ✅ Font size, weight, line spacing, margins, alignment, paragraph spacing
- ✅ Page background color + text color selection
- ✅ Hyphenation toggle
- ✅ Swipe navigation
- ✅ Volume button navigation
- ✅ Tap zones — partially implemented (3×3 grid exists; action mapping UI incomplete)
- ✅ Scroll mode (continuous)
- ✅ Page mode (paginated)
- ✅ Auto-scroll (active during TTS playback)
- ✅ Overlay controls with auto-hide
- ✅ Progress bar
- ✅ Chapter navigation
- ✅ Bookmarks UI — fully implemented

### Remaining / Pending

- ❌ Pinch to zoom (text size via gesture)
- ❌ Full tap zone action configurability (action mapping UI incomplete)
- ❌ Notes / highlights — status uncertain; treat as pending
- ❌ Page turning animations
- ❌ Two-page mode (tablet support)

---

## Stage 5: TTS System ✅ COMPLETE

- ✅ `TTSEngine` — TextToSpeech API, voice selection, speed/pitch controls
- ✅ Text preprocessing and sentence splitting
- ✅ Page extraction for TTS, current sentence tracking, sentence navigation
- ✅ `TTSReplacementRule` — simple, regex, and command types (SKIP, STOP, NEXT, PAUSE)
- ✅ Rule parsing, load/save, default ruleset, `@Voice` format import, enable/disable
- ✅ TTS controls overlay (play/pause/stop, speed/pitch sliders, navigation, progress)
- ✅ TTS settings screen (voice selection, replacement rule editor, import/export)
- ✅ `TTSService` (foreground) — media session, notification with controls, audio focus, wake lock
- ✅ `POST_NOTIFICATIONS` permission — shared helper, 4 UI sites, Android 13+ guard in `DownloadNotificationHelper`
- ✅ Auto-scroll synced to TTS playback
- ✅ Sleep timer

---

## Stage 6: Advanced Format Parsing ⚠️ PARTIAL

8 of 9 planned formats complete. AZW3/KF8 added beyond original roadmap scope. HTML is the only remaining item blocking stage closure.

| Format | Status | Notes |
| --- | --- | --- |
| **TXT** | ✅ COMPLETE | Stage 3 |
| **EPUB** | ✅ COMPLETE | Stage 3 |
| **PDF** | ✅ COMPLETE | Stage 3 |
| **MOBI / AZW** | ✅ COMPLETE | PalmDOC LZ77, EXTH metadata, TOC, chapter pagination |
| **AZW3 / KF8** | ✅ COMPLETE | Full HUFF-CDIC decompressor — added beyond original roadmap scope |
| **FB2** | ✅ COMPLETE | XML parse, metadata, base64 images, formatted output |
| **CBZ / CBR** | ✅ COMPLETE | Archive extraction, page order, image viewer mode |
| **RTF** | ✅ COMPLETE | Lightweight in-repo parser; control words, bold/italic/underline, Unicode escapes |
| **DOCX** | ✅ COMPLETE | Apache POI (`poi-ooxml 5.2.5`), paragraph/run traversal, heading TOC, metadata |
| **HTML** | ❌ PENDING | Not yet implemented — sole remaining item for Stage 6 closure |

`FormatCatalog` updated to reflect all live statuses.

---

## Stage 7: Library Features ✅ COMPLETE

Stage 7 is fully closed and exceeded its original scope. Cover management and the instrumented test suite were not in the original plan.

- ✅ **Collections** — `CollectionEntity`, cross-ref table, `CollectionDao`, `CollectionRepository`, full CRUD UI, `CollectionPickerBottomSheet`
- ✅ **Smart collections** — `RECENTLY_OPENED`, `IN_PROGRESS`, `COMPLETED`, `NOT_STARTED`
- ✅ **Bulk metadata editor** — title, author, tags, favorite toggle for selected books (`LibraryFragment` + `LibraryViewModel`)
- ✅ **Cover management** — `CoverManager` (disk I/O, `saveCover`, `deleteCover`, `overrideCover`, `updateCoverPath`), `CoverCache` (LRU memory layer, 20 entries), manual override hook in book menu, `BooksAdapter` updated
- ✅ **Saved searches** — `LibrarySearchFilters`, `SavedLibrarySearch`, `LibraryPreferences` persistence, UI in `LibraryFragment` + `LibraryViewModel`
- ✅ **`LibrarySearchUseCase`** integrated with `LibraryViewModel`
- ✅ **Library statistics** — `formatDistribution`, `readingProgressBreakdown` (NotStarted / InProgress / Completed / averageCompletion), `topCollections`; `LibraryStatisticsFragment` UI with canvas bar chart
- ✅ **androidTest bootstrapped** — `CollectionWorkflowTest`, `LibrarySearchFilterTest`, `MetadataEditorTest` (in-memory Room DB)
- ✅ `MissingPermission` lint on `DownloadNotificationHelper` resolved
- ✅ `notifIdFor` hash collision fixed (unsigned int masking)
- ✅ `"Unknown error"` hardcoded string → `R.string.error_unknown`

### Non-Blocking Follow-Up Notes (Low Priority)

- Split manual and extracted `coverPath` into two distinct fields (currently stored as one)
- Localize the remaining hardcoded HTTP failure string in `BookDownloadManager`
- `ReaderSettingsFragment:150` NewApi lint warning — pre-existing; resolve before release

---

## Stage 8: Cloud & Sync ❌ NOT STARTED

> **Do not begin implementation without a dedicated planning session.** Authentication flows, offline queue design, and conflict resolution strategy all need to be scoped before a single line of Stage 8 code is written.

### Planned Scope

- ❌ Google Drive provider — OAuth 2.0, REST client, cached file listing, book download
- ❌ Dropbox provider — OAuth 2.0, delta sync
- ❌ WebDAV provider — Calibre/OPDS server support
- ❌ Credential storage — `EncryptedSharedPreferences` / Android Keystore
- ❌ Offline queue + conflict resolution for reading progress sync
- ❌ Background sync via WorkManager
- ❌ Full OPDS client — feed parsing (Atom/XML), pagination, download integration
- ❌ UI flows — cloud account linking, sync toggles, OPDS catalog browser
- ❌ End-to-end tests for sync enable/disable and error handling

### Recommended Implementation Order

1. **WebDAV / OPDS** — simplest authentication, most immediately useful for Calibre users
2. **Google Drive** — OAuth 2.0 integration
3. **Dropbox** — OAuth 2.0 + delta sync
4. **WorkManager background sync** + conflict resolution

---

## Post-MVP Enhancements ❌ NOT STARTED

### Reader UI Polish (Next Priority)

- ❌ Pinch to zoom gesture
- ❌ Full tap zone action configurability
- ❌ Notes and highlights system
- ❌ Page turning animations
- ❌ Two-page mode (tablet support)
- ❌ HTML format support

### Advanced Features

- ❌ Annotations system (highlights, notes, export)
- ❌ Dictionary integration (offline lookup)
- ❌ Translation features
- ❌ Export / share quotes
- ❌ Reading goals and time tracking
- ❌ Accessibility improvements (TalkBack, dynamic text size)

### Release Preparation

- ❌ Performance profiling across large libraries (1,000+ books)
- ❌ Database query optimization and full pagination audit
- ❌ Beta release build
- ❌ Play Store listing, screenshots, promotional materials
- ❌ 16 KB page size compliance audit (NDK r28+, `-Wl,-z,max-page-size=16384`, Android 15+ emulator test)

---

## Optional Features

### ⭐ Priority Optional Features (Top 8)

#### 1. Bionic Reading + Custom Fonts

**What it is:** Two font-layer features delivered together. Bionic Reading bolds the first half of every word as a visual focus aid, helping readers track faster with less eye fatigue. Custom Fonts lets users import their own `.ttf` or `.otf` files from device storage and adds them to the existing font picker alongside bundled fonts.

**Why it matters:** Bionic Reading is one of the most-requested reader features of the last few years. Custom font support is expected by power users and language learners who need specific scripts. Both are low-effort and high-visibility.

**How it works:** Bionic Reading — inject a JavaScript or Kotlin string processor that wraps the first half of each word in a `<b>` tag before rendering in WebView. Toggle stored in reader preferences. Custom Fonts — use a file picker to copy the chosen font file into internal storage, register it in the font list, and load via `Typeface.createFromFile()`.

**Complexity:** Low

---

#### 2. Reading Time Tracking

**What it is:** Automatically logs time spent reading each book per session. Session start is recorded when the reader opens a book; session end is recorded on pause, background, or close. Totals are accumulated per book and displayed in the existing Library Statistics dashboard.

**Why it matters:** Readers want to know how much time they invest in books. The Library Statistics screen is already built — this just adds a meaningful new data point to it with minimal new infrastructure.

**How it works:** Store a `readingTimeMs: Long` field on `BookMeta`. In `ReaderActivity`, record `System.currentTimeMillis()` on `onResume` and accumulate the delta on `onPause`. Flush to the database via `BookRepository.addReadingTime(bookId, deltaMs)`. Surface total time per book and library-wide totals in `LibraryStatisticsFragment`.

**Complexity:** Low

---

#### 3. Footnote Popups

**What it is:** When a reader taps a footnote link inside an EPUB, instead of navigating away from the current reading position, the footnote content opens in a small BottomSheet overlay. The reader stays on the same page and dismisses the popup when done.

**Why it matters:** Most ebook readers handle footnotes poorly by treating them as full page navigations, breaking reading flow. This is one of the clearest quality-of-life differentiators between a good reader and a great one. Academic and non-fiction readers notice immediately.

**How it works:** Intercept `shouldOverrideUrlLoading` in the EPUB WebView client. Detect links pointing to a fragment anchor within the same document or to a known footnote file. Extract the target element's text content, pass it to a `FootnoteBottomSheet`, and suppress the default navigation.

**Complexity:** Medium

---

#### 4. Backup and Restore

**What it is:** Export the full library state — Room database, cover image cache, saved searches, preferences, and TTS replacement rules — into a single `.zip` archive the user can save anywhere. Restore from that archive on any device running RiftedReader.

**Why it matters:** Before beta, users need a safety net. Device loss, factory reset, or app reinstall should not wipe a user's entire library history, collections, and reading progress. No cloud account required — works entirely via local file transfer.

**How it works:** Use `ZipOutputStream` to package the Room database file (`books.db`), the covers directory, and a JSON export of `LibraryPreferences`. Save via SAF file picker. Restore — unzip, close the database connection, overwrite files, reopen the database and reload preferences.

**Complexity:** Medium

---

#### 5. Project Gutenberg + Standard Ebooks Browser

**What it is:** An in-app content discovery browser with two tabs: Project Gutenberg (70,000+ public domain books via their public REST API) and Standard Ebooks (a curated catalog of the same public domain books formatted to professional publishing standards). Users can browse, search, preview, and download books directly into their RiftedReader library.

**Why it matters:** Content discovery is a top retention driver. Users who run out of books to read churn. A built-in free book source requiring no account and no payment is a significant differentiator — Librera does not have this. Standard Ebooks specifically offers EPUB files formatted better than anything else freely available.

**How it works:** Gutenberg — query `gutendex.com` REST API (JSON, no auth required). Standard Ebooks — parse their OPDS feed. Display results in a searchable `RecyclerView`. Download selected books via `DownloadManager` into the library scan path so they appear automatically.

**Complexity:** Medium

---

#### 6. Dictionary Lookup

**What it is:** Long-pressing any word in the reader opens an inline popup showing the dictionary definition, part of speech, and pronunciation. Works fully offline with a bundled dictionary database. No internet connection required.

**Why it matters:** Dictionary lookup is a baseline expectation for serious readers, language learners, and students. It is present in every major ebook platform (Kindle, Kobo, Apple Books). Its absence is a noticeable gap.

**How it works:** Bundle WordNet or a compact SQLite dictionary in the assets folder. On long-press in WebView, extract the selected word via JavaScript interface. Query the SQLite dictionary and display results in a `PopupWindow` or `BottomSheet`. Dictionary DB is approximately 10–30 MB depending on the source.

**Complexity:** Low–Medium

---

#### 7. Word-Highlight TTS / Karaoke Mode

**What it is:** During TTS playback, the exact word currently being spoken is highlighted in the reader view in real time, moving forward as speech progresses. Combines reading and listening simultaneously for improved comprehension and retention.

**Why it matters:** Sentence tracking is already implemented for TTS. Word-level highlight is the natural and highly requested next step. It is the signature feature of apps like Voice Dream Reader and distinguishes a serious TTS reader from a basic one.

**How it works:** Use `TextToSpeech.setOnUtteranceProgressListener` with `onRangeStart(utteranceId, start, end)` (API 26+) to receive word-level byte range callbacks. Pass these ranges to the WebView via JavaScript interface to apply a highlight CSS class to the corresponding text node. Remove the highlight when the range advances.

**Complexity:** Medium

---

#### 8. Home Screen Widget

**What it is:** An Android home screen widget showing the cover art of the currently reading book, a reading progress bar, and a Resume button that opens the reader directly to the last position. Optionally shows the book title and percentage complete.

**Why it matters:** Home screen widgets are one of the highest-engagement features on Android. A single tap to resume reading removes all friction from returning to the app. Users who have a widget installed open apps significantly more often than those who do not.

**How it works:** Implement an `AppWidgetProvider` subclass. Use `RemoteViews` to render the cover image, progress bar, title text, and a `PendingIntent` on the Resume button that deep-links into `ReaderActivity` with the last-read book ID. Update via `AppWidgetManager.updateAppWidget()` whenever reading position changes.

**Complexity:** Medium

---

## Technical Standards (Ongoing)

### Architecture

- MVVM + Repository pattern throughout
- Hilt dependency injection
- Room + Flow for reactive data
- Coroutines for all async work
- `runCatching { }.getOrElse { }` resilience pattern on all parser I/O

### Testing

- 456 JVM unit tests passing
- androidTest suite bootstrapped (`CollectionWorkflowTest`, `LibrarySearchFilterTest`, `MetadataEditorTest`)
- Parser contract tests for all 9 formats
- Goal: instrumented test coverage across all critical user flows before beta

### 16 KB Page Size (Android 15+ Requirement)

> **Action required before beta.** All native `.so` dependencies must have LOAD segments aligned at 16 KB boundaries. When adding native libraries: verify with `readelf -l <library>.so`, use NDK r28+, and add `-Wl,-z,max-page-size=16384` to CMake builds. Test against the Android 15+ emulator.

### Code Quality

- Kotlin style guide enforced
- Lint clean (no MissingPermission; remaining NewApi warnings resolved before release)
- Feature branches + code review before merge
- Semantic commit messages

---

## What Changed From the Original Roadmap

| Original Plan | Actual Outcome |
| --- | --- |
| DOCX listed as "optional" | ✅ Implemented — Apache POI (`poi-ooxml 5.2.5`) |
| RTF listed as "if needed" | ✅ Implemented — lightweight in-repo parser |
| AZW3 / KF8 not mentioned | ✅ Added — full HUFF-CDIC decompressor, scope expansion |
| `android-pdf-viewer` dependency | ❌ Removed — dead dependency; replaced with working implementation |
| Timeline: 20–24 weeks MVP | Development is ongoing; Stages 1–7 complete |
| Stage 7 listed as Week 17–18 scope | ✅ Complete — includes cover management and instrumented tests not in original plan |
| `POST_NOTIFICATIONS` not in roadmap | ✅ Implemented — Android 13+ runtime permission guard, 4 UI sites |

---

*This document reflects actual project status as of June 5, 2026. Stage 8 and Post-MVP sections require dedicated planning before work begins.*
