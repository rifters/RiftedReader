# Backlog — designed or wanted, not yet built

This file is the single visible list of features that have been designed or
wanted but not built. It exists so good ideas stop dying of burial in planning
docs. Rules:

- Present tense only: each entry is a decision waiting to be made, with its
  value, its costs, and its difficulty stated plainly.
- When an item ships, it leaves this file and enters `README.md` /
  `docs/ROADMAP.md`. When an item is rejected, it is deleted, not left to rot.
- Difficulty is judged against this codebase and the minSdk 24 floor, which is
  deliberate and not negotiable per-feature.

---

## FlexPaginator Settings Lock

*Source: `docs/planning/FLEX_PAGINATOR_SETTINGS_LOCK.md` — while reader
settings are open, lock page flips and conveyor buffer shifts so a settings
change cannot collide with slicing.*

- **Pros:** Prevents a real class of state-corruption glitches at exactly the
  moment re-slicing happens (font/size changes). Invisible when it works —
  that is the point of insurance.
- **Cons:** A lock that fails closed feels like a frozen app, which is worse
  than the glitch it prevents. Adds a state machine to the reader's most
  delicate interaction surface.
- **Difficulty:** Medium. The design is written; the risk is in the lock
  lifecycle and its tests.
- **Weight:** Medium. Bundle with other FlexPaginator hardening rather than
  doing it alone.

## FlexPaginator Error Recovery (rollback)

*Source: `docs/planning/FLEX_PAGINATOR_ERROR_RECOVERY.md` — never destroy
working slice state until the new state is verified; roll back on failure.*

- **Pros:** FlexPaginator is now the default engine, so its failure modes are
  every user's failure modes. The one unforgivable failure in a reader is
  losing the user's place or breaking their book mid-read; this is the safety
  net under that.
- **Cons:** Dual slice state to manage; failure paths are the hardest to test
  honestly (requires failure injection, not just happy-path tests).
- **Difficulty:** Medium–Hard. The state lifecycle work is moderate; proving
  it works is the real cost.
- **Weight:** High. The top FlexPaginator item — do before any public release.

## FlexPaginator Progress Overlay (full design)

*Source: `docs/planning/FLEX_PAGINATOR_PROGRESS_OVERLAY.md` — cover-art
overlay showing "window X of Y" during re-slicing. A plain progress bar
shipped instead.*

- **Pros:** Re-slicing after a font change is a visible wait; the designed
  overlay turns dead time into feedback users actually see.
- **Cons:** Pure polish — the progress bar already communicates the essential
  fact. An overlay that appears for a 300 ms re-slice is annoying; needs a
  show-only-if-slow threshold.
- **Difficulty:** Low–Medium. UI layer only; window counts may need plumbing.
- **Weight:** Low–Medium. Cheap garnish; do opportunistically with the
  delay threshold, never as a headline task.

## Reading Time Tracking

*Source: roadmap Optional Features — accumulate per-book reading time and
surface it in the existing Library Statistics screen.*

- **Pros:** The best effort-to-value ratio on this list. Fully specced
  already: one field, accumulation on reader pause/resume, display in a
  screen that already exists. Users love their stats; also feeds future
  reading-goals features.
- **Cons:** A Room migration, and honest session accounting (background /
  TTS-only listening time is a definition decision).
- **Difficulty:** Low.
- **Weight:** High for its size. The classic quick win — take it whenever a
  small slot opens.

## Backup & Restore

*Source: roadmap Optional Features — export library state (database, covers,
searches, preferences, TTS rules) to a single zip; restore on any device.*

- **Pros:** Close to table stakes for a published reader. Device loss or a
  reinstall wiping a library is the kind of loss users do not forgive. Needs
  no account and no cloud, which fits this app's design. Also protects the
  developer's own multi-device testing.
- **Cons:** Restore correctness is unforgiving: overwriting a live database
  corrupts it, and exports must survive app-version changes. The export half
  is easy; the restore half is where the danger lives.
- **Difficulty:** Medium. Well specced; the cost is restore sequencing and
  round-trip tests with real archives.
- **Weight:** High. A release gate, not a nicety.

## On-Device Neural TTS Voices

*Source: long-standing want, never previously staged. The engine seam landed
Nov 2026 (`TTSAudioEngineProvider`, `selectedEngine` preference, inert
sherpa-onnx stub). This entry is the actual engine: Piper/Kokoro-class
voices via sherpa-onnx, downloaded on demand.*

- **Pros:** The differentiator for a listening-first reader — the feature
  this app exists for, done properly. On-device means offline, private, and
  zero per-use cost. The architecture cost is already paid; what remains is
  integration, not redesign.
- **Cons:** Voice models are tens of MB each (download management, storage
  choices). Native library integration carries the 16 KB page-size
  compliance watch-out. Engine switching during playback (hot-swap) becomes
  necessary companion work. Voice quality varies and needs curation.
- **Difficulty:** High, but bounded — sherpa-onnx has known Android
  integration paths.
- **Weight:** Highest strategic value on this list. Deserves its own
  planning pass, the way Stage 8 got one — not a squeezed-in task.

## Word-Highlight TTS (Karaoke Mode)

*Source: roadmap Optional Features — highlight the exact spoken word in the
reader view during playback.*

- **Pros:** The signature feature of premium TTS readers. Sentence tracking
  already exists; word level is the visible upgrade, especially for language
  learners and reluctant readers.
- **Cons:** The platform catch: word-range callbacks (`onRangeStart`) need
  API 26+, and this app's floor is API 24 — deliberately. Options are a
  gated feature (26+ only, fragmenting the older-phones-first promise) or
  timing estimation (fragile, visibly wrong often enough to cheapen it).
  Mapping utterance ranges onto sliced FlexPaginator pages is also genuinely
  fiddly.
- **Difficulty:** Hard, for this floor and this pagination system.
- **Weight:** High appeal, high friction. Deferred until the floor moves or
  a credible API 24 approach exists. Do not fake it with timers.

## Project Gutenberg + Standard Ebooks Browser

*Source: roadmap Optional Features — in-app browsing/downloading of free
public-domain books (Gutendex API; Standard Ebooks OPDS feed).*

- **Pros:** Free, legal content inside the app is a retention driver and a
  real differentiator — Librera does not have it. The Standard Ebooks OPDS
  feed doubles as the first real OPDS client work, which is runway for
  Stage 8 instead of a detour from it.
- **Cons:** A mini-app inside the app: browse, search, network failure
  handling, download integration. Gutendex is a third-party service outside
  our control. Scope-creep risk is real.
- **Difficulty:** Medium–High.
- **Weight:** Medium–High. Strong second-act project after core hardening;
  Standard Ebooks first, because it dogfoods the OPDS design.

## Offline Dictionary Lookup

*Source: roadmap Optional Features — long-press a word, get a definition
from a bundled/offline dictionary.*

- **Pros:** Baseline expectation in the Kindle/Kobo class; genuinely useful
  for students and language learners; fully offline fits the app.
- **Cons:** A bundled dictionary adds 10–30 MB to the APK (a downloadable
  pack avoids that, at the cost of a second moving part). Word-selection
  plumbing through the WebView is a real chunk of UI work. Dictionary data
  licensing needs care.
- **Difficulty:** Medium.
- **Weight:** Medium. Worthwhile after the flagship and safety items;
  prefer a downloadable pack over bundling.

## Home Screen Widget

*Source: roadmap Optional Features — current book cover, progress, and a
one-tap Resume.*

- **Pros:** Resume-in-one-tap is a proven engagement pattern; cover and
  progress infrastructure already exists.
- **Cons:** Widgets are a maintenance tail of launcher quirks and
  RemoteViews limits, disproportionate to their size.
- **Difficulty:** Low–Medium.
- **Weight:** Medium–Low. A fine post-release small project; not before.

## Bionic Reading

*Source: roadmap Optional Features — bold the first half of each word as a
visual focus aid. (Its sibling from the same entry, custom font import,
already shipped.)*

- **Pros:** Cheap to build, highly visible, some readers swear by it.
- **Cons:** The benefit evidence is thin and marketing-driven. It is a
  *visual speed-reading* aid in a *listening-first* app — the audience
  overlap is small. Must be applied only to the visual layer so markup never
  leaks into TTS text.
- **Difficulty:** Low, with one fiddly boundary (TTS separation).
- **Weight:** Low for this app's identity. Skip unless users ask for it by
  name.

---

## Suggested order

1. **Error Recovery** — safety net under the now-default engine (bundle the
   Settings Lock with this hardening pass).
2. **Reading Time Tracking** — the quick win, any time a small slot opens.
3. **Backup & Restore** — before any public release.
4. **Neural TTS Voices** — the flagship; give it a dedicated planning pass.
5. **Gutenberg + Standard Ebooks** — second act; OPDS runway for Stage 8.
6. Then, opportunistically: Progress Overlay, Dictionary, Widget.
   Bionic Reading only on user demand. Karaoke waits on the API floor.
