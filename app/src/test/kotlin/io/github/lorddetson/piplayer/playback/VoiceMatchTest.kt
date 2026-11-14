package io.github.lorddetson.piplayer.playback

import io.github.lorddetson.piplayer.library.lists.AlbumCredit
import io.github.lorddetson.piplayer.library.lists.AlbumGroup
import io.github.lorddetson.piplayer.library.lists.ArtistGroup
import io.github.lorddetson.piplayer.library.lists.GenreGroup
import io.github.lorddetson.piplayer.library.lists.ListKind
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

    private fun genre(name: String) = GenreGroup(name.lowercase(), name, songs = 4)

    private fun playlist(name: String, id: Long) = VoiceMatch.NamedList(name, VoiceMatch.Playlist(id))

    private fun smart(name: String, kind: ListKind) = VoiceMatch.NamedList(name, VoiceMatch.SmartList(kind))

    @Test
    fun aGenreNamedAsTheQueryPlaysTheGenreRatherThanTheSongsItsWordFinds() {
        // Every song of the genre holds "rock" in its search text, so the filtered Songs list is not empty.
        val songs = listOf(song(1, "Stone Cold"), song(2, "Rocket Man"))

        assertThat(VoiceMatch.choose("Rock", emptyList(), emptyList(), songs, genres = listOf(genre("Rock and Roll"), genre("ROCK"))))
            .isEqualTo(VoiceMatch.Genre("rock"))
        // A genre whose name only holds the query is no match: the songs play.
        assertThat(VoiceMatch.choose("rock", emptyList(), emptyList(), songs, genres = listOf(genre("Rock and Roll"))))
            .isEqualTo(VoiceMatch.FoundSongs(1))
    }

    @Test
    fun aSongTitledAsTheQueryComesBeforeAPlaylistOfThatName() {
        val songs = listOf(song(1, "Road Trip Anthem"), song(2, "Road Trip"))

        assertThat(VoiceMatch.choose("road trip", emptyList(), emptyList(), songs, playlists = listOf(playlist("Road Trip", 5))))
            .isEqualTo(VoiceMatch.FoundSongs(2))
        assertThat(VoiceMatch.choose("road trip", emptyList(), emptyList(), songs.take(1), playlists = listOf(playlist("Road Trip", 5))))
            .isEqualTo(VoiceMatch.Playlist(5))
    }

    @Test
    fun aPlaylistComesBeforeAGenreOfTheSameNameAndAUsersPlaylistBeforeASmartList() {
        val songs = listOf(song(1, "Something"))

        assertThat(
            VoiceMatch.choose("chill", emptyList(), emptyList(), songs, playlists = listOf(playlist("Chill", 3)), genres = listOf(genre("Chill"))),
        ).isEqualTo(VoiceMatch.Playlist(3))
        assertThat(
            VoiceMatch.choose("favorites", emptyList(), emptyList(), songs, playlists = listOf(playlist("Favorites", 8), smart("Favorites", ListKind.FAVORITES))),
        ).isEqualTo(VoiceMatch.Playlist(8))
        assertThat(VoiceMatch.choose("избранное", emptyList(), emptyList(), emptyList(), playlists = listOf(smart("Избранное", ListKind.FAVORITES))))
            .isEqualTo(VoiceMatch.SmartList(ListKind.FAVORITES))
    }

    @Test
    fun anArtistOrAlbumNamedAsTheQueryStillWinsOverAPlaylistOrAGenre() {
        val playlists = listOf(playlist("Queen", 1))
        val genres = listOf(genre("Queen"))

        assertThat(VoiceMatch.choose("queen", listOf(artist("Queen")), emptyList(), emptyList(), playlists, genres)).isEqualTo(VoiceMatch.Artist("queen"))
        assertThat(VoiceMatch.choose("queen", emptyList(), listOf(album("Queen", "q")), emptyList(), playlists, genres)).isEqualTo(VoiceMatch.Album("q"))
    }

    @Test
    fun withNoPlaylistOrGenreOfTheNameTheOldOrderHolds() {
        val songs = listOf(song(7, "Love Me Do"))

        assertThat(VoiceMatch.choose("love", emptyList(), emptyList(), songs, listOf(playlist("Lovers", 2)), listOf(genre("Love Songs"))))
            .isEqualTo(VoiceMatch.FoundSongs(7))
        assertThat(VoiceMatch.choose("nov", listOf(artist("Nova", "n")), emptyList(), emptyList(), listOf(playlist("Novelties", 2)), emptyList()))
            .isEqualTo(VoiceMatch.Artist("n"))
    }
}
