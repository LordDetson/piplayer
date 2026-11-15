package io.github.lorddetson.piplayer.identification

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The AcoustID application key ships in every build (TI §9). It is not a secret, but it must never reach a log line, a
 * crash report, the debug spike's screen text or a `toString`, so only the wiring and the client may touch it. This is a
 * tripwire, not a proof: a new file that names the key's field or its usual parameter names fails here and has to be
 * added on purpose, after a look at where the value goes; code that renames it on the way is left to review.
 */
class ApiKeyReadersTest {

    @Test
    fun `only the wiring and the AcoustID client touch the application key in shipped code`() {
        val readers = SHIPPED_SOURCE_SETS
            .flatMap { set -> File(set).walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") } }
            .filter { file -> KEY_NAMES.containsMatchIn(file.readText()) }
            .map { it.invariantSeparatorsPath }
            .sorted()
        assertThat(readers).containsExactly(
            "src/debug/kotlin/io/github/lorddetson/piplayer/spike/SpikeServices.kt",
            "src/main/kotlin/io/github/lorddetson/piplayer/PiPlayerApplication.kt",
            "src/main/kotlin/io/github/lorddetson/piplayer/identification/IdentificationServices.kt",
            "src/main/kotlin/io/github/lorddetson/piplayer/identification/acoustid/AcoustIdClient.kt",
        )
    }

    @Test
    fun `the crash reports never read build fields beyond the version and the build type`() {
        val fields = File("src/main/kotlin/io/github/lorddetson/piplayer/crash").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { BUILD_CONFIG_FIELD.findAll(it.readText()).map { match -> match.groupValues[1] } }
            .toSet()
        assertThat(fields).isNotEmpty.isSubsetOf("VERSION_NAME", "VERSION_CODE", "BUILD_TYPE", "DEBUG")
    }

    private companion object {
        /** The source sets that go into an APK; tests are not shipped. */
        val SHIPPED_SOURCE_SETS = listOf("src/main", "src/debug", "src/release", "src/benchmark")

        /** The BuildConfig field and the names the key travels under. */
        val KEY_NAMES = Regex("""ACOUSTID_API_KEY|\bapiKey\b|\bacoustIdApiKey\b""")

        val BUILD_CONFIG_FIELD = Regex("""BuildConfig\.([A-Z_]+)""")
    }
}
