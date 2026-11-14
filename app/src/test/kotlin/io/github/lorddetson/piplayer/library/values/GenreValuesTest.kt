package io.github.lorddetson.piplayer.library.values

import io.github.lorddetson.piplayer.library.lists.GenreKeys
import io.github.lorddetson.piplayer.library.sort.SortKeys
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The genres of a file's merged values, which lists group it by (docs/library.md §19.1). */
class GenreValuesTest {

    private fun unread(genre: String?) =
        MergedValues.of("Song.mp3", IndexValues(title = "Song", genre = genre), tags = null, read = false).displayGenres

    private fun read(vararg genres: String, index: String? = "Index genre") =
        MergedValues.of(
            "Song.mp3",
            IndexValues(title = "Song", genre = index),
            TrackTags.from(mapOf("TITLE" to listOf("Song"), "GENRE" to genres.toList())),
            read = true,
        ).displayGenres

    @Test
    fun `before the read the index's genre is split at semicolons with each part trimmed`() {
        assertThat(unread(" Rock ;  Pop ").map { it.name }).containsExactly("Rock", "Pop")
        assertThat(unread("Jazz").map { it.name }).containsExactly("Jazz")
    }

    @Test
    fun `empty parts and the index's placeholder are dropped part by part`() {
        assertThat(unread("Rock;;<unknown>; ;Folk").map { it.name }).containsExactly("Rock", "Folk")
        assertThat(unread("<unknown>")).isEmpty()
        assertThat(unread("  ")).isEmpty()
        assertThat(unread(null)).isEmpty()
    }

    @Test
    fun `a slash never splits a genre`() {
        assertThat(unread("Hip-Hop/Rap; R&B/Soul").map { it.name }).containsExactly("Hip-Hop/Rap", "R&B/Soul")
    }

    @Test
    fun `after the read the tags' genres count in their order and the index's never`() {
        assertThat(read("Pop", "Rock").map { it.name }).containsExactly("Pop", "Rock")
        assertThat(read("Ambient; Drone").map { it.name }).containsExactly("Ambient", "Drone")
        assertThat(read()).isEmpty()
    }

    @Test
    fun `two spellings of one key count once and the first stays`() {
        assertThat(unread("Hip-Hop; hip hop; HIP HOP").map { it.name }).containsExactly("Hip-Hop")
        assertThat(read("Rock", "ROCK", "Pop").map { it.name }).containsExactly("Rock", "Pop")
    }

    @Test
    fun `each genre carries its key, its search text and the collator's key of its name`() {
        val genre = unread("Электроника").single()

        assertThat(genre.key).isEqualTo(GenreKeys.of("Электроника")).isEqualTo("электроника")
        assertThat(genre.searchText).contains("электроника")
        assertThat(genre.sortKey).isEqualTo(SortKeys.of("Электроника"))
    }

    @Test
    fun `the search text still holds the genres`() {
        val values = MergedValues.of("Song.mp3", IndexValues(title = "Song", genre = "Rock; Pop"), tags = null, read = false)

        assertThat(values.searchText).contains("rock").contains("pop")
    }
}
