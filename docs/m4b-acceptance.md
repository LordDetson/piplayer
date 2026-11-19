# M4b acceptance

The checks for tag writing, history and undo in [Milestone M4b](features.md#m4b-tag-writing-history-and-undo)
and [Track Identification §11](track-identification.md#11-acceptance-criteria-and-tests). The milestone is in progress.

## Test devices

| API | Device | Test storage | Coverage |
|---|---|---|---|
| 30 | Medium_Phone AVD | Internal storage and virtual SD card | Automated and consent checks |
| 36 | Pixel_8 AVD | Internal storage | Automated and consent checks |

## Automated checks

| Check | Test coverage |
|---|---|
| Build a diff from decided keys, retain unchanged property values, and apply protected-field rules | `TagDiffTest` and `TagWritePreparationTest` |
| Write and reread MP3, FLAC, M4A, Ogg Vorbis, Opus and WAV while preserving the audio content key | Native `TagWriterRoundTripTest`, `TagWriterId3v1Test` and `TagWriterCoverTest` |
| Identify the changed fields that keep their existing ID3v1 values | `ManualTagEditorTest` and `TagWriterId3v1Test`, including a comparison against the bytes after writing |
| Prepare and verify the copy, record the write before streaming, verify the original, and keep the journal for undo | `TagWriteSessionTest` and `TagWriteJournalTest` |
| Update the library immediately and recover when its transaction is interrupted after journal verification | `SingleTagWriteRecoveryTest`, `TagPassTest` and `UserDatabaseTest` |
| Reopen the journal with a deferred single-file write, require consent and verify the final file | `SingleTagWriteRecoveryTest` |
| Resume a batch with the verified files excluded from the next write request | `BatchTagRecoveryTest` and the 500-file procedure below |
| Run the approved selection as a private `dataSync` service and retain recoverable work after stopping | `BatchTagApplyServiceTest`, `TagApplyQueueTest` and the foreground-service procedure below |
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
5. Play a shell-owned library track and start a manual tag write on that track. After the first system consent,
   verify that PiPlayer waits for playback and the original is unchanged. Force-stop PiPlayer, reopen Fix and resume
   the interrupted write. Verify that Android requests consent again for this file. Approve and reread the tags
   after the player releases it. Confirm that the audio hash matches.
6. On API 31 and later, if optional media management access is enabled for the build, check `MediaStore.canManageMedia()`
   before and after granting it. Repeat a write request and the interruption procedure. Record whether the platform
   shows a dialog and whether a new request still needs the Activity. Repeat after revoking access.

## Foreground-service timeout

On an isolated API 35 or later emulator, save the current `activity_manager/data_sync_fgs_timeout_duration` value and
set a short test timeout with `adb shell device_config put activity_manager data_sync_fgs_timeout_duration 6500`.
Approve a batch from the Activity and confirm the `dataSync` service is in the foreground before pressing Home.
Verify that the service stops at the timeout, the app process survives, the count of completed files is preserved,
and tapping the recovery notification opens Fix. Restore the original setting before resuming the batch.

## Recorded results

- API 30, real MediaStore consent: approved writes from `Download/` and from the root of the virtual SD card. The new
  title was read back and the audio hash matched on both files.
- API 30, 500 MP3 files owned by `com.android.shell`: the first dialog covered 500 files. After force-stop, the next
  process found 53 verified writes and the second dialog covered 447 files. All 500 reached verified. Tags and audio
  hashes were checked on files 001, 250 and 500. The reproducible test preparation is in commit `65027812`.
- API 36, one MP3 owned by `com.android.shell`: Deny preserved the file and the pending manual edit. Allow wrote the
  title and preserved the audio hash. After force-stop and relaunch, a subsequent edit showed a new system dialog.
- API 36, manual ID3v1 preview on a shell-owned MP3: the editor reported that the title exceeded ID3v1 capacity. After
  consent, ID3v2 held the new title, ID3v1 held the old title, both tags held the new album, and the audio hash matched.
- API 30, single-write recovery on 20 November: a shell-owned MP3 waited for playback after consent. PiPlayer was
  force-stopped at PID 5217. PID 5821 showed the interrupted write in Fix and requested new consent for the same file.
  Denying it preserved every byte. Allowing it and choosing Write tags now applied the requested title, preserved the
  audio hash and returned the player to its paused state.
- API 36, library refresh: the library showed the new title before the MediaStore observer. A simulated failure
  between database transactions left a pending refresh. Reopening the user database and reading the verified file
  updated the library with the file bytes unchanged. An older tag pass with matching size and modification time
  could not replace the new library tags.
- API 30, schema v10 and app-owned fixtures: the v9 migration passed. After stopping PiPlayer during a 500-file write,
  the next process found 72 verified writes and completed the remaining 428. This test used ownership for file access.
- API 36, 500 app-owned test files after journal compaction: a new process found 72 verified entries and completed the
  remaining 428. The test used write access from ownership.
- API 36, 500 MP3 files owned by `com.android.shell` on the foreground service: the initial dialog covered all 500.
  Android timed out the service after Home. The same PiPlayer process remained alive, the UI showed 104 written and 396
  remaining, and the recovery notification opened Fix. After restoring the default timeout, apply resumed. The app was
  force-stopped while the service was active. A new process recovered 178 verified writes, requested access to exactly
  322 files and completed all 500. MediaStore and the journal both confirmed 500 writes. Files 0001, 0250 and 0500 had
  the new title and unchanged audio hashes.
- Optional media management access is below the cut line.
