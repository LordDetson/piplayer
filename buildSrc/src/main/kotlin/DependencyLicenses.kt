import java.io.File
import java.io.InputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** A Maven module version: "group:name:version". */
data class Coordinates(val group: String, val name: String, val version: String) {
    override fun toString() = "$group:$name:$version"

    companion object {
        fun parse(text: String): Coordinates {
            val parts = text.split(':')
            require(parts.size == 3 && parts.none { it.isBlank() }) { "Not group:name:version: $text" }
            return Coordinates(parts[0], parts[1], parts[2])
        }
    }
}

/** A `<license>` of a POM. */
data class PomLicense(val name: String?, val url: String?)

/** What the licence list needs from a POM: whose it is, its own licences and its parent POM. */
data class PomFacts(val coordinates: Coordinates?, val licenses: List<PomLicense>, val parent: Coordinates?)

/** Reads the parts of a POM the licence list needs (docs/library.md §14.2). */
object Poms {

    fun read(file: File): PomFacts = file.inputStream().use(::read)

    fun read(stream: InputStream): PomFacts {
        val factory = DocumentBuilderFactory.newInstance().apply {
            // A POM is data from a repository: no external entities or DTDs.
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            isExpandEntityReferences = false
            isNamespaceAware = false
        }
        val project = factory.newDocumentBuilder().parse(stream).documentElement
        val parent = project.child("parent")?.let { element ->
            coordinatesOf(element.text("groupId"), element.text("artifactId"), element.text("version"))
        }
        // A POM without its own group or version takes its parent's.
        val coordinates = coordinatesOf(
            project.text("groupId") ?: parent?.group,
            project.text("artifactId"),
            project.text("version") ?: parent?.version,
        )
        val licenses = project.child("licenses")?.children("license").orEmpty().map { element ->
            PomLicense(element.text("name"), element.text("url"))
        }
        return PomFacts(coordinates, licenses, parent)
    }

    private fun coordinatesOf(group: String?, name: String?, version: String?): Coordinates? =
        if (group == null || name == null || version == null) null else Coordinates(group, name, version)

    private fun Element.children(tag: String): List<Element> {
        val nodes = childNodes
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }.filter { it.tagName == tag }
    }

    private fun Element.child(tag: String): Element? = children(tag).firstOrNull()

    private fun Element.text(tag: String): String? = child(tag)?.textContent?.trim()?.takeIf { it.isNotEmpty() }
}

/**
 * A kind of licence the app ships one text of (docs/library.md §14.2): its SPDX [id], the [title] the screen shows, and
 * whether each component under it must bring its own copyright notice, because the text asks for "the above copyright
 * notice" ([needsNotice]).
 */
data class LicenseKind(val id: String, val title: String, val needsNotice: Boolean = false)

/** The licence kinds the build knows, and which POM licences are which. */
object LicenseKinds {

    val APACHE_2 = LicenseKind("Apache-2.0", "Apache License 2.0")
    val MIT = LicenseKind("MIT", "MIT License", needsNotice = true)
    val BSD_3 = LicenseKind("BSD-3-Clause", "BSD 3-Clause License", needsNotice = true)

    val all = listOf(APACHE_2, MIT, BSD_3)

    /**
     * The kind of [license], from its name or URL; null when the build does not know it, or when the name and the URL
     * say different things (a GPL name with an Apache URL is no Apache licence).
     */
    fun of(license: PomLicense): LicenseKind? {
        val name = license.name?.lowercase()?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }
        val url = license.url?.let(::normalizedUrl)?.takeIf { it.isNotEmpty() }
        val byName = name?.let { text -> NAMES.firstOrNull { (_, patterns) -> patterns.any { it.matches(text) } }?.first }
        val byUrl = url?.let { text -> URLS.firstOrNull { (_, urls) -> text in urls }?.first }
        return when {
            name != null && url != null -> byName?.takeIf { byUrl == null || byUrl == it } ?: byUrl?.takeIf { byName == null && !nameNamesAnother(name) }
            else -> byName ?: byUrl
        }
    }

    /** A name the build does not map that still names some licence: a URL alone must not outvote it. */
    private fun nameNamesAnother(name: String) =
        listOf("gpl", "gnu", "eclipse", "epl", "mozilla", "mpl", "cddl", "commons clause", "creative commons", "proprietary")
            .any { it in name }

    /** "https://www.apache.org/licenses/LICENSE-2.0.txt" and its other spellings become "apache.org/licenses/license-2.0". */
    private fun normalizedUrl(url: String): String = url.trim().lowercase()
        .removePrefix("http://").removePrefix("https://").removePrefix("www.")
        .removeSuffix("/").removeSuffix(".txt").removeSuffix(".html").removeSuffix(".php")

    private val APACHE_URLS = setOf(
        "apache.org/licenses/license-2.0",
        "opensource.org/licenses/apache-2.0",
        "spdx.org/licenses/apache-2.0",
    )
    private val APACHE_NAMES = listOf(
        Regex("(the )?apache( software)? license,? (version )?2\\.0"),
        Regex("apache-2\\.0"),
        Regex("apache 2(\\.0)?"),
        Regex("apache license 2\\.0"),
    )
    private val MIT_URLS = setOf("opensource.org/licenses/mit", "spdx.org/licenses/mit", "opensource.org/license/mit")
    private val MIT_NAMES = listOf(Regex("(the )?mit( license)?"))
    private val BSD_3_URLS = setOf("opensource.org/licenses/bsd-3-clause", "spdx.org/licenses/bsd-3-clause")
    private val BSD_3_NAMES = listOf(Regex("bsd-3-clause"), Regex("(the )?(new )?bsd 3-clause( license)?"))

    private val NAMES = listOf(APACHE_2 to APACHE_NAMES, MIT to MIT_NAMES, BSD_3 to BSD_3_NAMES)
    private val URLS = listOf(APACHE_2 to APACHE_URLS, MIT to MIT_URLS, BSD_3 to BSD_3_URLS)
}

/** A runtime dependency with its licence kinds and, where its kinds need one, its copyright notice. */
data class LicensedLibrary(val coordinates: Coordinates, val kinds: List<LicenseKind>, val notice: String?)

/**
 * A native component the app builds from source (THIRD_PARTY_NOTICES.md), with its own licence files in the assets
 * ([texts], asset paths): their copyright lines are the component's own.
 */
data class NativeLibrary(val name: String, val version: String, val license: String, val texts: List<String>) {
    init {
        require(texts.isNotEmpty()) { "$name has no licence text" }
    }

    fun encode() = listOf(name, version, license, texts.joinToString(TEXT_SEPARATOR)).joinToString(SEPARATOR)

    companion object {
        private const val SEPARATOR = "|"
        private const val TEXT_SEPARATOR = ";"

        fun decode(text: String): NativeLibrary {
            val parts = text.split(SEPARATOR)
            require(parts.size == 4) { "Not name|version|license|texts: $text" }
            return NativeLibrary(parts[0], parts[1], parts[2], parts[3].split(TEXT_SEPARATOR))
        }
    }
}

/** Why the list cannot be made: each entry names a dependency and what is missing for it. */
class LicenseProblems(val problems: List<String>) : Exception(
    "The licence list cannot be made (docs/library.md §14.2):\n" + problems.joinToString("\n") { "  - $it" },
)

/** Finds the licence of every runtime dependency from its POM and its parents' (docs/library.md §14.2). */
object DependencyLicenses {

    /** A parent chain longer than this is taken for a loop. */
    private const val MAX_PARENTS = 8

    /**
     * The licences of [components], read from [poms] by their coordinates: a POM without licences takes its nearest
     * parent's. [notices] are the copyright notices by "group:name", for the kinds that need one.
     */
    fun resolve(
        components: List<Coordinates>,
        poms: Map<Coordinates, PomFacts>,
        notices: Map<String, String> = emptyMap(),
    ): List<LicensedLibrary> {
        val problems = mutableListOf<String>()
        val libraries = components.distinct().sortedWith(compareBy({ it.group }, { it.name }, { it.version })).mapNotNull { component ->
            val licenses = licensesOf(component, poms)
            if (licenses == null) {
                problems += "$component: no POM with a <licenses> element, itself or a parent"
                return@mapNotNull null
            }
            val kinds = licenses.map { license -> license to LicenseKinds.of(license) }
            val unknown = kinds.filter { it.second == null }.map { it.first }
            if (unknown.isNotEmpty()) {
                problems += "$component: unknown licence " + unknown.joinToString { "\"${it.name}\" <${it.url}>" } +
                    "; add it to LicenseKinds and its text to app/licenses"
                return@mapNotNull null
            }
            val known = kinds.mapNotNull { it.second }.distinct()
            val notice = notices["${component.group}:${component.name}"]
            if (notice == null && known.any { it.needsNotice }) {
                problems += "$component: ${known.first { it.needsNotice }.id} needs its copyright notice in the notices of app/build.gradle.kts"
                return@mapNotNull null
            }
            LicensedLibrary(component, known, notice)
        }
        if (problems.isNotEmpty()) throw LicenseProblems(problems)
        return libraries
    }

    private fun licensesOf(component: Coordinates, poms: Map<Coordinates, PomFacts>): List<PomLicense>? {
        var pom = poms[component] ?: return null
        repeat(MAX_PARENTS) {
            if (pom.licenses.isNotEmpty()) return pom.licenses
            pom = pom.parent?.let(poms::get) ?: return null
        }
        return null
    }

    /**
     * The list as the app reads it (docs/library.md §14.3): the libraries, the native components, and the kinds with the
     * asset path of each kind's text under [kindsFolder].
     */
    fun json(libraries: List<LicensedLibrary>, natives: List<NativeLibrary>, kindsFolder: String): String = buildString {
        append("{\n  \"libraries\": [")
        libraries.forEachIndexed { index, library ->
            append(if (index == 0) "\n" else ",\n")
            append("    {\"group\": ").append(quote(library.coordinates.group))
            append(", \"name\": ").append(quote(library.coordinates.name))
            append(", \"version\": ").append(quote(library.coordinates.version))
            append(", \"licenses\": [").append(library.kinds.joinToString { quote(it.id) }).append("]")
            library.notice?.let { append(", \"notice\": ").append(quote(it)) }
            append("}")
        }
        append("\n  ],\n  \"native\": [")
        natives.forEachIndexed { index, native ->
            append(if (index == 0) "\n" else ",\n")
            append("    {\"name\": ").append(quote(native.name))
            append(", \"version\": ").append(quote(native.version))
            append(", \"license\": ").append(quote(native.license))
            append(", \"texts\": [").append(native.texts.joinToString { quote(it) }).append("]}")
        }
        append("\n  ],\n  \"kinds\": [")
        val kinds = libraries.flatMap { it.kinds }.distinct().sortedBy { it.id }
        kinds.forEachIndexed { index, kind ->
            append(if (index == 0) "\n" else ",\n")
            append("    {\"id\": ").append(quote(kind.id))
            append(", \"title\": ").append(quote(kind.title))
            append(", \"text\": ").append(quote("$kindsFolder/${kind.id}.txt")).append("}")
        }
        append("\n  ]\n}\n")
    }

    private fun quote(text: String): String = buildString {
        append('"')
        for (char in text) {
            when {
                char == '"' -> append("\\\"")
                char == '\\' -> append("\\\\")
                char == '\n' -> append("\\n")
                char < ' ' -> append("\\u%04x".format(char.code))
                else -> append(char)
            }
        }
        append('"')
    }
}
