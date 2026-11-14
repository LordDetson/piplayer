# Library

How PiPlayer builds and keeps its music library: the library database, its sync with the platform's media index, and
the lists built on it. This is the specification of milestone M2a (features.md); each step of M2a adds its section.
The data model it implements is sketched in track-identification.md §10 (TI below).

## 1. Library database and media index sync (M2a step 1)

### 1.1 Database file

- `library.db` holds everything that can be rebuilt from the media index and the files (TI §10.1). It lives under
  `Context.getNoBackupFilesDir()`, which Auto Backup skips, and is opened with Room 3 on the SQLite bundled with the
  app (`BundledSQLiteDriver`, about 1 MB per ABI): the same version on every device, with JSON functions. On the API 30
  emulator it wrote the 20 000 rows of a table in 0.11 s instead of 2 s on the platform's SQLite, which has no JSON
  functions there and pays for every bound value, and it read them three times as fast.
- It has no migrations: when its schema version changes, Room drops every table and the next sync fills them again
  (§1.3). Its schema is not exported, as nothing outside the app reads it. The version also changes when the stored
  text changes without a change of schema, as with the search normalization in version 4 (TI §4.1, §10.1).
- Only the main process opens it (§1.5).

### 1.2 Tables in step 1

- `audio_file`: one row per audio row of the media index, with PiPlayer's own row ID, the index's ID, the volume, the
  relative path, the display name, the size, the modification time, the index generation of the last change, the MIME
  type, and the kinds the index gives it: music, podcast, audiobook, ringtone, notification sound, alarm, recording.
  Android 11 has no recording flag, so it is unknown there.
- `media_index_entry`: the index's values for the file (title, artist, album, album artist, composer, genre, year,
  track number, length), as the index gives them. Later steps use them only until the tag pass has read the file
  (TI §7.1).
- `track_display`: what lists show and sort by: title, artist, album and length, the title key and the search text.
    - One resolver computes these values from the precedence levels of TI §7.1. The title is the first of: the file's
      tag once the tag pass has read it, the index's title until then, the file name parsed as in TI §3.3, the display
      name without its extension. The artist goes the same way, without the last step.
    - Before the read, the index's placeholder `<unknown>` and blanks count as empty. After it, an empty tag stays
      empty: the index no longer fills it, only the file name.
    - The album is the tag or, until the read, the index's value. The length is the exact one once measured (step 7),
      then the index's, then the tag reader's estimate (a length of 0 counts as none).
    - The title key is the title normalized as for search (TI §4.1). Step 11 replaced it with the collator's key of the
      title, `title_sort` (§11.1).
    - The search text joins the normalized title, all artists, album, composers, genres and display name without its
      extension with spaces, leaving out empty values, and keeps the glued forms of each field's names (TI §10.1).
      Search itself arrives in step 12; the column and its index exist now, so that
      search can be measured on the real schema early.
- `volume_sync_state`: per volume, the index version and generation the last sync reached.

### 1.3 Sync

- **What is read.** Every audio row of the media index on every attached external volume (the names
  `MediaStore.getExternalVolumeNames` returns), whatever its kind: later steps hide ringtones, recordings and short
  files by a setting rather than by not knowing them. Pending and trashed rows are not read, as the index leaves them
  out by default. Files that the index files as video (audio-only WebM, Matroska, 3GP) are not audio rows and stay out
  of the library; reading them would need the video permission on Android 13+ (`later`).
- **Full or incremental, per volume.** A volume gets a full sync when it has no sync state yet, when its index version
  differs from the stored one (the index was rebuilt, so its IDs and generations mean nothing any more), or when its
  generation is lower than the stored one. Otherwise it gets an incremental sync: the rows whose generation of change
  is higher than the stored generation are read and stored again.
- **Deletions.** Deleted rows leave no trace in the generations, so every sync of a volume also reads the IDs of all
  its audio rows and removes the stored files whose IDs are gone.
- **Row identity.** An incremental sync matches stored files by the index's ID. A full sync matches them by location
  (volume, relative path, display name), so that a file keeps PiPlayer's row ID across an index rebuild, which gives
  every file new IDs; unmatched stored files are removed and unmatched index rows added.
- **Generation.** The generation stored for a volume is the one read before its rows: a change that lands while the
  sync reads is read again by the next sync, never missed.
- **An index rebuilt during a sync.** The version is read again after the rows and IDs; if it changed, the volume
  keeps its files this time and the next sync, asked for at once, reads it in full.
- **Volumes that go away.** The files and the sync state of a volume that is no longer attached are removed (an
  unmounted SD card's songs disappear, as before). A volume whose version cannot be read at the moment is skipped and
  keeps its files.
- **One transaction.** Each sync writes its changes in one transaction, so a list never sees half a sync, and a sync
  that changes no file writes no file rows. A sync that fails is logged and leaves the database as it was. Rows go in
  as `INSERT OR REPLACE` of up to 2 000 rows per statement, each statement holding its rows as SQL literals (a quote
  doubled, a blob as hex, a text with a NUL character as the bytes of its UTF-8, an unpaired surrogate as U+FFFD);
  a changed file is written again under its row ID, a new one takes the next free ID. SQLite parses literals about
  three times as fast as it takes the same values out of a JSON parameter, which the writes used before. Room keeps a
  connection's last 25 prepared statements by their text, and a bulk statement's text never comes again, so one of
  32 KB or more is pushed out of that cache right after it runs; a smaller one stays, so that the other statements of
  its transaction stay prepared.
- **The first sync's budget.** The display values are resolved on all cores but one while the transaction writes the
  files, and each chunk of 2 000 is written as soon as it is resolved; the next statements are built on another thread
  while SQLite runs the one before. Into an empty library the indexes of the written tables are dropped and created
  again after the rows, inside the same transaction. Each sync logs how long it read the index and how long it wrote.
- **What the tag pass found stays.** A file stored again with the same size and modification time, also after a
  rename or an index rebuild, keeps what the tag pass found: its technical columns and `track` row, its failure, its
  content key and its count of failed reads. Its display values are resolved again, so a new name counts where the name
  is used. A change of size or modification time forgets all that, and the next pass reads the file again.
- **New display rules.** `library_meta.display_version` records the rules `track_display` was derived by. When the
  app's differ, the next sync derives every display row again from the stored values, without reading any file.
- **Journal mode.** The database is set to WAL explicitly, because the automatic choice gives a single connection on a
  device with little memory and lists would wait for writes.
- **When it runs.** In the main process only, one sync at a time, on a background thread:
    - when the process starts, if the app holds the audio permission;
    - when the permission is granted while the process runs;
    - a second after the media index reports a change to its audio rows (once for a burst of changes), from then on
      for as long as the process lives: while the screen is open and while only playback runs.
  A request that arrives during a sync starts one more sync after it; starting the library again (every new Songs
  screen does) asks for nothing. Without the permission nothing is synced: the
  index would show only the app's own files, and the deletion check would empty the library.
- **Ready.** The library is ready once a sync has finished or failed in this process, or as soon as the database
  shows that an earlier process synced it. It knows its folders only once a sync has succeeded, in this process or an
  earlier one: a failed first sync leaves it ready with no folders known.

### 1.4 Songs from the library

- The Songs screen and the playback service list the songs of the library: the files the index counts as music,
  as in M1 (playback.md §2.1). A song's URI is the index's content URI; since step 8 its ID is the file ID of
  PiPlayer (§8.4), which queue requests and the session's media IDs carry.
- Order: by the title key, compared as Unicode code points, then by the index's ID (since step 11, by the collator's key,
  §11.1). The database sorts; the list
  keeps the order it is given.
- The screen shows "Loading" until the library is ready, so a first launch never shows "No songs" while the first
  sync runs. It reads the list from the library when it starts and again after every sync that changed files; it no
  longer watches the media index itself. Before the list is first shown, the screen reads its first 50 songs on their
  own and shows them while it reads the whole list, which takes a few hundred milliseconds for 20 000 songs; the
  rows keep their places when the rest arrives.
- The list held in memory stays shared by the screen and the playback service (playback.md §2.1, §2.3); the service
  reads the library when it runs without the screen. A kept list is not handed out once a sync has changed the
  library since it was read, so a tap never plays a list the screen no longer shows. A read while the library is not
  ready yet (the service restoring a saved queue during the first sync) waits for it, up to 30 s. Without the audio
  permission the list is empty, although the database keeps its rows.

### 1.5 Processes

- The library database, the sync and the DataStore files check, when they are first opened in a process, that it is
  the main process, and refuse to start anywhere else (playback.md §1.5).

### 1.6 Generated library

- `scripts/generate_library.py` creates a library of 20 000 short tagged files for measurements and for checks by
  hand. It is deterministic: the same arguments give the same files.
- Every file has its own audio (its own tones and noise), so that later steps can tell files apart by content. Most
  files last 31 s, longer than any short-file limit the settings may choose; 100 last 3 s. Bitrates are low (mono,
  8 kHz), so the library takes well under 1 GB.
- Formats: mostly MP3; about 200 MP3 files with variable bitrate and no frame-count header; about 200 each of FLAC,
  Ogg Vorbis and M4A (AAC); 100 Opus.
- Contents: about 400 artists with five albums of about ten tracks; Cyrillic artists, albums, titles and file names
  (with "ё"); Latin names with diacritics; names with punctuation and leading articles; sort tags on some artists and
  albums; multi-disc albums in `CD1` and `CD2` folders; compilations with an album artist and different track artists;
  titles shared by many artists; very long titles; files without any tags; files outside album folders.
- The files go to `Music/PiPlayerTest20k/` on the device as one archive that the device unpacks, and the media index
  then scans that folder. The library is kept on the owner's phone for the length of M2a and removed at its end.

### 1.7 Checks

- On the generated library, with an empty library database:
    - from the grant of the permission, which starts the first sync, to the first page of Songs: at most 3 s on the
      owner's phone and 5 s on the API 30 emulator in every pass. Since the first sync was sped up in M2b, the median
      of three warm quiet passes on the API 30 emulator is at most 4 s, which leaves a fifth of the limit for a slow
      pass; the release check of each step measures it;
    - the whole first sync: at most 10 s on both;
    - a later start with the library already synced: the first page within 1 s, without waiting for the start sync
      (the database shows that an earlier process synced it).
  Newer emulator images (API 36 and 37) take longer than the 1 s limit to show any screen at all, so there the times
  are recorded, not checked.
- Search on the real schema (TI §10.1): every query of the M0 spike's search-as-you-type set returns its first page of
  50 in title order within 50 ms (95th percentile), on both devices.
- The scroll of 20 000 songs is measured in step 2, on a release build with R8: debug builds of Compose are not
  representative.

## 2. Release build with R8 and smooth scrolling (M2a step 2)

### 2.1 Release build

- The release build runs R8: code is shrunk, optimized and obfuscated, and unused resources are removed. Classes that
  native code finds by name through JNI (the TagLib reader and its sink, the Chromaprint session, the resampler) keep
  their names and native methods through the native library's consumer rules.
- The release build stays unsigned until M5. A `benchmark` build type is the release build signed with the debug key
  and profileable from the shell, so that it can be installed and measured; it is never published. It is a package of
  its own (`io.github.lorddetson.piplayer.benchmark`): a connected run uninstalls the app it tested, with its data,
  and must leave the debug build alone.
- CI builds the release APK, so an error R8 reports while building (a missing class, an unresolved reference) shows
  up on every push. A missing keep rule for a class reached by name only fails when the app runs, which the smoke
  checks of §2.2 cover on a device.

### 2.2 Checks on the release build

- A Macrobenchmark module runs against the `benchmark` build:
    - smoke checks: the app starts, the Songs screen lists the library, a tap plays the song, and the mini player and
      Now Playing show the tapped song's title;
    - the scroll of Songs over the generated library (§1.6) with flings down and up, measured frame by frame.
- Smooth means that at most 5 % of the frames miss their display refresh during the flings, as the system counts
  janky frames (`dumpsys gfxinfo`: on Android 12 and later frames that missed their refresh, on Android 11 frames longer
  than one refresh period), on the owner's phone. Emulators draw through the host's graphics and miss most refreshes
  whatever the app does (50 to 77 % on the API 30 emulator; 71 % even with every method compiled ahead of time), so
  the scroll check runs on devices only, with the generated library on them.
- Measured on 01.11.2026 with R8: 0.04 to 0.17 % janky frames in four runs on the owner's phone (for example 7 of
  18 095); on the API 30 emulator 76.6 % without R8, 50.3 % and 58.7 % with it.
- A Baseline Profile is added only if the scroll fails this limit or the start fails the limits of §1.7. With R8 the
  scroll is well within its limit on the phone, so there is none yet; the start limits were measured on debug builds,
  which are slower than the release build, and are not measured on the release build in this step.

## 3. User data, settings, library folders and hidden files (M2a step 3)

### 3.1 User database

- `user.db` holds what the user decided; it is never rebuilt from files (TI §10.2). It lives in the app's default
  database directory, is opened with Room 3 on the bundled SQLite, and only in the main process (§1.5).
- Its schema is exported from version 1 and committed (`app/schemas/`); every later version comes with a migration and
  a test that runs it, and there is no destructive fallback. CI fails when a build changes a committed schema, so that
  a changed schema cannot keep its old version.
- Version 1 holds:
    - `library_folder_rule(volume_name, relative_path, mode)`: a folder the user included or excluded (§3.4);
    - `user_meta(id, change_counter)`: one row whose counter goes up in the same transaction as every write of user
      data, so that the library database can tell whether it still mirrors it (TI §10.3).
- Version 2 adds the track identities (§8.1), version 3 the references of own covers (§13.2), version 4 favorites,
  favorite albums, their album members and play statistics (§15.1). Later tables (playlists, decisions) arrive with
  their milestones.
- Since version 4 `user_meta` also has `rules_counter`, which goes up only with the folder rules: the sync applies the
  rules again when it changes (§3.4), so that a favorite or a play does not make the sync walk the rules and read the
  lists again. `change_counter` goes up with every write the library database mirrors (folder rules, favorites,
  favorite albums, plays) and is what the mirror of §15.4 follows; the migration to version 4 copies it into
  `rules_counter`, so that the rules already mirrored are not applied again.

### 3.2 Settings files

- Settings live in DataStore in the main process, in two files (TI §10):
    - `settings`, backed up: the theme, the hidden-file choices (§3.5), the sleep timer's last choice and, from M2b,
      the legacy encoding (`legacy_encoding`, §5.7);
    - `device_state`, not backed up: "stop after current track" (playback.md §6.4), whether the audio permission was
      denied before (playback.md §2.2) and, from M2b, the legacy encoding the library was last decoded with (§5.7).
- The three SharedPreferences files of M1 (`player`, `playback_controls`, `songs`) move into them under the same keys
  the first time each file is read, and are deleted afterwards.

### 3.3 Backup

- Both rule files (`fullBackupContent` for Android 11, `dataExtractionRules` for cloud backup and device transfer on
  Android 12 and later) list what is backed up: `user.db` with its WAL file and the `settings` file. Everything else
  stays out: the library database, `device_state`, the saved queue, crash reports, the copies of own covers (§13.2) and
  caches (TI §10.3).

### 3.4 Library folders

- A folder rule includes or excludes a folder of a volume with all its subfolders. The deepest rule above a file
  decides; a file under no rule is included. "Only these folders" is an excluded volume root with included folders
  inside it.
- Paths are compared as the media index gives them (`relative_path`, ending with `/`), ignoring case; the index gives
  `/` for a volume's root, which the library keeps as the empty path. A rule for a volume that is not attached, or for
  a folder that holds no audio now, is kept and shown as such.
- An explicitly included folder shows everything in it, also the kinds and short files §3.5 hides elsewhere: a user
  who includes `Recordings/Lectures/` wants to see the lectures.
- The library database mirrors the rules: every file carries the rule of its folder (none, include or exclude), set
  by each sync, so that lists filter without reading the user database. The library database keeps the user
  database's rules counter it last mirrored (§3.1, TI §10.3); a sync that finds another counter applies the rules to every
  file again. A write of the rules asks for a sync at once.

### 3.5 Hidden files

- The lists show every audio file of the index except the hidden ones, so podcasts and audiobooks are listed too.
- Hidden by default, each with its own setting:
    - system sounds: ringtones, notification sounds and alarms;
    - recordings: what the index flags as a recording; on Android 11, which has no such flag, a file under a folder
      named `Recordings` (any level, ignoring case);
    - short files: shorter than a chosen length (off, 5, 10, 15, 30 or 60 s; 10 s by default), by the length the
      index gives until the tag pass measures it. A file whose length is unknown is never short. The index estimates a
      VBR MP3 file without a frame-count header from its first frame and can make it look short (in the generated
      library 81 such files of 31 s were estimated at 8 to 9 s); M2a step 7 measures the exact length.
- A change of a hidden-file setting or of a folder rule reloads the lists at once, and a list kept for the playback
  service is not handed out after such a change, as after a sync (§1.4).

### 3.6 Settings screen and theme

- A Settings screen, opened from the Songs screen's top bar, has two sections in M2a: Library (the hidden-file
  settings and Library folders; from M2b also the legacy tag encoding, §5.7) and Appearance (the theme). Other
  sections arrive with their milestones.
- The theme is System (the default), Light or Dark; dynamic color stays on where the system has it. The choice is
  read before the activity draws anything and becomes the night mode of its configuration, so its window background and
  the system bars follow it from the first frame on. Android 12 and later also keep it as the app's night mode, which
  themes the splash screen; on Android 11 the starting window the system shows before the app runs follows the system.
  A new choice that changes what the screen shows creates it again, keeping its state.
- Library folders shows each attached volume as a tree of the folders that hold audio, with each folder included,
  excluded or following its parent; rules that match none of those folders (their volume is not attached, or their
  folder holds no audio now) are listed below with a way to remove them. Until the library knows its folders (before
  a sync has succeeded, or without access to the audio files) no rule is listed so.
- A failed write of a setting or a rule, as on a full disk, is logged and changes nothing; it never ends the app.

## 4. The tag reader process `:tagger` (M2a step 4)

PiPlayer reads tags with TagLib, native code that a malformed file can crash or hang. It runs in a process of its own,
`:tagger`, so that such a file cannot stop playback or the screens (TI §7.2 step 11). This step builds the process,
the way the main process calls it and the count of failed reads; the tag pass of step 5 is its first real user.

### 4.1 The process

- `TaggerService`, declared with `android:process=":tagger"` and not exported, is a bound service with an AIDL
  interface. It refuses to start in any other process, and it opens no database and no DataStore file.
- The main process opens each file itself and hands the service a read-only `ParcelFileDescriptor`, one file per
  call, with the file's display name (TagLib picks the format by its extension). `:tagger` never resolves a URI. A
  descriptor that is not a regular file (some document providers hand out a pipe) is copied into the cache by the main
  process first, as in M0.
- `TagReader` (nativelib) refuses to read in any process whose name does not end in `:tagger`; nativelib's own
  instrumented tests read with a switch that only they can reach.
- The service closes the descriptor it was given after each call.

### 4.2 What a read returns

- TagLib's property map (keys with their lists of values), the audio properties (length, bitrate, sample rate,
  channels) and, for each embedded picture, its type, MIME type, description and size in bytes. Picture bytes never
  cross the process boundary; the files of covers arrive with step 13.
- For the tag pass, the text of ID3v1, encoding-0 ID3v2 frames and RIFF INFO with each byte at or above 0x80 marked as
  a private-use character, so that the main process decides on the encoding (TI §3.6). Any other reader gets TagLib's
  own strings; a write never sees marks.
- The tags of one format fill in what another lacks: ID3v2 first, then APE, then ID3v1.
- The audio's coding (`mp3`, `aac`, `alac`, `flac`, `vorbis`, `opus`, `pcm` and so on) and, for lossless codings, its bit
  depth.
- The name of the container that holds the audio: `mpeg`, `adts`, `flac`, `mp4`, `ogg-vorbis`, `ogg-opus`, `ogg-flac`,
  `ogg-speex`, `wav`, `aiff`, `asf`, `ape`, `wavpack`, `mpc`, `tta`, `dsf`, `dsdiff`, `shorten` or `matroska`; none for
  tracker modules. TagLib opens an AAC stream without a container as an MPEG file, so `adts` comes from its audio
  properties.
- A read that TagLib cannot do returns the reason, as in M0: invalid descriptor, not a regular file, unknown format,
  read error, native error.
- The reply travels as UTF-8 JSON. It crosses inline when it is at most 120 KiB and no single string in it (a key, a
  value, a picture's MIME type, type or description) is longer than 16 KiB in UTF-8, so that each binder reply stays
  under 128 KB (the binder buffer of about 1 MB is shared by every transaction of the process). Otherwise `:tagger`
  writes the JSON to a file under the app's cache directory (`tagger/`) and only the file's path crosses; the main
  process reads the file, deletes it, and accepts only paths inside that directory. Files left there by a process that
  died are deleted when the main process next connects.

### 4.3 Calls from the main process

- One `TaggerClient` per main process makes one call at a time. It binds the service on its first call, asks it for
  its process ID, and unbinds after 30 s without calls, so that `:tagger` does not hold memory while nothing is read.
  When the service has died (a crash, a kill, or Android giving up on a process that crashed twice within a minute),
  the next call binds it again.
- Every call names the file it reads, for the count of failed reads (§4.4): a file of the library by its row ID, any
  other file (picked in the debug screen, opened from another app) by its URI, size and modification time, which the
  main process takes from the open descriptor (`fstat`).
- Before each call the count of the file is raised by one and committed. When the reply arrives, the count goes back
  to 0 (also for a reply that says TagLib could not read the file: the native code returned).
- A call that has not returned after 30 s kills `:tagger` (`Process.killProcess` with the ID it gave) and counts as a
  crash: the count stays raised. A call during which `:tagger` dies counts the same way.
- A file whose count has reached 2 is unsafe: further calls for it return "unsafe" at once, without reaching
  `:tagger`. The tag pass skips unsafe files (step 5).
- A caller that gives up during a call (its coroutine is cancelled) kills `:tagger` too, so that no native call
  outlives its caller and `:tagger` never runs two calls at once; that read proved nothing, so its count goes back to
  what it was.
- What a call returns: the reply, "crashed", "timed out" or "unsafe".

### 4.4 Where the count lives

- Library files: `audio_file.unsafe_count` in `library.db` (TI §10.1). The sync keeps it when it stores a file again
  with the same size and modification time (also when an index rebuild gives the file a new index ID), and sets it to
  0 when either changed: a changed file gets new chances. A removed file takes its count with it.
- Other files: the table `outside_file(uri, size, mtime, unsafe_count)` in `library.db`, one row per file that has a
  count above 0. A size or modification time other than the stored one counts as a new file, with a count of 0; a
  reply deletes the row.
- `library.db` is rebuildable: a rebuild forgets the counts, which costs at most two more failed reads per unsafe file.
- The library database moves to schema version 3 (step 3 took version 2; no migration, as in §1.1: the next sync
  fills it again).

### 4.5 Crashes and the debug hooks

- A native crash in `:tagger` makes a crash report like any other (playback.md §7), with the process name
  `io.github.lorddetson.piplayer:tagger`.
- Debug builds of the native library have two test hooks, compiled only when CMake builds the `Debug` configuration:
  a file that starts with the bytes `PIPLAYER-TEST-ABORT` makes the reader call `abort()`, and one that starts with
  `PIPLAYER-TEST-HANG` makes it sleep until it is killed. Release libraries contain neither (checked by hand: the
  marker strings are absent from the release `libpiplayer_jni.so`).
- The debug screen "Identify a file" reads tags through `:tagger`; a crash, a timeout or an unsafe file shows as the
  failed tag stage, and the other stages still run.

### 4.6 Checks

JVM tests:

- the reply encoding: inline up to 120 KiB, a file above it, a file for any string over 16 KiB (measured in UTF-8),
  the round trip of every field;
- the call guard on a fake connection and fake counts: the count is raised and committed before the call, back to 0
  after a reply (also a TagLib failure), kept after a crash, kept after a timeout, which kills the process; the second
  failure makes the file unsafe and no further call reaches the connection; calls run one at a time; a cancelled call
  kills the process and leaves the count as it was; the timeout is 30 s by default.

Instrumented tests on the API 30, 36 and 37 emulators:

- tags of the M0 fixtures read through `:tagger` equal TagLib's own reading, with picture sizes and no picture bytes;
  a reply with a value over 16 KiB and one over 120 KiB in total arrive through a file, which is then gone, and the
  largest inline reply parcels to less than 128 KB;
- `TagReader` refuses to read in the main process; the service runs in `:tagger` and is not exported;
- a hanging file is killed after the timeout and counted; a second hang makes it unsafe and the third call does not
  reach `:tagger`; a good file read after the kill works (the service is bound again); one test waits the real 30 s;
- a song keeps playing while `:tagger` is killed during a read;
- the counts in `library.db`: committed before the call (visible from another connection while the call hangs),
  kept by a sync for an unchanged file and across an index rebuild, reset for a changed file; the outside table keyed
  by URI, size and modification time.

Crash tests, opt-in with `-e crashTests true` like the crash reports' end-to-end tests (Android counts repeated
crashes against the app): a file that aborts the reader is counted as a crash, a second abort makes it unsafe, a song
keeps playing meanwhile, and the crash leaves a native crash report of `:tagger`.

## 5. The tag pass (M2a step 5)

### 5.1 What it reads

- Each file of the library that no reader version has settled yet or only an older one has, except those in excluded
  folders and those marked unsafe. Hidden kinds and short files are read too, for their tags.
- A run takes its list when it starts, visible songs in list order first, then the kinds lists hide, so that the first
  screens get better first. A file that arrives later waits for the next run.

### 5.2 What it stores

- Results go into the database in batches of up to 200 files, 1 MiB of raw tags or one second, whichever comes first,
  one transaction each, while no sync runs. Everything is written where it is: a run stopped at any point loses at most
  one batch, and the next run goes on, reading nothing that was committed.
- A file the sync replaced while it was being read keeps nothing of that read, by its size and modification time. A file
  renamed meanwhile keeps the read and shows its new name where the name counts.
- A read file has `tag_pass_at`, its technical columns and a `track` row with its typed values and raw tags. Values over
  16 KiB, such as lyrics, stay out of the raw tags. Albums, artists and genres are filled from the track; those left
  without tracks go when the run ends. A file moved to another folder is read again, because its album depends on the
  folder.
- An album is one by its release ID, or else by its normalized title and album artist within a folder, plus the
  compilation flag. A last folder named like a disc (`CD1`, `Disc 2`, `Диск 1`) does not count.

### 5.3 What can go wrong

| Case | Stored | Lists show | Tried again |
|---|---|---|---|
| Read | `tag_pass_at`, reader version | Tags, then the file name | When the file or the reader version changes |
| TagLib does not know the format, or fails on it | `tag_failure` `unsupported` or `read`, reader version | Index values | When the file or the reader version changes |
| The file cannot be opened, or the device fails during the read (a full disk) | `tag_failure` `open` | Index values | In a later process |
| `:tagger` cannot be reached | Nothing, it says nothing about the file | Index values | The run waits a minute and tries the same file again |
| `:tagger` crashes or hangs | `tag_failure` `crash`, the count of §4.4 | Index values | In a later process, until the count marks it unsafe; the run waits a minute first, because Android gives up on a process that crashes twice a minute |

### 5.4 Lists during and after a pass

- A batch changes rows in place: title, artist, album and length change where they are, the order and the membership do
  not, so that nothing moves under the user's finger. The screen and the playback service share one list, so a tap plays
  what is visible.
- While the pass runs, a sync that adds or removes files keeps the order: rows stay where they are, removed ones go, and
  a new one goes after the kept row before it in title order.
- The list is sorted anew at a new entry to the screen or a start of the process, and once more at the first return from
  the background after a pass that changed rows ended, on the same song; if another pass is running by then, that sort
  waits for its end. Other returns read nothing unless the library changed. A row whose new length crosses the short-file
  limit stays until the next read of the list.
- A line over the top of the list shows the progress of a run of at least 50 files, without moving any row.

### 5.5 When it runs

- In the main process, one run at a time, on a thread of background priority: a few seconds after the process starts,
  after a sync that changed the library or its folder rules, and when asked (§5.6). It needs a library that knows its files.
- It reads while the app shows or music plays, also with the screen off and no charger; otherwise Android soon freezes the
  process, and a read frozen in the middle could count as a hang. So a library opened rarely and never played may stay unread
  for long; lists show the index's values meanwhile.
- A run waits between files when that no longer holds, and for a minute after an underrun of the audio or after a device
  has been hot (thermal status moderate or above). Nothing is cancelled for that, because cancelling a read kills `:tagger`.
- The pass takes no wake lock of its own and no WorkManager: quotas apply to that even during playback. The reads run at
  background priority too, which the binder call carries over to `:tagger`.
- Checks: a pass over the 20 000 generated files on the phone, with the FLAC album on repeat, the screen off and no
  charger: no underrun, no stall, no new audio output, and the worst lateness of the playback thread's looper compared
  with a minute of playback without the pass (`TagPassScreenOffTest`).

### 5.6 What the settings offer

- **Refresh the library**: a sync now, and a run of the pass after it, for what the sync found. Nothing else: a sync also
  runs by itself when the index reports a change (§1.3), so this is for when something seems to be missing.
- **Read all tags again**: every file is read again, those that failed before too. `tag_pass_at` stays set, so lists keep
  showing what they show until the new values land.
- **Rebuild the library**, which keeps the user data, came with step 8, because it relies on re-attachment (§8.5).

### 5.7 The legacy encoding (M2b)

- **Legacy tag encoding**, in the Library section of the settings, chooses how text in legacy fields is decoded
  (TI §3.6): Automatic, the default, or one code page. The row shows the choice; a dialog lists Automatic first and
  then the code pages the device supports, each as its script with the code page's name ("Cyrillic (KOI8-R)"), with
  a line that says which tags it is for. A choice applies when it is tapped; there is nothing to confirm, because
  choosing Automatic again undoes it.
- The choice is stored in the `settings` file under `legacy_encoding` as the code page's name in lower case; no value
  means Automatic. It goes into backups with the other settings.
- Reads after a change decode with the new setting at once. Files read before it are decoded again by the tag pass's
  thread, as part of a run: a change asks for a run, and a run that is already reading does it before its next file,
  so a choice made during the first pass of a large library shows at once and not only after the pass.
- Decoding again reads no file. It takes the files whose `tag_encoding` is set, which are exactly those whose legacy
  fields hold bytes above ASCII, decodes their stored raw tags with the new setting, and leaves a file as it is when its
  code page stays the same (files whose legacy fields are valid UTF-8, for example, or every file when the choice
  equals what the automatic mode found). Files without legacy text are never touched. Values over 16 KiB, which the raw
  tags leave out, are not decoded again; none of them is a value the lists use.
- `device_state` keeps the choice the stored files were last decoded with (`legacy_encoding_decoded`, no value for
  Automatic), written when a decoding again has finished. Every run compares it with the setting before it reads, so
  a change that no run could finish before the process ended is still applied, and a decoding stopped half-way goes on
  where it stopped, because the files it finished already carry the new code page. It describes the library database,
  so it is not backed up either; every library of M2a was decoded automatically, so an update to M2b starts with no
  work.
- It stores in batches like the pass (§5.2): up to 200 files a transaction, while no sync runs, each with its track,
  artists, genres and album built again as a read builds them, and the new `tag_encoding`. A file whose album title or
  album artist changes moves to another album key, as after a tag edit by another app.
- Lists and system surfaces get the new values as after a batch of the pass (§5.4): rows change in place, the session's
  items and the controller tree follow, albums, artists and genres group again after the run, and the list is sorted
  anew at the next point §5.4 names.
- The automatic mode's language condition is read at each decoding; a change of the device's or the app's languages
  alone does not decode the library again in M2b ("Read all tags again" does).
- Checks: the decoding again of only the affected files without a read, a wrong choice and its undoing, albums,
  artists and genres that follow the new text, a choice made while a run reads, a change no run applied before the
  process ended and a decoding stopped half-way, the lists' batches through the runner; the fixtures of TI §11.2
  through the real reader; the setting and the remembered choice in their files; the setting's dialog with its labels
  and actions.

## 6. Library tabs and pages (M2a step 6)

### 6.1 Screens

- The library screen has a top bar with the app's name and actions for search, sort and the settings, then the tabs
  Songs, Albums, Artists and Folders, and the mini player at the bottom. Without the audio permission every tab and page
  shows the permission card of playback.md §2.2; access granted from any of them starts the library.
- A tap on an album, an artist or a folder opens its page, which comes in from the side and has its own top bar with
  back, search and sort, and the mini player.
    - **Album page:** the album's title, its artist and year, then its songs. When its songs carry more than one disc
      number, each disc starts with a header.
    - **Artist page:** the artist's albums, then all of the artist's songs, in one scroll.
    - **Folder page:** a button that plays the folder with all its subfolders (when it holds any song), the subfolders,
      then the folder's own songs.
    - A page whose search finds nothing says so under its header. Another order or search starts the list at the top.
- The Folders tab is the page of the top level: with one volume, the folders at its root and the songs right in it;
  with more, one entry per volume (the primary one named "Internal storage").
- A tap on a song plays the list it was tapped in, from that song, in the order and with the search shown (§6.4).
- The playing song is marked in every list. On Now Playing the artist and the album open their pages, as found at the
  tap. Each song row
  has a menu with "Play next" and "Add to queue" (playback.md §9.1), "Go to album", "Go to artist" (one entry per
  artist) and "Go to folder"; album, artist and folder rows and the top bars of their pages have "Play next" and "Add
  to queue".
- The debug entry moves from the list into the top bar.

### 6.2 Grouping

- `track_display` also holds what groups and sorts a file: the album key, the album artist, the compilation flag, the
  year, the disc and track numbers, and the sort keys of the artist, the album and the file name (since step 11 the
  collator's keys of §11.1, with the album artist's and the title's). One resolver derives
  them with the other display values (§1.2), from the tags once the tag pass has read the file, and from the index's
  values until then, so that the first launch groups at once and is not slowed down.
    - The album key is the one the tag pass gives (§5.2): the release ID, or else the normalized album and album artist
      within a folder, a last folder named like a disc not counting, plus the compilation flag. A file without an album
      belongs to none.
    - The index gives the track number with the disc in its thousands (`2005` is disc 2, track 5); the resolver splits it.
      The year is the first four digits of the date, or else of the original date; from the index, its year.
- `display_artist` lists each artist of a file in order: all the artists of its tags once read, otherwise the displayed
  artist. An artist is one by its normalized name, which is also the key its page is found by: the key stays the same
  when the tag pass reads the file, and two artists of one name are one. Album artists and composers are not listed.
- An album shows its album artist; without one, its artist when all its songs share it (by its key, so "Queen" and
  "QUEEN" are one), otherwise "Various artists"; with no artist on any song, "Unknown artist". A compilation without an
  album artist shows "Various artists" too.
- The resolver normalizes each text once: the title and file name once per file, and the album, artists, album artist,
  composers and genres, which repeat over an album's tracks and over one track's fields, once per batch of files. With
  every text normalized again, the grouping values made a first sync of the generated library about 1.5 s slower.
- Hidden files (§3.5) count nowhere: an album, artist or folder whose songs are all hidden is not listed.
- An album's year is the earliest year of its songs. Albums sort by artist with the key of their album artist, or else
  the lowest artist key of their songs.

### 6.3 Sorting and search

| List | Sort fields | Default |
|---|---|---|
| Songs | title, artist, album, length | title |
| Albums | title, artist, year | title |
| Artists | name, number of songs (most first) | name |
| Album page | disc and track | disc and track |
| Artist page songs | album by year, title | album by year |
| Folders tab and folder pages | songs by file name or title; subfolders always by name | file name |

- Every list also has "Reverse order", which reverses the whole list (on a folder page, its subfolders too). Ties go by
  title, then by the index's ID, so that the order is total and the reverse exact; disc and track go by the file name
  before the title. A missing value (no length, no year, no track number) sorts last. Albums and artists tie by their
  keys.
- The choice is kept per kind of list, not per album or folder, in the settings file that is backed up (§3.2).
- Sorting compares sort keys, which come from one function. Step 6 made them the names normalized as for search (TI
  §4.1); step 11 made them locale-aware ICU keys that honour sort tags, without changing the queries (§11.1).
- The search action opens a field over the current tab or page that filters it as the user types (the list is read
  once typing pauses for a quarter of a second): songs by their search
  text (§1.2), albums by their title and artist, artists by their name, folders by their name (then any folder of the
  library that matches, with its path). The words of the query must all occur, as in §1.2. A search does not change the
  sort.
- On an artist page the artist's albums are sorted by year, then title; they are every album with a visible song of
  theirs. Its songs "by album by year" keep each album together: albums by the earliest year of the artist's songs on
  them, each by disc and track, and songs without an album last, by their own year.
- "Play all, with subfolders" plays the folder's tree with the page's sort and search, from its first song.

### 6.4 Queue requests

- A tap sends a compact request of version 2 (playback.md §2.3) that names the list, its key, the sort, the reverse
  and the search: `q2/<list>?key=<key>&sort=<field>&rev=<0|1>&q=<query>&start=<ID>&index=<row>`, the key and the query
  percent-encoded, the start the song's file ID (§8.4). The lists are `songs` (no key), `album` (its album key), `artist` (its artist key), `folder` (the
  volume, `/` and the relative path) and `tree` (a folder with all its subfolders).
- `tree` plays the folder's own songs first, in the folder page's sort, then each subfolder in name order, in turn with
  its own songs first. "Reverse order" reverses the whole list.
- The service expands a request from the list the screen shows when it holds the start song, otherwise by reading the
  list again. The screens and the service share one kept list per request without its start, so that a tap plays
  exactly what is visible: each screen holds the list it shows and the service the list it plays, and besides those the
  last six lists read are kept. A batch of the tag pass changes the rows of every kept list in place, and every kept
  list follows the rules of §5.4.
- A saved queue of an album or an artist whose key names nothing any more when it is restored (the tag pass has found a
  release ID or another album artist since) follows its saved song to that song's album or first artist, and the saved
  request takes the new key.
- Requests of version 1 still name the Songs list by title, so that saved queues stay valid.

### 6.5 Routes

- Pages are found by natural keys, not by row IDs: an album by its album key, an artist by its artist key, a folder by
  its volume and relative path. They survive a rebuild of the library database and a new index. A key that no longer
  names anything (the tag pass found a release ID, the folder is gone) shows that the album, artist or folder is not
  found, with the way back.
- While the tag pass runs, the lists of albums, artists and folders are not grouped again under the user's finger:
  they are read when the screen first starts, after a sync that changed the library, after a change of the hidden files,
  the order or the search, and once more at the first return after a pass that changed rows; a return or a switch of
  tabs reads nothing else. The songs on pages change in place as in §5.4.

### 6.6 Checks

JVM tests: the request of version 2 for every list, keys with every kind of character, version 1 still read; the folder
level of one or more volumes with counts that include subfolders; the track number with its disc; the display values of
grouping before and after the read; kept lists per request.

Instrumented tests: on the library database, albums grouped from index values and from tags, `CD1` and `CD2` as one
album, compilations, multi-value artists, the songs of each list in every sort and its reverse, the search of each list,
hidden files left out, the `tree` order; through the service, requests of version 2 expand to the visible list; on the
screen, the tabs, the pages, a page found again after the library database is rebuilt, the sort and search controls, the
Now Playing links, the mark of the playing song and the song menu.

## 7. Content key and exact duration (M2a step 7)

The layout of the key and the rules for lengths are in TI §3.1. This step builds them behind `:tagger`.

### 7.1 What a read returns

- A read of `:tagger` (§4.2) also returns the file's content key in hex, its format token and a duration plan: the
  length to store for now, if the header gives one, and what is left to do: nothing, a check of FLAC's end, or a walk.
- The key depends on the content alone, not on the name and not on whether TagLib took the tags. A file TagLib calls
  unknown still gets a key, from its whole bytes unless its first bytes or its MPEG frames say otherwise. A read that
  failed for a reason that may pass (a read error, a native error) gives no key, and neither does an empty file.
- The walks of the formats are Kotlin, tested on the JVM against synthetic files and against the keys of an independent
  reference (`scripts/content_key_reference.py`). Only MPEG frame offsets come from TagLib.

### 7.2 The frame walk

- `audio_file.content_key`, `duration_ms` and `duration_source` hold what a read and the walks found, and the sync clears
  them when a file's size or modification time changes. The source says how far the length is settled: `header`, `walk`,
  `unknown`, or waiting with `pending-walk` (no length yet: lists show the index's) or `pending-flac-end` (the header's for
  now). A read of the same file again keeps what a walk settled. `track.track_key` is the content key and the track's
  start, `<key>:0` until CUE sheets arrive.
- `walkDuration` is a call of its own, for files whose plan asks for one, and runs last, after the tag pass, in the pass's
  order. A run the pass is asked for goes first: the walks stop between files, and what is left waits for the next run.
- The gate of the pass (§5.5) applies, but a walk may read for long, so it does not wait for the next file: 3 s after the
  gate closes it is cancelled, which kills `:tagger`, and its file comes again after the others.
- Results go in batches of at most 50 or 1 s. The list gets the new lengths in place, and no order waits for them.
- A crash or a timeout skips the file until the process starts anew or a full read is asked for, and counts as in §4.3.
- It runs in `:tagger` and not in the main process: it shares no heap with the player, and it can be ended at once by
  killing the process, as any call whose caller gives up (§4.3). Its thread runs at background priority. It goes on
  while music plays; the 0 underrun measurement of the tag pass covers it.
- It reads no sample data, only the samples' times, and takes the last time plus one frame. On the API 36 emulator
  63 MiB of raw AAC take 1.4 to 4 s.
- It counts against the file like any call of `:tagger`, with a timeout of its own, 2 minutes or 1 s per MiB,
  whichever is longer. A file the extractors cannot read has no length, which is not a failure.
- FLAC is checked instead of walked: the extractor seeks to 2 s before the STREAMINFO length and goes on to the last
  frame. Only when that frame ends more than 2 s too early is the file walked from the start.
- A WAV that is cut off comes out within a tenth of a second, the size of the extractor's samples.

### 7.3 Checks

JVM tests: the layout against reference keys, with the limits at 131 071 and 196 607 bytes; the walk of each format on
synthetic files; the bounded Ogg walk (page sizes, CRC, chains, a cut or junk at the end, bytes read); golden keys of
real files in the walked formats; the table of duration rules; the sample clock.

Instrumented tests on the emulators, through the real `:tagger`: golden keys of every format; tag edits in every
format, also by real tools (a copy with a new Xing frame, `moov` moved, comments rewritten); any file name and no name;
a file TagLib refuses; the walked length of headerless VBR and CBR MP3 and raw ADTS against the decoded length within
one frame; truncated WAV, MP4 and FLAC files; an interrupted walk.

On the phone (`TagPassScreenOffTest`, which waits for the walks too): after a pass over the 21 368 files the walks settled
19 499 lengths in 1 155 s (17 a second) with the FLAC album on repeat, the screen off and no charger: no underrun, no
stall, no new audio output, and a worst lateness of the playback thread's looper of 71 ms during the walks (116 ms during
the pass; the earlier control minute without either measured 82 ms).

## 8. Track identity and re-attachment (M2a step 8)

The rules are in TI §3.1 (re-attachment) and §10.2 (`track_identity`). This step builds them for the library; the
saved queue that refers to identities is in playback.md §4.

### 8.1 Identities

- `user.db` version 2 adds `track_identity(id, content_key, start_ms, duration_ms, recording_mbid, hint_volume_name,
  hint_relative_path, hint_display_name, hint_size, created_at, missing_since)`. The content key is a 32-byte blob and
  the recording MBID a 16-byte blob; `(content_key, start_ms)` is unique. Version 1 migrates by adding the table.
- Every file whose content key is known gets an identity, so that re-attachment runs on the whole library from the
  first day, not only once favorites arrive. 20 000 identities take about 4 MB of the backup (TI §10.2).
- A file is attached when an identity holds its key. Byte-identical audio in two places shares one identity; its hints
  name one of the places, the one it named before while that file is still there.
- `missing_since` is empty while a file holds the identity's key and is set when none does any more. An identity that
  holds no user data and has been missing for 30 days is deleted: a deleted file, an SD card left out of the phone. An
  identity that holds user data is never deleted. User data that refers to identities: a song's own cover (§13.2), a
  favorite, play events and play statistics (§15); playlists (M2b) and decisions (M3) will add theirs. Album members
  (§15.3) are not user data: they never keep an identity.
- Attaching changes no user data, so it does not raise the user database's change counter (§3.1). Nothing is written
  into the library database: lists do not need the identity, and the two files keep no row IDs of each other (TI §10).
  Each pass asks the mirror of §15.4 for a run afterwards, because favorites and play counts follow the keys.

### 8.2 The attach pass

- One pass at a time, in the main process, on a background thread. It runs when the library starts, after a sync that
  changed files and after a run of the tag pass and its walks; requests during a pass collapse into one more pass.
- It reads the files of the library database while no sync writes, and then writes only the user database, in one
  transaction. It writes only what changed: new identities, new keys and hints, `missing_since`, deletions.
- A file is a candidate once its key is known and its length settled: a length still waiting for a walk or a check of
  FLAC's end (§7.2) could fall outside the 1 s window of steps 2 and 3. Such a file gets no identity until it settles.
- In each pass, in this order (TI §3.1):
  1. Identities whose key a file holds are attached; their hints follow the file when it moved.
  2. A missing identity takes the key of an unattached file at its hinted place (volume, relative path, display name)
     whose length is within 1 s of the identity's. The key changed in place: a gain tool rewrote the frames.
  3. A missing identity with a recording MBID takes the key of the one unattached file that carries that MBID in its
     tags, when the length is within 1 s and no other unattached file and no other missing identity has that MBID.
  4. Missing identities that hold user data try the fingerprint cache (§8.3).
  5. Every unattached file left gets a new identity.
- Taking a key moves the identity: `content_key`, `duration_ms` and the hints become the file's, and `missing_since` is
  cleared. When two missing identities could take one file, the newest one takes it.
- The identity's recording MBID follows the file's tags while it is attached. A decided MBID of M3 will win over tags.

### 8.3 The fingerprint cache

- `library.db` gets `fingerprint(content_key, fingerprint, computed_at)`, the cache of TI §10.1 without the columns of
  identification (M3). A row whose key no file and no identity holds is dropped after 30 days.
- Step 4 runs only for missing identities that hold user data and whose old key has a cached fingerprint. Candidates are
  attached files of the same format, with a length within 1 s, whose identity holds no user data and is newer than the
  missing one: the file came after the identity went missing, so the pass of step 5 may already have given it a new
  identity. Candidates without a cached fingerprint are fingerprinted at background priority (the decoders of TI §3.2,
  at most 120 s of audio) and cached by their key.
- A match is a bit error rate of at most 10 % at the best offset (`FingerprintComparison`, as in M0). With exactly one
  match the missing identity takes the file's key and the new identity is deleted; with none or more it stays missing.
- Each pair of identity and file is tried once per process. The library scan never fingerprints (TI §3.1).
- In M2a nothing fills the cache, so this step finds nothing yet even for identities with an own cover (§13.2);
  identification (M3) fills the cache, and favorites (M2b) make more identities hold data.

### 8.4 File IDs

- Lists, queue requests and the session's media IDs now use PiPlayer's own file ID (`audio_file.id`) instead of the
  media index's ID. A file keeps it when the index is rebuilt (§1.3) and when the library is rebuilt (§8.5); only the
  content URI the player opens still carries the index's ID.
- A file ID is never given to another file: `library_meta.last_file_id` keeps the highest ID ever given, so a deleted
  file's ID is not used again.
- `library_meta.library_id` is a random ID made when the library database is created. A saved queue stores it, and file
  IDs saved under another library ID mean nothing (playback.md §4).

### 8.5 Rebuilding the library

- Settings, Library: **Rebuild library**, after a confirmation that says that lists empty for a moment and tags are read
  again, and that favorites and other user data stay.
- The tag pass stops, the library database is read for its files (ID, volume, relative path, display name) and its
  library ID, deleted and created anew. The first sync matches the index's rows to those files by place, as a full sync
  does (§1.3), so every file that is still there keeps its ID and a playing queue keeps playing. Then the tag pass reads
  everything and identities attach again by key as the keys come back. A library database that cannot be read is
  rebuilt with new IDs and a new library ID.
- Lists show "Loading" until the first sync has finished, as on a first launch.

### 8.6 Checks

JVM tests: the attach pass on tables of files and identities: each step of TI §3.1 and its order, another song at the
same place 3 s off is not attached, two unattached files with one MBID attach neither, a file with a pending length is
left alone, byte-identical copies share one identity and keep its hint, `missing_since` and the 30-day deletion without
user data, never with it; step 4 with fake fingerprints (one match, two matches, a different format, a length 2 s off,
an older identity, once per process).

Instrumented tests on the API 30 emulator:

- `user.db` from version 1 to 2 with a row of version 1 (`MigrationTestHelper`), and the exported schema.
- Through the real library, the sync and the tag pass on generated files: a file moved to another folder, a renamed
  file and an index rebuild with new IDs keep their identity; a file whose audio changed at the same place keeps it; a
  moved Ogg file whose comments an external tool rewrote keeps its key and identity; a deleted file's identity goes
  missing and is deleted 30 days later; rebuilding the library keeps file IDs and identities.
- Step 4 on real fingerprints: a WAV file and a copy with one changed sample at another place (another key, nearly the
  same sound) attach to the identity of the first through a cached fingerprint, when that identity holds user data in
  the test.

## 11. Sorting, the scroll thumb and grids (M2a step 11)

### 11.1 Collation

- Lists sort with `android.icu.text.Collator` for the app's language (the first locale of the app's configuration, which
  follows the system unless the app has a language of its own): Russian puts Cyrillic before Latin and ё with е,
  English puts Latin first. The collator compares case and accents only as tie-breaks (tertiary strength), orders
  digits by their value (`Track 2` before `Track 10`) and ignores spaces and punctuation (`(I Can't Get No)
  Satisfaction` sorts under I). It is frozen once and shared by all threads.
- A sort key is the collator's key of a text as bytes, stored as a BLOB. SQLite compares BLOBs byte by byte, which is
  the collator's order, so the queries of §6.3 keep their shape. `track_display` holds `title_sort`, `artist_sort`,
  `album_sort`, `album_artist_sort` and `file_sort`, and `display_artist` holds `sort_key`. The text keys `title_key`,
  `album_title_key` and `file_key` are gone. `artist_key` stays: it is the artist's identity, the normalized name their
  page is found by (§6.2), not an order.
- What a key is made from: the title's sort tag (TITLESORT), else the title; the artist's (ARTISTSORT), else the artist;
  the album's (ALBUMSORT), else the album; the album artist's (ALBUMARTISTSORT), else the album artist; the file name
  without its extension. A sort tag counts only while its field comes from the tags: before the tag pass has read the
  file the index gives no sort tags, and a value of levels 1 and 2 (M3) ignores the tag. An artist of `display_artist`
  takes ARTISTSORT when the file has exactly one artist, otherwise their name. Articles are not dropped: "The Beatles"
  sorts under B only with a sort tag such as "Beatles, The".
- Ties go as in §6.3: by the title's key, then by the index's ID. Albums by artist sort by their album artist's key,
  else the lowest artist key of their songs; artists by name by the lowest key of their rows, so that a sort tag on any
  of their files counts. Songs by album and by "album by year" sort each album by the lowest album key of its songs, so
  that songs that spell the album differently ("Greatest Hits", "Greatest hits") stay together in disc and track order.
  Subfolders sort by name with the same collator.
- The collation is named `<language tag>/<collator version>/<UCA version>/<rules version>` and stored in
  `library_meta.collation`. The tag keeps the language, script, region and a collation keyword (`-u-co-`), not the
  regional preferences that Android 14 adds to the locale (first day of the week, units), which do not change the order.
  When a sync finds another name (the app's language changed, Android brought another ICU, the rules changed), it
  derives every display row again in its transaction, as for a new display version (§6.2). A change of the app's
  language while the process runs asks for a sync at once, without a run of the tag pass. Lists sort anew when it has
  written, keeping the row at the top in view; a song list kept in its order during the tag pass (§5.4) takes the new
  order too, because it was read under another collation. A saved queue read again by its request (playback.md §4) may
  come in the new order; its current song stays.
- The keys are computed by the resolver with the other display values, off the transaction, on all cores. A frozen
  collator makes one key at a time behind a lock, so each chunk takes a thawed copy of its own, and keeps the keys of
  the texts it has seen, so an artist or an album of many songs costs one key. The keys replace three normalizations of
  step 6. `library.db` is version 9.

### 11.2 The scroll thumb

- Every list of the tabs and pages longer than five screens gets a thumb at its right edge (it goes again only below
  four, so that partly visible rows do not make it come and go). It shows while the list scrolls and hides 1.5 s after
  the last scroll or drag. Dragging it moves the list in proportion, the thumb's place being the row at the top as at
  rest, and shows a bubble with that row's section beside the thumb. The menu items "Reverse order" and "Show as grid"
  tell TalkBack whether they are on.
- Sections: the letter for orders by title, artist, album, name and file name; the year for orders by year and "album
  by year" (the album's year); the whole minute for length (`3:00`); the number of songs for "number of songs". Orders
  by disc and track have no bubble, only the thumb. A row without the value (no year, no length) shows `?`.
- Letters come from `android.icu.text.AlphabeticIndex` built on the same collator, with the letters of the app's
  languages and of English; digits, symbols and empty names go under `#`, other scripts under `…`. A row's letter is
  found from its stored key: the key's primary part (up to the first level separator, the byte 01) is compared with the
  primary keys of the runs the letters start. A letter starts a run at itself; the Latin letters of Chinese pinyin start
  a second run at the collator's own boundary among the Han characters; a bucket of other scripts starts a run at each
  script the index files into it. So the letter agrees with the order and with ICU's own index exactly, sort tags
  included, and costs nothing at the first launch. The letters are built on a background thread when the collation is
  chosen, before a list needs them. The controller tree of step 10 uses the same letters.
- Each row carries its section as text (`Song.section`, `AlbumGroup.section`, `ArtistGroup.section`,
  `FolderEntry.section`), set when the list is read. A row the tag pass changes in place keeps its section until the
  list is read again.

### 11.3 List or grid

- The sort menus of the Albums and Artists tabs have "Show as grid". The choice is kept per tab in the settings file
  that is backed up (§3.2); a list is the default. The albums of an artist page follow the Albums tab.
- A grid has columns at least 150 dp wide, two on a phone held upright; an artist page's albums take as many columns as
  the Albums tab. An album's tile is a square with its cover (§13.5), its title, then its artist and year; an
  artist's tile is a circle with their cover, or else their letter, their name, then the numbers of albums and songs. A tile
  has the menu of its row (step 9: play next, add to queue) beside its title. The thumb works over grids too.

### 11.4 Checks

JVM tests (with ICU4J as a test dependency): letters found from keys for Cyrillic, Latin, Chinese, digits, punctuation,
both cases, equal to ICU's own index; keys from sort tags only after the read, only for a field from the tags and never
over levels 1 and 2; the artist's sort tag with one and with two artists; sections for every order; subfolders by the
collator; regional preferences keep the collation's name; a song list read under another collation takes its order.

Instrumented tests on the API 30 emulator: the platform's collator for Russian (Cyrillic first, ё with е) and English,
digits by value, punctuation ignored, letters equal to `AlphabeticIndex`'s own for a mixed set of names; the lists of
§6.3 in Russian order with sort tags after the read, albums and artists by their keys, an album spelled two ways kept
together, sections of the Songs, Albums and Artists lists; a sync under another collation writes every key again and a
sync under the same one writes none; on the screen, the thumb on a long list and over a grid shows its letter and moves
the list, no thumb on a short list, and "Show as grid" on Albums and Artists with its state for TalkBack, kept after the
activity is recreated, and followed by an artist page. The quiet timing pass keeps the first launch under 5 s and a later launch under 1 s.

## 13. Covers (M2a step 13)

Covers inside the app (part A): where a cover comes from, the user's own covers, loading and caching, the placeholder.
Covers for controllers outside PiPlayer (part B, §13.8): a content provider that serves the covers of the library tree
(playback.md §10) and of the playing song by content URI.

### 13.1 Sources and their order

- A song's cover is the first of:
  1. the song's own cover (§13.2);
  2. its album's own cover;
  3. the picture embedded in the file, read on demand by Media3's extractors, the reader playback uses (§13.3): a front
     cover wins over other pictures, otherwise the last picture counts (playback.md §3.4). It is skipped when the tag pass
     found no picture in the file (`embedded_cover_present` is 0); an unread file is tried;
  4. the system thumbnail of the file (`ContentResolver.loadThumbnail` on its media index URI), which Android takes from
     the embedded picture or from an image in the file's folder;
  5. the placeholder (§13.4).
- An album's cover is its own cover, otherwise the embedded picture or system thumbnail of one of its songs: the first by
  disc, track and file name among the songs whose picture the tag pass found, then among the unread ones, then among the
  rest. A song's own cover stands for that song only, never for its album. Hidden files (§3.5) may stand for an album too:
  their pictures are the album's all the same. An artist's albums, though, are exactly those of the artist page, with
  its rule for hidden files.
- An artist's cover is the cover of one of their albums, taken in the artist page's order (the album's year, then its
  title, over all its songs, so that a compilation sorts by its own year): the first
  with an own cover, otherwise the first with an embedded picture, otherwise the first. An artist without albums shows
  the embedded picture or system thumbnail of their first song.
- Media3 reads the picture rather than `MediaMetadataRetriever`, because the platform's retriever takes the first picture
  of an MP3 even when it is a back cover, and because what lists show then matches what playback reads. The extractors
  run in Java in PiPlayer's process: a malformed file fails with an exception, not a crash.
- A picture in the album folder (`cover.jpg`) through a folder grant comes with folder access in v1.x; until then folder
  images reach PiPlayer only through the system thumbnail. Covers from Cover Art Archive come with identification (M3).
- A file opened from another app is not in the library: Now Playing shows the picture the player read from it, as before
  (playback.md §5.2).

### 13.2 Own covers

- The menu of a song row has "Choose cover" and, when the song has one, "Remove own cover"; the menus of an album row,
  an album tile and the album page's top bar have the same for the album. "Choose cover" opens the system photo picker
  for images; PiPlayer needs no permission for it. Where the photo picker is not available, Android shows its document
  picker instead.
- A song's own cover belongs to its track identity (§8.1), so it follows the song through renames, moves, a new media
  index and a rebuild of the library. A song gets an identity once the tag pass has read it; until then "Choose cover"
  says that the song's tags are still being read and changes nothing.
- An album's own cover belongs to its album key (§6.2, §6.5), so it survives renamed files and a rebuild of the library.
  When the key changes (the tag pass finds a release ID, or the album's folder moves), the cover follows the album's
  tracks to the new key as §15.3 says; until it can, the reference stays stored and applies to nothing.
- PiPlayer keeps a copy of the chosen image and never changes an audio file. The image is decoded (the decoder applies
  its orientation), scaled down to at most 1500 px on its longer side and saved as JPEG at quality 92, or as PNG when it
  has transparency, under a random name in `no_backup/covers/`. A new choice replaces the old copy; "Remove own cover"
  deletes the reference and the copy. Copies that no reference names (a choice cut short by the process dying) are
  deleted the next time the covers are read.
- `user.db` version 3 adds `track_cover(identity_id, file_name, width, height, set_at)` and
  `album_cover(album_key, file_name, width, height, set_at)`, keyed by their first column. Version 2 migrates by adding
  the two tables (an automatic migration with the exported schema `3.json`).
- An identity with an own cover holds user data: it is never deleted, and step 4 of the attach pass (§8.3) tries to keep
  it on its file.
- Setting or removing a cover does not raise the change counter of §3.1: the library database mirrors nothing of it. It
  asks the mirror of §15.4 for a run, which records the album's members (§15.3).
- Backup: the references are backed up with `user.db`; the copies, in the no-backup files, are not (TI §10.3). After a
  restore a reference whose copy is missing counts as no own cover, and the next source shows: the first read of the own
  covers drops such references, so that neither the menus ("Remove own cover"), nor an artist's choice of album, nor the
  session takes them for covers.

### 13.3 Loading

- One loader in the main process serves the screens and the session. A view asks for a cover with what it shows (a
  song by file ID, an album by key, an artist by key) and the size it draws, in pixels. Everything but a memory hit runs
  off the main thread, at most four loads at a time. A row that scrolls away cancels its load: the system thumbnail
  through its cancellation signal, Media3's retrieval by closing it.
- Sizes are rounded up to steps: 96, 128, 192, 256, 384, 512, 768, 1024, 1536 and 2048 px. Decoders scale while they
  decode, so that the shorter side is at least the step (views crop to a square); a row never holds a full-size picture.
  Now Playing asks for the shorter side of the screen.
- A memory cache keeps decoded pictures, an eighth of the heap and at most 32 MB, the least recently used going first.
  It also remembers that a source had no picture. A change of own covers empties it, and the covers on screen load again,
  each keeping its picture until the new one is there. A sync that changed the library and the end of a run of the tag
  pass empty it too (not every batch, so that scrolling back during a pass finds its covers), and so does an attach pass
  that wrote identities, since an own cover follows its identity to another file (§8.2), and a change of the hidden
  files, which decide the albums of an artist (§13.1) and raise no revision; views on screen then keep what they show
  until they are drawn anew.
- A disk cache keeps the pictures of steps up to 768 px as JPEG at quality 90, and an empty file for an embedded picture
  that a file does not have, in `cache/covers/`. A missing thumbnail is not remembered on disk: an image put into the
  folder later changes neither the file's size nor its time, and a passing error of the media index must not stick. A
  missing own copy is only looked for again. The cache holds at most 100 MB, the least recently used file going first.
  It reads its folder once, each file's time once, outside its lock. Keys name the source and its state: an own cover by its copy's name; a picture of a file by the file ID, size and modification time, so that a
  changed file is read again. The cache is cleared with the app's caches and is never backed up (§3.3). A picture of
  Now Playing's size is decoded from its source each time.
- Own covers are read from `user.db` once and kept in memory; a change replaces them and raises a version that the
  views watch.

### 13.4 Placeholder

- Without a cover, a song or an album shows a calm color made from the album name with a music note on it: the hue comes
  from a stable hash of the name (FNV-1a over the lowercase name), the saturation is low, and the color is light in the
  light theme and dark in the dark theme, with the note in a darker or lighter shade of the same hue. The note has a
  contrast of at least 3:1 against the color in both themes. The same name gives the same color everywhere; neighbouring
  tiles may share one.
- A song without an album and a file that is not in the library show the neutral placeholder of playback.md §3.4. An
  artist without a cover keeps their letter (grid) or the artist icon (list).

### 13.5 Where covers show

- Song rows, album rows and artist rows: a 48 dp picture before the text, rounded for songs and albums, round for
  artists. Album tiles: a square as wide as the tile; artist tiles: a circle. The album page's header shows the album's
  cover at 120 dp beside its title.
- The mini player and Now Playing show the current song's cover by the order of §13.1; a new song shows the placeholder
  until its own cover is loaded, never the previous one.
- Covers are decorative and have no label (playback.md §3.8).

### 13.6 The session

- The session still shows the picture the player reads from the stream (playback.md §2.6). When the current song has an
  own cover (its own or its album's), that cover replaces it, as JPEG bytes loaded for 1024 px (a copy is at most 1500 px
  anyway); when the tag pass found no picture in the file, the system thumbnail loaded for 512 px stands in, so that a
  folder image reaches the notification too. A file opened from another app keeps the stream's picture. The service
  looks again at each change of the current item and of own covers (`SessionCovers`).
- Since part B the playing library song's metadata also carries its cover URI (§13.8.6) next to the picture. Items of
  the queue carry no artwork.

### 13.7 Checks

JVM tests: the order of §13.1 for songs, albums and artists, with and without own covers and embedded pictures; the
size steps and the decode target of an oblong picture; the disk cache's limit, its least-recently-used order, its empty
markers and a reopened cache; the memory cache's limit by weight; the loader: the first source with a picture wins, a
disk or memory hit reads no source, a source without a picture is remembered, a change of own covers loads again, a
cancelled load stores nothing; the placeholder's colors: the same name the same color, contrast in both themes, light
and dark lightness, spread hues.

Instrumented tests (on GitHub Actions, API 30 and 36):

- `user.db` from version 2 to 3 with an identity of version 2 (`MigrationTestHelper`), and the exported schema.
- Own covers: a chosen image is copied scaled down, a new choice replaces the copy, removing deletes it, a song's cover
  follows its identity to a moved file, a copy without a reference is cleaned up, an identity with a cover is kept.
- The sources on the cover fixtures: the embedded picture of MP3, FLAC, Ogg Vorbis, Opus and MP4, the front cover
  before a back cover, no picture in a file without one, the system thumbnail of a file in the media index, a picture
  decoded at its step and not at full size.
- On the screen: a song row, an album row and tile, and the album page show an own album cover; a song's own cover
  wins over its album's; the placeholder without a cover; Now Playing and the mini player show an own cover.
- Through the service: an own cover replaces the stream's picture in the session, and the stream's picture is kept
  without one.

### 13.8 Covers for controllers (part B)

Android Auto, Android Automotive, Wear and the system's media browsers load the covers of browse results themselves, by
URI. Android Auto takes `content://` and `android.resource://` URIs only and asks for no bitmaps in browse results:
many bitmaps can exceed the binder's 1 MB (playback.md §10.1). PiPlayer serves its covers through a read-only content
provider of its own, `CoverProvider`, with the sources, their order and the disk cache of part A.

#### 13.8.1 Which items have a cover URI

- Songs, albums and artists of the library tree, in browse results, items and search results, and the playing library
  song in the session (§13.8.6). A song's URI names the song, an album's the album and an artist's the artist; each
  serves the cover that §13.1 gives that subject in the app.
- Not the four tabs, the buckets, nor folders: a folder has no cover source of its own until folder access (cover.jpg)
  comes in v1.x, and a controller's own folder icon tells a folder from an album better than one song's picture would.
- Not files opened from another app: they are not in the library.

#### 13.8.2 The URI

- `content://<application ID>.covers/<kind>/<id>/<version>`, for example
  `content://io.github.lorddetson.piplayer.covers/s/1234/k3x90000`:
  - kind `s`, a song, by its file ID (§8.4), which survives a rebuild and a new media index;
  - kind `a`, an album, and `r`, an artist, by a number that the process gives each album key or artist key the first
    time it builds a URI for it. The key itself never goes into the URI: album keys hold names and folder paths, and
    they are long;
  - the version: four random characters (`0-9`, `a-z`) drawn when the process starts, then the cover generation in base
    36, at least four digits. The generation rises each time the loader's memory is emptied (§13.3): own covers
    changed, a sync changed the library, a run of the tag pass ended, an attach pass wrote identities, or the hidden
    files changed. A new generation keeps the URI's length, which browse items weigh, so that the borders between
    buckets do not move under a controller that pages through a node (playback.md §10.2).
- So a URI changes whenever the cover it names may have changed, and controllers that cache pictures by URI load it
  again. The tree's kept search results (playback.md §10.5) are built anew at a new generation, so that they never
  hand out an old version. One generation for all URIs, not one per item: building a URI reads nothing (a list of 20 000 songs builds
  20 000 of them), and the events that raise it are rare. A new process gives new URIs, so a change while PiPlayer was
  not running (a file edited elsewhere) cannot leave an old picture under an old URI; controllers then load each cover
  once more, from the disk cache.
- No file path, title, key or other user data goes into a URI. A song URI shows a file ID, a number that means nothing
  outside PiPlayer's databases.
- A URI is about 60 characters long; its length counts toward the weight of a browse item (playback.md §10.2). The four
  random characters make two processes give the same album and artist numbers under the same version about once in
  1.7 million starts; a controller would then show the cached picture of another album until the next change.

#### 13.8.3 Who may open a cover

- Exactly the controllers that may read the library through the session (playback.md §1.4, the rule of `GET_TIMELINE`
  and of browsing, §10.4): PiPlayer itself, the callers Android trusts for media control
  (`MediaSessionManager.isTrustedForMediaControl`: the system, holders of the media control permission, apps with an
  enabled notification listener), and apps whose uid holds the audio permission. The provider checks the caller of each
  open by its uid and pid, as the session checks a controller; a package name alone is not enough, and a caller that
  names no package (or one Android does not confirm as its own) is judged by its uid alone: the system or the media
  control permission. Anyone else gets a security error. Covers are pictures of the user's music: an app that may not
  read music must not read them through PiPlayer, just as it may not read the titles.
- Display is not reading: another app may put a cover URI into its own session or notification, and System UI, which
  may read covers, then shows that picture. The other app never gets the bytes.
- The provider is exported without a manifest permission and without URI grants:
  - Exported, because the controllers live in other apps. No platform permission is held by Android Auto, Wear and
    Android Automotive and by no other app, so a manifest permission could not draw the line; the check in code draws
    the session's line instead.
  - No URI grants (`grantUriPermissions` is false). The other way, a provider that is not exported plus a grant of each
    URI to each controller's package, needs a call to the system for every item of every page (up to 500 a node),
    leaves the grants in the system until the device restarts, cannot reach the readers of the platform session's
    metadata, whose packages PiPlayer does not know, and adds nothing: the controllers that would get grants are exactly
    those that pass the check.
- Only reading: any other mode is refused. `getType` answers `image/jpeg` for a URI of the right form without looking
  anything up or checking the caller, so it tells nothing; `query`, `insert`, `update` and `delete` do nothing.

#### 13.8.4 Serving

- An open checks the caller and the form of the URI, then returns the read end of a pipe at once: no database or file
  is read on the binder thread, so that a car asking for fifty covers cannot hold up the binder threads that the session
  needs too. The cover is looked up and written on the loader's threads (at most four loads at a time, together with
  the app's own loads, §13.3).
- The size is the 512 px step of §13.3: the shorter side 512 px, as a JPEG at quality 90. That is sharp on a car's
  tiles and its playing screen, and it is a step the disk cache keeps, so a cover that the app or a controller loaded
  before is served from `cache/covers/` without decoding; a new one is decoded from its source once and stored there.
- A song URI serves the song's current cover, whatever its version: file IDs mean the same in every process. An album or
  artist URI of another process (its four characters differ) or with a number the process never gave is not found.
- A disk hit is served as stored once it starts as a JPEG does; anything else is loaded again and replaced. A request
  that memory already knows to have no cover (§13.3) is answered at once, without a plan.
- Limits, so that a controller that scrolls fast or an app that opens thousands of URIs cannot hold PiPlayer down:
  - at most 64 covers are served at a time, each holding two descriptors, and at most 8 of them to one caller (uid), so
    that one caller that opens many and reads none cannot take the places of Android Auto, a watch or PiPlayer's own
    notification; more opens are not found;
  - when a load gets its turn (one of the four, behind the app's own loads), it first looks whether the reader is still
    there, and loads nothing for a reader that has gone;
  - a load is given up when it takes longer than 10 seconds from its turn on (the wait in line does not count);
  - a write is given up when the reader takes nothing for 10 seconds, or after 30 seconds in all;
  - a write that waits for its reader holds no thread: it looks at the pipe without waiting every 20 ms and is
    suspended in between, so that a reader that takes nothing (or shrinks its pipe) holds up no other cover;
  - waits and writes run on four threads of their own, apart from the database's;
  - whatever fails while a cover is served is logged, the reader gets an error, and the process goes on.

#### 13.8.5 Without a picture

- A subject without a picture still gets a URI: the tree builds its items without reading covers (playback.md §10.5),
  so it cannot know. Opening such a URI gives an empty pipe closed with an error, which controllers treat as a cover
  that failed to load: they show their own default. PiPlayer serves no placeholder: the colored placeholder of §13.4 is
  part of PiPlayer's own look, and in a car's grid a controller's uniform default reads better than a tile that looks
  like a cover.
- The tree does not drop the URI of a subject found without a picture later: items whose weight changed while a
  controller pages through a node could move the borders between its buckets (playback.md §10.2).

#### 13.8.6 The session

- The playing library song's metadata carries the song's cover URI next to the picture of §13.6. Android scales the
  bitmaps of the platform session's metadata down, and controllers such as Android Auto prefer a URI for the playing
  item; Media3 controllers of other apps take it from the metadata as well. The picture stays, for the notification and
  for controllers that cannot load URIs.
- The session's URI also names the picture it shows: its version ends with the CRC-32 of that picture in base 36
  (`.../s/1234/k3x90000` without a picture, `.../s/1234/k3x900001a2b3c` with one). Media3's bitmap cache matches the
  URI before the picture, so under one URI the picture loaded first (before the stream's arrived, or before an own
  cover replaced it) would stay. Any version names the song alike (§13.8.4).
- Where the metadata has the URI and no picture (the stream's picture not read yet, or a song without one), Media3
  loads the URI itself for the notification and the platform session, inside PiPlayer, through the provider. The
  notification may then show a cover it showed nothing for before. For a song without any cover that load fails,
  quickly once memory knows (§13.8.4), and Media3 logs it.
- Only the playing item: the queue's items stay without artwork (a URI each would change every item of a queue of 20 000
  at each new generation, and no platform queue is published, playback.md §2.3).
- When own covers change, the session looks again (§13.6), and its listeners hear the new URI even when the picture
  stays the same. After other changes of the generation the playing song's URI is new at its next change; until then
  its old URI serves the current cover anyway.

#### 13.8.7 Start

- Nothing at start or in the first sync: the provider's `onCreate` does nothing, and the cover loader, the numbers of
  keys and the version come into being with the first URI or the first open.
- Android publishes content providers before the application's own start has run, so an open from a controller that
  started PiPlayer's process waits (off the binder thread, at most 10 seconds) until the application has set the library
  up.

#### 13.8.8 Checks

JVM tests: the URI of each kind and its parsing back, malformed URIs and other authorities refused, the number of a key
stable within a process and refused from another one, the version rising with the generation and keeping the length,
the length bound; the access rule (own uid, trusted, audio permission, anyone else refused); the limit of covers in
flight, in all and for one caller; the loader's encoded cover: a disk hit served without decoding or reading a source,
a disk entry that is no JPEG loaded again, a miss read once and stored, no picture, a cover memory knows to be none, a plan that fails, a
picture that cannot be encoded, a load that takes too long, the time counted from the turn on, a reader gone before
the turn; a change of the hidden files empties memory; the length of a URI known without building it, a salted song URI.

Instrumented tests (API 30 and 36): a URI from the tree opened through the content resolver as a controller does gives
a JPEG of the album's own cover at 512 px, also for its songs and its artist, and the shell (another uid) reads it as
the rule decides for it; a caller that may not read music is refused, also when it claims PiPlayer's package; a write
mode is refused; a cover without a picture fails to open; browse results of a Media3 browser and of a platform browser
carry cover URIs and no artwork data or bitmaps, and so do search results; after an own cover changes, the tree gives
a new URI that serves the new picture, and the playing song's URI changes through the service; the playing song's
session metadata carries its URI next to its picture (an added entry its song's, an opened file none), the URI changes
with the picture shown, the platform session still gets the stream's cover, its listeners hear a new URI, and the same
again is no news; the provider is the only exported component besides the activity, the service and Media3's; a
reader that never reads, one that reads a page now and then, and one that has gone do not keep the writer, and one that
takes nothing does not hold up a second reader on the same thread; a caller over its share of open covers is refused
while another is served; a search again after a new own cover gives the new URI; the weight of an item with a cover URI
is at least its parcel.

## 14. First launch, empty states and open-source licences (M2a step 14)

### 14.1 First launch and empty states

- **Onboarding.** On a launch without the audio permission, before onboarding is done on this device, the app shows one
  screen instead of the library: the app's name, a few lines on what PiPlayer is and why it needs access to the audio
  files (only audio files are read, also on Android 11 and 12, whose dialog asks for "photos and media"), and an
  "Allow" button that brings up the system dialog (playback.md §2.2). Whatever the answer, onboarding is
  then done and the library screen follows at once: with the permission it starts the first sync and shows its
  progress, without it the library shows its permission card. Library folders and the theme stay in Settings; onboarding
  asks nothing else. The system back gesture on the onboarding screen leaves the app, as on any first screen.
- Onboarding is done once the system dialog it opened has answered, or as soon as the app holds the permission (granted
  in the system settings, by a restore, or by a test), so a phone that already gave access never sees it. The flag
  lives in the `device_state` file (§3.2), which is not backed up: a new phone shows onboarding again, as it asks for
  the permission again.
- A file opened from another app (playback.md §5) plays without the permission. Onboarding keeps the library's mini
  player and messages for it, so Now Playing opens from there as it does over the library.
- **Empty states.** Each library tab shows one of these instead of an empty list:
    - **No permission:** the permission card of playback.md §2.2, with "Allow access", or, once the system no longer
      shows its dialog, "Open settings" (the app's details screen) and "Try again".
    - **Scanning:** while the library is not ready (§1.3: no sync has finished in this process and the database shows
      none from an earlier one) for more than half a second, "Looking for music on this device" over an indeterminate
      progress bar. A later launch finds its earlier sync as soon as its first sync starts, a moment after the screen
      appears; the half second keeps that moment from showing the scan. The tag pass that follows has its own progress line over the list
      (§5.4).
    - **No music found:** when the library is ready and the tab's list is empty without a search: a title, a line on
      where PiPlayer looks (the music files in the device's storage, minus the hidden kinds and excluded folders of §3)
      and a "Settings" button that opens PiPlayer's settings. With a search the tab says that nothing was found, as
      before.

### 14.2 The licence list

- The licence list names every component the APK contains: each module of the variant's runtime classpath (AndroidX,
  Media3, Kotlin and kotlinx, OkHttp and Okio, Guava's parts, reorderable and the rest) with its version and licence,
  and the native components of `THIRD_PARTY_NOTICES.md` (TagLib, utfcpp, Chromaprint, KissFFT, speexdsp, LLVM libc++).
- It is written while the app is built, by an own Gradle task in `buildSrc` (`collect<Variant>DependencyLicenses`), with
  no new library: the task takes the resolved runtime classpath of the variant (group, name and version of every module
  in its graph, BOMs and Kotlin Multiplatform root modules included), fetches each module's POM, and takes the
  `<licenses>` of the POM or, when it has none, of its nearest parent POM, up to four levels up (Guava's parts have
  their licence in `guava-parent`). A configuration keeps one version of each module, so parents in two versions are
  fetched separately.
- The POMs are found while Gradle plans the task graph, since a parent level is known only from the level below. So a
  configuration cache miss (a changed build script, a new worktree, every CI run) resolves the runtime classpath and the
  POMs before any task runs, and Gradle notes that the classpath was resolved during configuration time; after the
  first build the POMs come from Gradle's cache. A run that reuses the configuration cache does none of this.
- Each POM licence is mapped by its name or URL to a licence kind the build knows: Apache License 2.0, MIT and BSD
  3-Clause so far. When the name and the URL disagree (a name of another licence with an Apache URL), the licence is
  unknown. The app ships one text of each kind it uses, from `app/licenses/<SPDX id>.txt`. The MIT and BSD texts
  ask for "the above copyright notice", which a generic text cannot hold, so each module under them has its notice in
  the build script, taken from the module's own licence file (checker-qual; AndroidX's copy of Protocol Buffers).
- The build fails, naming the module, when a module has no POM licence (itself or a parent), when its licence maps to
  no kind, or when its kind needs a notice that is not given. So a new dependency cannot reach the APK unlisted: adding
  it means adding its licence kind, text or notice when the build does not know them.
- The native components come from a list in `app/build.gradle.kts` with their licences and their own licence files,
  which `:nativelib` already puts into the assets under `licenses/` (with their copyright lines). The same list names
  what Maven modules carry under a licence of their own: SQLite in `androidx.sqlite:sqlite-bundled` (public domain) and
  the Public Suffix List in OkHttp's assets (MPL 2.0), with their texts in the app's own assets. The list ships in a
  processed form, so its entry also says where its Source Code Form is (MPL 2.0 §3.2(a)).
- Output, in the variant's assets: `licenses/dependencies.json` (the modules sorted by coordinates, the native
  components, and the kinds with the asset path of their texts) and `licenses/kinds/<SPDX id>.txt`. Debug builds list
  their own runtime classpath, which has the debug-only tooling modules as well.

### 14.3 The licences screen

- Settings has an About section with the app's version and "Open-source licences". The licences screen lists the
  modules, one row each with `group:name`, version and licence, then the native components with theirs. A row opens
  the licence: the module's copyright notice when it has one, then the kind's text; for a native component, its own
  licence files one after another.
- The list and texts are read from the assets off the main thread; a missing or unreadable list shows a line that the
  list could not be read instead of failing.

### 14.4 Checks

JVM tests: onboarding shows only without the permission and before it is done; the licence list read by the app
parses, holds every module whose files the debug APK packages (a second task lists them from the packaged artifacts,
not from the graph the list walks, so that a module the walk misses fails the test; BOMs and Kotlin Multiplatform root
modules have no files, so the list may hold more), each with a known kind whose text is in the assets, each MIT or BSD
module with a notice, and every component of `THIRD_PARTY_NOTICES.md` and nothing else. A module without a known
licence or notice already fails the build at the list task.
Instrumented tests: the onboarding screen and its "Allow" button; the gate shows the library once the dialog answered,
and never with the permission; onboarding keeps the mini player of an opened file; the empty states for no permission,
scanning and no music, and "No music found" on every tab of a library with no songs; Settings opens the licences screen, which lists a Media3 module and TagLib, and opens a licence text; every
text the list names opens from the assets.

## 15. Favorites, album references and play statistics (M2b: PIM-43, PIM-66, PIM-49)

The tables are in TI §10.2 and the mirror in TI §10.1 and §10.3; how a play is counted from the player is in
playback.md §11.

### 15.1 What `user.db` version 4 adds

- `favorite(identity_id, added_at)`: a favorite song, by its track identity (§8.1).
- `favorite_album(album_key, release_mbid, added_at)`: a favorite album, by its album key (§6.2), keyed by it. An album
  with a release ID has the key `mbid:<release ID>`, and then `release_mbid` holds the same ID as a 16-byte blob; other
  albums are found by their normalized title and album artist within their folder, as the key says.
- `album_member(album_key, identity_id)`: the identities seen on an album that a favorite or an own cover refers to
  (§15.3).
- `play_event(identity_id, started_at, listened_ms)`, keyed by the identity and the start, and
  `play_stats(identity_id, play_count, listened_ms, last_played_at)` (§15.5).
- `user_meta.rules_counter` (§3.1).
- Version 3 migrates by an automatic migration with the exported schema `4.json`, which then copies `change_counter`
  into `rules_counter`; a test runs it on a database of version 3 with rows.

### 15.2 Favorites

- A favorite toggle (a heart) sits on Now Playing, at the right of its top row before the queue button, and the song
  menu of every list has "Add to favorites" or "Remove from favorites". The album page's top bar has the same toggle for
  the album. Each toggle is labelled "Favorite" and states on or off (playback.md §3.8).
- A song becomes a favorite through its track identity, so it stays one through renames, moves, a new media index and a
  rebuild of the library, as an own cover does (§13.2). A song gets an identity once the tag pass has read it; until
  then the toggle says that the song's tags are still being read and changes nothing. A file opened from another app
  has no identity, and Now Playing shows no toggle for it.
- Byte-identical copies share one identity (§8.1), so they are favorites together.
- An album becomes a favorite by its album key. The Albums tab has a "Favorites" filter chip above its list or grid;
  on, the tab shows only favorite albums, with the same sort, search, list or grid. The choice lasts while the app runs
  and is not saved. With no favorite album it says "No favorite albums".
- Every change of a favorite raises the change counter of §3.1 in the same transaction, and the mirror (§15.4) brings it
  into the library database. Now Playing, the song menu and the album page read the user database itself, so they show
  a change at once; lists read the mirror.

### 15.3 When an album's key changes

The owner's decision of 14.11.2026: the album's references follow its tracks.

- An own album cover (§13.2) and a favorite album refer to an album key. The key changes when the tag pass finds a
  release ID, when the album's folder moves, or when the album or album artist tags change.
- Members: while a referenced key names an album (a file of the library database has it as its album key, hidden or
  not), every full mirror run records the identities of its files in `album_member`. Members are only added: a member
  stays when its file leaves the album, so that an album that leaves its key in steps is judged by all its tracks.
  Members of a key that nothing refers to any more go, and so do members whose identity is deleted (§8.1). Files the
  tag pass has not read yet have no identity and are no members; a reference made before the album's tags are read can
  follow only once they are.
- A move: the references of a referenced key move to the key that holds more than half of its present members, when
  that is another key. A member is present when a file of the library holds its identity's key; the album key of that
  file counts for it (an identity with copies in several albums counts for each), and a member still on the old key
  counts for the old key. Members that are gone count for nothing. So a retag that reaches most of an album's files (a
  release ID written into 11 of its 12 files) or a move of most of them to another folder takes the references along,
  also while the rest still names the old key.
- No move: while more than half of the present members are still on the old key, on a tie (half of them left behind,
  or a split into two halves), or when no key holds more than half (a split into three), the references stay where
  they are, and every later run looks again. While the old key names no album its references apply to nothing; a key
  that comes back has its references back with it.
- A merge: when several old keys move to one new key, or the new key already has references, a favorite stays one, with
  the earliest `added_at`; of the own covers the one set last (`set_at`) wins and the others are deleted with their
  copies. The members of the old keys join the new key's.
- It runs in the full mirror run of §15.4, after a sync, an attach pass or a run of the tag pass, never when a list is
  read. Moving a favorite raises the change counter; moving a cover does not, as setting one does not (§13.2).
- The covers move first, then the favorites and the members of the old keys in one transaction of the user database.
  If the cover move fails or the process dies in between, the old key keeps its members, so the next run finds the
  same move again and finishes it; a cover already moved is not moved twice.

### 15.4 The mirror

- The library database holds the mirrored user fields in two tables beside `track_display` (TI §10.1):
  `track_mirror(file_id, favorite, play_count, last_played_at)`, a row only for a file whose identity is a favorite or
  has plays, and `album_mirror(album_key)`, one row per favorite album. They are kept apart from `track_display`, which
  the sync, the tag pass and a new display version write whole, so that none of those writes has to carry the user
  fields over and the first sync does no more work than before. The library database's version goes to 10.
- A full run reads the favorites, favorite albums, plays and members of the user database, moves album references
  (§15.3), records members, and writes both tables anew in one transaction of the library database while no sync
  writes; files are matched by the content key of their identity. It runs when the library starts, after every change
  of the change counter, after every attach pass (§8.2) and after a change of the own covers; requests during a run
  collapse into one more run. It is cheap: a few thousand rows at most. It compares no counters and always runs when
  asked: before the first sync no file is known and a request does nothing, so the first full run of each process comes
  after the first attach pass, which asks for it. The flows of the user database's counters ask only when a counter
  really changes: Room reads them again on every write to `user_meta`, also when the value stays.
- A partial run after each batch of the tag pass writes the rows of the batch's files from the values of the last full
  run, so that after a rebuild or a deletion of the library database favorites and counts come back as the tag pass
  brings the keys back, not only when its run ends.
- Lists that need the fields join these tables: the Albums tab's filter (§15.2), and the smart lists Recently played and
  Most played (PIM-37), for which `LibraryLists.recentlyPlayed` and `mostPlayed` already give the visible songs with
  plays, the latest play or the most plays first.

### 15.5 Play statistics

- How a play is counted is in playback.md §11. A counted play writes one `play_event` row with the identity, the time
  the playback started (wall clock) and the time listened so far, and raises the change counter. When the playback ends
  its listened time is updated without raising the counter, because nothing of it is mirrored.
- A song's play count is its `play_stats.play_count` plus its events, its last played time the latest start of either.
- Events older than 90 days are folded into `play_stats` (count, listened time, latest start) by the first full mirror
  run of each process; the mirrored values do not change, so the counter does not go up.
- Plays of a file without an identity (its tags not read yet, or a file opened from another app) are not recorded.

### 15.6 Checks

JVM tests: the move, split, tie, merge, no-op and partial cases of §15.3 on tables of members and files, also a
majority that moved while the rest still names the old key and members on the old key that keep a new key from a
majority; a counter's flow asking only on a change; the counting of
playback.md §11 (half, 4 minutes, 30 s, pauses, seeks, a new playback, repeat-one, an unknown length, a resumed
playback); the listened time in the saved snapshot.

Instrumented tests on the API 30 emulator:

- `user.db` from version 3 to 4 with rows of version 3 (`MigrationTestHelper`), the counters copied.
- The mirror on the real databases: favorites, favorite albums, play counts and last played times written and cleared,
  copies of one key, a favorite that does not make the sync apply the rules again; the references of §15.3 through the
  real sync for a moved folder (move), two of three files moved with one left behind (move), a folder split in two
  (no move), two albums merged by one release ID (merge), an album left alone (no-op) and a cover move that fails
  (finished by the next run).
- Through the real library: favorites, favorite albums and play counts survive an index rebuild with new IDs and a
  deletion of the library database, coming back as the keys come back.
- The counting of playback.md §11 on a real player with a test clock, and the service's recorder writing a library
  song's play by its identity (opened files and songs without one not at all) and ending its writer when closed.
- On the screen: the toggle on Now Playing and in the song menu, on the album page, and the Favorites chip of the
  Albums tab.
