package io.github.lorddetson.piplayer.library.identity

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The check of steps 4 and 5 in their write transaction (docs/library.md §8.2): a takeover found on the identities and
 * user data read before the step ran is written only while it still holds on those of the transaction.
 */
class TakeoverTest {

    private val now = 1_800_000_000_000L
    private val place = Place("external_primary", "Music/New/", "song.mp3")
    private val file = KeyedFile(1, "bb", place, size = 5_000_000, durationMs = 200_000, settled = true, recordingMbid = null, format = "mpeg")
    private val missing = Identity(7, "aa", 200_000, null, null, null, createdAt = now - 1_000_000, missingSince = now - 1_000)
    private val replaced = Identity(100, "bb", 200_000, null, place, 5_000_000, createdAt = now, missingSince = null)
    private val takeover = Takeover(missingId = 7, file = file, replacedId = 100)

    private fun check(identities: List<Identity> = listOf(missing, replaced), holding: Set<Long> = setOf(7L)) =
        Takeover.stillValid(listOf(takeover), identities, holding)

    @Test
    fun aTakeoverThatStillHoldsIsKept() {
        assertThat(check()).containsExactly(takeover)
    }

    @Test
    fun theReplacedIdentityGainedUserDataMeanwhile() {
        // A favorite, a playlist entry, a play or an own cover written while step 4 fingerprinted.
        assertThat(check(holding = setOf(7L, 100L))).isEmpty()
    }

    @Test
    fun theMissingIdentityIsNoLongerMissing() {
        assertThat(check(identities = listOf(missing.copy(missingSince = null), replaced))).isEmpty()
    }

    @Test
    fun eitherIdentityIsGoneOrTheReplacedOneNoLongerHoldsTheFilesKey() {
        assertThat(check(identities = listOf(replaced))).isEmpty()
        assertThat(check(identities = listOf(missing))).isEmpty()
        assertThat(check(identities = listOf(missing, replaced.copy(contentKey = "cc")))).isEmpty()
    }
}
