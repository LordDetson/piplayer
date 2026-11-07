package io.github.lorddetson.piplayer.library.sort

import android.icu.text.AlphabeticIndex
import android.icu.text.Collator
import android.icu.text.RuleBasedCollator
import android.icu.text.UnicodeSet
import android.icu.util.ULocale
import java.util.Locale

/**
 * The platform's ICU collator for the first of [locales] (docs/library.md §11.1): tertiary, digits by value, spaces and
 * punctuation ignored, frozen, so that every thread may use it. Its letters are those of all [locales] and of English.
 */
class IcuCollation(private val locales: List<Locale>) : Collation {

    private val collator: RuleBasedCollator = (Collator.getInstance(locales.first()) as RuleBasedCollator).apply {
        numericCollation = true
        isAlternateHandlingShifted = true
        freeze()
    }

    override val name: String = SortKeys.nameOf(locales.first(), collator.version, collator.ucaVersion)

    /** ICU's own index of the letters, which [buckets] agree with. Built when a list first needs its letters. */
    internal val index: AlphabeticIndex.ImmutableIndex<Any> by lazy {
        AlphabeticIndex<Any>(collator.cloneAsThawed() as RuleBasedCollator)
            .addLabels(*(locales + Locale.ENGLISH).distinct().toTypedArray())
            .setUnderflowLabel(SortBuckets.UNDERFLOW)
            .setInflowLabel(SortBuckets.OTHER)
            .setOverflowLabel(SortBuckets.OTHER)
            .buildImmutableIndex()
    }

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

    /**
     * A frozen collator makes one key at a time behind a lock, so each resolver chunk takes a thawed copy of its own,
     * which shares the tailoring.
     */
    override fun newKeys(): (String) -> SortKey {
        val own = collator.cloneAsThawed() as RuleBasedCollator
        return { text -> SortKey(own.getCollationKey(text).toByteArray()) }
    }

    private companion object {
        /** The root collator's strings that open each script: U+FDD1 and a character of the script. */
        fun scriptStarts(): List<String> {
            val contractions = UnicodeSet()
            (Collator.getInstance(ULocale.ROOT) as RuleBasedCollator).getContractionsAndExpansions(contractions, null, false)
            return contractions.strings().filter { it.startsWith("﷑") }
        }
    }
}
