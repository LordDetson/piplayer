package io.github.lorddetson.piplayer.identification.text

import io.github.lorddetson.piplayer.identification.score.TrackHint
import io.github.lorddetson.piplayer.library.values.LegacyEncodings
import io.github.lorddetson.piplayer.library.values.LegacyText
import io.github.lorddetson.piplayer.library.values.TrackTags
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.charset.Charset

/** The stage 4 hint of TI §3.3: tags as decoded at stage 0, the file-name guess and the exact duration. */
class TrackHintsTest {

    /**
     * Each row: tag title, artist, album and track number (empty = no tag), the file name, the exact duration, and the
     * hint's expected title, artist, album, track number and duration (empty = null).
     */
    @ParameterizedTest(name = "{4}")
    @CsvSource(
        delimiter = '|',
        value = [
            // Tags only: every field from the tags, the album and track number too.
            "Paper Lanterns|The Quiet Harbors|Harbor Lights|3|track_07.mp3|214000|Paper Lanterns|The Quiet Harbors|Harbor Lights|3|214000",
            // No tags: everything the file name says; a file name never gives an album.
            "||||03 - Kino - Gruppa krovi.mp3|285000|Gruppa krovi|Kino||3|285000",
            // A field the tags leave empty is filled from the file name, field by field.
            "Song From Tag|||  |01 - File Artist - File Song.mp3|200000|Song From Tag|File Artist||1|200000",
            "|Tag Artist|||File Artist - File Song.flac|200000|File Song|Tag Artist|||200000",
            // The tags' track number wins over the file name's.
            "Song||Album|7|02 - Song.mp3|200000|Song||Album|7|200000",
            // A tag value without a letter or digit counts as empty.
            "?|-|-||Artist - Title.mp3|200000|Title|Artist|||200000",
            // A title of digits only is a title.
            "1999|Prince|||x.mp3|200000|1999|Prince|||200000",
            // A name that says nothing gives nothing.
            "||||AUD-20230101-WA0001.opus|61000|||||61000",
            "||||Track 05.mp3|61000|||||61000",
            // A path and a download-site name are parsed as the parser does for level 5.
            "||||Music/Artist - Title [320kbps].mp3|180000|Title|Artist|||180000",
            // Only a positive exact duration counts.
            "Song|Artist|||x.mp3|0|Song|Artist|||",
            "Song|Artist|||x.mp3||Song|Artist|||",
        ],
    )
    fun `the hint takes each field from the tags and else from the file name`(
        tagTitle: String?,
        tagArtist: String?,
        tagAlbum: String?,
        tagTrack: String?,
        fileName: String,
        exactDurationMs: Long?,
        title: String?,
        artist: String?,
        album: String?,
        trackNumber: Int?,
        durationMs: Long?,
    ) {
        val tags = TrackTags(
            title = tagTitle,
            artist = tagArtist,
            album = tagAlbum,
            trackNumber = tagTrack?.trim()?.toIntOrNull(),
        )
        assertThat(TrackHints.assemble(tags, fileName, exactDurationMs))
            .isEqualTo(TrackHint(title, artist, durationMs, album, trackNumber))
    }

    @Test
    fun `a file the tag pass has not read is hinted from its name alone`() {
        assertThat(TrackHints.assemble(tags = null, fileName = "05. Artist - Title (Live).mp3", exactDurationMs = 1_000))
            .isEqualTo(TrackHint(title = "Title (Live)", artist = "Artist", durationMs = 1_000, trackNumber = 5))
    }

    @Test
    fun `raw tags are decoded with the legacy encoding before they become the hint`() {
        val raw = mapOf("TITLE" to listOf(marked("Группа крови", "windows-1251")), "ARTIST" to listOf(marked("Кино", "windows-1251")))
        val hint = TrackHints.assemble(raw, cyrillicLanguage = true, manual = null, fileName = "x.mp3", exactDurationMs = 285_000)
        assertThat(hint.title).isEqualTo("Группа крови")
        assertThat(hint.artist).isEqualTo("Кино")
    }

    @Test
    fun `the manual legacy encoding replaces the automatic guess`() {
        val koi8 = mapOf("ARTIST" to listOf(marked("Кино", "KOI8-R")))
        val manual = LegacyEncodings.ALL.single { it.name == "koi8-r" }
        assertThat(TrackHints.assemble(koi8, cyrillicLanguage = true, manual = manual, fileName = "x.mp3", exactDurationMs = null).artist)
            .isEqualTo("Кино")
        // Without the choice, KOI8-R text reads as Windows-1251 and is not "Кино".
        assertThat(TrackHints.assemble(koi8, cyrillicLanguage = true, manual = null, fileName = "x.mp3", exactDurationMs = null).artist)
            .isNotEqualTo("Кино")
    }

    @Test
    fun `the scoring hint brackets a version written without brackets, as the text search does`() {
        val hint = TrackHint(title = "Girlfriend Radio Edit", artist = "A", durationMs = 1)
        assertThat(TrackHints.forScoring(hint).title).isEqualTo(VersionSuffix.bracket("Girlfriend Radio Edit"))
        assertThat(TrackHints.forScoring(hint, QueryOptions(bracketVersion = false))).isEqualTo(hint)
    }

    private fun marked(text: String, charset: String): String = buildString {
        for (byte in text.toByteArray(Charset.forName(charset))) {
            val value = byte.toInt() and 0xFF
            append(if (value < 0x80) value.toChar() else (LegacyText.MARK_BASE + value).toChar())
        }
    }
}
