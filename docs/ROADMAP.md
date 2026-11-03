# RiftedReader Implementation Roadmap

**Project Goal:** Feature-rich Android ebook reader with best-in-class TTS support, inspired by LibreraReader
**Language:** Kotlin | **Min SDK:** 24 (Android 7.0) | **Target SDK:** 35 (Android 15)
**Package:** `com.rifters.riftedreader`
**Revised:** November 3, 2026

---

## Current Status Summary

> **Test Suite:** 500 JVM unit tests in source | androidTest suite bootstrapped and compiling

| Stage | Description | Status |
| --- | --- | --- |
| **Stage 1** | Project Foundation | ✅ COMPLETE |
| **Stage 2** | File Management & Database | ✅ COMPLETE |
| **Stage 3** | Basic Parsing (TXT, EPUB, PDF) | ✅ COMPLETE |
| **Stage 4** | Enhanced Reader UI | ⚠️ PARTIAL |
| **Stage 5** | TTS System | ✅ COMPLETE |
| **Stage 6** | Advanced Format Parsing | ✅ COMPLETE |
| **Stage 7** | Library Features | ✅ COMPLETE |
| **Stage 8** | Cloud & Sync | 🧱 SCAFFOLDED (decisions pending) |
| **Post-MVP** | Polish, Annotations, Release | ❌ NOT STARTED |

*Status as of November 3, 2026, reconciled against the code. Earlier roadmap versions and the change history are preserved in `docs/history/` — they describe the journey, not the current state.*

---

## Stage 1: Project Foundation ✅ COMPLETE

- ✅ Android Studio project initialized (`com.rifters.riftedreader`, Kotlin, SDK 24/34)
- ✅ Version control (Git) configured
- ✅ `build.gradle.kts` with all core dependencies
- ✅ CI/CD (GitHub Actions)
- ✅ `README.md`
- ✅ Full package architecture: `data/`, `domain/`, `ui/`, `util/`
- ✅ Dependency wiring via explicit constructors/ViewModel factories (no DI framework)
- ✅ Room database configured
- ✅ ViewModel + Flow architecture
- ✅ Base classes (`BaseActivity`, `BaseFragment`, `BaseViewModel`)
- ✅ `MainActivity` with Navigation component
- ✅ Bottom navigation bar
- ✅ Material Design theme (light/dark switching; app theme preference in Settings > Appearance, dark by default)
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

> **Blocking stage closure:** gesture-based zoom, notes/highlights, page turning animations, tablet two-page mode.

### Completed

- ✅ Font selection (bundled fonts + custom import)
- ✅ Font size, weight, line spacing, margins, alignment, paragraph spacing
- ✅ Page background color + text color selection
- ✅ Hyphenation toggle
- ✅ Swipe navigation
- ✅ Volume button navigation
- ✅ Tap zones — fully implemented (3×3 grid with complete per-zone action mapping UI)
- ✅ FlexPaginator — the default pagination engine: fixed 5-slot conveyor-belt windowing with on-demand slicing and height estimation, built to stay fast on minSdk 24 devices; classic pagination retained as a selectable fallback
- ✅ Scroll mode (continuous)
- ✅ Page mode (paginated)
- ✅ Auto-scroll (active during TTS playback)
- ✅ Overlay controls with auto-hide
- ✅ Progress bar
- ✅ Chapter navigation
- ✅ Bookmarks UI — fully implemented

### Remaining / Pending

- ❌ Pinch to zoom (text size via gesture)
- ❌ Notes / highlights — confirmed not implemented; treat as pending
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

- ✅ Sentence splitting via ICU `BreakIterator` (respects abbreviations, decimals, and citations)
- ✅ TTS engine abstraction: `TTSAudioEngineProvider` + `selectedEngine` preference; the system engine is the only shipping engine, and the sherpa-onnx provider is an inert stub for future neural voices
- ✅ "Read footnotes" preference (default on) — DOCX footnotes/endnotes are spoken in context unless the user turns them off

---

## Stage 6: Advanced Format Parsing ✅ COMPLETE

All 10 formats complete. AZW3/KF8 added beyond original roadmap scope.

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
| **DOCX** | ✅ COMPLETE | Native in-repo parser (ZIP + XmlPullParser); paragraphs/runs/tables, heading TOC, metadata, footnotes/endnotes |
| **HTML** | ✅ COMPLETE | Jsoup-based `HtmlParser`, registered in `ParserFactory` |

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

## Calibre Integration (implemented — outside the stage plan)

Built outside the stage structure:

- ✅ Calibre content-server client: connection config + credential store, library browsing (`CalibreLibraryFragment`), web view (`CalibreWebFragment`), book downloads
- ✅ Settings toggles in Reader Settings (`contentServerEnabled`, `calibreWebEnabled`, both default off)
- ✅ Tab discoverability: the bottom-navigation Calibre tabs are always visible; while a feature is not enabled, its screen shows a setup state with a shortcut to the Reader Settings Calibre section (the Reader Settings toggles remain the source of truth, both default off)

---

## Stage 8: Cloud & Sync 🧱 SCAFFOLDED

> **Do not begin implementation until the planning decisions are settled.** Authentication flows, offline queue design, and conflict resolution strategy all need decisions before provider code is written. The open decisions are listed in `docs/stage8-scaffolding.md`.

### Scaffolding in place

- ✅ Inert `sync/` and `opds/` package structure (interfaces/models only, `NotImplementedError` stubs, no dependencies, nothing wired into app code)
- ✅ `docs/stage8-scaffolding.md` — per-provider auth options, conflict strategy (furthest-reading-position-wins is the leading candidate for position sync), and sync-scope decisions awaiting sign-off
- ❌ No provider implemented; no accounts, no network sync code

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
- ❌ Notes and highlights system
- ❌ Page turning animations
- ❌ Two-page mode (tablet support)

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

> Note: DOCX footnotes/endnotes are supported as inline end-of-paragraph notes with a TTS on/off setting. This item is specifically about *EPUB* footnote popups, which remain future work.

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
- Manual dependency wiring (explicit constructors + ViewModel factories)
- Room + Flow for reactive data
- Coroutines for all async work
- `runCatching { }.getOrElse { }` resilience pattern on all parser I/O

### Testing

- 500 JVM unit tests in source
- androidTest suite bootstrapped (`CollectionWorkflowTest`, `LibrarySearchFilterTest`, `MetadataEditorTest`)
- Parser contract tests for all 10 formats
- Goal: instrumented test coverage across all critical user flows before beta

### 16 KB Page Size (Android 15+ Requirement)

> **Action required before beta.** All native `.so` dependencies must have LOAD segments aligned at 16 KB boundaries. When adding native libraries: verify with `readelf -l <library>.so`, use NDK r28+, and add `-Wl,-z,max-page-size=16384` to CMake builds. Test against the Android 15+ emulator.

### Code Quality

- Kotlin style guide enforced
- Lint clean (no MissingPermission; remaining NewApi warnings resolved before release)
- Feature branches + code review before merge
- Semantic commit messages

---

--- | --- |
| DOCX listed as "optional" | ✅ Implemented — originally Apache POI; rewritten as a native in-repo parser (Nov 2026), POI removed |
| RTF listed as "if needed" | ✅ Implemented — lightweight in-repo parser |
| AZW3 / KF8 not mentioned | ✅ Added — full HUFF-CDIC decompressor, scope expansion |
| `android-pdf-viewer` dependency | ❌ Removed — dead dependency; replaced with working implementation |
| Timeline: 20–24 weeks MVP | Development is ongoing; Stages 1–7 complete |
| Stage 7 listed as Week 17–18 scope | ✅ Complete — includes cover management and instrumented tests not in original plan |
| `POST_NOTIFICATIONS` not in roadmap | ✅ Implemented — Android 13+ runtime permission guard, 4 UI sites |
| HTML listed as pending | ✅ Implemented — Jsoup `HtmlParser` registered since Nov 2025; the "pending" status in these docs was stale until the Nov 2026 reconciliation |
| Tap zones listed as partial | ✅ Completed Nov 2026 — full action mapping UI |
| Regex sentence splitting in TTS | ✅ Replaced Nov 2026 — ICU `BreakIterator` |
| FlexPaginator as an experiment behind a flag | ✅ Promoted Nov 2026 — default pagination engine with a Settings toggle |

---

*This document reflects actual project status as of November 3, 2026. Previous versions and the original-plan comparison are archived in `docs/history/`. Stage 8 implementation requires the planning decisions in docs/stage8-scaffolding.md to be settled first.*
