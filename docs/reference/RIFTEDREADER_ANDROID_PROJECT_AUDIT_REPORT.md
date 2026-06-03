# RiftedReader Android Project Audit Report

## Audit scope and verification

- **Repository:** `/tmp/workspace/rifters/RiftedReader`
- **File inventory reviewed:** 394 project files from glob `**/*`
- **Main code searched/read:** Kotlin sources, assets, tests, resources, README/docs status files

### Android unit test verification

- **Command:** `cd /tmp/workspace/rifters/RiftedReader && ./gradlew testDebugUnitTest`
- **Result:** FAILED
- **Summary:** 425 tests completed, 4 failed

**Failing tests:**

- `BookmarkRestorationTest.bookmarkRestoration_worksAfterWindowMigration`
- `ContinuousPaginatorTest.window unloads chapters outside range`
- `ContinuousPaginatorTest.navigateToChapter loads correct window`
- `ContinuousPaginatorTest.navigateToGlobalPage shifts window when needed`

### JS test verification

- **Command:** `cd /tmp/workspace/rifters/RiftedReader/tests/js && npm test -- --runInBand`
- **Result:** FAILED TO START — `jest: not found`; dependencies are declared in `/tmp/workspace/rifters/RiftedReader/tests/js/package.json` but not installed.

## Section 1: What is fully complete and wired

> Important: because the full Android unit test suite currently fails, “passing unit tests” below means the associated generated JUnit XML class had `failures=0` in this run, not that the entire suite passed.

| File | Class / function | What calls or registers it | Test coverage |
|---|---|---|---|
| `/tmp/workspace/rifters/RiftedReader/app/src/main/java/com/rifters/riftedreader/RiftedReaderApplication.kt` | `RiftedReaderApplication` | Registered in `/tmp/workspace/rifters/RiftedReader/app/src/main/AndroidManifest.xml`; initializes `AppLogger`, `BufferLogger`, `HtmlDebugLogger` | Indirectly covered by logger tests; no direct app lifecycle unit test |
| `/tmp/workspace/rifters/RiftedReader/app/src/main/java/com/rifters/riftedreader/data/database/BookDatabase.kt` | `BookDatabase` | Used by repositories/view models through app database setup | Room compile coverage via Gradle; no direct passing DB test found |
| `/tmp/workspace/rifters/RiftedReader/app/src/main/java/com/rifters/riftedreader/data/database/entities/BookMeta.kt` | `BookMeta` | Core entity used by library, reader, parsers, repositories | `BookMetaTest` passed |
| `/tmp/workspace/rifters/RiftedReader/app/src/main/java/com/rifters/riftedreader/data/database/entities/Bookmark.kt` | `Bookmark`, `BookmarkEntity` | Used by `BookmarkRepository`, `BookmarkManager`, `ReaderViewModel` | `BookmarkCreationTest` passed; `BookmarkRestorationTest` has 1 failure |
| `/tmp/workspace/rifters/RiftedReader/app/src/main/java/com/rifters/riftedreader/data/database/entities/CollectionEntity.kt` | `CollectionEntity`, `BookCollectionCrossRef`, `CollectionWithBooks` | Used by `CollectionDao`, `CollectionRepository`, `LibraryViewModel` | Covered by `LibrarySearchUseCaseTest`, passed |
| `/tmp/workspace/rifters/RiftedReader/app/src/main/java/com/rifters/riftedreader/data/repository/BookRepository.kt` | `BookRepository` | Used by `LibraryViewModel`, `ReaderViewModel`, `BookDownloadManager`, `FileScanner` | Covered indirectly by `LibrarySearchUseCaseTest`; passed |
| `/tmp/workspace/rifters/RiftedReader/app/src/main/java/com/rifters/riftedreader/data/repository/CollectionRepository.kt` | `CollectionRepository` | Used by `LibraryViewModel`, `LibrarySearchUseCase` | `LibrarySearchUseCaseTest` passed |
| `/tmp/workspace/rifters/RiftedReader/app/src/main/java/com/rifters/riftedreader/domain/library/LibrarySearch.kt` | `LibrarySearchUseCase` | Instantiated in `LibraryViewModel` | `LibrarySearchUseCaseTest` passed |
| `/tmp/workspace/rifters/RiftedReader/app/src/main/java/com/rifters/riftedreader/domain/library/SmartCollections.kt` | `SmartCollections` | Used by `LibraryViewModel.observeSmartCollections()` | Covered by `LibrarySearchUseCaseTest`; passed |
| `/tmp/workspace/rifters/RiftedReader/app/src/main/java/com/rifters/riftedreader/domain/library/LibraryStatistics.kt` | `LibraryStatisticsCalculator` | Used by `LibraryStatisticsFragment` | No direct passing unit test found |
| `/tmp/workspace/rifters/RiftedReader/app/src/main/java/com/rifters/riftedreader/domain/parser/ParserFactory.kt` | `ParserFactory` | Used by `FileScanner`, `LibraryViewModel.importBook()`, reader parser loading | Covered indirectly by parser/library tests; passed except no dedicated factory test |
| `/tmp/workspace/rifters/RiftedReader/app/src/main/java/com/rifters/riftedreader/domain/parser/TxtParser.kt` | `TxtParser` | Registered in `ParserFactory.init()` | `TxtParserConcurrencyTest` passed |
| `/tmp/workspace/rifters/RiftedReader/app/src/main/java/com/rifters/riftedreader/domain/parser/EpubParser.kt` | `EpubParser` | Registered in `ParserFactory.init()`; used for EPUB import/reader content | `EpubHtmlRenderingTest`, `EpubImageCacheTest`, `EpubPathParsingTest`, `EpubSvgImageTest`, `EpubCoverCacheTest`, `EpubCoverDebugLoggingTest` passed |
| `/tmp/workspace/rifters/RiftedReader/app/src/main/java/com/rifters/riftedreader/domain/parser/HtmlParser.kt` | `HtmlParser` | Registered in `ParserFactory.init()` | Indirect coverage through parser factory paths; no direct test found |
| `/tmp/workspace/rifters/RiftedReader/app/src/main/java/com/rifters/riftedreader/domain/parser/PdfParser.kt` | `PdfParser` | Registered in `ParserFactory.init()` | No direct passing unit test found |
| `/tmp/workspace/rifters/RiftedReader/app/src/main/java/com/rifters/riftedreader/domain/reader/ReaderHtmlWrapper.kt` | `ReaderHtmlWrapper.wrap()` | Used by reader/window HTML generation | `ReaderHtmlWrapperTypographyTest` passed |
| `/tmp/workspace/rifters/RiftedReader/app/src/main/java/com/rifters/riftedreader/domain/reader/HeadingAnchorSlugger.kt` | `HeadingAnchorSlugger` | Called by `ReaderHtmlWrapper.wrap()` | `HeadingAnchorSluggerTest` passed |
| `/tmp/workspace/rifters/RiftedReader/app/src/main/java/com/rifters/riftedreader/util/EpubImageAssetHelper.kt` | `EpubImageAssetHelper` | Used by `ReaderHtmlWrapper`, `ReaderPageFragment`, `EpubImagePathHandler`, `EpubParser` | `EpubImageAssetHelperTest` passed |
| `/tmp/workspace/rifters/RiftedReader/app/src/main/java/com/rifters/riftedreader/ui/reader/EpubImagePathHandler.kt` | `EpubImagePathHandler` | Registered in `ReaderPageFragment` through `WebViewAssetLoader` | `EpubImagePathHandlerSecurityTest` passed |
| `/tmp/workspace/rifters/RiftedReader/app/src/main/java/com/rifters/riftedreader/util/BookmarkPreviewExtractor.kt` | `BookmarkPreviewExtractor` | Used by bookmark creation/preview paths | `BookmarkPreviewExtractorTest` passed |
| `/tmp/workspace/rifters/RiftedReader/app/src/main/java/com/rifters/riftedreader/util/HtmlDebugLogger.kt` | `HtmlDebugLogger` | Initialized by `RiftedReaderApplication`; used for HTML debug logging | `HtmlDebugLoggerTest` passed |
| `/tmp/workspace/rifters/RiftedReader/app/src/main/java/com/rifters/riftedreader/util/WindowRenderingDebug.kt` | `WindowRenderingDebug` | Used by reader debug rendering and `ReaderHtmlWrapper` debug banner | `WindowRenderingDebugTest` passed |
| `/tmp/workspace/rifters/RiftedReader/app/src/main/java/com/rifters/riftedreader/util/AppLogger.kt` | `AppLogger` | Used widely; initialized by app class | `AppLoggerWindowNavigationTest` passed |
| `/tmp/workspace/rifters/RiftedReader/app/src/main/java/com/rifters/riftedreader/ui/reader/PaginatorBridge.kt` | `PaginatorBridge` | Registered in `ReaderPageFragment.addJavascriptInterface(..., "PaginatorBridge")`; used by `minimal_paginator.js` | Indirectly covered by reader/WebView tests; no dedicated bridge test found |
| `/tmp/workspace/rifters/RiftedReader/app/src/main/assets/minimal_paginator.js` | `window.minimalPaginator` | Loaded by `ReaderHtmlWrapper` when `useFlexPaginator=false`; active default paginator | JS tests exist in `/tmp/workspace/rifters/RiftedReader/tests/js/minimal_paginator.test.js`, but could not run because Jest is missing |
| `/tmp/workspace/rifters/RiftedReader/app/src/main/java/com/rifters/riftedreader/ui/reader/ReaderTapZone.kt` | `ReaderTapZoneDetector.detect()` | Used by reader tap handling/settings | `ReaderTapZoneDetectorTest` passed |
| `/tmp/workspace/rifters/RiftedReader/app/src/main/java/com/rifters/riftedreader/ui/reader/conveyor/ConveyorBeltSystemViewModel.kt` | `ConveyorBeltSystemViewModel` | Attached from `ReaderViewModel`; used as primary continuous-window cache/buffer | `ConveyorBeltSystemViewModelTest` passed |
| `/tmp/workspace/rifters/RiftedReader/app/src/main/java/com/rifters/riftedreader/ui/reader/conveyor/ConveyorBeltIntegrationBridge.kt` | `ConveyorBeltIntegrationBridge` | Bridges `ReaderViewModel.currentWindowIndex` to conveyor | Covered indirectly by conveyor tests; passed |
| `/tmp/workspace/rifters/RiftedReader/app/src/main/java/com/rifters/riftedreader/pagination/SliceMetadata.kt` | `PageSlice`, `SliceMetadata` | Used by `FlexPaginator`/offscreen slicing and cached `WindowData` | `SliceMetadataTest` passed |
| `/tmp/workspace/rifters/RiftedReader/app/src/main/java/com/rifters/riftedreader/pagination/FlexPaginator.kt` | `FlexPaginator` | Called by `ConveyorBeltSystemViewModel` only when `ReaderSettings.flexPaginatorEnabled=true` | `FlexPaginatorTest` passed |
| `/tmp/workspace/rifters/RiftedReader/app/src/main/java/com/rifters/riftedreader/pagination/FlexSlicingConfig.kt` | `FlexSlicingConfig` | Used by `OffscreenSlicingWebView` and conveyor typography flow | `FlexSlicingConfigTypographyTest` passed |
| `/tmp/workspace/rifters/RiftedReader/app/src/main/java/com/rifters/riftedreader/pagination/WindowCalculator.kt` | `WindowCalculator` | Used by pagination/window tests and helpers | `WindowCalculatorTest` passed |
| `/tmp/workspace/rifters/RiftedReader/app/src/main/java/com/rifters/riftedreader/domain/pagination/ChapterIndexProvider.kt` | `ChapterIndexProvider` | Used by reader chapter visibility/window mapping | `ChapterIndexProviderTest` passed |
| `/tmp/workspace/rifters/RiftedReader/app/src/main/java/com/rifters/riftedreader/domain/tts/TTSService.kt` | `TTSService` | Registered in manifest as foreground media playback service | No direct passing service unit test found |
| `/tmp/workspace/rifters/RiftedReader/app/src/main/java/com/rifters/riftedreader/domain/tts/TTSReplacementParser.kt` / `TTSReplacementEngine.kt` | TTS replacement parsing/application | Used by `TTSReplacementRepository`, `TTSService`, settings UI | `TTSReplacementRepositoryTest` passed |
| `/tmp/workspace/rifters/RiftedReader/app/src/main/res/navigation/nav_graph.xml` | Main nav graph | Used by `MainActivity` / Navigation component | Compile/resource validation; no direct unit test |

## Section 2: What is built but not wired

| File / asset | What it does | Why it is not wired or only conditionally wired | What would wire it |
|---|---|---|---|
| `/tmp/workspace/rifters/RiftedReader/app/src/main/java/com/rifters/riftedreader/pagination/FlexPaginator.kt` | Builds pre-sliced window HTML for `FlexPaginator` | Wired only behind `ReaderSettings.flexPaginatorEnabled=false` default | Enable the setting through `/tmp/workspace/rifters/RiftedReader/app/src/main/java/com/rifters/riftedreader/ui/reader/ReaderTextSettingsBottomSheet.kt` and validate production behavior |
| `/tmp/workspace/rifters/RiftedReader/app/src/main/java/com/rifters/riftedreader/pagination/OffscreenSlicingWebView.kt` | Hidden WebView for slicing Flex windows | Used only when `FlexPaginator` is enabled | Same feature flag; ensure real viewport parity via `OffscreenSlicingWebView.setDefaultViewportSize()` |
| `/tmp/workspace/rifters/RiftedReader/app/src/main/assets/flex_paginator.js` | Node-walking slicing and `FlexPaginator` runtime | Loaded only when `ReaderHtmlConfig.useFlexPaginator=true`; default OFF | Turn on `ReaderSettings.flexPaginatorEnabled` |
| `/tmp/workspace/rifters/RiftedReader/app/src/main/java/com/rifters/riftedreader/ui/reader/FlexPaginatorBridge.kt` | JS bridge for `FlexPaginator` callbacks | Registered only when `FlexPaginator` is enabled or scroll mode is active | Enable `FlexPaginator` and verify page/boundary callbacks in production |
| `/tmp/workspace/rifters/RiftedReader/app/src/main/assets/inpage_paginator.js` | Older in-page/window paginator implementation | Not selected by `ReaderHtmlWrapper`; default path chooses `minimal_paginator.js` or `flex_paginator.js` | Either remove or explicitly reintroduce through `ReaderHtmlWrapper` |
| `/tmp/workspace/rifters/RiftedReader/app/src/main/java/com/rifters/riftedreader/ui/reader/WebViewPaginatorBridge.kt` | Kotlin control wrapper for `minimal_paginator.js` | Annotated deprecated; comments say `PaginatorBridge` is current bridge | Delete after confirming no runtime references beyond docs/tests |
| `/tmp/workspace/rifters/RiftedReader/app/src/main/java/com/rifters/riftedreader/domain/pagination/legacy/StableWindowManager.kt` | Original stable-window manager | Package is legacy; comments say replace with removed `WindowBufferManager` / newer conveyor model | Delete or migrate any still-useful tests/docs into conveyor model |
| `/tmp/workspace/rifters/RiftedReader/app/src/main/java/com/rifters/riftedreader/domain/parser/PreviewParser.kt` | Placeholder parser for in-progress formats | Only registered by `ParserFactory.enablePreviewParsers()`, called during file scanning; not full rendering | Implement real MOBI/FB2/CBZ parsers and register as supported |
| `/tmp/workspace/rifters/RiftedReader/app/src/main/java/com/rifters/riftedreader/domain/parser/FormatCatalog.kt` planned descriptors | Tracks CBR/RTF/DOCX as planned | No parser implementation for planned formats | Add real parser classes and update `FormatSupportStatus` |
| `/tmp/workspace/rifters/RiftedReader/app/src/main/java/com/rifters/riftedreader/ui/reader/conveyor/ConveyorDebugActivity.kt` | Debug UI for conveyor buffer | Registered in manifest and exported, but not navigated from live UI | Add a debug-only launcher/menu action or make non-exported if purely internal |
| `/tmp/workspace/rifters/RiftedReader/docs/deprecated/*.deprecated` | Archived Kotlin/test files | Not part of compilation | Keep as historical docs or delete after migration notes are captured |
| `/tmp/workspace/rifters/RiftedReader/detect_viewpager_traces.sh` | Shell helper for old ViewPager traces | Not part of app/build wiring | Keep as maintenance script or delete if ViewPager cleanup is complete |

## Section 3: What is stubbed or incomplete

| File | Missing / incomplete item | What depends on it |
|---|---|---|
| `/tmp/workspace/rifters/RiftedReader/app/src/main/java/com/rifters/riftedreader/domain/parser/PreviewParser.kt` | Explicit placeholder content: “preview support is not fully implemented yet” | MOBI/FB2/CBZ import/rendering quality |
| `/tmp/workspace/rifters/RiftedReader/app/src/main/java/com/rifters/riftedreader/domain/parser/FormatCatalog.kt` | CBR, RTF, DOCX are PLANNED; no parser implementations found | Full format support roadmap |
| `/tmp/workspace/rifters/RiftedReader/app/src/main/java/com/rifters/riftedreader/domain/parser/EpubParser.kt` | TODOs at image mapping: enable `EpubImageAssetHelper.recordMapping` after asset handler PR merges | EPUB image debug mapping/completeness |
| `/tmp/workspace/rifters/RiftedReader/app/src/main/java/com/rifters/riftedreader/ui/reader/ReaderPageFragment.kt` | TODO: notify TTS chunks ready; TODO: notify TTS start from tapped chunk | TTS sentence/chunk integration |
| `/tmp/workspace/rifters/RiftedReader/app/build.gradle.kts` | Native/CMake config says “prepared for future native code”; no CMake/native code present | Future native dependencies only |
| `/tmp/workspace/rifters/RiftedReader/README.md` | Mentions Hilt and cloud/sync scaffolding, but no Hilt dependency or cloud provider code found in current file inventory | Documentation accuracy |
| `/tmp/workspace/rifters/RiftedReader/docs/planning/STAGE_6_8_TODO.md` | Lists remaining Stage 6–8 work including preview parser replacement | Parser roadmap |
| `/tmp/workspace/rifters/RiftedReader/tests/js/package.json` | JS tests cannot run until dependencies are installed | Verification of JS paginator assets |
| `/tmp/workspace/rifters/RiftedReader/docs/sessions/RESTART_2026.md` | Says `flex_paginator.test.js` missing, but current repo has `/tmp/workspace/rifters/RiftedReader/tests/js/flex_paginator.test.js`; doc is stale | Documentation cleanup |

## Section 4: Deprecated / superseded code

| File | Deprecated / superseded reason | Superseded by | Safe to delete? |
|---|---|---|---|
| `/tmp/workspace/rifters/RiftedReader/app/src/main/java/com/rifters/riftedreader/ui/reader/WebViewPaginatorBridge.kt` | Annotated `@Deprecated`; comments say use `PaginatorBridge` and direct `evaluateJavascript` | `/tmp/workspace/rifters/RiftedReader/app/src/main/java/com/rifters/riftedreader/ui/reader/PaginatorBridge.kt` | Maybe — compile still includes it; delete after confirming tests/docs don’t rely on it |
| `/tmp/workspace/rifters/RiftedReader/app/src/main/java/com/rifters/riftedreader/domain/pagination/legacy/StableWindowManager.kt` | Package name legacy; comments describe original stable-window design | Conveyor stack: `/tmp/workspace/rifters/RiftedReader/app/src/main/java/com/rifters/riftedreader/ui/reader/conveyor/ConveyorBeltSystemViewModel.kt` | Maybe — not obviously live, but preserve until migration docs/tests are reconciled |
| `/tmp/workspace/rifters/RiftedReader/app/src/main/assets/inpage_paginator.js` | Older paginator alongside active `minimal_paginator.js` and experimental `flex_paginator.js` | `minimal_paginator.js` default; `flex_paginator.js` future path | Maybe — JS tests still cover it; delete only if historical tests are removed |
| `/tmp/workspace/rifters/RiftedReader/app/src/main/java/com/rifters/riftedreader/ui/reader/PaginationModeGuard.kt` | Duplicate guard name in UI package | `/tmp/workspace/rifters/RiftedReader/app/src/main/java/com/rifters/riftedreader/pagination/PaginationModeGuard.kt` or domain variant | Maybe — determine active imports first |
| `/tmp/workspace/rifters/RiftedReader/app/src/main/java/com/rifters/riftedreader/domain/pagination/PaginationModeGuard.kt` | Duplicate guard name in domain package | `/tmp/workspace/rifters/RiftedReader/app/src/main/java/com/rifters/riftedreader/pagination/PaginationModeGuard.kt` appears newer Flex/conveyor-related | Maybe |
| `/tmp/workspace/rifters/RiftedReader/docs/deprecated/WindowBufferManager.kt.deprecated` | Archived deprecated source | Conveyor system | Yes, if historical archive is not needed |
| `/tmp/workspace/rifters/RiftedReader/docs/deprecated/WindowBufferManagerTest.kt.deprecated` | Archived deprecated test | Conveyor tests | Yes, if archive not needed |
| `/tmp/workspace/rifters/RiftedReader/docs/deprecated/WindowRenderingPipelineTest.kt.deprecated` | Archived deprecated test | Current window/conveyor tests | Yes, if archive not needed |
| `/tmp/workspace/rifters/RiftedReader/docs/sessions/RESTART_2026.md` | Contains stale statements about missing JS Flex tests | Current code and `/tests/js/flex_paginator.test.js` | No — historical doc, but mark stale |

## Section 5: Naming and structure inconsistencies

| Issue | Files | Details |
|---|---|---|
| Three `PaginationModeGuard` classes | `/tmp/workspace/rifters/RiftedReader/app/src/main/java/com/rifters/riftedreader/domain/pagination/PaginationModeGuard.kt`, `/tmp/workspace/rifters/RiftedReader/app/src/main/java/com/rifters/riftedreader/pagination/PaginationModeGuard.kt`, `/tmp/workspace/rifters/RiftedReader/app/src/main/java/com/rifters/riftedreader/ui/reader/PaginationModeGuard.kt` | Same conceptual responsibility split across domain/pagination/UI packages |
| Two `SlidingWindowPaginator` classes | `/tmp/workspace/rifters/RiftedReader/app/src/main/java/com/rifters/riftedreader/domain/pagination/SlidingWindowPaginator.kt`, `/tmp/workspace/rifters/RiftedReader/app/src/main/java/com/rifters/riftedreader/pagination/SlidingWindowPaginator.kt` | Similar window math APIs in different packages |
| Two `WindowSyncHelpers` objects | `/tmp/workspace/rifters/RiftedReader/app/src/main/java/com/rifters/riftedreader/domain/pagination/WindowSyncHelpers.kt`, `/tmp/workspace/rifters/RiftedReader/app/src/main/java/com/rifters/riftedreader/pagination/WindowSyncHelpers.kt` | Duplicate helper names and responsibilities |
| Bridge naming drift | `PaginatorBridge.kt`, `FlexPaginatorBridge.kt`, `WebViewPaginatorBridge.kt`, `WebViewJsBridge.kt`, `AppLoggerBridge.kt` | Some are JS callback bridges, some Kotlin control wrappers; names do not consistently indicate direction or lifecycle |
| Pagination packages split | `/domain/pagination` and `/pagination` | `FlexPaginator`, `SliceMetadata`, `DefaultWindowAssembler` live outside domain while older paginator/domain types live inside domain; unclear canonical package |
| Hardcoded tuning values | `ReaderPageFragment.kt`, `OffscreenSlicingWebView.kt`, JS paginator assets | Delays/timeouts/retry counts appear inline: e.g. 50, 100, 10 s, buffer size 5, debounce/retry constants |
| Documentation status drift | `README.md`, `docs/sessions/RESTART_2026.md`, many `docs/planning/*` | Some docs describe missing or future work that now exists, while other docs describe completed features as still pending |

## Section 6: Technical debt hotspots

| Rank | Hotspot | Files | Risk |
|---:|---|---|---|
| 1 | Continuous paginator/window migration currently has failing tests | `/tmp/workspace/rifters/RiftedReader/app/src/test/java/com/rifters/riftedreader/ContinuousPaginatorTest.kt`, `/tmp/workspace/rifters/RiftedReader/app/src/test/java/com/rifters/riftedreader/BookmarkRestorationTest.kt` | High — navigation/bookmark restoration correctness |
| 2 | Multiple pagination stacks coexist | `minimal_paginator.js`, `inpage_paginator.js`, `flex_paginator.js`, conveyor, domain pagination, app pagination | High — behavior drift and hard-to-debug reader bugs |
| 3 | `FlexPaginator` behind default-off flag with fallback swallowing | `ConveyorBeltSystemViewModel.assembleWindowData()` | Medium/High — failures silently fall back to legacy path |
| 4 | WebView lifecycle and JS bridge complexity | `ReaderPageFragment.kt`, `FlexPaginatorBridge.kt`, `PaginatorBridge.kt`, `WebViewJsBridge.kt` | High — race conditions, stale callbacks, lifecycle leaks |
| 5 | Incomplete parser roadmap | `PreviewParser.kt`, `FormatCatalog.kt` | Medium — users can index formats that only render placeholder content |
| 6 | EPUB image mapping TODOs | `EpubParser.kt`, `EpubImageAssetHelper.kt`, `EpubImagePathHandler.kt` | Medium — image rendering/debug gaps |
| 7 | Documentation is significantly stale/duplicated | `README.md`, `docs/planning/*`, `docs/sessions/*`, `CLEANUP.md` | Medium — developer confusion and duplicated cleanup work |
| 8 | Broad filesystem permissions | `/tmp/workspace/rifters/RiftedReader/app/src/main/AndroidManifest.xml` | Medium — `MANAGE_EXTERNAL_STORAGE` is high-friction and high-risk for Play policy/privacy |

## Section 7: Cleanup priority list

### Priority 1 — Safe deletes / archive decisions

| Action | Files | Risk |
|---|---|---|
| Remove or archive deprecated WindowBuffer source/test snapshots if historical docs are not needed | `/tmp/workspace/rifters/RiftedReader/docs/deprecated/WindowBufferManager.kt.deprecated`, `/tmp/workspace/rifters/RiftedReader/docs/deprecated/WindowBufferManagerTest.kt.deprecated`, `/tmp/workspace/rifters/RiftedReader/docs/deprecated/WindowRenderingPipelineTest.kt.deprecated` | Low |
| Decide whether to delete `WebViewPaginatorBridge.kt` after import/reference confirmation | `/tmp/workspace/rifters/RiftedReader/app/src/main/java/com/rifters/riftedreader/ui/reader/WebViewPaginatorBridge.kt` | Medium |
| Decide whether `inpage_paginator.js` is historical or supported | `/tmp/workspace/rifters/RiftedReader/app/src/main/assets/inpage_paginator.js`, `/tmp/workspace/rifters/RiftedReader/tests/js/inpage_paginator.test.js` | Medium |

### Priority 2 — Renames / moves

| Action | Files | Risk |
|---|---|---|
| Pick one canonical pagination package and move/rename duplicates | `/domain/pagination/*`, `/pagination/*` | High |
| Consolidate `PaginationModeGuard` classes into one canonical implementation | Three `PaginationModeGuard.kt` files | Medium/High |
| Consolidate `SlidingWindowPaginator` and `WindowSyncHelpers` duplicates | Matching domain/app pagination files | Medium |

### Priority 3 — Consolidations

| Action | Files | Risk |
|---|---|---|
| Centralize pagination timing constants, buffer sizes, retry delays, and timeouts | `ReaderPageFragment.kt`, `ConveyorBeltSystemViewModel.kt`, `OffscreenSlicingWebView.kt`, paginator JS assets | Medium |
| Normalize JS bridge naming and callback contracts | `PaginatorBridge.kt`, `FlexPaginatorBridge.kt`, `WebViewJsBridge.kt`, `AppLoggerBridge.kt` | Medium |
| Install or document JS test setup so paginator tests are runnable | `/tmp/workspace/rifters/RiftedReader/tests/js/package.json` | Low |

### Priority 4 — Wiring gaps

| Action | Files | Risk |
|---|---|---|
| Fix current failing continuous paginator tests before further pagination cleanup | `ContinuousPaginator.kt`, `ReaderViewModel.kt`, related tests | High |
| Finish `FlexPaginator` production validation or keep it clearly experimental | `FlexPaginator.kt`, `OffscreenSlicingWebView.kt`, `ConveyorBeltSystemViewModel.kt`, `ReaderTextSettingsBottomSheet.kt`, `flex_paginator.js` | High |
| Replace `PreviewParser` placeholders with real parsers for MOBI/FB2/CBZ or keep them out of “supported” UX | `PreviewParser.kt`, `FormatCatalog.kt`, `ParserFactory.kt` | Medium |

### Priority 5 — Risk mitigations

| Action | Files | Risk |
|---|---|---|
| Add lifecycle cleanup checks for WebView JS interfaces and offscreen WebView destruction | `ReaderPageFragment.kt`, `OffscreenSlicingWebView.kt` | High |
| Avoid silent `FlexPaginator` fallback without visible diagnostics | `ConveyorBeltSystemViewModel.kt` | Medium |
| Review storage permissions and scan scope | `AndroidManifest.xml`, `FileScanner.kt` | Medium |
| Update stale docs to match current code | `README.md`, `docs/sessions/RESTART_2026.md`, `docs/planning/*`, `CLEANUP.md` | Low/Medium |
