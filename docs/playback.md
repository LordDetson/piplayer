# Playback

How PiPlayer plays music: the playback service, its media session and the player inside it. This is the specification
of milestone M1 (features.md); each step of M1 adds its section. Facts about Media3 refer to version 1.11.1.

## 1. Service and session (M1 step 1)

### 1.1 Service

- One media library service (`PlaybackService`) in the main process holds one ExoPlayer and one media library
  session. The app, the notification, the lock screen, Bluetooth devices, watches and cars all control playback
  through this session.
- The manifest declares the service as exported, with the foreground service type `mediaPlayback` and the actions
  `androidx.media3.session.MediaLibraryService` and `android.media.browse.MediaBrowserService` (browsers that still use
  the platform protocol). The app asks for `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MEDIA_PLAYBACK`; `WAKE_LOCK`
  comes with Media3. It does not ask for `POST_NOTIFICATIONS`: media session notifications are exempt from it.
- Media3 runs the service in the foreground while the player plays, or buffers with play requested, and for up to 10
  minutes after a pause, an error or the end; after that the notification stays as an ordinary one.
- When the user swipes the app away from recents, playback goes on if it is playing, buffering or held by a short
  interruption such as a call (it then resumes when the interruption ends); otherwise playback pauses and the service
  stops once no other app is bound to it. Media3's own rule would also stop playback held by a call.
- Starts of the service that Media3 does not handle itself (an intent from another app that is neither a media button
  nor one of Media3's own) are made safe in step 4, together with media button starts and playback resumption.
- When the service is destroyed, it releases the session and then the player.

### 1.2 Player

- Audio attributes: usage media, content type music. The player handles audio focus itself:
    - Another player takes focus for good: playback pauses and does not resume by itself.
    - A short interruption (a transient loss: a phone call, including its ringing, or a voice assistant): playback is
      held and resumes when focus returns. A pause pressed during the interruption stays.
    - A short prompt that allows ducking (navigation, a notification sound): playback goes on at a lower volume. The
      system lowers it for prompts of other apps; for a prompt from PiPlayer's own process the player lowers it to 20%
      itself.
- Becoming noisy: when wired headphones are unplugged or a Bluetooth, USB or other external output disconnects while
  it carries the music, playback pauses (the platform's `ACTION_AUDIO_BECOMING_NOISY`).
- A partial wake lock is held while playing (including while held by a short interruption) and released on pause, at
  the end and on stop. Local files need no Wi-Fi lock.
- A file that cannot be decoded to its end (for example one cut off by an interrupted copy) plays its audio up to the
  damage. Then, depending on the device's decoder, the track ends there, or playback stops with a decoding error, and
  the audio already handed to the output (up to 0.75 s) is lost with it (the FLAC decoder of API 30 rejects a cut last
  frame; that of API 36 ends before it). From step 2 on, the queue moves on to the next track after such an error.
- FLAC frames reach the decoder whole. When STREAMINFO leaves the frame size unknown (piped encoders write it so),
  Media3 keeps the platform's default input buffer of 32 KB and drops every larger frame without an error; PiPlayer
  sizes the buffer for the largest frame the stream allows, a verbatim frame of its largest block (a side channel
  takes one bit more per sample).

### 1.3 Seeking

- MP3, by what the file offers, in this order: the table of an ID3 MLLT frame; the table of contents of a Xing or VBRI
  header; an index built while reading, when the Xing header has no table of contents or the file's length is
  unknown; otherwise the bitrate of the first frame (an Info header adds the exact length and the average bitrate).
  Constant-bitrate files without any header therefore seek exactly. Variable-bitrate files without any header, which
  encoders rarely write, get an estimated length and inexact seeks: Media3 1.9 and later build the index for them
  only when the length is unknown, and an exact map would need a scan of its own (`v1.x` if files like this turn up).
- Raw AAC (ADTS): seeking and the length are estimated from the average size of up to the first 1 000 frames.
- Raw AMR: seeking and the length follow from the size of the first frame, when the first 20 frames all have that size
  (one mode throughout). A file whose frame sizes vary (silence frames of a voice recorder, mode changes) cannot be
  seeked and has no length; reading it with an index would be `v1.x`.
- Without these settings, raw AAC and AMR files cannot be seeked at all and have no length.
- MP4 seeks through its sample table, Matroska and WebM through their cues (a file without cues, as browser
  recordings and live muxers write it, plays but cannot be seeked), FLAC through its seek table or else a binary search
  over its frames, Ogg by bisection over its pages, and WAV by its byte rate.
- Right after a seek that does not go through the index (a Xing table, a bitrate estimate), the MP3 decoder lacks the
  bit reservoir of the frames before, so the first one or two frames come out garbled (about 50 ms). Encoders use the
  reservoir at constant and variable bitrate alike. A seek through the index reads on from an earlier point and has no
  such burst. Whether it is audible is judged by ear on the phone; reading a few frames before the target would be
  `v1.x`.

### 1.4 Controllers

- Every controller may connect, browse and control playback. Media3 1.11 gives untrusted controllers (other apps that
  are not the system, hold no media control permission and have no enabled notification listener) only read access by
  default. PiPlayer grants every controller the default session and library commands and the player commands for
  playback: play and pause, seeking, skipping, the queue, shuffle and repeat, speed, the device volume. Commands that
  change how the player is set up (audio attributes and with them the handling of audio focus, the player's own volume,
  track selection, a video surface) stay with PiPlayer itself and the controllers Android trusts. A controller that
  names PiPlayer's package from another app is refused.
- Reading the library: the queue is the user's music library (§2.3), so only controllers that may read music anyway
  get the timeline and may ask for the song list: PiPlayer itself, the controllers Android trusts, and apps whose uid
  holds the audio permission (checked by uid, since a package name can be claimed). Other apps see the current song
  only. Without this, an app with no access to music
  could read the titles, artists and albums of the whole library through PiPlayer.
- What a controller may play: items from PiPlayer's own process may name a file by URI. Items from other apps may only
  name PiPlayer's own lists: the song list's compact request (§2.3) for controllers that may read the library, and
  library items by media ID once the library tree arrives (M2a). The media notification's controller runs in PiPlayer's process but
  never adds items; Media3 hands it to the session callback in place of a controller that says it is the system UI, so
  it counts as another app. Other apps never make PiPlayer open a file with PiPlayer's own permissions.
- Browsing: the root is an empty folder (media ID `root`) until M2a.
- The notification is Media3's, with PiPlayer's monochrome play icon and the channel "Playback" (ID `playback`);
  tapping it opens PiPlayer.
- Playback resumption from the system media controls and from headset buttons after the service has stopped: M1
  step 4.

### 1.5 Processes

- A process's role comes from its name: the package name is the main process; `:tagger` (M2a) runs native tag calls
  only; any other name is neither.
- Components of the main process check the role when they start and refuse to run elsewhere: the playback service
  now; WorkManager, the databases and DataStore when they arrive.

### 1.6 Debug entry

- Debug builds have "Play a file" on the main screen: it picks an audio file with the system picker and plays it
  through the session, the way the app will. It keeps a persisted read grant for the picked file (and gives back the
  previous one), because playback outlives the activity whose grant the picker issued. It plays files that the media
  index does not list, so it stays until open-with arrives (M1 step 5).

### 1.7 Checks

Instrumented tests on the API 30, 36 and 37 emulators:

- A controller plays a file from the start; playback goes on after the controller disconnects.
- Every format delivers all of a 2 s tone to the audio output, counted there (at most 0.1 s more, the padding some
  formats keep at their end): MP3 (also at 320 kbit/s), AAC in MP4, raw AAC, AMR narrowband and wideband (raw and in
  3GP), Ogg Vorbis, Opus (in Ogg and WebM), Vorbis in Matroska, FLAC (96 kHz 24-bit, 5.1, frames of 150 KB with no
  frame size in STREAMINFO) and WAV (16-bit, 24-bit, float, 11 025 Hz). A FLAC file cut off in its last frame plays
  up to the cut and ends (at least 1.9 of its 1.95 s), or stops with a decoding error (at least 1.1 s).
- While playing, the service runs in the foreground under the media notification, with PiPlayer's icon, channel and
  tap action. The service is exported, runs in the main process, and both actions find it.
- Audio focus: lost for good pauses without resuming; a short interruption holds playback, keeps the wake lock and
  resumes playback afterwards; a pause pressed during it stays; swiping the app away during it does not stop playback;
  a prompt that allows ducking keeps playback going, and from PiPlayer's own process lowers the output volume to 20%
  until it ends.
- The wake lock is held while playing and released after a pause.
- Seeks forward and back land in the right second, told by the tone that plays after the seek: MP3 with a Xing table,
  MP3 with a Xing header without a table, MP3 with constant bitrate and no header, raw AMR; raw AAC within one second.
  The test player buffers only about 1 s, so that every seek goes through the file's seek map: a seek inside the
  buffered audio is exact whatever the file offers. Lengths are checked too.
- PiPlayer and trusted controllers get every command; other apps get the playback commands without the setup ones; a
  controller naming PiPlayer's package from another app is refused; items that name files are refused from other apps
  and from the notification's controller, and items that are neither files nor compact requests (§2.3) are refused
  for everyone; the library root is an empty folder.
- The process role from process names and the FLAC frame bound (JVM tests).

The playback tests (without the UI ones) also run on the owner's phone. By hand on the phone: the notification and
lock screen controls, playback with the screen off, a call (playback holds and resumes when it ends), a Bluetooth
disconnect (and wired headphones, if available), a navigation prompt lowering the volume, and whether a seek in an MP3
is heard as a click. The becoming-noisy broadcast is protected, so emulator tests cannot send it.

## 2. Song list and queue (M1 step 2)

### 2.1 Temporary song list

- Until the library arrives (M2a), the main screen lists the songs of the platform's media index: the rows of
  `MediaStore.Audio.Media` on every attached external volume with `IS_MUSIC` set. The media index decides `IS_MUSIC` by
  folder alone: files under Ringtones, Notifications, Alarms, Podcasts, Audiobooks and Recordings (on Android 11 only
  once its media module is updated) are left out, a path with a Music folder counts even inside those, and everything
  else counts, voice messages in other folders included. Hidden folders and folders with `.nomedia` are not indexed, the
  songs of an unmounted SD card disappear, and audio-only WebM, Matroska video and 3GP files are filed as video by the
  index and are not listed (M2a's own sync will find them).
- A row shows the title, the artist and the length. The index gives the file name without its extension as the title
  when the file has no title tag, `<unknown>` as the artist (shown as "Unknown artist"), the folder's name as the
  album, and no length, or 0, for files it could not read (shown as "--:--"); such rows stay in the list.
- Order: by the title normalized as for search (TI §4.1: case, diacritics outside Cyrillic and "ё" folded, punctuation
  as spaces), compared character by character (digits before Latin before Cyrillic), then by the index's ID, so that
  the list and the playback service always agree.
- The list is read again whenever the screen starts, when access is granted, and, while the screen is started, a
  second after the media index reports a change (once for a burst of changes). The list held in memory is shared by the screen and the playback service, which run in one process, so that
  a tap does not read 20 000 rows a second time.

### 2.2 Permission

- The app asks for `READ_MEDIA_AUDIO` (API 33 and later) and `READ_EXTERNAL_STORAGE` with `maxSdkVersion` 32 (API 30
  to 32). Audio has no partial access.
- Without the permission, the list shows a short explanation and an "Allow access" button that brings up the system
  dialog. After a second denial the system no longer shows the dialog; the card then says that access is turned off
  and offers "Open settings" (the app's details screen) and "Try again". A dialog closed without an answer is no
  denial: the system explains why it asks only after a real denial, and PiPlayer remembers across launches that it
  did. The permission is checked again whenever the screen resumes, because a grant in the settings does not restart
  the app. The app never asks on its own, only on a tap.

### 2.3 Compact queue request

- Tapping a song plays the whole list from that song. The screen sends one media item whose media ID names the list,
  its order and the tapped song: `q1/songs?sort=title&start=<ID>&index=<row>`; it also carries the song's title and
  artist, so that the screen shows them at once. The UI never builds thousands of media items.
- The playback service expands the request in `onSetMediaItems`, on a background thread: it takes the shared list (or
  reads it, if the service runs without the screen), builds one media item per song (media ID, content URI, title,
  artist, album, length, music type; no artwork and no request metadata, which would be published to other apps) and
  starts at the tapped song. If the song is gone, it starts at the same row, or the last one. A request whose list is
  empty, or that cannot be read, is refused.
- Only controllers that may read the library (§1.4) may send such a request; others are refused, because the queue
  it builds would show them the library. Every other set request follows §1.4 (files only from PiPlayer's own
  process).
- The whole queue is one player playlist (gapless, §1). The platform session (the notification, the lock screen,
  Bluetooth, cars) is not given the queue: the controller Media3 connects for the media notification gets every
  command but `GET_TIMELINE`, so the platform sees the current song, its controls and its position, but no list of
  what comes next. Media3 would otherwise publish all 20 000 items at every track change, and Bluetooth reads them
  again at every play and pause. A window of neighbouring songs can come later (`v1.x`).
- A 20 000-song queue starts within about 1 s of the tap. Every queue item is a local file, so the player makes a
  progressive media source for it directly: Media3's default factory would first work out the kind of stream of each
  item on the main thread (a median of 1.15 s against 0.73 s for 20 000 songs on the API 30 emulator).

### 2.4 Broken files

- When a song cannot be played, playback moves on to the next one: a missing or unreadable file, a format no extractor
  recognizes or a malformed one, a decoder that fails to start or to decode, and a song with no audio track that can
  be played (no decoder for its codec, or an image with an audio name), which Media3 would otherwise play as silence.
- Problems of the device rather than the file (the audio output fails, the system takes the decoder back, the decoder
  list cannot be read, the player stops making progress) stop playback at once. A failed check inside the player counts
  as the song's: an odd file is the likelier cause. So does an audio output that cannot be set up for the file's format
  (9 channels, a rate the platform rejects), which Media3 reports under the same code as an output that fails to
  start; the cause tells them apart.
- Failures in a row are counted; a song that opens (the player gets ready) resets the count. After 5 in a row, or as
  many as the queue has songs if it has fewer, playback stops, and so it does when the failing song is the last one.
  When playback stops, the player keeps the last error and play is paused (a song with no audio leaves no error; the
  player is stopped instead, so that play prepares it, and checks it, again), and the service sends a message to its
  controllers (a non-fatal session error), which the screen shows. Pressing play again retries the song with a new
  count, so playback moves on from it if it fails again.

### 2.5 Checks

JVM tests: the compact request's format; the order of titles; finding the start song; which errors skip, which stop,
and the count, including an audio output that rejects the file's format; when access counts as denied for good; a
slow older read of the list does not replace a newer one.

Instrumented tests on the API 30, 36 and 37 emulators, the playback ones also on the owner's phone:

- The media index source on files the test inserts into the index itself (no permission needed for own files): music
  under Music is listed with its tags, length and a playable URI; a file under Ringtones is not.
- The session callback: a compact request expands into the whole list starting at the tapped song, with the songs'
  metadata; a missing start song falls back to its row and makes the list be read again, a known one does not; a list
  that cannot be read is refused; the system and apps whose uid may read music may send compact requests, apps
  without access to music (also under the name of one that has it) and the notification's controller may not; those
  have no `GET_TIMELINE`.
- Through the service: a tapped song plays and the next one follows; the platform session shows the song but no
  queue; queues with missing files, text files named like MP3 and an image named like MP3 skip them; 5 broken songs in
  a row, a broken last song, an image as the last song and an all-broken queue with repeat stop with the message; play
  after a stop retries with a new count and moves on; a song that plays between broken ones resets the count; a
  failing audio output stops at once (on a player of the test's own).
- A 20 000-song queue from a synthetic list starts within 1 s (the median of 5 runs after one warm-up run) on the phone
  and the API 30 and 36 emulators. The devices run one at a time, since the start is bound by the main thread. The API
  37 emulator image with 16 KB pages is slower throughout (1.3 to 1.9 s against 0.57 s on the phone); there the time
  is only logged.
- The screen: the permission card, the denied-for-good card, the list in title order, a tap that starts playback at
  that row, and a change of the media index that makes the list be read again.

By hand on the phone: the first-use dialog, the list of the owner's own music, the notification and Bluetooth with a
large queue, and a song from an SD card if there is one.
