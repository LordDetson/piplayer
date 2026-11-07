package io.github.lorddetson.piplayer.playback

/**
 * The media IDs of queue items (docs/playback.md §9.4): a library song of the compact request by its file ID, an entry
 * an edit added by its file ID and its number (`<file ID>/a<number>`), a file opened from another app by its URI.
 *
 * They are read for every entry of a 20 000-song queue on the main thread at each save and edit, so they are parsed by
 * hand rather than with regular expressions.
 */
object QueueIds {

    /** The media ID of an added entry. */
    fun added(number: Long, fileId: Long): String = "$fileId/a$number"

    /** The file ID a library song or an added entry plays, or null for anything else. */
    fun fileId(mediaId: String): Long? {
        val slash = mediaId.indexOf('/')
        if (slash < 0) return number(mediaId, 0, mediaId.length)
        return if (addedNumber(mediaId) != null) number(mediaId, 0, slash) else null
    }

    /** The number of an added entry, or null for anything else. */
    fun addedNumber(mediaId: String): Long? {
        val slash = mediaId.indexOf('/')
        if (slash <= 0 || slash + 1 >= mediaId.length || mediaId[slash + 1] != 'a') return null
        if (number(mediaId, 0, slash) == null) return null
        return number(mediaId, slash + 2, mediaId.length)
    }

    /** The entry [mediaId] names at [row], or null when it is neither a song, an added entry nor one of [openItems] there. */
    fun key(mediaId: String, row: Int, openItems: List<SavedOpenItem>): EntryKey? {
        if (mediaId.indexOf('/') < 0) number(mediaId, 0, mediaId.length)?.let { return EntryKey.Song(it) }
        addedNumber(mediaId)?.let { return EntryKey.Added(it) }
        val index = openItems.indexOfFirst { it.index == row && it.uri == mediaId }
        return if (index >= 0) EntryKey.Opened(index) else null
    }

    /**
     * The entries of a queue of [mediaIds] by their keys, or null when one of them is neither a song, an added entry nor
     * one of [openItems] at its row.
     */
    fun keys(mediaIds: List<String>, openItems: List<SavedOpenItem>): List<EntryKey>? =
        mediaIds.mapIndexed { row, id -> key(id, row, openItems) ?: return null }

    /** The decimal digits of [text] from [from] to [to] as a number: 1 to 19 digits, nothing else. */
    private fun number(text: String, from: Int, to: Int): Long? {
        if (to - from !in 1..MAX_DIGITS) return null
        for (i in from until to) if (text[i] !in '0'..'9') return null
        return text.substring(from, to).toLongOrNull()
    }

    private const val MAX_DIGITS = 19
}
