package io.github.lorddetson.piplayer.library.values

import io.github.lorddetson.piplayer.identification.text.TextNormalizer

/**
 * What makes tracks one album (TI §10.1): the release ID, or else the normalized title and album artist within a folder,
 * plus the compilation flag. A last folder named like a disc does not count, so that `CD1` and `CD2` stay one album.
 */
object AlbumKey {

    private const val SEPARATOR = "\u001F"
    private val discFolder = Regex("""(?:cd|dis[ck]|диск)[\s_-]*\d+""", RegexOption.IGNORE_CASE)

    /** Null for a track without an album. */
    fun of(tags: TrackTags, volume: String, relativePath: String): String? {
        tags.releaseMbid?.let { return "mbid:$it" }
        val album = tags.album ?: return null
        return listOf(
            TextNormalizer.normalize(album),
            tags.albumArtist?.let(TextNormalizer::normalize).orEmpty(),
            volume,
            albumFolder(relativePath).lowercase(),
            if (tags.compilation) "1" else "0",
        ).joinToString(SEPARATOR)
    }

    /** [relativePath] without a last folder named like a disc, unless that is all it holds. */
    private fun albumFolder(relativePath: String): String {
        val parts = relativePath.trimEnd('/').split('/')
        if (parts.size < 2 || !discFolder.matches(parts.last())) return relativePath
        return parts.dropLast(1).joinToString("/", postfix = "/")
    }
}
