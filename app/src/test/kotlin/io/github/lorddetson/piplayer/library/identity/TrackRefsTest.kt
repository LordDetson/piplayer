package io.github.lorddetson.piplayer.library.identity

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** How a saved track reference finds its file again (docs/playback.md §4.8). */
class TrackRefsTest {

    private val here = Place("external_primary", "Music/A/", "01.mp3")
    private val there = Place("external_primary", "Music/B/", "01.mp3")

    private class Lookup(val files: List<FoundFile>, val identities: Map<Long, String> = emptyMap()) : TrackLookup {
        override fun file(fileId: Long) = files.firstOrNull { it.fileId == fileId }
        override fun identityKey(identityId: Long) = identities[identityId]
        override fun filesWithKey(contentKey: String) = files.filter { it.contentKey == contentKey }
        override fun filesAt(place: Place) = files.filter { it.place == place }
    }

    private val ref = TrackRef(fileId = 5, identityId = 9, contentKey = "aa", place = here, durationMs = 200_000)

    @Test
    fun theFileIdWinsInTheSameLibraryWhileTheFileIsAtItsPlace() {
        val lookup = Lookup(listOf(FoundFile(5, here, null, 200_000), FoundFile(6, there, "aa", 200_000)))

        assertThat(TrackRefs.resolve(ref, sameLibrary = true, lookup)).isEqualTo(5)
    }

    @Test
    fun theFileIdMeansNothingInAnotherLibrary() {
        val lookup = Lookup(listOf(FoundFile(5, there, "zz", 100_000), FoundFile(8, here, null, 200_000)))

        assertThat(TrackRefs.resolve(ref, sameLibrary = false, lookup)).isEqualTo(8)
    }

    @Test
    fun aFileIdNowAtAnotherPlaceFallsThroughToTheKey() {
        val lookup = Lookup(listOf(FoundFile(5, there, "zz", 100_000), FoundFile(6, there, "aa", 200_000)))

        assertThat(TrackRefs.resolve(ref, sameLibrary = true, lookup)).isEqualTo(6)
    }

    @Test
    fun theIdentitysCurrentKeyComesBeforeTheSavedKey() {
        val lookup = Lookup(
            listOf(FoundFile(6, there, "aa", 200_000), FoundFile(7, there.copy(displayName = "x"), "bb", 200_000)),
            identities = mapOf(9L to "bb"),
        )

        assertThat(TrackRefs.resolve(ref, sameLibrary = false, lookup)).isEqualTo(7)
    }

    @Test
    fun aGoneIdentityFallsBackToTheSavedKey() {
        val lookup = Lookup(listOf(FoundFile(6, there, "aa", 200_000)))

        assertThat(TrackRefs.resolve(ref, sameLibrary = false, lookup)).isEqualTo(6)
    }

    @Test
    fun amongCopiesWithTheKeyTheSavedPlaceWinsThenTheLowestId() {
        val copies = listOf(FoundFile(3, there, "aa", 200_000), FoundFile(8, here, "aa", 200_000))
        assertThat(TrackRefs.resolve(ref, sameLibrary = false, Lookup(copies))).isEqualTo(8)

        val elsewhere = listOf(FoundFile(8, there.copy(displayName = "y"), "aa", 200_000), FoundFile(3, there, "aa", 200_000))
        assertThat(TrackRefs.resolve(ref, sameLibrary = false, Lookup(elsewhere))).isEqualTo(3)
    }

    @Test
    fun thePlaceCountsOnlyWithALengthWithinOneSecond() {
        assertThat(TrackRefs.resolve(ref, false, Lookup(listOf(FoundFile(4, here, null, 200_900))))).isEqualTo(4)
        assertThat(TrackRefs.resolve(ref, false, Lookup(listOf(FoundFile(4, here, null, 203_000))))).isNull()
        assertThat(TrackRefs.resolve(ref, false, Lookup(listOf(FoundFile(4, here, null, null))))).isNull()
    }

    @Test
    fun aFileAtThePlaceWhoseKeyChangedIsStillTheTrack() {
        // As step 2 of re-attachment: a gain tool rewrote the frames.
        assertThat(TrackRefs.resolve(ref, false, Lookup(listOf(FoundFile(4, here, "cc", 200_000))))).isEqualTo(4)
    }

    @Test
    fun aReferenceWithoutKeyOrPlaceUsesItsFileIdOnly() {
        val bare = TrackRef(fileId = 5, identityId = null, contentKey = null, place = null, durationMs = null)
        val lookup = Lookup(listOf(FoundFile(5, here, null, 1)))

        assertThat(TrackRefs.resolve(bare, sameLibrary = true, lookup)).isEqualTo(5)
        assertThat(TrackRefs.resolve(bare, sameLibrary = false, lookup)).isNull()
    }

    @Test
    fun nothingFoundIsNull() {
        assertThat(TrackRefs.resolve(ref, sameLibrary = true, Lookup(emptyList()))).isNull()
    }
}
