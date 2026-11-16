package io.github.lorddetson.piplayer.library.values

import io.github.lorddetson.piplayer.library.values.DecidedField.ALBUM
import io.github.lorddetson.piplayer.library.values.DecidedField.ALBUM_ARTIST
import io.github.lorddetson.piplayer.library.values.DecidedField.COMPILATION
import io.github.lorddetson.piplayer.library.values.DecidedField.RELEASE_MBID
import io.github.lorddetson.piplayer.library.values.DecidedField.TITLE
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The album key with decided values (docs/library.md §6.2, TI §7.1; §11.1 M3 "Album key with decided values"): a file's
 * key comes from its own values, and only a release MBID that every file of its group ends up with changes it.
 */
class AlbumGroupKeyTest {

    private val volume = "external_primary"
    private val path = "Music/Record/"
    private val tags = TrackTags(title = "Song", artist = "Ann", album = "Record", albumArtist = "Ann")
    private val ownKey = AlbumKey.of(tags, volume, path)!!
    private val release = "1e100000-1111-4222-8333-000000000001"
    private val other = "1e100000-1111-4222-8333-000000000002"

    private fun decided(vararg parts: Pair<DecidedField, String?>) =
        FieldOverrides(parts.associate { (part, value) -> part to Decided(value, DecisionKind.LEVEL_IDENTIFICATION, 1) })

    private fun read(tags: TrackTags = this.tags, overrides: FieldOverrides = FieldOverrides.NONE, path: String = this.path) =
        MergedValues.of("01.mp3", null, tags, read = true, overrides = overrides, volume = volume, relativePath = path)

    @Test
    fun `decided album, album artist, compilation and release leave the key of the file's own values`() {
        val values = read(overrides = decided(ALBUM to "Record (Deluxe)", ALBUM_ARTIST to "Ann & Bob", COMPILATION to "1", RELEASE_MBID to release))

        assertThat(values.albumKey).isEqualTo(ownKey)
        // The decided values are still what the file shows.
        assertThat(values.album).isEqualTo("Record (Deluxe)")
        assertThat(values.albumArtist).isEqualTo("Ann & Bob")
        assertThat(values.albumGroup).isEqualTo(AlbumGroupFacts(release, decidedRelease = true, volume, path))
    }

    @Test
    fun `a file's own release is its tags' one until a decision decides it, also as empty`() {
        val tagged = tags.copy(releaseMbid = other)

        assertThat(read(tagged).albumKey).isEqualTo("mbid:$other")
        assertThat(read(tagged).albumGroup).isEqualTo(AlbumGroupFacts(other, decidedRelease = false, volume, path))
        assertThat(read(tagged, decided(RELEASE_MBID to release)).albumKey).isEqualTo("mbid:$other")
        assertThat(read(tagged, decided(RELEASE_MBID to release)).albumGroup?.release).isEqualTo(release)
        assertThat(read(tagged, decided(RELEASE_MBID to null)).albumGroup).isEqualTo(AlbumGroupFacts(null, decidedRelease = true, volume, path))
        assertThat(read().albumGroup).isEqualTo(AlbumGroupFacts(null, decidedRelease = false, volume, path))
    }

    @Test
    fun `an unread file's key comes from the index and keeps it under decided values`() {
        val index = IndexValues(title = "Index song", album = "Index album", albumArtist = "Index artist")
        val own = AlbumKey.of(TrackTags(album = "Index album", albumArtist = "Index artist"), volume, path)

        val values = MergedValues.of(
            "01.mp3", index, null, read = false, overrides = decided(ALBUM to "Record", RELEASE_MBID to release),
            volume = volume, relativePath = path,
        )

        assertThat(values.albumKey).isEqualTo(own)
        assertThat(values.albumGroup).isEqualTo(AlbumGroupFacts(release, decidedRelease = true, volume, path))
    }

    @Test
    fun `a disc folder's file names its parent folder as the group's place`() {
        assertThat(read(path = "Music/Record/CD2/").albumGroup?.albumFolder).isEqualTo("Music/Record/")
    }

    @Test
    fun `a file without an album of its own takes the key of its decided release`() {
        val untagged = TrackTags(title = "track_07")

        val values = read(untagged, decided(TITLE to "Song", ALBUM to "Record", RELEASE_MBID to release))

        assertThat(values.albumKey).isEqualTo("mbid:$release")
        assertThat(values.albumGroup).isNull()
    }

    @Test
    fun `a file without an album of its own and without a decided release takes its decided album within its folder`() {
        val untagged = TrackTags(title = "track_07")

        val values = read(untagged, decided(ALBUM to "Record", ALBUM_ARTIST to "Ann"))

        assertThat(values.albumKey).isEqualTo(AlbumKey.of(TrackTags(album = "Record", albumArtist = "Ann"), volume, path))
        assertThat(values.albumGroup).isNull()
        assertThat(read(untagged).albumKey).isNull()
    }

    @Test
    fun `a group whose files all end up with one release takes that release's key`() {
        assertThat(AlbumKey.ofGroup(ownKey, listOf(release))).isEqualTo("mbid:$release")
        assertThat(AlbumKey.ofGroup(ownKey, listOf(release, release.uppercase(), " $release "))).isEqualTo("mbid:$release")
    }

    @Test
    fun `one file without that release keeps the group's own key`() {
        assertThat(AlbumKey.ofGroup(ownKey, listOf(release, null))).isEqualTo(ownKey)
        assertThat(AlbumKey.ofGroup(ownKey, listOf(release, other))).isEqualTo(ownKey)
        assertThat(AlbumKey.ofGroup(ownKey, listOf(null, null))).isEqualTo(ownKey)
    }

    @Test
    fun `a group of a tag release moves only when every file has another one decided`() {
        val tagKey = "mbid:${other.uppercase()}"

        assertThat(AlbumKey.ofGroup(tagKey, listOf(other.uppercase(), release))).isEqualTo(tagKey)
        assertThat(AlbumKey.ofGroup(tagKey, listOf(release, release))).isEqualTo("mbid:$release")
        // The same release in another spelling is no move.
        assertThat(AlbumKey.ofGroup(tagKey, listOf(other, other.uppercase()))).isEqualTo(tagKey)
    }

    @Test
    fun `a release that is no MBID makes no key`() {
        assertThat(AlbumKey.ofGroup(ownKey, listOf("not an mbid", "not an mbid"))).isEqualTo(ownKey)
    }
}
