package io.github.lorddetson.piplayer.net

import io.github.lorddetson.piplayer.BuildConfig
import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class UserAgentTest {

    @Test
    fun `contains app name, version and contact in the MusicBrainz format`() {
        assertThat(UserAgent.of("0.1.0")).isEqualTo("PiPlayer/0.1.0 ( https://github.com/LordDetson/piplayer )")
    }

    @Test
    fun `keeps a debug suffix of the version name`() {
        assertThat(UserAgent.of("0.1.0-debug"))
            .isEqualTo("PiPlayer/0.1.0-debug ( https://github.com/LordDetson/piplayer )")
    }

    @Test
    fun `rejects a blank version name`() {
        assertThatThrownBy { UserAgent.of(" ") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `rejects a version name with spaces, which would break the header format`() {
        assertThatThrownBy { UserAgent.of("0.1 beta") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `rejects a version name with separators, which would not make one product token`() {
        // RFC 9110 tchar: a product version may not carry ( ) / ; , " or the other separators.
        for (version in listOf("0.1(rc)", "1/2", "0.1;x", "0.1,2", "0.1\"x", "0.1[x]", "0.1{x}", "0.1@x", "0.1=x")) {
            assertThatThrownBy { UserAgent.of(version) }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `accepts every token character in the version name`() {
        assertThat(UserAgent.of("0.1.0-rc_1+b.2~x!#\$%&'*^`|"))
            .isEqualTo("PiPlayer/0.1.0-rc_1+b.2~x!#\$%&'*^`| ( https://github.com/LordDetson/piplayer )")
    }

    @Test
    fun `the build's User-Agent carries its version name and the contact from BuildConfig`() {
        assertThat(UserAgent.forBuild())
            .isEqualTo("PiPlayer/${BuildConfig.VERSION_NAME} ( ${BuildConfig.CONTACT_URL} )")
    }

    @Test
    fun `the build's contact is piplayer_contactUrl, or the repository URL when it is not set`() {
        // Gradle hands the property to the tests only when it is set (app/build.gradle.kts), as in a fork.
        val property = System.getProperty("piplayer.contactUrl")?.trim()
        assertThat(BuildConfig.CONTACT_URL).isEqualTo(property ?: "https://github.com/LordDetson/piplayer")
        if (property == null) {
            assertThat(UserAgent.DEFAULT_CONTACT).isEqualTo(BuildConfig.CONTACT_URL)
            assertThat(UserAgent.forBuild())
                .isEqualTo("PiPlayer/${BuildConfig.VERSION_NAME} ( https://github.com/LordDetson/piplayer )")
        }
    }

    @Test
    fun `a fork's contact takes the place of the repository URL`() {
        assertThat(UserAgent.of("0.1.0", "https://example.org/my-fork"))
            .isEqualTo("PiPlayer/0.1.0 ( https://example.org/my-fork )")
        assertThat(UserAgent.of("0.1.0", "fork@example.org")).isEqualTo("PiPlayer/0.1.0 ( fork@example.org )")
    }

    @Test
    fun `rejects a blank contact`() {
        for (contact in listOf("", " ", "\t")) {
            assertThatThrownBy { UserAgent.of("0.1.0", contact) }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `rejects a contact with spaces`() {
        assertThatThrownBy { UserAgent.of("0.1.0", "https://example.org/my fork") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { UserAgent.of("0.1.0", "https://example.org/\n") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `rejects characters an HTTP header cannot carry, in the contact and in the version name`() {
        for (contact in listOf("https://example.org/fork\u0000", "https://пример.рф/fork", "https://example.org/é")) {
            assertThatThrownBy { UserAgent.of("0.1.0", contact) }.isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThatThrownBy { UserAgent.of("0.1.0-ß") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `rejects a contact with parentheses, which would end the comment early`() {
        assertThatThrownBy { UserAgent.of("0.1.0", "https://example.org/a)b") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { UserAgent.of("0.1.0", "(https://example.org)") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `rejects a contact with quotes or backslashes, as the build does`() {
        for (contact in listOf("https://example.org/\"fork\"", "https://example.org/a\\b")) {
            assertThatThrownBy { UserAgent.of("0.1.0", contact) }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `shipped code takes the build's User-Agent, so a fork's contact reaches every request`() {
        val callers = listOf("src/main", "src/debug", "src/release", "src/benchmark")
            .flatMap { set -> File(set).walkTopDown().filter { it.isFile && it.extension == "kt" } }
            .filter { Regex("""\bUserAgent\.of\(""").containsMatchIn(it.readText()) }
            .map { it.invariantSeparatorsPath }
        assertThat(callers).isEmpty()
    }
}
