package io.github.lorddetson.piplayer.library.playlists

import io.github.lorddetson.piplayer.library.identity.Place
import io.github.lorddetson.piplayer.library.values.LegacyEncodings
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** M3U8 parsing, writing, legacy decoding, the paths of volumes and the matching of lines (docs/library.md §16.6). */
class M3uTest {

    private val paths = M3uPaths(mapOf("external_primary" to "/storage/emulated/0", "1234-abcd" to "/storage/1234-ABCD"))

    // Parsing.

    @Test
    fun extinfGivesTheLengthTheArtistAndTheTitleOfTheNextPath() {
        val text = "\uFEFF#EXTM3U\r\n#PLAYLIST:Road trip\r\n#EXTINF:241,Ann - First Song\r\n/storage/emulated/0/Music/a.mp3\r\n" +
            "\r\n# a comment\r\n#EXTINF:-1,Only A Title\r\nb.mp3\r\n#EXTINF:12.6 tvg-id=\"x\",Bob - Two - Parts\r\nc.mp3\r\n" +
            "d.mp3\n"

        val playlist = M3u.parse(text)

        assertThat(playlist.name).isEqualTo("Road trip")
        assertThat(playlist.entries).containsExactly(
            M3uEntry("/storage/emulated/0/Music/a.mp3", 241, "Ann", "First Song"),
            M3uEntry("b.mp3", null, null, "Only A Title"),
            M3uEntry("c.mp3", 13, "Bob", "Two - Parts"),
            M3uEntry("d.mp3", null, null, null),
        )
    }

    @Test
    fun aPlainM3uWithoutHeaderIsAListOfPaths() {
        assertThat(M3u.parse("a.mp3\nb.mp3").entries.map { it.location }).containsExactly("a.mp3", "b.mp3")
        assertThat(M3u.parse("#EXTM3U\n").entries).isEmpty()
        assertThat(M3u.parse("").name).isNull()
    }

    // Writing.

    @Test
    fun exportWritesTheHeaderAndOneExtinfPerEntry() {
        val text = M3u.write(
            listOf(
                M3uEntry("/storage/emulated/0/Music/a.mp3", 241, "Ann", "First Song"),
                M3uEntry("/storage/emulated/0/Music/b.mp3", null, null, "Line\nbreak"),
            ),
        )

        assertThat(text).isEqualTo(
            "#EXTM3U\n#EXTINF:241,Ann - First Song\n/storage/emulated/0/Music/a.mp3\n" +
                "#EXTINF:-1,Line break\n/storage/emulated/0/Music/b.mp3\n",
        )
        assertThat(M3u.parse(text).entries).containsExactly(
            M3uEntry("/storage/emulated/0/Music/a.mp3", 241, "Ann", "First Song"),
            M3uEntry("/storage/emulated/0/Music/b.mp3", null, null, "Line break"),
        )
    }

    @Test
    fun secondsAreRoundedToTheNearest() {
        assertThat(M3u.seconds(241_499)).isEqualTo(241)
        assertThat(M3u.seconds(241_500)).isEqualTo(242)
        assertThat(M3u.seconds(null)).isNull()
    }

    // Decoding.

    @Test
    fun utf8IsReadWithOrWithoutAByteOrderMark() {
        val text = "#EXTINF:1,Кино - Звезда\nМузыка/a.mp3\n"

        assertThat(M3u.decode(text.toByteArray(Charsets.UTF_8), cyrillicLanguage = false)).isEqualTo(text)
        assertThat(M3u.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray(), false)).isEqualTo(text)
    }

    @Test
    fun anOldM3uInWindows1251ReadsAsTheTagsDo() {
        val text = "#EXTINF:1,Кино - Звезда по имени Солнце\nМузыка/Кино.mp3\n"
        val bytes = text.toByteArray(charset("windows-1251"))

        assertThat(M3u.decode(bytes, cyrillicLanguage = true)).isEqualTo(text)
        // Without a Cyrillic language it is Latin-1, unless chosen by hand (docs/library.md §5.7).
        assertThat(M3u.decode(bytes, cyrillicLanguage = false)).isNotEqualTo(text)
        assertThat(M3u.decode(bytes, cyrillicLanguage = false, manual = LegacyEncodings.fromStored("windows-1251"))).isEqualTo(text)
        assertThat(M3u.decode("Motörhead.mp3".toByteArray(Charsets.ISO_8859_1), cyrillicLanguage = true)).isEqualTo("Motörhead.mp3")
    }

    // Paths.

    @Test
    fun absolutePathsOfAttachedVolumesNameTheirPlace() {
        assertThat(paths.placeOf("/storage/emulated/0/Music/A/01.mp3")).isEqualTo(LinePlace("external_primary", "Music/A/", "01.mp3"))
        assertThat(paths.placeOf("/storage/emulated/0/song.mp3")).isEqualTo(LinePlace("external_primary", "", "song.mp3"))
        assertThat(paths.placeOf("/sdcard/Music/01.mp3")).isEqualTo(LinePlace("external_primary", "Music/", "01.mp3"))
        assertThat(paths.placeOf("/storage/1234-ABCD/Music/x.flac")).isEqualTo(LinePlace("1234-abcd", "Music/", "x.flac"))
        // A card that is not in the phone now.
        assertThat(paths.placeOf("/storage/5555-0000/Music/x.flac")).isEqualTo(LinePlace("5555-0000", "Music/", "x.flac"))
        assertThat(paths.placeOf("file:///storage/emulated/0/Music/A%20B/01%25.mp3"))
            .isEqualTo(LinePlace("external_primary", "Music/A B/", "01%.mp3"))
    }

    @Test
    fun windowsAndRelativePathsKeepTheirFoldersWithoutAVolume() {
        assertThat(paths.placeOf("C:\\Users\\me\\Music\\Ann\\01 Intro.mp3")).isEqualTo(LinePlace(null, "Users/me/Music/Ann/", "01 Intro.mp3"))
        assertThat(paths.placeOf("Ann\\01.mp3")).isEqualTo(LinePlace(null, "Ann/", "01.mp3"))
        assertThat(paths.placeOf("01.mp3")).isEqualTo(LinePlace(null, null, "01.mp3"))
    }

    @Test
    fun relativePathsGoFromThePlaylistsFolderWhenItIsKnown() {
        val folder = DocumentFolder("external_primary", "Music/Lists/")

        assertThat(paths.placeOf("Ann/01.mp3", folder)).isEqualTo(LinePlace("external_primary", "Music/Lists/Ann/", "01.mp3"))
        assertThat(paths.placeOf("../Other/./02.mp3", folder)).isEqualTo(LinePlace("external_primary", "Music/Other/", "02.mp3"))
        assertThat(paths.placeOf("03.mp3", folder)).isEqualTo(LinePlace("external_primary", "Music/Lists/", "03.mp3"))
    }

    @Test
    fun streamsAndEmptyLinesNameNoFile() {
        assertThat(paths.placeOf("http://radio.example/stream")).isNull()
        assertThat(paths.placeOf("https://radio.example/a.mp3")).isNull()
        assertThat(paths.placeOf("   ")).isNull()
    }

    @Test
    fun exportWritesAbsolutePathsThatImportReadsBack() {
        val places = listOf(
            Place("external_primary", "Music/A/", "01.mp3"),
            Place("external_primary", "", "root.mp3"),
            Place("1234-abcd", "Music/", "x.flac"),
            Place("fakevolume", "Music/", "y.flac"),
        )

        assertThat(paths.pathOf(places[0])).isEqualTo("/storage/emulated/0/Music/A/01.mp3")
        assertThat(paths.pathOf(places[1])).isEqualTo("/storage/emulated/0/root.mp3")
        assertThat(paths.pathOf(places[3])).isEqualTo("/storage/fakevolume/Music/y.flac")
        for (place in places) {
            assertThat(paths.placeOf(paths.pathOf(place))).isEqualTo(LinePlace(place.volume, place.relativePath, place.displayName))
        }
    }

    @Test
    fun theStorageProvidersDocumentsNameTheirFolder() {
        val provider = "com.android.externalstorage.documents"

        assertThat(M3uPaths.folderOf(provider, "primary:Music/Lists/x.m3u8")).isEqualTo(DocumentFolder("external_primary", "Music/Lists/"))
        assertThat(M3uPaths.folderOf(provider, "1234-ABCD:x.m3u8")).isEqualTo(DocumentFolder("1234-abcd", ""))
        assertThat(M3uPaths.folderOf("com.android.providers.downloads.documents", "msf:12")).isNull()
        assertThat(M3uPaths.folderOf(null, null)).isNull()
    }

    // Matching.

    private fun file(id: Long, place: Place, durationMs: Long? = 200_000) = HintFile(id, place, durationMs)

    private fun match(line: LinePlace, seconds: Long?, files: List<HintFile>) =
        FileMatcher(files).find(line.hint(seconds?.let { it * 1_000 }))?.fileId

    @Test
    fun aLineGoesToItsPlaceThenItsPlaceIgnoringCaseThenItsName() {
        val exact = file(1, Place("external_primary", "Music/A/", "01.mp3"))
        val otherCase = file(2, Place("external_primary", "music/b/", "02.MP3"))
        val byName = file(3, Place("external_primary", "Elsewhere/", "03.mp3"))
        val files = listOf(exact, otherCase, byName)

        assertThat(match(LinePlace("external_primary", "Music/A/", "01.mp3"), 200, files)).isEqualTo(1)
        assertThat(match(LinePlace("external_primary", "Music/B/", "02.mp3"), 200, files)).isEqualTo(2)
        assertThat(match(LinePlace(null, "Users/me/", "03.mp3"), 200, files)).isEqualTo(3)
        assertThat(match(LinePlace(null, null, "03.mp3"), null, files)).isEqualTo(3)
        // The name with a length 2 s off is another song.
        assertThat(match(LinePlace(null, null, "03.mp3"), 202, files)).isNull()
        assertThat(match(LinePlace(null, null, "04.mp3"), null, files)).isNull()
    }
}
