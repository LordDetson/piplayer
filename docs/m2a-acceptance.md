# M2a acceptance

The checks that close milestone M2a (features.md, M2a "Done when", and the acceptance rows of the M2a plan), the test
or measurement that proves each, and the result with its date. The specification is library.md (and playback.md §9 and
§10 for the queue and the library tree); this file only records whether M2a holds on the final M2a build.

By the owner's decision of 07.11.2026, M2a closes after the acceptance by tests. The week of daily use with real music
and M1's check C2 with a real phone call moved to the start of M2b (features.md, M2b). Media3 stays on 1.12.0-alpha02,
because no stable 1.12 has been released (playback.md §8.1).

**Verdict, 08.11.2026: M2a is closed** on `91c4b81`. Every row passes, with three recorded exceptions: L1 has no headroom
and one quiet pass of six went over the phone limit (owner's decision: close as is, speed up the first sync in M2b), V1 stays
on 1.12.0-alpha02, and the hand checks W1, R2 and K3 move to the daily-use week at the start of M2b.

## Devices

| Device | Android | Media3 | Build |
|---|---|---|---|
| Emulator, API 30 (`google_apis`, x86_64), local `emulator-5554` and GitHub Actions | 11 | 1.12.0-alpha02 | debug |
| Emulator, API 36 (GitHub Actions) | 16 | 1.12.0-alpha02 | debug |
| Samsung Galaxy S24 Ultra (owner's phone), with the generated library of 20 000 files | 16, One UI 8.5 | 1.12.0-alpha02 | debug and benchmark (release with R8) |

## How

T is an instrumented test, J a JVM test, M a measurement (an instrumented `@TimingTest` in the quiet `--timing` pass or a
test that measures and logs), H by hand. "CI" is the full instrumented suite on GitHub Actions (API 30 and API 36),
started with `git push -f origin HEAD:ci/<name>` and `tools/instrumented/ci_wait.py`. The quiet timing pass runs with
`tools/instrumented/run_instrumented.py <serial> --timing` while nothing builds on the machine.

## Acceptance rows

| ID | What M2a promises | How | Result | Date |
|---|---|---|---|---|
| A1 | Everything specified so far still holds | T: the whole instrumented suite; J: `./gradlew testDebugUnitTest lintDebug` | pass. CI on API 30 and API 36 on c464f5d, run 42458448590 (all of steps 10, 13 and 14); after the tree speed-up 91c4b81, API 30 green (run 42469839260) while API 36 could not start: the fpcalc download from GitHub failed with HTTP 504. S24 on c464f5d: 734 tests, 724 passed, 0 failed, 10 skipped by their flags. JVM 1186 tests, lint without errors | 08.11.2026 |
| L1 | The generated 20 000-file library is browsable within seconds of the first launch | M: `GeneratedLibraryTest` (first page after the grant at most 5 s on API 30 and 3 s on the phone; a later start at most 1 s) | pass by the owner's decision of 08.11.2026, with no headroom. S24, final build, six warm quiet passes: first page 3.00, 2.78, 2.70, 2.77, 3.04 and 2.71 s (one over 3 s), later start 0.43 to 0.53 s. API 30, final build: 4.87, 5.37 and 4.91 s (one over 5 s), later start 0.84 to 0.89 s; a bisect over the last five merges gave 4.3 to 4.75 s on every one of them, so no late step regressed it. Speeding up the first sync is a task for M2b | 08.11.2026 |
| L1b | The Albums, Artists and Folders tabs of 20 000 files open fast | M: the tab timings of the quiet pass | pass. S24, final build: albums 64 to 88 ms, artists 29 to 55 ms. API 30: albums 69 ms, artists 18 ms, folder levels 27 ms (step 6) | 08.11.2026 |
| L2 | The tag pass over 20 000 files while music plays keeps list positions and causes no underrun | T: `TagPassScreenOffTest` on the phone (FLAC album on repeat, screen off, no charger), `SongsInPlaceScreenTest` for positions | pass. S24: 21 368 files in 887 s (24 files/s), 0 read failures, 0 underruns, no stall, no new audio output, worst playback-thread lateness 89 ms against 82 ms in the control minute; again after the lower thread priority: 866 s, worst 116 ms. Positions: CI | 05.11.2026 |
| L2b | The duration walks after the pass (step 7) keep playback clean | T: `TagPassScreenOffTest` (waits for the walks) | pass. S24: 19 499 lengths in 1 155 s, no underrun, no stall, worst lateness 71 ms | 05.11.2026 |
| L3 | Songs scroll smoothly on an R8 release build on the phone | M: macrobenchmark `ScrollBenchmark` (at most 5 % janky frames during flings) | pass. S24, final build with covers in the rows: 450 janky of 24 220 frames (1.86 %), frame overrun P99 0.87 ms. The step 2 build had 0.04 to 0.17 % | 08.11.2026 |
| L4 | The release build starts, lists, plays and edits the queue under R8 | T: macrobenchmark `SmokeTest` on the benchmark build | pass, 3/3 on the S24 (steps 5 and 9, with the queue screen and a drag) | 07.11.2026 |
| S1/S2 | "ежик" finds "Ёжик", "Beyonce" finds "Beyoncé", "acdc" finds "AC/DC" | J: `SearchTermsTest`, `TextNormalizerTest`; T: `LibraryListsTest`, `NormalizerSameResultTest` | pass: CI | 08.11.2026 |
| S3 | Search answers its first page within 50 ms (95th percentile) | M: `LibrarySearchSpikeTest` on the real schema | pass. S24, final build: slowest p95 32 to 44 ms in five passes. API 30 after step 12: 45, 33 and 25 ms | 08.11.2026 |
| I1 | The TI §11 rows of M2a: content key, exact duration, re-attachment, the `:tagger` process | J: `ContentKeyTest`, `ExactDurationTest`, `OggWalkTest`, `IdentityMatcherTest`, `TagRepliesTest`; T: `ContentKeyGoldenTest`, `ExactDurationDeviceTest`, `IdentityAttacherTest`, `FingerprintReattachTest`, `LibraryRebuildTest`, `TaggerClientTest`, `TaggerCrashTest` | pass: CI and JVM on the final build; `TaggerCrashTest` (crash hooks of the debug build) passed on API 30 and API 36 in step 4 | 08.11.2026 |
| B1 | Browsing from a controller: the library tree with A to Z buckets and size caps | T: `LibraryTreeTest`, `LibraryTreePlayTest`, `TreeItemWeightTest` (the platform `MediaBrowser` and Media3's `MediaBrowser`); M: the tree timing of `GeneratedLibraryTest` | pass with test controllers: CI. S24 after 91c4b81: Songs root 126 to 136 ms, a cold bucket 136 to 179 ms (limit 200 ms on the phone). A real Bluetooth head unit, Android Auto or Wear was not tried (moves to the daily-use week) | 08.11.2026 |
| R1 | M1 still holds: Q1 on the real generated library, gapless G1 and G2, restore K1 to K3 | T: `PlaybackQueueTest` (phone only), `GaplessTest`, `ScreenOffGaplessTest`, `PlaybackRestoreTest`, `QueueRestoreTest` | Q1 pass: S24 final build, median 404 to 451 ms in five passes (limit 1 s). G1: CI. G2: journals of `GaplessTest` and `ScreenOffGaplessTest` byte for byte equal on alpha01 and alpha02 (01.11). K1 and K2 by the restore tests: CI. K3 (the lock screen's play) by hand: not repeated in M2a, moves to the daily-use week | 01.11 to 08.11.2026 |
| V1 | Media3 on a stable 1.12, or the owner's exception | Version check of the Maven metadata | exception: no 1.12 beta, release candidate or stable release on 08.11.2026 (newest 1.12.0-alpha02 of 26.10). Moving to the stable 1.12 is a separate commit with an A/B `GaplessTest`, required before the first public 0.x | 08.11.2026 |
| W1 | A week as the owner's daily player with real music, no open blocker | H | moved to the start of M2b (owner's decision 07.11.2026) | |
| R2 | M1's manual leftovers: C2 with a real call, and the manual items of M1 steps 3 to 7 | H | moved to the start of M2b, into the daily-use week | |

## Features of M2a

Each feature of the M2a list in features.md, with the tests that cover it. Every class named here passed on API 30 and
API 36 in the final CI run (42416883212) or among the JVM tests of the final build, unless the row says otherwise.

| Feature | Spec | Tests |
|---|---|---|
| Two databases, sync with the media index, ready and known states, rebuild that keeps user data | library.md §1, §3.1, §8.5 | J: `SyncPlannerTest`; T: `LibrarySyncTest`, `MediaStoreIndexTest`, `LibraryReopenTest`, `LibraryRebuildTest`, `UserDatabaseTest` |
| Library folders, hidden ringtones, recordings and short files, settings skeleton, theme | §3 | J: `FolderRuleMatcherTest`, `HiddenFilesTest`, `ThemeModeTest`; T: `LibrarySettingsScreenTest`, `FoldersBeforeTheFirstSyncTest`, `SettingsScreenTest`, `SettingsMigrationTest`, `BackupRulesTest` |
| The `:tagger` process | §4 | T: `TaggerClientTest`, `TaggerCrashTest`, `TagReadGuardTest` |
| Tag pass, merged values, legacy encodings in automatic mode, in-place batches, progress line | §5 | J: `MergedValuesTest`, `LegacyTextTest`, `TrackTagsTest`; T: `TagPassTest`, `TagPassRunnerTest`, `TagPassThroughTaggerTest`, `SongsInPlaceScreenTest`, `SessionMetadataTest` |
| Albums, Artists and Folders tabs and pages, recursive folder play, compact `q2` requests, track menu, current song marked | §6 | J: `AlbumKeyTest`, `GroupingValuesTest`, `QueueRequestTest`; T: `LibraryTabsScreenTest`, `LibraryListsTest`, `FolderLevelsTest`, `PlayingSongFollowsThePassTest` |
| Content key and exact duration | §7 | J: `ContentKeyTest`, `ExactDurationTest`, `DurationWalksTest`; T: `ContentKeyGoldenTest`, `ExactDurationDeviceTest`, `StoredDurationTest` |
| Stable identity, re-attachment, snapshot v3 with the shuffle order | §8 | J: `IdentityMatcherTest`, `TrackRefsTest`; T: `IdentityAttacherTest`, `FingerprintReattachTest`, `QueueFileTest`, `QueueRestoreTest` |
| Queue editing, own shuffle with "play next" kept next, queue screen | playback.md §9 | J: `QueueEditingTest`, `StartFirstShuffleOrderTest`, `QueueRebuildTest`; T: `QueueEditingTest`, `QueueScreenTest`, `QueueJournalTest`; M: `QueueEditTimingTest` (add 161 ms, remove 173 ms, move 71 ms on 20 000, limit 250 ms) |
| Library tree for controllers | playback.md §10 | J: `TreeNodeTest`, `TreeBucketsTest`, `VoiceMatchTest`; T: `LibraryTreeTest`, `LibraryTreePlayTest`, `TreeItemWeightTest` |
| Locale-aware sorting, scroll thumb, list or grid | §11 | J: `SortKeyTest`, `SectionsTest`, `SortBucketsTest`; T: `IcuCollationTest`, `LibrarySortingTest`, `LongListsScreenTest` |
| Search over titles, artists, albums, composers, genres and file names | §6.3, TI §10.1 | J: `SearchTermsTest`, `TextNormalizerTest`; T: `LibrarySearchSpikeTest`, `NormalizerSameResultTest` |
| Covers: own, embedded, system thumbnail, placeholder; a track's own cover | §13 | J: `CoverPlanTest`, `CoverSizesTest`, `CoverDiskCacheTest`; T: `CoverSourcesTest`, `CoversScreenTest`, `OwnCoversTest`, `LibraryCoverFactsTest` |
| Onboarding, empty states for no permission, scanning and no music | §14.1 | J: `OnboardingTest`; T: `OnboardingScreenTest`, `OnboardingWithThePermissionTest`, `SongsContentTest`, `LibraryEmptyStatesTest`; `OnboardingFirstLaunchTest` by hand after `pm revoke` (the helper and CI grant access): pass on API 30, 08.11.2026 |
| Open-source licences in Settings > About, listed at build time | §14.2, §14.3 | J: `OpenSourceLicensesTest`; T: `LicensesScreenTest`, `LicenseTextsTest`; the list task fails the build on a module without a known licence |

The cover provider for the controller tree (step 13, part B) is not part of this table: it was still being built when
this file was written.

## Found and fixed while closing M2a

- `DecodingEdgeCasesTest#aFlacCutOffInsideAFrameLongBeforeItsStatedLengthEnds` failed now and then on loaded CI (0.673 s
  against at least 0.696 s). It was a real loss of audio, not a slow test: a decoder that fails on the cut last frame
  gives none of the frames it had decoded but not yet handed out, and how many it held depended on timing (1 to 6 of
  30 frames in 11 of 40 decodes on the API 30 emulator). The last FLAC frame now waits until the frame before it has
  come out (TI §3.2); 40 of 40 decodes keep all 30 frames, and a new test decodes the file 40 times.
- The Maven dependencies had no licence notices in the app; the licences screen now lists every runtime module, plus
  SQLite and the Public Suffix List that two of them carry.
