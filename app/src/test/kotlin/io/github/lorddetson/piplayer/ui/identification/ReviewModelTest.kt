package io.github.lorddetson.piplayer.ui.identification

import io.github.lorddetson.piplayer.identification.caa.CaaResult
import io.github.lorddetson.piplayer.identification.contract.IdentificationOutcome
import io.github.lorddetson.piplayer.identification.pipeline.PipelineProgress
import io.github.lorddetson.piplayer.identification.pipeline.PipelineStage
import io.github.lorddetson.piplayer.identification.provider.ProviderId
import io.github.lorddetson.piplayer.identification.provider.StopReason
import io.github.lorddetson.piplayer.identification.release.EditionSource
import io.github.lorddetson.piplayer.identification.release.SuggestedEdition
import io.github.lorddetson.piplayer.identification.score.Band
import io.github.lorddetson.piplayer.identification.score.Source
import io.github.lorddetson.piplayer.library.details.CoverSource
import io.github.lorddetson.piplayer.settings.ProtectedField
import io.github.lorddetson.piplayer.ui.identification.ReviewFixtures.found
import io.github.lorddetson.piplayer.ui.identification.ReviewFixtures.libraryFile
import io.github.lorddetson.piplayer.ui.identification.ReviewFixtures.match
import io.github.lorddetson.piplayer.ui.identification.ReviewFixtures.result
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The review's state machine (TI §6, review screen A; §11.1 M3 "Review card state machine") on a fake backend. */
class ReviewModelTest {

    private val liveDetails = ReviewFixtures.harborDetails.copy(
        recordingId = ReviewFixtures.LIVE_RECORDING,
        releaseId = "66666666-6666-4666-8666-666666666666",
        title = "Paper Lanterns (live)",
    )
    private val liveEdition = SuggestedEdition(ReviewFixtures.harborRelease, liveDetails, EditionSource.RANKING)
    private val live = match(ReviewFixtures.LIVE_RECORDING, score = 0.62, title = "Paper Lanterns (live)", foundBy = listOf(Source.TEXT_SEARCH))

    private fun TestScope.started(backend: FakeReviewBackend, file: io.github.lorddetson.piplayer.identification.pipeline.FileRef = libraryFile): ReviewModel {
        val model = ReviewModel(file, backend, modelScope())
        model.start()
        advanceUntilIdle()
        return model
    }

    /**
     * The model's scope on the test's scheduler, outside the test's own job: advanceUntilIdle runs its work, which
     * backgroundScope's it would not, and a run left waiting does not keep the test from ending.
     */
    private fun TestScope.modelScope() = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())

    private val ReviewModel.ended get() = state.value as ReviewState.Ended
    private val ReviewModel.card get() = ended.card!!

    @Test
    fun `while the run goes the state shows its last progress`() = runTest {
        val backend = FakeReviewBackend(result(found()))
        backend.progress = listOf(PipelineProgress.Started(PipelineStage.LOCAL_READ), PipelineProgress.ReleasePages("rec", 2))
        backend.runGate = CompletableDeferred()

        val model = started(backend)

        assertThat(model.state.value).isEqualTo(ReviewState.Running(PipelineProgress.ReleasePages("rec", 2)))
        backend.runGate!!.complete(Unit)
        advanceUntilIdle()
        assertThat(model.state.value).isInstanceOf(ReviewState.Ended::class.java)
    }

    @Test
    fun `a blocked run shows its outcome and reads nothing more`() = runTest {
        for (outcome in listOf(
            IdentificationOutcome.Disabled,
            IdentificationOutcome.NoNetwork,
            IdentificationOutcome.CaptivePortal,
            IdentificationOutcome.ProviderStopped(ProviderId.MUSICBRAINZ, StopReason.USER_AGENT_REJECTED),
        )) {
            val backend = FakeReviewBackend(result(outcome, read = false))

            val model = started(backend)

            assertThat(model.state.value).isEqualTo(ReviewState.Blocked(outcome))
            assertThat(backend.calls).containsExactly("identify $libraryFile")
        }
    }

    @Test
    fun `a run without a candidate ends without a card, with the retry time only when it reports one`() = runTest {
        val at = Instant.parse("2026-11-16T11:35:00Z")
        for (outcome in listOf(
            IdentificationOutcome.NoKey,
            IdentificationOutcome.Unavailable(ProviderId.ACOUSTID),
            found(match(score = 0.3), edition = null),
        )) {
            val model = started(FakeReviewBackend(result(outcome, retryAt = at)))
            assertThat(model.ended.outcome).isEqualTo(outcome)
            assertThat(model.ended.card).isNull()
            assertThat(model.ended.candidates).isEmpty()
            assertThat(model.ended.retryAt).isEqualTo(at)
        }
        val unreadable = started(FakeReviewBackend(result(IdentificationOutcome.Unreadable, read = false)))
        assertThat(unreadable.ended.retryAt).isNull()
        assertThat(unreadable.ended.keepOrSkip).isFalse()
    }

    @Test
    fun `a found run shows the winner's card with the diff of the values shown now`() = runTest {
        val backend = FakeReviewBackend(result(found(match(), live)))

        val model = started(backend)

        val card = model.card
        assertThat(card.index).isEqualTo(0)
        assertThat(card.band).isEqualTo(Band.HIGH)
        assertThat(card.edition).isEqualTo(ReviewFixtures.harborEdition)
        assertThat(model.ended.candidates.map { it.recordingId }).containsExactly(match().recordingId, ReviewFixtures.LIVE_RECORDING)
        assertThat(card.lines).isEqualTo(
            ReviewDiff.lines(ReviewDiff.current(backend.details), ReviewDiff.proposed(ReviewFixtures.harborDetails), emptySet()),
        )
        assertThat((card.cover as CoverState.Ready).line.ticked).isTrue()
        assertThat(backend.calls).containsExactly("identify $libraryFile", "current $libraryFile", "cover ${ReviewFixtures.harborDetails.releaseId}")
        assertThat(model.ended.canApply).isTrue()
    }

    @Test
    fun `the winner's card has the run's band, and candidates below 0_50 are left out`() = runTest {
        val high = started(FakeReviewBackend(result(found(match(score = 0.98), match("low", score = 0.4)))))
        assertThat(high.card.band).isEqualTo(Band.HIGH)
        assertThat(high.ended.candidates).hasSize(1)

        val medium = started(FakeReviewBackend(result(found(match(score = 0.93), match("close", score = 0.88)))))
        assertThat(medium.card.band).isEqualTo(Band.MEDIUM)
        assertThat(medium.ended.candidates).hasSize(2)
    }

    @Test
    fun `Apply waits for the cover fetch to end`() = runTest {
        val backend = FakeReviewBackend(result(found()))
        backend.coverGate = CompletableDeferred()

        val model = started(backend)

        assertThat(model.card.cover).isEqualTo(CoverState.Loading)
        assertThat(model.ended.canApply).isFalse()
        model.apply()
        advanceUntilIdle()
        assertThat(backend.calls).doesNotContain("apply")
        backend.coverGate!!.complete(Unit)
        advanceUntilIdle()
        assertThat(model.ended.canApply).isTrue()
    }

    @Test
    fun `no image or a failed fetch leaves the cover out, and Apply goes`() = runTest {
        val none = started(FakeReviewBackend(result(found()), coverResult = CaaResult.NotFound))
        assertThat(none.card.cover).isEqualTo(CoverState.None)
        assertThat(none.ended.canApply).isTrue()

        val busy = started(FakeReviewBackend(result(found()), coverResult = CaaResult.Busy(Instant.EPOCH)))
        assertThat(busy.card.cover).isEqualTo(CoverState.Failed)
        assertThat(busy.ended.canApply).isTrue()
    }

    @Test
    fun `Apply stores the ticked fields with the cover and the provenance, then offers Undo`() = runTest {
        val backend = FakeReviewBackend(result(found()))
        val model = started(backend)

        model.apply()
        advanceUntilIdle()

        val (request, cover) = backend.applied.single()
        assertThat(request.target).isEqualTo(ReviewFixtures.libraryTarget)
        assertThat(request.details).isEqualTo(ReviewFixtures.harborDetails)
        assertThat(request.fields).isEqualTo(ReviewDiff.fields(model.card.lines, (model.card.cover as CoverState.Ready).line))
        assertThat(request.fields).contains(ProtectedField.TITLE, ProtectedField.COVER)
        assertThat(request.cover).isEqualTo(cover!!.source)
        assertThat(cover.bytes).isEqualTo(ReviewFixtures.cover().bytes)
        assertThat(request.provenance.recordingId).isEqualTo(ReviewFixtures.harborDetails.recordingId)
        assertThat(request.provenance.confidence).isEqualTo(0.96)
        assertThat(request.provenance.acoustIdTrackId).isEqualTo("acoustid-track")
        assertThat(request.provenance.fetchedAt).isEqualTo(FakeReviewBackend.NOW)
        assertThat(model.ended.last).isEqualTo(LastAction(ActionKind.APPLY, 101))
        assertThat(model.ended.applied).isTrue()
        assertThat(model.ended.canApply).isFalse()
    }

    @Test
    fun `unticked lines and an unticked cover stay out of Apply`() = runTest {
        val backend = FakeReviewBackend(result(found()))
        val model = started(backend)

        model.toggle(ProtectedField.TITLE)
        model.toggle(ProtectedField.COVER)
        assertThat(model.card.lines.single { it.field == ProtectedField.TITLE }.ticked).isFalse()
        assertThat((model.card.cover as CoverState.Ready).line.ticked).isFalse()
        model.apply()
        advanceUntilIdle()

        val (request, cover) = backend.applied.single()
        assertThat(request.fields).doesNotContain(ProtectedField.TITLE, ProtectedField.COVER)
        assertThat(request.cover).isNull()
        assertThat(cover).isNull()
    }

    @Test
    fun `with every line unticked Apply is not offered`() = runTest {
        val model = started(FakeReviewBackend(result(found()), coverResult = CaaResult.NotFound))

        model.card.lines.filter { it.ticked }.forEach { model.toggle(it.field) }

        assertThat(model.ended.canApply).isFalse()
    }

    @Test
    fun `Undo after Apply undoes it and offers Apply again`() = runTest {
        val backend = FakeReviewBackend(result(found()))
        val model = started(backend)
        model.apply()
        advanceUntilIdle()

        model.undo()
        advanceUntilIdle()

        assertThat(backend.calls).endsWith("apply", "undo 101")
        assertThat(model.ended.last).isNull()
        assertThat(model.ended.applied).isFalse()
        assertThat(model.ended.note).isEqualTo(ReviewNote.UNDONE)
        assertThat(model.ended.canApply).isTrue()
    }

    @Test
    fun `Keep as-is and Skip offer Undo, and a later action replaces the Undo offered`() = runTest {
        val backend = FakeReviewBackend(result(found()))
        val model = started(backend)
        assertThat(model.ended.keepOrSkip).isTrue()

        model.skip()
        advanceUntilIdle()
        assertThat(model.ended.last).isEqualTo(LastAction(ActionKind.SKIP, 103))
        model.keepAsIs()
        advanceUntilIdle()
        assertThat(model.ended.last).isEqualTo(LastAction(ActionKind.KEEP_AS_IS, 102))
        model.undo()
        advanceUntilIdle()

        assertThat(backend.calls).endsWith("skip ${ReviewFixtures.FILE_ID}", "keep ${ReviewFixtures.FILE_ID}", "undo 102")
        assertThat(model.ended.last).isNull()
    }

    @Test
    fun `Keep as-is and Skip are offered after no match, never for an opened file`() = runTest {
        val noMatch = started(FakeReviewBackend(result(found(match(score = 0.2), edition = null))))
        assertThat(noMatch.ended.keepOrSkip).isTrue()
        noMatch.keepAsIs()
        advanceUntilIdle()
        assertThat(noMatch.ended.last?.kind).isEqualTo(ActionKind.KEEP_AS_IS)

        val opened = FakeReviewBackend(result(found(), target = ReviewFixtures.openedTarget))
        val model = started(opened, ReviewFixtures.openedFile)
        assertThat(model.ended.keepOrSkip).isFalse()
        model.keepAsIs()
        model.skip()
        advanceUntilIdle()
        assertThat(opened.calls.filter { it.startsWith("keep") || it.startsWith("skip") }).isEmpty()
    }

    @Test
    fun `a failed action says so and changes nothing`() = runTest {
        val backend = FakeReviewBackend(result(found()))
        backend.applyAnswer = null
        backend.skipAnswer = null
        val model = started(backend)

        model.apply()
        advanceUntilIdle()
        assertThat(model.ended.note).isEqualTo(ReviewNote.APPLY_FAILED)
        assertThat(model.ended.applied).isFalse()
        assertThat(model.ended.last).isNull()
        assertThat(model.ended.canApply).isTrue()

        model.skip()
        advanceUntilIdle()
        assertThat(model.ended.note).isEqualTo(ReviewNote.SKIP_FAILED)

        backend.keepAnswer = 7
        backend.undoAnswer = false
        model.keepAsIs()
        advanceUntilIdle()
        model.undo()
        advanceUntilIdle()
        assertThat(model.ended.note).isEqualTo(ReviewNote.UNDO_FAILED)
    }

    @Test
    fun `a winner without an edition has its card without Apply`() = runTest {
        val model = started(FakeReviewBackend(result(found(edition = null))))

        assertThat(model.card.edition).isNull()
        assertThat(model.card.lines).isEmpty()
        assertThat(model.ended.canApply).isFalse()
    }

    @Test
    fun `choosing another candidate loads its edition and makes it the card`() = runTest {
        val backend = FakeReviewBackend(result(found(match(), live)))
        backend.editions[ReviewFixtures.LIVE_RECORDING] = liveEdition
        val model = started(backend)

        model.choose(1)
        advanceUntilIdle()

        assertThat(model.card.index).isEqualTo(1)
        assertThat(model.card.match).isEqualTo(live)
        assertThat(model.card.band).isEqualTo(Band.MEDIUM)
        assertThat(model.card.edition).isEqualTo(liveEdition)
        assertThat(model.card.lines.single { it.field == ProtectedField.TITLE }.new).isEqualTo(FieldValue.Text("Paper Lanterns (live)"))
        assertThat(backend.calls).contains("edition ${ReviewFixtures.LIVE_RECORDING}", "cover ${liveDetails.releaseId}")
        model.apply()
        advanceUntilIdle()
        assertThat(backend.applied.single().first.provenance.recordingId).isEqualTo(ReviewFixtures.LIVE_RECORDING)
        // Found by text only: no AcoustID track ID in its provenance.
        assertThat(backend.applied.single().first.provenance.acoustIdTrackId).isNull()
    }

    @Test
    fun `a candidate's edition that fails to load keeps the card shown before`() = runTest {
        val backend = FakeReviewBackend(result(found(match(), live)))
        backend.editionFailures += ReviewFixtures.LIVE_RECORDING
        val model = started(backend)

        model.choose(1)
        advanceUntilIdle()

        assertThat(model.card.index).isEqualTo(0)
        assertThat(model.ended.note).isEqualTo(ReviewNote.EDITION_FAILED)
        assertThat(model.ended.loading).isNull()
    }

    @Test
    fun `going back to the winner fetches its cover no second time`() = runTest {
        val backend = FakeReviewBackend(result(found(match(), live)))
        backend.editions[ReviewFixtures.LIVE_RECORDING] = liveEdition
        val model = started(backend)

        model.choose(1)
        advanceUntilIdle()
        model.choose(0)
        advanceUntilIdle()

        assertThat(model.card.index).isEqualTo(0)
        assertThat(backend.calls.count { it == "cover ${ReviewFixtures.harborDetails.releaseId}" }).isEqualTo(1)
        assertThat(backend.calls.count { it.startsWith("edition") }).isEqualTo(1)
    }

    @Test
    fun `under an own cover the cover line starts unticked`() = runTest {
        val backend = FakeReviewBackend(result(found()), details = ReviewFixtures.currentDetails(coverSource = CoverSource.ALBUM_OWN, coverWidth = 300))

        val model = started(backend)

        val line = (model.card.cover as CoverState.Ready).line
        assertThat(line.ownStaysOnTop).isTrue()
        assertThat(line.ticked).isFalse()
    }

    @Test
    fun `protected fields have no line and the card names them`() = runTest {
        val backend = FakeReviewBackend(result(found()), protected = setOf(ProtectedField.TITLE, ProtectedField.COVER))

        val model = started(backend)

        assertThat(model.card.lines.map { it.field }).doesNotContain(ProtectedField.TITLE)
        assertThat(model.card.cover).isEqualTo(CoverState.None)
        assertThat(model.card.protectedFields).containsExactlyInAnyOrder(ProtectedField.TITLE, ProtectedField.COVER)
        assertThat(backend.calls.filter { it.startsWith("cover") }).isEmpty()
    }

    @Test
    fun `Enable after online lookups off stores the answer and runs again`() = runTest {
        val backend = FakeReviewBackend(result(IdentificationOutcome.Disabled, read = false), result(found()))
        val model = started(backend)

        model.enable()
        advanceUntilIdle()

        assertThat(backend.calls.take(3)).containsExactly("identify $libraryFile", "enable", "identify $libraryFile")
        assertThat(model.ended.card).isNotNull()
    }

    @Test
    fun `Cancel while Enable is stored runs nothing more`() = runTest {
        val backend = FakeReviewBackend(result(IdentificationOutcome.Disabled, read = false), result(found()))
        backend.enableGate = CompletableDeferred()
        val model = started(backend)

        model.enable()
        advanceUntilIdle()
        model.cancel()
        backend.enableGate!!.complete(Unit)
        advanceUntilIdle()

        assertThat(model.state.value).isEqualTo(ReviewState.Closed)
        assertThat(backend.calls.count { it.startsWith("identify") }).isEqualTo(1)
    }

    @Test
    fun `Try again waits while a decision is written, so its Undo is not lost`() = runTest {
        val backend = FakeReviewBackend(result(IdentificationOutcome.Unavailable(ProviderId.MUSICBRAINZ)), result(found()))
        backend.writeGate = CompletableDeferred()
        val model = started(backend)

        model.keepAsIs()
        advanceUntilIdle()
        model.retry()
        advanceUntilIdle()
        assertThat(backend.calls.count { it.startsWith("identify") }).isEqualTo(1)
        backend.writeGate!!.complete(Unit)
        advanceUntilIdle()

        assertThat(model.ended.last).isEqualTo(LastAction(ActionKind.KEEP_AS_IS, 102))
    }

    @Test
    fun `a blocked run keeps the retry time it reports`() = runTest {
        val at = Instant.parse("2026-11-16T11:35:00Z")
        val stopped = IdentificationOutcome.ProviderStopped(ProviderId.ACOUSTID, StopReason.INVALID_API_KEY)

        val model = started(FakeReviewBackend(result(stopped, retryAt = at)))

        assertThat(model.state.value).isEqualTo(ReviewState.Blocked(stopped, at))
    }

    @Test
    fun `Not now stores the answer and closes`() = runTest {
        val backend = FakeReviewBackend(result(IdentificationOutcome.Disabled, read = false))
        val model = started(backend)

        model.notNow()
        advanceUntilIdle()

        assertThat(backend.calls).endsWith("not now")
        assertThat(model.state.value).isEqualTo(ReviewState.Closed)
    }

    @Test
    fun `Try again runs again`() = runTest {
        val backend = FakeReviewBackend(result(IdentificationOutcome.NoNetwork, read = false), result(found()))
        val model = started(backend)

        model.retry()
        advanceUntilIdle()

        assertThat(backend.calls.count { it.startsWith("identify") }).isEqualTo(2)
        assertThat(model.ended.card).isNotNull()
    }

    @Test
    fun `Cancel ends the run and closes`() = runTest {
        val backend = FakeReviewBackend(result(found()))
        backend.runGate = CompletableDeferred()
        val model = started(backend)

        model.cancel()
        advanceUntilIdle()
        backend.runGate!!.complete(Unit)
        advanceUntilIdle()

        assertThat(model.state.value).isEqualTo(ReviewState.Closed)
        assertThat(backend.calls.filter { it.startsWith("current") || it.startsWith("cover") }).isEmpty()
    }

    @Test
    fun `a run that throws fails the review`() = runTest {
        val backend = object : ReviewBackend by FakeReviewBackend(result(found())) {
            override suspend fun identify(
                file: io.github.lorddetson.piplayer.identification.pipeline.FileRef,
                onProgress: (PipelineProgress) -> Unit,
            ): io.github.lorddetson.piplayer.identification.pipeline.PipelineResult = error("broken")
        }
        val model = ReviewModel(libraryFile, backend, modelScope())
        model.start()
        advanceUntilIdle()

        assertThat(model.state.value).isEqualTo(ReviewState.Failed)
    }
}
