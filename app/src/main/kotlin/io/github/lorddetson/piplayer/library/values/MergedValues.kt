package io.github.lorddetson.piplayer.library.values

import android.provider.MediaStore
import io.github.lorddetson.piplayer.identification.text.FileNameParser
import io.github.lorddetson.piplayer.identification.text.TextNormalizer
import io.github.lorddetson.piplayer.library.search.SearchText
import io.github.lorddetson.piplayer.library.sync.DisplayValues

/** What the media index says of a file, precedence level 4 (TI §7.1). */
data class IndexValues(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val composer: String? = null,
    val genre: String? = null,
    val durationMs: Long? = null,
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
     * a header, so it comes last (TI §3.5).
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
        return DisplayValues(
            title = title,
            artist = artist,
            album = album,
            durationMs = exactDurationMs?.takeIf { it > 0 } ?: index?.durationMs?.takeIf { it > 0 }
                ?: tagLengthMs?.takeIf { it > 0 },
            titleKey = TextNormalizer.normalize(title),
            searchText = SearchText.of(
                title,
                artistsForSearch,
                album,
                tagValues?.composers?.joinToString(" ") ?: indexValues?.composer.present(),
                tagValues?.genres?.joinToString(" ") ?: indexValues?.genre.present(),
                fileName,
            ),
            // Unknown until read: the index fills albums with folder names (docs/playback.md §2.2).
            reasons = if (!read) {
                null
            } else {
                var reasons = 0
                if (titleFromLevels == null) reasons = reasons or TITLE_FROM_FILE_NAME
                if (artist == null) reasons = reasons or NO_ARTIST
                if (album == null) reasons = reasons or NO_ALBUM
                if (!embeddedCover) reasons = reasons or NO_COVER
                if (tags?.recordingMbid == null) reasons = reasons or NO_RECORDING_ID
                reasons
            },
        )
    }

    /** Media index placeholders count as empty. */
    private fun String?.present(): String? = this?.takeUnless { it.isBlank() || it == MediaStore.UNKNOWN_STRING }
}
