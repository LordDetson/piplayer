# PiPlayer features and roadmap (draft)

Status: draft v0.1, 2026-10-18, pending owner review.

This document lists what PiPlayer will do, in which milestone each feature lands, and what it deliberately leaves
out. Track identification is specified in [track-identification.md](track-identification.md); references such as
"TI §4.2" point to its sections and are not repeated here.

## Product goals

1. **A real everyday player for local music.** Reliable background playback, gapless albums, a library that is
   usable within seconds and stays correct, and system controls (notification, lock screen, headsets, Bluetooth car
   stereos) that simply work; Android Auto follows soon after 1.0.
2. **Identification is the main feature.** A file with a meaningless name becomes a correctly described track with
   cover art, after the user has seen the candidates and every proposed change (TI §1 to §6).
3. **Trust.** Results are saved in PiPlayer by default; files are written only on request, verified and undoable;
   every value shows where it came from (TI §7).
4. **Correct non-Latin libraries.** Locale-aware sorting, search that ignores case, diacritics and the difference
   between ё and е, readable legacy-encoded tags.
5. **Maintainable by one developer.** Standard Android and Media3 building blocks, few settings with good defaults,
   no custom audio engine, and a 1.0 scope with an explicit cut line (see Milestones).
6. **Free and private.** GPL-3.0-or-later, no ads, no paid features, donations only through an external link
   (TI §12 D1, D2). Online lookups are opt-in and disclosed (TI §9), there is no analytics, and the permission set
   is minimal.

## Non-goals

- A custom native audio engine, own output drivers, own USB audio drivers (direct USB host access), an app volume
  that replaces the system volume, native DSD output (DoP or raw DSD) or PCM-to-DSD conversion.
- Tracker module formats (MOD, XM, S3M, IT).
- Third-party skins or theme packages, visualization preset engines, downloadable native plugins.
- An own lock screen, screen overlays, changes to system settings, privileged-permission workarounds, vendor hooks.
- An exported library database or settings provider for other apps; other apps use the standard media session.
- All-files storage access (TI §7.3).
- Streaming services, cloud and network library sources, internet radio in 1.0.
- In-app purchases, ads, analytics or crash-reporting SDKs, numeric audio quality ratings.
- Writing anything into a file without the user's confirmation.

## Features

Tags: `M0` to `M5` are milestones of version 1.0 (see Milestones; M2 and M4 each have parts a and b), `v1.x` follows
soon after 1.0, `later` is planned without a date. The items listed under Cut line move to `v1.x` if 1.0 slips.

### Playback

- One Media3 player inside a media library service, so every system surface sees the same session. `M1`
- Formats through platform decoders: MP3, AAC/M4A, FLAC, Ogg Vorbis, Opus, WAV, AMR/3GP, Matroska and WebM audio. `M1`
- Seeking in MP3 files by the header's table, by an index built while reading where the header has no table, and by
  the bitrate otherwise; estimated seeking in raw AAC (ADTS) files and in raw AMR files of one mode (playback.md
  §1.3). `M1`
- Gapless playback; the whole queue lives in one player playlist that the playback service builds. `M1`
- Audio focus: pause for other players; hold playback during calls and other short breaks and resume after them;
  lower the volume for short prompts. `M1`
- Pause when wired headphones are unplugged or a Bluetooth output disconnects. `M1`
- Headset and Bluetooth buttons: play/pause, next, previous; a double press skips forward. `M1`
- Background playback that survives screen off; after the process is killed, the queue and position are restored. `M1`
- Device sound effects and system equalizers attach to PiPlayer's audio session. `M1`
- An Equalizer entry that opens the device's effect panel for PiPlayer's audio session, shown only when the device
  has one. `M1`
- Shuffle, repeat off/all/one; previous returns to the start of the track after its first few seconds, otherwise
  to the previous track. `M1`
- Sleep timer with an optional "finish the current track" mode and a short fade-out. `M1`
- "Stop after current track" toggle, independent of the sleep timer. `M1`
- Shuffle keeps "play next" items next (own shuffle order). `M2a`
- A basic equalizer on the platform effect attached to PiPlayer's session, for devices without an effect panel;
  bands and quality depend on the device. `M5`
- ReplayGain from tags: track, album and automatic modes, preamp, peak protection, Opus R128 values. `v1.x`
- Speed and pitch control, optional silence skipping. `v1.x`
- A-B repeat: loop a section of the current track between two marks. `v1.x`
- Fades on play, pause and seek. `v1.x`
- Own equalizer: 10-band graphic EQ, bass and treble, presets, parametric text import, headroom and limiter. `v1.x`
- Car and headset command filters (stray play commands after connecting, triple press) with a command log. `v1.x`
- Optional resume when headphones or a Bluetooth output connect while PiPlayer is running. `v1.x`
- Bundled decoders for lossless formats the device cannot play, ALAC first. `v1.x`
- Opt-in loudness analysis (EBU R128) in the background; album gain grouped by identified release. `v1.x`
- More lossless and niche formats decoded to PCM (APE, WavPack, TTA, Musepack, TAK, WMA, DSF/DFF). `later`
- Crossfade that never fades inside continuous albums, using release data. `later`
- Parametric EQ editor, per-device presets, headphone correction downloads, spectrum view, waveform seek bar. `later`
- Bit-perfect USB output through the official platform API on Android 14+. `later`
- CUE sheets, internet radio. `later`

### Library

- Library built from the system media index, usable within seconds of the first launch. `M2a`
- Background tag pass with PiPlayer's own reader, whose values then replace the index values everywhere; results
  are applied in batches with stable list positions and a visible progress line, so lists do not jump while the
  user browses. `M2a`
- Exact duration measured by the background tag pass and shown in lists and track details, also for VBR MP3
  without a header. `M2a`
- Full tag model: multi-value artists and genres, album artist, sort names, disc and track totals, dates and
  original dates, MusicBrainz IDs, ISRC, label, catalog number, barcode, ReplayGain values, embedded cover
  details. `M2a`
- Library folders screen: include or exclude folders. `M2a`
- Automatic refresh when files change; manual refresh, full tag re-read and a rebuild that keeps user data. `M2a`
- Ringtones, notification sounds, recordings and very short files hidden by default. `M2a`
- Stable track identity independent of tags, names and the media index, so user data survives moves and
  writes. `M2a`
- Browse Songs, Albums, Artists and Folders, built as the media library tree that car displays reuse later. `M2a`
- Play a folder together with all its subfolders. `M2a`
- Album grouping by release ID when known, folder-aware fallback, compilations, multi-disc albums with
  headers. `M2a`
- Locale-aware sorting using sort tags; per-list sort field, reverse order, list or grid view. `M2a`
- A draggable scroll thumb with a letter or year bubble on long lists. `M2a`
- Search over titles, artists, albums, composers, genres and file names, ignoring case, diacritics and the
  difference between ё and е. `M2a`
- Queue: play next, add to queue, drag to reorder, remove; tapping a track plays the visible list from it. `M2a`
- Browse Genres and Playlists. `M2b`
- Legacy tag encodings (for example Windows-1251) shown correctly, with an automatic mode and a manual
  choice. `M2b`
- Playlists: create, rename, reorder, import and export M3U8; entries keep resolving after files move. `M2b`
- Smart lists: Favorites, Recently added, Recently played, Most played, Songs missing information. `M2b`
- "Songs missing information": no title or a file-name title, no artist or album, no cover, no MusicBrainz
  IDs. `M2b`
- A favorite toggle on Now Playing and in the track menu. `M2b`
- Favorite albums: the same toggle on album pages and a Favorite albums list. `M2b`
- Play statistics counted from time actually listened. `M2b`
- Multi-select for queue and playlist actions; identification and tag actions appear as their milestones
  land. `M2b`
- Composers and Years, folder tree, an A to Z side index, choice and order of top-level tabs. `v1.x`
- Nested browsing by genre, then artist, then album. `v1.x`
- Artist splitting by user-chosen separators, with exceptions. `v1.x`
- Star ratings with half stars, resume position for long tracks, delete to the system trash, share files. `v1.x`
- Rule-based smart playlists, duplicate finder by recording, bookmarks, extra folders from the system
  picker. `later`

### Player UI

- Compose app with Material 3 following the system's light or dark theme, dynamic color on Android 12+. `M1`
- A choice of light, dark or system theme in the settings. `M2a`
- Edge-to-edge layout with correct insets, and system back including predictive back, on every screen as required
  by targetSdk 36. `M1`
- Content labels and semantic actions on every new control from the first screen. `M1`
- Now Playing: cover, title, artist, album, seek bar with remaining time, controls, a format line. `M1`
- Top app bar with a search action, Library tabs and the mini player. `M2a`
- A thin progress line in the mini player. `M1`
- A track's own embedded cover is shown for that track when it differs from the album cover. `M2a`
- Bottom bar with Library, Search and Fix, once the Fix tab exists. `M4a`
- Track menu: play next, add to queue, go to album, artist or folder (`M2a`); add to playlist, favorite and
  details (`M2b`); identify (`M3`) and edit (`M4b`) entries appear with their milestones. `M2a`
- Track details: values, technical data, cover size, IDs as links. `M2b`
- Source of each value and per-field revert in track details. `M3`
- Artist and album on Now Playing open their pages; the current track is marked in lists. `M2a`
- Onboarding with permission explanations; empty states for no permission, scanning and no music. `M2a`
- Metadata badge on Now Playing and in details: from tags, identified (source and confidence), saved in PiPlayer,
  written to file, needs review (states appear as their milestones land). `M3`
- Cover gestures (swipe to skip, swipe down to close), each with a visible button and an accessibility
  action. `M5`
- Adaptive layouts for landscape, tablets and foldables. `M5`
- Accessibility audit with TalkBack and large font scales; confidence always as text, never by color alone. `M5`
- English and Russian at launch, per-app language, layouts ready for right-to-left scripts. `M5`
- Lyrics view, synced and plain, with tap to seek and timing offsets (global, per track, per output). `v1.x`
- Home screen widget, configurable player buttons, seek back and forward buttons on long tracks. `v1.x`
- Cover-tinted background, pure black theme, colors from the current cover. `v1.x`
- Additional player layouts, shared-element transitions, Quick Settings tile, pinned shortcuts. `later`

### System integration

- Media notification, lock screen and system media controls with skip, shuffle and repeat. `M1`
- Playback resumption from system media controls and Bluetooth after the app was closed. `M1`
- Open audio files from file managers and other apps and play them without adding them to the library; such files
  stay in a restored queue only while access lasts, otherwise they are skipped with a note. `M1`
- Watches, headsets and car stereos get title, artist and, where the device supports it, cover through the media
  session. `M1`
- Minimal permissions, each requested at first use and explained in the app. `M1`
- System surfaces show PiPlayer's own metadata (its tag reading, later identification results). `M2a`
- Car displays (Android Auto) with browsing and search, voice requests, a cover provider for external
  hosts. `v1.x`
- Configurable extra buttons for the notification and the car (favorite, seek back 10 s, seek forward
  10 s). `v1.x`
- Share target for audio files that opens identification; launcher shortcuts. `v1.x`
- Opt-in status broadcasts (track and play state only, no library data) for older scrobbling apps. `later`
- Built-in ListenBrainz scrobbling with recording IDs; a permission-gated automation API if users ask. `later`
- Casting, only if a GPL-compatible way is found (decision pending). `later`

### Metadata and identification

- Local covers: embedded art, folder images, generated placeholder; one resolver for lists, player and session;
  list covers come from system thumbnails. `M2a`
- Single-file identification from the track menu, Now Playing and track details (TI §1 to §4). `M3`
- Identify a file opened from another app; the result is kept in PiPlayer's database by content key until the file
  appears in the media index or in a granted folder (TI §1, §3.1). `M3`
- Edition picker listing every edition in the release group (TI §4.2). `M3`
- Review screen: per-field diff, field and cover checkboxes, snippet playback, manual search, confidence as a
  percentage with a short reason (TI §6). `M3`
- Save to PiPlayer's database with provenance and undo; every view updates at once (TI §7.1 and the planned
  changes below). `M3`
- Online covers from Cover Art Archive for identified albums and albums tagged with release IDs (TI §5). `M3`
- Cover picker: release images, other editions, embedded and folder images, an image from the device. `M3`
- Batch identification for a selection, a folder or "Songs missing information", saved in PiPlayer until the user
  chooses to write tags (TI §8). `M4a`
- Fix tab: review queue, needs-attention list, counters and ETA. `M4a`
- Optional tag writing with partial updates, verification, backup and undo that survives restarts, through
  MediaStore write requests with one consent per apply (TI §7.2 to §7.4). `M4b`
- Optional media management access on Android 12+, so applying a batch skips the confirmation dialog
  (TI §7.3). `M4b`
- Manual tag editor for one file on the same pipeline, with or without writing to the file. `M4b`
- Change history per file and per batch, with revert. `M4b`
- Folder grants for background tag writing, with a health check, re-grant and write-access status per folder
  (TI §7.3). `v1.x`
- Lyrics: embedded, .lrc files in granted folders, LRCLIB on request (TI §9). `v1.x`
- Convert legacy-encoded tags to Unicode as a reviewed fix. `v1.x`
- Batch tag editing, tags from file names, "Albums missing covers" batch, cover.jpg in the album folder (needs a
  folder grant). `v1.x`
- Artist, genre and playlist images derived from album covers. `v1.x`
- Rating tags; MusicBrainz genres (CC BY-NC-SA) behind a setting (TI §1, §9); contributing fingerprints with the
  user's own key (TI §12 D5). `later`

### Settings and data

- Local crash capture, offered for sharing on the next start. `M1`
- Settings: Library folders, Playback, Appearance, Identification and privacy, Storage and write access, About;
  sections fill in as their milestones land. `M2a`
- Optional own AcoustID application key (TI §9). `M3`
- Android backup and device transfer of user data (decisions, edits, favorites, playlists, statistics); the
  rebuildable library database and caches are excluded, and restored data is re-attached to files by content
  key. `M5`
- Report a problem: copyable text with device and versions, optionally an identification trace. `M5`
- About: version, source code, licenses, data source attribution (TI §9), privacy policy, donation link. `M5`
- Opt-in update notice in the GitHub build only. `M5`
- Export and import of user data as a versioned JSON file that merges with existing data. `v1.x`
- Storage screen that clears caches but keeps identification results and undo history. `v1.x`
- Diagnostics screen with media command history. `v1.x`
- Settings search and per-screen reset. `later`

## Milestones

Sizes are rough estimates of part-time work by one developer who is new to Android: S up to 2 weeks, M 3 to 6
weeks, L 7 to 12 weeks. They are re-estimated after M0 and again after M1. Together they add up to roughly a year.

Between milestones, 0.x pre-releases on GitHub put the app into real use: after M2a a player without online
features, after M3 and M4a identification with results saved in PiPlayer only. Public pre-releases get online
identification only after AcoustID and MetaBrainz confirm the terms in writing (TI §9), or with a separate test
application key.

### M0 Project skeleton and identification spike

Size: M (about 4 to 6 weeks).

- Gradle project with Kotlin, Compose, Material 3 and Media3; minSdk 30, targetSdk 36 (TI §7.4).
- One app module with packages for UI, playback, library, tagging and identification, plus one native module for
  the TagLib and Chromaprint JNI code; split into Gradle modules later only if build time or boundaries need it.
  Each main-process component (the media session, WorkManager, Room, DataStore) checks the process name when it
  starts, so they exist only in the main process; `:tagger` runs native calls only (TI §7.2, playback.md §1.5).
- Native spike: TagLib 2.3.2 and Chromaprint built with NDK r28+, 16 KB page alignment checked in CI (TI §3.2,
  §12 D4).
- Identification spike: a debug-only screen opens a file through the system picker, fingerprints it with the
  native Chromaprint build, queries AcoustID and MusicBrainz with the real User-Agent and rate limiter, and shows
  the raw candidates.
- Text-search check in the same spike: the stage 4 query builder runs on the JVM against the owner's 1 476-track
  liked list (no device needed), and the share of correct top results is recorded (TI §3.3).
- Room spike on API 30: search over normalized columns with LIKE on a generated 20 000-row table; FTS4 only if
  that is too slow. Done with Room 3.0.3: instr() per word over one normalized search column, through an index on
  (title key, search text), answers every search-as-you-type query within 13 to 33 ms (95th percentile, two emulator
  runs) on the API 30 emulator; LIKE takes 38 to 96 ms there, because the platform's LIKE goes through ICU. No
  full-text index and no bundled SQLite. To be measured again on the slowest phone in M2a; if it is too slow there,
  the fallback is the bundled SQLite with an FTS5 trigram index, since FTS4 cannot find the middle of a word.
- CI: build, JVM tests, lint, a check that only the intended components are exported.
- Strings in resources from the first screen, with English and Russian resource sets.
- Done when a debug build runs on API 30 and 36 emulators and on a 16 KB page-size emulator image, the
  fingerprints of the bundled samples are within the bit-error threshold of desktop fpcalc, TagLib reads the tag
  fixtures, and the owner's own files return results whose correctness is recorded (planned for 30 files; the owner
  closed the check at 11, and the larger corpus check stays in M3).

### M1 Playback core

Size: L (about 8 weeks).

- Media library service, notification, lock screen controls, media buttons, audio focus, wake lock.
- Edge-to-edge layout and system back with predictive back on every screen; content labels and semantic actions
  on every new control.
- A temporary song list from the media index, Now Playing and the mini player.
- Starting playback from a list sends a compact request (list identity, sort and start index) as a single media
  item; the playback service expands it in the session callback (onSetMediaItems / onAddMediaItems). The UI never
  builds thousands of media items.
- Queue persistence, playback resumption, gapless, open-with, the system effects session and the Equalizer entry,
  the sleep timer and "stop after current track", the mini player progress line, local crash capture.
- Done when, on the owner's phone and an API 30 emulator, an album plays gapless with the screen off, survives a
  call and an unplugged headset, restores its queue and position after a killed process, and resumes from the
  system media controls; wired, Bluetooth and call checks run on the phone. Gapless is judged on a fixture: one
  continuous piece split into tracks encoded as MP3 (LAME), AAC with gapless info, FLAC and Opus. A 20 000-item
  queue starts within about 1 s, and the notification, lock screen and a Bluetooth controller stay responsive.

### M2a Browse and play

Size: L (about 10 weeks).

- Data model with two database files (rebuildable library data and user data), tracks separate from files, stable
  identity (TI §3.1, §10).
- Media index sync, the background tag pass, Songs, Albums, Artists and Folders as the media library tree,
  grouping, sorting, the scroll thumb, search, queue editing with its own shuffle order, local covers including a
  track's own cover, playing a folder with its subfolders, library folders, onboarding, the settings skeleton with
  the theme choice.
- Done when the owner uses PiPlayer as the daily player for a week, and a generated 20 000-track library (a script
  that creates short tagged files with varied and Cyrillic names) is browsable within seconds of the first launch
  and scrolls smoothly on a named device (the slowest one available); a search for "ежик" finds "Ёжик" and
  "Beyonce" finds "Beyoncé".

### M2b Library data

Size: M (about 5 weeks).

- Playlists with resolution hints, smart lists, favorite tracks and albums, statistics, legacy encodings, track details,
  multi-select, the Genres and Playlists categories.
- Done when favorites, playlists and statistics survive a rebuild of the media index and a deletion of the library
  database, and the legacy-encoding fixtures show readable text, on the same named device.

### M3 Identification (single file)

Size: L (about 8 weeks).

- TI §3 to §6 and §7.1 for one file, with the online lookup opt-in and disclosure (TI §9).
- Rate limiters and caches (TI §8), provenance, the metadata badge, undo of database-only changes, and an immediate
  refresh of the playing item and system surfaces after Apply or Undo.
- Corpus check and threshold tuning (TI §11) on the owner's own files.
- Done when the TI §11 JVM tests for the filename parser, request builder, scoring, release ranking, rate limiter
  and cover URLs pass, the fingerprint test passes, Apply followed by Undo restores the earlier state, and on at
  least 100 of the owner's own files the high band holds no more than 2 % wrong matches (threshold set by the
  owner); the shares of high, medium and not found are recorded.

### M4a Batch identification saved in PiPlayer only

Size: M (about 5 weeks).

- TI §8 phases (a) to (c): local scan and fingerprints, network lookups, review; the Fix tab and the bottom bar;
  batch progress notifications. Accepted results are saved in PiPlayer only.
- Done when a 500-file batch started from a folder runs without audible effect on playback, resumes after its
  process is killed in any phase, and its accepted results appear in every view and on system surfaces.

### M4b Tag writing, history and undo

Size: L (about 8 weeks).

- Tag writing through MediaStore write requests (one consent per apply); if the process dies during apply, the
  batch resumes after a new consent from the UI. The write-access interface exists from day one; its folder-grant
  implementation follows in v1.x (TI §7.2 to §7.4).
- History and undo, the manual tag editor, deferred writes for the file that is playing, optional media management
  access.
- Done when the TI §11 tag-diff and tag round-trip tests pass on API 30, 33, 35 and 36, and a 500-file apply
  resumes correctly after its process is killed. The write pipeline is tested automatically on app-private
  fixtures; consent dialogs follow a written manual checklist on API 30, 33, 35 and 36.

### M5 Polish and first release

Size: M (about 6 weeks, plus at least 14 days of closed testing).

- Gestures, adaptive layouts, the basic platform equalizer, the accessibility audit and translation pass, About,
  problem reports, backup rules, the opt-in update notice, startup and list performance work.
- Re-check thresholds on the full corpus (TI §11), privacy policy, store data-safety and foreground-service
  declarations, a fresh check of third-party terms (TI §9), a check that Google Play's payments policy allows the
  donation link in the Play build (TI §12 D2).
- Developer identity verification (also needed for APKs installed outside Google Play); a Play closed test with
  the required number of testers and days (12 testers for 14 days under the 2025 rules; check the current rule),
  recruited from GitHub pre-release testers; store listing.
- Release: a signed APK on GitHub Releases first, then Google Play. IzzyOnDroid (picks up APKs from GitHub
  Releases) and later F-Droid are channels that suit a GPL, ad-free, privacy-first app.
- Done when all TI §11 tests pass on API 30, 33, 35 and 36, no data loss occurs in round-trip and process-kill
  tests, and the high-band error rate stays within the owner's threshold.

### Cut line

If 1.0 slips, these move to v1.x and the release goes ahead: M3U8 import and export; smart lists beyond Favorites
and Recently played; cover picker sources other than release and embedded images; image from the device; tablet
and foldable layouts; device-transfer re-attachment by content key; batch change history beyond per-file revert;
the basic platform equalizer; optional media management access. Background (folder-grant) tag writing is already
planned for v1.x.

### After 1.0

v1.x, in planned order (items moved by the cut line come first):

1. Car displays (Android Auto), voice requests, the cover provider and configurable notification buttons.
2. Lyrics (TI §9) with timing offsets, together with folder grants: their health check and per-folder write
   status, background tag writing, .lrc files and cover.jpg in the album folder.
3. ReplayGain from tags, speed and pitch, silence skipping, then fades.
4. Own equalizer with bass and treble, presets, text import and limiter.
5. Home screen widget, configurable player buttons, launcher shortcuts, share target.
6. User data export and import, storage screen, diagnostics with the command log.
7. Bundled lossless decoders, loudness analysis, batch tag editing, Unicode conversion of legacy tags, derived
   images, theme additions, command filters, resume on connect and the remaining library additions.

Open decision for the owner: move the own equalizer up to item 2, or accept that devices without an effect panel
have only the basic platform equalizer in 1.0 (and none if it falls below the cut line).

Later: crossfade, CUE sheets, internet radio, casting (only if a GPL-compatible way is found), more lossless and
niche formats, parametric EQ and headphone presets, bit-perfect USB output, rule-based smart playlists, duplicate
finder, rating tags, status broadcasts, ListenBrainz scrobbling, waveform seek bar, spectrum view, additional
player layouts.

## Changes made to the identification spec

These followed from the feature plan and were folded into TI v0.2 (2026-10-18); see its "Changes in v0.2" list and
§12 decisions D6 to D10:

- A content key computed from audio data only, so tag edits by PiPlayer or other apps do not change it; PiPlayer
  re-checks it in the same transaction after each of its own writes; Ogg files use a bounded page walk (§3.1, D10).
- Exact duration measured once by the background tag pass is the single value for AcoustID and the player (§3.1).
- Legacy encodings decoded at stage 0 before text search and diff; an ID3v1 policy (§3.1).
- Two database files. The library database holds everything that can be rebuilt from the media index and file
  tags (files, tracks, albums, search columns, API and release caches) and is excluded from backup. The user
  database holds identification decisions, edits, favorites, playlists, statistics and undo metadata and is backed
  up whole. Rebuilding the library means deleting the library database; user data is not touched. A few user
  fields (such as favorites) are mirrored into the library database at start-up and on change, for sorting and
  lists (§10).
- Field precedence: user edit, then confirmed identification, then file tags, then the media index, then a
  file-name guess. Rows per field and source are kept only for sources other than file tags and the media index,
  whose values live in typed track columns (§7.1, §10).
- Every surface (lists, search, notification, lock screen, car, widget) reads the merged values, and the playing
  item is refreshed after Apply and Undo (§7.1).
- Undo also for changes saved only in PiPlayer; a field is flagged if the file changes after a database-only
  decision (§7.1).
- After a verified write the database is updated from the re-read file; the change observer and index sync
  recognize PiPlayer's own writes and keep values and provenance (§7.2, §10).
- Only the main process writes to the database; `:tagger` returns results over IPC in batches well under the
  binder transaction limit, without cover bytes (§7.2, §10).
- A file that is playing or preloaded is written only after a pause and re-prepare, or later (§7.2).
- One write-access interface from day one, with the MediaStore write request implementation in 1.0 for every
  interactive and user-started batch write; the folder-grant implementation follows in v1.x. A batch interrupted
  during apply resumes after a new consent from the UI (§7.3, §7.4, §8).
- Reading .lrc, cover images and playlists needs the same folder grant; an explanation before system write and
  trash dialogs (§7.3).
- Entry points (files from other apps, multi-select, the Fix tab), criteria for "Songs missing information",
  remembered Keep as-is and Skip decisions (§1, §2).
- Prefer the release already chosen for other files in the same folder (§4.2).
- One decoding service for fingerprints and analysis, with PCM format conversion, downmix and anti-aliased
  resampling (§3.2); snippet playback that does not disturb the main player (§6).
- Extra fields (artist lists, sort names, compilation flag, disc subtitle) and per-format rules for multi-values,
  dates, track positions and front covers (§1, §5, §7.2).
- Protected data: loudness tags, ratings, statistics and lyrics frames are never changed by identification (§6).
- A per-file identification trace and backup rules per database file in the data model (§10).
- Batches that never disturb playback: low priority, one decoder while music plays, pause on underruns (§8).
- Notification permission before the first batch, an unmetered-network option, disclosure for every online
  service (§8, §9).
- The corpus check moves to the single-file milestone and is repeated before release; storage-flow tests run
  automatically on app-private fixtures, with consent dialogs on a manual checklist (§11).
- Own names for the edition picker and its default entry, and for undo that survives restarts; naming consistent
  with this document: American spelling, Now Playing, track details instead of the file info screen (§1, §4.2,
  §6, §7.2).
