# Playback

How PiPlayer plays music: the playback service, its media session and the player inside it. This is the specification
of milestone M1 (features.md); each step of M1 adds its section. Facts about Media3 refer to version 1.12.0-alpha02,
the version PiPlayer builds with; sections 1 to 7 were written on 1.11.1 and their checks pass on it and on the
1.12.0 alphas.

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
  library items by media ID from the library tree (§10). The media notification's controller runs in PiPlayer's process but
  never adds items; Media3 hands it to the session callback in place of a controller that says it is the system UI, so
  it counts as another app. Other apps never make PiPlayer open a file with PiPlayer's own permissions.
- Browsing: the library tree of §10 (since M2a step 10; until then the root was an empty folder).
- The notification is Media3's, with PiPlayer's monochrome play icon and the channel "Playback" (ID `playback`);
  tapping it opens PiPlayer.
- Playback resumption from the system media controls and from headset buttons after the service has stopped: M1
  step 4. Files opened from another app arrive through PiPlayer's own screen and the `OPEN` session command: M1 step 5.

### 1.5 Processes

- A process's role comes from its name: the package name is the main process; `:tagger` (M2a, library.md §4) runs
  native tag calls only, and TagLib refuses to read anywhere else; any other name is neither.
- Components of the main process check the role when they start and refuse to run elsewhere: the playback service,
  the library database and its sync, and DataStore (library.md §1.5); WorkManager when it arrives.
- Crash capture (§7) is the exception: it runs in every process of the app, so that a crash anywhere leaves a report.

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
  for everyone. The library tree has its own checks (§10.7).
- The process role from process names and the FLAC frame bound (JVM tests).

The playback tests (without the UI ones) also run on the owner's phone. By hand on the phone: the notification and
lock screen controls, playback with the screen off, a call (playback holds and resumes when it ends), a Bluetooth
disconnect (and wired headphones, if available), a navigation prompt lowering the volume, and whether a seek in an MP3
is heard as a click. The becoming-noisy broadcast is protected, so emulator tests cannot send it.

## 2. Song list and queue (M1 step 2)

### 2.1 Song list

- The main screen lists the songs of the platform's media index: the rows of `MediaStore.Audio.Media` on every attached
  external volume with `IS_MUSIC` set. Since M2a they come from the library database, which a sync keeps up to date
  with the index (library.md §1). The media index decides `IS_MUSIC` by folder alone: files under Ringtones,
  Notifications, Alarms, Podcasts, Audiobooks and Recordings (on Android 11 only once its media module is updated) are
  left out, a path with a Music folder counts even inside those, and everything else counts, voice messages in other
  folders included. Hidden folders and folders with `.nomedia` are not indexed, the songs of an unmounted SD card
  disappear, and audio-only WebM, Matroska video and 3GP files are filed as video by the index and are not listed.
- A row shows the title, the artist and the length. The index gives the file name without its extension as the title
  when the file has no title tag, `<unknown>` as the artist (shown as "Unknown artist"), the folder's name as the
  album, and no length, or 0, for files it could not read (shown as "--:--"); such rows stay in the list.
- Order: by the title normalized as for search (TI §4.1: case, diacritics outside Cyrillic, letters such as "ø" and
  "ё" folded, punctuation
  as spaces), compared by code point (digits before Latin before Cyrillic), then by the index's ID, so that the list
  and the playback service always agree.
- The list is read from the library when the screen starts and after every sync that changed it (library.md §1.4).
  The list held in memory is shared by the screen and the playback service, which run in one process, so that a tap
  does not read 20 000 rows a second time.

### 2.2 Permission

- The app asks for `READ_MEDIA_AUDIO` (API 33 and later) and `READ_EXTERNAL_STORAGE` with `maxSdkVersion` 32 (API 30
  to 32). Audio has no partial access.
- Without the permission, the list shows a short explanation and an "Allow access" button that brings up the system
  dialog. After a second denial the system no longer shows the dialog; the card then says that access is turned off
  and offers "Open settings" (the app's details screen) and "Try again". A dialog closed without an answer is no
  denial: the system explains why it asks only after a real denial, and PiPlayer remembers across launches that it
  did. The permission is checked again whenever the screen resumes, because a grant in the settings does not restart
  the app. The app never asks on its own, only on a tap: on the first launch the tap is onboarding's "Allow"
  (library.md §14.1).

### 2.3 Compact queue request

- Tapping a song plays the whole list from that song. The screen sends one media item whose media ID names the list,
  its order and the tapped song; it also carries the song's title and artist, so that the screen shows them at once.
  The UI never builds thousands of media items. M1 had one form, `q1/songs?sort=title&start=<ID>&index=<row>`, which is
  still read; since M2a step 6 a request names any list of the library with its sort, reverse and search
  (`q2/...`, library.md §6.4).
- The playback service expands the request in `onSetMediaItems`, on a background thread: it takes the shared list of
  the request's list (or reads it, if the service runs without the screen or no longer keeps it), builds one media item per song (media ID, content URI, title,
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
- The platform session takes the commands of the notification's controller, which Media3 connects only once the
  session is added to the service; until then it has all of them. So the service adds its session as soon as it builds
  it, rather than when a controller first asks for it, and the platform session never holds the queue, not even for a
  moment (Bluetooth on Android 11 can crash on a queue that is there one moment and gone the next).
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

### 2.6 What the session shows

- The session's player shows the queue item's metadata and nothing of what the extractor finds in the stream, so that
  fields PiPlayer leaves empty show nothing, not raw tags (TI §7.1). It stays a `ForwardingPlayer` that filters both the
  value it gives and what its listeners hear, because the session keeps what it hears. The stream's cover stays until the
  cover resolver of M2a step 13 takes over; before that every cover would vanish.
- While the tag pass lands its batches (library.md §5), the playing item and the next one come up to date in place, each
  when its values differ, with the same URI: playback does not prepare again. The next item after a transition catches up
  at that transition. The other items of a queue wait, because each change costs every controller a new timeline.

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
- A 20 000-song queue from a synthetic list starts within 1 s (the median of 5 runs after one warm-up run) on the
  phone (0.57 s). The devices run one at a time, since the start is bound by the main thread. The emulators only log
  the time: the API 30 image sits at the bound even on a quiet host (0.8 to 1.2 s), and the API 37 image with 16 KB
  pages is slower throughout (1.3 to 1.9 s).
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
- The song list is a screen of its own, so it reads the library again each time Now Playing closes (§2.1,
  library.md §1.4).
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
- Songs added to the queue keep their place in the order, and the up-next run stays next when shuffle is turned on
  (§9.3).
- Repeat goes off, all, one, and round again. With repeat all, shuffle replays the same order on every lap.
- Previous goes back to the start of the song after its first 3 seconds, otherwise to the previous song: the previous
  one of the shuffle order while shuffle is on. At the first song it restarts the song, or with repeat all goes to the
  last one. The notification, the lock screen, Bluetooth and headset buttons all reach the same rule.
- Turning shuffle on or off keeps the count of broken songs (§2.4); only a new queue starts a new count.
- Turning shuffle on in a 20 000-song queue reaches the controllers within 0.25 s (the median of 5 runs: 0.1 s on
  the API 30 emulator, 0.2 s on API 36). The API 37 emulator image with 16 KB pages takes about 0.3 s; there the time
  is only logged.

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
- When the process is killed while playing, the system starts the service again on its own, and a later key reaches
  that running session instead of the receiver. Playback then resumes the same way, in the foreground under its
  notification, and the platform session gets no queue (§2.3).
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
- The platform session publishes no queue (read from `dumpsys media_session`) when a queue starts from the app, when a
  media button resumes it through the receiver, and when the key reaches a service that runs without anything having
  asked Media3 for its session, as after the system restarts it; that playback runs in the foreground.

By hand on the phone later: open the app after a force stop and check the paused queue, press play from the lock screen
and a Bluetooth device, use the headset buttons after process death, and send a foreign start with `adb` while watching
that playback is unaffected.

### 4.8 Schema 3: identities, the queue file and the shuffle order (M2a step 8)

Schema 3 replaces what §4.1 to §4.3 say about media index IDs, the saved shuffle order and where the queue lives.

- **Two files.** `playback_state` keeps what changes while playing: the current item, its row and position, shuffle,
  repeat, what the system shows before the queue is read (title, artist, album, duration, URI), and the revision of
  the queue file it belongs to. It is still written every five seconds while playing and at once on the events of
  §4.2. The structure of the queue lives in `queue.bin` under the app's no-backup files and is written only when the
  structure changes: a new queue, an open from another app, shuffle turned on or off, an edit (step 9). It is written
  to a new file that then replaces the old one, so a reader never sees half of it.
- **The queue file** (format 2 since step 9; format 1 is still read) holds its revision, the library ID (library.md §8.4),
  the entries added by edits, the edit journal, the up-next run (§9.4) and, while shuffle is on, the play order. The compact request and the opened files at their rows stay in
  `playback_state`, as in schema 2, because they are small.
  An entry of the queue is named by a key: a library song by its file ID, an opened file by its place in the opened
  list, an added entry by a number that the queue file hands out once.
- **The current item** is saved as a track reference, the form of a playlist entry (TI §10.2): its file ID, its
  identity when it has one, its content key, its place (volume, relative path, display name) and its length. A
  restore finds it by the file ID when the library ID is the same and that file still has the place, otherwise by the
  identity's current key, then by the saved key, then by the place with a length within 1 s; among several files with
  the key, the one at the saved place wins. Only then does the saved row count, with position zero, as in §4.3. So the
  position survives a moved or renamed album, an index rebuild and a library rebuild, also after a new app version
  whose library database starts empty, as long as the file is still at its place or its key is known again.
- **The shuffle order** is saved exactly: the keys of the queue's entries in play order, those already played
  included. A restore gives the player the same order. When the library changed meanwhile, the entries that are still
  there keep their order, the gone ones leave, and new ones (the compact request is read again, §4.2) go to random
  places after the current item, so they are still to come. When the library ID differs, file IDs mean nothing and
  the order is generated again with the current item first, as before. A current song that moved and has another file
  ID now keeps its place in the order. The library ID saved is the one the songs of the queue were read under, so a queue
  that outlived a rebuild with new IDs is not taken for a queue of the new library.
- **The edit journal** (filled since step 9, §9) lists the edits in the order they were made: insert entries after an entry,
  remove an entry, move an entry after another. Each edit names entries by their keys, not by rows, and keeps the row
  it was made at as a fallback, so it still applies when the compact request reads a changed list: an insert whose
  entry is gone goes to its row, a removal or a move of an entry that is gone does nothing. Added entries carry their
  track references, so they survive a library rebuild like the current item. A journal of more than 200 edits
  is folded at the next restore (§9.4):
  the queue file then holds every library entry as an added entry, the opened files stay at their rows in
  `playback_state`, which holds no compact request any more, and the play order names the same entries under their new
  keys. From then on the queue is a fixed list that no longer follows the library.
- **Schema 2** files are still read: their compact request's start ID and the saved song ID are media index IDs, which
  a restore turns into file IDs through `audio_file.media_store_id`; the order is generated again. Schema 2 and 1
  files are rewritten as schema 3 at the next save. The queue file is written before `playback_state`, which names its
  revision; a queue file of another revision (the process died between the two writes), a damaged one or one of a
  format it does not know is ignored, and the queue starts from the snapshot's request and opened files as in schema 2.
- **Checks.** JVM tests: the queue file round trip and a damaged or unknown file; the track reference resolution in
  its order; the shuffle order restored exactly, with gone entries left out and new ones only after the current item;
  a library ID that differs; the journal: inserts, removals and moves on an unchanged and on a changed list, a missing
  anchor falling back to the row, folding after 200 edits; schema 2 to 3. Instrumented tests on the API 30 emulator,
  through the service: a shuffled queue is restored in the same order after the process is gone; the current song is
  restored after its file moved to another folder, after an index rebuild with new IDs and after a library rebuild; a
  20 000-song queue still starts within 1 s on the phone (§2.3).

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

## 6. Sleep timer, stop after current, effects (M1 step 6)

### 6.1 Buttons

- Now Playing has a second row under its controls: the sleep timer, "Stop after current track" and the Equalizer.
  The timer button shows the time left next to its icon while a timer runs. The Equalizer button shows only when the
  device has an effect panel; PiPlayer checks again whenever the screen resumes, since a panel app can come and go.

### 6.2 Setting the sleep timer

- The timer button opens a sheet: two wheels, hours (0 to 23) and minutes (0 to 59), that turn round in both
  directions; under them ready-made times as chips, from the longest to the shortest: 1:30, 1:00, 0:45, 0:30, 0:15. A
  chip sets the wheels to its time; the chip that matches the wheels is outlined. There are no seconds.
- A checkbox "Finish the current track" and a Start button, which is disabled at 0:00.
- While a timer runs, the sheet shows the time left instead of the wheels, whether it finishes the track, and a button
  that cancels it. Cancelling keeps the sheet open with the wheels, so that a new timer can be set at once; starting
  one closes the sheet. Starting a timer replaces the running one.
- The sheet keeps its last choice, hours, minutes and the checkbox, in the app's settings as soon as it changes,
  whether or not a timer was started, so that the next evening's timer is one tap away, also after the app was closed.
  The first time it shows 0:30 without the checkbox.

### 6.3 What the timer does

- The timer counts real time, also while playback is paused, and lives in the playback service: closing the screen
  does not stop it. It is lost when the process dies, and with it the playback it was meant to stop.
- Without "Finish the current track": the volume falls over the last 10 s before the time is up, smoothly in steps
  of the ear (from the player's own volume down to -50 dB), and at the time playback pauses. The queue, the song and its
  position stay, and the player's volume comes back at once, so the next play starts at full volume. The fade uses the
  player's own volume, never the device volume, and multiplies with ducking for prompts.
- With "Finish the current track": when the time is up while music plays, the song that is then playing plays to its
  end, without a fade, and playback stops on the next song, as "stop after current track" does (§6.4). A song skipped
  to by hand in the meantime is the one that finishes.
- A pause while the volume falls or while the song is being finished (by the user, a disconnected output, another
  player taking audio focus) ends the timer and restores the volume. Skipping songs does not. A call holds playback and
  so lets the timer run out, which is meant at bedtime. A timer that is up while nothing plays just ends.
- While the music plays, the player's wake lock keeps the timer on time with the screen off and the device dozing.

### 6.4 Stop after current track

- A toggle: when the song that plays ends, playback stops and moves to the next song without playing it, paused at its
  start, so that play starts that song. The next song is the one repeat and shuffle would play: the next of the list or
  of the shuffle order, the first again with repeat all at the last song, the same song from its start with repeat one.
  After the last song with repeat off there is none, and playback stays at the end of the last song.
- Turned on, it is kept in the app's settings until it has stopped playback or is turned off: it outlasts a closed
  app and a killed process, and applies to the queue restored afterwards (§4). Like the saved queue, the setting stays
  out of backups and transfers to another device.
- It turns itself off once it has stopped playback, or when the user turns it off. Changing the song by hand (a skip,
  previous to another song, a new queue, an opened file, a broken song skipped) keeps it on: it then stops after the
  song that plays.
- It only turns on while there is a current song.

### 6.5 Who may set them

- Both are commands of PiPlayer's own screen (`SLEEP_TIMER`, `STOP_AFTER_CURRENT`), refused for other apps and for the
  media notification's controller, like `RESTORE` and `OPEN` (§4.4, §5.4). Their state goes as session extras to
  PiPlayer's own controllers only, never into the platform session that other apps read.
- The timer is not saved; "stop after current track" is saved on its own, apart from the queue (§6.4).

### 6.6 Effects

- Media3 does not announce the audio session to effect apps itself. The service announces PiPlayer's session
  (`ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION` with the session ID, PiPlayer's package and the music content type) once
  the player has its session ID, and again whenever playback starts, so that an effect app that was restarted takes
  the session back. It closes the session (`ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION`) when the service is destroyed,
  not on pause, so that effects are there at once on play. The broadcasts are implicit; Android lets them through to
  effect apps in the background.
- Effects belong to the session, not to a song, so they stay across songs, gapless joins and format changes. Offload
  stays off. A multichannel file played on an external output without a mixer (HDMI, some USB) may play without them.
- The Equalizer button opens the device's panel (`ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL`) for PiPlayer's session,
  started for a result as the panel requires. The manifest declares the action under `<queries>`, which PiPlayer needs
  to see whether a panel exists. On the owner's S24 Ultra the panel is Samsung's SoundAlive, and no app there declares a
  receiver for the session announcements (Samsung's own effects presumably apply to the whole output; to be heard on
  the phone). The emulators have AOSP's MusicFX, which opens PiPlayer's session on the announcement.

### 6.7 Accessibility

- The timer button is labelled "Sleep timer" and states the time left; the toggle "Stop after current track" with the
  state on or off; the Equalizer button "Equalizer". Each wheel is labelled "Hours" or "Minutes", states its value
  ("1 hour", "30 minutes") and can be adjusted by accessibility services; each chip reads its time in words.

### 6.8 Checks

JVM tests: the fade curve; the timer's rules on a fake clock (no change before the fade, the fade to the deadline, a
late tick after deep sleep, a pause or cancel during the fade, finish-track mode, a timer that is up while paused); when
"stop after current" turns itself off; the chips' order and matching.

Instrumented tests on the API 30, 36 and 37 emulators:

- The sheet and buttons on a test player: wheels, chips longest first, the outlined chip, Start disabled at 0:00, a
  start sends the time and the checkbox, the running timer with its time left and cancel, the toggle, the Equalizer
  button only with a panel; labels and states.
- Through the service: a short timer fades and pauses at its time with the volume restored and the queue and position
  kept; a pause during the fade ends the timer; finish-track mode stops at the song's end on the next song; stop after current stops
  at the end of the song on the next song at its start, turns itself off, and play goes on from there; a skip keeps it
  and it stops after the song skipped to; repeat one stops on the same song; the last song stays at its end; the commands are
  refused for other apps and the notification's controller; the state reaches only PiPlayer's controllers.
- The session is announced with PiPlayer's package and session ID and closed when the service is destroyed; the
  Equalizer button starts the panel intent for PiPlayer's session (intercepted).

By hand on the phone: a 5-minute timer with the screen off, the fade by ear (also during a navigation prompt and over
Bluetooth), stop after current, and the Equalizer button opening SoundAlive.

## 7. Crash reports (M1 step 7)

PiPlayer keeps its own crash reports on the device and offers them for sharing on the next start. It has no crash
reporting service and sends nothing by itself: a report leaves the device only when the user shares it.

### 7.1 What is caught

- Kotlin and Java crashes, in every process of the app: PiPlayer's own handler writes the report in the crashing
  process, then hands the crash on to Android's handler, so that Android still records it and ends the process as it
  would. The handler is installed first thing in the application object, before anything else of the process runs.
- Freezes (ANRs) and native crashes (TagLib, Chromaprint, the platform's codecs), which kill the process before any
  code of the app can react: Android keeps a record of how each process ended (`ApplicationExitInfo`), and the main
  process reads it at its next start. An ANR record carries the threads' stacks as text. A native crash carries a
  tombstone from Android 12 on, which PiPlayer turns into text: the signal, the cause, the abort message and every
  thread's backtrace with file, function, offset and build ID, laid out as Android's own crash dumps are, so that the
  usual tools can symbolize it. Android 11 keeps no tombstone for apps, so there the report holds the record alone.
- Other ways a process ends (the system reclaiming memory, the user stopping the app, an update) are not crashes and
  make no report.
- Each record is reported once: the reports remember which records they have seen. Records from before the first
  start of a build with crash reports are not reported. A Java crash that the handler has written is not reported a
  second time from its record; the record's details are added to the report instead.

### 7.2 Reports

- A report is plain text: the app's version and build, the device, Android's version and build, the process, the
  time, the kind (crash, native crash, ANR), then the stack trace (with its causes) or the trace. The text stays as it
  is: messages may name files and songs, which is why the user sees the whole report before sharing it.
- An ANR trace is kept up to 4 MB, and the report says where it was cut; a tombstone larger than 8 MB is not read. A
  trace that cannot be read leaves a report with the record and the reason.
- The newest 5 reports are kept in the app's private storage; older ones are deleted. They stay out of backups and
  transfers to another device.

### 7.3 On the next start

- When there are reports the user has not seen, the app shows a dialog: "PiPlayer stopped unexpectedly last time",
  with the kind and time (or how many reports there are), and Share, Show report and Close. It appears once for those
  reports, whatever the user chooses.
- Several new reports are offered together as one text, newest first.
- Show report opens the full text on a screen of its own, selectable, with Share and Copy.
- Share opens the system share sheet with the report as a text file and, up to 32 000 characters, as text too (longer
  text is cut at a line and says so). The share sheet has no Copy button once a file is attached; the report screen's
  Copy puts up to 100 000 characters on the clipboard.

### 7.4 Checks

JVM tests: the handler writes a complete report and then hands the crash on exactly once, also when it cannot write,
and ignores a crash within the crash; the report text; which exit records make a report (kinds, seen records, the
first start, a Java crash already written); turning records into reports (a decoded tombstone, an unreadable one, an
ANR trace cut at its limit, a trace that cannot be opened, Android's record added to the handler's report); keeping
the newest 5; the tombstone decoder on hand-made tombstones; cutting the shared text; the backup exclusions.

Instrumented tests on the API 30, 36 and 37 emulators: the installed handler is in front of Android's (in the tests,
in front of the test runner's, which hands on to Android's); the dialog appears once
for new reports, and Close, Show report and Share work; the share intent carries the text and a readable file; the
report screen scrolls to the end of a long report.

End to end, run by hand with `-e crashTests true` (they crash test processes, which Android counts against the app if
repeated within minutes): a Java crash and a native crash in debug-only processes give one report each, with Android's
record; on API 31 and later the native report has the decoded tombstone, with the signal and the backtrace.

By hand on the phone: the dialog after the end-to-end crashes, and sharing a report to email, Telegram and Files.

## 8. Gapless playback (M1 step 8)

Songs of an album that run into each other play as one stream: no silence, no repeated or lost audio where one song
ends and the next begins.

### 8.1 How

- Encoders add silence: a delay at the start of a song and padding to fill its last frame. The player trims both by what
  the file says, and keeps one audio output across songs of the same sample rate and channel layout, so the joined
  audio is the original to the sample. Offload stays off (§6.6), so the player trims also with the screen off.
- Per format:
    - MP3: the LAME (Xing or Info) tag, which LAME and ffmpeg write, or iTunSMPB. An MP3 with neither gets nothing
      trimmed.
    - AAC in MP4 or M4A: iTunSMPB, as iTunes writes it, or an edit list. AAC encoded by ffmpeg is a known limitation:
      ffmpeg ends its edit list with a short last frame, and Media3 takes the padding as the frames' length minus the
      edit's end, which comes to nothing, so the rest of the last frame plays (0 to 1023 samples, up to 23 ms at each
      join).
    - Raw AAC (ADTS) carries no gapless information: each join keeps the encoder's delay and padding.
    - FLAC and WAV: nothing to trim.
    - Opus in Ogg: the pre-skip, and the end of the last page.
- The last song's padding is trimmed too.
- A song with another sample rate or channel layout than the one before it gets a new audio output, which Media3 fades
  in over 20 ms: a short gap. Within an album in one format and rate this does not happen.
- Media3 1.11 shifted every join of an MP3 with a LAME tag by 529 samples and kept the end of every Opus song; PiPlayer
  uses 1.12.0-alpha02 (1.12.0-alpha01 fixed both), and moves to 1.12.0 once it is stable, before its first public
  release.
- M2a closed on 1.12.0-alpha02: on 08.11.2026 the Maven repository had no 1.12.0 beta, release candidate or stable
  release (the newest version was alpha02, of 26.10.2026). The move to the stable 1.12 is a separate commit with an
  A/B run of `GaplessTest` and `ScreenOffGaplessTest` (the old and the new version's journals compared, as for
  alpha01 to alpha02), and it is required before the first public 0.x pre-release; a beta or release candidate that
  comes first may be taken the same way, each in its own commit.

### 8.2 The fixture

- One continuous 12 s piece, stereo, 16-bit, computed from a formula, so that the tests compute it too: on the left a
  slow logarithmic sweep, a steady tone and a chirp from 2 to 12 kHz each half second that pins an alignment to the
  sample; on the right a falling sweep and a low tone.
- It is split at fixed times into 5 songs, one under a second, none a whole number of any encoder's frames (576, 1024,
  1152, 960 samples) or FLAC blocks, and encoded as albums: FLAC, MP3 (LAME, variable bitrate), AAC in M4A with
  iTunSMPB and no edit list, Opus in Ogg (the piece computed at 48 kHz), and AAC as ffmpeg writes it, the known
  limitation.
- `scripts/generate_gapless_fixtures.py` makes the albums and checks each file's gapless information against the true
  length of its song (the LAME tag, iTunSMPB, the edit list and frame table, STREAMINFO, the pre-skip and last
  granule). The files (1.5 MB) are in the repository like the other fixtures.

### 8.3 Checks

Instrumented tests on the API 30, 36 and 37 emulators and the owner's phone. Each album plays through the service's
player with its output copied after trimming:

- The left channel lines up with the piece to the sample: in windows of 4096 samples before and after every join and
  at both ends, the best match with the formula is at an offset of 0 (searched 1100 samples either way).
- The FLAC album is the piece bit for bit (MD5).
- Exactly the piece comes out (AAC from ffmpeg: with its late samples, the last song's included), one audio output
  serves the album, and every song change is automatic. On the phone no underrun; the emulators only log underruns,
  which their host's audio causes.
- AAC from ffmpeg: each join is late by exactly the rest of the song's last frame. The test fails once Media3 changes
  this, so that this section is updated.

A debug build keeps an audio journal (`files/debug/audio-journal.txt`): the decoders, each new audio output, every
song change and every underrun. Through the service with its default settings, the FLAC album plays through with
automatic song changes, no pause and one audio output. With `-e screenOff true`, the albums play through the service
with the screen off, one audio output each and no underrun on the phone.

By hand on the phone: one of the owner's albums (MP3 with a LAME tag) with the screen off and earbuds, no gap heard.

## 9. Queue editing and own shuffle (M2a step 9)

### 9.1 Adding songs

- Every song row has "Play next" and "Add to queue" in its menu, above "Go to album", "Go to artist" and "Go to folder".
  Album, artist and folder rows of the tabs and pages have a menu with the same two entries, and so has the top bar of
  an album, artist and folder page. An album adds its songs by disc and track, an artist and a folder (with all its
  subfolders, as "Play all, with subfolders") in the sort kept for that kind of list (library.md §6.3), without the
  page's search.
- "Play next" puts the songs right after the current one. "Add to queue" puts them after the current song and after
  the songs already added that come right after it: the songs added by "Play next" and "Add to queue" that have not
  played yet form an up-next run behind the current song, and "Add to queue" extends it. A song played, or passed by a
  jump to a later song, leaves the run. Without such a run "Add to queue" also goes right after the current song, so a
  song added to a 20 000-song queue plays soon rather than after the whole list.
- A song already in the queue is added again as another entry. A message says what happened: "Plays next: <title>" or
  "Added to queue: <title>" for one song, with the number of songs for more.
- With an empty player the songs become the queue, paused at the first of them: the mini player shows it and nothing
  plays until the user presses play.
- The songs are read by the service off the main thread, from the library as the request names them. Only PiPlayer's
  own screen may add songs this way, as it alone may restore the queue (§4.4).

### 9.2 The queue screen

- A "Queue" button on Now Playing opens the queue screen: every entry of the queue in the order it plays, the shuffle
  order while shuffle is on, the current song marked and shown when the screen opens. A tap plays that entry.
- Each row has a handle to drag it to another place and a menu with "Remove from queue"; a swipe to either side removes
  it too. TalkBack offers "Move up" and "Move down" on each row in place of the drag. A removal shows "Removed: <title>"
  with "Undo" for a few seconds; undo puts that entry back where it was, in the list and in the play order, as long as
  the queue has not changed since (another removal, an edit, a new queue, shuffle turned on or off). A newer message
  replaces the one shown. Removing the current song plays the next.
- A drag while shuffle is on changes the play order, not the list underneath: turning shuffle off afterwards goes on in
  the list's order from the current song. With shuffle off a drag moves the entry in the list.
- A tap on a song in any list still replaces the whole queue, edits included, without asking.

### 9.3 Own shuffle order and "Play next"

- Songs added while shuffle is on go to the same place in the play order as in the list: right after the current song,
  or after the up-next run. They are not scattered.
- The up-next run stays right after the current song when shuffle is turned on: the new order is the current song, the
  run in its order, then the other songs shuffled. Turning shuffle off goes on in list order, where the run already
  follows the current song.
- A file opened from another app goes right after the current song in the play order too, before the run, and plays
  (§5.1): nothing of the order is skipped. Songs that a controller adds by itself go to random places after the current
  song and the run, never among songs already played.
- After a restore (§4.8) the order and the run are the saved ones; new songs of the compact request go to random places
  after the run.

### 9.4 What is saved

- An edited queue is the compact request (§2.3) with the edit journal and the added entries of the queue file (§4.8).
  An added entry has its own media ID, the file ID and the entry's number (`<file ID>/a<number>`), so that a song added
  twice is two entries; a song's file ID marks the playing song in the lists either way.
- The journal names entries of the library and added ones. Its rows count only those entries: files opened from other
  apps are not in it and keep their rows in `playback_state` as the queue has them now, so they are put back at the end.
  Removing or moving an opened file changes only its row.
- The queue file (format 2) also holds the up-next run's entries. Format 1 files are still read, without a run.
- Every edit writes the queue file and then `playback_state`; a queue of added songs only has no compact request.
- An edited queue restored under another library ID keeps its added entries, found by their track references, leaves
  out the edits that name library songs by file ID, which would now name other files, and is folded at once (below),
  so that no later save writes old file IDs under the new library ID.
- When the journal holds more than 200 edits, the next restore folds it (§4.8): the restored queue is a fixed list of
  added entries, each with the track reference the library gives its file now, and no compact request. Folding while
  the queue plays would change the media ID of every entry.
- Removing the last entry empties the queue and forgets the saved one; undoing that removal saves the queue again.

### 9.5 Checks

JVM tests: inserts after the queue advanced never land behind the current song; "Play next" and "Add to queue" places
in the list and in the play order, with and without the run; shuffle turned on keeps the run; a move in the play order;
the restored order puts new songs after the run; media IDs of added entries; the journal rows without opened files and
opened rows after inserts, removals and moves; the rebuilt queue from the request, the journal, the added entries and
the opened files equals the edited one, also on a changed list and with another library ID; folding over 200 edits;
the queue file format 2 and format 1; a snapshot of added songs only; removing the only entry, and ranges across opened
files; when a restore folds.

Instrumented tests on the API 30 emulator: through the service, "Play next" and "Add to queue" with shuffle off and on,
the run kept when shuffle is turned on, removal and undo, moves with shuffle off and on, an empty player taking added
songs paused, other apps refused; an undo that names an older removal or comes after shuffle was turned on is refused;
removing the last entry forgets the saved queue and its undo saves it again; under shuffle a file opened from another
app plays right after the current song in the play order; an edited and shuffled queue restored after the process is
gone with its entries, order and run; on the library database, songs by file ID; on the screen, the song menu, the
album, artist and folder menus, the queue screen in play order with the current song, a tap, a removal with undo, a
swiped row undone that stays, a drag, and "Move up" and "Move down". An edit of a 20 000-song queue reaches the
controllers within 0.25 s (the median of 5 runs, `@TimingTest`).

## 10. The library tree for controllers (M2a step 10)

Controllers that browse (Android Auto, Wear, Bluetooth AVRCP browsing, the system's media browsers) see the library
through the media library service: a tree of nodes, each a folder or a song.

### 10.1 Nodes

- The root (media ID `root`) has exactly four browsable children, the app's tabs: Songs, Albums, Artists and Folders.
  Android Auto shows at most four root children. Node IDs do not name their parent, so when M2b adds genres and playlists
  under a node "More", a node can move under it and keep its ID.
- What each node holds, in the order the app keeps for that kind of list (library.md §6.3), never with a search:

| Node | Media ID | Children |
|---|---|---|
| Songs | `songs` | every song, in the Songs tab's order |
| Albums | `albums` | the albums, in the Albums tab's order |
| Artists | `artists` | the artists, in the Artists tab's order |
| Folders | `folders` | the Folders tab's top level: with one volume, the subfolders of its root, then the songs right in it; with more, one node per volume |
| An album | `album/<album key>` | its songs, in the album page's order |
| An artist | `artist/<artist key>` | the artist's albums by year, then all their songs, in the artist page's order |
| A folder | `folder/<volume and path>` | its subfolders by name, then its own songs, in the folder page's order |

- The keys are those of the app's pages (library.md §6.5), percent-encoded as in queue requests (library.md §6.4): an
  album by its album key, an artist by their artist key, a folder by its volume, `/` and relative path encoded as one
  key (`folder/external_primary%2FMusic%2F`). They survive a rebuild of the library database and a new media index.
- A song's media ID is the compact request (§2.3) of the list it is shown in, starting at it: the song's file ID
  (library.md §8.4), which survives a rebuild too, and its row as the fallback.
- An item has its title and the media type of what it is (a folder of titles, albums, artists or mixed, an album, an
  artist, music); an album also its artist and year, a song its artist, album and length, a volume the name the app
  gives it. Browsable nodes are folders; songs and the nodes of §10.3 that play are playable.
- No item carries artwork data. Media3 decodes it into a bitmap for every item it hands a legacy browser, and Android
  Auto warns that such bitmaps can exceed the binder's 1 MB. The cover provider of step 13 gives songs, albums and artists
  an artwork URI (a content URI) through one hook that the tree already asks; until then it gives none.

### 10.2 Long lists

- No node has more than 500 children, nor more than a legacy browser can take in one answer: Media3 cuts a legacy
  browser's list without a word where it reaches 256 KB in parcel form (on the API 30 emulator after 489 songs of an album
  with a long key), so the tree weighs every child from the lengths of its media ID and of the texts it shows (an upper
  bound of its parcel, checked against the platform's own media item) and keeps a node under 240 KB.
- A list of more than 500 entries, or of more weight, becomes bucket nodes, one for each section of the scroll thumb
  (library.md §11.2) in list order: the letter for orders by name, title or file name, the year, the minute or the number
  of songs for the others. A run of one section that is longer or heavier splits into parts, each as long as fits (at
  most 500), titled `A (1)`, `A (2)` and so on. The same section can start two runs (pinyin, or an artist's albums and
  songs of one year); its parts count on. A bucket never holds both albums, artists or folders and songs, so that every
  bucket of songs plays. With texts of usual length a bucket of songs holds about 400 to 450 of them.
- A list whose entries have no section (disc and track order), or whose sections would make more than 500 buckets, splits
  into runs of rows instead, cut the same way and titled by their rows (`1-420`). The list of buckets itself is not
  weighed: it would take more than about 250 buckets with very long folder paths to fill a legacy answer.
- A bucket's media ID is its node's ID, `!` and the section (percent-encoded; empty for runs of rows), then `!` and the
  part from the second part on: `songs!A`, `songs!A!2`, `songs!!3`. It has the type of what it holds; a bucket of songs
  is playable. A bucket's place depends on the lengths of the names before it, so a change of the library may move the
  border between two parts; the section and its first part stay.
- Controllers that page get the page they ask for out of a node's children. Legacy browsers that do not page ask for
  everything at once (page 0 with the largest page size), which then fits. A node that is gone (a key that names nothing
  any more, a section that the order no longer has) is an error.

### 10.3 Playing and adding from the tree

- A song plays the list it is shown in from that song, as a tap in the app: Songs in the tab's order, an album, an
  artist's songs, a folder's own songs.
- A playable node plays its list from the start: Songs, an album, an artist's songs, a folder with all its subfolders
  (as "Play all, with subfolders"), and a bucket of songs its whole list from the bucket's first song. Albums, Artists,
  Folders and the buckets of albums, artists and folders do not play.
- The list is read when the request comes, in the order the app keeps for its kind at that moment. The request
  becomes the queue's compact request, so the queue is saved and restored as one from the app's screen.
- An add from a controller (Media3 `addMediaItems`, a legacy queue add) adds songs by their media IDs: a song of the tree
  adds that song, a playable node the songs of its list (a bucket its own songs), a queue item (a file ID or an added
  entry's ID) its song. They become added entries of the queue (§9.4) at the place the controller asks for, and while
  shuffle is on at random places after the current song and the up-next run (§9.3). A set of several such items starts
  a queue of added entries.
- Search: a controller's search answers with the app's search (library.md §6.3: every word of the query, normalized as
  the library is): up to 100 artists and 100 albums whose names hold it, then up to 300 songs of the Songs list filtered by
  it, cut where a legacy answer is full (§10.2). A song found plays the filtered list from it, as the app's Songs tab
  does while searching.
- Voice: a play request with a search query and no media ID (how Media3 hands over a legacy `playFromSearch`, such as
  "play X on PiPlayer" from the Assistant) plays the best match with its list. An artist whose name is the query (as
  normalized) plays the artist's songs; else an album whose title is the query plays the album; else the Songs list
  filtered by the query plays from its first song whose title is the query, or else from its first song; else the
  first artist, then the first album, in the orders of their tabs, whose name holds the query. An empty query plays every song; when nothing is
  found the request fails. An add with a search query adds the songs that the play would queue, from the match on.

### 10.4 Who may browse

- Only controllers that may read the library (§1.4: PiPlayer itself, the controllers Android trusts, and apps whose uid
  holds the audio permission) get children, items and search results, and may play or add library items. Any other
  controller gets the root, whose children are an empty list; every other node, item and search is refused with a
  permission error, and a play or add of library items fails. The media notification's controller counts as another app
  here too; Media3 also hands System UI's browsing to the callback as that controller, and serves System UI's recent root
  for playback resumption itself.
- Without PiPlayer's own access to music the library lists nothing, so every node is empty.

### 10.5 When the tree is read

- Nothing of the tree is read at start or in the first sync. A node is read when a controller asks for it, on a thread of
  its own, from the library as the app reads it: the songs lists through the shared lists of library.md §6.4 (a list the
  app shows serves the tree at once, and a list the tree reads serves the tap that follows), the albums, artists and
  folders once for each revision of the library, hidden files, collation and order. The results of the last search are
  kept the same way.
- The Songs root and one of its buckets on the generated library of 20 000 songs answer within 300 ms on the API 30
  emulator (the median of 5 after a warm-up, `@TimingTest`). A browse in the car should feel immediate, and step 6 read
  the whole tree of the generated library in about 170 ms on that emulator, which leaves room for a busy host.

### 10.6 Not yet

- The content style hints of Android Auto (lists or grids, group titles in search results) are unstable Media3 API and
  wait until the covers of step 13 make grids worth having. Media focus extras of voice requests (an artist or album
  named apart from the query) are not read. A root children limit below four is not honoured.

### 10.7 Checks

JVM tests: node IDs for every kind with keys of every kind of character, malformed IDs refused; buckets by section, runs
of one section split, sections that repeat, entries without a section, too many sections; pages of children, also with
the largest page size; the weight of an item and buckets cut by weight; the best match of a voice query; an add at a
row in the journal.

Instrumented tests on the API 30 emulator, on a library database filled from a test index: the root and its four nodes;
the children of every node in the app's order, with every ID they give resolving to the same item; buckets on a list
longer than 500 and no node over 500 items with the largest page size; a legacy browser of the platform's protocol gets
every node whole, also one of 450 songs with long titles, and no bitmaps; the weight of an item at least its parcel;
no artwork data anywhere; a song of an album, an
artist, a folder and the Songs list plays that list from it, a node plays its list from the start; search results and
their songs playing the filtered list; a voice query playing an artist, an album and a song with its list; adds of tree
items and queue items journaled as added entries; an app without access to music gets the root and nothing more, and
cannot play or add library items. On the generated library, the timing check of §10.5.
