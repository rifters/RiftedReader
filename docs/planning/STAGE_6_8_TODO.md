# Stage 6-8 TODO Summary

**Status (2025-11-15)**

- Current focus: **Stage 7 – Library Features** (bulk metadata management).
- Blocking items before moving on: cover management UX + instrumentation.
- Action once the current branch is merged to `origin`: begin structured QA of
	the new selection + metadata flows, then tackle the remaining Stage 7 TODOs
	listed below.

After QA is underway we can promote focus to Stage 6 backlog items or start on
Stage 8 prep, depending on priorities.

This document follows the structure of
[IMPLEMENTATION_ROADMAP.md](IMPLEMENTATION_ROADMAP.md) and annotates items
that still need implementation.

## Stage 6 – Advanced Parsing

- [ ] Wire real extractors for MOBI/AZW/AZW3 using libmobi (parity with Librera's `MobiExtract.java`).
- [ ] Build dedicated FB2 parser with XML stylesheet support and base64 image decoding.
- [ ] Implement CBZ image paging and caching; integrate with a comic viewer component.
- [ ] Add CBR support (RAR decompression via Commons Compress or Junrar).
- [ ] Add converters for RTF and DOCX (Mammoth/Apache POI).
- [ ] Replace preview parser placeholder content with real page extraction.
- [ ] Expand unit tests covering newly supported formats.

## Stage 7 – Library Features

- [x] Surface favorites toggle, collection picker, and format chips in the library UI.
- [x] UI surface for creating, editing, and deleting collections.
- [x] Smart collections (auto filters based on progress, format, favorites).
- [x] Bulk metadata editor for title, author, tags, and favorite toggles.
- [ ] Cover art management (download/update, manual override hooks).
- [ ] Cover management (download, cache, and manual override).
- [x] Persisted search filters & saved searches UI.
- [x] Integrate `LibrarySearchUseCase` with `LibraryViewModel` (UI filter controls pending).
- [x] Display library statistics in Settings/About or dedicated dashboard.
- [ ] Instrumented tests for collection workflows and search filters.
- [ ] Instrumented tests for metadata editor + selection lifecycle.

## Stage 8 – Cloud & Sync (Optional)

- [ ] Implement Google Drive provider (OAuth, REST client, cached listing).
- [ ] Implement Dropbox provider (OAuth 2, delta sync).
- [ ] Implement WebDAV provider (Calibre/OPDS servers).
- [ ] Persist cloud credentials securely (EncryptedSharedPreferences / Android Keystore).
- [ ] Offline queue + conflict resolution for reading progress sync.
- [ ] Background sync scheduling via WorkManager.
- [ ] Full OPDS client with feed parsing, pagination, and download integration.
- [ ] UI flows for cloud account linking, sync toggles, and OPDS catalog browsers.
- [ ] End-to-end tests covering sync enabling/disabling and error handling.
