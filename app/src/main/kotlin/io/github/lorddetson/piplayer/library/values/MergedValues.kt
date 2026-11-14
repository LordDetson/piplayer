package io.github.lorddetson.piplayer.library.values

import android.provider.MediaStore
import io.github.lorddetson.piplayer.identification.text.FileNameParser
import io.github.lorddetson.piplayer.library.lists.DisplayArtists
import io.github.lorddetson.piplayer.library.lists.DisplayGenres
import io.github.lorddetson.piplayer.library.sync.DisplayValues

/** What the media index says of a file, precedence level 4 (TI §7.1). */
data class IndexValues(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val composer: String? = null,
    val genre: String? = null,
    val durationMs: Long? = null,
    val albumArtist: String? = null,
    /** The index's year, and its track number with the disc in the thousands. */
    val year: Int? = null,
    val trackNumber: Int? = null,
)

/** The winners of levels 1 and 2 per field, which M3 fills from the user database (TI §7.1). */
data class FieldOverrides(val title: String? = null, val artist: String? = null, val album: String? = null) {
    companion object {
        val NONE = FieldOverrides()
    }
}

/** Resolves what lists show from the precedence levels (TI §7.1, docs/library.md §1.2). */
object MergedValues {

    const val TITLE_FROM_FILE_NAME = 1
    const val NO_ARTIST = 2
    const val NO_ALBUM = 4
    const val NO_COVER = 8
    const val NO_RECORDING_ID = 16

    /**
     * [read] says the tag pass has read the file (`tag_pass_at` is set): from then on the index no longer fills empty
     * tags, only the file name does. The length is not one of those values: TagLib's is an estimate for a VBR MP3 without
     * a header, so it comes last (TI §3.5). [failed] says the tag pass tried an unread file and could not read it
     * (`tag_failure` is set): its reasons come from the index's values (TI §1, docs/library.md §17.1).
     */
    fun of(
        displayName: String,
        index: IndexValues?,
        tags: TrackTags?,
        read: Boolean,
        exactDurationMs: Long? = null,
        tagLengthMs: Long? = null,
        embeddedCover: Boolean = false,
        overrides: FieldOverrides = FieldOverrides.NONE,
        volume: String = "",
        relativePath: String = "",
        forms: TextForms = TextForms(),
        failed: Boolean = false,
    ): DisplayValues {
        val tagValues = tags.takeIf { read }
        val indexValues = index.takeIf { !read }
        val fileName = displayName.substringBeforeLast('.').ifEmpty { displayName }
        val parsed by lazy(LazyThreadSafetyMode.NONE) { FileNameParser.parse(displayName) }

        val titleFromLevels = overrides.title ?: tagValues?.title ?: indexValues?.title.present()
        val title = titleFromLevels ?: parsed?.title ?: fileName
        val artist = overrides.artist ?: tagValues?.artist ?: indexValues?.artist.present() ?: parsed?.artist
        val album = overrides.album ?: tagValues?.album ?: indexValues?.album.present()
        val artistsForSearch = when {
            overrides.artist != null -> overrides.artist
            !tagValues?.artists.isNullOrEmpty() -> tagValues!!.artists.joinToString(" ")
            else -> artist
        }
        val albumArtist = if (read) tagValues?.albumArtist else indexValues?.albumArtist.present()
        val compilation = tagValues?.compilation == true
        val albumTags = album?.let { TrackTags(album = it, albumArtist = albumArtist, releaseMbid = tagValues?.releaseMbid, compilation = compilation) }
        val (disc, track) = if (read) tagValues?.discNumber to tagValues?.trackNumber else splitTrackNumber(indexValues?.trackNumber)
        val artists = when {
            overrides.artist != null -> listOf(overrides.artist)
            !tagValues?.artists.isNullOrEmpty() -> tagValues!!.artists
            else -> listOfNotNull(artist)
        }
        // A sort tag counts only while its field comes from the tags (docs/library.md §11.1).
        val titleSortTag = tagValues?.titleSort.present().takeIf { overrides.title == null && tagValues?.title != null }
        val artistSortTag = tagValues?.artistSort.present().takeIf { overrides.artist == null && tagValues?.artist != null }
        val albumSortTag = tagValues?.albumSort.present().takeIf { overrides.album == null && tagValues?.album != null }
        val albumArtistSortTag = tagValues?.albumArtistSort.present()
        // Each text normalized once: the title and file name for this file, the others for the batch (TextForms).
        // The genres lists group by (docs/library.md §19.1): the tags' once read, else the index's split as the tags are.
        val genres = if (read) tagValues?.genres.orEmpty() else indexValues?.genre?.split(';')?.map(String::trim)?.filter { it.present() != null }.orEmpty()
        val titleForm = forms.once(title)
        val fileForm = forms.once(fileName)
        val artistForm = artist?.let(forms::shared)
        val albumForm = album?.let(forms::shared)
        return DisplayValues(
            albumKey = albumTags?.let { AlbumKey.of(it, volume, relativePath, { text -> forms.shared(text).normalized }, forms::albumFolder) },
            albumArtist = albumArtist,
            compilation = compilation,
            year = if (read) yearOf(tagValues?.date) ?: yearOf(tagValues?.originalDate) else indexValues?.year?.takeIf { it > 0 },
            discNumber = disc,
            trackNumber = track,
            displayArtists = DisplayArtists.of(artists, forms, if (artists.size == 1) artistSortTag else null),
            displayGenres = DisplayGenres.of(genres, forms),
            artistKey = artistForm?.let { DisplayArtists.keyOf(it.text, it.normalized) },
            albumSearch = if (albumForm == null) "" else TextForms.searchText(albumForm, (albumArtist ?: artist)?.let(forms::shared)),
            titleSort = titleSortTag?.let(forms::sortKey) ?: titleForm.sortKey,
            artistSort = artist?.let { forms.sortKey(artistSortTag ?: it) },
            albumSort = album?.let { forms.sortKey(albumSortTag ?: it) },
            albumArtistSort = albumArtist?.let { forms.sortKey(albumArtistSortTag ?: it) },
            fileSort = fileForm.sortKey,
            title = title,
            artist = artist,
            album = album,
            durationMs = exactDurationMs?.takeIf { it > 0 } ?: index?.durationMs?.takeIf { it > 0 }
                ?: tagLengthMs?.takeIf { it > 0 },
            searchText = TextForms.searchText(
                titleForm,
                artistsForSearch?.let(forms::shared),
                albumForm,
                (tagValues?.composers?.joinToString(" ") ?: indexValues?.composer.present())?.let(forms::shared),
                (tagValues?.genres?.joinToString(" ") ?: indexValues?.genre.present())?.let(forms::shared),
                fileForm,
            ),
            // Unknown until read, as the tag pass soon does: the index fills albums with folder names (docs/playback.md
            // §2.2). A file the pass could not read keeps the index's values, so they are judged (docs/library.md §17.1).
            reasons = when {
                read -> {
                    var reasons = 0
                    if (titleFromLevels == null) reasons = reasons or TITLE_FROM_FILE_NAME
                    if (artist == null) reasons = reasons or NO_ARTIST
                    if (album == null) reasons = reasons or NO_ALBUM
                    if (!embeddedCover) reasons = reasons or NO_COVER
                    if (tags?.recordingMbid == null) reasons = reasons or NO_RECORDING_ID
                    reasons
                }
                failed -> {
                    // No picture and no MusicBrainz ID are known of a file none of whose tags were read.
                    var reasons = NO_COVER or NO_RECORDING_ID
                    if (titleFromLevels == null || titleFromLevels == fileName) reasons = reasons or TITLE_FROM_FILE_NAME
                    if (artist == null) reasons = reasons or NO_ARTIST
                    if (album == null || album == folderName(relativePath)) reasons = reasons or NO_ALBUM
                    reasons
                }
                else -> null
            },
        )
    }

    /** The name of the folder [relativePath] ends in, which the index gives a file without an album tag as its album. */
    private fun folderName(relativePath: String): String? = relativePath.trimEnd('/').substringAfterLast('/').ifEmpty { null }

    private val fourDigits = Regex("""^\d{4}""")

    /** The year of a date tag: its first four digits, or null. */
    private fun yearOf(date: String?): Int? = date?.trim()?.let { fourDigits.find(it) }?.value?.toInt()?.takeIf { it > 0 }

    /** The index's track number holds the disc in its thousands (`2005` is disc 2, track 5); 0 is none. */
    private fun splitTrackNumber(number: Int?): Pair<Int?, Int?> {
        if (number == null || number <= 0) return null to null
        return if (number >= 1000) number / 1000 to (number % 1000).takeIf { it > 0 } else null to number
    }

    /** Media index placeholders count as empty. */
    private fun String?.present(): String? = this?.takeUnless { it.isBlank() || it == MediaStore.UNKNOWN_STRING }
}
