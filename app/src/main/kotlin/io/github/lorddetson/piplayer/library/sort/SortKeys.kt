package io.github.lorddetson.piplayer.library.sort

import java.util.Locale

/** A collator and its letters (docs/library.md §11.1); safe to use from any thread. */
interface Collation {
    /** `<language tag>/<collator version>/<UCA version>/<rules version>`, stored next to the keys. */
    val name: String

    val buckets: SortBuckets

    fun key(text: String): SortKey

    /** Keys for one thread at a time, which need not wait for other threads; a resolver chunk takes one. */
    fun newKeys(): (String) -> SortKey = ::key
}

/** The collation lists sort by, which follows the app's languages (docs/library.md §11.1). */
object SortKeys {

    /** A change of how keys are made raises this; the next sync makes them all again. */
    const val RULES_VERSION = 1

    /** Makes the collation of a list of languages; the platform's ICU, or ICU4J in JVM tests. */
    @Volatile
    var factory: (List<Locale>) -> Collation = { IcuCollation(it) }

    @Volatile
    private var current: Pair<List<Locale>, Collation>? = null

    /** The collation of the languages last given to [use], or of the default ones. */
    fun current(): Collation = current?.second ?: synchronized(this) {
        current?.second ?: factory(listOf(Locale.getDefault())).also { current = listOf(Locale.getDefault()) to it }
    }

    /** Follows [locales], the app's languages; true when the collation changed. */
    fun use(locales: List<Locale>): Boolean = synchronized(this) {
        val languages = locales.ifEmpty { listOf(Locale.getDefault()) }
        val old = current
        if (old != null && old.first == languages) return false
        val collation = factory(languages)
        current = languages to collation
        old?.second?.name != collation.name
    }

    fun of(text: String): SortKey = current().key(text)

    /**
     * The name of a collation of [locale]: its language, script and region with the collation keyword only, so that a
     * regional preference such as the first day of the week (an extension of the locale since Android 14) does not make
     * every key again.
     */
    fun nameOf(locale: Locale, collatorVersion: Any, ucaVersion: Any): String {
        val base = Locale.Builder().setLocale(locale).clearExtensions().build().toLanguageTag()
        val tag = locale.getUnicodeLocaleType("co")?.let { "$base-u-co-$it" } ?: base
        return "$tag/$collatorVersion/$ucaVersion/$RULES_VERSION"
    }

    /** Keys of one collation that remembers those of texts it has seen: a resolver chunk's, for one thread. */
    class Cache(collation: Collation = current()) {
        private val make = collation.newKeys()
        private val keys = HashMap<String, SortKey>()

        fun of(text: String): SortKey = keys.getOrPut(text) { make(text) }
    }
}
