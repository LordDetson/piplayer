package io.github.lorddetson.piplayer.library.values

import io.github.lorddetson.piplayer.identification.text.TextNormalizer

/**
 * What makes tracks one album (TI §10.1): the release ID, or else the normalized title and album artist within a folder,
 * plus the compilation flag. A last folder named like a disc does not count, so that `CD1` and `CD2` stay one album.
 */
object AlbumKey {

    private const val SEPARATOR = "\u001F"
    private val discFolder = Regex("""(?:cd|dis[ck]|диск)[\s_-]*\d+""", RegexOption.IGNORE_CASE)

    /** Null for a track without an album. [folder] gives the folder part of [relativePath], [folderKey] unless cached. */
    fun of(
        tags: TrackTags,
        volume: String,
        relativePath: String,
        normalize: (String) -> String = TextNormalizer::normalize,
        folder: (String) -> String = ::folderKey,
    ): String? {
        val album = tags.album ?: return null
        tags.releaseMbid?.let { return "mbid:$it" }
        return buildString {
            append(keyOf(album, normalize)).append(SEPARATOR)
            tags.albumArtist?.let { append(keyOf(it, normalize)) }
            append(SEPARATOR).append(volume)
            append(SEPARATOR).append(folder(relativePath))
            append(SEPARATOR).append(if (tags.compilation) '1' else '0')
        }
    }

    /** The folder part of the key of an album in [relativePath]: the path without a disc folder, in lower case. */
    fun folderKey(relativePath: String): String = albumFolder(relativePath).lowercase()

    /** The normalized text; a name of punctuation alone, such as `+`, normalizes to nothing and keeps itself. */
    private fun keyOf(text: String, normalize: (String) -> String) = normalize(text).ifEmpty { text.lowercase() }

    /** [relativePath] without a last folder named like a disc, unless that is all it holds. */
    private fun albumFolder(relativePath: String): String {
        val parts = relativePath.trimEnd('/').split('/')
        if (parts.size < 2 || !discFolder.matches(parts.last())) return relativePath
        return parts.dropLast(1).joinToString("/", postfix = "/")
    }
}
