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
  (§1.3). Its schema is not exported, as nothing outside the app reads it.
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
    - The title is the index's title, or the display name without its extension when the index has none.
    - The artist is empty when the index gives none or its placeholder `<unknown>`; the album and the length are empty
      when the index gives none (a length of 0 counts as none).
    - The title key is the title normalized as for search (TI §4.1).
    - The search text joins the normalized title, artist, album, composer, genre and display name without its extension
      with spaces (TI §10.1), leaving out empty values. Search itself arrives in step 12; the column and its index exist now, so that
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
  as `INSERT OR REPLACE` of up to 2 000 rows per statement, each statement taking its rows as one JSON parameter that
  `json_each` unpacks; a changed file is written again under its row ID, a new one takes the next free ID.
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
  as in M1 (playback.md §2.1). A song's ID stays the index's ID and its URI the index's content URI, so queue
  requests, the saved queue and the session's media IDs do not change.
- Order: by the title key, compared as Unicode code points, then by the index's ID. The database sorts; the list
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
      owner's phone and 5 s on the API 30 emulator;
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
- Later tables (track identities, favorites, playlists, statistics, decisions) arrive with their milestones.

### 3.2 Settings files

- Settings live in DataStore in the main process, in two files (TI §10):
    - `settings`, backed up: the theme, the hidden-file choices (§3.5) and the sleep timer's last choice;
    - `device_state`, not backed up: "stop after current track" (playback.md §6.4) and whether the audio permission was
      denied before (playback.md §2.2).
- The three SharedPreferences files of M1 (`player`, `playback_controls`, `songs`) move into them under the same keys
  the first time each file is read, and are deleted afterwards.

### 3.3 Backup

- Both rule files (`fullBackupContent` for Android 11, `dataExtractionRules` for cloud backup and device transfer on
  Android 12 and later) list what is backed up: `user.db` with its WAL file and the `settings` file. Everything else
  stays out: the library database, `device_state`, the saved queue, crash reports and caches (TI §10.3).

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
  database's change counter it last mirrored (TI §10.3); a sync that finds another counter applies the rules to every
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
  settings and Library folders) and Appearance (the theme). Other sections arrive with their milestones.
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
- Settings that will affect reading (legacy encoding, from step 5) travel as call parameters, because DataStore
  exists only in the main process.

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
