package io.github.lorddetson.piplayer.ui.identification

import android.text.format.Formatter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.identification.caa.CaaResult
import io.github.lorddetson.piplayer.identification.caa.CoverEntity
import io.github.lorddetson.piplayer.identification.contract.IdentificationOutcome
import io.github.lorddetson.piplayer.identification.pipeline.PipelineProgress
import io.github.lorddetson.piplayer.identification.provider.ProviderId
import io.github.lorddetson.piplayer.identification.provider.StopReason
import io.github.lorddetson.piplayer.library.details.CoverSource
import io.github.lorddetson.piplayer.settings.ProtectedField
import io.github.lorddetson.piplayer.ui.identification.ReviewFixtures.found
import io.github.lorddetson.piplayer.ui.identification.ReviewFixtures.result
import io.github.lorddetson.piplayer.ui.theme.PiPlayerTheme
import java.text.NumberFormat
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Review screen A (TI §6; §11.2 M3 "Review screen A") with fake pipeline results built from the shared fixtures: the
 * progress, the card, the diff, the actions with Undo, the outcomes and what TalkBack reads.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ReviewScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = TestFiles.targetContext
    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var closed = false

    @After
    fun stop() = scope.cancel()

    private fun show(
        backend: FakeReviewBackend,
        file: io.github.lorddetson.piplayer.identification.pipeline.FileRef = ReviewFixtures.libraryFile,
        onFileWrite: ((LastAction) -> Unit)? = {},
    ) {
        val model = ReviewModel(file, backend, scope)
        compose.setContent {
            PiPlayerTheme { ReviewContent(model, onBack = { closed = true }, onFileWrite = onFileWrite) }
        }
        compose.runOnUiThread { model.start() }
    }

    private fun waitForText(value: String) = compose.waitUntilAtLeastOneExists(hasText(value), timeoutMillis = 10_000)

    private fun waitForCard() = compose.waitUntilAtLeastOneExists(hasTestTag(ReviewTags.CARD), timeoutMillis = 10_000)

    private val percent get() = NumberFormat.getPercentInstance(Locale.getDefault())

    private fun isCheckbox() = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)

    private fun isButton() = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    private fun click(label: Int) = compose.onNode(hasText(text(label)) and isButton()).performScrollTo().performClick()

    @Test
    fun theProgressNamesTheStepAndCancelClosesTheScreen() {
        val backend = FakeReviewBackend(result(found()))
        backend.progress = listOf(PipelineProgress.ReleasePages("rec", 2))
        backend.runGate = CompletableDeferred()
        show(backend)

        waitForText(context.resources.getQuantityString(R.plurals.review_progress_release_pages, 2, 2))
        compose.onNodeWithTag(ReviewTags.PROGRESS).assertExists()
        click(R.string.review_cancel)

        compose.waitUntil(10_000) { closed }
        assertThat(backend.calls.filter { it.startsWith("current") }).isEmpty()
    }

    @Test
    fun theCardShowsTheCandidateItsReleaseItsCoverTheConfidenceAndTheReason() {
        show(FakeReviewBackend(result(found())))
        waitForCard()

        for (value in listOf("Paper Lanterns", ReviewFixtures.harborDetails.trackArtist, "Harbor Lights")) {
            compose.onNode(hasText(value) and hasAnyAncestorTag(ReviewTags.CARD)).assertExists()
        }
        compose.onNode(hasText("2019", substring = true) and hasAnyAncestorTag(ReviewTags.CARD)).assertExists()
        compose.onNode(hasText("Album · Digital Media · XW") and hasAnyAncestorTag(ReviewTags.CARD)).assertExists()
        val cover = ReviewFixtures.cover()
        val size = text(R.string.review_pixels, cover.size!!.width, cover.size!!.height) + " · " +
            Formatter.formatShortFileSize(context, cover.bytes.size.toLong())
        compose.waitUntilAtLeastOneExists(hasText(size), timeoutMillis = 10_000)
        // The confidence is text: the percentage and the band's name.
        compose.onNode(hasText(percent.format(0.96)) and hasAnyAncestorTag(ReviewTags.CARD)).assertExists()
        compose.onNode(hasText(text(R.string.review_band_high)) and hasAnyAncestorTag(ReviewTags.CARD)).assertExists()
        val score = NumberFormat.getNumberInstance(Locale.getDefault()).apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }.format(0.97)
        val reason = listOf(
            text(R.string.review_stage_with_score, text(R.string.review_stage_fingerprint), score),
            text(R.string.review_reason_duration, "+1"),
            text(R.string.review_reason_title, percent.format(0.92)),
            text(R.string.review_reason_also, text(R.string.review_stage_text)),
        ).joinToString(" · ")
        compose.onNodeWithTag(ReviewTags.REASON).assert(hasText(reason))
    }

    @Test
    fun theDiffShowsOldAndNewValuesAndItsCheckboxesChangeWhatApplyStores() {
        val backend = FakeReviewBackend(result(found()))
        show(backend)
        waitForCard()
        compose.waitUntilAtLeastOneExists(hasTestTag(ReviewTags.COVER_LINE), timeoutMillis = 10_000)

        val title = compose.onNodeWithTag(ReviewTags.line(ProtectedField.TITLE)).performScrollTo()
        title.assert(isCheckbox()).assertIsOn()
        title.assert(hasContentDescription(text(R.string.review_line_description, text(R.string.identification_field_title), "track_07", "Paper Lanterns")))
        compose.onNodeWithTag(ReviewTags.line(ProtectedField.ALBUM)).performScrollTo()
            .assert(hasContentDescription(text(R.string.review_no_change), substring = true))
        title.performClick()
        title.assertIsOff()
        click(R.string.review_apply)

        compose.waitUntil(10_000) { backend.applied.isNotEmpty() }
        assertThat(backend.applied.single().first.fields).doesNotContain(ProtectedField.TITLE).contains(ProtectedField.ALBUM, ProtectedField.COVER)
    }

    @Test
    fun fileWritingIsAnExplicitOptInForAnIdentifiedLibraryFile() {
        val backend = FakeReviewBackend(result(found()))
        val writes = mutableListOf<LastAction>()
        show(backend, onFileWrite = { writes += it })
        waitForCard()

        val write = compose.onNodeWithTag(ReviewTags.ALSO_WRITE).performScrollTo()
        write.assert(isCheckbox()).assertIsOff()
        write.performClick()
        write.assertIsOn()
        click(R.string.review_apply)

        waitForText(text(R.string.review_applied))
        assertThat(backend.applied).hasSize(1)
        compose.runOnIdle { assertThat(writes).containsExactly(LastAction(ActionKind.APPLY, 101)) }
    }

    @Test
    fun applyThenUndo() {
        val backend = FakeReviewBackend(result(found()))
        val writes = mutableListOf<LastAction>()
        show(backend, onFileWrite = { writes += it })
        waitForCard()
        compose.waitUntilAtLeastOneExists(hasTestTag(ReviewTags.COVER_LINE), timeoutMillis = 10_000)

        click(R.string.review_apply)
        waitForText(text(R.string.review_applied))
        compose.onNode(hasText(text(R.string.review_apply)) and isButton()).assertIsNotEnabled()
        click(R.string.review_undo)

        waitForText(text(R.string.review_undone))
        assertThat(backend.calls).endsWith("apply", "undo 101")
        compose.runOnIdle { assertThat(writes).isEmpty() }
        compose.onNode(hasText(text(R.string.review_apply)) and isButton()).assertIsEnabled()
    }

    @Test
    fun keepAsIsAndSkipOfferUndo() {
        val backend = FakeReviewBackend(result(found()))
        show(backend)
        waitForCard()

        click(R.string.review_keep_as_is)
        waitForText(text(R.string.review_kept))
        click(R.string.review_skip)
        waitForText(text(R.string.review_skipped))
        click(R.string.review_undo)

        waitForText(text(R.string.review_undone))
        assertThat(backend.calls).endsWith("keep 7", "skip 7", "undo 103")
    }

    @Test
    fun keepAsIsAndSkipComeAlsoWithoutAMatch() {
        val backend = FakeReviewBackend(result(found(ReviewFixtures.match(score = 0.2), edition = null)))
        show(backend)

        waitForText(text(R.string.review_no_match))
        click(R.string.review_skip)

        waitForText(text(R.string.review_skipped))
        assertThat(backend.calls).endsWith("skip 7")
    }

    @Test
    fun anOpenedFileHasNoKeepAsIsOrSkip() {
        show(FakeReviewBackend(result(found(), target = ReviewFixtures.openedTarget)), ReviewFixtures.openedFile)
        waitForCard()

        assertThat(compose.onAllNodes(hasText(text(R.string.review_keep_as_is))).fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodes(hasText(text(R.string.review_skip))).fetchSemanticsNodes()).isEmpty()
        compose.onNodeWithTag(ReviewTags.ALSO_WRITE).assertDoesNotExist()
        compose.onNode(hasText(text(R.string.review_apply)) and isButton()).assertExists()
    }

    @Test
    fun underAnOwnCoverTheCoverLineSaysItStaysOnTopAndStartsUnticked() {
        show(FakeReviewBackend(result(found()), details = ReviewFixtures.currentDetails(coverSource = CoverSource.SONG_OWN, coverWidth = 300)))
        waitForCard()

        compose.waitUntilAtLeastOneExists(hasTestTag(ReviewTags.COVER_LINE), timeoutMillis = 10_000)
        compose.onNodeWithTag(ReviewTags.COVER_LINE).performScrollTo().assert(isCheckbox()).assertIsOff()
        compose.onNode(hasText(text(R.string.review_cover_own_song))).assertExists()
        // TalkBack reads the line by its description, so the note is part of it.
        compose.onNodeWithTag(ReviewTags.COVER_LINE).assert(hasContentDescription(text(R.string.review_cover_own_song), substring = true))
    }

    @Test
    fun talkBackReadsFromAnotherEditionWithTheCoverLine() {
        show(FakeReviewBackend(result(found()), coverResult = CaaResult.Found(ReviewFixtures.cover(CoverEntity.RELEASE_GROUP))))
        waitForCard()

        compose.waitUntilAtLeastOneExists(hasTestTag(ReviewTags.COVER_LINE), timeoutMillis = 10_000)
        compose.onNodeWithTag(ReviewTags.COVER_LINE).performScrollTo()
            .assert(hasContentDescription(text(R.string.review_another_edition), substring = true))
    }

    @Test
    fun onlineLookupsOffShowsTheDisclosureAndEnableRunsAgain() {
        val backend = FakeReviewBackend(result(IdentificationOutcome.Disabled, read = false), result(found()))
        show(backend)

        waitForText(text(R.string.identification_disclosure_title))
        compose.onNode(hasText(text(R.string.identification_enable)) and isButton()).performClick()

        waitForCard()
        assertThat(backend.calls.take(3)).containsExactly("identify ${ReviewFixtures.libraryFile}", "enable", "identify ${ReviewFixtures.libraryFile}")
    }

    @Test
    fun notNowInTheDisclosureClosesTheScreen() {
        val backend = FakeReviewBackend(result(IdentificationOutcome.Disabled, read = false))
        show(backend)

        waitForText(text(R.string.identification_disclosure_title))
        compose.onNode(hasText(text(R.string.identification_not_now)) and isButton()).performClick()

        compose.waitUntil(10_000) { closed }
        assertThat(backend.calls).endsWith("not now")
    }

    @Test
    fun noNetworkAndSignInNeededSaySoWithTryAgain() {
        val backend = FakeReviewBackend(result(IdentificationOutcome.NoNetwork, read = false), result(IdentificationOutcome.CaptivePortal, read = false))
        show(backend)

        waitForText(text(R.string.review_no_network))
        click(R.string.review_try_again)

        waitForText(text(R.string.review_captive_portal))
        compose.onNode(hasText(text(R.string.review_try_again)) and isButton()).assertHasClickAction()
    }

    @Test
    fun aStoppedProviderSaysWhyAndOffersNoTryAgain() {
        val stopped = IdentificationOutcome.ProviderStopped(ProviderId.MUSICBRAINZ, StopReason.USER_AGENT_REJECTED)
        show(FakeReviewBackend(result(stopped, read = false)))

        waitForText(text(R.string.review_stopped_user_agent, text(R.string.identification_name_musicbrainz)))
        assertThat(compose.onAllNodes(hasText(text(R.string.review_try_again))).fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun noKeySaysWhatWasSearchedAndOffersNoTryAgain() {
        show(FakeReviewBackend(result(IdentificationOutcome.NoKey)))

        waitForText(text(R.string.review_no_key))
        assertThat(compose.onAllNodes(hasText(text(R.string.review_try_again))).fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun anUnreadableFileAndAnUnavailableProviderHaveTheirTextsWithTryAgain() {
        val backend = FakeReviewBackend(
            result(IdentificationOutcome.Unreadable, read = false),
            result(IdentificationOutcome.Unavailable(ProviderId.ACOUSTID)),
        )
        show(backend)

        waitForText(text(R.string.review_unreadable))
        click(R.string.review_try_again)

        waitForText(text(R.string.review_unavailable, text(R.string.identification_name_acoustid)))
        compose.onNode(hasText(text(R.string.review_try_again)) and isButton()).assertHasClickAction()
    }

    @Test
    fun aRetryTimeIsShownInTheDevicesTimeFormat() {
        val at = Instant.parse("2026-11-16T11:35:00Z")
        show(FakeReviewBackend(result(found(ReviewFixtures.match(score = 0.2), edition = null), retryAt = at)))

        val time = android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date.from(at))
        waitForText(text(R.string.review_retry_at, time))
    }

    @Test
    fun talkBackReadsTheConfidenceAndTheActionsAreButtons() {
        show(FakeReviewBackend(result(found())))
        waitForCard()

        compose.onNodeWithTag(ReviewTags.CONFIDENCE)
            .assert(hasContentDescription(text(R.string.review_confidence_description, percent.format(0.96), text(R.string.review_band_high))))
        for (action in listOf(R.string.review_apply, R.string.review_keep_as_is, R.string.review_skip)) {
            compose.onNode(hasText(text(action)) and isButton()).performScrollTo().assertHasClickAction()
        }
        for (field in listOf(ProtectedField.TITLE, ProtectedField.ARTIST, ProtectedField.TRACK_NUMBER)) {
            compose.onNodeWithTag(ReviewTags.line(field)).performScrollTo().assert(isCheckbox()).assertHasClickAction()
        }
    }

    @Test
    fun aProtectedFieldIsNamedAndHasNoLine() {
        show(FakeReviewBackend(result(found()), protected = setOf(ProtectedField.TITLE)))
        waitForCard()

        assertThat(compose.onAllNodes(hasTestTag(ReviewTags.line(ProtectedField.TITLE))).fetchSemanticsNodes()).isEmpty()
        compose.onNodeWithText(text(R.string.review_protected_note, text(R.string.identification_field_title))).performScrollTo().assertExists()
    }

    private fun hasAnyAncestorTag(tag: String) = androidx.compose.ui.test.hasAnyAncestor(hasTestTag(tag))
}
