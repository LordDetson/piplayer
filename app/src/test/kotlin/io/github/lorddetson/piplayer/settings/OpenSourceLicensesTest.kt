package io.github.lorddetson.piplayer.settings

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The open-source licence list the build writes into the debug variant's assets (docs/library.md §14.2, §14.4), read
 * as the app reads it and compared with an independent listing of the variant's runtime modules.
 */
class OpenSourceLicensesTest {

    /** The generated asset folder; Gradle passes it to the debug unit tests. */
    private val assets = File(requireNotNull(System.getProperty("piplayer.licenses.dir")) { "Run through Gradle" })

    /** "group:name:version" of every module of the debug runtime classpath, from the listing task. */
    private val components = File(requireNotNull(System.getProperty("piplayer.licenses.components")) { "Run through Gradle" })
        .readLines().filter { it.isNotBlank() }

    private val list by lazy { OpenSourceLicenses.parse(assets.resolve(OpenSourceLicenses.LIST).readText()) }

    @Test
    fun everyRuntimeModuleIsListedAndNothingElse() {
        val listed = list.libraries.map { "${it.group}:${it.name}:${it.version}" }

        assertThat(components).describedAs("the listing task found the runtime modules").hasSizeGreaterThan(50)
        assertThat(listed).containsExactlyInAnyOrderElementsOf(components)
    }

    @Test
    fun everyModuleHasAKnownLicenceWhoseTextIsInTheAssets() {
        val kinds = list.kinds.associateBy { it.id }

        list.libraries.forEach { library ->
            assertThat(library.licenses).describedAs("${library.group}:${library.name}").isNotEmpty()
            library.licenses.forEach { id ->
                val kind = kinds[id]
                assertThat(kind).describedAs("kind $id of ${library.name}").isNotNull()
                assertThat(assets.resolve(kind!!.text)).describedAs("text of $id").isFile().isNotEmpty()
            }
        }
    }

    @Test
    fun modulesWhoseLicenceAsksForTheirCopyrightNoticeHaveOne() {
        val needNotice = list.libraries.filter { library -> library.licenses.any { it in NOTICE_KINDS } }

        assertThat(needNotice).describedAs("at least checker-qual is MIT").isNotEmpty()
        needNotice.forEach { assertThat(it.notice).describedAs("notice of ${it.name}").isNotBlank() }
    }

    @Test
    fun everyKindNamedIsUsedAndTitled() {
        val used = list.libraries.flatMap { it.licenses }.toSet()

        assertThat(list.kinds.map { it.id }).containsExactlyInAnyOrderElementsOf(used)
        list.kinds.forEach { assertThat(it.title).isNotBlank() }
    }

    @Test
    fun theNativeComponentsAreThoseOfTheNoticesFile() {
        // "## TagLib 2.3.2" and the like; the test recordings are not shipped.
        val headings = File("../THIRD_PARTY_NOTICES.md").readLines()
            .filter { it.startsWith("## ") && !it.startsWith("## Test recordings") }
            .map { it.removePrefix("## ") }

        assertThat(list.native).hasSameSizeAs(headings)
        list.native.forEach { native ->
            val firstWord = native.name.substringBefore(' ')
            assertThat(headings).describedAs("a heading for ${native.name}").anyMatch { it.startsWith(firstWord) }
            assertThat(native.license).isNotBlank()
            assertThat(native.texts).isNotEmpty().allMatch { it.startsWith("licenses/") }
        }
    }

    @Test
    fun theAppReadsTheListFormat() {
        val parsed = OpenSourceLicenses.parse(
            """
            {"libraries": [{"group": "g", "name": "n", "version": "1", "licenses": ["MIT"], "notice": "Copyright 1 A"},
                           {"group": "h", "name": "m", "version": "2", "licenses": ["Apache-2.0"], "extra": true}],
             "native": [{"name": "TagLib", "version": "2.3.2", "license": "GNU LGPL 2.1", "texts": ["licenses/a", "licenses/b"]}],
             "kinds": [{"id": "MIT", "title": "MIT License", "text": "licenses/kinds/MIT.txt"}]}
            """.trimIndent(),
        )

        assertThat(parsed.libraries.map { it.notice }).containsExactly("Copyright 1 A", null)
        assertThat(parsed.native.single().texts).containsExactly("licenses/a", "licenses/b")
        assertThat(parsed.kinds.single().title).isEqualTo("MIT License")
    }

    private companion object {
        /** The kinds whose text keeps "the above copyright notice" (docs/library.md §14.2). */
        val NOTICE_KINDS = setOf("MIT", "BSD-3-Clause")
    }
}
