package io.github.lorddetson.piplayer.library.values

import io.github.lorddetson.piplayer.identification.text.TextNormalizer
import kotlin.random.Random
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A first sync resolves the display values of every file (docs/library.md §1.7, §6.2): an album, its artist and album
 * artist repeat over its tracks and over the fields of one track, so each distinct text is normalized once per batch.
 */
class TextFormsTest {

    @Test
    fun `each distinct text of a batch is normalized once`() {
        var calls = 0
        val forms = TextForms { calls++; TextNormalizer.normalize(it) }

        repeat(10) { i ->
            MergedValues.of(
                "Song$i.mp3",
                IndexValues(title = "Title$i", artist = "Artist", album = "Album", albumArtist = "Band", year = 2000, trackNumber = i + 1),
                tags = null,
                read = false,
                volume = "external_primary",
                relativePath = "Music/Album/",
                forms = forms,
            )
        }

        // Each file's title and file name, and the artist, album and album artist once for all.
        assertThat(calls).isEqualTo(10 * 2 + 3)
    }

    @Test
    fun `the folder part of the album keys is derived once per folder of a batch`() {
        val folders = mutableListOf<String>()
        val forms = TextForms(folders = { folders += it; AlbumKey.folderKey(it) })

        val keys = List(12) { i ->
            MergedValues.of(
                "Song$i.mp3",
                IndexValues(title = "Title$i", artist = "Artist", album = "Album", year = 2000, trackNumber = i + 1),
                tags = null,
                read = false,
                volume = "external_primary",
                relativePath = listOf("Music/Album/CD1/", "Music/Album/CD2/", "Music/Other/")[i % 3],
                forms = forms,
            ).albumKey
        }

        assertThat(folders).containsExactly("Music/Album/CD1/", "Music/Album/CD2/", "Music/Other/")
        // The disc folders still make one album.
        assertThat(keys.distinct()).hasSize(2)
    }

    @Test
    fun `values resolved with shared forms are those resolved alone`() {
        val random = Random(66)
        val parts = listOf("Ёлка", "AC/DC", "Beyoncé", "the end", "a b c", "+", "", "Sigur Rós", "ДДТ", "01 - intro", "x_y")
        fun text() = if (random.nextInt(8) == 0) null else List(random.nextInt(1, 3)) { parts.random(random) }.joinToString(" ")
        val shared = TextForms()
        repeat(2_000) { i ->
            val read = random.nextBoolean()
            val tags = TrackTags.from(
                listOfNotNull(
                    text()?.let { "TITLE" to listOf(it) },
                    text()?.let { "ARTISTS" to listOf(it, parts.random(random)) },
                    text()?.let { "ALBUM" to listOf(it) },
                    text()?.let { "ALBUMARTIST" to listOf(it) },
                    text()?.let { "COMPOSER" to listOf(it) },
                ).toMap(),
            )
            val index = IndexValues(text(), text(), text(), text(), text(), 1_000L * i, text(), 1990 + i % 30, i)
            val name = "${text().orEmpty()} - ${text().orEmpty()}.mp3"
            // Some in disc folders, which the album key leaves out.
            val path = "Music/${text().orEmpty()}/" + listOf("", "", "CD1/", "Disc 2/", "Ёлка/").random(random)
            fun resolve(forms: TextForms) = MergedValues.of(name, index, tags, read, volume = "v", relativePath = path, forms = forms)

            assertThat(resolve(shared)).describedAs("input $i").isEqualTo(resolve(TextForms()))
        }
    }
}
