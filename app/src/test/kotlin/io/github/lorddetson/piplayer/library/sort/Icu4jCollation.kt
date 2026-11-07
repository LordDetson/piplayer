package io.github.lorddetson.piplayer.library.sort

import com.ibm.icu.text.AlphabeticIndex
import com.ibm.icu.text.Collator
import com.ibm.icu.text.RuleBasedCollator
import com.ibm.icu.text.UnicodeSet
import com.ibm.icu.util.ULocale
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import java.util.Locale

/**
 * The collation of [IcuCollation] on ICU4J, the library the platform's ICU is taken from, so that the JVM tests sort and
 * find letters as the device does (docs/library.md §11.4).
 */
class Icu4jCollation(locales: List<Locale>) : Collation {

    private val collator: RuleBasedCollator = (Collator.getInstance(locales.first()) as RuleBasedCollator).apply {
        numericCollation = true
        isAlternateHandlingShifted = true
        freeze()
    }

    val index: AlphabeticIndex.ImmutableIndex<Any> = AlphabeticIndex<Any>(collator.cloneAsThawed() as RuleBasedCollator)
        .addLabels(*(locales + Locale.ENGLISH).distinct().toTypedArray())
        .setUnderflowLabel(SortBuckets.UNDERFLOW)
        .setInflowLabel(SortBuckets.OTHER)
        .setOverflowLabel(SortBuckets.OTHER)
        .buildImmutableIndex()

    override val name: String = SortKeys.nameOf(locales.first(), collator.version, collator.ucaVersion)

    override val buckets: SortBuckets by lazy {
        SortBuckets.build(
            labels = (0 until index.bucketCount).map { index.getBucket(it).label },
            kinds = (0 until index.bucketCount).map { BucketKind.valueOf(index.getBucket(it).labelType.name) },
            scriptStarts = scriptStarts(),
            bucketOf = index::getBucketIndex,
            key = ::key,
            letterStarts = UnicodeSet().also { collator.getContractionsAndExpansions(it, null, false) }
                .strings().filterTo(HashSet()) { it.startsWith(SortBuckets.PINYIN) },
        )
    }

    override fun key(text: String): SortKey = SortKey(collator.getCollationKey(text).toByteArray())

    override fun newKeys(): (String) -> SortKey {
        val own = collator.cloneAsThawed() as RuleBasedCollator
        return { text -> SortKey(own.getCollationKey(text).toByteArray()) }
    }

    companion object {
        /** The root collator's strings that open each script: U+FDD1 and the script's first character. */
        fun scriptStarts(): List<String> {
            val contractions = UnicodeSet()
            (Collator.getInstance(ULocale.ROOT) as RuleBasedCollator).getContractionsAndExpansions(contractions, null, false)
            return contractions.strings().filter { it.startsWith("﷑") }
        }
    }
}

/** Has every JVM test sort with ICU4J, in English unless a test chooses other languages. */
class Icu4jSortKeys : BeforeAllCallback {
    override fun beforeAll(context: ExtensionContext) {
        SortKeys.factory = { Icu4jCollation(it) }
        SortKeys.use(listOf(Locale.ENGLISH))
    }
}
