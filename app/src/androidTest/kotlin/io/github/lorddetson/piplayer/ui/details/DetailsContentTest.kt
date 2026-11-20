package io.github.lorddetson.piplayer.ui.details

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.identification.provider.ProviderId
import io.github.lorddetson.piplayer.identification.score.Reason
import io.github.lorddetson.piplayer.identification.score.Source
import io.github.lorddetson.piplayer.identification.score.Tier
import io.github.lorddetson.piplayer.library.details.Badge
import io.github.lorddetson.piplayer.library.details.BadgeState
import io.github.lorddetson.piplayer.library.details.DecisionTarget
import io.github.lorddetson.piplayer.library.details.DetailField
import io.github.lorddetson.piplayer.library.details.DetailRow
import io.github.lorddetson.piplayer.library.details.DetailValue
import io.github.lorddetson.piplayer.library.details.IdentificationFacts
import io.github.lorddetson.piplayer.library.details.ListDecision
import io.github.lorddetson.piplayer.library.details.MusicBrainzKind
import io.github.lorddetson.piplayer.library.details.TraceRun
import io.github.lorddetson.piplayer.library.details.TraceStep
import io.github.lorddetson.piplayer.library.details.TrackDetails
import io.github.lorddetson.piplayer.library.details.ValueSource
import io.github.lorddetson.piplayer.library.values.DecisionKind
import io.github.lorddetson.piplayer.settings.ProtectedField
import io.github.lorddetson.piplayer.ui.identification.IdentifyTarget
import io.github.lorddetson.piplayer.ui.identification.LocalIdentify
import io.github.lorddetson.piplayer.ui.theme.PiPlayerTheme
import io.github.lorddetson.piplayer.userdata.Decisions
import java.text.NumberFormat
import java.util.Collections
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The sources, decisions, identification, trace and badge of track details on fake details (docs/library.md §18.7). */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class DetailsContentTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = TestFiles.targetContext
    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    private val recording = "8f3471b5-7e6a-48da-86a9-c1c07a0f47ae"
    private val target = DecisionTarget(3, "%064x".format(3), Decisions.LocationValues("external_primary", "Music/", "a.flac", emptyMap()))

    /** Records what the screen asks; a revert writes decision 41. */
    private class FakeActions : DetailsActions {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override suspend fun revert(target: DecisionTarget, field: ProtectedField): Long? {
            calls += "revert $field"
            return 41
        }

        override suspend fun undoIdentification(target: DecisionTarget): Long? {
            calls += "undo identification"
            return 5
        }

        override suspend fun keepPiPlayersValue(target: DecisionTarget, field: ProtectedField) {
            calls += "keep $field"
        }

        override suspend fun undo(decisionId: Long): Boolean {
            calls += "undo $decisionId"
            return true
        }
    }

    private val actions = FakeActions()
    private val identified = Collections.synchronizedList(mutableListOf<IdentifyTarget>())

    private fun details(
        rows: List<DetailRow>,
        badge: Badge? = null,
        identification: IdentificationFacts? = null,
        listDecision: ListDecision? = null,
        trace: List<TraceRun> = emptyList(),
        target: DecisionTarget? = this.target,
    ) = TrackDetails(7, "Decided title", "Ann", "Red", rows, badge, identification, listDecision, trace, target)

    private fun show(details: TrackDetails) {
        compose.setContent {
            PiPlayerTheme {
                CompositionLocalProvider(LocalIdentify provides { identified += it }) {
                    val snackbar = remember { SnackbarHostState() }
                    val scope = rememberCoroutineScope()
                    val commands = remember { DetailsCommands(actions, DetailsActionRunner(scope)) }
                    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
                        DetailsContent(details, null, snackbar, commands, Modifier.padding(padding).fillMaxSize())
                    }
                }
            }
        }
    }

    private fun waitForCall(call: String) = compose.waitUntil(5_000) { call in actions.calls }

    private val title = DetailRow(DetailField.TITLE, DetailValue.Text("Decided title"),
        ValueSource(DecisionKind.LEVEL_IDENTIFICATION, ProviderId.MUSICBRAINZ), decided = true)

    @Test
    fun theProblemReportPreviewsAnOptInTraceAndOnlyCopiesOnRequest() {
        val trace = listOf(
            TraceRun(1_700_000_000_000, "found=high", null, listOf(TraceStep("text_search", "musicbrainz"))),
        )
        show(details(listOf(title), trace = trace))

        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val previousClip = compose.runOnIdle {
            clipboard.primaryClip.also { clipboard.setPrimaryClip(ClipData.newPlainText("Test", "Existing clipboard")) }
        }
        try {
            val report = text(R.string.problem_report_title)
            val dialog = hasAnyAncestor(isDialog())
            val includeTrace = compose.onNode(hasText(text(R.string.problem_report_include_trace)) and dialog)
            val tracePreview = compose.onNode(hasText("found=high", substring = true) and dialog)
            val button = compose.onNodeWithText(report)

            compose.onNodeWithTag(DetailsTags.LIST).performScrollToNode(hasText(report))
            button.performClick()
            includeTrace.assertIsOff()
            compose.onNode(hasText("Android", substring = true) and dialog).assertExists()
            tracePreview.assertDoesNotExist()
            includeTrace.performClick()
            tracePreview.assertExists()
            assertThat(compose.runOnIdle { clipboard.primaryClip?.getItemAt(0)?.text?.toString() })
                .isEqualTo("Existing clipboard")

            compose.onNode(hasText(text(R.string.settings_dialog_cancel)) and dialog).performClick()
            button.performClick()
            includeTrace.assertIsOff()
            includeTrace.performClick()
            compose.onNode(hasText(text(R.string.problem_report_copy)) and dialog).performClick()

            val copied = compose.runOnIdle { clipboard.primaryClip?.getItemAt(0)?.text?.toString() }
            assertThat(copied).contains("PiPlayer", "Identification trace", "found=high", "text_search")
                .doesNotContain("Decided title", "a.flac")
            compose.onNode(isDialog()).assertDoesNotExist()
        } finally {
            compose.runOnIdle {
                if (previousClip == null) clipboard.clearPrimaryClip() else clipboard.setPrimaryClip(previousClip)
            }
        }
    }

    @Test
    fun eachValueSaysWhereItComesFrom() {
        show(
            details(
                listOf(
                    title,
                    DetailRow(DetailField.ARTIST, DetailValue.Text("Ann"), ValueSource(DecisionKind.LEVEL_USER_EDIT), decided = true),
                    DetailRow(DetailField.ALBUM, DetailValue.Text("Red"), ValueSource(DecisionKind.LEVEL_TAGS)),
                    DetailRow(DetailField.DATE, DetailValue.Text("1999"), ValueSource(DecisionKind.LEVEL_INDEX)),
                    DetailRow(DetailField.ALBUM_ARTIST, DetailValue.Empty, ValueSource(DecisionKind.LEVEL_USER_EDIT), decided = true),
                    DetailRow(DetailField.ACOUSTID_ID, DetailValue.AcoustId("9a4c1f2e-3b5d-4e6f-8a7b-0c1d2e3f4a5b"),
                        ValueSource(DecisionKind.LEVEL_IDENTIFICATION, ProviderId.ACOUSTID)),
                ),
            ),
        )

        compose.onNodeWithText(text(R.string.details_source_identified, "MusicBrainz")).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.details_source_tags)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.details_source_index)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.details_empty)).assertIsDisplayed()
        compose.onNodeWithTag(DetailsTags.LIST).performScrollToNode(hasText(text(R.string.details_source_identified, "AcoustID")))
        compose.onNodeWithText(text(R.string.details_source_identified, "AcoustID")).assertIsDisplayed()
        assertThat(compose.onAllNodes(hasText(text(R.string.details_source_edited))).fetchSemanticsNodes()).hasSize(2)
    }

    @Test
    fun aDecidedFieldOffersItsRevertOnceAndTheMessageUndoesIt() {
        val artistIds = listOf("a74b1b7f-71a5-4011-9441-d0b5e4122711", "b10bbbfc-cf9e-42e0-be17-e2c3e1d2600d").map {
            DetailRow(DetailField.ARTIST_ID, DetailValue.MusicBrainz(MusicBrainzKind.ARTIST, it),
                ValueSource(DecisionKind.LEVEL_IDENTIFICATION, ProviderId.MUSICBRAINZ), decided = true)
        }
        show(details(listOf(title, DetailRow(DetailField.ALBUM, DetailValue.Text("Red"), ValueSource(DecisionKind.LEVEL_TAGS))) + artistIds))

        // A field from the tags has none; the IDs are one field with one revert.
        assertThat(compose.onAllNodes(hasTestTag(DetailsTags.revert(DetailField.ALBUM))).fetchSemanticsNodes()).isEmpty()
        compose.onNodeWithTag(DetailsTags.LIST).performScrollToNode(hasTestTag(DetailsTags.revert(DetailField.ARTIST_ID)))
        assertThat(compose.onAllNodes(hasTestTag(DetailsTags.revert(DetailField.ARTIST_ID))).fetchSemanticsNodes()).hasSize(1)
        compose.onNodeWithTag(DetailsTags.revert(DetailField.ARTIST_ID)).performClick()
        waitForCall("revert MUSICBRAINZ_IDS")
        compose.onNodeWithTag(DetailsTags.LIST).performScrollToNode(hasTestTag(DetailsTags.revert(DetailField.TITLE)))
        compose.onNodeWithTag(DetailsTags.revert(DetailField.TITLE)).assertIsDisplayed()

        compose.onNodeWithTag(DetailsTags.revert(DetailField.TITLE)).performClick()
        waitForCall("revert TITLE")
        compose.waitUntilAtLeastOneExists(hasText(text(R.string.details_reverted)), 5_000)
        compose.onNodeWithText(text(R.string.review_undo)).performClick()

        waitForCall("undo 41")
    }

    @Test
    fun aFieldChangedInTheFileOffersToKeepPiPlayersValueOrToUseTheFiles() {
        show(details(listOf(title.copy(changedInFile = true))))

        compose.onNodeWithText(text(R.string.details_changed_in_file)).assertIsDisplayed()
        compose.onNodeWithTag(DetailsTags.keep(DetailField.TITLE)).performClick()
        waitForCall("keep TITLE")
        compose.waitUntilAtLeastOneExists(hasText(text(R.string.details_value_kept)), 5_000)
        compose.onNodeWithTag(DetailsTags.useFile(DetailField.TITLE)).performClick()

        waitForCall("revert TITLE")
        assertThat(actions.calls).containsExactly("keep TITLE", "revert TITLE")
    }

    @Test
    fun aChangedFieldOfSeveralRowsSaysSoOnceOnItsFirstRow() {
        val artistIds = listOf("a74b1b7f-71a5-4011-9441-d0b5e4122711", "b10bbbfc-cf9e-42e0-be17-e2c3e1d2600d").map {
            DetailRow(DetailField.ARTIST_ID, DetailValue.MusicBrainz(MusicBrainzKind.ARTIST, it),
                ValueSource(DecisionKind.LEVEL_IDENTIFICATION, ProviderId.MUSICBRAINZ), decided = true, changedInFile = true)
        }
        show(details(artistIds))

        compose.onNodeWithTag(DetailsTags.LIST).performScrollToNode(hasTestTag(DetailsTags.keep(DetailField.ARTIST_ID)))
        assertThat(compose.onAllNodes(hasText(text(R.string.details_changed_in_file))).fetchSemanticsNodes()).hasSize(1)
        assertThat(compose.onAllNodes(hasTestTag(DetailsTags.keep(DetailField.ARTIST_ID))).fetchSemanticsNodes()).hasSize(1)
    }

    @Test
    fun theIdentificationSectionUndoesTheIdentificationAndResetsASkip() {
        val reason = Reason(Source.FINGERPRINT, Tier.IDENTIFIER, setOf(Source.FINGERPRINT), 0.97, -800, 0.95, 0.9, emptySet())
        show(
            details(
                listOf(title),
                badge = Badge(BadgeState.IDENTIFIED, ProviderId.MUSICBRAINZ, 0.97),
                identification = IdentificationFacts(5, ProviderId.MUSICBRAINZ, 0.97, reason, 1_700_000_000_000, 1_699_999_000_000),
                listDecision = ListDecision(12, skipped = true),
            ),
        )

        compose.onNodeWithTag(DetailsTags.LIST).performScrollToNode(hasTestTag(DetailsTags.IDENTIFICATION))
        compose.onNodeWithTag(DetailsTags.IDENTIFICATION).assertIsDisplayed()
        compose.onNodeWithTag(DetailsTags.LIST).performScrollToNode(hasTestTag(DetailsTags.UNDO_IDENTIFICATION))
        compose.onNodeWithTag(DetailsTags.UNDO_IDENTIFICATION).performClick()
        waitForCall("undo identification")
        compose.waitUntilAtLeastOneExists(hasText(text(R.string.details_identification_undone)), 5_000)

        compose.onNodeWithTag(DetailsTags.LIST).performScrollToNode(hasTestTag(DetailsTags.RESET))
        compose.onNodeWithText(text(R.string.review_skipped)).assertIsDisplayed()
        compose.onNodeWithTag(DetailsTags.RESET).performClick()
        waitForCall("undo 12")
    }

    @Test
    fun withoutAnIdentityNothingCanBeDecided() {
        show(
            details(
                listOf(title.copy(changedInFile = true)),
                identification = IdentificationFacts(5, ProviderId.MUSICBRAINZ, 0.97, null, 1, null),
                listDecision = ListDecision(12, skipped = false),
                target = null,
            ),
        )

        compose.onNodeWithText(text(R.string.details_source_identified, "MusicBrainz")).assertIsDisplayed()
        for (tag in listOf(DetailsTags.revert(DetailField.TITLE), DetailsTags.keep(DetailField.TITLE), DetailsTags.useFile(DetailField.TITLE),
            DetailsTags.UNDO_IDENTIFICATION, DetailsTags.RESET)) {
            assertThat(compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes()).describedAs(tag).isEmpty()
        }
    }

    @Test
    fun theTraceListsItsRunsWithTheirOutcomesAndSteps() {
        show(
            details(
                listOf(title),
                trace = listOf(
                    TraceRun(2_000, "found=high", null, listOf(TraceStep("fingerprint", "acoustid", 200, 3, 0.97, 1_250))),
                    TraceRun(1_000, "no_network", null, listOf(TraceStep("local_read", elapsedMs = 12))),
                ),
            ),
        )

        compose.onNodeWithTag(DetailsTags.LIST).performScrollToNode(hasTestTag(DetailsTags.TRACE))
        compose.onNodeWithTag(DetailsTags.LIST).performScrollToNode(hasText(text(R.string.trace_outcome_no_network)))
        compose.onNodeWithText(text(R.string.trace_outcome_high)).assertExists()
        compose.onNodeWithText(text(R.string.trace_outcome_no_network)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.trace_stage_fingerprint), substring = true).assertExists()
        compose.onNodeWithText(text(R.string.trace_http, 200), substring = true).assertExists()
    }

    @Test
    fun theBadgeFromTagsOpensTheReview() {
        show(details(listOf(title), badge = Badge(BadgeState.FROM_TAGS)))

        compose.onNodeWithTag(BadgeTags.BADGE)
            .assertContentDescriptionEquals(text(R.string.badge_description, text(R.string.badge_from_tags)))
            .performClick()

        compose.waitUntil(5_000) { identified.isNotEmpty() }
        assertThat(identified).containsExactly(IdentifyTarget.Song(7))
    }

    @Test
    fun theBadgeOfAnIdentifiedSongShowsTheIdentificationSection() {
        val many = DetailField.entries.filter { it.section == io.github.lorddetson.piplayer.library.details.DetailSection.FILE }
            .map { DetailRow(it, DetailValue.Text(it.name)) }
        show(
            details(
                listOf(title) + many,
                badge = Badge(BadgeState.IDENTIFIED, ProviderId.MUSICBRAINZ, 0.97),
                identification = IdentificationFacts(5, ProviderId.MUSICBRAINZ, 0.97, null, 1, null),
            ),
        )
        val percent = NumberFormat.getPercentInstance(context.resources.configuration.locales[0]).format(0.97)
        val label = text(R.string.badge_identified, "MusicBrainz", percent)

        compose.onNodeWithTag(BadgeTags.BADGE).assertContentDescriptionEquals(text(R.string.badge_description, label)).performClick()

        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag(DetailsTags.IDENTIFICATION)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(DetailsTags.IDENTIFICATION).assertIsDisplayed()
        assertThat(identified).isEmpty()
    }
}
