package io.github.lorddetson.piplayer.library.values

import android.provider.MediaStore
import io.github.lorddetson.piplayer.identification.text.FileNameParser
import io.github.lorddetson.piplayer.library.lists.DisplayArtists
import io.github.lorddetson.piplayer.library.lists.DisplayGenres
import io.github.lorddetson.piplayer.library.sync.DisplayValues
import io.github.lorddetson.piplayer.settings.ProtectedField

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

/**
 * The winners of levels 1 and 2 per part (TI §7.1), as `decided_mirror` holds them: a part that is here wins, also with
 * an empty value.
 */
data class FieldOverrides(val decided: Map<DecidedField, Decided>) {

    /** User edits of the title, artist and album, as tests write them. */
    constructor(title: String? = null, artist: String? = null, album: String? = null) : this(
        buildMap {
            title?.let { put(DecidedField.TITLE, Decided(it, DecisionKind.LEVEL_USER_EDIT, 0)) }
            artist?.let { put(DecidedField.ARTIST, Decided(it, DecisionKind.LEVEL_USER_EDIT, 0)) }
            album?.let { put(DecidedField.ALBUM, Decided(it, DecisionKind.LEVEL_USER_EDIT, 0)) }
        },
    )

    operator fun get(part: DecidedField): Decided? = decided[part]

    val title: String? get() = decided[DecidedField.TITLE]?.value
    val artist: String? get() = decided[DecidedField.ARTIST]?.value
    val album: String? get() = decided[DecidedField.ALBUM]?.value

    companion object {
        val NONE = FieldOverrides(emptyMap<DecidedField, Decided>())
    }
}

/** A field part's value with the precedence level it comes from (TI §7.1); [value] null is empty. */
data class Sourced(val value: String?, val level: Int)

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
        val decided = overrides.decided

        // A decided part wins with its value, also an empty one (TI §7.1); then the tags once read, else the index.
        fun pick(part: DecidedField, file: () -> String?): String? = if (part in decided) decided[part]?.value else file()
        val titleFromLevels = pick(DecidedField.TITLE) { tagValues?.title ?: indexValues?.title.present() }
        val title = titleFromLevels ?: parsed?.title ?: fileName
        val artistDecided = DecidedField.ARTIST in decided
        val artist = pick(DecidedField.ARTIST) { tagValues?.artist ?: indexValues?.artist.present() } ?: parsed?.artist
        val album = pick(DecidedField.ALBUM) { tagValues?.album ?: indexValues?.album.present() }
        // The credited names of a decided artist, else the decided artist itself; the tags' list otherwise.
        val artists = when {
            artistDecided -> DecidedField.creditedNames(decided[DecidedField.ARTIST_CREDITS]?.value).ifEmpty { listOfNotNull(artist) }
            !tagValues?.artists.isNullOrEmpty() -> tagValues!!.artists
            else -> listOfNotNull(artist)
        }
        val artistsForSearch = if (artistDecided || !tagValues?.artists.isNullOrEmpty()) artists.joinToString(" ").ifEmpty { null } else artist
        val albumArtist = pick(DecidedField.ALBUM_ARTIST) { if (read) tagValues?.albumArtist else indexValues?.albumArtist.present() }
        val compilation = if (DecidedField.COMPILATION in decided) decided[DecidedField.COMPILATION]?.value == "1" else tagValues?.compilation == true
        val albumTags = album?.let { TrackTags(album = it, albumArtist = albumArtist, releaseMbid = tagValues?.releaseMbid, compilation = compilation) }
        val (fileDisc, fileTrack) = if (read) tagValues?.discNumber to tagValues?.trackNumber else splitTrackNumber(indexValues?.trackNumber)
        val disc = if (DecidedField.DISC_NUMBER in decided) decided[DecidedField.DISC_NUMBER]?.value?.toIntOrNull() else fileDisc
        val track = if (DecidedField.TRACK_NUMBER in decided) decided[DecidedField.TRACK_NUMBER]?.value?.toIntOrNull() else fileTrack
        // The index's year is the date field's level 4 (TI §7.1): a decided date hides it as the tags' date does, also when it
        // is empty, and a decided original date does not; that gives the year only when neither does.
        val year = if (read || DecidedField.DATE in decided) {
            yearOf(pick(DecidedField.DATE) { tagValues?.date }) ?: yearOf(pick(DecidedField.ORIGINAL_DATE) { tagValues?.originalDate })
        } else {
            indexValues?.year?.takeIf { it > 0 } ?: yearOf(decided[DecidedField.ORIGINAL_DATE]?.value)
        }
        // A sort tag counts only while its field comes from the tags (docs/library.md §11.1); a decided field brings its own.
        val titleSortTag = tagValues?.titleSort.present().takeIf { DecidedField.TITLE !in decided && tagValues?.title != null }
        val artistSortTag = if (artistDecided) {
            decided[DecidedField.ARTIST_SORT]?.value.present()
        } else {
            tagValues?.artistSort.present().takeIf { tagValues?.artist != null }
        }
        val albumSortTag = tagValues?.albumSort.present().takeIf { DecidedField.ALBUM !in decided && tagValues?.album != null }
        val albumArtistSortTag = if (DecidedField.ALBUM_ARTIST in decided) {
            decided[DecidedField.ALBUM_ARTIST_SORT]?.value.present()
        } else {
            tagValues?.albumArtistSort.present()
        }
        val recordingId = if (DecidedField.RECORDING_MBID in decided) decided[DecidedField.RECORDING_MBID]?.value else tags?.recordingMbid
        val cover = embeddedCover || decided[DecidedField.COVER]?.value != null
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
            year = year,
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
                    if (!cover) reasons = reasons or NO_COVER
                    if (recordingId == null) reasons = reasons or NO_RECORDING_ID
                    reasons
                }
                failed -> {
                    // No picture and no MusicBrainz ID are known of a file none of whose tags were read, unless decided.
                    var reasons = 0
                    if (!cover) reasons = reasons or NO_COVER
                    if (recordingId == null) reasons = reasons or NO_RECORDING_ID
                    if (titleFromLevels == null || titleFromLevels == fileName) reasons = reasons or TITLE_FROM_FILE_NAME
                    if (artist == null) reasons = reasons or NO_ARTIST
                    if (album == null || album == folderName(relativePath)) reasons = reasons or NO_ALBUM
                    reasons
                }
                else -> null
            },
            badgeState = DecisionResolver.badgeOf(decided),
        )
    }

    /**
     * Every part of every field of TI §1 with its value and the level it comes from, by the precedence of TI §7.1: the
     * decided [overrides] (levels 1 and 2), the [tags] once [read] (3), the [index] while unread (4), and the file-name
     * guess (5) for the title, the artist and the track number. A part no level gives is missing. Track details resolve
     * by it (docs/library.md §18.2).
     */
    fun parts(
        displayName: String,
        index: IndexValues?,
        tags: TrackTags?,
        read: Boolean,
        overrides: FieldOverrides = FieldOverrides.NONE,
    ): Map<DecidedField, Sourced> {
        val result = LinkedHashMap<DecidedField, Sourced>()
        for ((part, decided) in overrides.decided) result[part] = Sourced(decided.value, decided.level)
        fun offer(part: DecidedField, value: String?, level: Int) {
            if (part !in result && value != null) result[part] = Sourced(value, level)
        }
        // Level 3 once read; a decided field keeps all of its parts (TI §7.1), so the tags fill only fields not decided.
        val decidedFields = overrides.decided.keys.mapTo(HashSet()) { it.group }
        if (read) {
            for ((part, value) in DecisionContent.ofTags(tags)) if (part.group !in decidedFields) offer(part, value.present(), DecisionKind.LEVEL_TAGS)
        } else if (index != null) {
            // Level 4 while unread: the values the media index has.
            val (disc, track) = splitTrackNumber(index.trackNumber)
            val fromIndex = mapOf(
                DecidedField.TITLE to index.title.present(),
                DecidedField.ARTIST to index.artist.present(),
                DecidedField.ALBUM to index.album.present(),
                DecidedField.ALBUM_ARTIST to index.albumArtist.present(),
                DecidedField.DATE to index.year?.takeIf { it > 0 }?.toString(),
                DecidedField.TRACK_NUMBER to track?.toString(),
                DecidedField.DISC_NUMBER to disc?.toString(),
            )
            for ((part, value) in fromIndex) if (part.group !in decidedFields) offer(part, value, DecisionKind.LEVEL_INDEX)
        }
        // Level 5: the file-name guess, for what is still empty; also for a decided title or artist that is empty (TI §7.1),
        // as the lists show them.
        val parsed = FileNameParser.parse(displayName)
        val fileName = displayName.substringBeforeLast('.').ifEmpty { displayName }
        fun guess(part: DecidedField, value: String?) {
            if (value == null) return
            if (part.group !in decidedFields) offer(part, value, DecisionKind.LEVEL_FILE_NAME)
            else if (result[part]?.value == null) result[part] = Sourced(value, DecisionKind.LEVEL_FILE_NAME)
        }
        guess(DecidedField.TITLE, parsed?.title ?: fileName)
        guess(DecidedField.ARTIST, parsed?.artist)
        if (ProtectedField.TRACK_NUMBER !in decidedFields) {
            offer(DecidedField.TRACK_NUMBER, parsed?.trackNumber?.takeIf { it > 0 }?.toString(), DecisionKind.LEVEL_FILE_NAME)
        }
        return result
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
