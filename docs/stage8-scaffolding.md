# Stage 8 Scaffolding — Cloud & Sync Architecture

This document describes the package and interface scaffolding created for Stage 8 (Cloud & Sync) of RiftedReader, followed by key architectural decisions and trade-offs that need to be made before full implementation.

---

## 1. What Was Created

Following the existing patterns in RiftedReader (Coroutines, StateFlow, Result wrappers, clean separation of data/domain/UI), the following scaffolding was established:

### A. Data Models (`com.rifters.riftedreader.sync.model`)
- `RemoteFileRef`: Represents remote items stored across cloud backends (id, path, size, timestamps, mime-type, checksum, provider type).
- `SyncState`: Sealed interface representing engine state (`Idle`, `Syncing`, `Success`, `Conflict`, `Error`).
- `ProviderAuthState`: Tracks authentication lifecycle (`Unauthenticated`, `Authenticating`, `Authenticated`, `Error`).
- `QueuedSyncOperation`: Persistent representation of queued offline sync actions (`UPLOAD_BOOK`, `DOWNLOAD_BOOK`, `DELETE_REMOTE_BOOK`, `SYNC_READING_POSITION`, `SYNC_BOOKMARK`) with retry counts and status.
- `ConflictRecord`: Data structure for recorded collisions with `ConflictResolutionStrategy` choices.

### B. Provider Layer (`com.rifters.riftedreader.sync.provider`)
- `CloudProvider`: Universal interface defining list, download, upload, delete, and auth state management.
- `GoogleDriveProvider`: Stub implementing `CloudProvider` (throws `NotImplementedError`).
- `DropboxProvider`: Stub implementing `CloudProvider` (throws `NotImplementedError`).
- `WebDavProvider`: Stub implementing `CloudProvider` (supports Nextcloud, ownCloud, Fastmail, generic WebDAV).

### C. Secure Credentials (`com.rifters.riftedreader.sync.credentials`)
- `SyncCredentialStore`: Interface for retrieving, saving, checking, and clearing encrypted provider credentials.
- `EncryptedSyncCredentialStore`: Implementation stub prepared for Android Keystore / EncryptedSharedPreferences.

### D. Sync Engine (`com.rifters.riftedreader.sync.engine`)
- `SyncEngine`: Coordination interface managing manual sync triggers, offline operation queues, and conflict resolutions.
- `DefaultSyncEngine`: Concrete stub coordinating background work.

### E. OPDS Client (`com.rifters.riftedreader.opds`)
- `OpdsFeed`, `OpdsEntry`, `OpdsLink`: Open Publication Distribution System data models for catalog browsing and book acquisition.
- `OpdsClient` and `DefaultOpdsClient`: Interface and stub for Atom/XML catalog querying and file acquisition.

---

## 2. Decisions Required Before Implementation

Before writing network and authentication logic, please make the following foundational decisions:

### Decision 1: Authentication Approach per Provider

#### Google Drive:
- **Option A: Google Identity Services / Credential Manager (OAuth 2.0 with Drive AppData or drive.file scope)**
  - *Pros*: Native Android UX, no need for user to enter client secrets manually, secure system token management.
  - *Cons*: Requires Google Play Services and Google Cloud Console SHA-1 fingerprint registration.
- **Option B: Standard OAuth 2.0 Authorization Code flow with PKCE via AppAuth / Custom Tabs**
  - *Pros*: Open standard, works without Google Play Services (F-Droid friendly).
  - *Cons*: Requires custom URI scheme redirect handling and OAuth client ID distribution.

#### Dropbox:
- **Option A: Official Dropbox Android SDK (`com.dropbox.core:dropbox-core-sdk`)**
  - *Pros*: Handles token refresh and OAuth flows out of the box with dedicated activity.
  - *Cons*: Adds dependency weight (~1.5 MB).
- **Option B: Lightweight REST API v2 + AppAuth / Custom Tabs**
  - *Pros*: Zero external SDK footprint, uses existing OkHttpClient.
  - *Cons*: Must maintain custom token refresh serialization.

#### WebDAV (Nextcloud / ownCloud / Fastmail):
- **Option A: HTTP Basic / Digest Auth with App Passwords**
  - *Pros*: Works with 100% of WebDAV servers, trivial to implement via OkHttpClient interceptors.
  - *Cons*: Requires user to enter credentials directly in the app.
- **Option B: Nextcloud Single Sign-On / Login Flow v2**
  - *Pros*: Opens Nextcloud app or web prompt to generate app token without user typing raw password.
  - *Cons*: Nextcloud-specific; generic WebDAV servers still require Basic Auth.

---

### Decision 2: Conflict Resolution Strategy

When reading positions or bookmarks differ between devices:

- **Option A: Latest Timestamp Wins (LWW - Last Write Wins)**
  - *Pros*: Completely automated; zero user friction during reading.
  - *Cons*: Clock drift between devices could cause modern progress to be overridden by a device with a desynchronized clock.
- **Option B: Maximum Reading Progress Wins (Highest Page / Char Offset)**
  - *Pros*: Safe for linear reading (reading forward is always preserved; you never get pushed back).
  - *Cons*: If a user intentionally re-reads a chapter or book, they might be forced forward again until they reset progress.
- **Option C: Interactive Resolution Prompt (Toast / Dialog on collision)**
  - *Pros*: Absolute user control; prevents accidental data loss.
  - *Cons*: Disrupts the reading flow if conflicts occur frequently.
- **Recommended Approach**: Hybrid: Automate with *Highest Reading Progress Wins* by default, and provide an interactive prompt if the difference is larger than a configurable threshold (e.g. > 10% book distance).

---

### Decision 3: Sync Scope — What Gets Synchronized?

- **Option A: Lightweight Sync Only (Reading Positions & Bookmarks)**
  - *Pros*: Minimal bandwidth (kilobytes per sync), instant updates, works smoothly on metered cellular data, avoids large cloud storage quotas.
  - *Cons*: Requires the user to manually load books onto each device independently.
- **Option B: Full Library Sync (Metadata + Database + Book Binary Files)**
  - *Pros*: True multi-device parity; a book imported on Phone A immediately appears on Tablet B.
  - *Cons*: High bandwidth and local storage usage, requires handling 50MB+ EPUB/PDF downloads in background, possible battery drain.
- **Option C: Tiered Sync (Metadata & Positions synced automatically; Book binaries downloaded on demand)**
  - *Pros*: Best balance: library list and reading positions stay in sync; actual book files are downloaded from the remote provider only when the user taps "Read" or "Download".
  - *Cons*: Needs a "Remote / Cloud" badge in the UI for books whose files are not yet cached locally.
