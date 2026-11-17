# M2b acceptance

The checks that close milestone M2b (features.md, M2b "Done when", and the items M2a handed over), the test or
measurement that proves each, and the result with its date. The specification is library.md §1.7, §5.7 and §15 to §20,
playback.md §4.3.1, §9.1, §9.6, §10 and §11, and TI §3.1 and §3.6; this file only records whether M2b holds on the final
M2b build.

By the owner's decision of 14.11.2026, the week of daily use on the phone (PIM-2) and M1's check C2 with a real phone
call (PIM-8) come at the end of M2b, on the finished milestone, and not at its start. The feature steps were decided by
the assistant's own recommendations (owner's decision of 14.11.2026); each decision is marked "decision" in the
specification. Media3 stays on 1.12.0-alpha02 (playback.md §8.1).

**Verdict, 17.11.2026: M2b is closed.** By the owner's decision the daily-use week (PIM-2) and C2 with a real call
(PIM-8) are accepted as they are and will not be re-run. The feature work and the automated acceptance pass on `66f32f2`: every
row that tests or the quiet timing pass on the API 30 emulator can prove passes, including the two data promises of
"Done when" (D1, D2) and the legacy-encoding fixtures (E1), with two recorded notes: L1-later passed on the three warm
passes (864, 819, 760 ms), while the cold first pass after the install took 1134 ms against 1 s; removing 1 000
scattered queue entries has little headroom (median 452 ms against 500 ms). V1 stays an exception on 1.12.0-alpha02;
the Maven metadata is checked again when M2b closes. The measurements on the S24 passed on 15.11.2026 with headroom (L1, L1-later, S3, Q1, the tree timing of B1), and
the update over the M2a build kept `user.db` (U1). K3 passed by hand on the phone. The daily-use week W1 and C2 with a real call (R2) are open;
every hand check is listed in "Open for the daily-use week".

## Devices

| Device | Android | Media3 | Build |
|---|---|---|---|
| Emulator, API 30 (`Medium_Phone`, x86_64), local, with the generated library of 20 000 files | 11 | 1.12.0-alpha02 | debug |
| Emulator, API 30 (`google_apis`, x86_64, GitHub Actions) | 11 | 1.12.0-alpha02 | debug |
| Emulator, API 36 (`google_apis`, x86_64, GitHub Actions) | 16 | 1.12.0-alpha02 | debug |
| Samsung Galaxy S24 Ultra (owner's phone, wireless debugging), with the generated library of 20 000 files | 17 (API 37), build S928BXXS6EZJ1 | 1.12.0-alpha02 | debug, `29fcf59` (same app code as `66f32f2`), installed over the M2a build on 15.11.2026 |

## How

T is an instrumented test, J a JVM test, M a measurement (an instrumented `@TimingTest` in the quiet `--timing` pass),
H by hand. "CI" is the full instrumented suite on GitHub Actions (API 30 and API 36), started with
`git push -f origin HEAD:ci/<name>` and `tools/instrumented/ci_wait.py`; it skips the `@TimingTest` tests, the crash
tests and `GeneratedLibraryTest` (no generated library there). The quiet timing pass runs with
`tools/instrumented/run_instrumented.py <serial> --timing` while nothing builds on the machine. The instrumented tests
empty the debug build's user data (favorites, plays, playlists, own covers, folder rules), so on the phone they run
before the daily-use week, not during it.

## Acceptance rows

| ID | What M2b promises | How | Result | Date |
|---|---|---|---|---|
| A1 | Everything specified so far still holds | T: the whole instrumented suite; J: `./gradlew testDebugUnitTest lintDebug` | pass. CI on `66f32f2` green on API 30 and API 36, run 43670904476. JVM 1442 tests, 0 failures, lint without errors; `ci.yml` on `main` run 43673309214 success. CI of the earlier M2b steps: step 1 run 43585890149, step 2 run 43597926836, step 3 run 43621850753, step 4 run 43639361570. The whole suite on the S24 was not run in M2b | 15.11.2026 |
| L1 | The first sync of 20 000 files is faster, with headroom (handed over by M2a, PIM-13) | M: `GeneratedLibraryTest#theFirstLaunchListsTheLibraryWithinSecondsAndSyncsItWithinTen` (first page after the grant at most 5 s on API 30 and 3 s on the phone in every pass, the median of three warm quiet passes on API 30 at most 4 s, library.md §1.7; whole sync at most 10 s). T: `BulkInsertTest`, `LibrarySyncTest#aFirstSyncWhoseResolvingFailsWritesNothing`, `#aFirstSyncWhoseWriteFailsWritesNothing`; J: `SqlRowsTest` | API 30: pass. Three warm quiet passes on `66f32f2`: 3.53, 3.00 and 2.84 s, median 3.00 s; the cold first pass after the install 3.70 s. PIM-13 alone took the first page from 4.97 to 5.44 s down to 3.54 to 3.69 s (M2a final build: 4.87 to 5.37 s); the genres of PIM-64 added no visible regression. S24: pass, the quiet pass on `29fcf59` (12 of 12 timing tests green) and two more warm passes: 2.03, 1.73 and 1.69 s, median 1.73 s against 3 s (M2a final build 2.70 to 3.04 s) | 15.11.2026 |
| L1-later | A later start shows the synced library within 1 s | M: `GeneratedLibraryTest#aLaterLaunchListsTheSyncedLibraryWithinASecond` | API 30: pass on the warm passes, 864, 819 and 760 ms (M2a: 0.84 to 0.89 s). The cold first pass after the install took 1134 ms, over the limit, as the first pass after an install did before. S24: pass, 428, 463 and 474 ms (M2a 0.43 to 0.53 s) | 15.11.2026 |
| D1 | Favorites, playlists and statistics survive a rebuild of the media index | T: `UserDataSurvivesRebuildTest#userDataSurvivesAMediaIndexRebuildWithNewIds` and `#userDataSurvivesARebuildOfTheLibrary` (a favorite song, a favorite album in the favorites-only album list behind the Albums tab's filter, a song played twice: play count 2 and first in Most played); `PlaylistsThroughTheLibraryTest#entriesResolveAfterAMoveARenameAndAnIndexRebuildWithNewIds` and `#entriesSurviveARebuildOfTheLibrary` (a playlist with one song twice shows the same songs again); `LibraryRebuildTest#aRebuildKeepsFileIdsTheLibraryIdAndIdentities`. After the library rebuild the test writes each file's content key as the tag pass would and runs the attach pass | pass: CI run 43670904476. The index rebuild gives every file a new media ID under a new index version; the library rebuild is `Library.rebuild`, which Settings > Rebuild the library calls (the button itself is not driven by a test). That the real tagger gives a file the same key again is shown by `IdentityThroughTaggerTest#aMovedOggFileWhoseCommentsAnotherToolRewroteKeepsItsKeyAndIdentity`; the whole chain on real files (real tag pass after a rebuild) only by hand in the week, item 12. Not asserted after a rebuild: the last played time behind Recently played (mirrored from the same play events, `UserMirrorTest#recentlyPlayedAndMostPlayedListTheVisibleSongsWithPlays`). On the phone with real data: in the week | 15.11.2026 |
| D2 | Favorites, playlists and statistics survive a deletion of the library database | T: `UserDataSurvivesRebuildTest#userDataSurvivesADeletionOfTheLibraryDatabase`, `PlaylistsThroughTheLibraryTest#entriesSurviveADeletionOfTheLibraryDatabase` (the database file is deleted and the library is opened again as a new process would open it and syncs; the test then writes each file's content key as the tag pass would and runs the attach pass) | pass: CI run 43670904476. Same assertions as D1; the user data comes back as the keys come back. That the real tagger gives a file the same key again is shown by `IdentityThroughTaggerTest#aMovedOggFileWhoseCommentsAnotherToolRewroteKeepsItsKeyAndIdentity`; the whole chain on real files (real tag pass after a rebuild) only by hand in the week, item 12 | 15.11.2026 |
| E1 | The legacy-encoding fixtures show readable text, automatically and with the manual choice (PIM-56) | T, real files through the real `:tagger`: `TagPassThroughTaggerTest#theWindows1251FixturesReadInTheAutomaticModeAndWithTheirOwnCodePage` (ID3v1, ID3v2.3 and RIFF INFO), `#aKoi8rFixtureNeedsTheManualChoice`, `#aShiftJisFixtureReadsWithTheManualChoice`, `#aWrongManualChoiceIsVisibleAndAutomaticBringsTheTextBackWithoutARead`, `#aLegacyFileReachesTheListsDecoded`; `LegacyEncodingLibraryTest#aChoiceMadeInTheSettingsDecodesTheLibraryAgainWithoutARead`; `LegacyEncodingChoiceTest` (the choice on the Settings screen); `LegacyEncodingsDeviceTest#everyCodePageOfTheListIsSupportedAndDecodesItsSample`; `PlaylistsThroughTheLibraryTest#aLegacyM3uFromTheFilePickerReads`; `TagReaderLegacyTest` (nativelib). J: `LegacyTextTest`, `LegacyEncodingsTest` | pass: CI run 43670904476 and JVM. Automatic mode reads the Windows-1251 fixtures when the device-language check answers Cyrillic (injected in `TagPassThroughTaggerTest`; the check of Russian, Belarusian and Ukrainian is in `LegacyTextTest`); KOI8-R and Shift JIS need the manual choice, which decodes again from the stored raw tags without reading a file. The owner's own legacy files: in the week | 15.11.2026 |
| O1 | The opened-file tests wait for events instead of a fixed 10 s (handed over by M2a, PIM-24) | T: the opened-file and restore tests of `PlaybackRestoreTest` (among them `#aFileOpenedFromAnotherAppGoesRightAfterTheCurrentSong`, which failed on API 36 at the close of M2a) and `PlaybackSessionCallbackTest` (`#openedFilesGoRightAfterTheCurrentSongAndTheFirstOnePlays` and the other opened-file tests), through the `Expectation` helper | pass: green on API 30 and API 36 in the CI of every M2b step and in run 43670904476. Before the change, CI run 43577409091 had 9 timeouts on API 30 | 15.11.2026 |
| S1/S2 | "ежик" finds "Ёжик", "Beyonce" finds "Beyoncé", "acdc" finds "AC/DC" | J: `SearchTermsTest`, `TextNormalizerTest`, `NormalizerSameResultTest`; T: `LibraryListsTest` | pass: CI run 43670904476 and JVM | 15.11.2026 |
| S3 | Search answers its first page within 50 ms (95th percentile) | M: `GeneratedLibraryTest#searchAsYouTypeOnTheRealSchemaAnswersWithinFiftyMilliseconds`, `LibrarySearchSpikeTest#searchAsYouTypeAnswersWithinFiftyMillisecondsOnThePlatformSqlite` | API 30: pass. Both passed in the quiet pass on `66f32f2`; the spike's slowest p95 on the platform SQLite was 42 ms with LIKE and 16 ms with INSTR (the real-schema p95 values went to the log and were not written down). S24: pass, real schema slowest p95 33 ms over three passes, the spike's slowest p95 33 ms with LIKE and 16 ms with INSTR (M2a 32 to 44 ms) | 15.11.2026 |
| Q1 | Long queues and playlists stay fast: a 20 000-song queue, its edits, the selection's removal and undo, a playlist's undo | M: `PlaybackQueueTest#aQueueOf20000SongsStartsWithinASecond` (limit 1 s, judged on the phone), `QueueEditTimingTest#editsOfA20000SongQueueArriveWithinAQuarterOfASecond` (250 ms), `QueueEditTimingTest#selectedEntriesOfA20000SongQueueAreRemovedAndPutBackInTime` (1 000 scattered 500 ms, all but one and undo 1 s), `QueueNavigationTest#turningShuffleOnInAQueueOf20000SongsIsQuick` (250 ms), `PlaylistsTest#thousandScatteredEntriesGoBackIntoATwentyThousandEntryPlaylistWithinASecond` (1 s) | API 30: pass. Queue of 20 000: median 659 ms (logged only on emulators). Edits: add 75 ms, remove 58 ms, move 57 ms. Removing 1 000 scattered entries: median 452 ms against 500 ms, little headroom; all but one 109 ms; undo 146 ms. Shuffle 150 ms. 1 000 of 20 000 playlist entries put back: 197 to 225 ms. S24: pass. Queue of 20 000: median 471 ms (M2a 404 to 451 ms, limit 1 s); add 115 ms, remove 54 ms, move 61 ms; 1 000 scattered 161 ms, all but one 125 ms, undo 116 ms; shuffle 115 ms; playlist entries put back 168 to 228 ms | 15.11.2026 |
| U1 | The M2b build installs over the M2a build and keeps the user data | T: `UserDatabaseTest#versionThreeMigratesToFourKeepingItsDataAndCopyingTheCounterIntoTheRulesCounter`, `#versionFourMigratesToFiveKeepingItsDataAndAddingPlaylists` | pass by tests: CI run 43670904476. library.db is now version 12 and is rebuilt by a full first sync on an installed build. H on the S24, 15.11.2026: `29fcf59` installed over the M2a build of 08.11 (`installDebug`, no uninstall); after the first start `user.db` went from version 3 to 5 and kept its 104 track identities. The phone had no own cover or folder rule left to compare (earlier instrumented runs emptied them; the owner skipped setting new ones) | 15.11.2026 |
| B1 | Browsing from a controller: the root Songs, Albums, Artists and More; Playlists (with the smart lists), Genres and Folders under More; voice requests for a genre or a playlist | T: `LibraryTreeCategoriesTest` (all 12 tests), `PlaybackServiceTest#theLibraryRootHoldsFourNodesWithMoreLast`, `LibraryTreeTest`, `LibraryTreePlayTest`; J: `VoiceMatchTest`, `TreeNodeTest`; M: `GeneratedLibraryTest#theControllerTreeAnswersTheSongsRootAndABucketWithin200MillisecondsOnThePhone` | pass with test controllers: CI run 43670904476; the tree timing passed its emulator limit of 300 ms in the quiet pass; on the S24 the Songs root took a median 128 ms and a cold bucket 154 ms (limit 200 ms). A real Android Auto head unit or the DHU, a Bluetooth head unit and Wear: pending. Known: an empty user playlist is listed under Playlists and opens empty, as in the app's tab | 15.11.2026 |
| K3 | M1 still holds: playback resumes from the lock screen's play after a kill, also when the saved position is at the end of a song (PIM-68) | T: `PlaybackRestoreTest#aPlayRequestOnAnEmptyPlayerRebuildsTheSavedQueueAndStartsIt`, `#aSongPlayedToItsEndAndStoppedIsSavedAtItsStartWithoutProgress`, `#aSavedPositionAtTheEndOfTheOnlyOpenedFilePlaysItFromItsStartOnASystemPlayRequest`, `#aPositionAtTheEndOfASongOfUnknownLengthPlaysItFromItsStartOnceThePlayerKnowsTheLength`, `RestoredEndCheckTest`; H: on the phone | by tests (CI run 43670904476): a system play request on an empty player rebuilds the saved queue; at the end of an only song it plays that song from its start. Moving on to the next song is tested at app start, paused (`#aPositionAtTheEndOfASongWithANextOneOpensTheAppPausedAtTheStartOfTheNextSong`, `RestoredEndCheckTest#aPositionAtTheEndMovesOnToTheStartOfTheNextSongAndStaysPaused`); the next song on a lock-screen play only by hand. By hand on the S24, 15.11.2026: pass. Paused, swiped away (One UI killed the process itself), play on the lock screen: the same song from the saved position. Paused 1 to 2 s before the end of a song with a next one, process dead, play: the next song from 0:00. The last song of the queue played to its end with repeat off, process killed, play: the same song from 0:00 | 15.11.2026 |
| V1 | Media3 on a stable 1.12, or the owner's exception | Version check of the Maven metadata | exception, carried over from M2a: the build uses 1.12.0-alpha02 (`gradle/libs.versions.toml`). Checked again on 15.11.2026: Google Maven has 1.12.0-beta01, still no release candidate or stable 1.12. Decision: the build stays on 1.12.0-alpha02 through the daily-use week, so that the week tests the build on the phone; beta01 is tried as a separate step at the start of M3 with an A/B `GaplessTest`. To be checked again at release: the move to a stable 1.12 is a separate commit with an A/B `GaplessTest`, required before the first public 0.x | 15.11.2026 |
| W1 | A week as the owner's daily player with real music, no open blocker (PIM-2) | H | accepted as they are, owner 17.11.2026 | 17.11.2026 |
| R2 | C2 with a real phone call, and the manual items of M1 steps 3 to 7 (PIM-8) | H | accepted as they are, owner 17.11.2026 | 17.11.2026 |

## Features of M2b

Each feature of the M2b list in features.md and each item M2a handed over, with the tests that cover it. Every
instrumented class named here passed on API 30 and API 36 in CI run 43670904476, and every JVM class among the 1442 JVM
tests of `66f32f2`, unless the row says otherwise; the `@TimingTest` methods ran in the quiet pass of 15.11.2026.

| Feature | Spec | Tests |
|---|---|---|
| Faster first sync of 20 000 files (PIM-13) | library.md §1.7 | J: `SqlRowsTest`; T: `BulkInsertTest`, `LibrarySyncTest` (`#aFirstSyncWhoseResolvingFailsWritesNothing`, `#aFirstSyncWhoseWriteFailsWritesNothing`); M: `GeneratedLibraryTest` |
| Opened-file tests wait for events (PIM-24) | playback.md §4.2 to §4.4, §5 | T: `PlaybackRestoreTest`, `PlaybackSessionCallbackTest`, `PlaybackStateStoreTest` |
| Favorite tracks and albums (PIM-43) | library.md §15.1, §15.2, §15.4 | J: `CounterChangesTest`; T: `UserMirrorTest`, `FavoritesScreenTest`, `UserDatabaseTest`, `UserDataSurvivesRebuildTest` |
| Album references (favorite, own cover) follow their tracks to a new album key (PIM-66) | library.md §15.3 | J: `AlbumRelinkTest`; T: `UserMirrorTest` (`#anAlbumWhoseFolderMovedTakesItsFavoriteAndCoverAlong`, `#mostOfAnAlbumMovedTakesItsFavoriteAndCoverAlongAlsoWhenTheRestStays`, `#anAlbumSplitInTwoHalvesKeepsItsReferences`, `#twoAlbumsThatBecomeOneKeepOneFavoriteAndTheCoverSetLast`, `#aCoverMoveThatFailsIsTriedAgainByTheNextRun`) |
| Play statistics: counted after half the song or 4 minutes of real listening, not under 30 s (PIM-49) | library.md §15.5, playback.md §11 | J: `PlayCounterTest` (half, 4 minutes, under 30 s, pauses, seeks, unknown length, resumed playback); T: `PlayTrackerTest`, `LibraryPlayRecorderTest`, `UserMirrorTest#recentlyPlayedAndMostPlayedListTheVisibleSongsWithPlays`, `SmartListsTest#recentlyPlayedAndMostPlayedHoldTheVisibleSongsWithCountedPlays`. No test plays a library song through the service until it lands in `user.db` (the fixtures are shorter than 30 s): checked by hand in the week |
| Manual choice of the legacy encoding (PIM-56) | library.md §5.7, TI §3.6 | J: `LegacyEncodingsTest`, `LegacyTextTest`; T: `LegacyEncodingChoiceTest`, `LegacyEncodingSettingTest`, `LegacyEncodingLibraryTest`, `LegacyEncodingsDeviceTest`, `TagPassTest` (`#aChangedChoiceDecodesOnlyTheLegacyFilesAgainAndReadsNoFile`, `#decodingAgainAsksTheGateBeforeEachBatch`, `#albumsArtistsAndGenresFollowTheTextDecodedAgain`), `TagPassThroughTaggerTest` |
| Playlists with resolution hints, M3U8 import and export (PIM-31) | library.md §16, TI §3.1 | J: `PlaylistHintsTest`, `M3uTest`, `TakeoverTest`, `PlaylistRestorePlanTest`; T: `PlaylistsTest`, `PlaylistsScreenTest`, `PlaylistsThroughTheLibraryTest` (`#anExportImportedAgainOnTheDeviceGivesTheSameSongs`, `#step5BringsBackAnEntryWhoseFileChangedItsAudioAndMoved`), `IdentityAttacherTest#theFingerprintStepComesBeforeThePlaylistHints` |
| A restored position at the end of a song goes where the player would have gone (PIM-68) | playback.md §4.2, §4.3.1 | J: `QueueRestoreTest`; T: `PlaybackRestoreTest` (`#aSongPlayedToItsEndAndStoppedIsSavedAtItsStartWithoutProgress`, `#aPositionAtTheEndOfASongWithANextOneOpensTheAppPausedAtTheStartOfTheNextSong`, `#aPositionAtTheEndOfASongOfUnknownLengthPlaysItFromItsStartOnceThePlayerKnowsTheLength`, `#aPositionPastAnUnderstatedLibraryLengthOpensTheAppPausedThere`), `RestoredEndCheckTest`, `PlayTrackerTest#aRestartIsANewPlaybackOfTheSameSong` |
| Smart lists: Favorites, Recently added, Recently played, Most played, Songs missing information (PIM-37) | library.md §17 | J: `MissingReasonsTest`, `SmartListCountsTest`, `SmartListHeaderTest`; T: `SmartListsTest`, `SmartListsScreenTest`, `SmartListPlaybackTest`, `TagPassTest#aFileThatCouldNotBeOpenedHasTheReasonsOfItsIndexValuesUntilALaterReadReplacesThem` |
| Track details (PIM-61) | library.md §18 | J: `TrackDetailsBuilderTest`, `DetailFormatTest`, `DetailsStatesTest`; T: `LibraryTrackDetailsTest`, `TrackDetailsScreenTest`, `OpenedFileDetailsTest`, `OpenedFileDetailsScreenTest` |
| One track menu everywhere (PIM-65) | playback.md §9.1 | J: `TrackMenuTest`; T: `TrackDetailsScreenTest` (`#theAlbumPageSongMenuHasDetails`, `#aPlaylistEntryHasTheWholeTrackMenu`, `#aQueueRowHasTheTrackMenu`, `#nowPlayingsMoreOptionsHaveTheMenuWithoutTheQueueEntries`) |
| Genres tab and pages, Playlists category, More in the controller tree (PIM-64) | library.md §19, playback.md §10 | J: `GenreKeysTest`, `GenreValuesTest`, `GenreOrderTest`, `ListSortsTest`, `VoiceMatchTest`; T: `GenreListsTest`, `GenresScreenTest`, `GenrePlaybackTest`, `LibraryRebuildTest#aRebuildGivesTheSameGenres`, `TagPassTest#displayGenresFollowTheTextDecodedAgain`, `LibraryTreeCategoriesTest`, `PlaybackServiceTest#theLibraryRootHoldsFourNodesWithMoreLast` |
| Multi-select of songs in lists, on the playlist page and in the queue (PIM-63) | library.md §20, playback.md §9.6 | J: `SelectionTest`, `TrackMenuTest` (the selection cases), `QueueSelectionKeysTest`, `FavoriteBatchResultTest`, `PlaylistRestorePlanTest`; T: `SelectionContentTest`, `SelectionScreenTest`, `SelectionRestoreTest` (Songs only), `QueueScreenTest#selectedEntriesHaveNoHandleNoSwipeAndNoMovesAndAreRemovedTogetherWithOneUndo`, `QueueEditingTest` (`#scatteredEntriesAreRemovedAtOnceAndOneUndoPutsThemAllBack`, `#entriesInManyRunsGoAroundThePlayingOneAndComeBackInTheirPlayOrder`, `#selectedSongsPlayAsAQueueOfAddedEntriesInTheirOrder`), `UserDatabaseTest` (`#severalFavoritesAreSetInOneTransactionThatRaisesTheChangeCounterOnce`, `#copiesThatShareOneIdentityCountAsTheSongsSelected`), `PlaylistsTest#selectedEntriesAreRemovedTogetherWithoutGapsAndPutBackAtTheirPlaces`; M: `QueueEditTimingTest#selectedEntriesOfA20000SongQueueAreRemovedAndPutBackInTime`, `PlaylistsTest#thousandScatteredEntriesGoBackIntoATwentyThousandEntryPlaylistWithinASecond`. Predictive back on API 36 and TalkBack: by hand in the week |

## Open for the daily-use week

The hand checks that close W1, R2, K3 and the S24 rows, on the S24 Ultra with the final M2b debug build. The order
matters: the measurements empty the debug build's user data, so they come first.

1. U1: done 15.11.2026 (see the U1 row).
2. L1, L1-later, S3, Q1 and the tree timing of B1 on the phone: done 15.11.2026 (see the rows). Next:
   `Music/PiPlayerTest20k` is excluded in the library folders before the week (the timing pass emptied the debug
   build's user data, folder rules included), so that the generated files stay out of the week.
3. W1: a week as the only music player with the owner's own music, with no open blocker at its end.
4. R2, C2: a real incoming call while music plays: paused during the call, playing the same song after it.
5. R2, the by-hand items of M1 steps 3 to 7 (playback.md §3.9, §4.7, §5.5, §6.8 and §7.4):
   - step 3: the notification, the lock screen and the system media controls with the shuffle and repeat buttons (are
     the dimmed icons readable?), the cover there and on a Bluetooth device, the predictive back gesture from Now
     Playing to the list, the mini player above the gesture bar and above the three-button bar, TalkBack on Now Playing
     and the mini player (is the seek bar's state read too often while it plays?), and a large cover (3000x3000) in the
     profiler;
   - step 4: the app opened after a force stop shows the paused queue, play from the lock screen and from a Bluetooth
     device, the headset buttons after process death, and a foreign start of the service sent with `adb` while
     playback is unaffected (15.11.2026: an unknown action and a media button without a key, sent with
     `am start-foreground-service` and `am startservice`, left playback PLAYING and the process running; the same
     unknown action while paused caused no crash or exit);
   - step 5: "Open with" from Files while a song plays, several files at once, a file while PiPlayer is in the
     background, a force stop and reopening, and a file whose temporary grant has expired;
   - step 6: a 5-minute sleep timer with the screen off, its fade by ear also during a navigation prompt and over
     Bluetooth, "Stop after current", and the Equalizer button opening the system panel (SoundAlive);
   - step 7: the crash dialog on the next start after the end-to-end crash tests (`-e crashTests true`, run by the
     assistant), and Share to mail, Telegram and Files. 15.11.2026: `CrashEndToEndTest` 2 of 2 on the S24; the dialog
     came up on the next start and Share to Gmail sent "PiPlayer crash report" with both reports in the body and
     `piplayer-crash-report.txt` attached. Telegram and Files: still to try.
6. K3: done 15.11.2026 (see the K3 row).
7. K3 with PIM-68: done 15.11.2026, both cases (see the K3 row).
8. Own cover chosen through the system photo picker for a song and for an album (the picker does not run in tests):
   shown in the lists, on Now Playing and in the notification; "Remove own cover" brings the old one back.
9. Track details and the track menu from a list, an album page, a playlist, the queue, Now Playing (More) and a file
   opened from another app (Details only on Now Playing, Details and "Remove from queue" in the queue); the MusicBrainz
   link, on a song whose tags carry an MBID, opens in the browser; a long press copies a value.
10. Multi-select on the Songs tab, an album page, a playlist and the queue, with the predictive back gesture (back ends
    selection first) and with TalkBack (Select action, checkbox state, the count read out).
11. Statistics: a song listened past half (or 4 minutes) raises its play count in Details and appears first in Recently
    played; a song skipped early or seeked through to the end does not count.
12. Favorites, favorite albums and playlists on real music; Settings > Rebuild the library on the phone, after which they
    come back once the tag pass has read the files (D1 on real data); M3U8 export and import of a playlist.
13. Genres tab on real tags: songs with two genres in both, "Unknown genre" last, a genre page plays from a row.
14. Legacy encodings on the owner's own old files, if any: readable automatically, or after a manual choice.
15. B1 on real controllers, as far as at hand: Android Auto (car or DHU) with the root Songs, Albums, Artists, More and
    Playlists, Genres and Folders under More, a voice request for a genre; a Bluetooth head unit; Wear.
16. V1: check the Media3 Maven metadata again at closing and record the date.

Decisions the owner may want to revisit in the week (recorded in the specification): an empty user playlist is listed in
the Playlists tab and under Playlists in the car and opens empty; undoing the removal of the current last queue entry
brings it back paused (playback.md §9.6); selecting albums, artists, genres, folders or playlists, and selecting a range,
are not part of M2b.

Known limitations (not blockers): the playlist dialogs close when the screen rotates; a horizontal swipe on a row during
selection acts as a tap; an image in the album's folder does not count as a cover for the "No cover" reason of Songs
missing information until M4a.
