# M1 acceptance

The checks that close milestone M1 (features.md, M1 "Done when"), the commands that run them, and the results per
device. The specification is playback.md; this file only records whether M1 holds on the final M1 build.

## Devices

| Device | Android | Media3 | Build |
|---|---|---|---|
| Emulator, API 30 (`google_apis`) | 11 | 1.12.0-alpha01 | debug |
| Emulator, API 36 (`google_apis_playstore`) | 16 | 1.12.0-alpha01 | debug |
| Emulator, API 37 (`google_apis_playstore`) | 17 | 1.12.0-alpha01 | debug |
| Samsung Galaxy S24 Ultra (owner's phone) | 16, One UI 8.5 | 1.12.0-alpha01 | debug |

## Checks

How: T is an instrumented test, A a few adb commands, H by hand. Media keys are sent with `input keyevent`, the way a
headset's are: on Android 11 a key sent with `cmd media_session dispatch` to a running session crashes Media3's
platform session code (it carries no package name).

| ID | What M1 promises | How | Expected |
|---|---|---|---|
| A1 | Everything specified so far still holds | T: the whole instrumented suite | All green. On the emulators the timing tests depend on the host's load, so a timing failure is run again on its own before it counts |
| G1 | Gapless, judged on the fixture (MP3 with a LAME tag, AAC with gapless info, FLAC, Opus) | T: `GaplessTest` | Every album lines up with the piece to the sample, FLAC bit for bit; AAC from ffmpeg is late by its documented amount (playback.md §8.1) |
| G2 | An album plays gapless with the screen off | T: `ScreenOffGaplessTest`, `-e screenOff true` | Automatic song changes, no pause or stall, one audio output; on the phone no underrun |
| G3 | The same with the owner's own album | H, with earbuds, then the audio journal | No gap heard; the journal shows one output and no underrun |
| C1 | A call holds playback, which resumes when it ends | A: `adb emu gsm call 5551234`, then `adb emu gsm accept 5551234`, then `adb emu gsm cancel 5551234` | During the call paused by the loss of audio focus; after it, playing the same song again |
| C2 | The same with a real call | H | As C1 |
| N1 | An unplugged headset pauses playback | A: `adb root`, then `am broadcast -a android.media.AUDIO_BECOMING_NOISY` (only the API 30 image allows root) | Paused, and it stays paused |
| N2 | The same with wired (USB-C) headphones and Bluetooth | H | As N1 |
| K1 | The queue and position come back after the process was killed | A: pause, swipe the app away, `adb shell am kill io.github.lorddetson.piplayer`, then `adb shell input keyevent KEYCODE_MEDIA_PLAY` | A new process plays the saved song from the saved position |
| K2 | The same after a kill while playing | A: `adb shell run-as io.github.lorddetson.piplayer kill -9 <pid>`, then `adb shell input keyevent KEYCODE_MEDIA_PLAY` | Plays the saved song from at most 5 s before the kill |
| K3 | Playback resumes from the system media controls | H: after K1, play on the lock screen or quick settings card | As K1 |
| B1 | A Bluetooth controller controls playback | H: play, pause, next and previous on the earbuds | Each takes effect |
| Q1 | A 20 000-item queue starts within about 1 s | T: `PlaybackQueueTest` | Median at most 1 s (an image with 16 KB pages only logs it) |
| Q2 | With 20 000 songs the notification, lock screen and a Bluetooth controller stay responsive | H | No visible delay |

Commands for the instrumented rows, from the repository root:

```
./gradlew :app:installDebug :app:installDebugAndroidTest
adb shell am instrument -w io.github.lorddetson.piplayer.test/io.github.lorddetson.piplayer.PiPlayerTestRunner
adb shell am instrument -w -e screenOff true -e class io.github.lorddetson.piplayer.playback.ScreenOffGaplessTest io.github.lorddetson.piplayer.test/io.github.lorddetson.piplayer.PiPlayerTestRunner
```

The audio journal of a debug build:

```
adb shell run-as io.github.lorddetson.piplayer cat files/debug/audio-journal.txt
```

## Results

Each cell is `pass`, `fail` with what happened, or `n/a`. All runs are of 2026-10-25, on the build of commit f502ab7
(Media3 1.12.0-alpha01). On the phone, music played at volume 0 during the automated runs, from the owner's library
for K1 and K2. An empty S24 cell is a check by hand that the owner has yet to make.

| ID | API 30 | API 36 | API 37 | S24 Ultra |
|---|---|---|---|---|
| A1 | pass: 296 tests, Q1's timing run again on its own | pass: 296 tests | pass: 296 tests, the platform queue tests run again after a fix of the test's own `dumpsys` parsing | pass: 296 tests; one that timed out waiting for an opened file passed twice on its own |
| G1 | pass | pass | pass | pass: Samsung's FLAC and MP3 decoders, the platform's AAC and Opus ones; one output and no underrun for each album |
| G2 | pass | pass | pass | pass: no underrun |
| G3 | n/a | n/a | n/a | |
| C1 | pass: paused on the ring (transient loss of focus), playing the same song after the call | n/a | n/a | n/a |
| C2 | n/a | n/a | n/a | |
| N1 | pass: paused and stayed paused | n/a | n/a | n/a |
| N2 | n/a | n/a | n/a | |
| K1 | pass: resumed at the paused position (62.7 s) | n/a | n/a | pass: resumed at the paused position (8.0 s) |
| K2 | pass: killed at 33.1 s, resumed from about 30 s | n/a | n/a | pass: killed at about 29 s, resumed from about 25 to 27 s |
| K3 | pass: Resume on the quick settings card played the saved song from its position | n/a | n/a | |
| B1 | n/a | n/a | n/a | |
| Q1 | pass: median 915 ms and 979 ms on its own (1323 ms within the whole suite) | pass | pass | pass: median 501 ms |
| Q2 | n/a | n/a | n/a | |

Found during these runs and fixed before them: after the system restarted the service, a media button resumed a queue
that the platform session published in full, without the notification (playback.md §2.3, §4.5).
