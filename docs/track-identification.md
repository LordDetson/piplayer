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
| A file opened from another app (open-with) | M3 | the result is kept in PiPlayer's database by content key (§3.1) until the file appears in the media index (or, v1.x, in a granted folder); only then can its tags be written (§7.3) |
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
  the user includes skipped tracks. A Skip lapses when the tag pass records a new size, modification time or content
  key for the file; the decision stores the size and modification time it was made with (§10.2).
- Both decisions are stored by track identity in the user database (§10.2) and mirrored into `track_display`
  (§10.3), so they survive moves, library rebuilds and restores, and both can be undone (§7.1).

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
| 1 | Known IDs | MusicBrainz recording/release IDs in tags | recording + release directly |
| 2 | ISRC | ISRC tag | `/ws/2/isrc/{isrc}` → recordings (can clash: always check duration and artist/title) |
| 3 | Fingerprint | first 120 s of decoded audio (§3.2) | Chromaprint fingerprint → AcoustID lookup → recordings with score |
| 4 | Text search | tags + parsed filename + duration | MusicBrainz Lucene recording search |
| 5 | Release choice | recording MBID(s) | ranked releases (§4.2) |
| 6 | Release details | chosen release MBID | full album data (one lookup per album, cached) |
| 7 | Cover | release / release group MBID | Cover Art Archive front image |

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
     position instead);
  4. the first 64 KB and the last 64 KB of the payload, or the whole payload once when it is shorter than 128 KB.
- Audio payload per format:
  - MP3: from the first MPEG frame to the end of the last one, so ID3v2 (with its padding), ID3v1 and APE tags stay
    outside. TagLib's `lastFrameOffset()` is where the last frame starts; the payload ends after that frame's length
    as given by its header;
  - FLAC: the frames after the last metadata block;
  - MP4/M4A (and 3GP): the contents of the `mdat` atom(s) in file order; the `moov` atom, which holds the tags, may
    sit before or after them;
  - WAV: the `data` chunk;
  - Ogg Vorbis and Opus: the bodies of all pages after the last header page, without the page headers, whose
    sequence numbers and CRCs change when comments are rewritten (both formats start audio data on a new page:
    Vorbis after its three header packets, Opus after its two);
  - raw AAC (ADTS): the file without leading ID3v2 and trailing ID3v1/APE tags;
  - formats without known payload offsets (for example Matroska/WebM): the whole file, token `file`; PiPlayer writes
    no tags into them (§7.5).
- Offsets come from TagLib in `:tagger` where it exposes them (for example the first and last MPEG frame offsets).
  Where it does not, a walk over top-level structures (FLAC metadata block headers, MP4 atoms, RIFF chunks, Ogg page
  headers) without parsing their contents is enough; no other own parsers.
- The layout never changes between app versions: golden-value tests freeze it (§11.1, M2a). A different layout would
  need a new key version and a migration that re-keys every identity.
- Ogg Vorbis and Opus use a bounded page walk (decision D10), so the key needs no more reading than for other
  formats and stays the same when another app rewrites the comments:
  - the header pages are skipped by adding up their segment tables; their bodies (which may hold a large embedded
    cover) are not read;
  - the first 64 KB of the payload are collected from the bodies of the pages that follow the last header page;
  - for the last 64 KB, PiPlayer reads the tail of the file (64 KB plus one maximum page size, 65 307 bytes), searches
    backward for the capture pattern `OggS`, accepts a page start only when its CRC matches, and walks forward to the
    end of the file; the final granule position comes from the last page;
  - when the payload is shorter than 128 KB, the whole file is walked page by page;
  - a file with more than one logical stream (chained or multiplexed Ogg) takes the whole-file token `file`, and
    PiPlayer writes no tags into it (§7.5).
- Not a hash of the whole file: it changes with every tag edit and would require reading the whole library once
  (hundreds of GB of lossless files). The cheap key of v0.1 (size plus the first and last 64 KB of the file) is
  dropped for the same reason: tags sit at the start (ID3v2, FLAC metadata, MP4 `moov` before `mdat`) or at the end
  (ID3v1, APE) of the file.
- Own writes: PiPlayer computes the key of the edited copy and compares it with the track identity before the
  original is touched (§7.2 step 3); a mismatch means the audio bytes changed and stops the write. The verified write
  is recorded in the same user database transaction that checks the key once more (§7.2 step 6).
- Re-attachment of an identity to a file, in this order:
  1. the content key;
  2. the same location (volume, relative path and display name, or an unchanged media index ID) with a duration
     within 1 s;
  3. the identity's recording MBID, when exactly one unattached file carries it in its tags and that file's duration
     is within 1 s;
  4. while the library database survives: an unattached file of the same format, with a duration within 1 s, whose
     fingerprint is similar to the one cached for the identity's old key (§10.1). These candidates are fingerprinted
     on demand at background priority, as a one-off for identities that hold user data and stayed unmatched; this is
     not part of the library scan (§8);
  5. for playlist entries also their hints (path, name, duration, recording MBID).

  A file is unattached when no identity holds its key. Re-attachment runs after media index rebuilds, SD card
  changes, moves, library rebuilds and restores from backup. Favorites, statistics, playlists, identification results
  and undo records depend on it.
- Limits: a library rebuild or a restore leaves no cached fingerprints, so a file whose key changed (its audio was
  re-encoded or trimmed by another app) and that also moved re-attaches only through step 3 or a playlist hint. Otherwise its identity stays unattached and keeps its data until a matching file appears; PiPlayer never
  attaches user data through a weaker guess.
- Byte-identical audio in two places shares one identity, so decided values apply to both copies. Per-location state
  (the file's value at a decision, the "changed in the file" flag, written tags, undo snapshots) is kept per file
  location (§10.2), and a playlist entry plays the first available location.
- A file opened from another app gets the same key, so its result attaches to the file once it appears in the media
  index (or, v1.x, in a granted folder).

**Duration.** One exact duration per file, measured once by the background tag pass and stored with its source. The
same value is used for AcoustID (`duration`, rounded to the nearest second), text search (`dur:`), scoring, lists,
track details, the media session and the player's time display (§7.1).

- Taken from the header where it is reliable: FLAC STREAMINFO sample count, MP4 track duration, Ogg final granule
  position (minus the Opus pre-skip), WAV data size, MP3 with a Xing/Info or VBRI frame count. ID3 `TLEN` is never
  used.
- Walked frame by frame with Media3's `MediaExtractorCompat` (the extractors of playback, §3.2), without decoding:
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

- The bytes come from TagLib's Latin-1 strings (each character maps to one byte) for the frames the reader reports as
  encoding 0, for ID3v1 and for RIFF INFO. Vorbis comments, MP4 items and APE tags are always Unicode.
- Automatic mode (default): the candidates are ISO-8859-1 and, when a device or app language is Russian, Belarusian
  or Ukrainian, Windows-1251. The choice is made per file, over all its legacy fields together: Windows-1251 is chosen
  when at least one word (a run of letters) of its result is entirely Cyrillic and no word mixes Latin and Cyrillic
  letters; otherwise ISO-8859-1. The choice is stored in `audio_file.tag_encoding`.
- A manual choice in settings overrides the automatic mode for the whole library.
- Writes (§7.2) store changed fields in Unicode and leave unchanged legacy frames as they are; converting those is a
  reviewed fix in v1.x.

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
  can play can also be fingerprinted. The decoder loop takes every free input buffer and every ready output buffer
  without waiting and waits briefly only when neither side moved. Measured in M0 on the API 36 emulator (debug
  build), the two minutes take about 5 s for 48 kHz FLAC and 11 to 15 s for MP3, Vorbis and Opus; nearly all of it is
  the platform's software decoders, while Media3's extractor, the resampler (about 1 s) and Chromaprint (under 0.1 s)
  are fast. The native code is compiled with `-O2` in debug builds too. To be measured on a phone (M0 own-files check).
- PiPlayer opens the file descriptor itself and hands it to the extractor, and keeps it open until the extractor is
  released. (Given a `content://` URI, the extractor treats it as a web address when it cannot open it, which hides
  the real error.) A descriptor that is not a regular file, such as a pipe from a document provider, cannot be read at
  random positions; such a file is copied into the cache first.
- Decoder output: the decoder is asked for 16-bit PCM, which is all Chromaprint takes, and the request also replaces
  the 24-bit encoding the extractor proposes for 24-bit FLAC. A float request would keep more precision, but on
  Android 13 and later the platform converts the output before it trims the MP3 and AAC encoder delay, and the trimming
  assumes 2 bytes per sample. The platform trims the encoder delay and padding that the extractor reports (LAME or
  Xing header, iTunSMPB, MP4 edit list); the Opus decoder drops the pre-skip itself. PCM stored in the container (WAV)
  skips `MediaCodec`, whose pass-through decoder could mislabel 24-bit and float samples as 16-bit. When the container
  gives no maximum sample size, the decoder's input buffers are made 1 MiB, because the default (32 KiB for FLAC) is
  smaller than the frames of some FLAC files.
- End of the stream: the stream ends where the extractor has no sample left. Media3 1.11.1's extractor does not end a
  FLAC stream that way (found in M0 on the emulators): past the last frame it hands out that frame again, with the
  same time, while `advance()` keeps reporting more samples, and reading such a repeat fails inside the extractor once
  the frame is larger than 64 KiB. So for FLAC, a sample with the time of the one before ends the stream before it is
  read, when it lies within the last 2 s of the container's duration or the duration is unknown. Other formats keep
  every sample: laced Matroska frames may share a time, an MP3 whose sample rate rises steps back in time, and MP4
  priming samples have negative times. A decoder that takes no input and gives no output for about five seconds fails
  the file instead of hanging; a decoder error after some audio came out (a file cut off in the middle of its last
  frame) ends the stream there and keeps that audio, but the decoded length then no longer counts as the file's
  length. PCM in the container that Android has no encoding for (big-endian, 64-bit float) is refused rather than
  read as noise.
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
  encoding and delay trimming. M3 adds 5.1, AAC and non-LAME MP3 recordings to the comparison and runs it on a phone.
- Chromaprint 1.6.2+ (≥ 1.6.1 is required: older versions drop partial frames between `feed` calls), built from
  source with our own CMake list, KissFFT, no FFmpeg, `USE_INTERNAL_AVRESAMPLE=OFF` (MIT + BSD-3 build), linked
  statically into the JNI library together with speexdsp's `resample.c`; TagLib stays a separate `libtag.so` (LGPL),
  with `c++_shared` for both. 16 KB page alignment, NDK r28+ (M0 uses NDK 30, which aligns by default). Checked in M0:
  on byte-identical PCM the Android build gives the same raw fingerprint as the desktop fpcalc 1.6.2, bit for bit, on
  five recordings of different formats and sample rates. Results are not guaranteed to be identical across ABIs (the
  sinc table and Chromaprint's FFT use each device's math library), so fingerprints are always compared by their bit
  error rate.
- Lookup: gzip-compressed form POST (`Content-Encoding: gzip`) to `https://api.acoustid.org/v2/lookup` with
  `client=<app key>`, `duration=<exact duration (§3.1) rounded to the nearest second, as an integer string>`,
  `fingerprint=<compressed>`, `meta=recordings releasegroups releases tracks isrcs compress` (**space-separated**: a
  literal `+` gets encoded as `%2B` and the server then returns no metadata), `format=json`. The server only compares
  fingerprints whose stored length is within 7 s of `duration`, and rejects 0, so a file whose length is unknown is not
  looked up. The key travels in the request body, never in a URL.
- Answers (checked in M0 against the server's source code): an error comes as `{"status": "error", "error": {"code",
  "message"}}`, mostly with HTTP 400; error 4 (invalid application key) stops the client without retries; error 14
  (too many requests, HTTP 429, sent without Retry-After) and HTTP 500, 502, 503 and 504 are retried with the backoff
  of §9; other errors are final. The proxies in front of the service may answer with HTML or plain text; such an
  answer counts by its HTTP status. A recording may come back with its ID only (no metadata) and its `duration` is in
  seconds with a fraction; a result may have no recordings at all.
- The fingerprint and the AcoustID track ID of the best result are cached by content key (§10.1); a confirmed
  decision keeps the AcoustID track ID in the user database (§10.2).
- An empty result is not final (server-side search timeouts look like "no match"): the file is marked "retry later"
  and falls back to text search. The lookup is retried once after 1 hour and once in the next batch; after that the
  empty result is final. A text-search candidate scoring at least 0.50 (§4.1) cancels the retry.
- Do not use undocumented parameters (`batch`, `maxdurationdiff`) unless AcoustID confirms them in writing.

### 3.3 Stage 4: text search fallback

- Parse the filename (`NN - Artist - Title (Remix)`, `Artist_-_Title`, track-number prefixes, `[bitrate]` noise,
  etc.). Tag values are used as decoded at stage 0 (§3.1).
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
  Free text typed by the user goes through `dismax=true`.
- MusicBrainz's own `score` is never shown as confidence (it is relative and full of ties).
- The query builder is first checked in M0, on the JVM, against a list of real track titles (§11.4); the variants and
  their order are tuned there and in M3.

## 4. Scoring and release choice

### 4.1 Recording confidence (0 to 1)

Tiered, not a flat weighted sum. A candidate found by several stages takes its highest score. The weights, credits
and penalties below are starting points, tuned in M3.

1. **Identifier tier**:
   - MusicBrainz recording ID from the file's tags, with the recording length within 7 s of the file's duration: 1.0;
   - ISRC hit with the recording length within 2 s: 0.95; if the ISRC maps to several recordings, each is scored by
     the similarity tier instead;
   - AcoustID result: its score × the duration credit; a recording without a length keeps its score (AcoustID
     only matches fingerprints whose length is within 7 s of the file's anyway).
2. **Similarity tier** (text search, and candidates without identifier evidence): 0.4 × title similarity + 0.3 ×
   artist similarity + 0.2 × duration credit + 0.1 × album/track agreement, capped at 0.85, so text alone never
   reaches the high band.
3. **Penalties**, applied after the tier score: a duration difference over 30 s multiplies the score by 0.3; a title
   similarity of at least 0.9 with an artist similarity below 0.5 subtracts 0.3 (only when the file has an artist).
   Without a duration on either side there is no duration credit and no duration penalty.

Definitions:

- Duration credit: 1 up to a difference of 2 s, falling linearly to 0 at 30 s.
- Similarity: Levenshtein ratio on strings normalized like search (case, diacritics, ё and е; Cyrillic, kana, Han and
  Hangul letters are kept whole). Bracketed suffixes at the end of a title (remix, live, edit; ASCII or full-width
  brackets) are compared separately: a suffix on only one side lowers the title similarity by 0.2, two different
  suffixes by 0.2 to 0.4. A featured artist or a remaster note in brackets is not a suffix, and brackets earlier in the
  title belong to the name, as in "(Don't Fear) The Reaper".
- Album/track agreement: 1 when a release of the candidate matches the album tag (similarity at least 0.9) and the
  track position equals the track number tag, 0.5 when one of the two matches, 0 otherwise or without these tags.

Bands:

| Score | Meaning | Single mode (M3) | Batch mode (M4a) |
|-------|---------|------------------|------------------|
| ≥ 0.90 and runner-up gap ≥ 0.10 | high | pre-selected, user taps Apply | prepared automatically, still shown in the summary before saving |
| 0.50 to 0.90, or ≥ 0.90 with a runner-up gap below 0.10 | medium | candidate list, user picks | review queue |
| < 0.50 | no match | "Not found" + manual search | "Needs attention" list |

Thresholds are starting points. They are tuned in M3 on the corpus (§11.4), where they drive pre-selection, and
re-checked before release (M5); in M4a they also drive automatic preparation.

### 4.2 Release (edition) choice for a recording

1. Get all releases of the recording with one browse request:
   `/ws/2/release?recording=<mbid>&status=official&inc=release-groups+media+labels&limit=100`
   (page by the number of releases actually returned). Fall back to non-official only if nothing is left.
2. Rank by these keys, in order:
   1. a release already confirmed for other files in the same folder (album consistency);
   2. agreement with the file's existing tags: the album tag matches the release title (similarity at least 0.9),
      and the medium's track count equals the track total tag;
   3. release type, by this rank (with several secondary types, the lowest-ranked one counts):
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
   4. format: Digital Media and CD rank equal, ahead of other formats;
   5. country: the user's country (from the locale), then worldwide (`XW`), then the others;
   6. date: earlier first, releases without a date last; then the release MBID, so the order is stable.

   Track durations are not part of the ranking: after the release lookup (§5.1) the track's length on the release is
   compared with the file's, and a difference over 7 s is shown in the review.
3. Do **not** simply take the oldest release (vinyl first pressings can have other lengths and covers). Store the
   release group's first release date separately as "original year".
4. If the recording appears on several tracks of the release, take the one whose position matches the file's track
   number tag, otherwise the first.
5. Batch mode: one release per album group (§8; files without an album tag are looked up one by one and grouped
   afterwards): the release that contains the most recordings of the group, ties broken by the ranking of step 2.
   Files whose recording is not on it get their own best release, and the album card in the review shows a split
   warning; so do files of one folder that end up on different editions.
6. The review screen pre-selects the top-ranked edition, labeled "Suggested edition". The edition picker ("Choose
   edition") lists **every** edition in the release group, loaded when the picker opens:
   `/ws/2/release?release-group=<mbid>&inc=media+labels&limit=100` (paged as in step 1, all statuses, official
   first), with type, format, country, date, label and track count, the suggested one first. An edition that is not
   among the recording's releases from step 1 uses another recording of the song or lacks it: choosing it loads its
   tracklist (§5.1) and maps the file to the track with the same position and a similar title (similarity at least
   0.9), whose recording ID then replaces the identified one. If no track qualifies, the edition is marked "does not
   contain this song" and cannot be chosen.

## 5. Release details and cover

### 5.1 Release details

- One lookup per chosen release, cached by release MBID for the other tracks of the album:
  `/ws/2/release/{mbid}?inc=recordings+artist-credits+labels+release-groups+isrcs&fmt=json` (no `genres`).
- Fields taken from it: the artist credits of the track and of the release (every credited artist, the name as
  credited, join phrases), sort names from the credited artists, the medium title as disc subtitle, track and disc
  positions from the medium and track `position` (never the printed `number`), totals from the medium's track count
  and the number of media, label, catalog number, barcode, ISRCs, the release track ID. The compilation flag is set
  when the release is credited to the special Various Artists artist (MBID
  `89ad4ac3-39f7-470e-963a-56509c546377`). Writing follows §7.5.

### 5.2 Cover

- If the release has a usable front image (`cover-art-archive.front` is true and `darkened` is false): display
  `release/{id}/front-500` (lists: `front-250`), embed from `front-1200`, and fall back to this release's original
  front image when `front-1200` is missing.
- Otherwise use the release group's front image (`release-group/{id}/front-500` and `front-1200`), marked in the
  review and in the cover picker as taken from another edition.
- Rewrite `http://` to `https://` before the first request (Android blocks cleartext). A 404 after a thumbnail
  redirect falls back to the original image.
- Online covers load only while online lookups are enabled: for identified albums, and lazily for albums whose tags
  already carry a release MBID but have no local cover (M3).
- Failures are cached (`api_cache`, §10.1) so lists do not retry them on every scroll: a 404 for 7 days, a 503 for
  5 minutes, doubling on each further 503 up to 6 hours.
- Embedded cover presets: **compatible** (baseline JPEG ≤ 1000 px, default), **car stereo** (baseline JPEG 600 px),
  **original**. Never replace an existing cover with a smaller one unless the user asks: "smaller" means a shorter
  short side in pixels, and such a cover's checkbox starts unticked.
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
  not lose it; it is not backed up and is re-fetched by release MBID after a restore (§10.3).
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
  media session and starts at about one third of the track (intros are often quiet; tracks shorter than 45 s play
  from the start).
  - The snippet player is built with `handleAudioFocus = false`: Media3's own focus handling would request a full
    `AUDIOFOCUS_GAIN` for media playback, and the main player would then lose its focus for good.
  - Before the snippet starts, PiPlayer pauses the main player through its MediaController, remembers whether it was
    playing, and requests `AUDIOFOCUS_GAIN_TRANSIENT` for the snippet with an `AudioFocusRequest`. When the snippet
    ends or the card closes, PiPlayer abandons that focus and resumes the main player if it was playing.
  - If the snippet loses focus (a call, another app), it stops, and the main player is not resumed.
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
  - Within levels 1 and 2 the newest decision that is not undone wins per field. A per-field revert is a decision
    that hides the older level-1 and level-2 values of that field, so the file's value wins again.
  - One repository function resolves the winners and copies them into `track_display`, with the normalized search
    columns and the missing-information reasons (§1), on every change (decision, undo, tag pass, index sync) and at
    start-up when the user database changed since the last mirror (§10.3).
- **Merged values everywhere.** Every surface reads the merged values, never raw tags: lists, search, track details,
  Now Playing, the media session with its notification, lock screen, Bluetooth controllers and watches, and in v1.x
  car displays, voice requests and the widget. After Apply and Undo the playing item and the queued items of the same
  tracks are refreshed in place (Media3 `replaceMediaItem`) without restarting playback; verify on devices that the
  player does not prepare the file again.
- **System surfaces.** The player given to the media session is wrapped (`ForwardingSimpleBasePlayer`) so that its
  metadata is only the MediaItem's metadata built from the merged values, with `artworkUri` pointing to the cover the
  resolver chose (§5.2), and each item's duration is the stored exact duration (§3.1) whenever one is stored.
  Metadata that Media3's extractors read from the stream (tag frames, the embedded picture as `artworkData`) is never
  shown: otherwise fields PiPlayer leaves empty would show raw tags, and the session's bitmap loader would prefer the
  old embedded picture to a saved cover. Verify on the pinned Media3 version (M2a).
- **Undo for changes saved only in PiPlayer** (M3). Apply and Edit fields store the values they set; Keep as-is and
  Skip store no values. Undo marks a decision undone and re-resolves the winners, so undoing an older decision
  changes only the fields where it still wins: after two identifications in a row, undoing the older one changes
  nothing visible, and undoing the newer one brings the older one's values back. In M3 the review card offers Undo
  right after Apply, Keep as-is or Skip, and track details offer "Undo identification" and a per-field revert; the
  change history per decision and per batch lands in M4b (batch history beyond per-file revert is below the cut
  line). Undo of a change written into the file follows §7.2 step 10.
- **File changed after a decision.** For each decided field and file location, the reference value is the file's
  value at the decision or, after a verified write, the written value (§7.2 step 6); only values that differ from the
  decided value are stored (`file_state`, §10.2). If a later tag pass finds a file value that differs from the
  reference, the field is flagged in track details as changed in the file, with "Keep PiPlayer's value" and "Use the
  file's value" (a per-field revert). The comparison runs per file location, for written decisions as well as for
  database-only ones. Until the user chooses, the decision keeps winning. PiPlayer's own writes are not such changes
  (§7.2 step 8).

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
    - A bound service declared with `android:process=":tagger"` exposes an AIDL interface. It runs native calls only
      and never opens the databases; only the main process opens them (§12 D9).
    - The main process opens each file (`ContentResolver.openFileDescriptor`, or the working copy) and passes the
      `ParcelFileDescriptor`; `:tagger` never resolves URIs. One file per native call.
    - Before each call the main process increments `audio_file.unsafe_count` and commits; it resets the count when a
      result arrives. A call that runs longer than 30 s kills `:tagger` and counts as a crash. A count of 2 marks the
      file "unsafe", which also protects the library tag pass.
    - Each IPC result stays under 128 KB (the binder buffer is about 1 MB, shared by all transactions of the
      process). A larger result, or any single value over 16 KB (embedded lyrics, large binary frames, raw tags), is
      written to a file in app storage and only its path crosses. Cover bytes never cross: images travel through
      files in app storage or pipes.
    - Settings that affect reading or writing (legacy encoding, ID3v1 policy, cover preset) travel as call
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
- Rate limits (process-wide token bucket per provider, last-request time persisted):
  - MusicBrainz: one request per ≥ 1.1 s per device; exponential backoff with jitter on 503, 429, 502 and 504 (§9);
    pause batch on repeated 503.
  - AcoustID: ≤ 3 req/s documented (the server enforces 4 per IP and, by default, 10 per application key, over a
    20 s window); single lookups start at least 0.35 s apart, but the per-application bucket is shared by **all**
    installs, so background batches use ≤ 1 req/s; global backoff on 429 / error 14.
  - Cover Art Archive: no documented limit, but handle 503 with backoff (§5.2).
- Random jitter; no jobs at fixed clock times; no polling MusicBrainz for changes.

## 9. Third-party services and compliance

| Service | Use | Notes |
|---------|-----|-------|
| AcoustID + Chromaprint | 1.0 (core, M3) | free for non-commercial use; register the app key; ask AcoustID OÜ in writing whether a free, open-source, donation-supported app qualifies; paid plans from EUR 50/month |
| MusicBrainz ws/2 | 1.0 (core, M3) | no key; 1 req/s per IP; mandatory User-Agent; core data CC0, genres/tags CC BY-NC-SA; register PiPlayer at metabrainz.org (non-profit tier) and confirm before the Play release (M5) |
| Cover Art Archive | 1.0 (core, M3) | images remain copyrighted by rights holders ("use at your own risk"); depends on Internet Archive uptime |
| GitHub Releases API | 1.0 (M5), opt-in update check, GitHub build only | sends no library data; disclosed like the services above |
| LRCLIB | v1.x, user-triggered | free, no key, synced lyrics; receives artist, title, album and duration; lyric texts are unlicensed, so never write them into files by default |
| Discogs | later, display-only | CC0 text but API terms limit storage (6 h display rule); images restricted; no secret in the APK |
| AudD / ACRCloud | later, bring-your-own-key | paid; audio upload must be disclosed |
| Spotify, Last.fm, Deezer, iTunes, Genius, Musixmatch, ShazamKit | no | storage/caching bans, non-commercial or private-use-only terms, promotional-only artwork, no lyrics in API, paid membership or terms that forbid copying content into tags |

Rules:

- User-Agent on every request: `PiPlayer/<version> ( https://github.com/LordDetson/piplayer )`, never the OkHttp
  default. Each build flavor (GitHub, Google Play) and each fork uses its own AcoustID application key, and a fork
  puts its own repository URL in the User-Agent.
- Rate limits, in detail for MusicBrainz (§8 lists all providers): one limiter per host for the whole process;
  requests start at least 1.1 s apart, in arrival order. Busy answers (503, 429, 502, 504) and network errors are
  retried with waits that double from 1 s up to 32 s, each taken at random between half and all of its step, and
  never sooner than the server's Retry-After (measured from the server's `Date` header; a Retry-After over 120 s ends
  the retries). Every wait is a pause of the shared limiter, so it holds back every caller, also when the client gives
  up. A 403 that rejects the User-Agent stops the client. In M0 the limiter keeps its state in memory; persisting the
  last request time (§8) comes with batches (M4a).
- Each provider sits behind a `MetadataProvider` interface with policy flags: `commercialAllowed`, `mayPersist`,
  `mayWriteToFile`, `maxDisplayAge`, `requiresSecret`, attribution text and per-item link, own rate limiter.
- Only CC0 core fields are written into files as text; embedded covers follow §5.2. Attribution in About (M5):
  "Identification: AcoustID / Chromaprint · Metadata: MusicBrainz (CC0) · Covers: Cover Art Archive".
- API keys shipped in the APK are not secrets (established open-source taggers publish theirs); document that forks
  must register their own key; optional setting for a user's own AcoustID application key (M3), kept on the device
  only (§10.3).
- Public 0.x pre-releases on GitHub use online identification only after AcoustID and MetaBrainz confirm the terms
  in writing, or with a separate test application key; the application key's rate limit is shared by every install,
  test builds included.
- Privacy: online lookup is opt-in with a clear explanation. Every online service is disclosed in the app before its
  first use, in the privacy policy and in Play Data safety, which must declare what is **shared** with whom:
  - AcoustID: fingerprints and durations;
  - MusicBrainz: existing tags, parsed file names, durations and IDs;
  - Cover Art Archive: release and release group IDs;
  - LRCLIB (v1.x): artist, title, album and duration;
  - GitHub Releases API (opt-in update check, GitHub build only): the request for the latest release;
  - all of them: IP address and user agent.
- Problem reports and crash reports leave the device only when the user shares them; an identification trace
  included in one leaves out file names and tag values by default (§10.1).
- Keep `docs/THIRD_PARTY_SERVICES.md` with terms URLs and access dates.

## 10. Data model (Room, sketch)

Two database files (§12 D6). Only the main process opens them; `:tagger` never does (§7.2 step 11). Room cannot join
across files, so neither file refers to the other's row IDs: links go through the track key (the content key of §3.1
plus the track's start offset in the file, 0 until CUE sheets arrive), file locations (volume, relative path and
display name) and batch UUIDs. Each file keeps a change counter, and the library database stores the user database's
counter value it last mirrored (§10.3).

Where things live:

- `library.db`, the working copies and undo snapshots of §7.2 and the saved cover files live under
  `Context.getNoBackupFilesDir()` (Room accepts an absolute database path), which Auto Backup skips (verify for device
  transfer); caches live in the cache directory.
- `user.db` lives in the default database directory.
- Settings live in DataStore in the main process (`files/datastore/`), in two files: `settings` (legacy-encoding
  mode, ID3v1 policy, unmetered-only, protected-fields list, cover preset and the other options) is backed up;
  `device_state` (the user's own AcoustID application key, rate-limiter last-request times) is not.
- Library folder rules are user decisions and live in the user database.
- The persisted queue (M1) lives in its own file in no-backup storage: it refers to track identities and, for files
  opened from other apps, to their URIs, which do not carry over to another device.

### 10.1 Library database (`library.db`): rebuildable, excluded from backup

Everything that can be rebuilt from the media index and file tags, or safely lost (batch state, candidates, traces,
caches). Rebuilding the library means deleting this file and filling it again from the media index, the tag pass and
the user database; user data is not touched. A rebuild cancels running batch phases (a) to (c); decisions already
made live in the user database.

- `audio_file(id, media_store_id, volume_name, relative_path, display_name, document_uri, size, mtime,
  generation_modified, format, codec, sample_rate, bit_depth, channels, bitrate, duration_ms, duration_source,
  content_key, tag_encoding, embedded_cover_present, embedded_cover_width, embedded_cover_height,
  embedded_cover_bytes, embedded_cover_hash, unsafe_count, tag_pass_at)`
- `media_index_entry(file_id, title, artist, album, album_artist, year, track_number, duration_ms)`: media index
  values, precedence level 4, used only while `tag_pass_at` is null (§7.1).
- `track(id, file_id, track_key, start_ms, end_ms, <typed tag columns>, raw_tags_json)`: values from PiPlayer's
  reader, precedence level 3. Typed columns: title, artist, artists, album, album artist, album artists, sort names,
  date, original date, track and disc numbers and totals, disc subtitle, compilation, label, catalog number, barcode,
  ISRC, MusicBrainz IDs, ReplayGain values.
- `album`, `artist`, `track_artist(track_id, artist_id, role, position, join_phrase)`, `genre`, `track_genre`:
  grouping by release MBID, otherwise normalized title + album artist + folder, plus the compilation flag, the
  mirrored album favorite and the result of the album's thumbnail probe (§1).
- `track_display(track_id, <merged values>, <normalized search columns>, missing_info_reasons,
  missing_info_decision, favorite, play_count, last_played_at, badge_state)`: merged values (§7.1) and the mirrored
  user fields, for lists, sorting, smart lists and search. `missing_info_reasons` is the set of §1 reasons, written by
  the precedence resolver; `missing_info_decision` is none, keep_as_is or skipped.
- `fingerprint(content_key, fingerprint, acoustid_id, decoder_path, computed_at)`: a cache for identification and
  re-attachment, kept by content key, so the fingerprint of a file that left the media index stays available for
  re-attachment (§3.1); rows that no file or identity refers to are dropped after 30 days.
- `lookup_job(file_id, batch_uuid, phase, status, attempts, next_attempt_at, last_error)` and
  `candidate(file_id, source, recording_mbid, release_mbid, score, reason, payload_json)`.
- `identification_trace(file_id, run_id, started_at, stage, provider, http_status, result_count, best_score,
  elapsed_ms, note)`: a per-file trace of the last 3 runs, shown in track details and offered in problem reports
  without file names and tag values by default (§9).
- `release_cache(release_mbid, payload_json, fetched_at)` and `api_cache(key, status, body, fetched_at, ttl)`, which
  also holds the failure entries of §5.2.
- `cover_cache(release_mbid, path, width, height, bytes)`, with the files in the cache directory.

### 10.2 User database (`user.db`): backed up whole

What the user decided or did; it is never rebuilt from files. MBIDs and other UUIDs are stored as 16-byte blobs and
content keys as 32-byte blobs. Per-file records are found through the identity and the file location when they are
used, never through a stored content URI, which goes stale after a media index rebuild.

- `track_identity(id, track_key, duration_ms, recording_mbid, hint_volume_name, hint_relative_path,
  hint_display_name, hint_size, created_at, last_seen_at)`: the stable track identity that all user data refers to;
  re-attachment (§3.1) updates `track_key` and the hints.
- `decision(id, identity_id, batch_uuid, kind, fields, release_choice_id, recording_mbid, release_track_mbid,
  acoustid_id, confidence, reason, provider, fetched_at, license_class, file_size, file_mtime, created_at, undone_at,
  lapsed_at)`: one row per track and decision. `kind` is apply, edit, revert_field, keep_as_is or skip; `fields` is the
  set of ticked fields. Provenance is stored here, once per decision, not with each value. `acoustid_id` is the
  AcoustID track ID of a confirmed result, so it survives rebuilds and restores. A skip stores the file's size and
  modification time, so that the tag pass can let it lapse (§1).
- `release_choice(id, release_mbid, release_group_mbid, values, saved_cover_id, fetched_at)`: release-level values
  (album, album artist credit and list, album artist sort names and IDs, dates, label, catalog number, barcode,
  totals, disc subtitles, compilation flag), stored once per chosen release; decisions that chose the same release
  with the same values share one row. `values` is gzip-compressed JSON.
- `track_metadata(decision_id, field, value)`, without row IDs: per-track values. Identification decisions store only
  per-track fields (title, artist credit and list, artist sort names and IDs, track and disc positions, ISRC,
  recording and release track IDs). Edits store any field, including release-level fields and the cover (a row with
  field `cover` pointing to a `saved_cover`). A revert_field decision stores a row without a value (§7.1).
- `file_state(identity_id, volume_name, relative_path, display_name, field, value_at_decision, changed_in_file)`:
  per-location state, stored only when needed: when the file's value at a decision differs from the decided value,
  or when the field changed in the file since (§7.1). Whether a field is written into a file is derived from
  `applied_change`.
- `saved_cover(id, release_mbid, path, width, height, bytes, hash, source)`: accepted covers; the image files live in
  no-backup app storage (§10).
- `favorite(identity_id, added_at)`, `favorite_album(release_mbid, album_key, added_at)` (by release MBID when known,
  otherwise by normalized title + album artist), `play_event(identity_id, started_at, listened_ms)` for the last
  90 days, and `play_stats(identity_id, play_count, listened_ms, last_played_at)`, into which older events are
  folded.
- `playlist(id, name, created_at, updated_at)` and `playlist_entry(playlist_id, position, identity_id,
  hint_volume_name, hint_relative_path, hint_display_name, hint_duration_ms, hint_content_key, hint_recording_mbid)`;
  an entry plays the first available location of its identity.
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
  (M3) and fails above 16 MB. The estimate:

  | Table | Assumption | Size |
  |-------|------------|------|
  | `track_identity` | 20 000 rows of about 200 B with the key index | 4 MB |
  | `decision` | one identification per track, about 80 B | 1.6 MB |
  | `track_metadata` | about 8 per-track values per identified track, about 30 B each | 4.8 MB |
  | `release_choice` | 2 000 releases, about 600 B compressed | 1.2 MB |
  | statistics, favorites, playlists | play events of 90 days, 5 000 playlist entries | 1.5 MB |
  | `file_state`, `tag_backup`, `applied_change` | a year with 5 000 written files, about 300 B each | 1.5 MB |
  | Total | | about 15 MB |

### 10.3 Mirror and backup rules

- Mirrored into the library database on every change and at start-up when the user database's change counter differs
  from the one the library database last mirrored: the merged values of decided fields, favorites (tracks and
  albums), play counts, last played times, the badge state and the Keep as-is/Skip state; the resolver recomputes the
  missing-information reasons at the same time.
- A change that touches both files commits the user database first and the library database in its own transaction
  afterwards. If the process dies in between, the start-up mirror and the tag pass bring the library database up to
  date (§7.2 step 7).
- Auto Backup and device transfer include `user.db` with its WAL file, so the copy is consistent, and the `settings`
  DataStore file. Everything else stays out: `library.db` with its WAL and SHM files, all caches, the fingerprint
  cache, saved cover files, undo snapshots, working copies, the persisted queue and the `device_state` DataStore file,
  so the user's own AcoustID key is entered again after a restore.
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
| M2a | Start-up mirror: when the user database's change counter differs from the mirrored one, merged values, favorites, statistics, badge state and Keep as-is/Skip state are mirrored again |
| M2a | IPC from `:tagger` for the tag pass: each result stays under 128 KB; a larger result or a value over 16 KB travels through a file; no cover bytes cross the process boundary |
| M2b | Legacy encodings, per file: "Кино", "Ёжик" and "Би-2" stored as Windows-1251 are decoded as such in automatic mode; "Beyoncé", "Motörhead", "Sigur Rós" and "ÉTÉ" stay ISO-8859-1; ID3v1, encoding-0 ID3v2 frames and RIFF INFO |
| M2b | "Songs missing information": the reasons of §1 on merged values, including a title from the file name (level 5, or level 4 equal to the display name), the cover rule (embedded, saved, album thumbnail) and the MusicBrainz filter being off by default |
| M3 | Keep as-is and Skip are remembered by identity and mirrored; a Skip lapses when the tag pass records a new size, modification time or content key; undoing either restores the earlier list state |
| M0 | Filename parser: a table of real-world names → (artist, title, track number) or "unknown" |
| M0 | AcoustID request builder: `meta` is space-separated, `duration` is an integer string rounded to the nearest second, body is gzip-compressed, the key stays out of the URL; answers: error 4 stops at once, error 14, 429, 500, 502, 503 and 504 are retried, other errors and non-JSON pages are final; recordings with an ID only and results without recordings are kept |
| M3 | Retry later: an empty AcoustID result is retried after 1 hour and once in the next batch, then counts as final; a text-search result of at least 0.50 cancels the retry |
| M0 | Text query builder: Lucene escaping, lower-case terms, the variants of §3.3 in order, skipped variants for missing values, the stop rule and the limits |
| M0 | PCM conversion: 8-, 16-, 24-, 32-bit and float input, stereo and 5.1 downmix, rounding and clipping to 16-bit; the fingerprint path stops at 120 s, skips the resampler at 11 025 Hz, flushes it at the end of a short file and starts a new one when the format changes |
| M3 | Recording scoring (§4.1): identifier tiers, duration credit, the 0.85 cap for text, both penalties, and a score of at least 0.90 with a runner-up gap below 0.10 landing in the medium band |
| M3 | Release ranking on recorded MusicBrainz JSON fixtures (for example a track with hundreds of same-title recordings, video recordings and compilations): the folder preference, the full type order, Digital Media equal to CD, date order, a recording on two tracks of one release; fixtures stripped of CC BY-NC-SA `tags`/`genres` before committing |
| M3 | Edition picker: every release of the group is listed; an edition without the identified recording maps the file by position and title or is marked "does not contain this song" |
| M0 | Rate limiter: never exceeds the configured rate under parallel callers; a pause holds back waiting callers; backoff with jitter on 503, 429, 502 and 504; Retry-After honoured; a rejected User-Agent is not retried |
| M3 | Cover URLs: `http://` → `https://`; a release with a usable front image (`front-500`, `front-1200`, original); a release without one (release group images, marked as another edition); 404 after a redirect; failure TTLs (404: 7 days; 503: 5 minutes, doubling up to 6 hours) |
| M3 | Cover size rule: a proposed cover whose short side is shorter than the existing cover's starts unticked |
| M3 | Database-only undo: Apply followed by Undo restores the earlier merged values; stacked decisions (two identifications in a row, an edit on top of an identification) resolve as in §7.1; Undo right after Apply, Keep as-is and Skip; a later file change flags the field per location |
| M3 | Size budget of `user.db` with generated data (§10.2): fails above 16 MB |
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
| M0 | Chromaprint JNI: fingerprints of bundled CC-licensed samples are similar to desktop `fpcalc` output (at most 10 % of bits differ over the aligned overlap; the threshold is calibrated in M0) and return the same AcoustID; never exact string equality (decoders differ). Measured in M0 through the device's own decoders and resampler against fpcalc on the same files (MP3 44.1 kHz, Ogg Vorbis 48 kHz, FLAC 48 kHz/24-bit, FLAC 96 kHz/24-bit, Opus 48 kHz): offset 0 and at most 1.4 % of bits differ (MP3), the others under 0.02 %. M3 adds 5.1, AAC and non-LAME MP3 recordings |
| M0 | Resampler: a 9 kHz tone at 44.1, 48 and 96 kHz comes out at least 40 dB below a 1 kHz tone of the same level (9 kHz would alias to about 2 kHz, inside the band Chromaprint uses); 1 kHz keeps its level within 0.5 dB and its phase; the output length is exact and does not depend on how the input is cut |
| M0 | Decoding fixtures (2 s of 1 kHz): MP3, AAC, Vorbis, Opus, FLAC 96 kHz/24-bit, FLAC 5.1, FLAC with frames larger than 64 KiB and no frame size in its header, WAV in 16-bit, 24-bit and float, WAV at 11 025 Hz; each at its own rate and channel count, never shorter than 2 s (encoder delay and Opus pre-skip are trimmed to the sample) and at most one frame longer (the platform trims MP3 padding, but AAC padding and the last Vorbis and Opus packets stay, which does not move the fingerprint), and still 1 kHz at the same level after the whole fingerprint path. Edge cases: an MP3 whose sample rate rises halfway reaches the consumer in both rates; a FLAC file cut off in its last frame keeps what was decoded; a single-frame FLAC is decoded once; a 64-bit float WAV is refused |
| M0 | TagLib reads the tag fixtures of every format |
| M2a | Content key through the real reader: unchanged after tag edits in every format (ID3v2 growing past its padding, ID3v1 and APE added or removed, FLAC metadata blocks, MP4 with `moov` before and after `mdat`), changed when the audio changes. Ogg Vorbis and Opus: unchanged after comments are rewritten across page boundaries, also when the number of header pages changes and when a large embedded cover is added; the bounded page walk (D10) reads no more of a file than the key of other formats, measured on the generated library; a chained Ogg file takes the token `file` |
| M2a | Exact duration of VBR MP3 without a frame-count header and of raw ADTS matches the decoded length within one frame; truncated WAV, MP4 and FLAC files report the length of what is left |
| M2a | Re-attachment (§3.1): a moved or renamed file and a media index rebuild with new IDs keep favorites, playlists and statistics; a moved Ogg file whose comments an external tool rewrote keeps its content key and re-attaches by it |
| M2a | `:tagger` isolation: a fixture that crashes the native reader and a call that hangs for more than 30 s are counted; the second time the file is marked unsafe; playback continues |
| M2b | Deleting `library.db` keeps favorites, playlists, statistics and (from M3) decisions, which re-attach after the rebuild |
| M3 | Decoding table: every format the player plays is read with `MediaExtractorCompat`, decoded and fingerprinted |
| M3 | Snippet: a playing main player pauses for the snippet and resumes at the same position; a main player paused before the snippet stays paused; queue, position and statistics do not change; a focus loss during the snippet stops it without resuming the main player |
| M3 | Session metadata: for a fixture whose embedded cover and tags differ from the saved values, and for an MP3 without a frame-count header, the MediaController's metadata, artwork and duration equal PiPlayer's values after Apply and after Undo, without preparing the file again |
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

- M0: the identification spike on at least 30 real files, with the correctness of each result recorded; the stage 4
  query builder runs on the JVM against a list of real track titles and records the share of correct top results.
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
| D9 | Database access from `:tagger` | **Decided 2026-10-18:** only the main process opens the databases; `:tagger` runs native calls and returns results over IPC (§7.2 step 11) |
| D10 | Ogg content key | **Decided 2026-10-18:** a bounded page walk (§3.1): header pages skipped by their segment tables, the first 64 KB of the payload from the following page bodies, the last 64 KB from the tail of the file located through CRC-checked page starts, the length from the final granule position; chained or multiplexed files take the whole-file token `file` |
