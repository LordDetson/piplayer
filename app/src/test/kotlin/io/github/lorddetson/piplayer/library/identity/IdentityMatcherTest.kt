package io.github.lorddetson.piplayer.library.identity

import io.github.lorddetson.piplayer.library.identity.IdentityMatcher.KEEP_MISSING_MS
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Steps 1, 2, 3 and 5 of re-attachment and the life of a missing identity (TI §3.1, docs/library.md §8). */
class IdentityMatcherTest {

    private val now = 1_800_000_000_000L
    private val music = Place("external_primary", "Music/Album/", "01.mp3")
    private val moved = Place("external_primary", "Music/Other/", "01.mp3")

    private fun file(
        id: Long,
        key: String,
        place: Place = music,
        durationMs: Long? = 200_000,
        settled: Boolean = true,
        mbid: String? = null,
        format: String? = "mpeg",
    ) = KeyedFile(id, key, place, size = 5_000_000 + id, durationMs, settled, mbid, format)

    private fun identity(
        id: Long,
        key: String,
        hint: Place? = music,
        durationMs: Long? = 200_000,
        mbid: String? = null,
        missingSince: Long? = null,
        createdAt: Long = now - 100_000,
    ) = Identity(id, key, durationMs, mbid, hint, hintSize = 5_000_000, createdAt, missingSince)

    private fun plan(files: List<KeyedFile>, identities: List<Identity>, holding: Set<Long> = emptySet()) =
        IdentityMatcher.plan(files, identities, holding, now)

    @Test
    fun aFileWithoutAnIdentityGetsANewOne() {
        val result = plan(listOf(file(1, "aa")), emptyList())

        assertThat(result.created).containsExactly(file(1, "aa"))
        assertThat(result.updated).isEmpty()
        assertThat(result.deleted).isEmpty()
    }

    @Test
    fun anAttachedIdentityThatChangedNothingWritesNothing() {
        val f = file(1, "aa")
        val same = identity(7, "aa").following(f)

        assertThat(plan(listOf(f), listOf(same)).isEmpty).isTrue()
    }

    @Test
    fun step1TheKeyFindsAMovedFileAndTheHintsFollowIt() {
        val f = file(1, "aa", place = moved)
        val id = identity(7, "aa")

        val result = plan(listOf(f), listOf(id))

        assertThat(result.created).isEmpty()
        assertThat(result.updated).containsExactly(id.copy(hint = moved, hintSize = f.size))
    }

    @Test
    fun step1ClearsMissingSinceWhenTheFileComesBack() {
        val f = file(1, "aa")
        val id = identity(7, "aa", missingSince = now - 1_000).following(f).copy(missingSince = now - 1_000)

        assertThat(plan(listOf(f), listOf(id)).updated).containsExactly(id.copy(missingSince = null))
    }

    @Test
    fun byteIdenticalCopiesShareOneIdentityThatKeepsItsHint() {
        val copy = file(2, "aa", place = moved)
        val original = file(5, "aa")
        val id = identity(7, "aa").following(original)

        val result = plan(listOf(copy, original), listOf(id))

        assertThat(result.isEmpty).isTrue()
    }

    @Test
    fun copiesWithANewKeyGetOneIdentityNamedAfterTheLowestFile() {
        val result = plan(listOf(file(5, "bb"), file(2, "bb", place = moved)), emptyList())

        assertThat(result.created).containsExactly(file(2, "bb", place = moved))
    }

    @Test
    fun aCopyLeftAfterTheHintedFileWentTakesTheHint() {
        val left = file(2, "aa", place = moved)
        val id = identity(7, "aa")

        assertThat(plan(listOf(left), listOf(id)).updated).containsExactly(id.copy(hint = moved, hintSize = left.size))
    }

    @Test
    fun step2AFileWhoseKeyChangedInPlaceKeepsItsIdentity() {
        val f = file(1, "bb", durationMs = 200_400)
        val id = identity(7, "aa", missingSince = now - 1_000)

        val result = plan(listOf(f), listOf(id))

        assertThat(result.created).isEmpty()
        assertThat(result.updated).containsExactly(id.following(f))
    }

    @Test
    fun step2AnotherSongAtTheSamePlaceThreeSecondsOffIsNotAttached() {
        val f = file(1, "bb", durationMs = 203_000)
        val id = identity(7, "aa")

        val result = plan(listOf(f), listOf(id))

        assertThat(result.created).containsExactly(f)
        assertThat(result.updated).containsExactly(id.copy(missingSince = now))
    }

    @Test
    fun step2NeedsALengthOnBothSides() {
        val f = file(1, "bb", durationMs = null)
        val id = identity(7, "aa")

        assertThat(plan(listOf(f), listOf(id)).created).containsExactly(f)
    }

    @Test
    fun step2TheNewestOfTwoMissingIdentitiesTakesTheFile() {
        val f = file(1, "cc")
        val older = identity(3, "aa", missingSince = now - 5_000)
        val newer = identity(4, "bb", missingSince = now - 5_000)

        val result = plan(listOf(f), listOf(older, newer))

        assertThat(result.updated).containsExactly(newer.following(f))
        assertThat(result.created).isEmpty()
    }

    @Test
    fun step2ComesBeforeStep3() {
        val f = file(1, "cc", mbid = "m1")
        val byPlace = identity(3, "aa", missingSince = now - 5_000)
        val byMbid = identity(4, "bb", hint = moved, mbid = "m1", missingSince = now - 5_000)

        val result = plan(listOf(f), listOf(byPlace, byMbid))

        assertThat(result.updated).contains(byPlace.following(f).copy(recordingMbid = "m1"))
        assertThat(result.updated.map { it.id }).doesNotContain(byMbid.id).hasSize(1)
    }

    @Test
    fun step3TheOneUnattachedFileWithTheMbidIsAttached() {
        val f = file(1, "bb", place = moved, durationMs = 199_500, mbid = "m1")
        val id = identity(7, "aa", mbid = "m1", missingSince = now - 1_000)

        val result = plan(listOf(f), listOf(id))

        assertThat(result.updated).containsExactly(id.following(f))
        assertThat(result.created).isEmpty()
    }

    @Test
    fun step3TwoUnattachedFilesWithOneMbidAttachNeither() {
        val a = file(1, "bb", place = moved, mbid = "m1")
        val b = file(2, "cc", place = moved.copy(displayName = "02.mp3"), mbid = "m1")
        val id = identity(7, "aa", mbid = "m1", missingSince = now - 1_000)

        val result = plan(listOf(a, b), listOf(id))

        assertThat(result.created).containsExactlyInAnyOrder(a, b)
        assertThat(result.updated).isEmpty()
    }

    @Test
    fun step3TwoMissingIdentitiesWithOneMbidTakeNothing() {
        val f = file(1, "cc", place = moved, mbid = "m1")
        val a = identity(3, "aa", mbid = "m1", missingSince = now - 1_000)
        val b = identity(4, "bb", hint = moved.copy(displayName = "x.mp3"), mbid = "m1", missingSince = now - 1_000)

        assertThat(plan(listOf(f), listOf(a, b)).created).containsExactly(f)
    }

    @Test
    fun step3NeedsTheLengthWithinOneSecond() {
        val f = file(1, "bb", place = moved, durationMs = 201_500, mbid = "m1")
        val id = identity(7, "aa", mbid = "m1", missingSince = now - 1_000)

        assertThat(plan(listOf(f), listOf(id)).created).containsExactly(f)
    }

    @Test
    fun step3IgnoresAnAttachedFileWithTheMbid() {
        val attached = file(1, "aa", mbid = "m1")
        val holder = identity(5, "aa", mbid = "m1").following(attached)
        val missing = identity(7, "zz", hint = moved, mbid = "m1", missingSince = now - 1_000)

        val result = plan(listOf(attached), listOf(holder, missing))

        assertThat(result.isEmpty).isTrue()
    }

    @Test
    fun aFileWithAPendingLengthIsLeftAlone() {
        val f = file(1, "bb", settled = false)
        val id = identity(7, "aa", missingSince = now - 1_000)

        val result = plan(listOf(f), listOf(id))

        assertThat(result.isEmpty).isTrue()
    }

    @Test
    fun aPendingFileStillHoldsItsIdentitysKey() {
        val f = file(1, "aa", settled = false, durationMs = null)
        val id = identity(7, "aa").following(f)

        assertThat(id.durationMs).isEqualTo(200_000)
        assertThat(plan(listOf(f), listOf(id)).isEmpty).isTrue()
    }

    @Test
    fun anIdentityWhoseFileWentIsMarkedMissing() {
        val id = identity(7, "aa")

        assertThat(plan(emptyList(), listOf(id)).updated).containsExactly(id.copy(missingSince = now))
    }

    @Test
    fun aMissingIdentityWithoutUserDataIsDeletedAfterThirtyDays() {
        val young = identity(6, "aa", missingSince = now - KEEP_MISSING_MS + 1)
        val old = identity(7, "bb", missingSince = now - KEEP_MISSING_MS)

        val result = plan(emptyList(), listOf(young, old))

        assertThat(result.deleted).containsExactly(7)
        assertThat(result.updated).isEmpty()
    }

    @Test
    fun aMissingIdentityThatHoldsUserDataIsNeverDeleted() {
        val old = identity(7, "bb", missingSince = now - 10 * KEEP_MISSING_MS)

        assertThat(plan(emptyList(), listOf(old), holding = setOf(7L)).isEmpty).isTrue()
    }

    @Test
    fun theMbidFollowsTheTagsOfAReadFile() {
        val f = file(1, "aa", mbid = "m1")
        val id = identity(7, "aa", mbid = "m1").following(f)

        assertThat(plan(listOf(f.copy(recordingMbid = "m2")), listOf(id)).updated).containsExactly(id.copy(recordingMbid = "m2"))
        assertThat(plan(listOf(f.copy(recordingMbid = null)), listOf(id)).updated).containsExactly(id.copy(recordingMbid = null))
    }

    @Test
    fun aFileWhoseTagsWereNotReadLeavesTheMbid() {
        val f = file(1, "aa", mbid = null).copy(tagsRead = false)
        val id = identity(7, "aa", mbid = "m1").following(f)

        assertThat(id.recordingMbid).isEqualTo("m1")
        assertThat(plan(listOf(f), listOf(id)).isEmpty).isTrue()
    }

    @Test
    fun step3APendingFileWithTheMbidMakesItAmbiguous() {
        val settled = file(1, "bb", place = moved, mbid = "m1")
        val pending = file(2, "cc", place = moved.copy(displayName = "02.mp3"), mbid = "m1", settled = false)
        val id = identity(7, "aa", mbid = "m1", missingSince = now - 1_000)

        val result = plan(listOf(settled, pending), listOf(id))

        assertThat(result.created).containsExactly(settled)
        assertThat(result.updated).isEmpty()
    }

    @Test
    fun theWindowIsOneSecondInclusive() {
        assertThat(IdentityMatcher.withinWindow(10_000, 11_000)).isTrue()
        assertThat(IdentityMatcher.withinWindow(11_000, 10_000)).isTrue()
        assertThat(IdentityMatcher.withinWindow(10_000, 11_001)).isFalse()
        assertThat(IdentityMatcher.withinWindow(null, 10_000)).isFalse()
        assertThat(IdentityMatcher.withinWindow(10_000, null)).isFalse()
    }
}
