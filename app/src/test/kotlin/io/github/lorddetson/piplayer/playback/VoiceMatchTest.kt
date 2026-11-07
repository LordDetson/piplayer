package io.github.lorddetson.piplayer.playback

import io.github.lorddetson.piplayer.library.lists.AlbumCredit
import io.github.lorddetson.piplayer.library.lists.AlbumGroup
import io.github.lorddetson.piplayer.library.lists.ArtistGroup
import io.github.lorddetson.piplayer.library.songs.Song
import io.github.lorddetson.piplayer.library.sort.SortKey
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The best match of a voice request among what the app's search finds (docs/playback.md §10.3). */
class VoiceMatchTest {

    private fun artist(name: String, key: String = name.lowercase()) = ArtistGroup(key, name, songs = 3, albums = 1)

    private fun album(title: String, key: String = "k-$title") =
        AlbumGroup(key, title, AlbumCredit.Unknown, year = null, songs = 2, titleSort = SortKey.EMPTY, artistSort = null)

    private fun song(id: Long, title: String) = Song(id, "content://media/$id", title, "Artist", "Album", 1_000)

    @Test
    fun anEmptyQueryPlaysEverySong() {
        assertThat(VoiceMatch.choose("", emptyList(), emptyList(), emptyList())).isEqualTo(VoiceMatch.AllSongs)
        assertThat(VoiceMatch.choose("  ", listOf(artist("Queen")), emptyList(), listOf(song(1, "A")))).isEqualTo(VoiceMatch.AllSongs)
    }

    @Test
    fun anArtistNamedAsTheQueryComesFirst() {
        val result = VoiceMatch.choose(
            "queen",
            artists = listOf(artist("Queens of the Stone Age", "qotsa"), artist("Queen", "queen")),
            albums = listOf(album("Queen")),
            songs = listOf(song(1, "Killer Queen"), song(2, "Queen")),
        )

        assertThat(result).isEqualTo(VoiceMatch.Artist("queen"))
    }

    @Test
    fun thenAnAlbumTitledAsTheQuery() {
        val result = VoiceMatch.choose(
            "Abbey road",
            artists = listOf(artist("Abbey Road Band")),
            albums = listOf(album("Abbey Road Live", "live"), album("ABBEY ROAD", "abbey")),
            songs = listOf(song(1, "Abbey Road Medley")),
        )

        assertThat(result).isEqualTo(VoiceMatch.Album("abbey"))
    }

    @Test
    fun thenTheFoundSongsFromTheOneTitledAsTheQuery() {
        val songs = listOf(song(1, "Yesterday Once More"), song(2, "Yesterday"), song(3, "Yesterdays"))

        assertThat(VoiceMatch.choose("yesterday", emptyList(), emptyList(), songs)).isEqualTo(VoiceMatch.FoundSongs(2))
        // Normalized as the library is: case, diacritics and ё.
        assertThat(VoiceMatch.choose("ежик", emptyList(), emptyList(), listOf(song(4, "Ёжик в тумане"), song(5, "Ёжик"))))
            .isEqualTo(VoiceMatch.FoundSongs(5))
    }

    @Test
    fun withoutAnExactTitleTheFoundSongsPlayFromTheirFirst() {
        val songs = listOf(song(7, "Love Me Do"), song(8, "All You Need Is Love"))

        assertThat(VoiceMatch.choose("love", listOf(artist("Courtney Love")), listOf(album("Love Songs")), songs))
            .isEqualTo(VoiceMatch.FoundSongs(7))
    }

    @Test
    fun withoutSongsTheFirstArtistThenTheFirstAlbumThatHoldTheQuery() {
        assertThat(VoiceMatch.choose("nova", listOf(artist("Nova Band", "nb"), artist("Bossa Nova", "bn")), listOf(album("Nova")), emptyList()))
            .isEqualTo(VoiceMatch.Album("k-Nova"))
        assertThat(VoiceMatch.choose("nov", listOf(artist("Nova Band", "nb"), artist("Bossa Nova", "bn")), listOf(album("Nova")), emptyList()))
            .isEqualTo(VoiceMatch.Artist("nb"))
        assertThat(VoiceMatch.choose("nov", emptyList(), listOf(album("Nova Live", "nl")), emptyList())).isEqualTo(VoiceMatch.Album("nl"))
    }

    @Test
    fun nothingFoundIsNoMatch() {
        assertThat(VoiceMatch.choose("zzzq", emptyList(), emptyList(), emptyList())).isNull()
    }
}
