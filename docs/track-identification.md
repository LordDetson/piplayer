# Track identification: specification (draft v0.2)

Status: draft v0.2, 2026-10-18.

Changes in v0.2:

- Stage 0: a content key from audio data only, with a fixed byte layout, a re-attachment order and its limits; one
  exact duration with header checks; legacy encodings decided per file; an ID3v1 policy (§3.1).
- One decoding service on Media3's extractors, with PCM conversion, downmix and anti-aliased resampling; a retry
  policy for empty AcoustID results (§3.2).
- Text search with ordered query variants and a stop rule (§3.3).
- Entry points, criteria for "Songs missing information", remembered Keep as-is and Skip decisions, extra fields
  (§1, §2).
- A starting formula for recording confidence (§4.1).
- Release choice: album consistency, a complete type order, one release per album group, an edition picker over the
  whole release group, own names for the picker and its default entry (§4.2, §6).
- Covers: release details and covers in their own subsections, front cover only, a cover chain for releases with and
  without their own front image, a failure cache, stored accepted covers, a cover picker, lazy loading (§5).
- Snippet playback that pauses and resumes the main player without changing its queue, position or statistics;
  protected data, including gapless info (§6).
- Field precedence with the media index as a temporary source, merged values on every surface through a wrapped
  session player, undo for changes saved only in PiPlayer with rules for stacked decisions, the "changed in the file"
  flag per file location (§7.1).
- Write pipeline: free-space check, a verified working copy, a journal entry before the original is touched,
  recovery of interrupted writes, separate commits per database file, recognition of PiPlayer's own writes, IPC
  rules for `:tagger`, deferred writes for the playing file, undo that survives restarts (§7.2).
- Tag writing in 1.0 only through MediaStore write requests; the folder-grant implementation follows in v1.x (§7.3,
  §7.4).
- Per-format rules and explicit TagLib calls (new §7.5).
- Batches: WorkManager jobs per album group, a foreground service for apply, network constraints, pauses for
  underruns and heat, notifications, an unmetered-network option (§8).
- Per-flavor AcoustID keys and User-Agent contact, a rule for public pre-releases, the GitHub Releases API,
  disclosure of every online service and of problem and crash reports (§9).
- Two database files with a fixed user-data layout and a size budget, the place of settings, folder rules and the
  queue, backup rules, a per-file identification trace (§10).
- Tests and the corpus check mapped to milestones (§11); D4 to D10 decided (§12).

Based on API/library research verified against official sources on 2026-10-18; statements marked "verify" are
checked in the milestone named with them or, without one, before the feature ships. Re-check every third-party term
right before the first Google Play release. Milestones (M0 to M5, with M2a/M2b and M4a/M4b), the tags `v1.x` and
`later`, and the cut line are those of [features.md](features.md); items marked "below the cut line" move to v1.x if
1.0 slips.

## 1. Goal

The user picks a local audio file whose name says nothing useful; only its duration, size and maybe partial tags are
known. PiPlayer identifies the recording, finds the right release (album) and fetches full metadata and cover art,
shows ranked candidates with a confidence and a per-field diff, and saves the result after the user confirms:
in PiPlayer's database by default, and optionally into the file's tags with undo.

### In scope for 1.0

- Single-file identification (M3) from the track menu, Now Playing and track details, and for a file opened from
  another app.
- Batch identification (M4a) for a selection, a folder, or the smart list "Songs missing information" (below the cut
  line); results are saved in PiPlayer until the user chooses to write tags.
- Fields: title, artist, album, album artist, artist lists (every credited artist with join phrases, for the track
  and the album), artist and album artist sort names, release date, original release date (shown as original year),
  track/disc number and totals, disc subtitle, compilation flag, label, catalog number, barcode, ISRC, MusicBrainz IDs
  (recording, release, release group, release track, artists, album artists), front cover. The text fields and IDs
  are CC0 core data and may be written to files under the per-format rules (§7.5); a field that a format cannot hold
  stays in PiPlayer only. Covers follow §5.2.
- Save to PiPlayer's database with provenance and undo (M3); optional write to file tags (M4b) through MediaStore
  write requests, with a backup and undo that survives restarts (§7.2 to §7.5).

### Out of scope for 1.0

- Lyrics: v1.x (embedded lyrics, `.lrc` files in granted folders, LRCLIB on request; see §9).
- Background tag writing through folder grants, reading `.lrc` files and writing `cover.jpg` into album folders: v1.x
  (§7.3, §7.4).
- Converting legacy-encoded tags that a change does not otherwise touch: v1.x, as a reviewed fix (§3.1).
- Genres from MusicBrainz (CC BY-NC-SA; behind a setting later, see §9).
- Paid/keyed recognition services (ShazamKit, ACRCloud, AudD), Spotify, Last.fm, Deezer, iTunes, Discogs, Genius,
  Musixmatch (see §9 for reasons).
- Submitting fingerprints or edits upstream (later, with the user's own AcoustID user key / MetaBrainz OAuth; §12 D5).
- Never: starting identification or writes from car displays, watches or media session commands. These screens exist
  only on the phone (rules for drivers, and write requests need an Activity).

### Entry points

| Entry point | Milestone | Notes |
|-------------|-----------|-------|
| "Identify" in the track menu, on Now Playing and in track details | M3 | single-file review (§6) |
| Metadata badge on Now Playing and in track details | M3 | opens the track's identification result or its review |
| A file opened from another app (open-with) | M3 | the result is kept in PiPlayer's database by content key (§3.1), with an identity made at Apply (§10.2), until the file appears in the media index (or, v1.x, in a granted folder); only then can its tags be written (§7.3) |
| "Identify" for a multi-selection | M4a | starts a batch; multi-select itself lands in M2b |
| A folder | M4a | batch over the folder's tracks |
| "Songs missing information" | M4a (below the cut line) | batch over the list; single tracks through the track menu (M3) |
| Fix tab | M4a | review queue, "Needs attention" list, counters and ETA |
| Share target for audio files, launcher shortcuts | v1.x | the share target opens identification |

### "Songs missing information" and remembered decisions

- The smart list (M2b, below the cut line) evaluates the merged values (§7.1). A track is listed for one or more of
  these reasons, which the precedence resolver stores in `track_display` (§10.1):
  - **Title from the file name**: no title, or the merged title comes from the file-name guess (precedence level 5),
    or from the media index (level 4) and equals the display name without its extension.
  - **No artist**, **no album**.
  - **No cover**: no embedded cover (from the tag pass), no saved cover, and no thumbnail for the track's album (one
    `ContentResolver.loadThumbnail` probe per album, cached in the library database and repeated when the album's
    files change).
  - **No MusicBrainz recording ID** (neither from tags nor from a confirmed identification). This reason is a filter
    the user can turn on: it is off in the default view, and a batch started from the list leaves such tracks out
    unless the user includes them, because most libraries carry few MusicBrainz IDs.
- Each track shows its reasons, and the list can be filtered by reason.
- **Keep as-is** means the current values are right: the track leaves the list and later batches do not offer it
  again; "Identify" from the track menu still works, and track details can reset the decision.
- **Skip** means "not now": the track stays in the list with a "skipped" mark, and later batches leave it out unless
  the user includes skipped tracks. A Skip lapses once the tag pass finds that no file of the track holds the size and
  modification time it was made with any more, as after a tagger changed the file or its audio was encoded again; the
  decision stores that size and modification time (§10.2).
- Both decisions are stored by track identity in the user database (§10.2) and mirrored into `track_display`
  (§10.3), so they survive moves and library rebuilds, and both can be undone (§7.1). Keep as-is also survives
  restores; a Skip survives a restore while the restored files keep their size and modification time (details below).
- Details (decided in M3):
  - The Keep as-is or Skip of a track is the newest of its Keep as-is and Skip decisions, by row ID, that is neither
    undone nor lapsed: a later Keep as-is replaces a Skip and the other way round, and undoing the newer one brings the
    older one back. Apply, Edit fields and a per-field revert neither end nor start one, since Keep as-is and Skip are
    about the list, not about the values.
  - Keep as-is leaves the track out of the list and out of its count, whatever its reasons. Skip leaves it listed, with
    "Skipped" after its reasons in its row.
  - A Skip stores the size and the modification time of the file it is made on. Each time the tag pass has stored a
    batch, every Skip that counts is checked for the files of the batch, those it could not read included: the Skip of
    the track a file belonged to before the read (as `decided_mirror` shows it), the Skip of the track it belongs to
    now, and the Skip of a track whose location hint is the file's place (since the sync forgets the key of a changed
    file, a whole run may have cleared its rows before the pass reads it). A Skip lapses when no file of its track holds
    the stored size and modification time any more, and no file without a content key yet holds them either: such a file
    may be the skipped one, as while a rebuilt library reads a copy before the skipped file, or when the skipped file's
    read failed. So a file changed by a tagger lets it lapse (the sync forgets what the pass read of a file whose size
    or modification time changed, and the pass reads it again), and so does a new content key, since the file then
    leaves the track's key; a byte-identical copy that appears, a move, a full read of the library and a library rebuild
    do not.
  - A Skip stores a stamp, not a location, so the rule is about the track's files, not about the one the Skip was made
    on, and two cases follow from it. A copy that kept the skipped file's size and modification time (copied with its
    times preserved, or restored from a backup) keeps the Skip after the skipped file changes, until that copy changes
    or leaves the library too. And once the skipped file has left the library (its volume removed, its folder
    excluded, or the music copied again with new times, as onto a restored device), the Skip lapses as soon as the pass
    reads or fails to read another file of the track. Skip only means "not now": either way the track is offered again,
    or stays marked, until the user decides again.
  - When the checks after a batch fail (the user database busy during a backup, say), the batch's files are checked
    again a few seconds later, at most three more times, with the comparison of §7.1, and their mirror run waits for
    them, since it replaces the rows that name a file's Skip before its read. So does every whole run (§10.3) asked for
    while any files wait for their checks, such as the one the attach pass after the batch asks for: it runs after
    the checks made again, as it runs after a batch's checks that succeed. After the last failure the files are
    mirrored without the checks, and the whole runs no longer wait; a later change of a file checks it again.
  - A lapse sets the decision's `lapsed_at`, raises the change counter in the same transaction and is mirrored for every
    file of the track, as a decision is (§10.3). Keep as-is never lapses.
- What M2b builds of this (PIM-37, library.md §17.1): the list, its five reasons as filter chips (title, artist and album
  on by default, cover and MusicBrainz ID off), each row with its reasons, and the list as a queue request. Decision: **No
  cover** in M2b means no embedded cover and no own cover of the track or its album; the album thumbnail probe comes
  with the batch over the list (M4a), and until then the cover reason is off by default, since a folder image counts as no
  cover. Keep as-is, Skip and `missing_info_decision` come with M3 and M4a. Decision: a file the tag pass has not tried
  yet has no reasons and is not listed; a file it tried and could not read keeps the index's values for good, and its
  reasons come from them, with the level 4 title rule above, an index album equal to the folder's name counted as no
  album, and no cover and no MusicBrainz ID (library.md §17.1).

## 2. User stories

1. As a listener, I open "Identify" on a file named `track_07.mp3` and see "Artist - Title (Album, 1997)" with a
   confidence of 96%, the cover, and what will change. I tap Apply.
2. As a listener with a messy folder, I start a batch. Confident matches are prepared automatically, doubtful ones
   wait in a review queue, unknown files are listed as "needs attention". Nothing is written until I confirm.
3. As a careful user, I can see the old and new value of every field, untick fields I want to keep, choose another
   edition of the album, play a snippet to make sure it is the right song, and revert any change later.
4. As a listener, I open a badly named file from a file manager, PiPlayer plays it and I identify it right away; when
   the file later appears in my library, the result is already there.
5. As a listener, once I mark a track "Keep as-is" or "Skip", it does not come back into "Songs missing information"
   or the next batch on its own.
6. As a careful user, I can undo a change saved only in PiPlayer just like a change written into the file, also after
   a restart.

## 3. Identification pipeline

Cheapest and most reliable source first; stop as soon as a stage gives a confident recording.

| # | Stage | Input | Output |
|---|-------|-------|--------|
| 0 | Local read | file URI | existing tags (TagLib, legacy encodings decoded), exact duration, size, content key, embedded cover details; normally already done by the background tag pass |
| 1 | Known IDs | MusicBrainz recording/release IDs in tags | recording + release directly: `/ws/2/recording/{mbid}?inc=artist-credits+isrcs` (a 404 means the ID is unknown and the stage finds nothing); with a release ID too, the release lookup of §5.1, and when that release contains the winning recording it is the suggested edition and the ranking of §4.2 is skipped, otherwise the ranking runs as usual |
| 2 | ISRC | ISRC tag | `/ws/2/isrc/{isrc}?inc=artists` → recordings with their artist credits (a 404 means none; can clash: always check the duration; an artist/title disagreement is shown in the reason, §4.1, which is why the credits are asked for) |
| 3 | Fingerprint | first 120 s of decoded audio (§3.2) | Chromaprint fingerprint → AcoustID lookup → recordings with score |
| 4 | Text search | tags + parsed filename + duration | MusicBrainz Lucene recording search |
| 5 | Release choice | recording MBID(s) | ranked releases (§4.2) |
| 6 | Release details | chosen release MBID | full album data (one lookup per album, cached) |
| 7 | Cover | release / release group MBID | Cover Art Archive front image |

Request budget for one file (M3; MusicBrainz requests are at least 1.1 s apart, §8):

- stage 1: one recording lookup, plus one release lookup when the tags carry a release ID (it also serves stage 6);
- stage 2: one ISRC lookup; stage 3: one AcoustID lookup; stage 4: at most the four automatic variants of §3.3;
- when the winning recording lacks a title or a credit (AcoustID knows some recordings by their ID only), one recording
  lookup for it; never for a recording that stage 1 already looked up, and not for a missing length alone, since every
  MusicBrainz answer gives the same length and a lookup would bring none either (decided in M3 after review);
- stage 5: release browse pages (§4.2) only for the winning recording and for recordings within the band gap (less
  than 0.10 below the best, the gap that keeps the best out of the high band, §4.1) that score at least 0.50
  themselves, at most three recordings in score order, at most 10 pages each, and nothing when the best score is below
  0.50; a failed browse of a recording other
  than the winner is skipped and traced; the browse of a release group waits until the edition picker opens;
- stage 6: one release lookup, for the chosen release only.

So a high-band fingerprint hit costs at most three requests plus the release pages, and a file found by text alone at
most four searches plus one release page and one release lookup when one recording leads by the band gap. Paging
hundreds of releases takes 5 to 10 s, so the progress shows the pages.

**One run (M3).** The pipeline (`identification/pipeline`) runs the stages for one file and gives one outcome. A run
that goes through its stages stores what it found under the file's content key in the library database (§10.1), in one
transaction: its ranked candidates, which replace those of the run before, the trace of its steps, of which the last 3
runs are kept, and the change to the lookup job with its "retry later" time (§3.2). A run that ends before stage 0 or
as "file unreadable" has no content key and stores nothing; a run that a blocked call ends, or that is cancelled
before it stores its rows, stores nothing either, so the rows of the run before stay. A cancellation that comes while
the rows are being written does not stop the transaction: the rows are stored whole and the caller sees the run
cancelled. A lookup job that cannot be read counts as none for what the run sends, and the run then changes the job
only by removing it after an AcoustID answer with results (§3.2), so that it never overwrites a job it could not see.
Rows that cannot be written cost the run nothing: its outcome is reported all the same.

- Before stage 0 the run checks what every client checks before every attempt (§8), in the same order, and stops at the
  first that fails, without reading, decoding or sending anything: the online gate (once the app has read the user's
  choice from the `settings` file, so that a choice not read yet does not count as off), MusicBrainz's stop for the
  process, and the network. The outcomes are "online lookups off", "provider stopped" with the reason, "no network" and
  "sign-in needed" (a captive portal). MusicBrainz's stop ends the run because every result needs MusicBrainz (a
  recording is applied with a release, §4.2 and §5.1); AcoustID's stop only skips stage 3. A call that is blocked in the
  middle of the run (the user turns lookups off, the network goes, MusicBrainz refuses the User-Agent) ends the run with
  the same outcomes, and what the earlier stages found is dropped.
- Stage 0 takes the content key, the tags as the tag pass decoded them, the exact duration, the format and the folder
  from the library when the tag pass has read the file. A length that the pass left for a walk (`pending-walk`,
  `pending-flac-end`) is walked now in `:tagger` by the rules of §3.1, where the FLAC end check keeps the header value
  unless the last frame ends sooner. A library file that the pass has not read, and a file opened from another app, are
  read through `:tagger` first, decoded with the legacy-encoding setting, and the header rules decide whether a walk
  follows. A length walked here serves this run only; the tag pass stores its own. A walk that finds no length leaves it
  unknown. A file that `:tagger` cannot read (gone, crashed or hung the reader, or marked unsafe) has no content key and
  ends the run as "file unreadable", with nothing sent. So does, for now, a file opened from another app whose provider
  hands out a pipe: the content key needs reads at any position, and stage 0 does not copy such a file yet (open in M3;
  §3.2 copies pipes for decoding only).
- Stage 0 writes no tag values, content key or length to the library database; only the count of failed reads of
  §7.2 step 11 (library.md §4.3) changes there, as for every read through `:tagger`. The content key, tags and length
  it reads from a library file that the tag pass has not read (also one whose stored key the pass has not confirmed
  since the file changed) serve this run and the rows of §10.1 kept by that key, so that Apply finds an identity by
  it; the tag pass stores its own values, and writes keys only while it holds the sync. Every read through `:tagger`
  goes through the one guard of the main process, one at a time (library.md §4.3), so stage 0's read of a file never
  overlaps the pass's read of the same file and their counts of failed reads never interleave.
- Stage 1 looks up the recording MBID of the tags, and the release MBID of the tags goes to release choice (§4.2 step
  0). Stage 2 looks up the ISRC. A tag value that is no MBID or ISRC sends nothing.
- Stage 3 runs only in a build with an application key (§9), with AcoustID not stopped and with a known exact duration;
  otherwise it is skipped for that reason, and nothing is decoded or sent to AcoustID. It takes the fingerprint cached
  for the content key and encodes its raw items (§3.2), or, without one, decodes the file on the user's lane (§3.2
  "Threads") and sends the string of the session that computed it; a cache that cannot be read counts as no fingerprint,
  and one that cannot be written does not keep the fingerprint from being sent. A file that cannot be decoded (ALAC
  without a decoder, a pipe past the copy's bounds or one whose writer failed) skips the stage. AcoustID stopping while
  the file decodes (another run's error 4) skips the stage too. After a lookup the AcoustID track ID
  of the best result, the one with the highest AcoustID score, is cached for the content key. An empty result marks the
  file "retry later" (§3.2), and a text-search candidate scoring at least 0.50 takes the mark off again. While the
  retry waits, and once the retry came back empty too, the stage is skipped for that reason ("retry pending", "empty
  result final"), with nothing decoded or sent to AcoustID.
- Stage 4 runs the text search of §3.3 with its hint.
- Stop rule: after each stage that found something, everything found so far is scored (§4.1), and when the best is in
  the high band, the stages after it are skipped. Text search alone never reaches the high band.
- A stage that fails (a provider that stays busy through every retry, an answer that is not the service's, error 4 from
  AcoustID, which also stops AcoustID for the process) is traced, and the next stage runs.
- Then release choice (§4.2) and the release details (§5.1) run on the ranking of everything found, within the budget
  above.
- The outcome: a best score of at least 0.50 gives the ranked recordings, the release choice and the AcoustID track ID,
  in their band. Below 0.50, "unavailable" with the provider when a stage failed, "provider stopped" when AcoustID refused
  the key, "no key" in a build without one (§9), and otherwise no match. A release browse of the winner or a release
  lookup that fails gives "unavailable" too, since the recording cannot be applied without its release.
- Progress names each stage as it starts and, during release choice, the release pages fetched so far for each
  recording. Cancelling the run ends it at its next request or decoder buffer (§3.2): nothing more is sent, and it gives
  no outcome.
- The debug spike (debug builds only) runs the same pipeline behind a gate of its own, open only while a spike run goes
  on, so it keeps its fingerprints and AcoustID track IDs in memory and writes nothing to the library database's
  fingerprint cache: the cache of a debug build holds only what the app's own runs found behind the user's choice. Its
  reads through `:tagger` still keep the read counts of §7.2 step 11, as every read does. Progress of a spike run that was cancelled or
  replaced by another does not change what the screen shows. Its runs store no candidates, trace or lookup job, so
  every spike run asks AcoustID, whatever an earlier run of the spike or of the app found.

### 3.1 Stage 0: local read

**Source of values.** The media index serves discovery and temporary values only; PiPlayer's own reader (TagLib
through JNI in the `:tagger` process, §7.2 step 11) is authoritative once it has read a file (§7.1). The background
tag pass (M2a) performs stage 0 for the whole library; identification reuses its results and reads a file itself only
when the pass has not reached it yet or the file is not in the library (opened from another app). One reader, one
cache and one "unsafe" flag serve the tag pass, identification and writes. The library scan never computes
fingerprints; the only exception is the on-demand matching of orphaned identities (re-attachment step 4 below).

**Content key (audio data only).** The key identifies the audio, not the file, so tag edits by PiPlayer or by other
apps do not change it.

- Input, hashed with SHA-256 in this order:
  1. a key version byte, `1`;
  2. a format token in ASCII followed by a zero byte: `mp3`, `flac`, `mp4`, `wav`, `ogg-vorbis`, `opus`, `adts` or
     `file`;
  3. the length of the audio payload as an 8-byte big-endian integer (Ogg Vorbis and Opus: the final granule
     position instead, as a two's complement number);
  4. the windows of the payload, each of 64 KiB (65 536 bytes):
     - Ogg Vorbis and Opus: the first and the last window, or the whole payload once when it is shorter than
       128 KiB (131 072 bytes);
     - every other token: the first window, the window at offset `(length - 65536) / 2` rounded down, and the last
       window, or the whole payload once when it is shorter than 192 KiB (196 608 bytes).
- The middle window is there because 64 KiB can be very little music: 0.37 s of CD audio in a WAV file, 1.6 to 4 s of
  a CBR MP3. Two different songs of one length with silent ends would otherwise share a key, and with it favorites,
  statistics and decided values. Ogg goes without it: a middle window would cost a walk over the whole file, and
  silence compresses to almost nothing there, so the two windows reach the music.
- The format comes from the file's content, never from its name or from whether TagLib accepted its tags, so a rename
  or a repair of broken metadata does not change a key:
  - `OggS` at the start: Ogg; the first packet tells Vorbis from Opus, and anything else takes `file`;
  - `fLaC`, after a leading ID3v2 tag if there is one: `flac`;
  - `ftyp` at offset 4: `mp4`;
  - `RIFF` and `WAVE` at offset 8: `wav`;
  - otherwise MPEG, when three frames in a row start within 1 MiB after a leading ID3v2 tag. A frame's header says
    whether it is MPEG audio (`mp3`, also for layers I and II) or raw AAC (`adts`).
  - A format whose payload cannot be found, or that turns out empty, takes `file`.
- Audio payload per format:
  - MP3: from the first MPEG frame to the end of the last one, so ID3v2 (with its padding), ID3v1 and APE tags stay
    outside. A frame counts when the next frame's header follows it with the same sync, version, layer and sample
    rate (TagLib's check). The first frame is the first that counts, searched from the end of the first ID3v2 tag.
    Searching back from the end of the file, or from the ID3v1 or APE tag, the frame after the last one that counts
    is the last frame; this is TagLib's `lastFrameOffset()`. The payload ends after that frame's length as given by
    its header, at the end of the file or at the trailing tags at the latest. A first frame that holds the ASCII
    string `Xing`, `Info` or `VBRI` carries counters instead of audio and stays outside, so that rebuilding that
    header does not change the key. Junk between frames stays inside;
  - FLAC: the frames after the last metadata block, up to the ID3v1 and APE tags that some taggers add at the end;
  - MP4/M4A (and 3GP): the contents of the `mdat` atom(s) in file order, cut at the end of the file; the `moov` atom,
    which holds the tags, may sit before or after them;
  - WAV: the first `data` chunk, cut at the end of the file;
  - Ogg Vorbis and Opus: the bodies of all pages after the last header page, without the page headers, whose
    sequence numbers and CRCs change when comments are rewritten (both formats start audio data on a new page:
    Vorbis after its three header packets, Opus after its two);
  - raw AAC (ADTS): from the first frame, so after a leading ID3v2 tag, up to the trailing ID3v1 and APE tags;
  - formats without known payload offsets (for example Matroska/WebM): the whole file, token `file`; PiPlayer writes
    no tags into them (§7.5).
- Offsets come from TagLib in `:tagger` where it exposes them (for example the first and last MPEG frame offsets).
  Where it does not, a walk over top-level structures (FLAC metadata block headers, MP4 atoms, RIFF chunks, Ogg page
  headers) without parsing their contents is enough; no other own parsers.
- The layout never changes between app versions: golden-value tests freeze it (§11.1, M2a), with expected keys from
  an independent reference (`scripts/content_key_reference.py`). A different layout would need a new key version and
  a migration that re-keys every identity.
- Ogg Vorbis and Opus use a bounded page walk (decision D10), so the key reads about as much as for other formats
  (about 215 KB, against 197 KB) and stays the same when another app rewrites the comments. What it hashes does not
  depend on how far it read:
  - the header pages are skipped by adding up their segment tables; their bodies (which may hold a large embedded
    cover) are not read;
  - from the last header page on, only page headers are read until the bodies add up to 128 KiB, which tells a short
    payload exactly, however small its pages; the walk stops at the first thing that is not a whole page;
  - the first 64 KiB of the payload are read from the bodies of the pages that follow the last header page;
  - for the last 64 KiB, PiPlayer reads the tail of the file, accepts a page start only when its CRC matches, skips
    whatever is not such a page, and reads further back until the bodies reach 64 KiB; the final granule position is
    that of the last such page that has one. Over 1 MiB of tail without enough pages takes `file`;
  - when the payload is shorter than 128 KiB, it is hashed whole, and its final granule comes from the page headers;
  - a file with more than one logical stream (chained or multiplexed Ogg) takes the whole-file token `file`, and
    PiPlayer writes no tags into it (§7.5). The walk sees another serial number or a beginning-of-stream flag only
    in what it reads, so a chain whose first and last links share a serial number and that hides the others in the
    middle goes unseen; its key is still stable.
- Not a hash of the whole file: it changes with every tag edit and would require reading the whole library once
  (hundreds of GB of lossless files). The cheap key of v0.1 (size plus the first and last 64 KB of the file) is
  dropped for the same reason: tags sit at the start (ID3v2, FLAC metadata, MP4 `moov` before `mdat`) or at the end
  (ID3v1, APE) of the file.
- Own writes: PiPlayer computes the key of the edited copy and compares it with the track identity before the
  original is touched (§7.2 step 3); a mismatch means the audio bytes changed and stops the write. The verified write
  is recorded in the same user database transaction that checks the key once more (§7.2 step 6).
- Re-attachment of an identity to a file, in this order:
  1. the content key;
  2. the same location (volume, relative path and display name) with a duration within 1 s;
  3. the identity's recording MBID, when exactly one unattached file carries it in its tags and that file's duration
     is within 1 s;
  4. while the library database survives: a file of the same format, with a duration within 1 s, whose fingerprint
     is similar to the one cached for the identity's old key (§10.1), and that is unattached or holds a newer identity
     without user data, which then goes. These candidates are fingerprinted on demand at background priority, as a
     one-off for identities that hold user data and stayed unmatched; this is not part of the library scan (§8);
  5. for playlist entries also their hints (path, name, duration, recording MBID): a missing identity that entries
     refer to takes over the one file that matches a hint by place and duration, by name and duration or by recording
     MBID and duration, when no other missing identity could take it. Like step 4 it runs after the new identities are
     made, so the file already has a new identity; it takes the file only from a new identity that holds no user data,
     which then goes. Byte-identical copies count as one file. An entry never points at a file; an entry without an
     identity is linked to the identity of the file its hints name (library.md §16.2).

  A file is unattached when no identity holds its key; a file whose length still waits for a walk is not matched yet.
  Every file left unattached gets a new identity. Re-attachment runs after media index rebuilds, SD card changes,
  moves, library rebuilds and restores from backup. Favorites, statistics, playlists, identification results and undo
  records depend on it. The attach pass of M2a is described in library.md §8.
- Known limits of the layout, accepted when it was frozen:
  - The same master with a short edit in a place no window covers shares a key in WAV, in CBR MP3 and in Opus, whose
    length field does not see the bytes. Someone must own both editions for this to matter.
  - QuickTime chapter text lives in `mdat`, so editing the chapters of an M4B or M4A changes the key.
  - Lossless gain tools (MP3Gain, foobar2000) rewrite a field in every MP3 frame: the key changes, and undoing the
    gain restores it. Users may see this as a tag edit; re-attachment step 2 recovers the file unless it also moved.
  - A WAV `data` chunk or an MP4 `mdat` that declares an open end takes in whatever is appended later.
- Limits: a library rebuild or a restore leaves no cached fingerprints, so a file whose key changed (its audio was
  re-encoded or trimmed by another app) and that also moved re-attaches only through step 3 or a playlist hint. Otherwise its identity stays unattached and keeps its data until a matching file appears; PiPlayer never
  attaches user data through a weaker guess.
- Byte-identical audio in two places shares one identity, so decided values apply to both copies. Per-location state
  (the file's value at a decision, the "changed in the file" flag, written tags, undo snapshots) is kept per file
  location (§10.2), and a playlist entry plays the first available location.
- A file opened from another app gets the same key, so its result attaches to the file once it appears in the media
  index (or, v1.x, in a granted folder). For the same reason the working rows of identification (the lookup job with
  its "retry later" time, the candidates and the trace) are kept by content key, not by file ID: neither a file opened
  from another app nor a library file that the tag pass has not read yet has a key in the library before stage 0
  computes it (§10.1, decided in M3). A decision needs an identity, so Apply makes one for such a file when none
  holds its key (§10.2 "An identity at Apply", decided in M3).

**Duration.** One exact duration per file, measured once by the background tag pass and stored with its source. The
same value is used for AcoustID (`duration`, rounded to the nearest second), text search (`dur:`), scoring, lists,
track details, the media session and the player's time display (§7.1).

- Taken from the header where it is reliable: FLAC STREAMINFO sample count, MP4 track duration, Ogg final granule
  position (minus the Opus pre-skip), WAV data size, MP3 with a Xing/Info or VBRI frame count. ID3 `TLEN` is never
  used.
- Walked frame by frame with Media3's `MediaExtractorCompat` (the extractors of playback, §3.2), without decoding, in
  `:tagger`, last, at low priority and also while music plays (library.md §7):
  - MP3 without a frame-count header, and raw AAC (ADTS), always;
  - MP3 whose Xing or VBRI byte count differs from the payload length by more than 2 %, and CBR MP3 whose header
    duration differs by more than 2 s from the payload length divided by the first frame's bitrate: VBR headers can
    be wrong by far more than AcoustID's ±7 s window;
  - files whose header value the payload cannot support: a WAV `data` chunk or an MP4 `mdat` atom declared longer
    than the bytes left in the file, or a FLAC file whose last frame (found by the extractor near the end) ends more
    than 2 s before the STREAMINFO length. Ogg's final granule comes from the last page, so a truncated Ogg file
    already reports its real length.
- The player's own duration is not used for this: index seeking in MP3 builds its time map while reading, and raw
  ADTS durations are estimates. (For playback the MP3 extractor runs with index seeking enabled.)
- A file that the pass has not reached yet, or one opened from another app, is measured the same way at stage 0
  before any lookup.

**Legacy encodings.** Text stored as ISO-8859-1 (ID3v1, ID3v2 frames with encoding byte 0, RIFF INFO) is often
another code page in practice, for example Windows-1251. Stage 0 decodes it with the legacy-encoding setting (M2b)
before text search, scoring and the diff, so the "old" column shows readable text.

- The bytes come from TagLib's string handlers for ID3v1, encoding-0 ID3v2 frames and RIFF INFO: during a read they
  mark each byte at or above 0x80 as a private-use character, so the main process decides per file and can decide again
  without reading the file. Vorbis comments, MP4 items and APE tags are always Unicode.
- Automatic mode (default), per file over all its legacy fields together, in this order:
  1. No field holds a byte above ASCII: nothing to decide.
  2. Every such field is valid UTF-8: UTF-8, written into a Latin-1 field by some taggers.
  3. When a device or app language is Russian, Belarusian or Ukrainian, Windows-1251 if its result has more real
     Cyrillic words than words that mix scripts. A real word is a run of letters, all Cyrillic, at least two of them,
     with a vowel or all in upper case; so `é` read as `й` counts for nothing.
  4. Otherwise ISO-8859-1, with Windows-1252's punctuation in the range 0x80 to 0x9F.
- The choice is stored in `audio_file.tag_encoding`. Without the language condition other legacy text would pass the
  rule (`Þú`, `ÅÄÖ`, and GBK or EUC-KR names), so that condition stays; a lone `Я` stays ISO-8859-1, and a Russian
  speaker on a phone with no such language keeps the old text. The manual choice of M2b covers both.
- A manual choice in settings overrides the automatic mode for the whole library (M2b, decided 14.11.2026):
  - The setting is **Automatic** (the default) or one code page. The list, grouped by script: Cyrillic (Windows-1251,
    KOI8-R, KOI8-U, CP866, ISO-8859-5), Central European (Windows-1250, ISO-8859-2), Western (Windows-1252,
    ISO-8859-15), Greek (Windows-1253), Turkish (Windows-1254), Hebrew (Windows-1255), Arabic (Windows-1256), Baltic
    (Windows-1257), Vietnamese (Windows-1258), Thai (TIS-620), Japanese (Shift_JIS, EUC-JP), Simplified Chinese (GBK),
    Traditional Chinese (Big5) and Korean (EUC-KR). These are the single-byte and double-byte code pages that old
    taggers wrote into Latin-1 fields. Android guarantees only UTF-8, UTF-16, US-ASCII and ISO-8859-1, so the decoding
    goes through the platform's `Charset` and a code page the device does not support is not offered; an instrumented
    test checks every entry on API 30 and 36. Windows-1251 keeps the own table of the automatic mode.
  - The choice replaces steps 3 and 4: step 1 stays, and so does step 2, because taggers that put UTF-8 into a Latin-1
    field do so whatever code page the user's other files use, and legacy text is almost never valid UTF-8 by accident
    (a field of only `ВЁ` in Windows-1251 is; such a file needs the per-file choice below). The language condition
    does not apply to a manual choice, and neither does the word rule: a lone `Я` is Windows-1251 once the user says so.
  - A code page of two bytes per character (Shift_JIS, EUC-JP, GBK, Big5, EUC-KR) decodes each run of ASCII and marked
    characters as one byte sequence, because a second byte may lie in the ASCII range (`ソ` is 0x83 0x5C in
    Shift_JIS); a sequence the code page does not know becomes U+FFFD, and the rest of the value still decodes.
  - `audio_file.tag_encoding` holds the code page the file was decoded with (`iso-8859-1`, `windows-1251`, `utf-8` or
    the name of the chosen code page in lower case), so whether a new setting changes a file is known without decoding
    it again.
  - A stored name that is unknown or that the device does not support (a backup restored onto another phone) counts
    as Automatic.
  - A change of the setting decodes the affected files again from their stored raw tags, without reading any file,
    and the lists and system surfaces follow (library.md §5.7). Choosing Automatic again brings back what the
    automatic mode gives from the stored raw tags and the current languages, so a wrong choice is undone by the same
    setting. That is what the first read gave unless the device's or the app's languages changed since, or the file
    holds a legacy value over 16 KiB above ASCII, which the raw tags leave out but the first read's detection saw
    (library.md §5.7); "Read all tags again" gives the first read's result in both cases.
  - A per-file choice is not part of M2b: one setting serves the case that matters, a library written by one old
    tagger in one code page. A choice for a single file comes with the manual tag editor (M4b), where the user sees
    that file's fields; it will be stored as user data by identity, not in `library.db`.
- Writes (§7.2) store changed fields in Unicode and leave unchanged legacy frames as they are, read without marks;
  converting those is a reviewed fix in v1.x.

**ID3v1.** Read only for fields that the ID3v2 and APE tags lack, decoded as above. For writes (M4b) a setting
decides, and the diff shows the ID3v1 change as its own line:

- **Update if present** (default): changed fields that fit ID3v1 (ISO-8859-1, within the field length) are written;
  a value that does not fit leaves the old one in place, and the diff says so.
- **Remove** the ID3v1 tag.
- **Leave untouched.**

PiPlayer never adds an ID3v1 tag to a file that has none; §7.5 lists the TagLib calls that keep it so.

### 3.2 Stage 3: fingerprint and AcoustID

- One decoding service serves fingerprinting and, in v1.x, loudness analysis. Samples are read with Media3's
  `MediaExtractorCompat` (since Media3 1.11 in the `media3-inspector` artifact), so the extractors that parse a file
  for playback also parse it here, and decoded with `MediaCodec` in 1.0; from v1.x the bundled decoders (ALAC first;
  the Android platform has no ALAC decoder) are called through the same service. A "format → extractor and decoder"
  table in code, covered by a test that plays and fingerprints every fixture, makes sure that every format the player
  can play can also be fingerprinted. Since M3 the table is code (`DecoderTable`), one row per codec as the extractor
  names it (its MIME type): MP3, AAC (in MP4 and raw ADTS), AMR narrowband and wideband (raw and in 3GP), Vorbis (in
  Ogg and Matroska), Opus (in Ogg and WebM), FLAC, PCM (in WAV) and ALAC (in MP4). A row gives the route, a platform
  decoder or, for PCM in the container, none, and the rules of its format (the FLAC end-of-stream rules below); a codec
  outside the table takes a platform decoder when the device has one. ALAC's row says that the platform has no
  decoder, so in 1.0 an ALAC file is not fingerprinted and is identified by its tags and text search only; on a phone
  whose maker adds an ALAC decoder it takes the platform route like the others. The decoding service reports the route
  and a decoder path, `media3-<Media3 version>:<MIME type>:<decoder name>` (`container` instead of the decoder name for
  PCM in the container, for example `media3-1.12.0-beta01:audio/flac:c2.android.flac.decoder`), which the fingerprint
  cache keeps in `decoder_path` (§10.1), so a cached fingerprint says which extractor version and which decoder made
  it. The test of §11.2 ("Decoding table") checks every row against a fixture and every fixture against its row. The
  decoder loop takes every free input buffer and every ready output buffer
  without waiting and waits briefly only when neither side moved. Measured in M0 on the API 36 emulator (debug
  build), the two minutes take about 5 s for 48 kHz FLAC and 11 to 15 s for MP3, Vorbis and Opus; nearly all of it is
  the platform's software decoders, while Media3's extractor, the resampler (about 1 s) and Chromaprint (under 0.1 s)
  are fast. The native code is compiled with `-O2` in debug builds too. To be measured on a phone (M0 own-files check).
- PiPlayer opens the file descriptor itself and hands it to the extractor, and keeps it open until the extractor is
  released. (Given a `content://` URI, the extractor treats it as a web address when it cannot open it, which hides
  the real error.) A descriptor that is not a regular file (`fstat` without `S_ISREG`), such as a pipe from a document
  provider, cannot be read at random positions (an MP4 file may keep its index at the end); such a file is first copied
  into the app's cache (`cache/fingerprint-copies`) and decoded from there; the copy's file is deleted as soon as it is
  open for decoding, so nothing stays behind even when the process dies while decoding. The copy is bounded: at most
  1 GiB, never leaving less than 256 MiB free on the cache's storage (looked at again every 16 MiB, since the other
  lane below or another app may write at the same time), and 120 s for the whole copy. It waits for data
  only until that deadline, so a provider that stops writing without closing the pipe cannot hang it. A pipe's end is
  not always a clean one: a provider that hands out a reliable pipe (`ParcelFileDescriptor.createReliablePipe`) and
  then fails, a storage device unplugged in the middle, closes it with an error, which reads like a normal end. So at
  the end of a pipe the copy asks the descriptor whether its writer closed it with an error (`checkError`), and such a
  copy counts as failed. Past a bound, after a failed read or a writer's error, or when the caller cancels, the partial
  copy is deleted and the file is not fingerprinted (its tags and text search can still identify it); a truncated copy
  is never decoded or cached. A partial copy that a process left behind when it died while copying is deleted by a
  later copy once it is an hour old by the file's time; a copy that this process is still writing is never deleted
  that way, even when the wall clock jumps ahead by more than an hour while it runs. A regular file is never copied.
- Threads: fingerprints are computed through one entry of the fingerprint cache with two lanes. Re-attachment (§3.1,
  step 4) queues on one thread of background priority, which also decodes; single-file identification that the user
  waits for has its own thread at normal priority, so the user's request never waits behind queued re-attachment work,
  not even behind the file being decoded. Batches (M4a) are started by the user but not waited for: they never take
  the user's lane; they take the background lane, or one of their own at background priority, under the batch rules
  of §8 (background priority, at most one decoder for the batch while music plays). Requests of one lane start and end
  one after another: a request holds its lane only while it opens, copies and decodes its file, and leaves it before
  the fingerprint is cached, so a request that has decoded completes without waiting for the next one in its lane to
  decode. Both lanes cache the fingerprint by content key with its decoder path; a recomputed fingerprint replaces the
  cached fingerprint, format, decoder path and time but keeps the cached AcoustID track ID (§10.1), since the content
  key names the same audio.
- Decoder output: the decoder is asked for 16-bit PCM, which is all Chromaprint takes, and the request also replaces
  the 24-bit encoding the extractor proposes for 24-bit FLAC. A float request would keep more precision, but on
  Android 13 and later the platform converts the output before it trims the MP3 and AAC encoder delay, and the trimming
  assumes 2 bytes per sample. The platform trims the encoder delay and padding that the extractor reports (LAME or
  Xing header, iTunSMPB, MP4 edit list); the Opus decoder drops the pre-skip itself. PCM stored in the container (WAV)
  skips `MediaCodec`, whose pass-through decoder could mislabel 24-bit and float samples as 16-bit. When the container
  gives no maximum sample size, the decoder's input buffers are made 1 MiB, because the default (32 KiB for FLAC) is
  smaller than the frames of some FLAC files. The bitrate is left out of the format before the decoder is looked up:
  the lookup checks it against the range a decoder declares, and a 320 kbit/s MP3 whose average the extractor works
  out a little above 320 kbit/s (from a LAME Info frame) would otherwise find no decoder at all (four of the owner's
  first eleven files in M0).
- End of the stream: the stream ends where the extractor has no sample left. Media3's extractor up to 1.12.0-alpha02
  does not end a FLAC stream that way (found with 1.11.1 in M0 on the emulators): past the last frame it hands out that
  frame again, with the same time, while `advance()` keeps reporting more samples, and reading such a repeat fails
  inside the extractor once the frame is larger than 64 KiB. 1.12.0-beta01 ends the stream after the last frame (checked
  in M3 on the API 36 emulator with six FLAC decoding fixtures and the two FLAC recordings, which alpha02 all repeated,
  two of them failing inside the extractor), but the rule stays: it changes nothing where no time repeats, and it keeps
  a rollback safe. So for FLAC, a sample with the time of the one before ends the stream before it is read, wherever it
  lies: a file cut off may end long before the length its header states (changed in M2a; the earlier rule looked only at
  the last 2 s of the container's duration). Other formats keep every sample: laced Matroska frames may share a time, an
  MP3 whose sample rate rises steps back in time, and MP4 priming samples have negative times. A decoder that takes no
  input and gives no output for about five seconds fails the file instead of hanging; a decoder error after some audio
  came out (a file cut off in the middle of its last frame) ends the stream there and keeps that audio, but the decoded
  length then no longer counts as the file's length. A decoder in error gives none of the frames it decoded but not yet
  handed out, so the last FLAC frame, which a cut file leaves broken, is queued only once the frame before it has come
  out (or after about two seconds without output, for a decoder that keeps frames back); without that, 1 to 6 of 30
  frames were lost in about a quarter of the decodes on the API 30 emulator, depending on timing (M2a step 14). PCM in
  the container that Android has no encoding for (big-endian, 64-bit float) is refused rather than read as noise.
- PCM conversion: accept every output encoding (8-, 16-, 24- and 32-bit integer, float), average all channels into
  mono, resample to 11 025 Hz with the resampler of speexdsp 1.2.1 (BSD-3-Clause; a Kaiser-windowed sinc filter that
  removes what 11 025 Hz cannot carry before the rate changes; quality 5, float samples, no SIMD), convert to 16-bit
  with rounding and clipping, feed Chromaprint, stop after 120 s of audio. Audio that already has 11 025 Hz skips the
  resampler. The resampler's output starts with the input (`skip_zeros`), and at the end of a file shorter than 120 s
  its tail is flushed, so the output has exactly the input's length at the new rate. With its internal resampler off
  and no FFmpeg, Chromaprint cannot resample, so it is fed 11 025 Hz mono 16-bit PCM (checked in M0: any other rate is
  refused at start).
- Known differences from the desktop `fpcalc`, which decodes with FFmpeg and resamples with swresample: fpcalc mixes
  5.1 into mono with swresample's default matrix (front channels weighted more, the LFE channel left out) where
  PiPlayer averages all channels; an MP3 without a LAME header, or with iTunes gapless data, may start a few
  milliseconds apart; vendor decoders on phones (instead of the platform's `c2.android` ones) may differ in output
  encoding and delay trimming. M3 adds three recordings to the comparison (§11.2): a 5.1 AAC file, a stereo AAC file
  and an MP3 without a LAME or Xing header, made from the freely licensed samples by the preparation script. The run
  on a phone follows with the corpus check (§11.4).
- Chromaprint 1.6.2+ (≥ 1.6.1 is required: older versions drop partial frames between `feed` calls), built from
  source with our own CMake list, KissFFT, no FFmpeg, `USE_INTERNAL_AVRESAMPLE=OFF` (MIT + BSD-3 build), linked
  statically into the JNI library together with speexdsp's `resample.c`; TagLib stays a separate `libtag.so` (LGPL),
  with `c++_shared` for both. 16 KB page alignment, NDK r28+ (M0 uses NDK 30, which aligns by default). Checked in M0:
  on byte-identical PCM the Android build gives the same raw fingerprint as the desktop fpcalc 1.6.2, bit for bit, on
  five recordings of different formats and sample rates. Results are not guaranteed to be identical across ABIs (the
  sinc table and Chromaprint's FFT use each device's math library), so fingerprints are always compared by their bit
  error rate. The JNI library also turns stored raw items into the compressed string that AcoustID takes
  (`chromaprint_encode_fingerprint`, added in M3), so a cached fingerprint keeps its raw items only and the string is
  made from them when a lookup needs it; it equals the string of the session that computed the items.
- Lookup: gzip-compressed form POST (`Content-Encoding: gzip`) to `https://api.acoustid.org/v2/lookup` with
  `client=<app key>`, `duration=<exact duration (§3.1) rounded to the nearest second, as an integer string>`,
  `fingerprint=<compressed>`, `meta=recordings releasegroups releases tracks isrcs compress` (**space-separated**: a
  literal `+` gets encoded as `%2B` and the server then returns no metadata), `format=json`. The server only compares
  fingerprints whose stored length is within 7 s of `duration`, and rejects 0, so a file whose length is unknown is not
  looked up. The key travels in the request body, never in a URL. Without an application key (a build made without
  one, §9) the fingerprint lookup is unavailable: nothing is sent to AcoustID, and text search (stage 4) still runs.
- Answers (checked in M0 against the server's source code): an error comes as `{"status": "error", "error": {"code",
  "message"}}`, mostly with HTTP 400; error 4 (invalid application key) fails without retries and stops AcoustID for
  the rest of the process (§8); error 14 (too many requests, HTTP 429, sent without Retry-After), HTTP 500, 502, 503
  and 504, and the 520 to 524 of the proxy in front of the service (its origin errors) are retried with the backoff of
  §9; other errors are final. The proxies in front of the service may answer with HTML or plain text; such an answer
  counts by its HTTP status: a retried status is retried, and any other status, a successful one included, is final. A
  recording may come back with its ID only (no metadata) and its `duration` is in seconds with a fraction; a result
  may have no recordings at all.
- The fingerprint and the AcoustID track ID of the best result are cached by content key (§10.1); computing the
  fingerprint again does not drop the cached AcoustID track ID; a confirmed decision keeps the AcoustID track ID in the
  user database (§10.2).
- An empty result is not final (server-side search timeouts look like "no match"): the file is marked "retry later"
  and falls back to text search. The lookup is retried once after 1 hour and once in the next batch; after that the
  empty result is final. A text-search candidate scoring at least 0.50 (§4.1) cancels the retry.
  - M3, a single file: the mark is the file's lookup job (§10.1, by content key) with the status `retry_later`, one
    empty answer and a next attempt 1 hour after that answer, by the services' clock. Nothing retries on its own in
    M3: an Identify of the file before that time skips stage 3 ("retry pending") and runs the other stages, and the
    first Identify at or after it asks AcoustID again, once. An answer with results then removes the job; a second
    empty answer makes it `final`, with two empty answers and no time, and later runs skip stage 3 for that ("empty
    result final") until the library database is rebuilt, which drops the job. A run whose stage 3 gets no answer
    (skipped for another reason, stopped early, failed or stopped while the file decodes) leaves the job as it is. A
    text candidate of at least 0.50 keeps the first empty answer from storing a job, and in a later run removes a job
    that waits for its retry, so the next Identify asks AcoustID as if for the first time; a final job stays.
  - A next attempt more than 1 hour after the moment stage 3 checks it can only come from a clock that ran ahead when
    the job was stored (before network time, say) and was set back since, so it counts as due: a wrong clock never
    holds the retry back for longer than the hour.
  - A run reports the retry time, and the review states it, only when the run stored the job, or tried to store it,
    or skipped stage 3 as "retry pending". A job that could not be written still reports its time, as the outcome
    stands when the run's rows cannot be written (§3 "One run"); the next Identify then asks AcoustID, as no job holds
    it back. A run that waited for nothing (a match in the high band before stage 3, a retry that was due but got no
    answer, a job that a text candidate removed) reports none, so the review never shows a time that has passed.
  - M4a, batches: the next batch that takes the file asks AcoustID once more, also for a job that the single-file
    retry made final; after that the empty result is final for batches too.
- `batch` (several fingerprints in one request) is meant for clients, as AcoustID confirmed in writing on 2026-11-05:
  batch mode (M4a) may send 10 to 20 fingerprints per request, never more, because larger requests time out.
  `maxdurationdiff` is a legacy flag that will likely be removed or replaced, so PiPlayer does not use it.

### 3.3 Stage 4: text search fallback

- The search input (the hint) is assembled per field from stage 0 data. Title, artist and track number come from the
  tags as decoded at stage 0 (after the legacy-encoding setting, §3.1) and, for a field the tags leave empty, from the
  file-name guess below; the album comes from the tags only; the duration is the exact duration of stage 0 (§3.1), never
  a tag or media index length. A tag value without a letter or digit (`-`, `?`) counts as empty, so the file name fills
  that field. The hint's album and track number feed scoring (§4.1), not the query. The file-name parser is the same one
  that gives precedence level 5 (§7.1), so tuning it also changes what the library shows.
- Parse the filename (`NN - Artist - Title (Remix)`, `Artist_-_Title`, track-number prefixes, `[bitrate]` noise,
  etc.). Tag values are used as decoded at stage 0 (§3.1). The traces of video downloaders are dropped too (added in
  M0 after the owner's first files): a video ID in square brackets (`Title [dQw4w9WgXcQ]`) or joined by a hyphen
  (`Title-dQw4w9WgXcQ`), counted as an ID only when its 11 characters look random (a digit, `_`, `-`, or a capital
  inside the word); an `audio only` marker (`Title_audio only_[ID]`, `(audio only)`, `- audio only`); a converter
  site in front (`y2mate.com - Title`); a bitrate at the end without brackets (`Title_128kbps`).
- Queries, in this order, with Lucene specials escaped and `AND video:false` added to each; variants that need a
  missing value are skipped, and the search stops at the first variant that yields a candidate scoring at least 0.50
  (§4.1):
  1. `recording:(<title>) AND artist:(<artist>) AND dur:[<ms-5000> TO <ms+5000>]`;
  2. the same without the duration clause (trimmed files, wrong lengths in other sources);
  3. title and artist words with fuzzy matching (`~`), with the duration clause;
  4. `recording:(<title>)` with the duration clause, for files whose artist is missing or doubtful.
- Tuned in M0 on the owner's 1 476-track list: every word of the song's name is required in the `recording` clause
  (`+word`), while a version description (also one written without brackets, such as "Song Radio Edit", which is put
  into brackets before searching and scoring) and a featured artist stay optional. On a 295-track sample this raised
  the correct top results from 68 % to 72 % and the share of correct ones among candidates scoring at least 0.50 from
  74 % to 91 %, at the cost of more "not found" results and about 1.7 instead of 1.35 requests per file.
- Query details checked in M0 against the MusicBrainz search server: words inside `field:(...)` are joined by OR, so
  the field clauses are joined with `AND`; words are sent in lower case, because upper-case `AND`, `OR` and `NOT` in
  a title would be read as operators; the fuzzy variant uses normalized words and allows one edit (`~1`) in words of
  five or more letters; `dur` is in milliseconds, and a duration range leaves out recordings without a length, which
  variant 2 still finds. Every kind of space counts as a space: stores often put a no-break space between a song's
  name and its version ("Girlfriend", no-break space, "Radio Edit").
- `limit=25` for automatic queries, `limit=50` for manual search. The album tag feeds scoring (§4.1), not the query.
  Free text typed by the user goes through `dismax=true`: it is sent as one query marked as manual, with its spaces
  collapsed and without Lucene escaping; blank text sends nothing.
- A MusicBrainz link or ID pasted into manual search is not searched: it becomes a lookup. Links to a recording or a
  release on `musicbrainz.org` or one of its subdomains (such as `beta.`), over `http` or `https` or without the scheme,
  also `/ws/2/` links and links with a query, a fragment or more path after the ID (`/release/<mbid>/disc/1`), give a
  recording or release lookup; the ID is compared in lower case. A `://` inside the query or fragment of a link without
  the scheme (`musicbrainz.org/recording/<mbid>?returnto=https://...`) does not make it a scheme, and a label glued to
  the link without a space (`Link:https://musicbrainz.org/...`, `Link:musicbrainz.org/...`) is not part of it. After a
  scheme the host ends at an optional port, so `https://example.com:musicbrainz.org/...` is a link to another site. A
  bare MBID could name either, so it is looked up as a recording first and then as a release. Links and IDs are also
  found among other pasted words, without the brackets, quotes or punctuation around them; a recording or release link
  wins over a bare MBID, which wins over another MusicBrainz link. Any other `musicbrainz.org` link (artist, release
  group, work, track, a search page, a malformed ID or escape) is refused with a hint to paste a recording or release
  link, and is never sent as search text. A link to another site is plain text.
- MusicBrainz's own `score` is never shown as confidence (it is relative and full of ties).
- The query builder is first checked in M0, on the JVM, against a list of real track titles (§11.4); the variants and
  their order are tuned there and in M3.

## 4. Scoring and release choice

### 4.1 Recording confidence (0 to 1)

Tiered, not a flat weighted sum. A candidate found by several stages (recordings are the same when their MusicBrainz
recording IDs are) takes its highest score, and its reason lists every stage that found it. Before scoring, a field that
one stage's copy of the recording lacks (AcoustID knows some recordings by their ID only, without a title, credit or
length) is taken from another stage's copy, the strongest stage first; a copy keeps the fields it has. So the candidate
shown, its reason and the duration credit use what any stage knows (decided in M3 after review). The weights, credits and
penalties below are starting points, tuned in M3 on the corpus (PIM-60, §11.4).

1. **Identifier tier**:
   - MusicBrainz recording ID from the file's tags, with the recording length within 7 s of the file's duration: 1.0;
     a recording outside the window, or one whose length is unknown on either side, is scored by the similarity tier
     instead (a copied or wrong ID would otherwise reach the high band unchecked);
   - ISRC hit with the recording length within 2 s: 0.95; if the ISRC maps to several recordings, each is scored by
     the similarity tier instead, and so is a single recording outside the window or without a length on either side;
   - AcoustID result: its score × the duration credit; a recording without a length keeps its score (AcoustID
     only matches fingerprints whose length is within 7 s of the file's anyway). An AcoustID track without any linked
     recording is not a candidate, because there is nothing to apply.
2. **Similarity tier** (text search, and recordings of stages 1 and 2 that do not qualify for the identifier tier):
   0.4 × title similarity + 0.3 × artist similarity + 0.2 × duration credit + 0.1 × album/track agreement, capped at
   0.85, so text alone never reaches the high band.
3. **Penalties**, applied after the tier score. The duration penalty applies to every tier: a duration difference over
   30 s multiplies the score by 0.3. The title/artist penalty applies to the similarity tier only: a title similarity of
   at least 0.9 with an artist similarity below 0.5 subtracts 0.3 (only when the file has an artist). Without a duration
   on either side there is no duration credit and no duration penalty. Decided in M3: an identifier hit whose title or
   artist disagrees with the file's values keeps its tier score, and the disagreement is shown in its reason instead.
   People identify files because their tags are wrong, and a correct fingerprint of a cover version would otherwise be
   demoted. The same holds for an ISRC hit: a reused or wrong ISRC is caught by the 2 s window and by the fallback for
   an ISRC with several recordings, and a single recording of another song within the window shows its disagreement.
   The corpus run (PIM-60) checks this against the high-band error target, with fingerprint and ISRC errors counted
   separately. With today's windows (7 s and 2 s) and duration credit (0 from 30 s), the duration penalty never changes
   an identifier score; it stays in the rule so that tuning the windows or the credit cannot drop it.

Definitions:

- Duration credit: 1 up to a difference of 2 s, falling linearly to 0 at 30 s.
- Similarity: Levenshtein ratio on strings normalized like search (case, diacritics, ё and е; Cyrillic, kana, Han and
  Hangul letters are kept whole; Latin letters without a decomposition take their plain spelling: ø as o, ł as l, đ and
  ð as d, æ as ae, œ as oe, ß as ss, ı as i, þ as th, ħ as h, ŧ as t, ŋ as ng; halfwidth kana become full kana).
  Bracketed suffixes at the end of a title (remix, live, edit; ASCII or full-width
  brackets) are compared separately: a suffix on only one side lowers the title similarity by 0.2, two different
  suffixes by 0.2 to 0.4. A featured artist or a remaster note in brackets is not a suffix, and brackets earlier in the
  title belong to the name, as in "(Don't Fear) The Reaper".
- Title refinement (added in M0): the whole titles, names and brackets together, are compared too, and that ratio counts
  when it is higher, at most 0.8 (the price of a suffix on one side). It covers titles that split differently, such as
  "Song (Part) Radio Edit", which has no trailing group, against "Song (Part)", which has one.
- Artist refinement (added in M0): the artist similarity is the best of three ratios: the file's artist against the
  whole credit as printed (names joined by their join phrases), against the first credited name (for names that contain
  a separator, such as "Above & Beyond"), and the file's first artist against the first credited name (for a file that
  lists several artists). The file's first artist is the text before the first `,`, `&`, `;` or a word `feat`, `ft` or
  `featuring`; "Feather" stays whole.
- Album/track agreement: 1 when a release of the candidate matches the album tag (similarity at least 0.9) and the
  track position equals the track number tag, 0.5 when one of the two matches, 0 otherwise or without these tags. The
  track position is the position on the medium (`position` where the answer has it, otherwise the track offset + 1),
  never the printed `number`, which can be "A4" on vinyl or continue across discs.

**Reason.** Each candidate carries a structured reason, which the review card shows (§6), for example "fingerprint 0.97
· duration ±1 s · title 92%": the stage whose tier gave the score and whether that was the identifier or the similarity
tier, every stage that found the recording, the AcoustID score when the fingerprint found it, the duration difference
(the recording's length minus the file's duration, unknown without both), the title and artist similarity to the
file's values (unknown when the file has no such value), and which of the two disagree (similarity below 0.5).

Bands:

| Score | Meaning | Single mode (M3) | Batch mode (M4a) |
|-------|---------|------------------|------------------|
| ≥ 0.90 and runner-up gap ≥ 0.10 | high | pre-selected, user taps Apply | prepared automatically, still shown in the summary before saving |
| 0.50 to 0.90, or ≥ 0.90 with a runner-up gap below 0.10 | medium | candidate list, user picks | review queue |
| < 0.50 | no match | "Not found" + manual search | "Needs attention" list |

The runner-up gap is the best score minus the second best after merging; a single candidate's runner-up counts as 0.
The boundaries belong to the higher band (0.90 with a gap of exactly 0.10 is high, exactly 0.50 is medium), and scores
are compared with a tolerance of 10⁻⁹ so that floating-point rounding never moves a boundary case.

Thresholds are starting points. They are tuned in M3 on the corpus (§11.4), where they drive pre-selection, and
re-checked before release (M5); in M4a they also drive automatic preparation.

### 4.2 Release (edition) choice for a recording

0. When the file's tags carry a release MBID and its release lookup (§5.1) shows that the release contains the
   recording, that release is the suggested edition and steps 1 and 2 are skipped for it: ranking would replace a
   correctly tagged edition. A tagged release without the recording, or one MusicBrainz does not know, is ignored and
   the ranking runs as usual (decided in M3).
1. Get all releases of the recording with browse requests:
   `/ws/2/release?recording=<mbid>&status=official&inc=release-groups+media+labels&limit=100`
   (page by the number of releases actually returned: the next offset is the previous one plus the releases received,
   until the reported release count is reached or a page comes back empty). Fall back to non-official only if nothing
   is left: when the recording has no official release, the same browse without `status`. The ranking runs over every
   page, up to 10 pages (1 000 releases); beyond that it ranks the releases it has and the trace records that the list
   was capped (decided in M3). The browse names neither the track nor its medium, so the keys below look at the whole
   release.
2. Rank by these keys, in order:
   1. a release already confirmed for other files in the same folder (album consistency). Decided in M3: a release
      counts when a file of the folder shows it as its release MBID, decided at level 1 or 2 or else from its tags (a
      decided empty value hides the tags' one), so a folder tagged by another tool counts as confirmed too. The file
      itself counts as well, since the hint knows only the folder: a file identified again keeps its folder's edition
      first. A last folder named like a disc counts with its parent folder, as album keys do (library.md §6.2). A file
      outside the library has no folder and no hint;
   2. agreement with the file's existing tags: the album tag matches the release title (similarity at least 0.9),
      and the track count of one of the release's media equals the track total tag; a release that agrees on both
      ranks ahead of one that agrees on one, which ranks ahead of one that agrees on none (a missing tag agrees with
      nothing);
   3. release type, by this rank (with several secondary types, the lowest-ranked one counts; "any type" includes a
      release group without a primary type; Soundtrack with a primary type other than Album ranks as that type alone,
      but never ahead of rank 4; a secondary type MusicBrainz adds later ranks with rank 10):
      1. Album without secondary types;
      2. Single without secondary types;
      3. EP without secondary types;
      4. Album + Soundtrack;
      5. Broadcast or Other without secondary types;
      6. any type + Remix, Mixtape/Street or Demo;
      7. any type + Live;
      8. any type + Compilation;
      9. any type + DJ-mix;
      10. any type + Spokenword, Interview, Audiobook, Audio drama or Field recording, and releases without a type;
   4. format of the first medium: Digital Media and CD rank equal, ahead of other formats; CD includes its MusicBrainz
      variants (SHM-CD, Blu-spec CD, HDCD, Enhanced CD, Copy Control CD, 8cm CD and the like), and a medium without a
      format counts as another format;
   5. country: the user's country (from the locale), then worldwide (`XW`), then the others; every release event's
      country counts, not only the first;
   6. date: earlier first, releases without a date last; a year alone sorts before full dates of the same year (the
      dates are compared as written); then the release MBID, so the order is stable.

   Track durations are not part of the ranking: after the release lookup (§5.1) the track's length on the release is
   compared with the file's, and a difference over 7 s is shown in the review.
3. Do **not** simply take the oldest release (vinyl first pressings can have other lengths and covers). Store the
   release group's first release date separately as "original year".
4. If the recording appears on several tracks of the release, take the one whose position on its medium matches the
   file's track number tag, otherwise the first in disc and track order.
5. Batch mode: one release per album group (§8; files without an album tag are looked up one by one and grouped
   afterwards): the release that contains the most recordings of the group, ties broken by the ranking of step 2.
   Files whose recording is not on it get their own best release, and the album card in the review shows a split
   warning; so do files of one folder that end up on different editions.
6. The review screen pre-selects the top-ranked edition, labeled "Suggested edition". The edition picker ("Choose
   edition") lists **every** edition in the release group, loaded when the picker opens:
   `/ws/2/release?release-group=<mbid>&inc=media+labels&limit=100` (paged as in step 1, all statuses, official
   first), with type, format, country, date, label and track count, the suggested one first and the others in the
   order of step 2, official ones first. The group's browse has the page cap of step 1 (10 pages, 1 000 releases); when
   the cap stops it, the picker says that its list is partial and shows the group's release count (decided in M3 after
   review). An edition that is not among the recording's releases from step 1 uses
   another recording of the song or lacks it: choosing it loads its tracklist (§5.1) and maps the file to the track
   with the same position and a similar title (title similarity of §4.1 at least 0.9), whose recording ID then
   replaces the identified one. The position is the disc and track position of the identified track on the suggested
   edition, or the file's track number tag on the first disc when that is unknown. A loaded edition that turns out to
   contain the identified recording uses it as in step 4. If no track qualifies, the edition is marked "does not
   contain this song" and cannot be chosen.

## 5. Release details and cover

### 5.1 Release details

- One lookup per chosen release, cached by release MBID for the other tracks of the album:
  `/ws/2/release/{mbid}?inc=recordings+artist-credits+labels+release-groups+isrcs&fmt=json` (no `genres`). The cache
  is read with the MBID as asked for in its plain form (trimmed, lower case); a lookup that answers with another ID
  (MusicBrainz has merged the tagged release into another) is kept under both IDs, so the other tracks of an album
  tagged with the old ID find it too (decided in M3 after review).
- Fields taken from it: the artist credits of the track and of the release (every credited artist, the name as
  credited, join phrases), sort names from the credited artists, the medium title as disc subtitle, track and disc
  positions from the medium and track `position` (never the printed `number`), totals from the medium's track count
  and the number of media, label, catalog number, barcode, ISRCs, the release track ID. The compilation flag is set
  when the release is credited to the special Various Artists artist (MBID
  `89ad4ac3-39f7-470e-963a-56509c546377`). Writing follows §7.5.
- Details of the extraction (decided in M3): each credit keeps the name as credited, the artist's own name, its MBID,
  its sort name and its join phrase; the credit as printed joins the credited names with their join phrases, and the
  sort credit joins the sort names the same way (the credited name stands in for a missing sort name). A blank medium
  title gives no disc subtitle. Labels and catalog numbers keep every distinct value in the release's order;
  MusicBrainz's `[none]` catalog number and an empty barcode (a release known to have none) give no value. The ISRCs
  are those of the track's recording. The track is chosen as in §4.2 step 4. The release date and the release group's
  first release date (the original year, §4.2 step 3) are kept as written. The track length is the release track's
  length, otherwise the recording's; when it differs from the file's exact duration by more than 7 s, the review
  shows it (§4.2). The release's `cover-art-archive` object (front, darkened, count) is kept for §5.2.
- The release cache sits behind an interface, so the same lookup serves every track of the album: the app keeps it in
  the `release_cache` table of §10.1, where a lookup stays for 30 days; JVM tests use one in memory.

### 5.2 Cover

- If the release has a usable front image (`cover-art-archive.front` is true and `darkened` is false): display
  `release/{id}/front-500` (lists: `front-250`), embed from `front-1200`, and fall back to this release's original
  front image when `front-1200` is missing.
- Otherwise use the release group's front image (`release-group/{id}/front-500` and `front-1200`, lists `front-250`,
  the original `front`), marked in the review and in the cover picker as taken from another edition. Without a
  release group ID there is no online cover.
- When the flags are not known, as for the lazy covers below (the tags carry a release MBID and nothing was looked
  up), the release's own images are asked for, and CAA's 404 answers a release without one. An ID that is not an MBID
  gives no address and sends nothing (decided in M3).
- Whenever the release's own front image is asked for and turns out missing (a 404, with the fallback to the original
  below already taken), the release group's front image is asked for next, marked as another edition, when the
  release group ID is known. A 503 of the release ends the attempt without asking the release group (decided in M3).
- Rewrite `http://` to `https://` before the first request (Android blocks cleartext). The rewrite applies to image
  addresses taken from MusicBrainz or Cover Art Archive JSON, such as the links of the release index below, which
  also move onto the base address; an address with any other scheme is dropped. The configured base address is used
  as it is, so tests can point it at a local fake (decided in M3).
- Image addresses of the release index are sent only to Cover Art Archive: an address on `coverartarchive.org` is
  moved onto the configured base address (its path appended to the base's path), an address on the base address's own
  host and port keeps its path on the base address, and an address on any other host is dropped, so no release ID
  reaches a host that §9 does not name (decided in M3).
- A 404 after a thumbnail redirect falls back to the original image: CAA redirects a thumbnail (`front-250`,
  `front-500`, `front-1200`) to the image host, which answers 404 when that thumbnail was never made (older uploads
  have no 1200 px one). The original (`front`) is then asked for in a request of its own, through the limiter. A 404
  from CAA itself, without a redirect to another host, means the release or release group has no front image: the
  original is not asked for. A redirect that stays on CAA (a release group sent on to one of its releases) counts as
  CAA's own answer. For an image of the release index, any 404 of its thumbnail falls back to that image's original,
  which the index listed (decided in M3).
- The release index (M3, for the cover picker): `release/{id}` answers JSON, through a redirect to the image host,
  that lists every image of the release with its ID, the front flag, its types, its comment, the address of the
  original and the thumbnails (250, 500 and 1200 px; the older names `small` and `large` stand for 250 and 500 when
  the numbered ones are missing). A 404 means the release has no images. A body that is not this JSON is retried as
  MusicBrainz's is (§8).
- An image answer is accepted when its body is a JPEG, PNG, GIF or WebP image of at most 32 MiB; its size in pixels
  is read from the image header (JPEG, PNG and GIF; a WebP image has no size until it is decoded). A text page with
  status 200 (a captive portal or a proxy) is retried as MusicBrainz's is; any other body fails the call (decided in
  M3).
- Online covers load only while online lookups are enabled: for identified albums, and lazily for albums whose tags
  already carry a release MBID but have no local cover (M3). A call blocked by the online gate, the provider's stop or
  the network sends nothing and records no failure (§8).
- Failures are cached (`api_cache`, §10.1) so lists do not retry them on every scroll: a 404 for 7 days, a 503 for
  5 minutes, doubling on each further 503 up to 6 hours. Details (decided in M3):
  - Entries are kept per image address. A live entry answers at once without a request. A success removes the entry
    of its address. A 404 replaces a 503 entry, and the doubling counts the 503s of one address in a row, so an
    expired 503 entry still doubles the next one, as long as it expired at most 6 hours ago; after a longer quiet
    time the next 503 starts again at 5 minutes.
  - A 404 entry of a thumbnail sends the next request for it straight to the original. A 404 from CAA itself also
    records the original's address, so neither is asked for again until the entry expires. A live 404 entry of a
    front original answers every thumbnail size of that release or release group without a request, since no
    thumbnail exists without the original.
  - A 503 is not retried in the same call: it pauses the Cover Art Archive limiter with the backoff of §9 (doubling
    on each 503 in a row of that provider, never shorter than Retry-After) and records the failure. A Retry-After
    longer than a retry ever waits (over 120 s) pauses the limiter for the Retry-After, at most 1 hour, and later
    calls fail at once without a request until the pause is over.
  - Network errors and the answers 429, 502 and 504 are retried as for the other providers (§9) and record nothing;
    other error statuses fail the call at once and record nothing.
  - The app holds one client per process, so the 503s in a row and the entries belong to the provider.
  - The entries sit behind an interface: the app keeps them in `api_cache` (§10.1), and JVM tests in memory, keeping
    at most a few thousand addresses.
- Embedded cover presets: **compatible** (baseline JPEG ≤ 1000 px, default), **car stereo** (baseline JPEG 600 px),
  **original**. Never replace an existing cover with a smaller one unless the user asks: "smaller" means a shorter
  short side in pixels, and such a cover's checkbox starts unticked. An equal short side is not smaller. When the
  size of the cover the user sees now is not known (decided in M3):
  - a system thumbnail (its size is not kept) counts as not smaller, so the box starts ticked, also when the
    proposed cover's own size is not known (a WebP image);
  - an embedded picture has its header read on demand, as track details do (library.md §18.3); a header that cannot
    be read counts as no picture, since the user sees none, so the box starts ticked;
  - with no cover now, the box starts ticked;
  - a proposed cover whose own size is not known starts unticked over a cover of known size and over an embedded
    picture whose header is read, since it cannot be shown not to be smaller.
- Only the front cover is replaced: back covers, booklets and other picture types stay untouched. Several front
  pictures in one file are replaced by the one new front image, so one front image remains. MP4 `covr` has no picture
  types: its first image counts as the front and the others stay. The picture's MIME type matches the image data; the
  description is empty (§7.5).
- The "old" cover in the diff is the cover the user sees now: embedded, or the folder image through system
  thumbnails (verify on devices that they include folder images; in v1.x also through a folder grant). PiPlayer does
  not request `READ_MEDIA_IMAGES`.
- The cover picker (M3) offers the release's images and the embedded images; covers of other editions, the folder
  image and an image from the device are below the cut line. The choice is a user decision (§7.1).
- An accepted cover is stored as a file in no-backup app storage (§10), not in the cache, so clearing the cache does
  not lose it; it is not backed up and is re-fetched by release MBID after a restore (§10.3). Details (decided in M3):
  - The file lies in the folder `saved_covers` of the no-backup files, named by the SHA-256 of its bytes in hex with
    the extension of its format (`.jpg`, `.png`, `.gif`, `.webp`); bytes that are no image of these formats are not
    saved. It is written to a temporary file first and then renamed, so a cover file is always whole.
  - Its `saved_cover` row (§10.2) names it with the release MBID, the size in pixels from the image header (none for
    WebP), the number of bytes, the hash and the source. The same bytes for the same release are one row, so the
    tracks of an album that accept its cover share one row and one release choice; the same bytes for another release
    get a row of their own that names the same file.
  - A file that no row names, left by a process that died between the file and its row, is deleted when the saved
    covers are first used in a process. A row whose file is gone (after a restore) stays for the re-fetch and gives
    no file.
- Writing `cover.jpg` into the album folder: v1.x, with folder grants.

## 6. Review UI

- Candidate card: title, artist, album, year, type/format/country, cover with resolution and size, confidence as a
  percentage + color band + short reason (for example "fingerprint 0.97 · duration ±1 s · title 92%").
- Confidence is always shown as text, never by color alone. Reasons are templates whose numbers and partial dates are
  formatted for the locale; the diff reads in a sensible order with TalkBack and becomes one card per field on narrow
  screens and large font scales (audit in M5).
- Per-field diff (old → new) with a checkbox per field and for the cover. A protected-fields list in settings. In the
  manual tag editor (M4b) and for future providers that propose them, user-curated fields (genre, comment) default to
  "fill empty only"; no 1.0 provider proposes them.
- Protected data is never changed by identification and does not appear in its diff: loudness tags (`REPLAYGAIN_*`,
  `R128_*`, `iTunNORM`), gapless info (`iTunSMPB` as an ID3 comment frame or MP4 freeform item, the encoder delay and
  padding in the LAME/Xing header), comment frames with a description (such as `iTunNORM` and `iTunSMPB`), ratings,
  play counts and other statistics, favorites, and lyrics frames (`USLT`, `SYLT` and their equivalents in other
  formats, each with its language and description). Only the manual tag editor (M4b) can change them; writing
  loudness values (v1.x analysis) will be a separate kind of change with its own diff.
- Actions: Apply · Choose edition (the edition picker, §4.2) · Search manually (free text or pasted MusicBrainz
  URL/ID) · Edit fields · Keep as-is · Skip. Apply saves to PiPlayer's database (M3); once writing exists (M4b) the
  card also offers "Also write to file". Keep as-is and Skip are remembered (§1). After Apply, Keep as-is or Skip the
  card offers Undo until the next action (M3, §7.1).
- Snippet: play a 15 s snippet of the file from the card. It uses a separate, short-lived Media3 player without a
  media session and starts at one third of the track (intros are often quiet; tracks shorter than 45 s and tracks of
  unknown length play from the start). The snippet ends after 15 s of playback or at the end of the file, whichever
  comes first. Playing a snippet again while one plays starts the new one at once and keeps what was remembered about
  the main player. The card's progress runs from the snippet's start to its end: 15 s later, or the end of the file
  when its known length comes first.
  - The snippet player is built with `handleAudioFocus = false`: Media3's own focus handling would request a full
    `AUDIOFOCUS_GAIN` for media playback, and the main player would then lose its focus for good.
  - Before the snippet starts, PiPlayer pauses the main player through its MediaController, remembers whether it was
    playing, and requests `AUDIOFOCUS_GAIN_TRANSIENT` for the snippet with an `AudioFocusRequest`. When the snippet
    ends or the card closes, PiPlayer abandons that focus and resumes the main player if it was playing.
  - If the snippet loses focus (a call, another app, the main player started from its notification), it stops, and the
    main player is not resumed. Headphones unplugged during the snippet stop it the same way. A short prompt that
    allows ducking only lowers the snippet's volume until focus comes back.
  - If the focus request is refused (during a call), the snippet does not play and the main player is resumed if it
    was playing. A file the snippet player cannot play, or cannot seek to the snippet's start, ends the snippet as its
    end does.
  - A main player held back by another app's short interruption (a prompt, a call) does not count as playing: the
    snippet leaves it to its own focus handling, which resumes it when the interruption ends.
  - The pause is a hold for the snippet, which the playback service knows of: PiPlayer asks the service to pause the
    main player for the snippet and, when the snippet ends, to end the hold with or without a resume by the rules
    above. During the hold the service treats the main player as playing. A sleep timer (docs/playback.md §6.3) keeps
    its fade or its "finish the track", and its countdown goes on. If its time comes during the snippet, it takes its
    course: in the finish-track mode the track finishes after the resume; otherwise the main player stays paused when
    the snippet ends. Swiping PiPlayer away from recents during the hold keeps the service, as with music that plays,
    and the card closing then resumes the main player.
  - The hold ends without a resume when the main player plays again by other means (its notification, another
    controller) or when the controller that asked for it disconnects; one that ends without a resume after PiPlayer
    was swiped away stops the service as the swipe would have. A session without the playback service's command (a
    test's bare session) gets a plain pause and play.
  - The main player's queue, position and play statistics do not change; its notification keeps showing the main
    track, paused while the snippet plays. With ReplayGain (v1.x) the snippet uses the same gain as playback.
- Batch review in the Fix tab (M4a): one card per album when several tracks share a release; queue counters; ETA.
- Metadata badge on Now Playing and in track details: from tags, identified (source and confidence), saved in
  PiPlayer, written to file, write failed or interrupted, needs review; each state appears with its milestone (M3,
  M4a, M4b).
- Track details show the source of each value, a per-field revert and "Undo identification" (M3).
- Identification and write screens run only on the phone (§1).

## 7. Saving

### 7.1 Database (always)

- The match (IDs, fields, provenance, decision, timestamps) is stored in PiPlayer's user database (§10.2); the player
  shows the merged metadata even if the file is never written. Every decision keeps the provenance of its values:
  provider, provider IDs, fetched_at, license class (CC0 / restricted / unknown).
- **Field precedence**, highest first: (1) user edit (manual tag editor, "Edit fields", cover picker), (2) confirmed
  identification, (3) file tags read by PiPlayer, (4) the media index, (5) a file-name guess.
  - Levels 1 and 2 are rows in the user database (§10.2). Values of level 3 live in typed columns of `track`, level 4
    in `media_index_entry`, both in the library database (§10.1). Level 5 is derived from `audio_file.display_name`
    by the filename parser (§3.3) when the winners are resolved, and is not stored.
  - Level 4 is used only while the tag pass has not read the file (`audio_file.tag_pass_at` is null). Once the file is
    read, an empty level-3 value stays empty and only level 5 can fill it. Media index placeholders such as
    `<unknown>` count as empty.
  - Within levels 1 and 2 the newest decision that is not undone wins per field, whatever its level: an
    identification applied after an edit wins over the edit, and undoing it brings the edit back. Decisions are
    ordered by their row ID, which is the order in which they were committed; the time a decision was made is kept for
    track details and the history and orders nothing, so a clock set back (by hand, by a network time correction, or
    on the device a backup came from) cannot make a new decision lose to an older one. A per-field revert is a decision
    that hides the older level-1 and level-2 values of that field, so the file's value wins again; a newer decision
    wins over it as over any other, and undoing the revert brings the hidden values back.
  - A decided value may be empty, as when an edit clears a field. It wins as any value does, and then only the
    file-name guess (level 5) can fill the title and the artist, as for a read file whose tag is empty; the lists and
    track details show that guess alike.
  - The year that lists show is the year of the date field, or else of the original date (library.md §6.2). While the
    file is unread, the media index's year is the date field's level-4 value: a decided date, also an empty one,
    hides it, and a decided original date does not. A decided original date gives the year of an unread file only
    when neither a decided date nor the index gives one.
  - A field is decided as a whole (decided in M3): a decision stores every part of each field it sets, empty parts
    included, so the parts of two decisions never mix within one field. The fields are those of the protected-fields
    list (§10), and their parts are listed in §10.2: the artist, for example, is its credit as printed, the credited
    names with their join phrases and the sort name, and the MusicBrainz IDs are the recording, release, release
    group, release track, artist and album artist IDs. Apply stores every ticked field this way, empty where the
    chosen release has no value; which fields the review proposes and ticks is decided there (§6).
  - The level of each value is kept per field, so track details can show the source of every value (§6), and the
    reasons of §1 use the decided values: a confirmed recording ID clears "No MusicBrainz recording ID", a decided
    title clears "Title from the file name", and a decided cover counts as a cover.
  - Decided values never make an album key; only a decided release MBID that every file of the album's group ends up
    with changes it, so identifying one track of a tagged album does not split the album (library.md §6.2, decided in
    M3).
  - One repository function resolves the winners and copies them into `track_display`, with the normalized search
    columns and the missing-information reasons (§1), on every change (decision, undo, tag pass, index sync) and at
    start-up when the user database changed since the last mirror (§10.3). The decided values reach it through
    `decided_mirror` (§10.1): a decision or an undo commits to the user database first, then the decided values of
    the track's files are replaced and their display rows derived again in one library database transaction. The
    sync, the tag pass and a new display version join `decided_mirror` on every write of a display row, so none of
    them loses a decided value. A file whose content key the tag pass has just stored gets its decided values in a
    mirror run right after that batch, as favorites do (library.md §15.4). Lists, Now Playing and the media session
    follow a version that every mirror run that changed decided values raises (decided in M3).
- **Merged values everywhere.** Every surface reads the merged values, never raw tags: lists, search, track details,
  Now Playing, the media session with its notification, lock screen, Bluetooth controllers and watches, and in v1.x
  car displays, voice requests and the widget. Track details resolve the fields that `track_display` does not hold by
  the same precedence, per field (library.md §18.2). After Apply and Undo the playing item and the queued items of the same
  tracks are refreshed in place (Media3 `replaceMediaItem`) without restarting playback; verify on devices that the
  player does not prepare the file again.
- **System surfaces.** The player given to the media session is wrapped (a `ForwardingPlayer`, playback.md §2.6) so that its
  metadata is only the MediaItem's metadata built from the merged values, with `artworkUri` pointing to the cover the
  resolver chose (§5.2), and each item's duration is the stored exact duration (§3.1) whenever one is stored.
  Metadata that Media3's extractors read from the stream (tag frames, the embedded picture as `artworkData`) is never
  shown: otherwise fields PiPlayer leaves empty would show raw tags, and the session's bitmap loader would prefer the
  old embedded picture to a saved cover. Checked on the pinned Media3 version in M2a; until the cover resolver arrives
  in step 13 the stream's picture stays, because nothing else would show a cover.
- **Undo for changes saved only in PiPlayer** (M3). Apply and Edit fields store the values they set; Keep as-is and
  Skip store no values. Undo marks a decision undone and re-resolves the winners, so undoing an older decision
  changes only the fields where it still wins: after two identifications in a row, undoing the older one changes
  nothing visible, and undoing the newer one brings the older one's values back. In M3 the review card offers Undo
  right after Apply, Keep as-is or Skip, and track details offer "Undo identification" and a per-field revert; the
  change history per decision and per batch lands in M4b (batch history beyond per-file revert is below the cut
  line). Undo of a change written into the file follows §7.2 step 10. Details (decided in M3):
  - An undone decision stays stored with its `undone_at`, for that history; undoing it again changes nothing, and
    there is no redo: applying the same result again makes a new decision.
  - Undo and the decision it undoes belong to one track identity; a decision of an identity that is gone (deleted
    by the attach pass, which never deletes an identity holding a decision, §10.2) cannot be undone, and nothing is
    written.
  - "Undo identification" in track details undoes the newest identification of the track that is not undone; an
    older one then wins again where no newer decision hides it. A per-field revert is a decision of its own, so it
    can be undone like any other.
  - Every decision, undo and revert raises the change counter in the same user database transaction (§10.3).
- **File changed after a decision.** For each decided field and file location, the reference value is the file's
  value at the decision or, after a verified write, the written value (§7.2 step 6); only values that differ from the
  decided value are stored (`file_state`, §10.2). If a later tag pass finds a file value that differs from the
  reference, the field is flagged in track details as changed in the file, with "Keep PiPlayer's value" and "Use the
  file's value" (a per-field revert). The comparison runs per file location, for written decisions as well as for
  database-only ones. Until the user chooses, the decision keeps winning. PiPlayer's own writes are not such changes
  (§7.2 step 8). Details (decided in M3):
  - A reference is stored as a 4-byte hash (CRC-32 of the value's text, an empty value as 0), not as the value:
    20 000 identified tracks would otherwise hold their old tags twice and break the size budget (§10.2). The hash
    says whether a value changed, and the value the file holds now comes from the tag pass.
  - The location a decision is made on keeps its references in the decision row; another location of the same
    identity whose values at the decision differ from those gets `file_state` rows. A location without either, as
    after a move, compares with the decision's references, so a moved file whose tags stayed the same is not
    flagged.
  - The reference of a field is that of the decision whose value wins the field now. A `file_state` row belongs to
    the decision it was stored for, and the comparison reads only the rows of the decision that wins: a newer
    decision, a per-field revert and an undo never delete another decision's rows, so undoing a revert or a newer
    decision brings back the older decision's references and flags with its values, and no other location is
    flagged for a value it always had. A flag stays until the user chooses: "Keep PiPlayer's value" clears it and
    takes the file's present value as the new reference of that location for the winning decision, and "Use the
    file's value" is a per-field revert: while it stands the field is not decided and nothing of it is flagged, and
    undoing it brings the flag back. The tag pass makes the comparison with the lapse of Skip (§1).
  - After each batch the tag pass has stored, every file it read whose track holds decided values is compared at its
    location with the values the pass stored, all files of the batch in one user database transaction, before the
    batch's mirror run. The comparison only writes flags and references; it raises no counter, since they are not
    mirrored. Decoding the stored tags again after a change of the legacy encoding (library.md §5.7) reads no file and
    compares nothing: the file has not changed. A comparison that fails is made again as the lapse of Skip is (§1).

### 7.2 File tags (opt-in per action or per batch; M4b)

Writing is optional: the user turns it on per action or per batch; otherwise every result stays in PiPlayer's
database (§7.1). Identification and the manual tag editor use the same pipeline and the write-access interface
(§7.4), after one consent per apply where the platform accepts the whole selection, otherwise as few as possible
(§7.3, §8). Each file goes through the steps below; its entry in the write journal (`applied_change`, §10.2) records
how far it got.

1. **Space and snapshot.** Check free space first (`StorageManager.getAllocatableBytes` for the volume of the app's
   storage): a file whose working copy and snapshot do not fit is skipped and listed as "not enough space". Snapshot
   the current tags as a JSON file and the embedded front pictures as image files in no-backup app storage (§10);
   paths and hashes go into `tag_backup` in the user database. Snapshot files are excluded from backup; the journal
   keeps the diff with the old values.
2. **Edit a working copy.** Copy the file into no-backup app storage and apply only the decided keys through the
   TagLib calls of §7.5, following the per-format rules (§7.5) and the ID3v1 policy (§3.1). Changed text frames are
   written in Unicode; unchanged frames keep their bytes and encoding.
3. **Verify the copy.** Re-read its tags and compare them with the expected values; recompute its content key and
   compare it with the identity's (§3.1); hash the whole copy with SHA-256. A mismatch stops here: the original is
   untouched, the file is marked "write failed", and the result stays saved in PiPlayer.
4. **Journal.** One user database transaction inserts the journal entry in state `pending`: the file location, the
   copy's path, size and SHA-256, the expected content key, a hash of the new tag values and the diff (old and new
   values).
5. **Stream back.** Open the original URI with mode `rwt`, stream the copy into it, sync the descriptor and close it;
   set the entry to `streamed`.
6. **Verify the original.** Check that it has the copy's size and SHA-256, and re-read its tags from the file itself
   (not MediaStore). Then one user database transaction checks the content key against the identity, sets the entry
   to `verified` with the actual size and modification time (from `fstat` on the descriptor), and removes this
   location's `file_state` values for the written fields, because the file now holds the decided values (§7.1). Only
   then is the working copy deleted.
   - If the check fails, PiPlayer streams the kept copy once more and checks again. If it fails again, the entry
     becomes `failed`: the file is marked "write failed" in the badge, track details and the Fix tab, the copy is
     kept, the result stays saved in PiPlayer only, and the decision's fields do not count as written. "Try again"
     streams the kept copy after a new consent.
7. **Library update.** A separate library database transaction updates the track's typed tag columns, the embedded
   cover details, the file's content key, size and modification time, and `track_display` from the re-read file. If
   it fails or the process dies first, nothing is lost: at start-up the mirror (§10.3) re-reads every file whose
   `verified` entry is newer than the library database's last mirror.
8. **Rescan and own-write recognition.** Rescan (`MediaScannerConnection.scanFile`); treat MediaStore as eventually
   consistent. When the change observer or the media index sync sees the file change, it looks for a `pending`,
   `streamed` or `verified` entry for this location and matches it by size and content key, plus the tag hash once
   the tag pass has re-read the file; `DATE_MODIFIED` is compared in whole seconds. A match is PiPlayer's own write:
   values and provenance stay as they are, and no "changed in the file" flag is raised (§7.1), also when the
   notification arrives before step 6 commits.
9. **Interrupted writes.** At start-up, an entry left in `pending` or `streamed` means the original may be
   incomplete. The file is marked "interrupted write" in the Fix tab and track details, a notification leads back to
   the app, and after the next consent PiPlayer streams the kept copy again and continues with step 6.
10. **Undo that survives restarts**, per file and per batch, from the snapshots and the journal (change history: M4b;
    batch history beyond per-file revert is below the cut line). Undo of a written change is itself a write: it runs
    through this pipeline with its own consent and restores only the keys whose current file value still equals the
    written value; the other keys are listed as conflicts for the user to decide. Undo of a cover change needs the
    snapshot image (§10.3).
11. **Process isolation.** Tag I/O runs in a separate process (`:tagger`) so a native crash on a malformed file
    cannot kill playback.
    - A bound service declared with `android:process=":tagger"` exposes an AIDL interface. It runs native calls and
      the walks of §3.1, and never opens the databases; only the main process opens them (§12 D9).
    - The main process opens each file (`ContentResolver.openFileDescriptor`, or the working copy) and passes the
      `ParcelFileDescriptor`; `:tagger` never resolves URIs. One file per native call.
    - Before each call the main process increments `audio_file.unsafe_count` (for files outside the library,
      `outside_file.unsafe_count`) and commits; it resets the count when a result arrives. A call that runs longer
      than 30 s kills `:tagger` and counts as a crash. A count of 2 marks the file "unsafe", which also protects the
      library tag pass; a file whose size or modification time changes starts again from 0 (library.md §4.4).
    - Each IPC result stays under 128 KB (the binder buffer is about 1 MB, shared by all transactions of the
      process). A larger result, or any single value over 16 KB (embedded lyrics, large binary frames, raw tags), is
      written to a file in app storage and only its path crosses. Cover bytes never cross: images travel through
      files in app storage or pipes.
    - Settings that affect reading or writing (ID3v1 policy, cover preset) travel as call
      parameters, because DataStore exists only in the main process.
12. **Playing files.** PiPlayer never writes the current item or the next item in playback order
    (`Player.getNextMediaItemIndex()`), which may already be preloaded for gapless playback. By default such a write
    waits until the file is neither and is shown as pending; it keeps its grant only while the process lives, and
    after process death the file needs a new consent (§7.3). From Now Playing the user can choose "Write now":
    PiPlayer pauses, calls `player.stop()` (which keeps the playlist and position), writes, calls `player.prepare()`,
    seeks to the remembered position and resumes.
13. WAV: warn that Android does not index WAV tags (other apps will still show the file name).

### 7.3 Storage access (all builds)

| Situation | Mechanism | When |
|-----------|-----------|------|
| Read the library | `READ_MEDIA_AUDIO` (API 33+) / `READ_EXTERNAL_STORAGE` (≤ 32) + MediaStore | 1.0 |
| Every interactive write and every user-started batch write, including files in `Download/` and on the SD-card root | `MediaStore.createWriteRequest` for the selection: one per apply where the platform accepts the whole selection, otherwise as few as possible (§8); write while the Activity is alive, or start the foreground service with an Intent whose `ClipData` holds the URIs and whose flags are `FLAG_GRANT_READ_URI_PERMISSION` and `FLAG_GRANT_WRITE_URI_PERMISSION` | 1.0 (M4b) |
| Apply interrupted by process death | the grant is gone; the remaining files and every write left `pending` or `streamed` (§7.2 step 9) wait for a new consent from the UI | 1.0 (M4b) |
| Power users (API 31+) | optional `MANAGE_MEDIA` ("no confirmation dialogs"): the write request is still made but granted without a dialog (manifest below); whether an interrupted batch can then resume without the UI is checked in M4b | 1.0 (M4b, below the cut line) |
| Background / library-wide batch writes without a consent per apply | user-picked music folder(s) via `ACTION_OPEN_DOCUMENT_TREE` + persistable grant; health check, re-grant UI and write status per folder | v1.x |
| `.lrc` and `.cue` files, cover images and playlists next to the music (`READ_MEDIA_AUDIO` does not cover them) | the same folder grant | v1.x |
| Files in `Download/` or SD-card root (cannot be tree-granted on 11+) | stay on `createWriteRequest`, also after v1.x | 1.0 |
| Never | `MANAGE_EXTERNAL_STORAGE` (Play policy rejects it for media apps) | |

- In 1.0, playlist import and export (M3U8, below the cut line) use the system file picker, and folder images reach
  PiPlayer only through system thumbnails (§5.2); CUE support remains later.
- A short in-app explanation comes before the system write dialog and, in v1.x, before the system trash dialog
  (`createTrashRequest`). Canceling a system dialog is not an error: the result stays saved in PiPlayer and the file
  is shown as not written.
- Files opened from another app (§1): a `content://media` URI is used directly, a document URI is mapped with
  `MediaStore.getMediaUri()`, and any other URI is matched by content key once the file is in the library. Tags can
  be written only after such a match (or, v1.x, in a granted folder).
- Manifest (draft, fixed in M4b): `READ_MEDIA_AUDIO`; `READ_EXTERNAL_STORAGE` with `maxSdkVersion="32"`;
  `MANAGE_MEDIA`. Whether `MANAGE_MEDIA` takes effect on API 33+ with `READ_MEDIA_AUDIO` alone or needs
  `READ_EXTERNAL_STORAGE` without the `maxSdkVersion` limit, and whether `ACCESS_MEDIA_LOCATION` must be declared or
  granted, is checked with `MediaStore.canManageMedia()` on API 31, 33, 35 and 36 in M4b.

### 7.4 minSdk 30 now, 26 later

- 1.0 ships with `minSdk 30`, `targetSdk 36`.
- All file writes go through one interface (for example `TagWriteAccess`) that exists from day one. Its shape:
  `requestAccess(files)` returns an `IntentSender` to launch with `ActivityResultContracts.StartIntentSenderForResult`,
  or nothing when access is already granted; `openForWrite(file)` returns a `ParcelFileDescriptor`. 1.0 ships one
  implementation, `MediaStoreWriteAccess` (`createWriteRequest`, API 30+), for every interactive and user-started
  batch write. The second, `SafTreeWriteAccess` (persistable folder grant, identical on API 26 to 36), follows in v1.x
  together with background batch writes, the folder health check, `.lrc` reading and `cover.jpg` writing. A batch
  interrupted during apply resumes after a new consent from the UI (§8). No caller checks `Build.VERSION` directly,
  and no caller knows which implementation serves a file.
- Lowering to 26 later is possible only once `SafTreeWriteAccess` exists, and means: route API 26 to 29 to
  `SafTreeWriteAccess` only (no per-file consent dialogs on API 29, no legacy `WRITE_EXTERNAL_STORAGE` path), keep
  `READ_EXTERNAL_STORAGE` with `maxSdkVersion="32"` (§7.3), and add API 26 and 29 emulators to the instrumented test
  matrix.
- Other API-level differences stay behind small wrappers too: media read permission (`READ_MEDIA_AUDIO` on 33+),
  notification permission (33+), `MANAGE_MEDIA` (31+), dynamic color (31+).

### 7.5 Per-format tag rules

TagLib's property map names fields uniformly (for example `ARTISTS`, `ALBUMARTISTSORT`, `DISCSUBTITLE`); these rules
settle what the map leaves to the writer.

**TagLib calls** (TagLib 2.3.2; verify each against its sources in the M0 native spike). TagLib's defaults do the
opposite of several rules below, so every save passes explicit arguments:

- MPEG (MP3): `save(tags, StripNone, version, DoNotDuplicate)`, where `tags` is ID3v2, plus ID3v1 only when the
  policy updates an existing ID3v1 tag (§3.1), and `version` is the file's own ID3v2 version. An ID3v2.2 tag cannot
  be written as v2.2: it is written as v2.3, and the diff says so; a file without an ID3v2 tag gets ID3v2.4. The
  Remove policy calls `strip(ID3v1)` first. The defaults (`StripOthers`, `v4`, `Duplicate`) would upgrade v2.3 to
  v2.4, create an ID3v1 tag from the ID3v2 values and strip an APE tag.
- RIFF WAV: `save(tags, StripNone, version)`, where `tags` is ID3v2, plus Info only when the file already has an
  INFO chunk, so no INFO chunk is created.
- Property updates: read `properties()`, change or erase only the decided keys, and pass the full map back to
  `setProperties()`. Unchanged keys carry TagLib's own strings as read, not PiPlayer's decoded legacy text, so their
  frames stay as they are. Passing only the changed keys would delete every other frame the map covers.
- Pictures: read `complexProperties("PICTURE")`, replace the front-cover entries (§5.2) and write the full list back,
  because `setComplexProperties("PICTURE")` replaces the whole list.
- Secondary tags (an APE tag in an MP3 file, ID3v2 or ID3v1 tags in a FLAC file) stay untouched; the diff notes that
  other apps may still show their old values.

| Topic | Rule |
|-------|------|
| Multi-value fields (artists, album artists, genres) | ID3v2.4: one frame with NUL-separated values; ID3v2.3: one frame, values joined with "; " by PiPlayer before writing; Vorbis comments (FLAC, Ogg Vorbis, Opus): one field per value; MP4: several values in one item where the item allows it, otherwise joined with "; ". `ARTIST` holds the credited name with join phrases, `ARTISTS` the list |
| Dates | release date into `DATE` (ID3v2.4 `TDRC`, ID3v2.3 `TYER` plus `TDAT` when day and month are known, MP4 `©day`); original date into `ORIGINALDATE` (ID3v2.4 `TDOR`, ID3v2.3 `TORY` with the year only). Partial dates stay partial (`1997`, `1997-05`) and are never padded with an invented month or day |
| Track and disc positions | numbers from the MusicBrainz medium and track `position`, never the printed track `number` (such as "A1"); totals from the medium's track count and the number of media: ID3 `TRCK`/`TPOS` as `n/total`, Vorbis `TRACKNUMBER` + `TRACKTOTAL` and `DISCNUMBER` + `DISCTOTAL`, MP4 `trkn`/`disk` pairs |
| Disc subtitle | the medium title into `DISCSUBTITLE` (ID3v2.4 `TSST`), only when the medium has a title |
| Sort names | `ARTISTSORT` and `ALBUMARTISTSORT` from the credited artists' sort names (ID3 `TSOP`, `TSO2`; MP4 `soar`, `soaa`) |
| Compilations | releases credited to Various Artists (§5.1) get `COMPILATION=1` (ID3 `TCMP`, MP4 `cpil`) and that album artist |
| MusicBrainz IDs | TagLib's property keys (`MUSICBRAINZ_TRACKID` holds the recording ID, `MUSICBRAINZ_ALBUMID` the release ID, plus release group, release track, artist and album artist IDs); in ID3v2 the recording ID also lands in the MusicBrainz `UFID` frame |
| Text | every written string is Unicode NFC; an option (default off) writes ASCII apostrophes instead of typographic ones |
| Front cover | ID3 `APIC`, FLAC `PICTURE` blocks and Ogg `METADATA_BLOCK_PICTURE` with picture type 3 (front cover); MP4 `covr` keeps its other images (§5.2); MIME type matching the image; empty description |
| Capabilities | a table per format in code lists the fields each container can hold. ID3v2.3 holds no disc subtitle: `TSST` is an ID3v2.4 frame that TagLib drops when it saves v2.3 (verify in 2.3.2), so the disc subtitle stays saved in PiPlayer only, and any existing frame that a v2.3 save would drop is shown as its own diff line that the user must tick. WAV: the `id3 ` chunk holds every field and the `LIST/INFO` chunk only a few, so PiPlayer writes the ID3 chunk and updates an existing INFO chunk for the fields it holds. A field that a format cannot hold, and every field of a format PiPlayer does not write (for example Matroska/WebM, AMR), is marked "saved in PiPlayer only" in the diff |

## 8. Batch processing

Phases (a) to (c) land in M4a, with accepted results saved in PiPlayer only; phase (d) lands in M4b.

- Phases with their own state: (a) local read + fingerprint (CPU), (b) network lookups, (c) user review (UI only,
  never background), (d) apply writes. Phases (a) to (c) keep their state in the library database; the apply phase
  keeps its per-file journal in the user database (§7.2, §10).
- Phases (a) and (b) run as WorkManager jobs, one album group per job, each finishing well inside WorkManager's
  10-minute limit; a finished job enqueues the next group, and workers never call `setForeground`. Constraints:
  battery not low; network `UNMETERED` while "unmetered only" is on, `NOT_ROAMING` after "Use mobile data this time",
  `CONNECTED` when the user allows roaming. WorkManager `Data` carries row IDs only; jobs are idempotent and resumable
  after process death.
- Phase (d) runs in a service started from the Activity with `foregroundServiceType="dataSync"`. On
  `Service.onTimeout` (API 35+, when the daily dataSync allowance of 6 hours runs out) it stops cleanly between files
  and posts a notification that resumes the apply from the app.
- Apply in 1.0: MediaStore write requests for the files of the apply step (§7.3; check the largest selection one
  request accepts on API 30 to 36, otherwise use as few requests in a row as possible); the granted URIs go to the
  service through `ClipData` with the grant flags. If the process dies during apply, the grant is gone: verified files
  stay done, a notification leads back to the app, and a new consent from the UI covers the remaining and
  interrupted files (§7.2 step 9).
- Batches never disturb playback: batch threads run at background priority; while music plays at most one decoder
  runs for the batch; the batch pauses when the playback player reports an audio underrun
  (`AnalyticsListener.onAudioUnderrun`) or the thermal status reaches `THERMAL_STATUS_MODERATE`
  (`PowerManager.addThermalStatusListener`), and continues once there has been no underrun for 60 s and the status
  has stayed below moderate for 60 s. Fingerprints are computed only for identification, never as part of the library
  scan, except the on-demand matching of orphaned identities (§3.1).
- Group files by album first, using the library's album entities (§10.1), so that "one card per album" matches the
  albums the user sees and one release lookup serves the whole album. Only groups with a release MBID or a non-empty
  album title count as album groups; files without an album tag are looked up one by one and merged afterwards when
  their recordings resolve to the same release. One release per album group (§4.2 step 5). Expect roughly 1 000 to
  2 000 tracks per hour; show progress and ETA.
- Notifications: separate channels for playback and for batches; the notification permission (API 33+) is requested,
  with an explanation, before the first batch; without it, batches still run and show their progress in the Fix tab.
  Progress with ETA; a final notification opens the review queue in the Fix tab.
- Network: the setting "Run batches on unmetered networks only" (default on). A waiting batch says so and offers "Use
  mobile data this time"; batches make no network requests while roaming unless the user allows it. Single-file
  identification is started by the user and uses any network.
- Manifest: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` and `FOREGROUND_SERVICE_DATA_SYNC` (API 34+).
  Play Console: declare the foreground service types `mediaPlayback` (playback) and `dataSync` (apply), with their
  justifications.
- Rate limits: one limiter per provider for the whole process, held by the app's identification services in the main
  process. A limiter is a spacer: requests start no closer together than its interval, in arrival order, with no
  bursts. It keeps its state in memory; persisting the last request time in `device_state` comes with batches (M4a,
  §9).
  - MusicBrainz: one request per ≥ 1.1 s per device; exponential backoff with jitter on 503, 429, 502 and 504 (§9);
    a successful answer whose body is not MusicBrainz's JSON (the page of a captive portal or a proxy) is retried
    the same way and reported as "unavailable" when the retries run out; pause batch on repeated 503.
  - AcoustID: ≤ 3 req/s documented. The limit counts per IP address, not per application key (AcoustID in writing,
    2026-11-05), so installs do not share a budget. Single lookups start at least 0.35 s apart; background batches
    use ≤ 1 req/s, because a device may share its IP address with others; global backoff on 429 / error 14 and on
    500, 502, 503, 504 and the proxy's 520 to 524 (§3.2).
  - Cover Art Archive: no documented limit; requests start at least 1 s apart, so that lazy covers in lists (§5.2)
    do not burst. The redirect to the image host (archive.org) belongs to the same call and takes no slot of its
    own. A 503 pauses the limiter with the backoff of §9 and is cached as a failure (§5.2).
- Before every attempt, and again when the limiter lets the attempt go, a call checks the online gate (§9 Privacy),
  the provider's stop and the network. The HTTP client follows redirects itself (GET and HEAD only, never from HTTPS
  to HTTP) and checks the gate again before each hop, before the hop's host is even looked up. The client also checks
  the gate before every host lookup, every new connection and every request it writes on a connection it already
  holds, so the repeats its HTTP library makes by itself (a 408, a 421, a 503 with Retry-After 0, a pooled connection
  that dropped) look up, connect and send nothing once the gate is closed. A Retry-After of digits the HTTP library
  cannot read as a number (over 2147483647 seconds, about 68 years, or any decimal digit outside the Basic
  Multilingual Plane, which Android's digit check accepts and its number parser rejects; its own 408 and 503 handling
  would throw on the thread that runs the call and could end the app) reaches the clients as 2147483647, so it still
  ends the retries and pauses the limiter for at most 1 hour like any Retry-After over 120 s; any other value, an HTTP
  date included, reaches them as the server sent it. The network must be
  Android's default network with both the INTERNET and the VALIDATED capability. A blocked call
  sends nothing and fails at once, saying why: online lookups are off, there is no network, the network shows a
  sign-in page first (captive portal), or the provider is stopped. It is never retried and never pauses the limiter,
  so a user who turns online lookups off during a backoff gets no further attempt.
- A provider that refuses PiPlayer itself stops for the rest of the process: MusicBrainz after a 403 whose
  `X-RateLimit-Who` starts with `ua-` (the User-Agent), AcoustID after error 4 (the application key). Its later calls
  fail without a request. The stop shows in Settings and in the review; it is not persisted, so the next process tries
  once more.
- Random jitter; no jobs at fixed clock times; no polling MusicBrainz for changes.

## 9. Third-party services and compliance

| Service | Use | Notes |
|---------|-----|-------|
| AcoustID + Chromaprint | 1.0 (core, M3) | free Open Source plan: AcoustID confirmed in writing on 2026-11-05 that a free GPL app without ads or paid features qualifies, with donations through an external link; one registered application key for every build until M5, then one per build flavor |
| MusicBrainz ws/2 | 1.0 (core, M3) | no key; 1 req/s per IP; mandatory User-Agent; core data CC0, genres/tags CC BY-NC-SA; register PiPlayer at metabrainz.org (non-profit tier) and confirm before the Play release (M5); until then the free web service with a User-Agent that names a contact is enough (MetaBrainz in writing, 2026-10-30); if donations exceed $500 a year, contact MetaBrainz again about commercial terms (same reply) |
| Cover Art Archive | 1.0 (core, M3) | images remain copyrighted by rights holders ("use at your own risk"); depends on Internet Archive uptime |
| GitHub Releases API | 1.0 (M5), opt-in update check, GitHub build only | sends no library data; disclosed like the services above |
| Radio Browser | v1.x, user-triggered (internet radio) | free community directory of radio stations, no key; receives search terms; the streams come from the stations themselves; send a User-Agent and pick a server from its DNS list |
| LRCLIB | v1.x, user-triggered | free, no key, synced lyrics; receives artist, title, album and duration; lyric texts are unlicensed, so never write them into files by default |
| Discogs | later, display-only | CC0 text but API terms limit storage (6 h display rule); images restricted; no secret in the APK |
| AudD / ACRCloud | later, bring-your-own-key | paid; audio upload must be disclosed |
| Spotify, Last.fm, Deezer, iTunes, Genius, Musixmatch, ShazamKit | no | storage/caching bans, non-commercial or private-use-only terms, promotional-only artwork, no lyrics in API, paid membership or terms that forbid copying content into tags |

Rules:

- User-Agent on every request: `PiPlayer/<version> ( <contact> )`, never the OkHttp default. The contact comes from
  the Gradle property `piplayer.contactUrl` through `BuildConfig`, and is `https://github.com/LordDetson/piplayer`
  when the property is not set. It must be one token of printable ASCII without spaces, quotes, backslashes or
  parentheses (a parenthesis would end the comment of the header early); the build and the User-Agent code refuse
  anything else, and an empty value fails the build instead of falling back to the default. A fork puts its own
  repository URL or e-mail address there (README, "Forks"). The version name is the product version and must be one
  RFC 9110 token (letters, digits and ``!#$%&'*+-.^_`|~``, no separators such as `( ) / ;`). Shipped code always
  sends the build's User-Agent.
- One AcoustID application key goes into every build type (debug, release, benchmark) through `BuildConfig`: from
  `acoustid.apiKey` in `local.properties`, which git ignores, or from `-Pacoustid.apiKey`. A build without a key gets
  an empty field. Its fingerprint stage then reports itself unavailable (no key) and sends nothing to AcoustID, while
  the other stages, text search included, run as usual; CI builds release and benchmark this way. There are no build
  flavors until M5, when the GitHub and the Google Play builds get one key each. A fork registers its own key.
- The key is not a secret (below), but it stays out of everything PiPlayer prints or shares: the build never echoes
  it, not even when it refuses a malformed one, and it never appears in a log line, a crash or problem report, the
  debug screens or a `toString`. A JVM test keeps the code that reads it to the app's wiring and the AcoustID client,
  and another keeps the crash report to the version and build type of `BuildConfig`. The key enters only through the
  identification services; shipped code, the debug spike included, takes its AcoustID client from their fingerprint
  stage and never makes one with a key of its own, so every caller agrees on whether stage 3 can run (a JVM test
  checks this too).
- Rate limits, in detail for MusicBrainz (§8 lists all providers): one limiter per host for the whole process;
  requests start at least 1.1 s apart, in arrival order. Busy answers (503, 429, 502, 504) and network errors are
  retried with waits that double from 1 s up to 32 s, each taken at random between half and all of its step, and
  never sooner than the server's Retry-After (measured from the server's `Date` header; a Retry-After over 120 s ends
  the retries). Every wait is a pause of the shared limiter, so it holds back every caller, also when the client gives
  up (for at most 1 hour). A call that would have to wait longer than a retry ever waits (a Retry-After of 120 s plus
  its jitter of up to 1 s) sends nothing and fails at once with how long the service asked to wait; this also ends a
  call that was already waiting when such a pause began. A 403 that rejects the User-Agent stops MusicBrainz for the
  rest of the process (§8). Until batches (M4a) the limiter keeps its state in memory; persisting the last request time
  (§8) comes with them.
- Each provider sits behind a `MetadataProvider` interface with policy flags: `commercialAllowed`, `mayPersist`,
  `mayWriteToFile`, `maxDisplayAge`, `requiresApiKey`, `requiresSecret`, attribution text and per-item link, own rate
  limiter. `requiresApiKey` means an application key that ships in the APK and is not a secret (AcoustID, see
  below). `requiresSecret` means a real secret, for example an OAuth consumer secret, which may not ship in the APK
  and so has to come from the user or from a server; no provider of 1.0 needs one. The values of 1.0, with each
  provider's shortest gap between two requests (§8); no provider sets a display age:

  | Provider | Host | `commercialAllowed` | `mayPersist` | `mayWriteToFile` | `requiresApiKey` | `requiresSecret` | Interval |
  |---|---|---|---|---|---|---|---|
  | AcoustID | api.acoustid.org | no (free Open Source plan) | yes | no (its track ID stays in the user database) | yes | no | 350 ms |
  | MusicBrainz | musicbrainz.org | yes (core data CC0; tags and genres are never requested) | yes | yes (CC0 core fields) | no | no | 1.1 s |
  | Cover Art Archive | coverartarchive.org | no (images stay with their rights holders) | yes | yes (accepted covers, §5.2) | no | no | 1 s |

  Cover Art Archive's limiter of 1 s also covers the redirect to the image host (archive.org), which takes no slot of
  its own.
- Only CC0 core fields are written into files as text; embedded covers follow §5.2. Attribution in About (M5):
  "Identification: AcoustID / Chromaprint · Metadata: MusicBrainz (CC0) · Covers: Cover Art Archive".
- API keys shipped in the APK are not secrets (established open-source taggers publish theirs); the README's "Forks"
  section tells forks to register their own key and set their own contact. Users need no key of their own: the
  AcoustID limit counts per IP address (§8).
- Public 0.x pre-releases on GitHub may use online identification: AcoustID (2026-11-05) and MetaBrainz (2026-10-30)
  confirmed the terms in writing.
- Privacy: online lookup is opt-in with a clear explanation. The opt-in is one setting, `online_lookups_enabled`,
  plus `disclosure_version`, the version of the in-app disclosure the user last answered, with no switch per service
  (§10 lists the keys). The online gate built on them is open only while the setting is on and `disclosure_version`
  is at least the version this build shows. It is closed by default and while the settings file has not been read
  yet, and it closes again when a build raises the disclosure version (a new service, or more data shared). The HTTP
  client checks it on every request and every redirect hop, and before every host lookup and new connection (§8),
  so a change of the gate also stops calls that are already waiting for the limiter, a backoff or a redirect, and
  nothing leaves the device while it is closed. Turning the setting off closes the gate at once, before the settings
  file is written; a turn-off that may not have been stored (its write failed or was cut short) keeps the gate closed
  until the file says off or Enable is stored, and Settings shows the gate as it is: a switch moved by an answer
  that was not stored goes back once the answers have ended. A settings file that cannot be read is read again with
  growing pauses. The gate follows the changes written through the process's settings file.
- The disclosure is one screen, shown before the first online lookup and again whenever the disclosure version
  rises, with two answers. **Enable** turns the setting on and records the version. **Not now** records the version
  with the setting off, so the screen does not come back before every identification, and identification stays
  local: it uses what the file already holds (stage 0) and reports that online lookups are off, without sending
  anything. Turning the setting on later in Settings shows the disclosure first when the user has not answered the
  current version. Settings, "Identification and privacy", holds the setting, the same list of services with links
  to their terms, the protected-fields list (§6) and each provider's status for this session (stopped after a
  User-Agent rejection or an invalid key, §8, or unavailable in a build without an AcoustID key). The disclosure and
  the section link to [PRIVACY.md](PRIVACY.md) and [THIRD_PARTY_SERVICES.md](THIRD_PARTY_SERVICES.md).
- The settings file is backed up (§10.3), so a restore carries the consent to the new device or the reinstalled
  app, like every other setting: the user gave it, and it describes what the app sends, not the device. A restored
  consent opens the gate only when its `disclosure_version` is at least the version of the installed build;
  otherwise the build shows its disclosure before the first online lookup, as after an update. Android applies a
  restore while the app is stopped, so the gate reads the restored file at the next start.
- Every online service is disclosed in the app before its first use, in the privacy policy and in Play Data safety,
  which must declare what is **shared** with whom:
  - AcoustID: fingerprints and durations;
  - MusicBrainz: existing tags, parsed file names, durations and IDs;
  - Cover Art Archive: release and release group IDs, also in the background while albums without a local cover are
    shown in lists (§5.2); the Internet Archive (archive.org), where it redirects image requests, gets the same IDs;
  - LRCLIB (v1.x): artist, title, album and duration;
  - GitHub Releases API (opt-in update check, GitHub build only): the request for the latest release;
  - all of them: IP address and user agent.
- Problem reports and crash reports leave the device only when the user shares them; an identification trace
  included in one leaves out file names and tag values by default (§10.1).
- [THIRD_PARTY_SERVICES.md](THIRD_PARTY_SERVICES.md) lists every service PiPlayer talks to with its terms URLs, the
  date they were last read and what the service receives. It is updated whenever a service is added or its terms
  change, and every term is read again before the first Google Play release.

## 10. Data model (Room, sketch)

Two database files (§12 D6). Only the main process opens them; `:tagger` never does (§7.2 step 11). Room cannot join
across files, so neither file refers to the other's row IDs: links go through the track key (the content key of §3.1
plus the track's start offset in the file, 0 until CUE sheets arrive), file locations (volume, relative path and
display name) and batch UUIDs. The user database keeps two counters in `user_meta`: `change_counter`, raised by every
write that the library database mirrors, and `rules_counter`, raised only by the folder rules. The library database's
`library_meta` stores the value of each that it last mirrored (§10.3).

Where things live:

- `library.db`, the working copies and undo snapshots of §7.2 and the saved cover files live under
  `Context.getNoBackupFilesDir()` (Room accepts an absolute database path), which Auto Backup skips (verify for device
  transfer); caches live in the cache directory.
- `user.db` lives in the default database directory.
- Settings live in DataStore in the main process (`files/datastore/`), in two files: `settings` (legacy-encoding
  mode, ID3v1 policy, unmetered-only, protected-fields list, cover preset and the other options) is backed up;
  `device_state` (rate-limiter last-request times, and from M2a "stop after current track" and the permission flag
  of library.md §3.2) is not.
- The identification and privacy keys of `settings` (M3):
  - `online_lookups_enabled` (boolean, absent means off): the online-lookup setting of §9;
  - `disclosure_version` (integer, absent means 0, never answered): the disclosure version the user last answered,
    by Enable or Not now (§9). The first disclosure is version 1;
  - `protected_fields` (set of strings, absent means empty): the fields identification never proposes to change
    (§6). The names are `title`, `artist` (the track's artists with their credits and sort names), `album`,
    `album_artist` (likewise), `date`, `original_date`, `track_number` (number and total), `disc_number` (number and
    total), `disc_subtitle`, `compilation`, `label`, `catalog_number`, `barcode`, `isrc`, `musicbrainz_ids` and
    `cover`. A name this build does not know is kept when the list changes, so a newer build's choice survives a
    downgrade or a restore into an older build.
- Library folder rules are user decisions and live in the user database.
- The persisted queue lives in its own DataStore file, `playback_state`, and in `queue.bin` under the no-backup files
  (playback.md §4.8), which the backup rules leave out: it refers to tracks by file ID, identity, content key and
  location and, for files opened from other apps, to their URIs, which do not carry over to another device.

### 10.1 Library database (`library.db`): rebuildable, excluded from backup

Everything that can be rebuilt from the media index and file tags, or safely lost (batch state, candidates, traces,
caches). Rebuilding the library means deleting this file and filling it again from the media index, the tag pass and
the user database; user data is not touched. A rebuild cancels running batch phases (a) to (c); decisions already
made live in the user database. A new schema version rebuilds it the same way (library.md §1.1); M3 brings version 13,
whose first sync after the update runs as on a first launch (about 1.7 s on the owner's phone).

- `audio_file(id, media_store_id, volume_name, relative_path, display_name, document_uri, size, mtime,
  generation_modified, date_added, mime_type, <media kinds>, format, codec, sample_rate, bit_depth, channels, bitrate, duration_ms, duration_source,
  content_key, tag_encoding, embedded_cover_present, embedded_cover_width, embedded_cover_height,
  embedded_cover_bytes, embedded_cover_hash, unsafe_count, tag_pass_at, reader_version, tag_failure, tag_length_ms)`:
  `tag_length_ms` is TagLib's length, an estimate for a VBR MP3 without a header, used only while no better one is known.
  Its index on (`content_key`, `size`) finds the files of a key, and the files without a key yet that have a size, by
  exact lookups: the lapse of a Skip (§1) asks for both after every batch of the tag pass that may touch a skipped
  track, also while a new or rebuilt library has no keys yet
- `outside_file(uri, size, mtime, unsafe_count)`: the count of failed reads (§7.2 step 11) of files outside the
  library, such as files opened from other apps; a row exists only while its count is above 0 (library.md §4.4).
  The media kinds are the index's flags: music, podcast, audiobook, ringtone, notification, alarm and recording (the
  last unknown on Android 11); every audio row is stored, and lists hide kinds by setting (library.md §1.3).
- `media_index_entry(file_id, title, artist, album, album_artist, composer, genre, year, track_number, duration_ms)`:
  media index values, precedence level 4, used only while `tag_pass_at` is null (§7.1).
- `track(id, file_id, track_key, start_ms, end_ms, album_id, <typed tag columns>, raw_tags_json)`: values from
  PiPlayer's reader, precedence level 3. `track_key` stays empty until the content key is computed. Typed columns: title,
  artist, artists, album, album artist, album artists, composers, genres, sort names,
  date, original date, track and disc numbers and totals, disc subtitle, compilation, label, catalog number, barcode,
  ISRC, MusicBrainz IDs, ReplayGain values.
- `album`, `artist`, `track_artist(track_id, artist_id, role, position, join_phrase)`, `genre`, `track_genre`:
  grouping by release MBID, otherwise normalized title + album artist + folder, plus the compilation flag and the
  result of the album's thumbnail probe (§1).
- `track_mirror(file_id, favorite, play_count, last_played_at)` and `album_mirror(album_key)` (M2b): the mirrored user
  fields of §10.3, a row only for a file whose identity is a favorite or has plays, and one per favorite album. They
  sit beside `track_display` rather than in it, so that the writers of display rows (sync, tag pass, a new display
  version) never carry user fields over (library.md §15.4).
- `track_display(file_id, <merged values>, <normalized search columns>, missing_info_reasons,
  missing_info_decision, badge_state)`: merged values (§7.1), for lists, sorting, smart lists and search; lists join
  `track_mirror` for the mirrored user fields. It is keyed by file, because a file has a display row before
  the tag pass has made a `track` row of it; CUE sheets will change that. The reasons are empty until the file is read,
  because the index fills albums with folder names. Search (M0 spike): one `search_text` column holds the
  title, artists, album, composer, genre and file name, each normalized like §4.1 and joined by spaces. Each field also
  keeps its names glued, inside that field only: a word with punctuation inside ("AC/DC" as "ac dc acdc"; an underscore
  separates words) and a run of neighbouring words of up to two letters ("AC DC" as "ac dc acdc"), so "acdc" finds both.
  Longer words written apart stay apart: "blink182" does not find "Blink 182", and "theend" does not find "The End".
  A query is
  normalized the same way and split into words (no repeats, longest first, at most 8), and every word must occur in
  the text, tested with `instr()` (in the M0 spike on the platform's SQLite, LIKE went through ICU and was two to three
  times slower). The library database uses the SQLite bundled with the app (library.md §1.1).
  An index on (`title_sort`, `search_text`) (the collator's key of the title since M2a step 11, library.md §11.1) lets
  a search walk the titles in order and stop at the first page of 50.
  `missing_info_reasons` is the set of §1 reasons, written by
  the precedence resolver; `missing_info_decision` is none, keep_as_is or skipped (0, 1, 2), and
  `badge_state` is the state of the
  metadata badge (§6). Both are written with the merged values from the mirrored decisions (`decided_mirror`), so a
  writer that replaces a display row keeps them, and both are none until the first decision is mirrored (M3).
  `badge_state` in M3 (decided in M3): 0 from tags, when no decided value wins; 1 identified, when values of a
  confirmed identification win and no edit does; 2 edited, when a value of the user's own edit wins. Both decided
  states are saved in PiPlayer only until writing exists (M4b), which adds its states with their own codes.
- `decided_mirror(file_id, field, value, level, decision_id)` (M3): the decided values of §7.1 as the precedence
  resolver uses them: for each file and field part the winning value of levels 1 and 2 (the parts of the fields of
  the protected-fields list by their names in §10.2, such as `title` or `artist_sort`; a value may be empty, as when
  an edit clears a field; a list joined by U+001F), its level and the user database's
  decision it comes from. A file whose track has a Keep as-is or Skip that counts (§1) also holds one row with the
  field `missing_info_decision`, the value `keep_as_is` or `skipped`, level 0 and that decision, compared and replaced
  like the others; a writer of display rows takes `missing_info_decision` from it. Like `track_mirror` it sits beside
  `track_display` rather than in it, because the writers of
  display rows (sync, tag pass, a new display version) replace them whole; they join it on every refresh. It is filled
  from the user database on each decision commit and at start-up when `user_meta.change_counter` differs from
  `library_meta.mirrored_change_counter` (§10.3).
- `library_meta(mirrored_rules_counter, mirrored_change_counter, display_version, library_id, last_file_id, collation)`:
  one row. `mirrored_rules_counter` is the user database's `rules_counter` that the folder rules of `audio_file` were
  last applied with (library.md §3.4), and `mirrored_change_counter` (M3) is its `change_counter` that `decided_mirror`
  was last filled at; the other columns belong to the sync (library.md §8.4, §11.1).
- `display_genre(file_id, position, genre_key, name, search_text, sort_key)` (M2b): each genre of a file as lists group
  it, from the merged values, beside `display_artist` (library.md §19.1, §19.5).
- `fingerprint(content_key, fingerprint, format, acoustid_id, decoder_path, computed_at)`: a cache for identification
  and re-attachment, kept by content key, so the fingerprint of a file that left the media index stays available for
  re-attachment (§3.1); rows that no file or identity refers to are dropped after 30 days. `format` is the file's
  format, which re-attachment step 4 compares. M2a creates it without `acoustid_id` (the AcoustID track ID of the best
  result, §3.2) and `decoder_path` (how the audio was decoded, §3.2), which identification adds in version 13 (M3).
- `lookup_job(content_key, batch_uuid, phase, status, attempts, next_attempt_at, last_error)`: one row per content
  key, with the "retry later" time of §3.2. A single-file run (M3) writes `batch_uuid` null, `phase` `lookup` (phase
  (b) of §8), `status` `retry_later` or `final`, `attempts` the empty AcoustID answers so far, `next_attempt_at` in
  milliseconds since the epoch (null when final) and `last_error` `acoustid_empty`, and deletes the row when the retry
  finds results or a text candidate of at least 0.50 cancels a waiting retry (§3.2).
- `candidate(content_key, position, source, recording_mbid, release_mbid, score, reason, payload_json)`: the ranked
  candidates of the last run, `position` 0 for the best. A run replaces the rows of the run before with its ranked
  recordings, at most 10: `source` is the stage of the recording's best score (`tag_recording_id`, `isrc`,
  `fingerprint`, `text_search`), `release_mbid` the suggested edition for the best and, for the others, the top of
  their release ranking when release choice browsed them (otherwise null), `reason` the reason of §4.1 as JSON, and
  `payload_json` the recording as the stages found it (title, credit, artist names, length, releases with the track's
  position), so the candidates read back as they were ranked.
- `identification_trace(content_key, run_id, position, started_at, stage, provider, http_status, result_count,
  best_score, elapsed_ms, note)`: a trace of the last 3 runs, one row per step of a run in `position` order, shown in
  track details and offered in problem reports without file names and tag values by default (§9). A run (M3) has a
  random UUID as `run_id` and writes one row per stage it reached (`local_read`, `known_ids`, `isrc`, `fingerprint`,
  `text_search`, `release_choice`), then one for its outcome (`outcome`). `started_at` is in milliseconds of the
  services' clock; `provider` is the stage's (`musicbrainz` or `acoustid`, none for stage 0), or for the outcome the
  provider that left the run unavailable or stopped; `http_status` is the status of the stage's last answer as the HTTP
  layer saw it, none when no answer came; `result_count` the recordings the stage found (the browsed recordings for
  release choice, every ranked recording for the outcome); `best_score` the best score of what the stage found by
  itself (§4.1), and for the outcome the run's best; `elapsed_ms` the stage's time, waits for the rate limiter
  included, and for the outcome the whole run's. `note` holds the skip reason (`skipped=no_value`), a code for the
  failure (`failed=unavailable`; never its class name, which R8 renames in a release), the attempts the stage made at the HTTP layer, answered or not (a failed lookup of the host or a refused connection counts too, a call that the gate, a stop or the network check refused does not), and the time of the answered ones (`requests=2 http_ms=310`),
  the browses of release choice (pages, releases received and the page cap) and, for the outcome, its kind
  (`found=high`, `no_key`, `unavailable`) and the "retry later" time. No column holds file names, tag values, addresses
  or error messages, which may carry tag values in a search query. A run that adds its rows drops the runs of its
  content key beyond the last 3, in the order they were added (by row ID), so a clock that was set back never drops
  the newest run.
- These three are keyed by the content key (§3.1), not by the file ID (decided in M3): a file opened from another app
  has no file ID, and a library file that the tag pass has not read yet gets its key at stage 0 (§3.1), before any
  lookup. So the rows serve every file that is identified, a file that enters the media index later finds its results
  by its key, and byte-identical copies share them as they share an identity. A file whose key changes (its audio was
  re-encoded) starts without them. They are kept per audio content, not per track: the track key of §10 also holds the
  track's start in the file, which is 0 for every file until CUE sheets arrive, so until then a content key names one
  track. CUE support adds the start to the keys of these three tables in a later library database version; as working
  rows of a rebuildable database they are dropped then rather than migrated, and a run fills them again.
- `release_cache(release_mbid, payload_json, fetched_at)`: the release lookups of §5.1 as JSON, under the MBID they
  were asked for and, when MusicBrainz answered with another (a merged release), under that one too. A row older than
  30 days counts as missing, so a later lookup brings MusicBrainz's corrections, and the next write drops such rows.
- `api_cache(key, status, body, fetched_at, ttl, busy_in_a_row)`: answers of the online services with their time to
  live. In M3 it holds only the failure entries of §5.2: the image or index address as the key, status 404 or 503, no
  body, the TTL in milliseconds and, for a 503, the count of the address's 503s in a row. An expired row stays while
  §5.2 still reads it (until 6 hours after it expired) and is dropped by the next write after that.
- There is no cover cache table (decided in M3): online covers are pictures of the cover disk cache (library.md §13.3)
  under their own source key, their failures live in `api_cache`, and accepted covers are `saved_cover` files of the
  user database (§10.2).

### 10.2 User database (`user.db`): backed up whole

What the user decided or did; it is never rebuilt from files. MBIDs and other UUIDs are stored as 16-byte blobs and
content keys as 32-byte blobs. Per-file records are found through the identity and the file location when they are
used, never through a stored content URI, which goes stale after a media index rebuild.

- `track_identity(id, content_key, start_ms, duration_ms, recording_mbid, hint_volume_name, hint_relative_path,
  hint_display_name, hint_size, created_at, missing_since)`: the stable track identity that all user data refers to;
  the content key and the start form the track key. Every file with a known content key has one (M2a). Re-attachment
  (§3.1) updates the key, the length and the hints. `missing_since` is set while no file holds the key; an identity
  without user data that has been missing for 30 days is deleted, one with user data never. A decision is user data,
  also an undone one, and so is a `file_state` row (M3). The listening outcomes (M3, PIM-89) are not: like album
  members they keep no identity, go with it when it is deleted and move with a takeover (library.md §8.1, §15.7).
- An identity at Apply (decided in M3): a decision needs an identity, and Apply makes one when no identity holds the
  target's content key, in the transaction that stores the decision:
  - A file opened from another app gets a new identity with its content key, the display name and size that the
    providing app gave as hints (no volume and no folder), no length and no recording MBID, and `missing_since` set to
    the time of Apply, since no file of the library holds the key. Its decision is user data, so the 30-day deletion
    never takes it. When the file appears in the media index and the tag pass has stored its key, step 1 of
    re-attachment (§3.1) attaches it: the hints, the length and `missing_since` follow the file, and the decided values
    reach the lists with the mirror run after that tag pass batch (§7.1). A file whose provider hands out only a pipe
    has no content key and no identity, and its result cannot be applied.
  - A library file without one (the tag pass has not read it, so the library holds no key for it, or its length still
    waits for a walk) gets its identity by the rules of re-attachment for that file alone, with the content key of
    stage 0 and the index's length: a missing identity hinted at its place with a length within 1 s takes the key
    (step 2), otherwise it gets a new identity with its place and size as hints. Step 3 needs the file's tags, which
    only the tag pass stores, and steps 4 and 5 belong to the attach pass, so neither runs here; once the new identity
    holds the decision, steps 4 and 5 never take it over. A library file that is gone gets none, and nothing is
    written. Its decided values reach the lists once the tag pass has stored its key, as for an opened file.
- `decision(id, identity_id, batch_uuid, kind, fields, release_choice_id, recording_mbid, release_track_mbid,
  acoustid_id, confidence, reason, provider, fetched_at, license_class, file_size, file_mtime, file_values, created_at,
  undone_at, lapsed_at)`: one row per track and decision. `kind` is apply, edit, revert_field, keep_as_is or skip;
  `fields` is the set of ticked fields. Provenance is stored here, once per decision, not with each value. `acoustid_id`
  is the AcoustID track ID of a confirmed result, so it survives rebuilds and restores. A skip stores the size and the
  modification time (in milliseconds) of the file it is made on, so that the tag pass can let it lapse, and `lapsed_at`
  once it lapses (§1). Keep as-is and Skip store no values and `fields` 0. `file_values` holds the references of §7.1
  for the location the decision was made on.
- `release_choice(id, release_mbid, release_group_mbid, values, saved_cover_id, fetched_at)`: release-level values
  (album, album artist credit and list, album artist sort names and IDs, dates, label, catalog number, barcode,
  totals, disc subtitles, compilation flag), stored once per chosen release; decisions that chose the same release
  with the same values share one row. `values` is gzip-compressed JSON.
- `track_metadata(decision_id, field, value)`, without row IDs: per-track values. Identification decisions store only
  per-track fields (title, artist credit and list, artist sort names and IDs, track and disc positions, ISRC,
  recording and release track IDs). Edits store any field, including release-level fields and the cover (a row with
  field `cover` pointing to a `saved_cover`). A revert_field decision stores a row without a value (§7.1).
- `file_state(identity_id, decision_id, volume_name, relative_path, display_name, field, value_at_decision,
  changed_in_file)`: per-location state of one decision, stored only when needed: when the file's value at the
  decision differs from the decision's reference, or when the field changed in the file since (§7.1). Whether a field
  is written into a file is derived from `applied_change`.
- The encodings of version 6 (decided in M3):
  - `kind`: 1 apply, 2 edit, 3 revert_field, 4 keep_as_is, 5 skip. `fields`: a bit per field of the
    protected-fields list (§10), in its order from bit 0 (`title`) to bit 15 (`cover`); a later field takes the next
    bit, so an older build keeps the bits it does not know. `provider`: 1 MusicBrainz, 2 AcoustID, 3 Cover Art
    Archive; `license_class`: 1 CC0, 2 restricted, 3 unknown. `confidence` in thousandths. `reason`: the match's
    reason of §4.1 (source and tier, the stages that found it, the fingerprint score, the length difference, the
    title and artist similarities and the disagreements) as a blob of its own, which the review and track details
    format for the locale. Its first byte holds the source in bits 0 and 1 (0 tag recording ID, 1 ISRC, 2
    fingerprint, 3 text search), the tier in bit 2 (0 identifier, 1 similarity), the stages that found it in bits 3
    to 6 (bit 3 plus the source's code) and in bit 7 whether a length difference follows. The next 4 bytes, most
    significant first, hold the disagreements in bits 30 (title) and 31 (artist) and the fingerprint score, the title
    similarity and the artist similarity in thousandths in bits 20 to 29, 10 to 19 and 0 to 9, 1023 for none. The
    length difference in milliseconds follows as a zigzag varint (7 bits a byte, least significant first). A match
    within 8 s of the file's length takes 7 bytes, where the earlier short text took about 25: 20 000 decisions save
    about 0.36 MB (§10.2 size budget). A blob this build cannot read (too short, longer than its parts, or a score
    above 1000 that is not 1023) gives no reason, as an older build's unknown value does; a score outside 0 to 1 is
    stored at the nearer end. The times are milliseconds since 1970.
  - The parts of the fields, by code and name (`track_metadata.field` and the hashes of `file_values` and
    `file_state` take the code, `decided_mirror` and `release_choice.values` the name): field `title`: 1 `title`;
    `artist`: 2 `artist` (the credit as printed), 3 `artist_credits` (the credited names with their join phrases),
    4 `artist_sort`; `album`: 5 `album`; `album_artist`: 6 `album_artist`, 7 `album_artist_credits`, 8
    `album_artist_sort`; `date`: 9 `date`; `original_date`: 10 `original_date`; `track_number`: 11 `track_number`, 12
    `track_total`; `disc_number`: 13 `disc_number`, 14 `disc_total`; `disc_subtitle`: 15 `disc_subtitle`;
    `compilation`: 16 `compilation`; `label`: 17 `label`; `catalog_number`: 18 `catalog_number`; `barcode`: 19
    `barcode`; `isrc`: 20 `isrc`; `musicbrainz_ids`: 21 `recording_mbid`, 22 `release_mbid`, 23
    `release_group_mbid`, 24 `release_track_mbid`, 25 `artist_ids`, 26 `album_artist_ids`; `cover`: 27 `cover` (a
    `saved_cover` ID). Label, catalog number, ISRC and the artist IDs are lists.
  - An identification stores the parts 1 to 4, 11, 13, 20 and 25 as `track_metadata` rows, the recording and
    release track IDs in the decision's own columns, the other release-level parts in its `release_choice`, the
    number of tracks and the disc subtitle there per disc, and its cover as the choice's `saved_cover_id`. An edit
    stores every part of the fields it sets as `track_metadata` rows.
  - A value is stored as UTF-8 text; an MBID as 16 bytes and a list of MBIDs as their bytes one after the other; a
    list of texts, and a credit as its names and join phrases in turn, joined by U+001F; a number in decimal digits;
    the compilation flag as `1` or `0`. An empty value is NULL. `release_choice.values` holds the release-level
    parts by their names in §10, with `discs` holding the number of tracks and the disc subtitle per disc number.
    Two decisions share a choice when everything but the discs is equal and no disc differs; a disc the row lacks is
    added to it.
  - `file_values` holds the references of §7.1 for each decided part whose value in the file differs from the decided
    one: a 4-byte mask of the parts whose file value was empty (bit `code - 1`), then a field code byte and a 4-byte
    hash for each other part, so a file without tags keeps all of them in 4 bytes. `file_state.field` is a part's
    code and `value_at_decision` its hash.
  - `track_metadata` and `file_state` have no row IDs (`WITHOUT ROWID`). Version 6 comes from version 5 by an
    automatic migration; every table it adds starts empty.
- `track_cover(identity_id, file_name, width, height, set_at)` and `album_cover(album_key, file_name, width, height,
  set_at)` (M2a): the user's own covers for a track and for an album, chosen from the device (library.md §13.2); the
  copies live in no-backup app storage.
- `saved_cover(id, release_mbid, path, width, height, bytes, hash, source)`: accepted covers; the image files live in
  no-backup app storage (§10). Decided in M3 (§5.2): `path` is the file's name in the folder `saved_covers`, `hash`
  the SHA-256 of its bytes in hex, and `source` where it came from: `release` or `release_group` for a front image of
  Cover Art Archive, `release_index` for an image of the release index, `embedded` for a picture of the file.
- `favorite(identity_id, added_at)`, `favorite_album(album_key, release_mbid, added_at)` (by the library's album key:
  the release MBID when known, then also stored as a blob, otherwise normalized title + album artist within the
  folder), `album_member(album_key, identity_id)` (the identities seen on a referenced album, so that its favorite and
  own cover follow its tracks to a new key, library.md §15.3), `play_event(identity_id, started_at, listened_ms)` for
  the last 90 days (keyed by the start and the identity without row IDs since version 6), and
  `play_stats(identity_id, play_count, listened_ms, last_played_at)`, into which older events are folded (M2b,
  version 4, library.md §15).
- `listen_event(started_at, identity_id, listened_ms, duration_ms, outcome)` for the last 90 days, keyed by the start
  and the identity without row IDs, with `outcome` 1 early skip, 2 late skip, 3 listened to the end, and the columns
  `early_skips`, `late_skips` and `finished` of `play_stats`, into which older events are folded with the play events
  (a row with `play_count` 0 holds only listening counts and is not read as plays): how every listening ended, kept
  for recommendations after 1.0 and never sent (M3, version 6, PIM-89, library.md §15.7, playback.md §12). They are
  not mirrored and do not raise the change counter.
- `playlist(id, name, created_at, updated_at)` and `playlist_entry(id, playlist_id, position, identity_id,
  hint_volume_name, hint_relative_path, hint_display_name, hint_duration_ms, hint_content_key, hint_recording_mbid,
  hint_title, hint_artist)` (M2b, version 5, library.md §16); an entry plays the first available location of its
  identity. `identity_id` is empty while the entry's song has no identity (its tags not read yet, or an imported line
  that found no file); such an entry is linked by its hints.
- `library_folder_rule(volume_name, relative_path, mode)`: include or exclude (M2a).
- `batch(uuid, created_at, source, status, write_to_file)`.
- `tag_backup(id, identity_id, volume_name, relative_path, display_name, created_at, tags_path, tags_hash,
  cover_path, cover_hash, id3_version)`: undo metadata; the snapshot files live in no-backup app storage.
- `applied_change(id, identity_id, decision_id, batch_uuid, volume_name, relative_path, display_name, state,
  copy_path, copy_size, copy_sha256, expected_content_key, tag_hash, diff, actual_size, actual_mtime, created_at,
  verified_at, undone_at)`: the write journal of §7.2, with `state` pending, streamed, verified or failed. It also lets
  the sync recognize PiPlayer's own writes. `diff` holds the old and new values of the written keys as gzip-compressed
  JSON, so text changes can be undone even without the snapshot files.
- Size budget: `user.db` stays under 16 MB for 20 000 identified tracks (about 2 000 albums) with a year of history,
  under the 25 MB backup quota with room for the WAL, which is checkpointed when the app goes to the background.
  Above 25 MB Auto Backup stops silently, so the budget is a release criterion. A test with generated data checks it
  (M3) and fails above 16 MB. Until the write journal and the tag backups exist (M4b), the test fills the tables of
  M3 and those before them at the volumes below and fails above 16 MB minus a fixed reserve of 1.5 MB for the M4b
  tables; M4b adds its tables to the test and drops the reserve. The estimate:

  | Table | Assumption | Size |
  |-------|------------|------|
  | `track_identity` | 20 000 rows of about 210 B with the key index | 4.2 MB |
  | `decision` | one identification per track, about 120 B with the reason in 7 B; half of them made on files whose tags differ from the decided values in 8 parts, whose references take 40 B more | 2.8 MB |
  | `track_metadata` | about 8 per-track values per identified track, about 26 B each | 4.2 MB |
  | `release_choice` | 2 000 releases, about 500 B compressed | 1 MB |
  | statistics, favorites, playlists | play events of 90 days (keyed by their start without row IDs since version 6, which saves about 0.16 MB), the play counts of 12 000 identities, 2 000 album members, 5 000 playlist entries | 1.15 MB |
  | `file_state` (M3) | a tenth of the tracks with a second location whose values differ in 4 parts, about 110 B a row | 0.9 MB |
  | `listen_event` and the listening counts of `play_stats` (M3, PIM-89) | 9 000 listening outcomes of 90 days (100 a day), about 0.24 MB, and the folded counts of 15 000 identities, about 0.11 MB | 0.35 MB |
  | `tag_backup`, `applied_change`, the `file_state` rows of written files (M4b) | a year with 5 000 written files, about 300 B each | 1.5 MB |
  | Total | | about 16 MB |

  The rows are shares estimated from the same rows in desktop SQLite, which come out about 0.35 MB above what the
  emulator measures, so on paper the total leaves no headroom. What decides is the test's measurement of the generated
  data (§11.2). With the reason as the short text of about 25 bytes it measured 14.64 MB on the API 30 and 36
  emulators, above the 14.5 MB that the test allows next to the M4b reserve; with the 7-byte reason blob it measures
  14.28 MB on both, 0.22 MB under that limit. The headroom is thin: M4b measures its tables against the 1.5 MB
  reserve before it drops the reserve, and a column added to `user.db` later shows in this test.

### 10.3 Mirror and backup rules

- Mirrored into the library database on every change: the decided values (`decided_mirror`, with the badge state and
  the Keep as-is/Skip state they give), favorites (tracks and albums), play counts and last played times; the resolver
  recomputes the missing-information reasons at the same time. Every write of such data raises
  `user_meta.change_counter` in the same transaction. The listening outcomes of §10.2 are not mirrored and raise no counter.
  - The decided values are also mirrored at start-up when `user_meta.change_counter` differs from
    `library_meta.mirrored_change_counter`, which the mirror sets to the counter it read once `decided_mirror` holds
    what the user database held at that value (M3).
  - Details (decided in M3): the start-up run reads the counter and every decision in one user database
    transaction, compares the decided values with `decided_mirror` and writes only the files whose values differ,
    with their display rows, in one library database transaction. The run after a decision or an undo mirrors that
    track's files alone and moves the stored counter to the decision's only when it stood one below, because
    favorites and plays raise the same counter without changing a decided value; otherwise the next start-up run
    compares all. A lapse of a Skip (§1) is committed and mirrored as a decision is: the run of its tracks' files moves
    the stored counter only from one below. The runs take one lock, and a run after a decision reads the track's
    decisions under it, so an
    older run never writes over a newer decision. The attach pass asks for a whole run too after each pass that
    wrote identities, since a takeover moves a key to another identity (library.md §8.2), and after its first pass of
    a process unless a whole run has run in that process already: a process that died after an attach pass wrote
    identities left its keys unmirrored without raising the counter. So every start makes one whole run, the
    start-up run when the counters differ or else the one after the first attach pass, not two. Only a whole run
    that completes counts: when the start-up run fails (a full disk, a locked or closed database), the first attach
    pass still asks for one, so the library database catches up within the session. A whole run reads
    the user database, and compares its decided values with `decided_mirror`, before it holds off the sync. Only the
    mirror writes values into `decided_mirror`, under its lock; the sync only deletes the rows of the files it deletes,
    and a file's ID is never used again (library.md §8.4), so a file's rows either stay as compared or are gone with
    the file. Under the hold the run reads the files' content keys again and compares again the files whose keys
    changed meanwhile (the sync and the tag pass change keys, and both write them only while they hold the sync) and
    the files that should hold no values, whose rows the sync may have deleted meanwhile; then it writes. A file that
    is gone is not written and does not count as a change. A whole run that finds every value mirrored writes nothing
    and leaves the display rows alone. The comparison streams the mirrored rows, in a few ranges read at once, against
    the values each keyed file should hold, and builds rows only for the files that differ; the ranges take one
    reader connection fewer than the library database has, so the lists and Now Playing still read meanwhile.
    Decoding and resolving the decisions use every core but one, as the display writer does, and the display rows of
    the files written join the rows the run writes without reading them back. With a decision on every one of 20 000 files, building both sides as maps of rows under
    the hold made a run that found every value mirrored slower than a full refresh (§11.2).
  - The run after a decision or an undo runs in the mirror's own scope, and the caller waits for it: a caller that
    is cancelled meanwhile (a screen left while a sync holds the library database) does not stop it, and when it
    fails the decision stays committed, the caller gets it as stored, and a whole run is asked for, so the library
    database catches up within the session rather than at the next start.
  - The folder rules have a counter of their own, `user_meta.rules_counter` (library.md §3.1), raised only with the
    rules; the sync applies the rules again when it differs from `library_meta.mirrored_rules_counter`, so that a
    favorite or a play does not make the sync apply the rules again.
  - The mirror of favorites and plays (M2b) compares no counters: its full run is cheap, and the first attach pass of
    each process asks for one (library.md §15.4).
  - Writes that change nothing mirrored (the listened time of a counted play, own covers, album members, folding old
    play events, playlists) do not raise the counter. Playlists are not mirrored at all: the playlist page and the
    queue resolve their entries against the library database by content key and place (library.md §16.5).
- A change that touches both files commits the user database first and the library database in its own transaction
  afterwards. If the process dies in between, the start-up mirror and the tag pass bring the library database up to
  date (§7.2 step 7).
- Auto Backup and device transfer include `user.db` with its WAL file, so the copy is consistent, and the `settings`
  DataStore file. Everything else stays out: `library.db` with its WAL and SHM files, all caches, the fingerprint
  cache, saved cover files, undo snapshots, working copies, the persisted queue and the `device_state` DataStore file.
- The rules exist in both forms: `android:fullBackupContent` for API 30 and `android:dataExtractionRules` (cloud
  backup and device transfer) for API 31+; they list the included files explicitly, and a JVM test checks both
  files.
- After a restore (M5), the library database is rebuilt and identities re-attach by content key, as after a library
  rebuild (M2b). Only the device-transfer flow (files copied to a new device, with new media index IDs and paths) is
  below the cut line. Undo of text changes works from the journal's `diff`; undo of cover changes is unavailable
  because snapshot files are not restored; accepted covers are re-fetched by release MBID where possible; in v1.x the
  app asks to grant folders again.
- A retention period for undo snapshots, and a storage screen that clears caches but keeps identification results
  and undo history: v1.x.

## 11. Acceptance criteria and tests

Each test lands with the milestone that builds its feature (see features.md). The release (M5) needs every 1.0 test
below to pass: the JVM tests in CI and the instrumented tests on API 30, 33, 35 and 36. Rows tagged v1.x, and tests of
features moved by the cut line, are excluded. The release also needs no data loss in round-trip and process-kill
tests, and a high-band error rate within the threshold of §11.4.

### 11.1 Unit tests (JVM)

| Milestone | Test |
|-----------|------|
| M2a | Field precedence: a table of values from user edit, identification, tags, media index and file-name guess → winner; the media index counts only before the tag pass has read the file, and placeholders such as `<unknown>` count as empty; one repository function updates the display, search and missing-information columns |
| M2a | Duration source: reliable headers are used; MP3 without a frame-count header and raw ADTS are walked; a Xing/VBRI byte count off by more than 2 %, a CBR header off by more than 2 s, and WAV, MP4 and FLAC headers that the payload cannot support trigger a walk |
| M2a | Content key: golden values (each fixture has its expected key in hex, checked on every TagLib or parser change); on synthetic payload offsets the key stays the same when only tag regions change and changes when payload bytes change |
| M2a | Re-attachment order (§3.1): content key, location with duration, unique recording MBID, cached fingerprint, playlist hints; another song at the same path with a duration 3 s off is not attached; two unattached files with the same recording MBID attach neither |
| M2b | Playlist hints (§3.1 step 5, library.md §16.2): a missing identity that entries refer to takes over the one file their place, name or MBID names with a duration within 1 s from a new identity without user data, never one that two identities could take; an entry without an identity is linked by key, place, name and MBID in that order; M3U8 lines are parsed, written and matched to library files (library.md §16.6) |
| M2b | Start-up mirror: when the user database's change counter differs from the mirrored one, favorites, statistics and the badge state are mirrored again; M3 adds the merged values of decided fields and the Keep as-is/Skip state |
| M2a | IPC from `:tagger` for the tag pass: each result stays under 128 KB; a larger result or a value over 16 KB travels through a file; no cover bytes cross the process boundary |
| M2b | Legacy encodings, per file: "Кино", "Ёжик" and "Би-2" stored as Windows-1251 are decoded as such in automatic mode; "Beyoncé", "Motörhead", "Sigur Rós" and "ÉTÉ" stay ISO-8859-1; ID3v1, encoding-0 ID3v2 frames and RIFF INFO |
| M2b | Manual legacy encoding: every code page of the list decodes its own sample (also a Shift_JIS second byte in the ASCII range and a broken two-byte sequence); the choice ignores the language condition and the word rule but keeps valid UTF-8; KOI8-R text in automatic mode reads as Windows-1251, which is why the choice exists; an unknown or unsupported stored name is Automatic |
| M2b | "Songs missing information": the reasons of §1 on merged values, including a title from the file name (level 5, or level 4 equal to the display name), the cover rule (embedded and saved in M2b, the album thumbnail in M4a) and the cover and MusicBrainz filters being off by default |
| M3 | Snippet rules (§6): the window starts at one third from 45 s up, at 0 below 45 s and for an unknown length, and lasts 15 s; the main player is paused only when it plays and resumed only after an end, a close or a failure, never after a focus loss or unplugged headphones; a refused focus request plays nothing; a second snippet keeps the first one's memory of the main player; a duckable loss lowers and a gain restores the volume; the focus request is abandoned before the main player resumes; a stale focus listener of an earlier snippet changes nothing; the progress ends at a known length below 15 s; the main player counts as playing only with play requested, ready or buffering and not held by a short interruption; the snippet player's end and unplugged headphones reach the rules; the service's hold for the snippet ends with a resume only when asked and the sleep timer has not stopped playback meanwhile, ends without one when the main player plays by other means, and after a swipe from recents a hold that ends without a resume stops the service |
| M3 | Keep as-is and Skip are remembered by identity and mirrored; a Skip lapses once the tag pass finds no file of its track holding the size and modification time it was made with; undoing either restores the earlier list state. In detail: the Keep as-is or Skip that counts is the newest by row ID that is neither undone nor lapsed, whatever value decisions lie between, so undoing the newer brings the older back and a lapsed Skip leaves an older Keep as-is counting; it becomes the `missing_info_decision` row of `decided_mirror`, which a whole run compares like a decided value and the display rows take as `missing_info_decision`; a Skip lapses once no file of its track holds its size and modification time (a changed file, a file that left the track's key) and not while one does (a copy, a move, a rebuild) or while a file without a key yet holds that stamp; a copy with the skipped file's size and modification time keeps it after the skipped file changes, and a skipped file that left the library lets it lapse once another file of the track is read; the list leaves out Keep as-is and marks Skip |
| M0 | Filename parser: a table of real-world names → (artist, title, track number) or "unknown" |
| M0 | AcoustID request builder: `meta` is space-separated, `duration` is an integer string rounded to the nearest second, body is gzip-compressed, the key stays out of the URL; answers: error 4 stops at once, error 14 and HTTP 429, 500, 502, 503, 504 and 520 to 524 are retried, also when the body is not JSON (such a page counts by its status, so one with any other status, 200 included, is final), other errors are final; recordings with an ID only and results without recordings are kept |
| M3 | Retry later, a single file (§3.2), with an injected clock: an empty AcoustID result stores a lookup job whose retry is 1 hour later, and the run reports that time; an Identify before it sends nothing to AcoustID, decodes nothing and still runs the text search; the first Identify at that time asks AcoustID once; results there remove the job, and a second empty result makes it final, after which stage 3 is skipped; a text candidate of at least 0.50 keeps the first empty result from storing a job and removes a waiting one in a later run, but leaves a final one; a run whose stage 3 gets no answer leaves the job; a next attempt exactly 1 hour after stage 3 checks it waits, and one later than that (a clock set back since the job was stored) counts as due; the retry time is reported only by a run that stored the job (or tried to, when the write fails) or waited for it, not by a high-band match before stage 3 nor by a due retry without an answer; with a job that cannot be read, results remove the job and an empty answer or a text candidate leaves a final job as it was |
| M4a | Retry later, batches (§3.2): the next batch asks AcoustID once more for a file whose empty result is waiting or final after the single-file retry, then the empty result counts as final |
| M3 | Pipeline persistence (§3 "One run", §10.1), on the shared fakes with an injected clock and an in-memory store: a run replaces the candidates of the run before (best first, at most 10, the suggested edition with the best, readable back with their source, score and reason); it writes one trace row per stage it reached and one for its outcome, with the provider, the HTTP status of the stage's last answer (also a busy 503 of a failed stage), the requests, the results, the best score and the elapsed time, and no file names, tag values or error messages; the trace keeps the last 3 runs of a content key in the order they were written; a run ended by a blocked call, a run cancelled while it decodes and an unreadable file write nothing; a store that cannot be read or written leaves the outcome as it was. Stage 0 of a library file the tag pass has not read takes its content key from the read through `:tagger`, also when the row holds an older key, counts the read against the file and writes no tag values, key or length to the library database; a file the pass has read takes the stored key and reads nothing |
| M3 | Descriptor copy (§3.2), with a fake reader and a virtual clock: everything the reader gives reaches the copy in order; a copy past the size bound, past the time bound (a reader that stops giving data without ending), with less free space than the reserve at its start or once another writer has taken the space while it runs, or cancelled by its caller is refused with its reason and the partial file deleted; a reader that fails deletes the partial file too, and so does a writer's error that the descriptor reports at the end (asked once, after the end); only copies older than an hour are swept, and never a copy that another copy is still writing, even when the wall clock has jumped ahead by more than an hour. The decoding table in code: one row per MIME type, FLAC with its end-of-stream rules, PCM in the container without a decoder, ALAC without a platform decoder, a codec outside the table on the platform route, and the decoder path built from the Media3 version, the MIME type and the decoder |
| M0 | Text query builder: Lucene escaping, lower-case terms, the variants of §3.3 in order, skipped variants for missing values, the stop rule and the limits |
| M0 | PCM conversion: 8-, 16-, 24-, 32-bit and float input, stereo and 5.1 downmix, rounding and clipping to 16-bit; the fingerprint path stops at 120 s, skips the resampler at 11 025 Hz, flushes it at the end of a short file and starts a new one when the format changes |
| M3 | Recording scoring (§4.1): identifier tiers (a tag recording ID within 7 s; an ISRC within 2 s, and the similarity tier for an ISRC with several recordings or a length outside the window; AcoustID score × duration credit), duration credit, the 0.85 cap for text, the duration penalty on every tier (for the identifier tiers checked on the penalty step itself, since today's windows and credit never let it show in a score) and the title/artist penalty on the similarity tier only, an identifier hit's disagreement shown in its reason, merging by recording with the highest score and a field one stage lacks (an AcoustID recording known by its ID only) taken from another stage's copy, the band boundaries, and a score of at least 0.90 with a runner-up gap below 0.10 landing in the medium band |
| M3 | Text search input (§3.3): the hint from decoded tags, the file-name guess and the exact duration, tags winning per field; track positions from the medium, never the printed number (vinyl "A4", numbering across discs); a manual query is marked manual with limit 50; pasted MusicBrainz links and IDs become recording or release lookups (also a link without the scheme whose query holds another link, and one with a label glued on), other MusicBrainz links are refused |
| M3 | Release ranking on recorded MusicBrainz JSON fixtures (for example a track with hundreds of same-title recordings, video recordings and compilations): the folder preference, the full type order, Digital Media equal to CD, date order, a recording on two tracks of one release; a tagged release MBID that contains the recording is kept without ranking; paging by the number of releases returned, a best release on the second page and the 10-page cap; the request budget of §3 (a high-band fingerprint hit, a text-only file) with MusicBrainz requests at least 1.1 s apart; the release details of §5.1, also for a Various Artists compilation; fixtures stripped of CC BY-NC-SA `tags`/`genres` before committing |
| M3 | Edition picker: every release of the group is listed; an edition without the identified recording maps the file by position and title or is marked "does not contain this song" |
| M0 | Rate limiter: never exceeds the configured rate under parallel callers; a pause holds back waiting callers; backoff with jitter on 503, 429, 502 and 504; Retry-After honoured; a rejected User-Agent is not retried; after giving up on a Retry-After over 120 s, later calls fail at once without a request until the pause is over |
| M3 | Sending rules (§8): the app's online gate is closed by default; with the gate closed no client sends a request (AcoustID, MusicBrainz, a Cover Art Archive image and its redirect hop); a repeat the HTTP library makes by itself after the gate closed (a 408 or a 503 with Retry-After 0, also after `Connection: close`, or a dropped pooled connection) looks up and connects nothing; a Retry-After of digits too large for the HTTP library (on a 408, 429 or 503, also in digits of another script, and any digit outside the Basic Multilingual Plane) ends no thread and still ends the retries and pauses the AcoustID, MusicBrainz and CAA limiters for 1 hour, while an HTTP date, a negative, a fractional, an empty or a non-numeric value reaches the client unchanged; closing the gate during a backoff sends no further attempt; a blocked call is neither retried nor pauses the limiter; a network that is not validated, or a captive portal, fails at once without a request; a MusicBrainz 403 for the User-Agent and AcoustID error 4 stop that provider for the process, and a stop in one set of services does not reach another; a MusicBrainz 200 with an HTML body is retried, then reported as unavailable; dropped connections and failing DNS lookups are retried and given up, with the limiter paused only by the backoff; one limiter per provider at 1.1 s, 0.35 s and 1 s |
| M3 | Online-lookup opt-in (§9, §10): the gate of the settings file is closed with an empty file and before the file is read; Enable opens it, Not now and turning the setting off close it, and turning off closes it before the write ends; a turn-off cancelled before its write keeps it closed until the file says off or Enable is stored; a settings file that cannot be read is read again; a build with a higher disclosure version finds it closed and asks for the disclosure, and Enable there opens it; a restored file opens it only with the current disclosure version; a change written by another writer through the process's settings file reaches the gate; an Enable the gate never sees stored leaves it closed, and overlapping answers report that they have ended once, after the last; turning the setting off while a lookup waits for its retry, or while a second lookup waits for the limiter, sends nothing more; the app's services use this gate; the protected-fields list keeps names it does not know |
| M3 | Cover URLs (§5.2): `http://` → `https://` for addresses from MusicBrainz or CAA JSON only, never for the configured base address, and other schemes dropped; a release with a usable front image (`front-250`, `front-500`, `front-1200`, original); a release without one or a darkened one (release group images, marked as another edition, none without a release group ID); unknown flags ask the release; a missing release front image (404) is followed by the release group's, marked as another edition, and a 503 of the release does not ask the release group; no address for an ID that is not an MBID; the release index on the fake CAA (images, thumbnails with the older names, the front flag, 404 as no images); index addresses on coverartarchive.org are moved onto the configured base address and fetched from the fake, addresses on other hosts are dropped; a redirect with the User-Agent on each hop; 404 after a thumbnail redirect falls back to the original, a 404 without a redirect, or after a redirect that stays on CAA, does not; a live 404 entry of a front original answers every thumbnail size without a request; an index image's thumbnail falls back to its own original after any 404; a 503 pauses the CAA limiter and records a failure, and one with a Retry-After over 120 s pauses it for that time, at most 1 hour, with later calls failing at once without a request; 429, 502 and 504 are retried and record nothing; a cached failure sends nothing; with the gate closed or the provider stopped nothing is sent and nothing is recorded; failure TTLs with an injected clock (404: 7 days; 503: 5 minutes, doubling on each further 503 of the address up to 6 hours, starting again at 5 minutes when the previous 503 entry expired more than 6 hours ago; a success clears it); image sizes from JPEG, PNG and GIF headers |
| M3 | Cover size rule: a proposed cover whose short side is shorter than the existing cover's starts unticked, an equal one ticked; with the current size unknown a system thumbnail counts as ticked, an embedded picture has its header read on demand (and not read when the rule does not need it), an unreadable header counts as no cover; no cover now starts ticked; a proposed cover of unknown size starts ticked over a system thumbnail and over an embedded picture whose header cannot be read, and unticked over a cover of known size or a readable embedded picture |
| M3 | Database-only undo: Apply followed by Undo restores the earlier merged values; stacked decisions (two identifications in a row, an edit on top of an identification) resolve as in §7.1; Undo right after Apply, Keep as-is and Skip; a later file change flags the field per location |
| M3 | Decided values per field (§7.1, §10.2): a table of decisions (apply, edit, revert_field, undone or not, in either order) → winner, level and decision for every part of every field; an identification after an edit wins, a revert hides only older values and its undo brings them back, an empty edit wins and leaves the title to the file name, in the parts that track details read as in the lists; decisions are ordered by row ID, so a later row made at an earlier time wins; a decided original date does not hide the index's year of an unread file, and gives the year only when neither the date nor the index does; a field's parts never mix between decisions; every field of §1 resolves with its level from 1 to 5 (decided, tags, the index while unread, the file name); the reasons use the decided title, artist, album, cover and recording ID; the badge state follows the levels that win; the stored encodings of the parts, the reason and the file references round-trip; the file references flag a changed field per location, a move with the same tags flags nothing, and "Keep PiPlayer's value" takes the present value |
| M3 | Album key with decided values (library.md §6.2): a file's key comes from its own values whatever is decided; a group whose files all end up with one release MBID, decided or from the tags, takes that release's key, while one file without it, or with an empty decided value, keeps the group's own key; a group whose tags carry a release MBID moves only when every file has another one decided; a file without an album of its own takes its key from its decided release MBID or its decided album |
| M3 | Identity at Apply (§10.2): for an unread library file the rules of re-attachment for that one file give it to a missing identity hinted at its place within 1 s, never to one 2 s off, to one at another place or to one that is not missing, and otherwise make a new identity; an opened file's identity, missing from Apply on and holding its decision, survives the 30-day deletion and is attached by its key once a library file holds it, its hints and length following the file |
| M3 | Folder hint (§4.2 key 1), on rows as the library gives them: a release decided for a file of the folder or of its disc folders, or tagged on one without a decided value, is confirmed in its usual form; a decided empty value hides the tags' one; another folder below it, a value that is no MBID and a file outside the library count for nothing, and the last reads nothing |
| M3 | Backup rules (§10.3): the folder of the saved covers lies in the no-backup files, and no rule of either file includes it; the user database and the settings file stay the only included files |
| M3 | Listening outcomes (PIM-89, playback.md §12): 29.9 s of listened time at a skip is an early skip and 30 s a late one, a late skip has its share of the length (none without a length); an automatic transition, a repeat-one replay and the end of the queue are listened to the end whatever the listened time, and so is a move within the end of the song (§4.3.1 zone), one just before it a skip; a new queue of another song is a skip, one of the same song or of nothing no outcome; no outcome after a playback error or without listened time; a restore that moves off the end of a saved song whose file is known finishes its playback, a resumed, a gone or an opened one not |
| M3 | Identification outcomes: a call blocked by the online gate, by a missing validated network, by a captive portal or by a provider's stop maps to its own outcome (online lookups off, no network, sign-in needed, provider stopped with the reason) and other errors map to none; a run that found nothing above 0.50 in a build without an AcoustID key reports the missing key, with a key it reports no match, and a match keeps its band in either build; provenance takes the provider's license class (MusicBrainz CC0, Cover Art Archive and AcoustID restricted), the confidence and reason of the match and the release track and AcoustID track IDs; an apply request needs at least one ticked field and carries a cover only when the cover is ticked |
| M3 | One run of the pipeline (§3), on the shared fakes with virtual time: a fingerprint cached for the content key is encoded from its raw items and nothing is decoded, while a missing one is decoded once and the session's string is sent without encoding it again; the AcoustID track ID of the best result is cached; a length left for a walk is walked before the lookup and the walked length is sent, and a walk that finds none, like an unknown length, sends nothing to AcoustID and still runs the text search; a high-band stage skips the stages after it (tag recording ID, fingerprint) while a medium one does not; a tag release MBID whose release contains the recording is the suggested edition; a closed gate, a MusicBrainz stop, no validated network and a captive portal end the run before stage 0 with their outcomes and no request, a gate that closes during the run ends it the same way, a build without a key or with AcoustID stopped sends nothing to AcoustID and decodes nothing, and an unreadable file sends nothing; an empty AcoustID result marks the file for a retry and a text candidate of at least 0.50 takes the mark off; a file that cannot be decoded falls back to text search, a fingerprint cache that cannot be read counts as a miss, a computation that fails in another way fails the stage, AcoustID stopping while the file decodes only skips the stage, and a stage 0 that fails in any way ends the run as unreadable; a stage that fails is traced and the next one runs, and nothing found after a failure reports the provider as unavailable; AcoustID refusing the key during the run with nothing found reports AcoustID as stopped for an invalid key, unless another stage failed too, which reports that provider as unavailable first; stage 0 takes a library file's stored length as the tag pass left it (`header` and `walk` exact, `unknown`, none or 0 unknown, `pending-walk` walked from nothing and `pending-flac-end` walked to check the stored header length); the request budget of §3 holds for a high-band fingerprint hit, a text-only file and a file with tag IDs; progress names the stages in order and counts the release pages; a cancelled run sends nothing more. The debug spike's fingerprint cache keeps a computed fingerprint and its AcoustID track ID in memory only, and progress of a cancelled or replaced spike run leaves the screen's state alone |
| M4a | Batch album groups: groups with a release MBID or an album title match the library's album entities; files without an album tag are looked up one by one and merged when their recordings resolve to the same release; one release per group (most recordings); a warning for split groups and folders |
| M4a | Work constraints: "unmetered only" → `UNMETERED`, "Use mobile data this time" → `NOT_ROAMING`, roaming allowed → `CONNECTED`, battery not low; the pause and resume rules for underruns and thermal status |
| M4b | Tag diff: only decided keys change, and the full property map goes back with TagLib's own strings for unchanged keys; "fill empty only" fields are untouched when non-empty; protected data never appears in an identification diff |
| M4b | Per-format rules (§7.5): multi-values, dates and partial dates, positions from `position`, totals, compilation, NFC, front cover selection, ID3v2.3 capabilities; the ID3v1 policy (§3.1) |
| M4b | Own-write recognition: a sync event that matches a `pending`, `streamed` or `verified` entry keeps values and provenance and raises no flag, also when the change notification arrives between the stream-back and the verified commit |
| M4b | Undo of a written change: only keys whose file value still equals the written value are restored; the others are listed as conflicts |
| M4b | IPC from `:tagger` for writes: the same limits as for the tag pass |
| M5 | Backup rules, in both rule files: `user.db`, its WAL and the `settings` DataStore file are included; `library.db`, caches, saved covers, snapshots, working copies, the persisted queue and the `device_state` DataStore file are not |
| M5 | Trace redaction: a problem report includes the identification trace without file names and tag values unless the user adds them |

### 11.2 Instrumented tests (emulators API 30, 33, 35, 36; in M0 also a 16 KB page-size image)

| Milestone | Test |
|-----------|------|
| M0 | Chromaprint JNI: fingerprints of bundled CC-licensed samples are similar to desktop `fpcalc` output (at most 10 % of bits differ over the aligned overlap; the threshold is calibrated in M0) and return the same AcoustID; never exact string equality (decoders differ). Measured in M0 through the device's own decoders and resampler against fpcalc on the same files (MP3 44.1 kHz, Ogg Vorbis 48 kHz, FLAC 48 kHz/24-bit, FLAC 96 kHz/24-bit, Opus 48 kHz): offset 0 and at most 1.4 % of bits differ (MP3), the others under 0.02 %. M3 adds three recordings and makes the AcoustID lookup opt-in (M3 row below) |
| M0 | Resampler: a 9 kHz tone at 44.1, 48 and 96 kHz comes out at least 40 dB below a 1 kHz tone of the same level (9 kHz would alias to about 2 kHz, inside the band Chromaprint uses); 1 kHz keeps its level within 0.5 dB and its phase; the output length is exact and does not depend on how the input is cut |
| M0 | Decoding fixtures (2 s of 1 kHz): MP3, AAC, Vorbis, Opus, FLAC 96 kHz/24-bit, FLAC 5.1, FLAC with frames larger than 64 KiB and no frame size in its header, WAV in 16-bit, 24-bit and float, WAV at 11 025 Hz; each at its own rate and channel count, never shorter than 2 s (encoder delay and Opus pre-skip are trimmed to the sample) and at most one frame longer (the platform trims MP3 padding, but AAC padding and the last Vorbis and Opus packets stay, which does not move the fingerprint), and still 1 kHz at the same level after the whole fingerprint path. Edge cases: an MP3 whose sample rate rises halfway reaches the consumer in both rates; a FLAC file cut off in its last frame keeps what was decoded; a single-frame FLAC is decoded once; a 64-bit float WAV is refused |
| M0 | TagLib reads the tag fixtures of every format |
| M2a | Content key through the real reader: unchanged after tag edits in every format (ID3v2 growing past its padding, a doubled ID3v2 tag, ID3v1 and APE added or removed, FLAC metadata blocks, MP4 with `moov` before and after `mdat`, a rebuilt Xing frame), unchanged under any file name and without one, changed when the audio changes. Ogg Vorbis and Opus: unchanged after comments are rewritten across page boundaries, also when the number of header pages changes and when a large embedded cover is added; the bounded page walk (D10) reads about as much of a file as the key of other formats and none of its cover; a chained Ogg file takes the token `file` |
| M2a | Exact duration of VBR MP3 without a frame-count header and of raw ADTS matches the decoded length within one frame; truncated WAV, MP4 and FLAC files report the length of what is left |
| M2a | Re-attachment (§3.1): a moved or renamed file and a media index rebuild with new IDs keep their track identity, which favorites, playlists and statistics refer to from M2b; a moved Ogg file whose comments an external tool rewrote keeps its content key and re-attaches by it |
| M2a | `:tagger` isolation: a fixture that crashes the native reader and a call that hangs for more than 30 s are counted; the second time the file is marked unsafe; playback continues |
| M2b | Legacy-encoding fixtures through the real reader: the Windows-1251 ID3v1, ID3v2 and RIFF INFO fixtures read in automatic mode and with the Windows-1251 choice, the KOI8-R and Shift_JIS fixtures with their own choice; a wrong choice shows other text, and Automatic brings the right text back without reading the file again; every code page of the list is supported by the device |
| M2b | Deleting `library.db` keeps favorites, playlists, statistics and (from M3) decisions, which re-attach after the rebuild; from M3 the decided values come back into `track_display` once the keys come back, also after a rebuild of the library |
| M2b | Playlist entries resolve after a move, a rename and a media index rebuild; an entry of a deleted file stays, unavailable, and comes back with the file; an M3U8 export imported again on the same device gives the same songs (library.md §16.7) |
| M3 | Chromaprint additions: encoding a fingerprint's raw items gives the string of the session that computed them (on synthetic audio and on the five recordings; no items give an empty string; an unknown algorithm is refused). The device comparison with fpcalc gains three recordings that the preparation script makes from the CC samples at their full length: the FLAC overture upmixed to 5.1 AAC in MP4 (the decoder must hand all six channels on to the downmix, not a stereo downmix of its own), the 96 kHz FLAC as stereo AAC in MP4, and the Ogg Vorbis recording as a constant-bitrate MP3 without a LAME or Xing header; each within 10 % at offset 0 ± 1 (measured on the API 30 and 36 emulators with Media3 1.12.0-alpha02 and again on API 36 with 1.12.0-beta01: 1.1 %, under 0.01 % and 1.3 %, all at offset 0). The AcoustID lookup of the samples is a real call: it runs only with the instrumentation argument `-e liveNetwork true` and an AcoustID key, never by default (the test helper never passes the argument) and never on CI, which has no key |
| M3 | Decoding table: every format the player plays (playback.md §1.7: MP3, also at 320 kbit/s, AAC in MP4 and raw (ADTS), AMR narrowband and wideband raw and in 3GP, Ogg Vorbis, Vorbis in Matroska, Opus in Ogg and WebM, FLAC, also in 5.1, and WAV) is read with `MediaExtractorCompat`, decoded by a platform decoder (WAV without one) and all of its audio reaches Chromaprint at 11 025 Hz; ALAC in MP4 is refused as an unsupported format, because the platform has no ALAC decoder, so in 1.0 an ALAC file is not fingerprinted and is identified by text search only (bundled decoders come in v1.x); on a phone whose maker adds an ALAC decoder the row expects the platform path instead, with the file's 2 s and at most one 4096-sample ALAC frame more. Every row must have an expected length, so none can drop out of the check unnoticed. The new formats are also decoding fixtures (row M0 above), each at its own rate and channel count, with the 1 kHz tone intact; raw AAC keeps its priming frame and padding (up to two frames more), and the 3GP AMR wideband fixture holds 1.995 s, because the 3GP edit list trims the encoder's delay that the encoder does not flush at the end |
| M3 | Fingerprint wiring: a pipe (`ParcelFileDescriptor.createPipe`) carrying a FLAC file, an MP4 file whose index sits at its end and a 12 s WAV is copied into the cache and gives the same decoding and the same fingerprint as the file itself, and the copy is gone afterwards; a regular file is decoded without a copy; through a real pipe a copy past the size bound, one whose writer stops without closing the pipe (past the time bound) and one whose caller cancels are refused with the partial copy deleted, and a copy an hour old is swept. Every row of the code's decoding table has a decoding-table fixture, every fixture's codec has its row, and the route and decoder path that decoding reports match it. A reliable pipe (`ParcelFileDescriptor.createReliablePipe`) whose writer closes it with an error after some bytes is refused as failed, with the partial copy deleted, directly and through the decoding source. The fingerprint cache with its own decoding source, opening the file through the content resolver, gets such a pipe from a test document provider of the debug build that runs in a process of its own and fails after half a WAV file: on either lane the computation fails with the writer's error and leaves no cache row, while the same provider's pipe that ends cleanly is fingerprinted and cached. Lanes of the fingerprint cache: with re-attachment computations queued behind one that waits, a user's request completes first, on its own thread at normal priority, while the re-attachment computations, decoding included, run one after another on the background thread at background priority; both cache the decoder path. Of two user requests in a row, the first completes, cached, while the second still waits in its decoding; a recomputed fingerprint keeps the AcoustID track ID cached for its content key and replaces the rest. The order is checked, not the speed |
| M3 | Snippet, through the main player's MediaController: a playing main player pauses for the snippet and resumes at the same position when the snippet ends after 15 s of playback from one third of a 60 s file or when the card closes; a main player paused before the snippet stays paused; a file shorter than 45 s plays from its start and the snippet ends with it; while the snippet plays, the audio focus stack holds its `GAIN_TRANSIENT` request, and afterwards the request is gone; queue, position and statistics (the play counter's listened time and playback, with no play recorded) do not change; a focus loss for good (another player) and a transient one (a call that then ends) stop the snippet without resuming the main player; a file the snippet player cannot read ends the snippet and resumes the main player. Through the playback service: a sleep timer finishing the track when the snippet starts still finishes it after the resume, and a countdown that ends during the snippet leaves the main player paused with the timer off; another controller playing the main player during the hold ends the hold, so that a user pause then turns off a sleep timer that is finishing the track and a later end of the hold with a resume leaves the main player paused; a swipe from recents during the hold keeps the service and the card closing then resumes the main player, while a hold that ends without a resume after the swipe stops the service |
| M3 | Session metadata: for a fixture whose embedded cover and tags differ from the saved values, and for an MP3 without a frame-count header, the MediaController's metadata, artwork and duration equal PiPlayer's values after Apply and after Undo, without preparing the file again |
| M3 | Identification and privacy (§9): the disclosure names every service with what it receives, the background Cover Art Archive loads included, and its Enable and Not now buttons and links have labels and click actions for TalkBack; the settings section shows the setting as a switch, opens the disclosure when the current version is unanswered and turns off at once, lists the services and their terms links, changes the protected-fields list with checkboxes, takes a second tap before the first is stored as reversing it, puts the switch back to the gate when an answer was not stored, and shows each provider's status (stopped after a User-Agent rejection or an invalid key, unavailable without a key); the app's services use the settings file's gate, closed with no stored consent |
| M3 | Library database version 13 (§10.1): an older library database is dropped and the new tables and columns are there (the content-key tables, `decided_mirror`, `release_cache`, `api_cache`, the identification columns of `fingerprint` and `track_display`, both counters of `library_meta`, no cover cache table); the release cache keeps a lookup under the asked and the answered MBID, survives reopening, and treats a row older than 30 days as missing and drops it on the next write; `api_cache` keeps a failure entry's kind, times and 503 count, keeps an expired entry for 6 hours so that the next 503 still doubles, then drops it, and the Cover Art Archive TTL rules hold through it; `decided_mirror` keeps one value per file and field, replaces a file's rows whole, clears, and the mirrored change counter moves apart from the rules counter; the app's services use these caches |
| M3 | User database version 6 (§10.2): version 5, created from its committed schema with rows in every table, migrates to 6 and validates against the committed schema of 6, keeping its rows and counters, with the new tables empty, `play_stats` rows with listening counts of 0 and `play_event` rows under its new key without row IDs; decisions, `file_state` rows and undone decisions keep their identities through the attach pass; apply, edit, revert and undo raise the change counter in their transaction, and two identifications of one release with the same values share one release choice; "Undo identification" undoes the newest identification by row ID, also after the clock was set back; undoing a revert or a newer decision gives a second location the references of the decision that wins again, so its unchanged values are not flagged |
| M3 | Decided values in the library (§7.1, §10.3): a decision reaches `decided_mirror` and `track_display` (values, search text, reasons and badge state) for every file of the track, an undo takes them back; the sync, the tag pass and a new display version keep them; a file whose key comes later gets them after its tag pass batch; a process that dies between the two commits (the user database holds the decision, the library database not) shows the decision after the next start, and a start-up whose counters agree writes nothing; a start makes one whole run, the start-up run when the counters differ (the run the start asks for itself, which the crash test tells apart from the one after the first attach pass) or else the one after the first attach pass, and a start-up run that fails leaves that run to the first attach pass; a whole run that waits for the sync while the sync deletes a file holding only the rows of an undone decision writes nothing and raises no version, and one that waits while the tag pass moves keys compares those files again, both tested at the moment the run waits rather than after a fixed delay; while a whole run reads `decided_mirror` in ranges, another reader of the library database (on a database file, as in the app) still gets a connection; a caller cancelled while the run after its decision waits for the lock still gets its decision mirrored, and a run that fails after the commit returns the decision and asks for a whole run that brings the library level; the snapshot of every decision and the display writer decode and resolve on the cores left for that (every core but one), so each waits while all of those cores are busy and finishes once they are free |
| M3 | Keep as-is and Skip in the library (§1, §7.1, §10.3): Keep as-is and Skip raise the change counter in their transaction and store the file's size and modification time with a Skip; Keep as-is takes the track out of Songs missing information and its count for every file of its key, Skip leaves it listed and marked, and undoing either brings the earlier list state back; both stay through a sync that stores every file again, a new display version and a whole run; after the tag pass reads a skipped file that a tagger changed, the Skip lapses (`lapsed_at`, the counter raised, the mark gone from every copy), and so it does for a file whose content key changed, while a copy that appears keeps it, and so does a copy read before the skipped file has its key again, as after a rebuild with the skipped file's read failing; a changed skipped file is found by its track's location hint once a whole run has cleared its rows, also when its read fails, and lets the Skip lapse; a lapse whose mirror run fails asks for a whole run; checks after a batch that fail are made again before the batch's mirror run and before a whole run asked for meanwhile, in the next pass of the mirror or in the same pass as the failed batch; the files that hold a Skip's stamp are found by exact lookups of the index on `content_key` and `size`, those without a key too; a read of a file whose decided part changed in the file flags the part at that location; Keep as-is and Skip walk the same index as the list without them |
| M3 | Listening outcomes (PIM-89, playback.md §12, library.md §15.7): the tracker on a real player with a test clock writes "Next" before and after 30 s as an early and a late skip, a transition to the next song, a repeat-one replay and the end of the queue as listened to the end, a new queue of another song as a skip and of the same song as none, nothing at a release and one outcome for a playback that ended the queue and went on; `user.db` writes an outcome by its identity (a second one of the same playback ignored, none without an identity), raises no counter, folds events older than 90 days into the counts of `play_stats` (a row with `play_count` 0 for an identity without a play, which the plays, the song details and the mirror do not read), and does not count listening outcomes among the identities that hold data: an identity with only listening outcomes is deleted after 30 days missing together with them, and a takeover moves them to the missing identity, so that listening during step 4 does not block it; the tracker writes nothing for a new queue whose first entry is an added entry of the playing file, after a playback error (until a retry plays) or for a song without playable audio that the service moves past, and listened to the end for a restored item that the length check moves to the next song and for stop after current track followed by a new queue; through the playback service a library song skipped with "Next" and the next one played to the end of the queue reach `listen_event` by their identities |
| M3 | Size budget of `user.db` (§10.2): generated data at the volumes of the estimate (20 000 identities, one identification each with every field but the cover ticked, half of them on files without tags and half on files whose tags differ in 8 parts, a second location that differs in 4 parts for a tenth of the tracks as `file_state` rows, titles of about 18 characters, 2 000 release choices, a year of plays, 9 000 listening outcomes of 90 days with the folded counts of 15 000 identities, favorites and 5 000 playlist entries) stays under 16 MB minus the 1.5 MB reserve for the M4b tables, measured on the database file after a checkpoint (13.0 MB on the API 36 emulator in M3 before the references and `file_state` rows were added; 14.64 MB on API 30 and 36 with the listening outcomes and the reason as a short text, over the limit; 14.28 MB with the 7-byte reason blob, 0.22 MB under the limit) |
| M3 | Timing (library.md §1.7, on the generated library, quiet pass): with a decision on every fifth of its 20 000 files, each write path that joins the decided values (a sync that stores every file again under a new index version, a full refresh of every display row, a refresh through the writer) is measured in the same run without and with the decided values mirrored, alternately, so that the host's load cancels out, and the decided values may add at most 0.15 ms per decided file to the faster of the runs without them (600 ms at 4 000); the start-up mirror of all 4 000 decisions may take as long as the full refresh without them in the same run, and half of that when it finds every value mirrored. With a decision on every one of the 20 000 files, a whole run that finds every value mirrored may take as long as a full refresh of the same run, and it holds off the sync for at most half of that. Without the decided values, every decision is undone, as in a library without decisions, so that the mirror runs the write paths ask for themselves keep `decided_mirror` empty. A first sync on an empty library database joins nothing and is not measured here (measured before M3 step 12 on the API 36 emulator: first sync at most 2.3 s, full refresh at most 2.5 s). Measured in M3 step 12 in three quiet passes on the API 36 emulator (Pixel_8, 4 cores, host CPU 20 to 21 %): with a decision on every file, full refresh 3.19 s, the whole run that finds every value mirrored 1.74, 1.74 and 1.68 s, holding off the sync at most 86, 98 and 93 ms (before the step's changes 4.56 s and 1.76 s against a full refresh of 3.20 s); with a decision on a fifth, the decided values added 0.21 to 0.42 s to a write path, the start-up mirror took 1.20, 1.19 and 1.16 s against full refreshes of 1.74, 1.91 and 1.71 s, and 0.36 to 0.37 s when it found every value mirrored. After the step's review (three ranges, which leave a reader free, and every core but one for decoding and resolving), in three quiet passes on the same emulator (host CPU 21 to 22 %): with a decision on every file, full refresh 3.43, 3.41 and 3.31 s, the whole run that finds every value mirrored 2.31, 2.22 and 1.96 s, holding off the sync at most 114 ms; with a decision on a fifth, the decided values added 0.31 to 0.56 s to a write path (the sync that stores every file again took the most), the start-up mirror took 1.35, 1.30 and 1.30 s against full refreshes of 1.71, 1.72 and 1.75 s, and 0.38 to 0.44 s when it found every value mirrored. On the 4-core emulator the core left free costs a whole run about 0.3 s and the fourth range about 0.15 s |
| M3 | Minified build (§9): every identification client decodes its service's answers in the R8 build. `MinifiedClientsTest` sends the fixtures of the AcoustID lookup and its error answer, the MusicBrainz search, the recording, ISRC (`inc=artists`) and release lookups, a pasted ID and the paged release browses of a recording and of a release group, and the Cover Art Archive front image and release index through the clients and the HTTPS fakes, compares the decoded values and checks that R8 renamed the DTOs. The normal run and CI run it against the debug build. The minified run uses AGP's own switch, not a self-test inside the app: `-Ppiplayer.minifiedTests=true` makes the `benchmark` build (the release build with R8, signed with the debug key) the tested build type, adds keep rules for the entry points that only the tests call and for the libraries the test APK shares with the app (`app/proguard-minified-tests.pro`, never part of a release), and compiles only this test, the test runner and the shared fakes into the test APK. The DTOs, their serializers and the parsing are shrunk, optimized and renamed as in a release, so the test reads the results through their `toString`. The libraries that the test APK shares with the app (the Kotlin standard library, coroutines, OkHttp, okio) are kept whole and are not processed by R8 in this run, so the check covers the app's clients, DTOs, serializers and parsing, not the HTTP stack under R8. A local run on the emulator, never on CI: `gradlew :app:installBenchmark :app:installBenchmarkAndroidTest -Ppiplayer.minifiedTests=true` with `ANDROID_SERIAL` set to the emulator (the install tasks serve every connected device), then the test helper with `MinifiedClientsTest --no-install -e minified true`; with that argument the run fails unless the build under test is the `benchmark` one, so a reinstalled debug build cannot pass for it. The test APK keeps the debug run's package, so the next normal run installs again instead of using `--no-install`. The keep rules name the entry points by hand: `MinifiedTestRulesTest` (JVM, so on every CI run) fails when one of them is renamed or removed, when the shared fakes or `IdentificationServices.create` take an app type the rules do not keep, when the shared fakes' bytecode reaches an app class or member the rules do not name (a type built only inside a body, or a `$default` bridge that a call leaving out a defaulted parameter compiles to), or when `MinifiedClientsTest` imports an app type the rules do not keep, other than the result type it checks for renaming. It reads names, not R8's behaviour, so the minified run is repeated whenever `IdentificationServices.create`, the shared fakes or the clients' calls change |
| M3 | Pipeline end to end (§3): a fixture file opened by its URI is read through `:tagger`, decoded on the device, fingerprinted and encoded with the native library, looked up on the HTTPS fakes of AcoustID and MusicBrainz and gives a high-band match with the suggested edition, its release pages in the progress and the fingerprint with the AcoustID track ID in the library database's cache; a second run takes the cached fingerprint and decodes nothing; with the gate closed nothing is read or sent. A library file in the media index that the tag pass has read takes its content key, tags, folder and target from the library database, and a length the pass left for a walk is walked in `:tagger` and sent to AcoustID; a library file the pass has not read is read through `:tagger` and gets its key from the file. The debug spike runs the same pipeline, over its own fingerprint cache in memory instead of the library database's |
| M3 | Working rows of identification in the library database (§10.1): the Room store keeps a lookup job by content key and deletes it, replaces a content key's candidates in position order without touching another key's, and drops the trace runs of a key beyond the last 3 in the order they were written, also when a later run has an earlier `started_at` |
| M3 | Identity at Apply on the real databases (§10.2, library.md §8.1): Apply of a result for a file opened from another app makes a missing identity with the provider's display name and size as hints; 31 days later the attach pass keeps it, and once the file is in the index and the tag pass has stored its key, the attach pass attaches it and the decided values reach its display; Apply on a library file the tag pass has not read makes its identity with the file's place, or gives the key to the missing identity hinted at that place, and its decided values reach the display after the tag pass reads it; a library file that is gone writes nothing |
| M3 | Album groups on the real databases (library.md §6.2): identifying one track of a tagged album keeps every track's album key, the album's favorite and own cover and the track's own cover and favorite; identifying every track to one release gives them the release's key, also through a write of every display row and of one of them; a new file in the album's folder and an undo bring the group's own key back |
| M3 | Folder release hint on the library database (§4.2 key 1): a release decided for a file of the folder, or tagged on one without a decided value, is confirmed for the folder and its disc folders and ranks first; a decided empty value hides the tags' one; another folder's release and an undone decision count for nothing |
| M3 | Saved covers (§5.2, §10.2): an accepted JPEG is stored once under the hash of its bytes in the no-backup folder with its row (release, size in pixels, bytes, hash, source), the same bytes for the same release give the same row, for another release a row of its own over the same file; bytes that are no image are refused; a file no row names is deleted when the covers are first used, a row whose file is gone gives none |
| M4a | A 500-file batch started from a folder resumes after its process is killed in any phase, and its accepted results appear in every view and on system surfaces |
| M4a | On the named physical device, not an emulator: playback during a 500-file batch has no audible effect and no more underruns than without the batch |
| M4b | Tag round trip for MP3 (ID3v2.3 with APIC, ID3v2.4, ID3v2.2), FLAC (PICTURE), M4A AAC/ALAC (`covr`), Ogg Vorbis, Opus, WAV: write → re-read → compare property maps → audio bytes unchanged → content key unchanged → ID3v2 version preserved (v2.2 written as v2.3). Also: no ID3v1 created on a file without one; an APE tag in an MP3 preserved; a v2.3 file with `TSST` and `TSOP` (`TSST` as its own diff line, `TSOP` kept); back cover preserved; gapless info and comment frames with a description untouched |
| M4b | Write pipeline on app-private fixtures, through a test implementation of the write-access interface: free-space check, copy, partial update, copy verification, journal states, stream back, verification, the two database commits, undo after process death |
| M4b | Interrupted stream-back: the process is killed in the middle of the stream-back; after the restart the file is marked "interrupted write", and after the (test) consent the kept copy is streamed again and the original equals the verified copy byte for byte |
| M4b | The current item and the next item in playback order are not written underneath the player; "Write now" pauses, stops, writes, prepares again and resumes at the same position |
| M4b | A 500-file apply resumes correctly after its process is killed, with a new consent from the UI |
| M5 | Restore into a fresh install: user data and settings come back, identities re-attach by content key once the library is built, text undo works from the journal's `diff`, and cover undo shows as unavailable |
| v1.x | Folder-grant flows: tree grant, health check, re-grant, background writes |

### 11.3 Manual checklist (M4b, repeated in M5)

System consent dialogs are not automated: they differ by API level and manufacturer and make UI Automator scripts
brittle. They follow a written checklist on API 30, 33, 35 and 36 and on a device with an SD card: one write request
for a selection; files in `Download/` and on the SD-card root; canceling a dialog (the result stays in PiPlayer, no
error); a new consent after the process was killed during apply; the explanation shown before the system dialogs; the
optional media management access on API 31+, with `MediaStore.canManageMedia()` checked on API 31, 33, 35 and 36. The
trash dialog joins the checklist in v1.x.

### 11.4 Corpus check

- M0: the identification spike on the owner's real files, with the correctness of each result recorded; the stage 4
  query builder runs on the JVM against a list of real track titles and records the share of correct top results.
  Planned for 30 files; the owner closed the check at 11 (MP3 with and without tags, AAC and Opus downloads with
  downloader traces in their names, Cyrillic names, a slowed version). Result on the emulator: AcoustID identified 8
  of 11 (scores 0.96 to 0.99, each matching the file's tags or name), and the text search found the same recording
  for 7 of them; the 3 others (a slowed version and two tracks AcoustID does not know) have no correct candidate
  above 0.50 in either stage. Running these files found two defects, both fixed in M0: 320 kbit/s MP3 files found no
  decoder (§3.2), and downloader traces stayed in the names (§3.3).
- M3: 100+ real files (including Russian/Belarusian and rare music, live versions, trimmed files): measure the shares
  of high, medium and not found, the wrong matches in the high band and the wrong-release rate; tune the §4
  thresholds and weights. The high band may hold no more wrong matches than the threshold set by the project owner
  (proposed: 2 %).
- M5: the same check on the full corpus before release; the high-band error rate stays within the threshold.

## 12. Decisions

| # | Decision | Status |
|---|----------|--------|
| D1 | PiPlayer license | **Decided 2026-10-18: GPL-3.0-or-later.** TagLib is taken under its LGPL-2.1 option (its MPL-1.1 option is GPL-incompatible); it ships unmodified as the separate shared library `libtag.so`, while Chromaprint (MIT) with its KissFFT (BSD-3) and the resampler of speexdsp (BSD-3) are linked statically into PiPlayer's JNI library; the licence texts ship in the app. Donations go through an external link, so no proprietary billing library is linked |
| D2 | Monetization (donations?) | **Decided 2026-10-18: donations only through an external link, no in-app payments and no Google Play Billing.** The app stays free with no ads or paid features; mention this when contacting AcoustID and MetaBrainz. Before the Play release (M5), check that Google Play's payments policy allows the donation link in the Play build |
| D3 | minSdk | **Decided 2026-10-18: minSdk 30**, designed to be lowered to 26 later (see §7.4), but only once the folder-grant write implementation exists (v1.x). minSdk can only be lowered painlessly; raising it strands users |
| D4 | Tag engine | **Decided 2026-10-18:** own thin JNI over TagLib 2.3.2 with partial updates, ID3v2 version control and picture writing (§7.5), with an existing open-source Kotlin binding of TagLib as the reference for fd ownership and native error handling; built and checked in the M0 native spike, which confirms or revises this decision. **Confirmed in M0:** TagLib reads every fixture format through a read-only stream over the caller's file descriptor, which it never closes (on Android 11+ closing a descriptor owned by a `ParcelFileDescriptor` aborts the process) |
| D5 | Fingerprint data | **Decided 2026-10-18:** nothing is submitted in 1.0; an optional "contribute to AcoustID" with the user's own user key comes later |
| D6 | Database files | **Decided 2026-10-18:** two files, `library.db` (rebuildable, excluded from backup) and `user.db` (backed up whole), fixed before the M2a schema (§10) |
| D7 | Tag writing path in 1.0 | **Decided 2026-10-18:** MediaStore write requests only, one consent per apply where the platform accepts the whole selection, otherwise as few as possible (§8); the write-access interface exists from day one, its folder-grant implementation follows in v1.x (§7.3, §7.4) |
| D8 | Track identity | **Decided 2026-10-18:** a content key from audio data only, with a layout frozen by golden tests and checked before every own write; re-attachment by location and duration, by recording MBID, and, while the library database survives, by a cached fingerprint (§3.1) |
| D9 | Database access from `:tagger` | **Decided 2026-10-18:** only the main process opens the databases; `:tagger` runs native calls and the walks of §3.1 and returns results over IPC (§7.2 step 11) |
| D10 | Ogg content key | **Decided 2026-10-18:** a bounded page walk (§3.1): header pages skipped by their segment tables, the first 64 KiB of the payload from the following page bodies, the last 64 KiB from the tail of the file located through CRC-checked page starts, the length from the final granule position; chained or multiplexed files take the whole-file token `file`. Ogg has no middle window, unlike the other formats |
