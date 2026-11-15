package io.github.lorddetson.piplayer.net

import io.github.lorddetson.piplayer.BuildConfig

/**
 * The User-Agent PiPlayer sends to MusicBrainz, AcoustID and Cover Art Archive (TI §9): `PiPlayer/<version>
 * ( <contact> )`, the format the MusicBrainz rate-limiting rules ask for.
 */
object UserAgent {

    /**
     * This repository, the contact of a build that does not set the Gradle property `piplayer.contactUrl`; the build
     * script has the same default, and a JVM test keeps the two equal.
     */
    const val DEFAULT_CONTACT = "https://github.com/LordDetson/piplayer"

    /**
     * The User-Agent of this build: its version name and the contact Gradle put into [BuildConfig.CONTACT_URL]. Shipped
     * code always sends this one, so a fork's contact reaches every request.
     */
    fun forBuild(): String = of(BuildConfig.VERSION_NAME, BuildConfig.CONTACT_URL)

    /**
     * The User-Agent of [versionName] with [contact], a URL or e-mail address where the services can reach whoever runs
     * the build; tests use it with fixed values. Both must be one token of printable ASCII, the only characters a header
     * value carries: a space would split the header's product and comment. The contact follows the build's own rule
     * (app/build.gradle.kts): no quotes or backslashes, which would also break the generated BuildConfig, and no
     * parentheses, which would end the comment early.
     */
    fun of(versionName: String, contact: String = DEFAULT_CONTACT): String {
        require(versionName.isToken()) { "Version name must be a single token without spaces: '$versionName'" }
        require(contact.isToken() && contact.none { it in CONTACT_EXCLUDED }) {
            "Contact must be a single token without spaces, quotes, backslashes or parentheses: '$contact'"
        }
        return "PiPlayer/$versionName ( $contact )"
    }

    private const val CONTACT_EXCLUDED = "\"\\()"

    /** Not empty, and only printable ASCII without the space. */
    private fun String.isToken(): Boolean = isNotEmpty() && all { it in '!'..'~' }
}
