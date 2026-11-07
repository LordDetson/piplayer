package io.github.lorddetson.piplayer.playback

import io.github.lorddetson.piplayer.library.lists.ListKind
import io.github.lorddetson.piplayer.library.lists.SongLinks
import io.github.lorddetson.piplayer.library.lists.SongQuery
import io.github.lorddetson.piplayer.library.lists.SongSort
import io.github.lorddetson.piplayer.library.songs.Song
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.net.URLEncoder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * A compact queue request (docs/playback.md §2.3, docs/library.md §6.4): play [list] from [startSongId], found at
 * [startRow] when the list was shown. It travels as the media ID of one media item, so Media3 controllers and the
 * platform's play-from-media-ID requests use one form.
 */
data class QueueRequest(val startSongId: Long, val startRow: Int, val list: SongQuery = SongQuery.SONGS) {

    init {
        require(startRow >= 0) { "startRow must not be negative" }
    }

    val mediaId: String
        get() = "q2/${list.kind.token}?key=${encode(list.key)}&sort=${list.sort.token}&rev=${if (list.reversed) 1 else 0}" +
            "&q=${encode(list.search)}&start=$startSongId&index=$startRow"

    companion object {
        /** Version 1, the song list by title, which saved queues may still hold. */
        private val VERSION_1 = Regex("""q1/songs\?sort=title&start=(-?\d{1,19})&index=(\d{1,9})""")
        private val VERSION_2 = Regex(
            """q2/([a-z]+)\?key=([^&]*)&sort=([a-z]+)&rev=([01])&q=([^&]*)&start=(-?\d{1,19})&index=(\d{1,9})""",
        )

        /** The request [mediaId] names, or null if it names none. */
        fun parse(mediaId: String): QueueRequest? {
            VERSION_1.matchEntire(mediaId)?.let { match ->
                val id = match.groupValues[1].toLongOrNull() ?: return null
                return QueueRequest(id, match.groupValues[2].toInt())
            }
            val match = VERSION_2.matchEntire(mediaId) ?: return null
            val (kindToken, rawKey, sortToken, reversed, rawSearch, start, index) = match.destructured
            val kind = ListKind.of(kindToken) ?: return null
            val sort = SongSort.of(sortToken) ?: return null
            val key = decode(rawKey) ?: return null
            val search = decode(rawSearch) ?: return null
            if ((kind == ListKind.SONGS) != key.isEmpty()) return null
            val list = SongQuery(kind, key, sort, reversed == "1", search)
            if ((kind == ListKind.FOLDER || kind == ListKind.TREE) && list.folder == null) return null
            val id = start.toLongOrNull() ?: return null
            return QueueRequest(id, index.toInt(), list)
        }

        /** [request] with its key followed to where the saved song is now, when the key names nothing any more. */
        fun rekeyed(request: QueueRequest, keyExists: Boolean, links: SongLinks?): QueueRequest {
            if (keyExists || links == null) return request
            val key = when (request.list.kind) {
                ListKind.ALBUM -> links.albumKey
                ListKind.ARTIST -> links.artists.firstOrNull()?.key
                // A folder's key is its place, which no read of the tags changes; the songs list has none.
                else -> null
            } ?: return request
            return request.copy(list = request.list.copy(key = key))
        }

        /** Where the queue of [songs] starts for [request]: the start song, else its row, else -1 for an empty list. */
        fun startIndex(songs: List<Song>, request: QueueRequest): Int {
            if (songs.isEmpty()) return -1
            val index = songs.indexOfFirst { it.id == request.startSongId }
            return if (index >= 0) index else request.startRow.coerceAtMost(songs.lastIndex)
        }

        /** Percent-encoding with `%20` for spaces, so the media ID holds no space, `&`, `=`, `#` or control character. */
        // The overloads that take a Charset need Android 13.
        private fun encode(text: String): String = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

        /** Null for text that is not percent-encoded UTF-8. */
        private fun decode(text: String): String? {
            if ('+' in text) return null
            val bytes = try {
                URLDecoder.decode(text, "ISO-8859-1").toByteArray(StandardCharsets.ISO_8859_1)
            } catch (_: IllegalArgumentException) {
                return null
            }
            return try {
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (_: CharacterCodingException) {
                null
            }
        }
    }
}
