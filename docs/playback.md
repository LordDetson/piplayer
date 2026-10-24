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
  step 4. Files opened from another app arrive through PiPlayer's own screen and the `OPEN` session command: M1 step 5.

### 1.5 Processes

- A process's role comes from its name: the package name is the main process; `:tagger` (M2a) runs native tag calls
  only; any other name is neither.
- Components of the main process check the role when they start and refuse to run elsewhere: the playback service
  now; WorkManager, the databases and DataStore when they arrive.

### 1.6 Debug entry

- Debug builds have "Identify a file" on the main screen: it picks an audio file with the system picker and runs the
  M0 identification spike on it. The old "Play a file" button is gone: files from other apps now arrive through
  open-with (A5), in debug and release builds alike.

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

## 3. Now Playing and mini player (M1 step 3)

### 3.1 Screens

- Two screens: the song list and Now Playing. While the queue holds a song, the list shows the mini player at its
  bottom; tapping the mini player opens Now Playing over the whole window. Back (the system button, the back gesture,
  or the collapse button at the top of Now Playing) returns to the list. Back on the list leaves the app with the
  system's back-to-home animation: PiPlayer handles back only while Now Playing is open.
- The screens are entries of a Navigation 3 back stack, which is kept across rotation and process death. Now Playing
  slides up when it opens and down when it closes.
- Predictive back: from Android 14 the back gesture moves Now Playing down with the finger and shows the list behind
  it; letting go returns to the list, cancelling keeps Now Playing. The manifest opts in with
  `enableOnBackInvokedCallback`, because targetSdk 36 makes predictive back the default only on Android 16. Android 13
  delivers back only once the gesture ends, and Android 11 and 12 have classic back.
- The song list is a screen of its own, so it reads the media index again each time Now Playing closes (§2.1); the
  library of M2a keeps its own index.
- Edge-to-edge: both screens draw behind the status and navigation bars and display cutouts. The list keeps its rows
  clear of the bars; the mini player's background reaches the bottom edge of the window while its content sits above
  the navigation bar. Now Playing keeps its content inside the safe drawing area, and the seek bar out of the side
  areas where the system takes the back gesture.
- One controller of the playback service serves the whole activity: the list, the mini player and Now Playing.
  Messages of the playback service (§2.4) show as a snackbar on either screen.

### 3.2 Mini player

- A bar with the cover thumbnail, the title and the artist (one line each) and a play/pause button. A thin line along
  its top edge shows how far the current song has played.
- It shows while the queue holds a song, also when playback is paused or stopped by an error, and hides when the queue
  is empty.
- Tapping the bar opens Now Playing; play/pause is a control of its own.

### 3.3 Now Playing

- From the top: a collapse button, the cover (square, as wide as the window allows), the title, the artist, the album,
  the format line (§3.5), the seek bar with the elapsed time on its left and the remaining time ("-m:ss") on its right,
  then shuffle, previous, play/pause, next and repeat.
- Title, artist and album are those of the queue item, that is of the media index (§2.3); the cover comes from the
  file (§3.4). No artist shows "Unknown artist", as in the list; no album shows nothing. An unknown length shows
  "--:--" and the seek bar cannot be moved.
- The seek bar follows playback smoothly. Dragging it shows the new position and seeks when the finger lifts.
- The content scrolls when it does not fit, in landscape or at large font scales; adaptive layouts come in M5.

### 3.4 Cover

- M1 shows the cover embedded in the file, as Media3 reads it: ID3 APIC frames in MP3 (and in raw AAC, where the
  cover appears only once playback starts), FLAC PICTURE blocks, `METADATA_BLOCK_PICTURE` comments in Ogg Vorbis and
  Opus, and `covr` in MP4. A front cover wins over other pictures; a file without a front cover shows its last picture.
  Covers in Matroska and WebM are not read. Folder images and the cover resolver come in M2a.
- Queue items still carry no artwork (§2.3): the cover reaches the screens and the system only through the metadata of
  the current song.
- The app decodes the cover once per song, off the main thread, scaled down to the shorter side of the screen at most.
  The mini player shows the same picture smaller.
- A song without a cover, or with one that cannot be decoded, shows a neutral placeholder with a music note. A new song
  never shows the previous song's cover: the placeholder stands in until its own cover is read.
- The system gets the current song's title, artist, album and cover through the session: the notification, the lock
  screen, the system media controls, Bluetooth devices that support cover art, watches and cars. Media3's own loader
  scales the cover to the platform's limit for them.

### 3.5 Format line

- What the player knows of the current song's audio track, joined by " · ": the codec; the bit depth for lossless
  formats, or the average bitrate for lossy ones; the sample rate; the channel layout when it is not stereo.
  Examples: "FLAC · 24 bit · 96 kHz", "MP3 · 320 kbit/s · 44.1 kHz", "AAC · 128 kbit/s · 44.1 kHz", "Opus · 48 kHz",
  "WAV · 32 bit float · 44.1 kHz · mono", "FLAC · 16 bit · 48 kHz · 5.1".
- Codec names: FLAC, ALAC, WAV (PCM), A-law and μ-law (in WAV, counted as lossy), MP3 (and MP1, MP2), AAC with HE-AAC and HE-AAC v2 told
  by their profile, Vorbis, Opus, AMR-NB, AMR-WB; any other codec shows its media type.
- The bit depth comes from the decoder's input format; FLAC depths that format cannot name (12 or 20 bit) come from
  the file's STREAMINFO. The bitrate is the average that the file states or Media3 works out (an MP3 header or its
  first frame, the sample table of an MP4, the nominal bitrate of Vorbis), rounded to whole kbit/s. Formats without one
  (Opus, raw AAC, FLAC) show none. Nothing in an MP3 tells constant from variable bitrate, so neither is named. The
  average counts the encoder's delay and padding in: a 2 s MP3 at 320 kbit/s shows 326, a song of minutes 320.
- Units and numbers follow the app's language ("44,1 кГц" in Russian).
- The line shows once the song's tracks are known and stays empty before.

### 3.6 Shuffle, repeat and previous

- Shuffle plays the current song first and every other song of the queue once, in random order, after it:
    - Turning shuffle on keeps the current song and its position and shuffles the others after it.
    - Tapping a song while shuffle is on starts the queue with that song, the others shuffled after it.
    - Turning shuffle off goes on in list order from the current song; turning it on again makes a new order.
- The order is the player's shuffle order. A new queue gets it from the order itself (Media3 passes the start song when
  a queue is set); for shuffle turned on in the middle of a queue, the player the session wraps sets the order before
  it turns shuffle on, so that the same rule holds whichever controller turns it on: the app, the notification, the
  system, Bluetooth or another app. Controllers that may read the timeline (§1.4) see the same order.
- Repeat goes off, all, one, and round again. With repeat all, shuffle replays the same order on every lap.
- Previous goes back to the start of the song after its first 3 seconds, otherwise to the previous song: the previous
  one of the shuffle order while shuffle is on. At the first song it restarts the song, or with repeat all goes to the
  last one. The notification, the lock screen, Bluetooth and headset buttons all reach the same rule.
- Turning shuffle on or off keeps the count of broken songs (§2.4); only a new queue starts a new count.
- Turning shuffle on in a 20 000-song queue reaches the controllers within 0.25 s (the median of 5 runs: 0.1 s on
  the API 30 emulator, 0.2 s on API 36). The API 37 emulator image with 16 KB pages takes about 0.3 s; there the time
  is only logged, as for the queue start (§2.5).

### 3.7 Notification and system controls

- The media notification and the system media controls show previous, play/pause and next, then shuffle and repeat.
  The icons of these two show the current mode, dimmed when it is off, and their names say it: "Shuffle off", "Shuffle
  on", "Repeat off", "Repeat all", "Repeat one". A tap turns shuffle on or off, or moves repeat to its next mode, and
  both buttons follow changes made anywhere else.
- Up to Android 12 the expanded notification shows all five and the compact one previous, play/pause and next. From
  Android 13 the system builds its controls from the session; shuffle and repeat take the last two places of the
  expanded player.
- The system drops notification updates that come faster than five a second, as they do when playback starts, so
  the service posts the notification again a second after shuffle or repeat changes: otherwise the buttons could keep
  the old mode.
- The platform session still publishes no queue (§2.3).

### 3.8 Accessibility

- Every control has a label, and the toggles a state: play/pause is "Play" or "Pause"; "Previous" and "Next";
  "Shuffle" with the state on or off; "Repeat" with the state off, all or one and the action "Change repeat mode"; the
  collapse button "Close Now Playing". The mini player reads its title and artist and offers "Open Now Playing" as its
  action; its play/pause button stays a control of its own.
- The seek bar is labelled "Playback position" and states the position in words ("1 minute, 2 seconds of 3 minutes, 45
  seconds"). The accessibility adjust actions move it by a twentieth of the song and seek.
- Covers are decorative and have no label. The mini player's progress line is hidden from accessibility services:
  inside the bar it would be read as a bare percentage.
- Now Playing announces itself as the pane "Now Playing", and its title is a heading.
- Every label exists in English and Russian.

### 3.9 Checks

JVM tests: the shuffle order (the start song first, every song once, next and previous as inverses, inserts, removes
and moves that keep a valid order, 20 000 songs built fast); the format line's parts for every format and the FLAC
depths from STREAMINFO; the repeat cycle.

Instrumented tests on the API 30, 36 and 37 emulators:

- The screens on a test player: Now Playing shows title, artist, album, format line, elapsed and remaining time; its
  controls call the player (play, pause, previous, next, shuffle, repeat round its cycle, a seek from the seek bar and
  from the accessibility adjust action); labels and states as in §3.8; the placeholder without a cover. The mini
  player shows title and artist, plays and pauses, opens Now Playing, and its progress line is hidden from
  accessibility services.
- Through the activity and the service: a tapped song brings up the mini player; it opens Now Playing; back returns to
  the list; a predictive back gesture in progress keeps Now Playing on screen and moved, cancelling keeps it, finishing
  returns to the list; back on the list is left to the system; the mini player's background reaches the bottom edge and
  its controls stay above the navigation bar; a cover from the file shows on Now Playing.
- Through the service: covers of MP3, FLAC, Ogg Vorbis, Opus and MP4 fixtures reach a controller, a front cover wins
  over a back cover, a file without one has none, and the platform session gets title, artist and cover; the format
  line of each decoding fixture; shuffle with the start song first and every song once, turned on in the middle of the
  queue (the current song first, its position kept), turned off (list order from the current song), and repeat all
  wrapping to the first song of the order; previous before and after 3 seconds; the notification's actions and the
  platform session's custom actions for shuffle and repeat, pressed from there and following changes from the app;
  shuffle keeps the count of broken songs; turning shuffle on in a 20 000-song queue, timed.

Not run on the owner's phone in this step (the owner's decision of 20.10). By hand on the phone later: the
notification, the lock screen and the system media controls with the shuffle and repeat
buttons (are the dimmed icons readable?), the cover there and on a Bluetooth device, the predictive back gesture, the
mini player above the gesture bar and the three-button bar, TalkBack on both screens (is the seek bar's state read
too often while it plays?), and a large cover (3000x3000) in the profiler.

## 4. Queue and position after process death

### 4.1 One state file

- The queue, the current song and its position, shuffle and repeat are kept in one Preferences DataStore file,
  `files/datastore/playback_state.preferences_pb`. One snapshot is written with one `edit`, so a reader never sees half
  of a snapshot.
- A snapshot holds the optional compact library request (A2.3); the small ordered list of files opened from other apps,
  each with its URI, title, artist, album, duration and final row; the current item (a media index ID, or the URI of an
  opened file), its row and position; shuffle, repeat; and the current item's title, artist, album, duration and URI.
  The current URI is also what the system's recent media controls show. A 20 000-song library queue still stores one
  short request, not 20 000 URIs or titles (A5.3).
- The schema is version 2. A version 1 file, which held only a library queue, is read as a library-only snapshot. A
  schema that a later version does not know is ignored instead of guessed. A file that cannot be read is replaced by an
  empty one: playback still starts, only the saved state is lost.
- The file is excluded from Android backup and device transfer: media index IDs and file URIs from one device mean
  nothing on another.

### 4.2 What is saved and when

- The library part of the queue is never written: its compact request is enough to read the list again from the media
  index. A 20 000-song queue saves one short request, not 20 000 URIs or titles.
- Files opened from other apps are a small list, so each is saved whole: URI, title, artist, album, duration and its
  final row among the library songs. They are written in the same snapshot as the library request, so their order
  survives a changed library. They are not added to the media index or to any library file.
- While playing, a snapshot is written every five seconds. It is also written at once when playback pauses or stops,
  when the song changes, when the position jumps (a seek, a restart after an error), when shuffle or repeat changes, and
  when the player's timeline or the compact queue request changes.
- Snapshots are built on the main thread, where the player lives, and written off it. A snapshot equal to the last one
  handed to the store is not written again, so a pause followed by a stop does not create two identical files.
- A queue with neither a compact request nor opened files is not saved: items that a controller or a test sets directly
  never replace the last snapshot.
- The service writes the current state once more before it is destroyed, after saving on a task swipe, so the last
  seconds of playback are not lost.

### 4.3 Which song and position

- The saved current item is found by its media index ID when it is a library song, or by its URI when it is an opened
  file. The saved position is applied. The row is not trusted for this: songs may have been added or removed, so the ID
  or URI is what is looked up.
- If the saved item is gone, the saved row selects the item now at that row, clamped to the end of the queue, and the
  position is zero. The saved position belonged to another file, so applying it would start the new item in the middle.
  An empty queue restores nothing.
- Opened files are put back at their saved rows. If the library shrank or grew, they keep their order and move to the
  nearest free rows, so they stay where the user heard them.
- Shuffle and repeat are restored with the item. The exact random order from before the process died is not saved: the
  order is generated again with the saved item first and every other item once after it (A3.6). Turning shuffle off
  after the restore continues in list order from the saved item.

### 4.4 Opening the app and resuming from the system

- Opening PiPlayer after process death shows the saved queue paused, at the saved item and position. The screen's
  controller sends the service a custom `RESTORE` session command after it connects. The service rebuilds the queue off
  the main thread, selects the saved start, prepares it, and stays paused. It calls pause before loading the items, so a
  leftover play-when-ready flag from the previous process cannot make the restored queue start by itself.
- Only PiPlayer's own screen may send `RESTORE`: a controller from another app and the media notification's controller
  cannot. A play request, a tapped song, a newer restore or an open from another app cancels a restore still on its
  way, so a slow restore never replaces what the user already started. If the player already has a queue, the command
  does nothing.
- Restored opened files whose temporary grant has expired are left out before the queue is built. The controllers get
  the same "files could not be opened" message as for a failed open. The rest of the queue is restored normally, and a
  file that opens but fails to decode is treated as a broken song (A2.4).
- A file opened from another app is inserted as A5.1 says. If a restore is still reading the library, the open
  supersedes it and the file starts at once; if the restore already showed the queue, the file goes right after its
  current item. The opened files are then the queue that the next launch restores.
- The system's recent media controls ask for the item to show before playback. The service gives them one item with the
  saved title, artist, album, duration and cover reference, not the queue. Its play button then asks for playback
  resumption: the service rebuilds the whole saved queue, selects the saved item and position, and lets Media3 start it.
- This is the path for the lock screen, the system media controls and a Bluetooth device's play button after Android
  killed the process. It does not publish the queue to the platform session (A2.3); only the restored current item is
  visible.

### 4.5 Headset and Bluetooth buttons after process death

- `androidx.media3.session.MediaButtonReceiver` is declared exported for `android.intent.action.MEDIA_BUTTON`. When no
  session is active after process death, the system sends a hardware key there instead of to the released session.
- On API 26 and later the receiver forwards only the first key-down of play, play/pause and the headset hook, through
  `startForegroundService`. Other keys are ignored there: a service started by a receiver must enter the foreground at
  once, and only a play command can make Media3 do that. Once the service is running, next, previous, play/pause and the
  default double tap of play/pause reach the session as usual (A3.6, A3.7).
- A forwarded play with no saved queue starts the service and then finds nothing to play. The start check of A4.6 stops
  it safely before the system's deadline instead of leaving a foreground service without a notification.
- The receiver is a manifest component only; the app does not register another receiver for media buttons.

### 4.6 Foreign starts of the service

- The playback service is exported because the system, Bluetooth and other controllers must reach it. Any app can also
  call `startForegroundService` on it. Android then expects a notification within five seconds and kills the process if
  there is none.
- A start is accepted as media when its action is `android.intent.action.MEDIA_BUTTON` with a key Media3 handles, when
  it carries Media3's custom notification action, or when it is Media3's own start with no action and its self-start
  extra. Media3 itself checks the value of that extra and stops a stale one.
- Anything else is foreign, and so is a media button without a handled key or with an extra of the wrong type. If the
  service is not in the foreground, it is stopped at once: the service posts a plain silent notification with the
  foreground flag, removes it, and stops itself for that start ID with `START_NOT_STICKY`. The process therefore ends
  normally instead of being killed for a missing notification.
- If the service is already in the foreground, a foreign start is passed to Media3 and otherwise ignored: playback
  must not stop because another app sent an unknown action.
- A legitimate media-button or Media3 self-start keeps the service only if playback is active or becomes active. Four
  seconds after the start, before the system's five-second deadline, a service that is still not in the foreground and
  has no active playback is stopped the same safe way. A notification action is sent with a plain `startService` and has
  no such deadline; it stops safely only if it finds no queue.

### 4.7 Checks

JVM tests: every snapshot key round-trips through Preferences, schema 1 becomes a library-only snapshot, broken and
malformed opened files are rejected, the saved song wins over the row and a missing one falls back to the row with
position zero, opened files are inserted and restored at their rows, and start intents are classified as media,
notification, self-start or foreign.

Instrumented tests on the API 30, 36 and 37 emulators:

- The state store reads back a snapshot, keeps the latest of two queued writes, clears it, reports a missing file as
  empty, and survives a new store on the same file.
- Through the service: the position is saved every five seconds while playing and at once on pause and seek; a song
  change and both modes are saved; a file set directly by a controller does not replace a saved library queue.
- Opening the app shows the saved queue paused at its song and position with repeat restored, and keeps it paused. A
  play request on an empty player rebuilds the queue and starts at the saved position. A missing saved song starts at
  its row without the old position.
- Headset next and previous move through the queue and a double tap of play/pause skips forward and keeps playing; the
  exported receiver is declared for the system's media button.
- A foreign action, a malformed media button and a play start with no saved queue stop before the system's deadline
  without killing the process; a foreign start while playing does not stop playback.

By hand on the phone later: open the app after a force stop and check the paused queue, press play from the lock screen
and a Bluetooth device, use the headset buttons after process death, and send a foreign start with `adb` while watching
that playback is unaffected.

## 5. Files opened from other apps

### 5.1 Opening and the queue

- PiPlayer advertises `ACTION_VIEW` for `content://` audio: `audio/*` and `application/ogg`, with the default
  category. It does not advertise `file://`, because another app could name any file PiPlayer can read, and it does not
  advertise `ACTION_SEND` or `ACTION_SEND_MULTIPLE`. Those actions are read only if an explicit intent carries one. A
  share target for playback is not part of M1; the v1.x share target stays for identification.
- The activity is `singleTop`: a file manager sends a second file to the running activity through `onNewIntent`.
- URIs come from `Intent.data`, `ClipData`, `EXTRA_STREAM` and an `EXTRA_STREAM` list. Their order is kept and
  duplicates are removed. Only `content://` URIs are played.
- Files are inserted right after the current item. The first opened file becomes current and starts playing, the other
  files follow in intent order, the old current stays immediately before them, and the rest of the old queue follows.
  If the player is empty, the opened files are the whole queue.
- Opening a file while PiPlayer is in the background brings the activity forward; playback does not wait for the screen.
  The activity sends the URIs to the service with the internal `OPEN` session command, which only PiPlayer's own screen
  may send (A1.4).
- A cold open-with launch starts the files at once. If the screen's restore of the saved queue is still reading the
  library, the open supersedes it; if the restore already showed the queue, the files go right after its current item.
  The queue is then saved as usual, so the next launch restores it.
- A file that cannot be read is skipped and counted; if none can be read, the command fails and the screen shows a
  message. An opened file is never added to the music library or to the media index.

### 5.2 Tags and cover

- The service reads title, artist, album and duration with `MediaMetadataRetriever` before inserting. A missing title
  falls back to the display name without its extension, then to the URI.
- The queue item carries those tags. ExoPlayer reads the embedded cover from the file, and the screen decodes it as for
  a library song. No artwork is stored in the snapshot.
- Reading tags blocks, so it happens off the main thread. A library queue still expands on its own thread, so opening
  files does not delay the 1 s start bound of A2.3.

### 5.3 Saving and restoring opened files

- The snapshot is schema 2. It stores the optional library request, the opened files with their final rows, and the
  current item. Schema 1, which had only a library queue, migrates to an empty opened list.
- Restoring puts opened files back at their saved rows. If the library changed, they keep their order and move to the
  nearest free rows. The current opened file is found by URI; a library current item by its media index ID.
- A temporary grant ends with the process. At restore, each opened URI is checked; files that cannot be opened are left
  out, and the controllers get the "files could not be opened" message. The rest of the queue is restored. A file that
  opens but fails to decode is treated as a broken song (A2.4).
- A provider that offers a persistable grant keeps the file readable after process death. PiPlayer takes such grants
  when files are opened and releases them when the files leave the queue. A provider that offers none leaves a
  temporary grant, which lasts only while the process does.
- A 20 000-song library queue still saves one compact request plus the few opened files; the opened list is not a
  second copy of the library.

### 5.4 What other apps cannot do

- Other apps cannot send `OPEN` or `RESTORE`. They may connect and control playback as A1.4 says, but the media
  notification's controller, which runs in PiPlayer's process, cannot send them either.
- Another app cannot make PiPlayer open a file with PiPlayer's permissions: `onAddMediaItems` still accepts only
  PiPlayer's own items.
- A `file://` URI is refused even when an explicit intent carries it, and the manifest does not match it.

### 5.5 Checks

JVM tests: opened files are inserted after the current item and keep their rows when the library changes; a pure opened
queue has no gaps; a changed library restores opened files near their saved rows; the current opened file is found by
URI; schema 2 and schema 1 snapshots round-trip; malformed opened files are rejected.

Instrumented tests on the API 30, 36 and 37 emulators:

- The open-with parser reads `data`, `ClipData`, `EXTRA_STREAM` and a stream list in order, removes duplicates and
  refuses `file://`.
- The manifest advertises `ACTION_VIEW` for `content://` audio and `application/ogg`, and not for `file://` or
  non-audio content.
- Through the service: files open right after the current song and the first one plays; several files keep their
  order; a pure opened queue starts playing; unreadable files are skipped and counted; only PiPlayer's own screen may
  send `OPEN`.
- Restore puts library songs and opened files back in order, starts at the current opened file, and rebuilds them for a
  system play request. An opened file whose URI is gone is left out with a message.
- An end-to-end open-with intent through `MainActivity` with a real `content://` file inserts it after the current
  song, saves the mixed queue, and restores the pure opened queue on a system play request.

By hand on the phone later: open a file from Files while a song plays, open several files at once, open one while
PiPlayer is in the background, force stop and reopen, and open a file whose temporary grant has expired.
