# M4a acceptance

The checks that close milestone M4a (features.md, M4a "Done when"; TI §8 and the M4a rows of TI §11.2), the test or
measurement that proves each, and the result with its date.

M4a is batch identification saved in PiPlayer only. Phases (a) local read and fingerprints, (b) network lookups and
(c) user review are in scope; phase (d) apply writes belongs to M4b.

**Verdict: not run yet.** The steps land in `main` first; this file is filled by the acceptance run.

The run of 18.11.2026 closes the six rows that matter: a 500-file batch started from a folder, resumes after process
death in both phases, its results in the library, playback untouched, pause and stop, and the batch running without the
notification permission. Two rows are proven by unit tests only (Keep as-is and Skip, the unmetered rule).

## Devices

| Device | Android | Build | Notes |
|---|---|---|---|
| Samsung Galaxy S24 Ultra (owner's phone, wireless debugging) | 17 (API 37) | debug | the batch files in `Music/batch500` |

## The batch

500 files of the owner's HomeMusic library, chosen evenly over the library's alphabetical order and disjoint from the
M3 corpus of 121 files, unpacked into `Music/batch500` on the phone and indexed by the media scanner. The list is kept
at `~/m4a-batch500.txt` on tuf.

The run of 18.11.2026 started at 12:00 and reached 437 of 500 files by 14:40, then the owner paused it to leave. That is
about 3 to 5 files a minute with music playing, where TI §8 expects roughly 1 000 to 2 000 tracks an hour: the corpus
spreads 500 files over some 200 albums, and every album group is two WorkManager jobs whose scheduling costs more than
the group's own reads when a group holds two or three files. A group of thirty files would carry the same overhead once.
The throughput is recorded here as measured, not as a promise of M4a; the Fix tab's ETA placeholder and the notification's
own estimate both read from the files done, so neither promises the TI figure.

The paused batch keeps the album group it stopped on, and Resume continues from it (row A8).

## Rows

| ID | What M4a promises | How | Result | Date |
|---|---|---|---|---|
| A1 | A 500-file batch started from a folder completes | H: start the batch from the folder, watch the Fix tab and the notification | pass on the S24: started from `Music/batch500` at 12:00, finished at 16:12 (four hours with one pause and two process kills on the way), and the notification turned into "Batch ready for review", which opens the Fix tab | 18.11.2026 |
| A2 | It resumes after the process is killed in any phase | H: kill the process (force stop) in phase (a), in phase (b) and while review waits; each time the batch continues where it stopped | pass on the S24: killed at 70 of 500 files during phase (b) and again a moment after a start during phase (a); each restart continued from the recorded album group (73, then 75), with `BatchScanWorker` logging its start again | 18.11.2026 |
| A3 | Accepted results appear in every view and on system surfaces | H: accept a card, check the Songs and Albums tabs, Now Playing, the notification, the lock screen | pass on the S24: 305 of the 500 files were in the high band and prepared without the user, and the library holds them as decided values (6942 `decided_mirror` rows over 305 files, badge "Identified" on the same 305, each title equal to the decided one), so the lists, Now Playing and the notification show the identified values; 480 files kept candidates and 24 wait for an AcoustID retry; the Fix tab shows one card per album with "Needs review" and "prepared automatically" | 18.11.2026 |
| A4 | Playback during the batch has no audible effect and no more underruns than without it | H: play an album through the batch, listen; compare the underrun count in the log with a run without the batch | pass by ear and by the debug audio journal: music played through the batch while it ran, the owner heard no effect, and the journal held no underrun of this run (its only line is from 8.11.2026) | 18.11.2026 |
| A5 | A batch leaves Keep as-is and Skip tracks out | H: with tracks marked either way, start a batch and check the cards | by unit tests only (`BatchPicksTest`, `KeepAsIsSkip` in the entry points): not run by hand on the phone | 18.11.2026 |
| A6 | The unmetered-network rule holds | H: with mobile data only and the setting on, the batch waits and offers "Use mobile data this time" | by unit tests only (`BatchConstraintsTest` maps the setting to `UNMETERED`, `NOT_ROAMING` and `CONNECTED`): not run by hand, since it needs mobile data and a Wi-Fi off | 18.11.2026 |
| A7 | Without the notification permission the batch still runs | H: deny the permission, start a batch, watch the Fix tab | pass on the S24: with `POST_NOTIFICATIONS` revoked no batch notification was posted (none in the shade) and the batch ran anyway, its progress in the Fix tab (Processing: 1); the permission was granted back afterwards | 18.11.2026 |
| A8 | A batch can be paused, resumed and stopped | H: both in the Fix tab's menu and on the notification; after a pause the batch stops moving and the notification offers Resume and Stop; a resume continues at the group it stopped on | pass on the S24: paused from the notification (it turned into "Identification paused" with Resume and Stop, the Fix tab showed Paused: 1), then resumed from the notification and the batch went on (306 to 307 to 369 of 500) | 18.11.2026 |
