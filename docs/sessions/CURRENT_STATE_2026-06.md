# RiftedReader Current State — June 2026

**Date:** June 2026

## Fully complete and wired

- `RiftedReaderApplication`, `BookDatabase`, and the core Room entities/repositories
- Parser stack: `ParserFactory`, `TxtParser`, `EpubParser`, `HtmlParser`, `PdfParser`
- Reader HTML and TOC pipeline: `ReaderHtmlWrapper`, `HeadingAnchorSlugger`, TOC loading/jump flow
- Bookmark pipeline: `Bookmark`, `BookmarkManager`, `BookmarkRepository`, `BookmarkPreviewExtractor`
- Reader UI wiring: `ReaderActivity`, `ReaderPageFragment`, `ReaderViewModel`
- Conveyor / pagination wiring: `ConveyorBeltSystemViewModel`, `ConveyorBeltIntegrationBridge`
- Flex pagination plumbing: `SliceMetadata`, `FlexPaginator`, `FlexSlicingConfig`, `WindowCalculator`
- TTS plumbing: `TTSService` and replacement engine/repository
- Calibre / download plumbing: `CalibreContentServerRepository`, `CalibreWebViewActivity`,
  `BookDownloadManager`, `DownloadNotificationHelper`
- Navigation graph and reader/settings screens

## Feature-flagged

- `FlexPaginator` is enabled only when `ReaderSettings.flexPaginatorEnabled` is set
- `ReaderMode.SCROLL` is active through the reader settings mode switch

## Known stubs

- `PreviewParser` returns placeholder content for MOBI, FB2, and CBZ
- `FormatCatalog` still marks CBR, RTF, and DOCX as planned

## Test status

- Kotlin: 425 tests
- JS: 43 tests after `cd tests/js && npm install` and `npm test -- --runInBand`

## Next priorities

- Finish cleanup around stale docs and duplicated pagination helpers
- Complete SAF migration for scanner access and replace `MANAGE_EXTERNAL_STORAGE`
- Resolve remaining format support gaps in `FormatCatalog`
- Harden `BookDownloadManager.notifIdFor(url)` before exposing parallel downloads in the UI
- Move download failure fallback text (`"Unknown error"`) into string resources

## Carried review notes

- `BookDownloadManager.notifIdFor(url)` currently uses `url.hashCode()`. Low risk in the
  current single-download flow, but collisions should be eliminated before parallel download UX.
- `DownloadNotificationHelper` intentionally wraps `NotificationManagerCompat.notify(...)`
  in `runCatching` to absorb `SecurityException` when `POST_NOTIFICATIONS` has not yet been granted.
- `ReaderViewModel.Factory` keeps the optional local `BookmarkManager(bookmarkRepository)`
  fallback on purpose because manual dependency wiring is still the repository pattern.
- `BookDownloadManager` still falls back to `"Unknown error"` for notification failures; that
  wording should migrate to a string resource in a cleanup pass.
