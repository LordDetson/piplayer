package io.github.lorddetson.piplayer.library.playlists

import io.github.lorddetson.piplayer.library.identity.Identity
import io.github.lorddetson.piplayer.library.identity.IdentityMatcher
import io.github.lorddetson.piplayer.library.identity.KeyedFile
import io.github.lorddetson.piplayer.library.identity.Place
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Step 5 of re-attachment, the linking of entries without an identity and their resolution (docs/library.md §16.2). */
class PlaylistHintsTest {

    private val now = 1_800_000_000_000L
    private val old = Place("external_primary", "Music/Album/", "01 Song.mp3")
    private val moved = Place("external_primary", "Music/Other/", "01 Song.mp3")
    private val renamed = Place("external_primary", "Music/Album/", "01 Song (remaster).flac")

    private fun file(id: Long, key: String, place: Place, durationMs: Long? = 200_000, mbid: String? = null) =
        KeyedFile(id, key, place, size = 5_000_000 + id, durationMs, settled = true, mbid, format = "mpeg")

    private fun missing(id: Long, key: String, hint: Place? = old, durationMs: Long? = 200_000, mbid: String? = null) =
        Identity(id, key, durationMs, mbid, hint, hintSize = 5_000_000, createdAt = now - 1_000_000, missingSince = now - 1_000)

    private fun hint(
        place: Place? = old,
        durationMs: Long? = 200_000,
        key: String? = null,
        mbid: String? = null,
        name: String? = place?.displayName,
    ) = EntryHint(place?.volume, place?.relativePath, name, durationMs, key, mbid)

    private fun hintFile(id: Long, place: Place, durationMs: Long? = 200_000, key: String? = null, mbid: String? = null) =
        HintFile(id, place, durationMs, key, mbid)

    // Step 5 of re-attachment (TI §3.1).

    @Test
    fun step5AMissingIdentityTakesTheOneFileItsEntryNamesByNameAndLength() {
        // The file was re-encoded (another key) and moved: steps 1 to 3 find nothing.
        val identity = missing(7, "aa", hint = Place("external_primary", "Music/Gone/", "x.mp3"))
        val target = file(1, "bb", moved)

        val plan = IdentityMatcher.plan(
            listOf(target), listOf(identity), holdingData = setOf(7), now = now,
            playlistHints = mapOf(7L to listOf(hint(old))),
        )

        assertThat(plan.created).isEmpty()
        assertThat(plan.updated.single().id).isEqualTo(7)
        assertThat(plan.updated.single().contentKey).isEqualTo("bb")
        assertThat(plan.updated.single().hint).isEqualTo(moved)
    }

    @Test
    fun step5ByThePlaceOfTheEntryWithTheLength() {
        val identity = missing(7, "aa", hint = null)

        val found = PlaylistHints.reattach(listOf(identity), mapOf(7L to listOf(hint(renamed))), listOf(file(1, "bb", renamed)))

        assertThat(found.mapValues { it.value.fileId }).containsExactly(java.util.Map.entry(7L, 1L))
    }

    @Test
    fun step5ByTheRecordingMbidWithTheLength() {
        val identity = missing(7, "aa", hint = null)
        val mbid = "0b8a3a2e-1111-4222-8333-444455556666"
        val target = file(1, "bb", Place("external_primary", "Other/", "track.ogg"), mbid = mbid)

        val found = PlaylistHints.reattach(listOf(identity), mapOf(7L to listOf(hint(null, mbid = mbid, name = null))), listOf(target))

        assertThat(found.keys).containsExactly(7L)
    }

    @Test
    fun step5NeedsTheLengthWithin1s() {
        val identity = missing(7, "aa", hint = null)

        val far = PlaylistHints.reattach(listOf(identity), mapOf(7L to listOf(hint(old))), listOf(file(1, "bb", moved, durationMs = 202_000)))
        // A hint without a length takes the identity's; with neither there is nothing to compare.
        val identityLength = PlaylistHints.reattach(listOf(identity), mapOf(7L to listOf(hint(old, durationMs = null))), listOf(file(1, "bb", moved)))
        val unknown = PlaylistHints.reattach(
            listOf(missing(7, "aa", hint = null, durationMs = null)), mapOf(7L to listOf(hint(old, durationMs = null))), listOf(file(1, "bb", moved)),
        )

        assertThat(far).isEmpty()
        assertThat(identityLength.keys).containsExactly(7L)
        assertThat(unknown).isEmpty()
    }

    @Test
    fun step5TakesNothingWhenTwoFilesMatch() {
        val identity = missing(7, "aa", hint = null)
        val files = listOf(file(1, "bb", moved), file(2, "cc", Place("external_primary", "Music/Third/", "01 Song.mp3")))

        assertThat(PlaylistHints.reattach(listOf(identity), mapOf(7L to listOf(hint(old))), files)).isEmpty()
    }

    @Test
    fun step5TakesNothingThatAnotherMissingIdentityCouldTake() {
        val identities = listOf(missing(7, "aa", hint = null), missing(8, "dd", hint = null))
        val hints = mapOf(7L to listOf(hint(old)), 8L to listOf(hint(moved)))

        assertThat(PlaylistHints.reattach(identities, hints, listOf(file(1, "bb", moved)))).isEmpty()
    }

    @Test
    fun step5ComesAfterTheIdentitysOwnSteps() {
        // Step 2 already takes the file at the identity's own place; the entry's hint does not move it elsewhere.
        val identity = missing(7, "aa", hint = old)
        val atOld = file(1, "bb", old)
        val byName = file(2, "cc", moved)

        val plan = IdentityMatcher.plan(
            listOf(atOld, byName), listOf(identity), holdingData = setOf(7), now = now,
            playlistHints = mapOf(7L to listOf(hint(moved))),
        )

        assertThat(plan.updated.single().contentKey).isEqualTo("bb")
        assertThat(plan.created.map { it.fileId }).containsExactly(2L)
    }

    // Entries without an identity.

    @Test
    fun anEntryWithoutAnIdentityIsLinkedByTheContentKeyFirst() {
        val files = listOf(hintFile(1, moved, key = "aa"), hintFile(2, old, key = "bb"))

        val linked = PlaylistHints.link(mapOf(10L to hint(old, key = "aa")), files)

        assertThat(linked.mapValues { it.value.fileId }).containsExactly(java.util.Map.entry(10L, 1L))
    }

    @Test
    fun thenByThePlaceWithTheLengthThenByTheNameThenByTheMbid() {
        val mbid = "0b8a3a2e-1111-4222-8333-444455556666"
        val files = listOf(
            hintFile(1, old, durationMs = 200_400),
            hintFile(2, Place("external_primary", "Elsewhere/", "Lonely Name.MP3")),
            hintFile(3, Place("external_primary", "X/", "y.ogg"), mbid = mbid),
        )
        val entries = mapOf(
            10L to hint(old),
            11L to hint(Place("external_primary", "Nowhere/", "lonely name.mp3")),
            12L to hint(null, mbid = mbid, name = null),
            // The place with a length 3 s off is another song.
            13L to hint(old, durationMs = 203_500),
        )

        val linked = PlaylistHints.link(entries, files).mapValues { it.value.fileId }

        assertThat(linked).containsExactlyInAnyOrderEntriesOf(mapOf(10L to 1L, 11L to 2L, 12L to 3L))
    }

    @Test
    fun aNameThatSeveralFilesCarryGoesToTheFolderThatEndsMostLikeTheHint() {
        val files = listOf(
            hintFile(1, Place("external_primary", "Music/Bob/Live/", "01 Intro.mp3")),
            hintFile(2, Place("external_primary", "Music/Ann/Debut/", "01 Intro.mp3")),
            hintFile(3, Place("external_primary", "Music/Cid/Debut/", "01 Intro.mp3")),
        )
        // A playlist from another device: another root, the same artist and album folders.
        val fromPc = EntryHint(null, "Users/me/Music/Ann/Debut/", "01 intro.mp3", 200_000, null, null)
        val bare = EntryHint(null, null, "01 Intro.mp3", 200_000, null, null)

        val linked = PlaylistHints.link(mapOf(10L to fromPc, 11L to bare), files).mapValues { it.value.fileId }

        assertThat(linked).containsExactlyInAnyOrderEntriesOf(mapOf(10L to 2L, 11L to 1L))
    }

    @Test
    fun aNameWithoutALengthMatchesAnyLength() {
        val linked = PlaylistHints.link(mapOf(10L to hint(moved, durationMs = null)), listOf(hintFile(1, Place("v", "a/", "01 song.mp3"), durationMs = 99_000)))

        assertThat(linked.keys).containsExactly(10L)
    }

    @Test
    fun anMbidThatTwoFilesCarryLinksNothing() {
        val mbid = "0b8a3a2e-1111-4222-8333-444455556666"
        val files = listOf(hintFile(1, Place("v", "a/", "1.ogg"), mbid = mbid), hintFile(2, Place("v", "b/", "2.ogg"), mbid = mbid))

        assertThat(PlaylistHints.link(mapOf(10L to hint(null, mbid = mbid, name = null)), files)).isEmpty()
    }

    // Resolution for the page and the queue.

    @Test
    fun anEntryWithAnIdentityPlaysTheCopyAtTheIdentitysPlaceThenAtTheEntrysThenTheLowestId() {
        val copies = listOf(hintFile(5, moved, key = "aa"), hintFile(3, renamed, key = "aa"), hintFile(9, old, key = "aa"))
        val withKey = { key: String -> copies.filter { it.contentKey == key } }
        val atPlace = { _: Place -> emptyList<HintFile>() }

        fun fileOf(identityHint: Place?, entryHint: Place?) = PlaylistResolution.fileOf(
            PlaylistResolution.Target(identityId = 7, identityKey = "aa", identityHint = identityHint, hint = hint(entryHint)),
            withKey, atPlace,
        )?.fileId

        assertThat(fileOf(moved, old)).isEqualTo(5)
        assertThat(fileOf(Place("v", "gone/", "x"), old)).isEqualTo(9)
        assertThat(fileOf(null, null)).isEqualTo(3)
    }

    @Test
    fun anEntryWithAnIdentityThatNoFileHoldsIsUnavailableEvenWithAFileAtItsPlace() {
        val atOld = hintFile(1, old, key = "zz")

        val found = PlaylistResolution.fileOf(
            PlaylistResolution.Target(identityId = 7, identityKey = "aa", identityHint = old, hint = hint(old)),
            withKey = { emptyList() },
            atPlace = { place -> listOf(atOld).filter { it.place == place } },
        )

        assertThat(found).isNull()
    }

    @Test
    fun anEntryWithoutAnIdentityPlaysItsHintedKeyThenItsPlaceWithTheLength() {
        val byKey = hintFile(1, moved, key = "aa")
        val atOld = hintFile(2, old, durationMs = 200_500)
        val withKey = { key: String -> listOf(byKey).filter { it.contentKey == key } }
        val atPlace = { place: Place -> listOf(atOld).filter { it.place == place } }

        fun fileOf(hint: EntryHint) = PlaylistResolution.fileOf(PlaylistResolution.Target(null, null, null, hint), withKey, atPlace)?.fileId

        assertThat(fileOf(hint(old, key = "aa"))).isEqualTo(1)
        assertThat(fileOf(hint(old, key = "nothing"))).isEqualTo(2)
        assertThat(fileOf(hint(old, durationMs = null))).isEqualTo(2)
        assertThat(fileOf(hint(old, durationMs = 205_000))).isNull()
        assertThat(fileOf(hint(null, name = "01 Song.mp3"))).isNull()
    }
}
