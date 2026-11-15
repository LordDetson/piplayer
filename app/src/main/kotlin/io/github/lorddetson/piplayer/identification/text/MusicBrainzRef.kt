package io.github.lorddetson.piplayer.identification.text

import java.util.Locale

/** A MusicBrainz link or ID pasted into manual search, as a typed lookup (TI §3.3). MBIDs are in lower case. */
sealed interface MusicBrainzRef {

    /** Look up this recording. */
    data class Recording(val mbid: String) : MusicBrainzRef

    /** Look up this release. */
    data class Release(val mbid: String) : MusicBrainzRef

    /** A bare MBID, which could name either: looked up as a recording first, then as a release. */
    data class AnyEntity(val mbid: String) : MusicBrainzRef

    /**
     * A `musicbrainz.org` link PiPlayer cannot look up: another entity (artist, release group, work, track), a search
     * page or a malformed ID. [entity] is the link's first path part after an optional `/ws/2`, empty for none.
     */
    data class Unsupported(val entity: String) : MusicBrainzRef

    companion object {

        private val mbid = Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")
        private const val HOST = "musicbrainz.org"
        private val schemes = setOf("http", "https")

        /** A URI scheme at the end of the text before `://`; what comes before it is a label glued to the link. */
        private val schemeAtEnd = Regex("""[A-Za-z][A-Za-z0-9+.-]*$""")

        /** Brackets, quotes and sentence punctuation that a pasted link or ID can come wrapped in. */
        private const val WRAPPING = "()[]{}<>\"'.,;:!?«»“”‘’"

        /**
         * The reference in [text], or null when no word of it is a bare MBID or a `musicbrainz.org` link (also one of
         * its subdomains, with or without the scheme). Punctuation around a word is not part of it. A recording or
         * release link wins over a bare MBID, which wins over another MusicBrainz link; among equals the first counts.
         */
        fun parse(text: String): MusicBrainzRef? {
            val found = Spaces.plain(text).split(' ').map { it.trim { char -> char in WRAPPING } }
                .filter { it.isNotEmpty() }
                .mapNotNull(::reference)
            return found.firstOrNull { it is Recording || it is Release }
                ?: found.firstOrNull { it is AnyEntity }
                ?: found.firstOrNull()
        }

        private fun reference(word: String): MusicBrainzRef? {
            if (mbid.matches(word)) return AnyEntity(word.lowercase(Locale.ROOT))
            if (!word.contains(HOST, ignoreCase = true)) return null
            // Split by hand: a URI parser rejects links that are still MusicBrainz links, such as a broken escape.
            val separator = word.indexOf("://")
            val pathStart = word.indexOfFirst { it == '/' || it == '?' || it == '#' }
            // A "://" after the first '/', '?' or '#' belongs to the path, query or fragment of a link without a scheme.
            val hasScheme = separator >= 0 && separator < pathStart
            val rest = if (hasScheme) {
                val scheme = schemeAtEnd.find(word.substring(0, separator))?.value?.lowercase(Locale.ROOT)
                if (scheme !in schemes) return null
                word.substring(separator + 3)
            } else {
                word
            }
            val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.takeIf { it >= 0 } ?: rest.length
            val authority = rest.substring(0, authorityEnd).substringAfterLast('@').lowercase(Locale.ROOT)
            // With a scheme the host ends at an optional port. Without one a label can come glued on with ':'
            // ("Link:musicbrainz.org/..."), so any part between the colons can be the host.
            val hosts = if (hasScheme) listOf(authority.substringBefore(':')) else authority.split(':')
            hosts.firstOrNull { it == HOST || it.endsWith(".$HOST") } ?: return null
            var parts = rest.substring(authorityEnd).substringBefore('?').substringBefore('#')
                .split('/').filter { it.isNotEmpty() }
            if (parts.size >= 2 && parts[0] == "ws" && parts[1] == "2") parts = parts.drop(2)
            val entity = parts.firstOrNull()?.lowercase(Locale.ROOT).orEmpty()
            val id = parts.getOrNull(1)?.takeIf(mbid::matches)?.lowercase(Locale.ROOT)
            return when {
                id != null && entity == "recording" -> Recording(id)
                id != null && entity == "release" -> Release(id)
                else -> Unsupported(entity)
            }
        }
    }
}

/** What manual search does with the user's text (TI §3.3). */
sealed interface ManualRequest {

    /** A pasted recording or release link, or a bare MBID: looked up, not searched. */
    data class Lookup(val ref: MusicBrainzRef) : ManualRequest

    /** Another MusicBrainz link: refused with a hint, never sent as search text. */
    data class Refused(val ref: MusicBrainzRef.Unsupported) : ManualRequest

    /** Free text, searched with `dismax=true`. */
    data class Search(val query: RecordingQuery) : ManualRequest

    companion object {

        /** Null for blank text, which sends nothing. */
        fun of(text: String): ManualRequest? = when (val ref = MusicBrainzRef.parse(text)) {
            is MusicBrainzRef.Unsupported -> Refused(ref)
            null -> TextQueryBuilder.manual(text)?.let(::Search)
            else -> Lookup(ref)
        }
    }
}
