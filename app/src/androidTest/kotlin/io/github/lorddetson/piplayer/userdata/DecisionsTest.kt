package io.github.lorddetson.piplayer.userdata

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.identification.contract.LicenseClass
import io.github.lorddetson.piplayer.identification.provider.ProviderId
import io.github.lorddetson.piplayer.library.identity.Hex
import io.github.lorddetson.piplayer.library.lists.MissingInfoDecision
import io.github.lorddetson.piplayer.library.values.Decided
import io.github.lorddetson.piplayer.library.values.DecidedField
import io.github.lorddetson.piplayer.library.values.DecisionKind
import io.github.lorddetson.piplayer.library.values.DecisionResolver
import io.github.lorddetson.piplayer.settings.ProtectedField
import io.github.lorddetson.piplayer.userdata.DecisionFixtures.details
import io.github.lorddetson.piplayer.userdata.DecisionFixtures.request
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** The decision repository on the real user database (TI §7.1, §10.2; §11.2 M3 "User database version 6"). */
@RunWith(AndroidJUnit4::class)
class DecisionsTest {

    private val context = TestFiles.targetContext
    private val database = UserDatabase.open(context, name = null)
    private var now = 1_800_000_000_000L
    private val decisions = Decisions(database) { now++ }
    private val dao = database.decisions()

    @After
    fun close() = database.close()

    private fun key(n: Long) = "%064x".format(n)

    private suspend fun identity(n: Long): Long = database.identities().add(
        TrackIdentityEntity(
            contentKey = Hex.bytes(key(n)), durationMs = 200_000, recordingMbid = null, hintVolumeName = "external_primary",
            hintRelativePath = "Music/", hintDisplayName = "$n.mp3", hintSize = 1_000, createdAt = 1, missingSince = null,
        ),
    )

    @Test
    fun applyStoresTheTickedFieldsWithTheirProvenanceAndRaisesTheCounterInItsTransaction() = runBlocking<Unit> {
        val identity = identity(1)
        val before = database.user().changeCounter()

        val committed = checkNotNull(decisions.apply(request(key(1))))

        assertThat(committed.identityId).isEqualTo(identity)
        assertThat(committed.counter).isEqualTo(before + 1).isEqualTo(database.user().changeCounter())
        val row = checkNotNull(dao.decision(committed.decisionId))
        assertThat(row.kind).isEqualTo(DecisionKind.APPLY.code)
        assertThat(DecisionCodec.fields(row.fields)).isEqualTo(ProtectedField.entries.toSet() - ProtectedField.COVER)
        assertThat(DecisionCodec.provider(row.provider)).isEqualTo(ProviderId.MUSICBRAINZ)
        assertThat(DecisionCodec.license(row.licenseClass)).isEqualTo(LicenseClass.CC0)
        assertThat(row.confidence).isEqualTo(965)
        assertThat(DecisionCodec.reason(row.reason)).isEqualTo(DecisionFixtures.reason)
        assertThat(Hex.mbidText(row.recordingMbid)).isEqualTo(details().recordingId)
        assertThat(Hex.mbidText(row.releaseTrackMbid)).isEqualTo(details().trackId)
        assertThat(Hex.mbidText(row.acoustIdId)).isEqualTo(DecisionFixtures.mbid(7, 0xac0))
        assertThat(row.fetchedAt).isEqualTo(1_800_000_000_000L)
        // The per-track parts as rows, the release-level ones in the release choice.
        assertThat(dao.metadataOf(listOf(committed.decisionId)).map { DecidedField.ofCode(it.field) }).containsExactlyInAnyOrder(
            DecidedField.TITLE, DecidedField.ARTIST, DecidedField.ARTIST_CREDITS, DecidedField.ARTIST_SORT,
            DecidedField.TRACK_NUMBER, DecidedField.DISC_NUMBER, DecidedField.ISRC, DecidedField.ARTIST_IDS,
        )
        assertThat(row.releaseChoiceId).isNotNull()

        val decided = decisions.decidedOf(listOf(identity)).getValue(identity)
        assertThat(decided[DecidedField.TITLE]).isEqualTo(Decided("Song", DecisionKind.LEVEL_IDENTIFICATION, committed.decisionId))
        assertThat(decided[DecidedField.ALBUM]?.value).isEqualTo("Record")
        assertThat(decided[DecidedField.TRACK_TOTAL]?.value).isEqualTo("9")
        assertThat(decided[DecidedField.RECORDING_MBID]?.value).isEqualTo(details().recordingId)
        assertThat(decided[DecidedField.RELEASE_MBID]?.value).isEqualTo(details().releaseId)
        assertThat(decided[DecidedField.LABEL]?.value).isEqualTo("Label")
        assertThat(decided).doesNotContainKey(DecidedField.COVER)
    }

    @Test
    fun onlyTheTickedFieldsAreDecided() = runBlocking<Unit> {
        val identity = identity(1)

        decisions.apply(request(key(1), fields = setOf(ProtectedField.TITLE, ProtectedField.ALBUM)))

        assertThat(decisions.decidedOf(listOf(identity)).getValue(identity).keys)
            .containsExactlyInAnyOrder(DecidedField.TITLE, DecidedField.ALBUM)
    }

    @Test
    fun aTargetWithoutAnIdentityWritesNothing() = runBlocking<Unit> {
        val before = database.user().changeCounter()

        assertThat(decisions.apply(request(key(9)))).isNull()

        assertThat(database.user().changeCounter()).isEqualTo(before)
        assertThat(dao.liveValueDecisions()).isEmpty()
    }

    @Test
    fun twoIdentificationsOfOneReleaseWithTheSameValuesShareOneReleaseChoice() = runBlocking<Unit> {
        identity(1)
        identity(2)
        identity(3)
        identity(4)

        val first = checkNotNull(decisions.apply(request(key(1), details(recording = 1, track = 1))))
        val second = checkNotNull(decisions.apply(request(key(2), details(recording = 2, track = 2))))
        // Another disc of the same release joins the row.
        val third = checkNotNull(decisions.apply(request(key(3), details(recording = 3, disc = 2, trackTotal = 5, discSubtitle = "Live"))))
        // Other values of the same release make a row of their own.
        val fourth = checkNotNull(decisions.apply(request(key(4), details(recording = 4, album = "Record (Remastered)"))))

        val choices = listOf(first, second, third, fourth).map { checkNotNull(dao.decision(it.decisionId)).releaseChoiceId }
        assertThat(choices[0]).isEqualTo(choices[1]).isEqualTo(choices[2])
        assertThat(choices[3]).isNotEqualTo(choices[0])
        assertThat(dao.releaseChoiceCount()).isEqualTo(2)
        val third3 = decisions.decidedOf(listOf(3L)).getValue(3L)
        assertThat(third3[DecidedField.TRACK_TOTAL]?.value).isEqualTo("5")
        assertThat(third3[DecidedField.DISC_SUBTITLE]?.value).isEqualTo("Live")
        assertThat(decisions.decidedOf(listOf(1L)).getValue(1L)[DecidedField.TRACK_TOTAL]?.value).isEqualTo("9")
    }

    @Test
    fun anEditStoresEveryPartOfTheFieldsItTouches() = runBlocking<Unit> {
        val identity = identity(1)
        decisions.apply(request(key(1)))

        val edit = checkNotNull(decisions.edit(identity, mapOf(DecidedField.ARTIST to "Me", DecidedField.ALBUM to null)))

        val decided = decisions.decidedOf(listOf(identity)).getValue(identity)
        assertThat(decided[DecidedField.ARTIST]).isEqualTo(Decided("Me", DecisionKind.LEVEL_USER_EDIT, edit.decisionId))
        assertThat(decided[DecidedField.ARTIST_SORT]).isEqualTo(Decided(null, DecisionKind.LEVEL_USER_EDIT, edit.decisionId))
        assertThat(decided[DecidedField.ALBUM]).isEqualTo(Decided(null, DecisionKind.LEVEL_USER_EDIT, edit.decisionId))
        assertThat(decided[DecidedField.TITLE]?.level).isEqualTo(DecisionKind.LEVEL_IDENTIFICATION)
        assertThat(dao.metadataOf(listOf(edit.decisionId)).map { DecidedField.ofCode(it.field) }).containsExactlyInAnyOrder(
            DecidedField.ARTIST, DecidedField.ARTIST_CREDITS, DecidedField.ARTIST_SORT, DecidedField.ALBUM,
        )
    }

    @Test
    fun aRevertHidesTheFieldAndItsUndoBringsItBack() = runBlocking<Unit> {
        val identity = identity(1)
        decisions.apply(request(key(1)))

        val revert = checkNotNull(decisions.revert(identity, setOf(ProtectedField.TITLE)))
        assertThat(decisions.decidedOf(listOf(identity)).getValue(identity)).doesNotContainKey(DecidedField.TITLE)

        decisions.undo(revert.decisionId)
        assertThat(decisions.decidedOf(listOf(identity)).getValue(identity)[DecidedField.TITLE]?.value).isEqualTo("Song")
    }

    @Test
    fun undoMarksTheDecisionUndoneOnceAndRaisesTheCounter() = runBlocking<Unit> {
        val identity = identity(1)
        val applied = checkNotNull(decisions.apply(request(key(1))))
        val counter = database.user().changeCounter()

        val undone = checkNotNull(decisions.undo(applied.decisionId))

        assertThat(undone.identityId).isEqualTo(identity)
        assertThat(undone.counter).isEqualTo(counter + 1)
        assertThat(dao.decision(applied.decisionId)?.undoneAt).isNotNull()
        assertThat(decisions.decidedOf(listOf(identity))).isEmpty()
        // A second undo changes nothing.
        assertThat(decisions.undo(applied.decisionId)).isNull()
        assertThat(decisions.undo(12_345)).isNull()
        assertThat(database.user().changeCounter()).isEqualTo(counter + 1)
        // The undone decision stays stored.
        assertThat(decisions.decisionsOf(identity).single().undone).isTrue()
    }

    @Test
    fun undoIdentificationUndoesTheNewestIdentificationThatIsNotUndone() = runBlocking<Unit> {
        val identity = identity(1)
        val older = checkNotNull(decisions.apply(request(key(1), details(title = "Older"))))
        val newer = checkNotNull(decisions.apply(request(key(1), details(title = "Newer"))))
        decisions.edit(identity, mapOf(DecidedField.ALBUM to "Mine"))

        assertThat(decisions.newestIdentification(identity)).isEqualTo(newer.decisionId)
        decisions.undo(newer.decisionId)
        assertThat(decisions.newestIdentification(identity)).isEqualTo(older.decisionId)
        assertThat(decisions.decidedOf(listOf(identity)).getValue(identity)[DecidedField.TITLE]?.value).isEqualTo("Older")
    }

    @Test
    fun decisionsAndFileStatesKeepTheirIdentitiesHeld() = runBlocking<Unit> {
        val decided = identity(1)
        val undone = identity(2)
        val flagged = identity(3)
        identity(4)
        decisions.apply(request(key(1)))
        decisions.undo(checkNotNull(decisions.apply(request(key(2)))).decisionId)
        // A file state alone holds its identity too.
        dao.saveFileStates(
            listOf(FileStateEntity(flagged, 99, "external_primary", "Music/", "3.mp3", DecidedField.TITLE.code, null, true)),
        )

        assertThat(database.user().identitiesHoldingData()).containsExactlyInAnyOrder(decided, undone, flagged)
    }

    @Test
    fun aChangedFieldIsFlaggedPerLocationUntilThePresentValueIsKept() = runBlocking<Unit> {
        val identity = identity(1)
        val here = Decisions.LocationValues("external_primary", "Music/", "1.mp3", mapOf(DecidedField.TITLE to "track01", DecidedField.ARTIST to "Ann"))
        val copy = Decisions.LocationValues("external_primary", "Copy/", "1.mp3", mapOf(DecidedField.TITLE to "copy title", DecidedField.ARTIST to "Ann"))
        decisions.apply(request(key(1), fields = setOf(ProtectedField.TITLE, ProtectedField.ARTIST)), atDecision = listOf(here, copy))

        // Unchanged at both places, and at a new place with the decided location's values.
        assertThat(decisions.fileChanged(identity, here)).isEmpty()
        assertThat(decisions.fileChanged(identity, copy)).isEmpty()
        assertThat(decisions.fileChanged(identity, here.copy(relativePath = "Moved/"))).isEmpty()

        // A tagger changed the title of the copy only.
        val changedCopy = copy.copy(values = copy.values + (DecidedField.TITLE to "retagged"))
        assertThat(decisions.fileChanged(identity, changedCopy)).containsExactly(DecidedField.TITLE)
        assertThat(decisions.fileChanged(identity, here)).isEmpty()
        assertThat(dao.fileStates(identity).single { it.relativePath == "Copy/" && it.field == DecidedField.TITLE.code }.changedInFile).isTrue()

        decisions.keepPiPlayersValue(identity, changedCopy, setOf(DecidedField.TITLE))
        assertThat(decisions.fileChanged(identity, changedCopy)).isEmpty()
    }

    @Test
    fun aDecisionMadeAfterTheClockWasSetBackStillWinsAndIsTheOneUndone() = runBlocking<Unit> {
        val identity = identity(1)
        decisions.apply(request(key(1), details(title = "Older")))
        // The clock is set back an hour: by hand, by a network time correction, or the older decision came from a backup
        // of a device whose clock ran ahead.
        now -= 3_600_000
        val newer = checkNotNull(decisions.apply(request(key(1), details(title = "Newer"))))

        assertThat(decisions.decidedOf(listOf(identity)).getValue(identity)[DecidedField.TITLE]?.value).isEqualTo("Newer")
        assertThat(decisions.newestIdentification(identity)).isEqualTo(newer.decisionId)
        assertThat(decisions.decisionsOf(identity).map { it.id }.last()).isEqualTo(newer.decisionId)
        decisions.undo(newer.decisionId)
        assertThat(decisions.decidedOf(listOf(identity)).getValue(identity)[DecidedField.TITLE]?.value).isEqualTo("Older")
    }

    /** The decided location and a copy whose title differs, both as the tag pass reads them. */
    private val here = Decisions.LocationValues(
        "external_primary", "Music/", "1.mp3", mapOf(DecidedField.TITLE to "track01", DecidedField.ARTIST to "Ann"),
    )
    private val copy = Decisions.LocationValues(
        "external_primary", "Copy/", "1.mp3", mapOf(DecidedField.TITLE to "copy title", DecidedField.ARTIST to "Ann"),
    )

    @Test
    fun undoingARevertGivesASecondLocationItsReferencesBack() = runBlocking<Unit> {
        val identity = identity(1)
        decisions.apply(request(key(1), fields = setOf(ProtectedField.TITLE, ProtectedField.ARTIST)), atDecision = listOf(here, copy))
        assertThat(decisions.fileChanged(identity, copy)).isEmpty()

        val revert = checkNotNull(decisions.revert(identity, setOf(ProtectedField.TITLE)))
        assertThat(decisions.fileChanged(identity, copy)).isEmpty()
        decisions.undo(revert.decisionId)

        // Neither file changed: the copy's title is the one it had at the identification.
        assertThat(decisions.fileChanged(identity, copy)).isEmpty()
        assertThat(decisions.fileChanged(identity, here)).isEmpty()
        // A real change is flagged again.
        assertThat(decisions.fileChanged(identity, copy.copy(values = copy.values + (DecidedField.TITLE to "retagged"))))
            .containsExactly(DecidedField.TITLE)
    }

    @Test
    fun undoingANewerDecisionGivesASecondLocationTheOlderOnesReferencesBack() = runBlocking<Unit> {
        val identity = identity(1)
        decisions.apply(request(key(1), fields = setOf(ProtectedField.TITLE, ProtectedField.ARTIST)), atDecision = listOf(here, copy))
        // An edit from a screen that knows no location values: it stores no references of its own.
        val edit = checkNotNull(decisions.edit(identity, mapOf(DecidedField.TITLE to "Mine")))

        decisions.undo(edit.decisionId)

        assertThat(decisions.fileChanged(identity, copy)).isEmpty()
        assertThat(decisions.fileChanged(identity, here)).isEmpty()
    }

    @Test
    fun aFlagUnderARevertComesBackWithItsUndo() = runBlocking<Unit> {
        val identity = identity(1)
        decisions.apply(request(key(1), fields = setOf(ProtectedField.TITLE, ProtectedField.ARTIST)), atDecision = listOf(here, copy))
        val retagged = copy.copy(values = copy.values + (DecidedField.TITLE to "retagged"))
        assertThat(decisions.fileChanged(identity, retagged)).containsExactly(DecidedField.TITLE)

        // "Use the file's value": nothing of the title is decided, so nothing of it is flagged.
        val revert = checkNotNull(decisions.revert(identity, setOf(ProtectedField.TITLE)))
        assertThat(decisions.fileChanged(identity, retagged)).isEmpty()

        decisions.undo(revert.decisionId)
        assertThat(decisions.fileChanged(identity, retagged)).containsExactly(DecidedField.TITLE)
    }

    @Test
    fun keepAsIsAndSkipStoreNoValuesRaiseTheCounterAndASkipKeepsTheFilesStamp() = runBlocking<Unit> {
        val identity = identity(1)
        val before = database.user().changeCounter()

        val kept = checkNotNull(decisions.keepAsIs(identity))
        val skipped = checkNotNull(decisions.skip(identity, fileSize = 5_000, fileMtimeMs = 1_700_000_000_000))

        assertThat(kept.counter).isEqualTo(before + 1)
        assertThat(skipped.counter).isEqualTo(before + 2).isEqualTo(database.user().changeCounter())
        with(checkNotNull(dao.decision(kept.decisionId))) {
            assertThat(kind).isEqualTo(DecisionKind.KEEP_AS_IS.code)
            assertThat(fields).isZero()
            assertThat(fileSize).isNull()
        }
        with(checkNotNull(dao.decision(skipped.decisionId))) {
            assertThat(kind).isEqualTo(DecisionKind.SKIP.code)
            assertThat(fields).isZero()
            assertThat(fileSize).isEqualTo(5_000)
            assertThat(fileMtime).isEqualTo(1_700_000_000_000)
            assertThat(lapsedAt).isNull()
        }
        assertThat(dao.metadataOf(listOf(kept.decisionId, skipped.decisionId))).isEmpty()
        // They decide no value, and the newer one counts.
        assertThat(decisions.decidedOf(listOf(identity))).isEmpty()
        assertThat(decisions.missingInfoOf(listOf(identity)).getValue(identity))
            .isEqualTo(Decided(MissingInfoDecision.SKIPPED_VALUE, DecisionResolver.MISSING_INFO_LEVEL, skipped.decisionId))
        // A decision is user data, so the identity stays.
        assertThat(database.user().identitiesHoldingData()).containsExactly(identity)
    }

    @Test
    fun keepAsIsAndSkipOfATrackThatHasNoIdentityNowWriteNothing() = runBlocking<Unit> {
        val identity = identity(1)
        val before = database.user().changeCounter()

        assertThat(decisions.keepAsIs(identity + 1)).isNull()
        assertThat(decisions.skip(identity + 1, 1, 1, contentKey = Hex.bytes(key(2)))).isNull()

        assertThat(database.user().changeCounter()).isEqualTo(before)
        assertThat(dao.decisionsOf(identity)).isEmpty()
    }

    @Test
    fun undoingTheNewerOfKeepAsIsAndSkipBringsTheOlderBack() = runBlocking<Unit> {
        val identity = identity(1)
        val kept = checkNotNull(decisions.keepAsIs(identity))
        val skipped = checkNotNull(decisions.skip(identity, 1, 1_000))

        decisions.undo(skipped.decisionId)
        assertThat(decisions.missingInfoOf(listOf(identity)).getValue(identity).decisionId).isEqualTo(kept.decisionId)

        decisions.undo(kept.decisionId)
        assertThat(decisions.missingInfoOf(listOf(identity))).isEmpty()
    }

    @Test
    fun aLapseMarksOnlyLiveSkipsAndRaisesTheCounterOnce() = runBlocking<Unit> {
        val first = identity(1)
        val second = identity(2)
        val kept = checkNotNull(decisions.keepAsIs(first))
        val skipped = checkNotNull(decisions.skip(first, 1, 1_000))
        val undone = checkNotNull(decisions.skip(second, 1, 1_000))
        decisions.undo(undone.decisionId)
        val other = checkNotNull(decisions.skip(second, 2, 2_000))
        assertThat(decisions.hasLiveSkips()).isTrue()
        val counter = database.user().changeCounter()

        val lapsed = checkNotNull(decisions.lapse(listOf(skipped.decisionId, kept.decisionId, undone.decisionId, other.decisionId)))

        assertThat(lapsed.counter).isEqualTo(counter + 1).isEqualTo(database.user().changeCounter())
        assertThat(lapsed.identityIds).containsExactlyInAnyOrder(first, second)
        assertThat(dao.decision(skipped.decisionId)?.lapsedAt).isNotNull()
        assertThat(dao.decision(other.decisionId)?.lapsedAt).isNotNull()
        // Keep as-is never lapses, and an undone Skip stays as it was.
        assertThat(dao.decision(kept.decisionId)?.lapsedAt).isNull()
        assertThat(dao.decision(undone.decisionId)?.lapsedAt).isNull()
        assertThat(decisions.missingInfoOf(listOf(first)).getValue(first).decisionId).isEqualTo(kept.decisionId)
        assertThat(decisions.missingInfoOf(listOf(second))).isEmpty()
        assertThat(decisions.hasLiveSkips()).isFalse()
        // A lapse with nothing left to lapse writes nothing.
        assertThat(decisions.lapse(listOf(skipped.decisionId, kept.decisionId))).isNull()
        assertThat(database.user().changeCounter()).isEqualTo(counter + 1)
    }

    @Test
    fun theLiveSkipsAreFoundByDecisionAndByIdentity() = runBlocking<Unit> {
        val first = identity(1)
        val second = identity(2)
        val third = identity(3)
        val a = checkNotNull(decisions.skip(first, 10, 10_000))
        val b = checkNotNull(decisions.skip(second, 20, 20_000))
        decisions.keepAsIs(third)
        val lapsed = checkNotNull(decisions.skip(third, 30, 30_000))
        decisions.lapse(listOf(lapsed.decisionId))

        val found = decisions.liveSkips(decisionIds = listOf(a.decisionId, lapsed.decisionId), identityIds = listOf(second, third))

        assertThat(found).containsExactlyInAnyOrder(
            Decisions.LiveSkip(a.decisionId, first, key(1), 10, 10_000),
            Decisions.LiveSkip(b.decisionId, second, key(2), 20, 20_000),
        )
    }

    @Test
    fun theFilesOfABatchAreComparedInOneCallAsOneByOne() = runBlocking<Unit> {
        val identity = identity(1)
        val other = identity(2)
        decisions.apply(request(key(1), fields = setOf(ProtectedField.TITLE, ProtectedField.ARTIST)), atDecision = listOf(here, copy))
        val retagged = copy.copy(values = copy.values + (DecidedField.TITLE to "retagged"))

        // The identity without decisions has nothing to compare.
        val flagged = decisions.filesChanged(listOf(identity to here, identity to retagged, other to here))

        assertThat(flagged).containsExactly(emptySet(), setOf(DecidedField.TITLE), emptySet())
        assertThat(dao.fileStates(identity).single { it.relativePath == "Copy/" && it.field == DecidedField.TITLE.code }.changedInFile).isTrue()
        assertThat(dao.fileStates(other)).isEmpty()
    }

    @Test
    fun theSnapshotHoldsTheKeepAsIsAndSkipOfEveryIdentityByKey() = runBlocking<Unit> {
        identity(1)
        identity(2)
        identity(3)
        decisions.apply(request(key(1)))
        val kept = checkNotNull(decisions.keepAsIs(2))
        val skipped = checkNotNull(decisions.skip(1, 1, 1_000))
        val lapsed = checkNotNull(decisions.skip(3, 1, 1_000))
        decisions.lapse(listOf(lapsed.decisionId))

        val snapshot = decisions.snapshot()

        assertThat(snapshot.byKey.keys).containsExactly(key(1))
        assertThat(snapshot.missingInfoByKey).isEqualTo(
            mapOf(
                key(1) to Decided(MissingInfoDecision.SKIPPED_VALUE, DecisionResolver.MISSING_INFO_LEVEL, skipped.decisionId),
                key(2) to Decided(MissingInfoDecision.KEEP_AS_IS_VALUE, DecisionResolver.MISSING_INFO_LEVEL, kept.decisionId),
            ),
        )
    }

    @Test
    fun theSnapshotHoldsEveryDecidedIdentityByKeyWithTheCounter() = runBlocking<Unit> {
        identity(1)
        identity(2)
        decisions.apply(request(key(1)))
        decisions.apply(request(key(2), details(title = "Two")))

        val snapshot = decisions.snapshot()

        assertThat(snapshot.counter).isEqualTo(database.user().changeCounter())
        assertThat(snapshot.byKey.keys).containsExactlyInAnyOrder(key(1), key(2))
        assertThat(snapshot.byKey.getValue(key(2))[DecidedField.TITLE]?.value).isEqualTo("Two")
    }
}
