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
  shows that an earlier process synced it.

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
