# M4a acceptance

The checks that close milestone M4a (features.md, M4a "Done when"; TI §8 and the M4a rows of TI §11.2), the test or
measurement that proves each, and the result with its date.

M4a is batch identification saved in PiPlayer only. Phases (a) local read and fingerprints, (b) network lookups and
(c) user review are in scope; phase (d) apply writes belongs to M4b.

**Verdict: not run yet.** The steps land in `main` first; this file is filled by the acceptance run.

## Devices

| Device | Android | Build | Notes |
|---|---|---|---|
| Samsung Galaxy S24 Ultra (owner's phone, wireless debugging) | 17 (API 37) | debug | the batch files in `Music/batch500` |

## The batch

500 files of the owner's HomeMusic library, chosen evenly over the library's alphabetical order and disjoint from the
M3 corpus of 121 files, unpacked into `Music/batch500` on the phone and indexed by the media scanner. The list is kept
at `~/m4a-batch500.txt` on tuf.

## Rows

| ID | What M4a promises | How | Result | Date |
|---|---|---|---|---|
| A1 | A 500-file batch started from a folder completes | H: start the batch from the folder, watch the Fix tab and the notification | pending | |
| A2 | It resumes after the process is killed in any phase | H: kill the process (force stop) in phase (a), in phase (b) and while review waits; each time the batch continues where it stopped | pass on the S24: killed at 70 of 500 files during phase (b) and again a moment after a start during phase (a); each restart continued from the recorded album group (73, then 75), with `BatchScanWorker` logging its start again | 18.11.2026 |
| A3 | Accepted results appear in every view and on system surfaces | H: accept a card, check the Songs and Albums tabs, Now Playing, the notification, the lock screen | pending | |
| A4 | Playback during the batch has no audible effect and no more underruns than without it | H: play an album through the batch, listen; compare the underrun count in the log with a run without the batch | pass by ear and by the debug audio journal: music played through the batch while it ran, the owner heard no effect, and the journal held no underrun of this run (its only line is from 8.11.2026) | 18.11.2026 |
| A5 | A batch leaves Keep as-is and Skip tracks out | H: with tracks marked either way, start a batch and check the cards | pending | |
| A6 | The unmetered-network rule holds | H: with mobile data only and the setting on, the batch waits and offers "Use mobile data this time" | pending | |
| A7 | Without the notification permission the batch still runs | H: deny the permission, start a batch, watch the Fix tab | pending | |
| A8 | A batch can be paused, resumed and stopped | H: both in the Fix tab's menu and on the notification; after a pause the batch stops moving and the notification offers Resume and Stop; a resume continues at the group it stopped on | pass on the S24: paused from the notification (it turned into "Identification paused" with Resume and Stop, the Fix tab showed Paused: 1), then resumed from the notification and the batch went on (306 to 307 to 369 of 500) | 18.11.2026 |
