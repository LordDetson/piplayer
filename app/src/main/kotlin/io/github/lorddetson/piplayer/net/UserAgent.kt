package io.github.lorddetson.piplayer.net

/** The User-Agent PiPlayer sends to MusicBrainz, AcoustID and Cover Art Archive. */
object UserAgent {

    /** Contact required by the MusicBrainz rate-limiting rules. */
    const val CONTACT = "https://github.com/LordDetson"

    fun of(versionName: String): String {
        require(versionName.isNotBlank() && versionName.none { it.isWhitespace() }) {
            "Version name must be a single token without spaces: '$versionName'"
        }
        return "PiPlayer/$versionName ( $CONTACT )"
    }
}
