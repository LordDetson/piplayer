package io.github.lorddetson.piplayer.identification.batch

import io.github.lorddetson.piplayer.identification.contract.ApplyRequest
import io.github.lorddetson.piplayer.identification.contract.IdentificationOutcome
import io.github.lorddetson.piplayer.identification.provider.ProviderId
import io.github.lorddetson.piplayer.identification.score.Band
import io.github.lorddetson.piplayer.settings.ProtectedField
import io.github.lorddetson.piplayer.ui.identification.ReviewFixtures
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The automatic preparation of a group's high band (TI §8): which of phase (b)'s results the app applies on its own and
 * which wait for a card, and what an automatic Apply stores. The results are the review's fixtures of a run.
 */
class BatchAutoApplyTest {

    private val at: Instant = Instant.parse("2026-11-17T09:00:00Z")

    /** A run of the studio album's recording in the high band, with its suggested edition. */
    private fun high() = ReviewFixtures.result(ReviewFixtures.found(ReviewFixtures.match()))

    /** A run that found the recording but stays in the medium band: no 0.90. */
    private fun medium() = ReviewFixtures.result(ReviewFixtures.found(ReviewFixtures.match(score = 0.70)))

    /** A run whose best score is below 0.50. */
    private fun noMatch() = ReviewFixtures.result(ReviewFixtures.found(ReviewFixtures.match(score = 0.20)))

    @Test
    fun `the high band is prepared on its own and the rest waits for a card`() {
        assertThat(ReviewFixtures.found(ReviewFixtures.match()).band).isEqualTo(Band.HIGH)

        val prepared = BatchAutoApply.divide(listOf(high(), medium(), noMatch()), emptySet(), at)

        assertThat(prepared.automatic).hasSize(1)
        assertThat(prepared.review).hasSize(2)
        assertThat(prepared.automatic.single().provenance.recordingId).isEqualTo(ReviewFixtures.harborDetails.recordingId)
    }

    @Test
    fun `a result whose release was not chosen waits for a card`() {
        val result = ReviewFixtures.result(ReviewFixtures.found(edition = null))

        assertThat(BatchAutoApply.request(result, emptySet(), at)).isNull()
        assertThat(BatchAutoApply.divide(listOf(result), emptySet(), at).review).containsExactly(result)
    }

    @Test
    fun `a run that found nothing, or a file the batch could not read, waits for a card`() {
        val unreadable = ReviewFixtures.result(IdentificationOutcome.Unreadable, read = false)
        val unavailable = ReviewFixtures.result(IdentificationOutcome.Unavailable(ProviderId.MUSICBRAINZ))

        assertThat(BatchAutoApply.request(noMatch(), emptySet(), at)).isNull()
        assertThat(BatchAutoApply.request(unreadable, emptySet(), at)).isNull()
        assertThat(BatchAutoApply.request(unavailable, emptySet(), at)).isNull()
        assertThat(BatchAutoApply.divide(listOf(noMatch(), unreadable, unavailable), emptySet(), at).automatic).isEmpty()
    }

    @Test
    fun `a file opened from another app waits for a card, since a batch holds library files`() {
        val opened = ReviewFixtures.result(ReviewFixtures.found(), target = ReviewFixtures.openedTarget)

        assertThat(BatchAutoApply.request(opened, emptySet(), at)).isNull()
    }

    @Test
    fun `an automatic Apply takes the release's values and leaves the cover and a compilation flag that is off alone`() {
        val request = BatchAutoApply.request(high(), emptySet(), at)!!

        assertThat(request.target).isEqualTo(ReviewFixtures.libraryTarget)
        assertThat(request.details.releaseId).isEqualTo(ReviewFixtures.harborDetails.releaseId)
        assertThat(request.cover).isNull()
        assertThat(request.fields).doesNotContain(ProtectedField.COVER, ProtectedField.COMPILATION)
        assertThat(request.fields).contains(
            ProtectedField.TITLE,
            ProtectedField.ARTIST,
            ProtectedField.ALBUM,
            ProtectedField.ALBUM_ARTIST,
            ProtectedField.DATE,
            ProtectedField.TRACK_NUMBER,
            ProtectedField.DISC_NUMBER,
            ProtectedField.MUSICBRAINZ_IDS,
        )
    }

    @Test
    fun `a compilation takes its flag, so that a release credited to Various Artists keeps it`() {
        val compilation = ReviewFixtures.harborDetails.copy(compilation = true)
        val edition = ReviewFixtures.harborEdition.copy(details = compilation)
        val result = ReviewFixtures.result(ReviewFixtures.found(edition = edition))

        val request = BatchAutoApply.request(result, emptySet(), at)!!

        assertThat(request.fields).contains(ProtectedField.COMPILATION)
    }

    @Test
    fun `protected fields are left alone`() {
        val request = BatchAutoApply.request(high(), setOf(ProtectedField.TITLE, ProtectedField.ALBUM), at)!!

        assertThat(request.fields).doesNotContain(ProtectedField.TITLE, ProtectedField.ALBUM)
        assertThat(request.fields).contains(ProtectedField.ARTIST)
    }

    @Test
    fun `a release that decides no field but the protected ones is no automatic Apply`() {
        assertThat(BatchAutoApply.request(high(), ProtectedField.entries.toSet(), at)).isNull()
    }

    @Test
    fun `the provenance takes the run's confidence, reason and fetching time`() {
        val request = BatchAutoApply.request(high(), emptySet(), at)!!

        assertThat(request.provenance.fetchedAt).isEqualTo(at)
        assertThat(request.provenance.confidence).isEqualTo(0.96)
        assertThat(request.provenance.reason).isEqualTo(ReviewFixtures.match().reason)
    }

    @Test
    fun `prepare applies every automatic result with the batch's UUID and leaves the rest to the cards`() = runTest {
        val preparation = FakePreparation()

        val prepared = BatchAutoApply.prepare(BATCH, listOf(high(), medium(), high()), preparation)

        assertThat(preparation.applied).containsExactly(BATCH, BATCH)
        assertThat(prepared.review).hasSize(1)
    }

    @Test
    fun `the group's high band is prepared in its order`() = runTest {
        val preparation = FakePreparation()
        val first = ReviewFixtures.match(score = 0.95)
        val second = ReviewFixtures.match(recordingId = ReviewFixtures.LIVE_RECORDING, score = 0.10)
        val run = ReviewFixtures.result(ReviewFixtures.found(first, second))
        val later = ReviewFixtures.result(ReviewFixtures.found(ReviewFixtures.match()))
        assertThat(run.outcome).isInstanceOf(IdentificationOutcome.Found::class.java)

        BatchAutoApply.prepare(BATCH, listOf(run, later), preparation)

        assertThat(preparation.requests.map { it.provenance.confidence }).containsExactly(0.95, 0.96)
    }

    @Test
    fun `a decision that cannot be written leaves its track to its card and fails no group`() = runTest {
        val preparation = FakePreparation(failOn = 1)

        val prepared = BatchAutoApply.prepare(BATCH, listOf(high(), high()), preparation)

        assertThat(preparation.applied).containsExactly(BATCH, BATCH)
        assertThat(prepared.automatic).hasSize(2)
    }

    /** A preparation that records what it is asked to apply; [failOn] is the request it answers with an error. */
    private class FakePreparation(private val failOn: Int? = null) : BatchPreparation {
        val requests = mutableListOf<ApplyRequest>()
        val applied = mutableListOf<String>()

        override suspend fun fields(): Set<ProtectedField> = emptySet()

        override fun now(): Instant = Instant.parse("2026-11-17T09:00:00Z")

        override suspend fun apply(request: ApplyRequest, batchUuid: String): Long? {
            requests += request
            applied += batchUuid
            if (requests.size == failOn) throw IllegalStateException("The user database is closed")
            return requests.size.toLong()
        }
    }

    private companion object {
        const val BATCH = "6f9619ff-8b86-4d01-8d42-6e50c94637ac"
    }
}
