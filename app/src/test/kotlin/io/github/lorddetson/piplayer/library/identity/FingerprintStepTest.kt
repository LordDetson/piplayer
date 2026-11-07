package io.github.lorddetson.piplayer.library.identity

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Step 4 of re-attachment: the fingerprint cache (TI §3.1, docs/library.md §8.3). */
class FingerprintStepTest {

    private val now = 1_800_000_000_000L
    private val old = Place("external_primary", "Music/Old/", "song.wav")
    private val new = Place("external_primary", "Music/New/", "song.wav")

    /** Fingerprints by key; a fingerprint is "similar" to another when both have the same first item. */
    private inner class Fake(val cache: MutableMap<String, IntArray>, val sound: Map<Long, IntArray>) : FingerprintSource {
        val computed = mutableListOf<Long>()

        override suspend fun cached(contentKey: String) = cache[contentKey]?.let { CachedPrint(it, "wav") }

        override suspend fun compute(file: KeyedFile): IntArray? {
            computed += file.fileId
            return sound[file.fileId]?.also { cache[file.contentKey] = it }
        }
    }

    private val same: (IntArray, IntArray) -> Boolean = { a, b -> a[0] == b[0] }

    private val missing = Identity(7, "old", 200_000, null, old, 1, createdAt = now - 10_000, missingSince = now - 5_000)

    private fun file(id: Long, key: String, durationMs: Long? = 200_300, format: String? = "wav") =
        KeyedFile(id, key, new, size = 10, durationMs, settled = true, recordingMbid = null, format = format)

    private fun newIdentity(id: Long, f: KeyedFile, createdAt: Long = now - 1_000) =
        Identity(id, f.contentKey, f.durationMs, null, f.place, f.size, createdAt, missingSince = null)

    @Test
    fun aSimilarNewFileIsTakenOverAndItsNewIdentityGoes() = runTest {
        val f = file(1, "new")
        val fake = Fake(mutableMapOf("old" to intArrayOf(5)), mapOf(1L to intArrayOf(5)))

        val result = FingerprintStep(fake, same).takeovers(listOf(f), listOf(missing, newIdentity(9, f)), setOf(7L))

        assertThat(result).containsExactly(Takeover(missingId = 7, file = f, replacedId = 9))
        assertThat(fake.cache).containsKey("new")
    }

    @Test
    fun aCachedCandidateIsNotComputedAgain() = runTest {
        val f = file(1, "new")
        val fake = Fake(mutableMapOf("old" to intArrayOf(5), "new" to intArrayOf(5)), emptyMap())

        val result = FingerprintStep(fake, same).takeovers(listOf(f), listOf(missing, newIdentity(9, f)), setOf(7L))

        assertThat(result).hasSize(1)
        assertThat(fake.computed).isEmpty()
    }

    @Test
    fun onlyIdentitiesThatHoldUserDataLook() = runTest {
        val f = file(1, "new")
        val fake = Fake(mutableMapOf("old" to intArrayOf(5)), mapOf(1L to intArrayOf(5)))

        val result = FingerprintStep(fake, same).takeovers(listOf(f), listOf(missing, newIdentity(9, f)), emptySet())

        assertThat(result).isEmpty()
        assertThat(fake.computed).isEmpty()
    }

    @Test
    fun withoutACachedFingerprintNothingIsComputed() = runTest {
        val f = file(1, "new")
        val fake = Fake(mutableMapOf(), mapOf(1L to intArrayOf(5)))

        assertThat(FingerprintStep(fake, same).takeovers(listOf(f), listOf(missing, newIdentity(9, f)), setOf(7L))).isEmpty()
        assertThat(fake.computed).isEmpty()
    }

    @Test
    fun candidatesMustShareTheFormatAndTheLength() = runTest {
        val otherFormat = file(1, "a", format = "mpeg")
        val tooLong = file(2, "b", durationMs = 202_000)
        val fake = Fake(mutableMapOf("old" to intArrayOf(5)), mapOf(1L to intArrayOf(5), 2L to intArrayOf(5)))
        val identities = listOf(missing, newIdentity(8, otherFormat), newIdentity(9, tooLong))

        assertThat(FingerprintStep(fake, same).takeovers(listOf(otherFormat, tooLong), identities, setOf(7L))).isEmpty()
        assertThat(fake.computed).isEmpty()
    }

    @Test
    fun aFileOfAnIdentityOlderThanTheLossOrHoldingDataIsNotTaken() = runTest {
        val older = file(1, "a")
        val holding = file(2, "b")
        val fake = Fake(mutableMapOf("old" to intArrayOf(5)), mapOf(1L to intArrayOf(5), 2L to intArrayOf(5)))
        val identities = listOf(missing, newIdentity(8, older, createdAt = now - 6_000), newIdentity(9, holding))

        assertThat(FingerprintStep(fake, same).takeovers(listOf(older, holding), identities, setOf(7L, 9L))).isEmpty()
    }

    @Test
    fun twoSimilarFilesTakeNothing() = runTest {
        val a = file(1, "a")
        val b = file(2, "b")
        val fake = Fake(mutableMapOf("old" to intArrayOf(5)), mapOf(1L to intArrayOf(5), 2L to intArrayOf(5)))
        val identities = listOf(missing, newIdentity(8, a), newIdentity(9, b))

        assertThat(FingerprintStep(fake, same).takeovers(listOf(a, b), identities, setOf(7L))).isEmpty()
    }

    @Test
    fun byteIdenticalCopiesAreOneCandidate() = runTest {
        val a = file(1, "new")
        val copy = file(2, "new").copy(place = old.copy(relativePath = "Music/Copy/"))
        val fake = Fake(mutableMapOf("old" to intArrayOf(5)), mapOf(1L to intArrayOf(5), 2L to intArrayOf(5)))

        val result = FingerprintStep(fake, same).takeovers(listOf(a, copy), listOf(missing, newIdentity(9, a)), setOf(7L))

        assertThat(result).containsExactly(Takeover(missingId = 7, file = a, replacedId = 9))
        assertThat(fake.computed).containsExactly(1L)
    }

    @Test
    fun aFingerprintThatCouldNotBeMadeIsTriedAgain() = runTest {
        val f = file(1, "new")
        val sound = mutableMapOf<Long, IntArray>()
        val fake = Fake(mutableMapOf("old" to intArrayOf(5)), sound)
        val step = FingerprintStep(fake, same)
        val identities = listOf(missing, newIdentity(9, f))

        assertThat(step.takeovers(listOf(f), identities, setOf(7L))).isEmpty()
        sound[1L] = intArrayOf(5)

        assertThat(step.takeovers(listOf(f), identities, setOf(7L))).hasSize(1)
    }

    @Test
    fun anEarlierMatchStillCountsAgainstALaterOne() = runTest {
        val first = file(1, "a")
        val second = file(2, "b")
        val fake = Fake(mutableMapOf("old" to intArrayOf(5)), mapOf(1L to intArrayOf(5), 2L to intArrayOf(5)))
        val step = FingerprintStep(fake, same)

        assertThat(step.takeovers(listOf(first), listOf(missing, newIdentity(8, first)), setOf(7L))).hasSize(1)
        assertThat(
            step.takeovers(listOf(first, second), listOf(missing, newIdentity(8, first), newIdentity(9, second)), setOf(7L)),
        ).isEmpty()
    }

    @Test
    fun aDifferentSoundIsNotTaken() = runTest {
        val f = file(1, "new")
        val fake = Fake(mutableMapOf("old" to intArrayOf(5)), mapOf(1L to intArrayOf(6)))

        assertThat(FingerprintStep(fake, same).takeovers(listOf(f), listOf(missing, newIdentity(9, f)), setOf(7L))).isEmpty()
    }

    @Test
    fun eachPairIsTriedOncePerProcess() = runTest {
        val f = file(1, "new")
        val fake = Fake(mutableMapOf("old" to intArrayOf(5)), mapOf(1L to intArrayOf(6)))
        val step = FingerprintStep(fake, same)
        val identities = listOf(missing, newIdentity(9, f))

        step.takeovers(listOf(f), identities, setOf(7L))
        fake.cache.remove("new")
        step.takeovers(listOf(f), identities, setOf(7L))

        assertThat(fake.computed).containsExactly(1L)
    }

    @Test
    fun theRealComparisonAcceptsUpToTenPercentOfDifferingBits() {
        val a = IntArray(40) { it * 7919 }
        val close = IntArray(40) { i -> if (i % 10 == 0) a[i] xor 0x7FFFFFFF.toInt() else a[i] }
        val far = IntArray(40) { i -> a[i] xor 0x0F0F0F0F }

        assertThat(FingerprintStep.similarFingerprints(a, a)).isTrue()
        assertThat(FingerprintStep.similarFingerprints(a, close)).isTrue()
        assertThat(FingerprintStep.similarFingerprints(a, far)).isFalse()
        assertThat(FingerprintStep.similarFingerprints(a, IntArray(2))).isFalse()
    }
}
