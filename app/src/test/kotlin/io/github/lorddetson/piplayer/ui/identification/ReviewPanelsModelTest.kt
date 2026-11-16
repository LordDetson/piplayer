package io.github.lorddetson.piplayer.ui.identification

import io.github.lorddetson.piplayer.fakes.FakeMusicBrainz
import io.github.lorddetson.piplayer.identification.caa.CaaReleaseIndex
import io.github.lorddetson.piplayer.identification.caa.CaaResult
import io.github.lorddetson.piplayer.identification.contract.IdentificationOutcome
import io.github.lorddetson.piplayer.identification.musicbrainz.MusicBrainzJson
import io.github.lorddetson.piplayer.identification.musicbrainz.RecordingDto
import io.github.lorddetson.piplayer.identification.musicbrainz.RecordingSearchResponse
import io.github.lorddetson.piplayer.identification.musicbrainz.RefLookup
import io.github.lorddetson.piplayer.identification.musicbrainz.toCandidate
import io.github.lorddetson.piplayer.identification.pipeline.FileRef
import io.github.lorddetson.piplayer.identification.provider.ProviderId
import io.github.lorddetson.piplayer.identification.provider.StopReason
import io.github.lorddetson.piplayer.identification.release.EditionSource
import io.github.lorddetson.piplayer.identification.release.SuggestedEdition
import io.github.lorddetson.piplayer.identification.score.Band
import io.github.lorddetson.piplayer.identification.text.MusicBrainzRef
import io.github.lorddetson.piplayer.library.details.CoverSource
import io.github.lorddetson.piplayer.library.details.PictureSize
import io.github.lorddetson.piplayer.library.values.DecidedField
import io.github.lorddetson.piplayer.settings.ProtectedField
import io.github.lorddetson.piplayer.ui.identification.ReviewFixtures.found
import io.github.lorddetson.piplayer.ui.identification.ReviewFixtures.libraryFile
import io.github.lorddetson.piplayer.ui.identification.ReviewFixtures.result
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Review screen B's panels on the review's state machine (TI §6, step 20; §11.1 M3 "Review screen B models"). */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewPanelsModelTest {

    private fun TestScope.started(backend: FakeReviewBackend, file: FileRef = libraryFile): ReviewModel {
        val model = ReviewModel(file, backend, CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()))
        model.start()
        advanceUntilIdle()
        return model
    }

    private val ReviewModel.ended get() = state.value as ReviewState.Ended
    private val ReviewModel.card get() = ended.card!!
    private val ReviewModel.coverLine get() = (card.cover as CoverState.Ready).line
    private inline fun <reified P : Panel> ReviewModel.panel(): P = ended.panel as P

    private fun backend(vararg configure: FakeReviewBackend.() -> Unit) = FakeReviewBackend(result(found())).apply {
        editionLists[ReviewFixtures.harborRelease.id] = ReviewFixtures.editionList()
        configure.forEach { it() }
    }

    // Choose edition

    @Test
    fun `the edition picker browses the group when it first opens and not again`() = runTest {
        val backend = backend()
        val model = started(backend)

        model.openEditions()
        assertThat(model.panel<Panel.Editions>().list).isNull()
        advanceUntilIdle()
        val panel = model.panel<Panel.Editions>()
        assertThat(panel.list).isEqualTo(ReviewFixtures.editionList())
        assertThat(panel.chosen).isEqualTo(ReviewFixtures.harborRelease.id)

        assertThat(model.closePanel()).isTrue()
        assertThat(model.ended.panel).isNull()
        model.openEditions()
        advanceUntilIdle()
        assertThat(model.panel<Panel.Editions>().list).isEqualTo(ReviewFixtures.editionList())
        assertThat(backend.calls.filter { it.startsWith("editions") }).containsExactly("editions ${ReviewFixtures.harborRelease.id}")
        assertThat(model.closePanel()).isTrue()
        assertThat(model.closePanel()).isFalse()
    }

    @Test
    fun `a picker closed and opened again while its group is browsed waits for that browse`() = runTest {
        val backend = backend({ editionListGate = CompletableDeferred() })
        val model = started(backend)
        model.openEditions()
        runCurrent()
        model.closePanel()

        model.openEditions()
        runCurrent()
        backend.editionListGate!!.complete(Unit)
        advanceUntilIdle()

        assertThat(model.panel<Panel.Editions>().list).isEqualTo(ReviewFixtures.editionList())
        assertThat(backend.calls.filter { it.startsWith("editions") }).containsExactly("editions ${ReviewFixtures.harborRelease.id}")
    }

    @Test
    fun `a browse that failed is sent again at the next opening`() = runTest {
        val backend = backend({ editionListFailures += ReviewFixtures.harborRelease.id })
        val model = started(backend)
        model.openEditions()
        advanceUntilIdle()
        model.closePanel()
        backend.editionListFailures.clear()

        model.openEditions()
        advanceUntilIdle()

        assertThat(model.panel<Panel.Editions>().list).isEqualTo(ReviewFixtures.editionList())
        assertThat(backend.calls.filter { it.startsWith("editions") }).hasSize(2)
    }

    @Test
    fun `a group that could not be browsed says so`() = runTest {
        val model = started(backend({ editionListFailures += ReviewFixtures.harborRelease.id }))

        model.openEditions()
        advanceUntilIdle()

        assertThat(model.panel<Panel.Editions>().failed).isTrue()
        assertThat(model.panel<Panel.Editions>().list).isNull()
    }

    @Test
    fun `an edition without the song is marked and cannot be chosen again`() = runTest {
        val backend = backend({ openedEditions[ReviewFixtures.promoRelease.id] = null })
        val model = started(backend)
        model.openEditions()
        advanceUntilIdle()

        model.chooseEdition(2)
        advanceUntilIdle()
        assertThat(model.panel<Panel.Editions>().withoutSong).containsExactly(ReviewFixtures.promoRelease.id)
        assertThat(model.card.edition).isEqualTo(ReviewFixtures.harborEdition)
        model.chooseEdition(2)
        advanceUntilIdle()

        assertThat(backend.calls.filter { it.startsWith("open") }).containsExactly("open ${ReviewFixtures.promoRelease.id}")
        // Kept for the next opening of the picker.
        model.closePanel()
        model.openEditions()
        assertThat(model.panel<Panel.Editions>().withoutSong).containsExactly(ReviewFixtures.promoRelease.id)
    }

    @Test
    fun `choosing the card's own edition closes the picker and loads nothing`() = runTest {
        val backend = backend()
        val model = started(backend)
        model.openEditions()
        advanceUntilIdle()
        assertThat(model.panel<Panel.Editions>().list).isNotNull()

        model.chooseEdition(0)

        assertThat(model.ended.panel).isNull()
        assertThat(backend.calls.filter { it.startsWith("open") }).isEmpty()
    }

    @Test
    fun `a tracklist that could not be loaded says so and keeps the card`() = runTest {
        val model = started(backend())
        model.openEditions()
        advanceUntilIdle()

        model.chooseEdition(1)
        advanceUntilIdle()

        assertThat(model.panel<Panel.Editions>().openFailed).isTrue()
        assertThat(model.panel<Panel.Editions>().opening).isNull()
        assertThat(model.card.edition).isEqualTo(ReviewFixtures.harborEdition)
    }

    @Test
    fun `a chosen edition with another recording becomes the card, with its diff, its cover and its fetch time`() = runTest {
        val backend = backend({ openedEditions[ReviewFixtures.cdRelease.id] = ReviewFixtures.cdEdition })
        val model = started(backend)
        model.openEditions()
        advanceUntilIdle()
        val loadedAt = Instant.parse("2026-11-16T10:05:00Z")
        backend.clock = loadedAt
        backend.openGate = CompletableDeferred()

        model.chooseEdition(1)
        advanceUntilIdle()
        assertThat(model.panel<Panel.Editions>().opening).isEqualTo(1)
        backend.openGate!!.complete(Unit)
        advanceUntilIdle()

        assertThat(model.ended.panel).isNull()
        val card = model.card
        assertThat(card.edition).isEqualTo(ReviewFixtures.cdEdition)
        assertThat(card.match.recordingId).isEqualTo(ReviewFixtures.CD_RECORDING)
        assertThat(card.found.recordingId).isEqualTo(FakeMusicBrainz.HARBOR_RECORDING)
        assertThat(card.match.score).isEqualTo(card.found.score)
        assertThat(card.lines.single { it.field == ProtectedField.MUSICBRAINZ_IDS }.new)
            .isEqualTo(ReviewDiff.proposed(ReviewFixtures.cdEdition.details)[ProtectedField.MUSICBRAINZ_IDS])
        assertThat(backend.calls).contains("cover ${ReviewFixtures.cdRelease.id}")

        backend.clock = Instant.parse("2026-11-16T10:30:00Z")
        model.apply()
        advanceUntilIdle()
        val request = backend.applied.single().first
        assertThat(request.details.recordingId).isEqualTo(ReviewFixtures.CD_RECORDING)
        assertThat(request.provenance.recordingId).isEqualTo(ReviewFixtures.CD_RECORDING)
        // The fingerprint found the identified recording, not the CD's.
        assertThat(request.provenance.acoustIdTrackId).isNull()
        assertThat(request.provenance.fetchedAt).isEqualTo(loadedAt)
    }

    @Test
    fun `another candidate and back shows the edition chosen last`() = runTest {
        val live = ReviewFixtures.match(ReviewFixtures.LIVE_RECORDING, score = 0.62)
        val backend = FakeReviewBackend(result(found(ReviewFixtures.match(), live))).apply {
            editionLists[ReviewFixtures.harborRelease.id] = ReviewFixtures.editionList()
            openedEditions[ReviewFixtures.cdRelease.id] = ReviewFixtures.cdEdition
            editions[ReviewFixtures.LIVE_RECORDING] = null
        }
        val model = started(backend)
        model.openEditions()
        advanceUntilIdle()
        model.chooseEdition(1)
        advanceUntilIdle()

        model.choose(1)
        advanceUntilIdle()
        model.choose(0)
        advanceUntilIdle()

        assertThat(model.card.edition).isEqualTo(ReviewFixtures.cdEdition)
        assertThat(model.card.match.recordingId).isEqualTo(ReviewFixtures.CD_RECORDING)
    }

    // Search manually

    private val searchResponse: RecordingSearchResponse = MusicBrainzJson.decodeFromString(FakeMusicBrainz.SEARCH.text())

    @Test
    fun `blank text sends nothing and another MusicBrainz link is refused without a request`() = runTest {
        val backend = backend()
        val model = started(backend)
        model.openSearch()

        model.searchText("   ")
        model.search()
        advanceUntilIdle()
        assertThat(model.panel<Panel.Search>().note).isNull()
        model.searchText("https://musicbrainz.org/artist/22222222-2222-4222-8222-222222222222")
        model.search()
        advanceUntilIdle()

        assertThat(model.panel<Panel.Search>().note).isEqualTo(SearchNote.REFUSED)
        assertThat(backend.calls.filter { it.startsWith("search") || it.startsWith("lookup") }).isEmpty()
    }

    @Test
    fun `free text is one manual query whose results are scored, and choosing one makes the card`() = runTest {
        val backend = backend({
            searchAnswer = searchResponse.recordings.map { it.toCandidate() }
            editions["12121212-1212-4212-8212-121212121212"] = ReviewFixtures.harborEdition.copy(
                details = ReviewFixtures.harborDetails.copy(recordingId = "12121212-1212-4212-8212-121212121212"),
            )
        })
        val model = started(backend)
        model.openSearch()
        model.searchText("paper lanterns  quiet harbors")
        model.search()
        advanceUntilIdle()

        assertThat(backend.calls.filter { it.startsWith("search") }).containsExactly("search paper lanterns quiet harbors")
        val results = model.panel<Panel.Search>().results!!
        assertThat(results.map { it.match.recordingId }).containsExactlyInAnyOrder(
            FakeMusicBrainz.HARBOR_RECORDING,
            "12121212-1212-4212-8212-121212121212",
        )
        val index = results.indexOfFirst { it.match.recordingId == "12121212-1212-4212-8212-121212121212" }

        model.chooseResult(index)
        advanceUntilIdle()

        assertThat(backend.calls).contains("edition 12121212-1212-4212-8212-121212121212")
        assertThat(model.ended.panel).isNull()
        assertThat(model.card.manual).isTrue()
        assertThat(model.card.match.recordingId).isEqualTo("12121212-1212-4212-8212-121212121212")
        assertThat(model.card.band).isEqualTo(ManualResults.band(results[index].match.score))
        // The run's candidates stay listed.
        assertThat(model.ended.candidates).hasSize(1)
        model.openSearch()
        assertThat(model.panel<Panel.Search>().results).isEqualTo(results)
    }

    @Test
    fun `a pasted recording ID is looked up and makes one result`() = runTest {
        val recording: RecordingDto = MusicBrainzJson.decodeFromString(FakeMusicBrainz.HARBOR_RECORDING_LOOKUP.text())
        val backend = backend({ lookupAnswer = RefLookup.Recording(recording) })
        val model = started(backend)
        model.openSearch()
        model.searchText("MBID: ${FakeMusicBrainz.HARBOR_RECORDING.uppercase()}.")

        model.search()
        advanceUntilIdle()

        assertThat(backend.calls.filter { it.startsWith("lookup") || it.startsWith("search") })
            .containsExactly("lookup ${MusicBrainzRef.AnyEntity(FakeMusicBrainz.HARBOR_RECORDING)}")
        assertThat(model.panel<Panel.Search>().results!!.map { it.match.recordingId }).containsExactly(FakeMusicBrainz.HARBOR_RECORDING)
    }

    @Test
    fun `a pasted release link lists its tracks and a chosen track takes that release without release choice`() = runTest {
        val backend = backend({ lookupAnswer = RefLookup.Release(ReviewFixtures.cdRelease) })
        val model = started(backend)
        model.openSearch()
        model.searchText("https://musicbrainz.org/release/${ReviewFixtures.cdRelease.id}")
        model.search()
        advanceUntilIdle()
        val results = model.panel<Panel.Search>().results!!
        assertThat(results.map { it.match.candidate.title }).containsExactly("Low Tide", "North Light", "Paper Lanterns", "Salt Road")

        model.chooseResult(2)
        advanceUntilIdle()

        assertThat(backend.calls.filter { it.startsWith("edition") }).isEmpty()
        val card = model.card
        assertThat(card.manual).isTrue()
        assertThat(card.edition!!.release).isEqualTo(ReviewFixtures.cdRelease)
        assertThat(card.edition!!.source).isEqualTo(EditionSource.PICKED)
        assertThat(card.edition!!.details.recordingId).isEqualTo(ReviewFixtures.CD_RECORDING)
        assertThat(card.edition!!.details.at.position).isEqualTo(3)
        assertThat(backend.calls).contains("cover ${ReviewFixtures.cdRelease.id}")
    }

    @Test
    fun `a track of a looked-up release keeps its disc when the recording is on two discs at the same place`() = runTest {
        val first = ReviewFixtures.harborRelease.media.first()
        val twoDiscs = ReviewFixtures.harborRelease.copy(media = listOf(first.copy(position = 1), first.copy(position = 2, title = "Bonus")))
        val model = started(backend({ lookupAnswer = RefLookup.Release(twoDiscs) }))
        model.openSearch()
        model.searchText("https://musicbrainz.org/release/${twoDiscs.id}")
        model.search()
        advanceUntilIdle()
        val results = model.panel<Panel.Search>().results!!
        val second = results.indexOfFirst { it.at == io.github.lorddetson.piplayer.identification.release.TrackPosition(2, 3) }

        model.chooseResult(second)
        advanceUntilIdle()

        val details = model.card.edition!!.details
        assertThat(details.at).isEqualTo(io.github.lorddetson.piplayer.identification.release.TrackPosition(2, 3))
        assertThat(details.discSubtitle).isEqualTo("Bonus")
    }

    @Test
    fun `a result's edition that comes after Try again leaves the new run alone`() = runTest {
        val recording: RecordingDto = MusicBrainzJson.decodeFromString(FakeMusicBrainz.HARBOR_RECORDING_LOOKUP.text())
        val backend = FakeReviewBackend(result(found(ReviewFixtures.match(score = 0.3)))).apply {
            lookupAnswer = RefLookup.Recording(recording)
            editions[FakeMusicBrainz.HARBOR_RECORDING] = ReviewFixtures.harborEdition
            editionGate = CompletableDeferred()
        }
        val model = started(backend)
        model.openSearch()
        model.searchText(FakeMusicBrainz.HARBOR_RECORDING)
        model.search()
        advanceUntilIdle()
        model.chooseResult(0)
        advanceUntilIdle()
        model.closePanel()
        model.retry()
        advanceUntilIdle()

        backend.editionGate!!.complete(Unit)
        advanceUntilIdle()
        backend.editionGate = null
        model.openSearch()
        model.searchText(FakeMusicBrainz.HARBOR_RECORDING)
        model.search()
        advanceUntilIdle()
        model.chooseResult(0)
        advanceUntilIdle()

        // The new run loads the edition itself rather than taking the old run's.
        assertThat(backend.calls.count { it == "edition ${FakeMusicBrainz.HARBOR_RECORDING}" }).isEqualTo(2)
        assertThat(model.card.edition).isEqualTo(ReviewFixtures.harborEdition)
    }

    @Test
    fun `an edition whose tracklist comes after Close is not remembered`() = runTest {
        val live = ReviewFixtures.match(ReviewFixtures.LIVE_RECORDING, score = 0.62)
        val backend = FakeReviewBackend(result(found(ReviewFixtures.match(), live))).apply {
            editionLists[ReviewFixtures.harborRelease.id] = ReviewFixtures.editionList()
            openedEditions[ReviewFixtures.cdRelease.id] = ReviewFixtures.cdEdition
            editions[ReviewFixtures.LIVE_RECORDING] = null
            openGate = CompletableDeferred()
        }
        val model = started(backend)
        model.openEditions()
        advanceUntilIdle()
        model.chooseEdition(1)
        advanceUntilIdle()
        model.closePanel()
        backend.openGate!!.complete(Unit)
        advanceUntilIdle()

        model.choose(1)
        advanceUntilIdle()
        model.choose(0)
        advanceUntilIdle()

        assertThat(model.card.edition).isEqualTo(ReviewFixtures.harborEdition)
    }

    @Test
    fun `an embedded read that failed is read again, and a picker reopened while loading loads nothing twice`() = runTest {
        val picture = EmbeddedImage(ByteArray(4), PictureSize(600, 600))
        val backend = backend({
            embeddedFails = true
            indexAnswer = CaaResult.Found(index)
        })
        val model = started(backend)
        model.openCovers()
        advanceUntilIdle()
        assertThat(model.panel<Panel.Covers>().embedded).isEmpty()
        assertThat(model.panel<Panel.Covers>().embeddedFailed).isTrue()

        backend.embeddedFails = false
        backend.embedded = listOf(picture)
        model.closePanel()
        model.openCovers()
        model.closePanel()
        model.openCovers()
        advanceUntilIdle()

        assertThat(model.panel<Panel.Covers>().embedded).containsExactly(picture)
        assertThat(model.panel<Panel.Covers>().embeddedFailed).isFalse()
        assertThat(model.panel<Panel.Covers>().images).isEqualTo(index.images)
        assertThat(backend.calls.count { it.startsWith("embedded") }).isEqualTo(2)
        assertThat(backend.calls.count { it.startsWith("index") }).isEqualTo(1)
    }

    @Test
    fun `a release index that a new run cut short is loaded again in it`() = runTest {
        val backend = backend({
            indexAnswer = CaaResult.Found(index)
            indexGate = CompletableDeferred()
            embedded = emptyList()
        })
        val model = started(backend)
        model.openCovers()
        advanceUntilIdle()
        model.closePanel()
        model.retry()
        advanceUntilIdle()

        backend.indexGate = null
        model.openCovers()
        advanceUntilIdle()

        assertThat(model.panel<Panel.Covers>().images).isEqualTo(index.images)
        assertThat(backend.calls.count { it.startsWith("index") }).isEqualTo(2)
    }

    @Test
    fun `a thumbnail asked for again while it loads is fetched once`() = runTest {
        val thumb = ReviewFixtures.indexCover(PictureSize(250, 250))
        val backend = backend({ thumbnails["1"] = thumb })
        val model = started(backend)
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        backend.imageGate = CompletableDeferred()

        val first = scope.async { model.thumbnail(index.images[0]) }
        val second = scope.async { model.thumbnail(index.images[0]) }
        advanceUntilIdle()
        backend.imageGate!!.complete(Unit)
        advanceUntilIdle()

        assertThat(first.await()).isSameAs(thumb)
        assertThat(second.await()).isSameAs(thumb)
        assertThat(backend.calls.filter { it.startsWith("image") }).containsExactly("image 1 250")
    }

    @Test
    fun `an unknown ID, an empty search and a failed one say so`() = runTest {
        val backend = backend()
        val model = started(backend)
        model.openSearch()
        model.searchText(FakeMusicBrainz.HARBOR_RECORDING)
        model.search()
        advanceUntilIdle()
        assertThat(model.panel<Panel.Search>().note).isEqualTo(SearchNote.NOT_FOUND)

        model.searchText("nothing at all")
        model.search()
        advanceUntilIdle()
        assertThat(model.panel<Panel.Search>().note).isEqualTo(SearchNote.NOTHING_FOUND)
        assertThat(model.panel<Panel.Search>().results).isEmpty()

        backend.searchFails = true
        model.search()
        advanceUntilIdle()
        assertThat(model.panel<Panel.Search>().note).isEqualTo(SearchNote.FAILED)
        assertThat(model.panel<Panel.Search>().searching).isFalse()
    }

    @Test
    fun `search is offered after no match, and its result makes a card there`() = runTest {
        val recording: RecordingDto = MusicBrainzJson.decodeFromString(FakeMusicBrainz.HARBOR_RECORDING_LOOKUP.text())
        val backend = FakeReviewBackend(result(found(ReviewFixtures.match(score = 0.3)))).apply {
            lookupAnswer = RefLookup.Recording(recording)
            editions[FakeMusicBrainz.HARBOR_RECORDING] = ReviewFixtures.harborEdition
        }
        val model = started(backend)
        assertThat(model.ended.card).isNull()

        model.openSearch()
        model.searchText(FakeMusicBrainz.HARBOR_RECORDING)
        model.search()
        advanceUntilIdle()
        model.chooseResult(0)
        advanceUntilIdle()

        assertThat(model.card.edition).isEqualTo(ReviewFixtures.harborEdition)
        assertThat(model.card.band).isIn(Band.MEDIUM, Band.NO_MATCH)
        assertThat(model.ended.canApply).isTrue()
    }

    // Choose cover

    private val index = CaaReleaseIndex(listOf(ReviewFixtures.indexImage("1"), ReviewFixtures.indexImage("2", listOf("Back"))))

    @Test
    fun `the cover picker lists the release index and the embedded pictures`() = runTest {
        val embedded = EmbeddedImage(ByteArray(4), PictureSize(600, 600))
        val backend = backend({
            indexAnswer = CaaResult.Found(index)
            this.embedded = listOf(embedded)
        })
        val model = started(backend)

        model.openCovers()
        advanceUntilIdle()

        val panel = model.panel<Panel.Covers>()
        assertThat(panel.images).isEqualTo(index.images)
        assertThat(panel.embedded).containsExactly(embedded)
        assertThat(backend.calls).contains("index ${ReviewFixtures.harborDetails.releaseId}", "embedded content://media/external/audio/media/42")
    }

    @Test
    fun `a release without images, and an index that failed, say so`() = runTest {
        val model = started(backend())
        model.openCovers()
        advanceUntilIdle()
        assertThat(model.panel<Panel.Covers>().indexNote).isEqualTo(CoversNote.NO_IMAGES)
        assertThat(model.panel<Panel.Covers>().images).isEmpty()

        val busy = started(backend({ indexAnswer = CaaResult.Busy(Instant.parse("2026-11-16T11:00:00Z")) }))
        busy.openCovers()
        advanceUntilIdle()
        assertThat(busy.panel<Panel.Covers>().indexNote).isEqualTo(CoversNote.FAILED)
    }

    @Test
    fun `a thumbnail is fetched once`() = runTest {
        val thumb = ReviewFixtures.indexCover(PictureSize(250, 250))
        val backend = backend({
            indexAnswer = CaaResult.Found(index)
            thumbnails["1"] = thumb
        })
        val model = started(backend)
        model.openCovers()
        advanceUntilIdle()

        assertThat(model.thumbnail(index.images[0])).isSameAs(thumb)
        assertThat(model.thumbnail(index.images[0])).isSameAs(thumb)
        assertThat(model.thumbnail(index.images[1])).isNull()

        assertThat(backend.calls.filter { it.startsWith("image") }).containsExactly("image 1 250", "image 2 250")
    }

    @Test
    fun `a chosen smaller release image starts unticked, at the embedding size`() = runTest {
        val backend = backend({
            indexAnswer = CaaResult.Found(index)
            images["2"] = ReviewFixtures.indexCover(PictureSize(500, 500))
            details = ReviewFixtures.currentDetails(coverWidth = 600)
        })
        val model = started(backend)
        model.openCovers()
        advanceUntilIdle()

        model.chooseImage(1)
        advanceUntilIdle()

        assertThat(backend.calls).contains("image 2 1200")
        assertThat(model.ended.panel).isNull()
        val line = model.coverLine
        assertThat(line.chosen).isTrue()
        assertThat((line.new as ProposedCover.Chosen).origin).isEqualTo(ProposedCover.Origin.RELEASE_INDEX)
        assertThat(line.new.size).isEqualTo(PictureSize(500, 500))
        assertThat(line.ticked).isFalse()
    }

    @Test
    fun `a chosen image of unknown size starts unticked over a known cover and ticked over a thumbnail or no cover`() = runTest {
        val unknown = EmbeddedImage(ByteArray(16), null)
        for ((details, ticked) in listOf(
            ReviewFixtures.currentDetails(coverWidth = 600) to false,
            ReviewFixtures.currentDetails(coverSource = CoverSource.THUMBNAIL, coverWidth = null) to true,
            ReviewFixtures.currentDetails(coverSource = CoverSource.NONE, coverWidth = null) to true,
        )) {
            val model = started(backend({
                this.details = details
                embedded = listOf(unknown)
            }))
            model.openCovers()
            advanceUntilIdle()

            model.chooseEmbedded(0)
            advanceUntilIdle()

            assertThat(model.coverLine.new.size).isNull()
            assertThat((model.coverLine.new as ProposedCover.Chosen).origin).isEqualTo(ProposedCover.Origin.EMBEDDED)
            assertThat(model.coverLine.ticked).describedAs(details.rows.toString()).isEqualTo(ticked)
        }
    }

    @Test
    fun `an image that could not be fetched says so, and another edition brings the proposed cover back`() = runTest {
        val backend = backend({
            indexAnswer = CaaResult.Found(index)
            images["1"] = ReviewFixtures.indexCover(PictureSize(1200, 1200))
            openedEditions[ReviewFixtures.cdRelease.id] = ReviewFixtures.cdEdition
        })
        val model = started(backend)
        model.openCovers()
        advanceUntilIdle()
        model.chooseImage(1)
        advanceUntilIdle()
        assertThat(model.panel<Panel.Covers>().failed).isTrue()
        model.chooseImage(0)
        advanceUntilIdle()
        assertThat(model.coverLine.chosen).isTrue()

        model.openEditions()
        advanceUntilIdle()
        model.chooseEdition(1)
        advanceUntilIdle()

        assertThat(model.coverLine.chosen).isFalse()
    }

    // Edit fields

    @Test
    fun `the editor starts with what Apply would leave and a changed field becomes a ticked user line`() = runTest {
        val model = started(backend())
        model.toggle(ProtectedField.ALBUM_ARTIST)

        model.openEditor()
        val editor = model.panel<Panel.Editor>()
        assertThat(editor.entries.map { it.field }).containsExactlyElementsOf(FieldEdits.EDITABLE)
        assertThat(editor.entries.single { it.field == ProtectedField.TITLE }.text).isEqualTo("Paper Lanterns")
        // Unticked: the old value stays, and the file has no album artist.
        assertThat(editor.entries.single { it.field == ProtectedField.ALBUM_ARTIST }.text).isEmpty()
        assertThat(editor.entries.single { it.field == ProtectedField.TRACK_NUMBER }).isEqualTo(EditorEntry(ProtectedField.TRACK_NUMBER, "3", "11"))

        model.editEntry(EditorEntry(ProtectedField.TITLE, "Paper Lanterns (single)"))
        model.saveEdits()

        assertThat(model.ended.panel).isNull()
        val title = model.card.lines.single { it.field == ProtectedField.TITLE }
        assertThat(title).isEqualTo(DiffLine(ProtectedField.TITLE, FieldValue.Text("track_07"), FieldValue.Text("Paper Lanterns (single)"), ticked = true, edited = true))
        // The other lines keep their ticks, and fields the user did not change are no edits.
        assertThat(model.card.lines.single { it.field == ProtectedField.ALBUM_ARTIST }.ticked).isFalse()
        assertThat(model.card.lines.filter { it.edited }.map { it.field }).containsExactly(ProtectedField.TITLE)
    }

    @Test
    fun `a user line the user unticked stays unticked when another field is saved`() = runTest {
        val model = started(backend())
        model.openEditor()
        model.editEntry(EditorEntry(ProtectedField.TITLE, "Mine"))
        model.saveEdits()
        model.toggle(ProtectedField.TITLE)

        model.openEditor()
        model.editEntry(EditorEntry(ProtectedField.ALBUM, "Other album"))
        model.saveEdits()

        assertThat(model.card.lines.single { it.field == ProtectedField.TITLE }.ticked).isFalse()
        assertThat(model.card.lines.single { it.field == ProtectedField.ALBUM }.ticked).isTrue()
    }

    @Test
    fun `a number that is not one blocks Save`() = runTest {
        val model = started(backend())
        model.openEditor()

        model.editEntry(EditorEntry(ProtectedField.TRACK_NUMBER, "three", "11"))
        model.saveEdits()

        assertThat(model.panel<Panel.Editor>().invalid).containsExactly(ProtectedField.TRACK_NUMBER)
        assertThat(model.card.lines.none { it.edited }).isTrue()
        model.editEntry(EditorEntry(ProtectedField.TRACK_NUMBER, "4", "11"))
        assertThat(model.panel<Panel.Editor>().invalid).isEmpty()
    }

    @Test
    fun `an edit back to the release's value gives the release's line back, and Close keeps the card`() = runTest {
        val model = started(backend())
        val before = model.card.lines
        model.openEditor()
        model.editEntry(EditorEntry(ProtectedField.TITLE, "Mine"))
        model.closePanel()
        assertThat(model.card.lines).isEqualTo(before)

        model.openEditor()
        model.editEntry(EditorEntry(ProtectedField.TITLE, "Mine"))
        model.saveEdits()
        model.openEditor()
        assertThat(model.panel<Panel.Editor>().entries.single { it.field == ProtectedField.TITLE }.text).isEqualTo("Mine")
        model.editEntry(EditorEntry(ProtectedField.TITLE, "Paper Lanterns"))
        model.saveEdits()

        assertThat(model.card.lines).isEqualTo(before)
    }

    @Test
    fun `an edit taken back to the release's value is gone on another candidate too`() = runTest {
        val live = ReviewFixtures.match(ReviewFixtures.LIVE_RECORDING, score = 0.62)
        val liveDetails = ReviewFixtures.harborDetails.copy(recordingId = ReviewFixtures.LIVE_RECORDING, title = "Paper Lanterns (Live)")
        val backend = FakeReviewBackend(result(found(ReviewFixtures.match(), live))).apply {
            editions[ReviewFixtures.LIVE_RECORDING] = SuggestedEdition(ReviewFixtures.harborRelease, liveDetails, EditionSource.PICKED)
        }
        val model = started(backend)
        model.openEditor()
        model.editEntry(EditorEntry(ProtectedField.TITLE, "Mine"))
        model.saveEdits()
        model.openEditor()
        model.editEntry(EditorEntry(ProtectedField.TITLE, "Paper Lanterns"))
        model.saveEdits()

        model.choose(1)
        advanceUntilIdle()

        assertThat(model.card.lines.none { it.edited }).isTrue()
        assertThat(model.card.lines.single { it.field == ProtectedField.TITLE }.new).isEqualTo(FieldValue.Text("Paper Lanterns (Live)"))
    }

    @Test
    fun `the release's value typed into an unticked line's field ticks the release's line`() = runTest {
        val model = started(backend())
        model.toggle(ProtectedField.TITLE)
        model.openEditor()
        assertThat(model.panel<Panel.Editor>().entries.single { it.field == ProtectedField.TITLE }.text).isEqualTo("track_07")

        model.editEntry(EditorEntry(ProtectedField.TITLE, "Paper Lanterns"))
        model.saveEdits()

        val title = model.card.lines.single { it.field == ProtectedField.TITLE }
        assertThat(title.edited).isFalse()
        assertThat(title.new).isEqualTo(FieldValue.Text("Paper Lanterns"))
        assertThat(title.ticked).isTrue()
    }

    @Test
    fun `the user's values stay through another edition`() = runTest {
        val model = started(backend({ openedEditions[ReviewFixtures.cdRelease.id] = ReviewFixtures.cdEdition }))
        model.openEditor()
        model.editEntry(EditorEntry(ProtectedField.TITLE, "Mine"))
        model.saveEdits()

        model.openEditions()
        advanceUntilIdle()
        model.chooseEdition(1)
        advanceUntilIdle()

        assertThat(model.card.lines.single { it.field == ProtectedField.TITLE }.new).isEqualTo(FieldValue.Text("Mine"))
    }

    // Apply with the user's values

    @Test
    fun `Apply stores the identification and then the edit, and Undo undoes both`() = runTest {
        val backend = backend()
        val model = started(backend)
        model.openEditor()
        model.editEntry(EditorEntry(ProtectedField.TITLE, "Mine"))
        model.saveEdits()

        model.apply()
        advanceUntilIdle()

        assertThat(backend.applied).isEmpty()
        val (request, cover, edit) = backend.appliedWithEdit.single()
        assertThat(request!!.fields).doesNotContain(ProtectedField.TITLE).contains(ProtectedField.ALBUM, ProtectedField.COVER)
        assertThat(cover).isNotNull()
        assertThat(edit.values).containsExactlyEntriesOf(mapOf(DecidedField.TITLE to "Mine"))
        assertThat(edit.cover).isNull()
        assertThat(edit.target).isEqualTo(ReviewFixtures.libraryTarget)
        assertThat(model.ended.last).isEqualTo(LastAction(ActionKind.APPLY, 101, editId = 104))
        assertThat(model.ended.applied).isTrue()

        model.undo()
        advanceUntilIdle()

        assertThat(backend.calls.filter { it.startsWith("undo") }).containsExactly("undo 104", "undo 101")
        assertThat(model.ended.last).isNull()
        assertThat(model.ended.applied).isFalse()
        assertThat(model.ended.note).isEqualTo(ReviewNote.UNDONE)
    }

    @Test
    fun `a ticked chosen cover goes into the edit, not the identification`() = runTest {
        val chosen = EmbeddedImage(ByteArray(16), PictureSize(1200, 1200))
        val backend = backend({ embedded = listOf(chosen) })
        val model = started(backend)
        model.openCovers()
        advanceUntilIdle()
        model.chooseEmbedded(0)
        advanceUntilIdle()

        model.apply()
        advanceUntilIdle()

        val (request, cover, edit) = backend.appliedWithEdit.single()
        assertThat(request!!.fields).doesNotContain(ProtectedField.COVER)
        assertThat(request.cover).isNull()
        assertThat(cover).isNull()
        assertThat(edit.values).isEmpty()
        assertThat(edit.cover!!.bytes).isSameAs(chosen.bytes)
        assertThat(edit.releaseId).isEqualTo(ReviewFixtures.harborDetails.releaseId)
    }

    @Test
    fun `with only the user's values ticked Apply stores the edit alone, once an identity holds the key`() = runTest {
        val backend = backend({ identityHeld = false })
        val model = started(backend)
        model.openEditor()
        model.editEntry(EditorEntry(ProtectedField.TITLE, "Mine"))
        model.saveEdits()
        for (line in model.card.lines.filter { it.ticked && !it.edited }) model.toggle(line.field)
        model.toggle(ProtectedField.COVER)

        assertThat(model.ended.canEdit).isFalse()
        assertThat(model.ended.canApply).isFalse()
        model.apply()
        advanceUntilIdle()
        assertThat(backend.appliedWithEdit).isEmpty()

        backend.identityHeld = true
        val held = started(backend)
        held.openEditor()
        held.editEntry(EditorEntry(ProtectedField.TITLE, "Mine"))
        held.saveEdits()
        for (line in held.card.lines.filter { it.ticked && !it.edited }) held.toggle(line.field)
        held.toggle(ProtectedField.COVER)
        assertThat(held.ended.canEdit).isTrue()
        held.apply()
        advanceUntilIdle()

        val (request, cover, edit) = backend.appliedWithEdit.single()
        assertThat(request).isNull()
        assertThat(cover).isNull()
        assertThat(edit.values).containsExactlyEntriesOf(mapOf(DecidedField.TITLE to "Mine"))
        assertThat(held.ended.last).isEqualTo(LastAction(ActionKind.APPLY, 104))
        held.undo()
        advanceUntilIdle()
        assertThat(backend.calls.filter { it.startsWith("undo") }).containsExactly("undo 104")
    }

    @Test
    fun `an edit that was not stored after the identification says so and Undo undoes the identification`() = runTest {
        val backend = backend({ editAnswer = null })
        val model = started(backend)
        model.openEditor()
        model.editEntry(EditorEntry(ProtectedField.TITLE, "Mine"))
        model.saveEdits()

        model.apply()
        advanceUntilIdle()

        assertThat(model.ended.note).isEqualTo(ReviewNote.EDIT_FAILED)
        assertThat(model.ended.last).isEqualTo(LastAction(ActionKind.APPLY, 101))
        assertThat(model.ended.applied).isTrue()
    }

    @Test
    fun `after Apply makes the identity an edit can be stored`() = runTest {
        val backend = backend({ identityHeld = false })
        val model = started(backend)
        assertThat(model.ended.canEdit).isFalse()
        backend.identityHeld = true

        model.apply()
        advanceUntilIdle()

        assertThat(model.ended.canEdit).isTrue()
    }

    // Panels and the snippet

    @Test
    fun `no panel opens while an action runs or an edition loads, and the snippet plays the run's file`() = runTest {
        val backend = backend()
        val model = started(backend)
        assertThat(model.ended.snippet).isEqualTo(SnippetSource("content://media/external/audio/media/42", 213_200))
        backend.writeGate = CompletableDeferred()
        model.apply()
        advanceUntilIdle()

        model.openEditions()
        model.openSearch()
        model.openCovers()
        model.openEditor()

        assertThat(model.ended.panel).isNull()
        backend.writeGate!!.complete(Unit)
    }

    @Test
    fun `a run that a blocked call stopped after reading the file offers the snippet and no panel`() = runTest {
        listOf(
            IdentificationOutcome.NoNetwork,
            IdentificationOutcome.CaptivePortal,
            IdentificationOutcome.Disabled,
            IdentificationOutcome.ProviderStopped(ProviderId.MUSICBRAINZ, StopReason.USER_AGENT_REJECTED),
        ).forEach { outcome ->
            val model = started(FakeReviewBackend(result(outcome)))

            val blocked = model.state.value as ReviewState.Blocked
            assertThat(blocked.snippet).describedAs("$outcome").isEqualTo(SnippetSource("content://media/external/audio/media/42", 213_200))
            model.openSearch()
            assertThat(model.state.value).describedAs("$outcome").isEqualTo(blocked)
        }
        val unread = started(FakeReviewBackend(result(IdentificationOutcome.NoNetwork, read = false)))
        assertThat((unread.state.value as ReviewState.Blocked).snippet).isNull()
    }

    @Test
    fun `a run that did not read the file offers no search and no snippet`() = runTest {
        val model = started(FakeReviewBackend(result(IdentificationOutcome.Unreadable, read = false)))

        model.openSearch()

        assertThat(model.ended.panel).isNull()
        assertThat(model.ended.snippet).isNull()
    }
}
