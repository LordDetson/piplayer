package io.github.lorddetson.piplayer.covers

import java.security.SecureRandom

/**
 * The content URIs of covers for controllers outside PiPlayer (docs/library.md §13.8.2), as text:
 * `content://<authority>/<kind>/<id>/<version>`. A song goes by its file ID, an album or an artist by a number this
 * process gives its key; the version is [tag], drawn when the process starts, and the cover [generation] in base 36, at
 * least [GENERATION_WIDTH] digits.
 * Safe to call from any thread.
 */
class CoverUris(
    val authority: String,
    private val tag: String = randomTag(),
    private val generation: () -> Long,
) {

    init {
        require(tag.length == TAG_LENGTH && tag.all(::isVersionChar)) { "A tag is $TAG_LENGTH characters of 0-9 and a-z" }
    }

    /** Album and artist keys by the number this process gave them, from 1 on, and back. */
    private class Keys {
        private val numbers = HashMap<String, Int>()
        private val keys = ArrayList<String>()

        @Synchronized
        fun number(key: String): Int = numbers.getOrPut(key) {
            keys += key
            keys.size
        }

        @Synchronized
        fun key(number: Int): String? = keys.getOrNull(number - 1)
    }

    private val albums = Keys()
    private val artists = Keys()

    private val prefix = "content://$authority/"

    /**
     * The URI of [request]'s cover. A [salt] (characters of 0-9 and a-z) is appended to the version of a song's URI, such as
     * the session's checksum of the picture it shows (docs/library.md §13.8.6); any version names the song alike.
     */
    fun of(request: CoverRequest, salt: String? = null): String {
        val (kind, id) = when (request) {
            is CoverRequest.Track -> SONG to request.fileId
            is CoverRequest.Album -> ALBUM to albums.number(request.key).toLong()
            is CoverRequest.Artist -> ARTIST to artists.number(request.key).toLong()
        }
        require(salt == null || (request is CoverRequest.Track && salt.all(::isVersionChar))) { "Only a song's version takes a salt of 0-9 and a-z" }
        return "$prefix$kind/$id/$tag${generation().toString(36).padStart(GENERATION_WIDTH, '0')}${salt.orEmpty()}"
    }

    /** The length of [of]'s URI for [request], without building it. */
    fun lengthOf(request: CoverRequest): Int {
        val id = when (request) {
            is CoverRequest.Track -> request.fileId
            is CoverRequest.Album -> albums.number(request.key).toLong()
            is CoverRequest.Artist -> artists.number(request.key).toLong()
        }
        // "<kind>/<id>/<tag><generation>" after the prefix.
        return prefix.length + 2 + digits(id, 10) + 1 + TAG_LENGTH + maxOf(GENERATION_WIDTH, digits(generation(), 36))
    }

    /**
     * What a URI of [authority] with [segments] names, or null when it is malformed, of another authority, or names an
     * album or artist by a number that this process did not give. Any version names a song: its file ID means the same in
     * every process and generation.
     */
    fun parse(authority: String?, segments: List<String>): CoverRequest? {
        if (!wellFormed(authority, segments)) return null
        val (kind, idText, version) = segments
        val id = canonicalNumber(idText) ?: return null
        return when (kind) {
            SONG -> CoverRequest.Track(id)
            ALBUM, ARTIST -> {
                // Numbers of another process name other keys.
                if (!version.startsWith(tag) || id > Int.MAX_VALUE) return null
                if (kind == ALBUM) albums.key(id.toInt())?.let(CoverRequest::Album) else artists.key(id.toInt())?.let(CoverRequest::Artist)
            }
            else -> null
        }
    }

    /** Whether a URI of [authority] with [segments] has the form of a cover URI, whatever it names; nothing is looked up. */
    fun wellFormed(authority: String?, segments: List<String>): Boolean {
        if (authority != this.authority || segments.size != 3) return false
        val (kind, id, version) = segments
        return kind in KINDS && canonicalNumber(id) != null && version.length > TAG_LENGTH && version.all(::isVersionChar)
    }

    companion object {
        const val TAG_LENGTH = 4

        /**
         * The generation's digits at least: a new generation keeps a URI's length, which items weigh, so that the borders
         * of buckets stay put (docs/playback.md §10.2) for the first 1 679 616 generations of a process.
         */
        const val GENERATION_WIDTH = 4

        /** The provider's authority for the app [packageName]. */
        fun authorityOf(packageName: String): String = "$packageName.covers"

        /** A new tag: [TAG_LENGTH] random characters of 0-9 and a-z. */
        fun randomTag(): String {
            val random = SecureRandom()
            return String(CharArray(TAG_LENGTH) { Character.forDigit(random.nextInt(36), 36) })
        }

        private const val SONG = "s"
        private const val ALBUM = "a"
        private const val ARTIST = "r"
        private val KINDS = setOf(SONG, ALBUM, ARTIST)

        private fun isVersionChar(c: Char) = c in '0'..'9' || c in 'a'..'z'

        /** The digits of a non-negative [value] in [radix]. */
        private fun digits(value: Long, radix: Int): Int {
            var rest = value
            var count = 1
            while (rest >= radix) {
                rest /= radix
                count++
            }
            return count
        }

        /** [text] as a non-negative number in the one form [of] writes it (no sign, no leading zero), or null. */
        private fun canonicalNumber(text: String): Long? {
            if (text.isEmpty() || !text.all { it in '0'..'9' } || (text.length > 1 && text[0] == '0')) return null
            return text.toLongOrNull()
        }
    }
}
