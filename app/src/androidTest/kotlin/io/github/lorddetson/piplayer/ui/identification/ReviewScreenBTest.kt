package io.github.lorddetson.piplayer.ui.identification

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.fakes.FakeMusicBrainz
import io.github.lorddetson.piplayer.identification.caa.CaaReleaseIndex
import io.github.lorddetson.piplayer.identification.caa.CaaResult
import io.github.lorddetson.piplayer.identification.musicbrainz.MusicBrainzJson
import io.github.lorddetson.piplayer.identification.musicbrainz.RecordingDto
import io.github.lorddetson.piplayer.identification.musicbrainz.RefLookup
import io.github.lorddetson.piplayer.identification.text.MusicBrainzRef
import io.github.lorddetson.piplayer.library.details.CoverSource
import io.github.lorddetson.piplayer.library.details.PictureSize
import io.github.lorddetson.piplayer.library.details.TrackDetails
import io.github.lorddetson.piplayer.library.values.DecidedField
import io.github.lorddetson.piplayer.playback.snippet.SnippetEnd
import io.github.lorddetson.piplayer.playback.snippet.SnippetPlayer
import io.github.lorddetson.piplayer.playback.snippet.SnippetState
import io.github.lorddetson.piplayer.settings.ProtectedField
import io.github.lorddetson.piplayer.ui.identification.ReviewFixtures.found
import io.github.lorddetson.piplayer.ui.identification.ReviewFixtures.result
import io.github.lorddetson.piplayer.ui.theme.PiPlayerTheme
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Review screen B (TI §6, step 20; §11.2 M3 "Review screen B") with fake results built from the shared fixtures: the
 * edition picker, manual search with a pasted ID, the cover picker's size rule, Edit fields and the snippet button.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ReviewScreenBTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = TestFiles.targetContext
    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun stop() = scope.cancel()

    private fun show(backend: FakeReviewBackend, snippet: SnippetPlayer? = null): ReviewModel {
        val model = ReviewModel(ReviewFixtures.libraryFile, backend, scope)
        compose.setContent { PiPlayerTheme { ReviewContent(model, onBack = {}, snippetPlayer = snippet) } }
        compose.runOnUiThread { model.start() }
        return model
    }

    private fun waitFor(matcher: androidx.compose.ui.test.SemanticsMatcher) = compose.waitUntilAtLeastOneExists(matcher, timeoutMillis = 10_000)

    private fun waitForText(value: String) = waitFor(hasText(value))

    private fun waitForCard() = waitFor(hasTestTag(ReviewTags.CARD))

    private fun click(label: Int) = compose.onNode(hasText(text(label)) and hasClickAction()).performScrollTo().performClick()

    private fun backend(details: TrackDetails = ReviewFixtures.currentDetails(), configure: FakeReviewBackend.() -> Unit = {}) =
        FakeReviewBackend(result(found()), details = details).apply {
            editionLists[ReviewFixtures.harborRelease.id] = ReviewFixtures.editionList()
            configure()
        }

    @Test
    fun theEditionPickerMarksAnEditionWithoutTheSongAndAChosenEditionChangesTheCard() {
        val backend = backend {
            openedEditions[ReviewFixtures.promoRelease.id] = null
            openedEditions[ReviewFixtures.cdRelease.id] = ReviewFixtures.cdEdition
        }
        show(backend)
        waitForCard()
        waitForText(text(R.string.review_choose_edition))

        click(R.string.review_choose_edition)

        waitFor(hasTestTag(ReviewTags.edition(2)))
        compose.onNode(hasTestTag(ReviewTags.edition(0)) and hasText(text(R.string.review_suggested_edition))).assertExists()
        compose.onNode(hasTestTag(ReviewTags.edition(0)) and hasText("Album · Digital Media · XW", substring = true)).assertExists()
        compose.onNodeWithTag(ReviewTags.edition(2)).performScrollTo().performClick()
        waitFor(hasTestTag(ReviewTags.edition(2)) and hasText(text(R.string.review_without_song)))
        compose.onNodeWithTag(ReviewTags.edition(2)).assertIsNotEnabled()

        compose.onNodeWithTag(ReviewTags.edition(1)).performScrollTo().performClick()

        waitFor(hasText("CD · GB", substring = true) and hasAnyAncestor(hasTestTag(ReviewTags.CARD)))
        compose.onNodeWithTag(ReviewTags.PANEL).assertDoesNotExist()
        assertThat(backend.calls).contains("open ${ReviewFixtures.promoRelease.id}", "open ${ReviewFixtures.cdRelease.id}", "cover ${ReviewFixtures.cdRelease.id}")
    }

    @Test
    fun manualSearchWithAPastedIdShowsTheRecordingAndChoosingItMakesTheCard() {
        val recording: RecordingDto = MusicBrainzJson.decodeFromString(FakeMusicBrainz.HARBOR_RECORDING_LOOKUP.text())
        val backend = FakeReviewBackend(result(found(ReviewFixtures.match(score = 0.3)))).apply {
            lookupAnswer = RefLookup.Recording(recording)
            editions[FakeMusicBrainz.HARBOR_RECORDING] = ReviewFixtures.harborEdition
        }
        show(backend)
        waitForText(text(R.string.review_no_match))

        click(R.string.review_search_manually)
        compose.onNodeWithTag(ReviewTags.SEARCH_FIELD).performTextInput("https://musicbrainz.org/recording/${FakeMusicBrainz.HARBOR_RECORDING}")
        click(R.string.review_search)

        waitFor(hasTestTag(ReviewTags.result(0)) and hasText("Paper Lanterns"))
        compose.onNodeWithTag(ReviewTags.result(1)).assertDoesNotExist()
        compose.onNodeWithTag(ReviewTags.result(0)).performScrollTo().performClick()

        waitForCard()
        compose.onNode(hasText("Harbor Lights") and hasAnyAncestor(hasTestTag(ReviewTags.CARD))).assertExists()
        assertThat(backend.calls).contains("lookup ${MusicBrainzRef.Recording(FakeMusicBrainz.HARBOR_RECORDING)}", "edition ${FakeMusicBrainz.HARBOR_RECORDING}")
        assertThat(backend.calls.filter { it.startsWith("search") }).isEmpty()
    }

    private val index = CaaReleaseIndex(listOf(ReviewFixtures.indexImage("1"), ReviewFixtures.indexImage("2", listOf("Back"))))

    private fun openCovers() {
        waitForCard()
        waitFor(hasTestTag(ReviewTags.COVER_LINE))
        click(R.string.review_choose_cover)
    }

    @Test
    fun aChosenSmallerCoverStartsUntickedAndOneOfUnknownSizeToo() {
        val backend = backend(ReviewFixtures.currentDetails(coverWidth = 600)) {
            indexAnswer = CaaResult.Found(index)
            thumbnails["2"] = ReviewFixtures.indexCover(PictureSize(250, 250))
            images["2"] = ReviewFixtures.indexCover(PictureSize(500, 500))
            embedded = listOf(EmbeddedImage(ByteArray(16), null))
        }
        show(backend)
        openCovers()

        waitFor(hasTestTag(ReviewTags.coverImage(1)))
        compose.onNode(hasTestTag(ReviewTags.coverImage(1)) and hasText("Back", substring = true)).assertExists()
        compose.onNodeWithTag(ReviewTags.coverImage(1)).performScrollTo().performClick()

        waitFor(hasTestTag(ReviewTags.COVER_LINE) and hasText(text(R.string.review_cover_chosen)))
        compose.onNode(hasText("→ " + text(R.string.review_pixels, 500, 500), substring = true)).assertExists()
        compose.onNodeWithTag(ReviewTags.COVER_LINE).performScrollTo().assertIsOff()

        click(R.string.review_choose_cover)
        waitFor(hasTestTag(ReviewTags.embeddedImage(0)))
        compose.onNodeWithTag(ReviewTags.embeddedImage(0)).performScrollTo().performClick()
        waitFor(hasTestTag(ReviewTags.COVER_LINE))
        compose.onNodeWithTag(ReviewTags.COVER_LINE).performScrollTo().assertIsOff()
        assertThat(backend.calls).contains("image 2 1200", "embedded content://media/external/audio/media/42")
    }

    @Test
    fun aChosenCoverOfUnknownSizeStartsTickedOverAThumbnail() = unknownSizeStartsTickedOver(CoverSource.THUMBNAIL)

    @Test
    fun aChosenCoverOfUnknownSizeStartsTickedOverNoCover() = unknownSizeStartsTickedOver(CoverSource.NONE)

    private fun unknownSizeStartsTickedOver(source: CoverSource) {
        val backend = backend(ReviewFixtures.currentDetails(coverSource = source, coverWidth = null)) {
            embedded = listOf(EmbeddedImage(ByteArray(16), null))
        }
        show(backend)
        openCovers()

        waitFor(hasTestTag(ReviewTags.embeddedImage(0)))
        compose.onNodeWithTag(ReviewTags.embeddedImage(0)).performScrollTo().performClick()

        waitFor(hasTestTag(ReviewTags.COVER_LINE) and hasText(text(R.string.review_cover_chosen)))
        compose.onNodeWithTag(ReviewTags.COVER_LINE).performScrollTo().assertIsOn()
    }

    @Test
    fun editFieldsPutsATickedUserLineOnTheCardAndApplyStoresItAsAnEditDecision() {
        val backend = backend()
        show(backend)
        waitForCard()
        waitFor(hasTestTag(ReviewTags.COVER_LINE))

        click(R.string.review_edit_fields)
        compose.onNodeWithTag(ReviewTags.editorField(ProtectedField.TITLE)).performScrollTo().performTextReplacement("Paper Lanterns (single)")
        click(R.string.review_save)

        waitFor(hasTestTag(ReviewTags.line(ProtectedField.TITLE)) and hasText(text(R.string.review_your_edit)))
        compose.onNodeWithTag(ReviewTags.line(ProtectedField.TITLE)).performScrollTo().assertIsOn()
        click(R.string.review_apply)

        compose.waitUntil(10_000) { backend.appliedWithEdit.isNotEmpty() }
        val (request, _, edit) = backend.appliedWithEdit.single()
        assertThat(edit.values).containsExactlyEntriesOf(mapOf(DecidedField.TITLE to "Paper Lanterns (single)"))
        assertThat(request!!.fields).doesNotContain(ProtectedField.TITLE)
        waitForText(text(R.string.review_applied))
    }

    @Test
    fun theSnippetButtonPlaysTheFilesSnippetStopsItAndTheReviewStopsItWhenItGoesAway() {
        val snippet = FakeSnippet()
        val model = ReviewModel(ReviewFixtures.libraryFile, backend(), scope)
        var shown by mutableStateOf(true)
        compose.setContent { PiPlayerTheme { if (shown) ReviewContent(model, onBack = {}, snippetPlayer = snippet) } }
        compose.runOnUiThread { model.start() }
        waitForCard()

        click(R.string.review_play_snippet)
        assertThat(snippet.played).containsExactly(Uri.parse("content://media/external/audio/media/42") to 213_200L)
        compose.runOnUiThread { snippet.mutableState.value = SnippetState.Playing(71_066, 86_066) }
        waitForText(text(R.string.review_stop_snippet))
        compose.onNodeWithTag(ReviewTags.SNIPPET_PROGRESS).assertExists()
        click(R.string.review_stop_snippet)
        assertThat(snippet.stops).isEqualTo(1)

        compose.runOnUiThread { snippet.mutableState.value = SnippetState.Ended(SnippetEnd.FAILED) }
        waitForText(text(R.string.review_snippet_failed))
        compose.runOnUiThread { shown = false }
        compose.waitUntil(10_000) { snippet.stops == 2 }
    }

    /** A snippet player that records what the card asks of it; the test sets its state. */
    private class FakeSnippet : SnippetPlayer {
        val mutableState = MutableStateFlow<SnippetState>(SnippetState.Idle)
        override val state: StateFlow<SnippetState> = mutableState
        override val positionMs: Long get() = 75_000
        val played: MutableList<Pair<Uri, Long?>> = CopyOnWriteArrayList()

        @Volatile
        var stops = 0

        override fun play(uri: Uri, durationMs: Long?) {
            played += uri to durationMs
        }

        override fun stop() {
            stops++
        }
    }
}
