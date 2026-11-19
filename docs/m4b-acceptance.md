# M4b acceptance

The checks for tag writing, history and undo in [Milestone M4b](features.md#m4b-tag-writing-history-and-undo)
and [Track Identification §11](track-identification.md#11-acceptance-criteria-and-tests). The milestone is in progress.

## Test devices

| API | Device | Test storage | Coverage |
|---|---|---|---|
| 30 | Medium_Phone AVD | Internal storage and virtual SD card | Automated and consent checks |
| 33 | No configured AVD | | Pending |
| 35 | No configured AVD | | Pending |
| 36 | Pixel_8 AVD | Internal storage | Automated and consent checks |

## Automated checks

| Check | Test coverage |
|---|---|
| Build a diff from decided keys, retain unchanged property values, and apply protected-field rules | `TagDiffTest` and `TagWritePreparationTest` |
| Write and reread MP3, FLAC, M4A, Ogg Vorbis, Opus and WAV while preserving the audio content key | Native `TagWriterRoundTripTest`, `TagWriterId3v1Test` and `TagWriterCoverTest` |
| Prepare and verify the copy, record the write before streaming, verify the original, and keep the journal for undo | `TagWriteSessionTest` and `TagWriteJournalTest` |
| Reopen the journal with a deferred single-file write, require consent and verify the final file | `SingleTagWriteRecoveryTest` |
| Resume a batch with the verified files excluded from the next write request | `BatchTagRecoveryTest` and the 500-file procedure below |
| Protect the current and next tracks, and return to the saved position after Write now | `TagWriteGateTest` and `PlaybackQueueTest` |

Record the revision and results for each API at acceptance. The instrumented pipeline fixtures use test-controlled
write access. The consent checklist uses audio files added to MediaStore by another owner, such as `com.android.shell`.

## MediaStore consent checklist

Use disposable files. Record the MediaStore volume and owner, initial tags, and a hash of the audio stream before
applying a change. Run the following on each API in the test matrix. Use the virtual SD card for the card-root check.

1. Apply a selection of files from `Music/`. Verify the in-app explanation, then count the system dialogs. One dialog
   should cover the full selection when the platform accepts it. Confirm and reread the changed tags and audio hashes.
2. Repeat on a file in `Download/` and on a file at the root of the SD card. Open the diff before approving. Reread
   the changed tags and compare the audio hashes.
3. Deny the system dialog for a recognized track. Verify that PiPlayer retains the accepted result and shows the file
   as unwritten. Reread the original file and compare its bytes.
4. Start the 500-file apply, approve the request, wait for a nonzero number of verified writes, and force-stop PiPlayer.
   Reopen the app and note the verified count before pressing Resume. Check that a fresh dialog covers only the
   remaining files. Approve it and verify that all 500 reach the verified state. Reread tags and compare the audio hashes
   on samples from the beginning, middle and end. Record both process IDs and both dialog counts.
5. On API 31 and later, if optional media management access is enabled for the build, check `MediaStore.canManageMedia()`
   before and after granting it. Repeat a write request and the interruption procedure. Record whether the platform
   shows a dialog and whether a new request still needs the Activity. Repeat after revoking access.

## Results recorded on 19 November 2026

- API 30, real MediaStore consent: approved writes from `Download/` and from the root of the virtual SD card. The new
  title was read back and the audio hash matched on both files.
- API 30, 500 MP3 files owned by `com.android.shell`: the first dialog covered 500 files. After force-stop, the next
  process found 53 verified writes and the second dialog covered 447 files. All 500 reached verified. Tags and audio
  hashes were checked on files 001, 250 and 500. The reproducible test preparation is in commit `65027812`.
- API 36, one MP3 owned by `com.android.shell`: Deny preserved the file and the pending manual edit. Allow wrote the
  title and preserved the audio hash. After force-stop and relaunch, a subsequent edit showed a new system dialog.
- API 36, 500 app-owned test files after journal compaction: a new process found 72 verified entries and completed the
  remaining 428. The test used write access from ownership. The API 36 system dialog for resuming a 500-file selection
  remains open.
- API 33 and API 35 are pending for both the automated and consent checks. Optional media management access is pending.
