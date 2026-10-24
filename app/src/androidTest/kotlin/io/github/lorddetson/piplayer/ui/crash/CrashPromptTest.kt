package io.github.lorddetson.piplayer.ui.crash

import android.content.ClipboardManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.crash.CrashKind
import io.github.lorddetson.piplayer.crash.CrashReports
import io.github.lorddetson.piplayer.crash.ReportStore
import io.github.lorddetson.piplayer.ui.theme.PiPlayerTheme
import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The dialog after a crash and the report screen (docs/playback.md §7.3). */
@RunWith(AndroidJUnit4::class)
class CrashPromptTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = TestFiles.targetContext
    private fun text(id: Int) = context.getString(id)

    private val dir = File(context.cacheDir, "crash-prompt-test")
    private val shared = mutableListOf<String>()

    @Before
    @After
    fun clean() {
        dir.deleteRecursively()
    }

    private fun offer(vararg reports: Pair<CrashKind, String>): CrashReports {
        val store = ReportStore(dir)
        reports.forEachIndexed { index, (kind, text) -> store.write(kind, TIME + index, 100 + index, text) }
        return CrashReports(store, "HEADER\n", exits = { emptyList() }).apply { refresh() }
    }

    private fun show(reports: CrashReports) = compose.setContent {
        PiPlayerTheme {
            CrashPrompt(reports, share = { shared += it })
        }
    }

    @Test
    fun oneReportShowsItsKindAndTime() {
        show(offer(CrashKind.NATIVE to "native report"))

        compose.onNodeWithText(text(R.string.crash_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.crash_kind_native), substring = true).assertIsDisplayed()
        compose.onNodeWithText(crashTime(context, TIME), substring = true).assertIsDisplayed()
    }

    @Test
    fun severalReportsShowHowManyThereAre() {
        show(offer(CrashKind.JAVA to "a", CrashKind.ANR to "b"))

        compose.onNodeWithText(context.resources.getQuantityString(R.plurals.crash_several, 2, 2)).assertIsDisplayed()
    }

    @Test
    fun theReportsCountAsShownOnceTheDialogAppearsAndCloseEndsIt() {
        val reports = offer(CrashKind.JAVA to "report")
        show(reports)

        compose.waitUntil(5_000) { ReportStore(dir).unshown().isEmpty() }
        compose.onNodeWithText(text(R.string.crash_close)).performClick()

        compose.onNodeWithText(text(R.string.crash_title)).assertDoesNotExist()
        assertThat(reports.pending.value).isNull()
    }

    @Test
    fun shareSendsTheTextAndClosesTheDialog() {
        val reports = offer(CrashKind.JAVA to "the report")
        show(reports)

        compose.onNodeWithText(text(R.string.crash_share)).performClick()
        compose.waitForIdle()

        assertThat(shared).singleElement().asString().contains("the report")
        compose.onNodeWithText(text(R.string.crash_title)).assertDoesNotExist()
        assertThat(reports.pending.value).isNull()
    }

    @Test
    fun theReportScreenScrollsToTheEndOfALongReport() {
        show(offer(CrashKind.ANR to (1..5_000).joinToString("\n") { "line $it of a long trace" }))

        compose.onNodeWithText(text(R.string.crash_show)).performClick()

        compose.onNodeWithText(text(R.string.crash_report_title)).assertIsDisplayed()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("line 5000 of a long trace", substring = true))
        compose.onNodeWithText("line 5000 of a long trace", substring = true).assertIsDisplayed()
    }

    @Test
    fun theReportScreenSharesAndStaysOpenThenClosesTheOffer() {
        val reports = offer(CrashKind.JAVA to "the report")
        show(reports)
        compose.onNodeWithText(text(R.string.crash_show)).performClick()

        compose.onNodeWithContentDescription(text(R.string.crash_share)).performClick()
        compose.waitForIdle()

        assertThat(shared).singleElement().asString().contains("the report")
        compose.onNodeWithText(text(R.string.crash_report_title)).assertIsDisplayed()

        compose.onNodeWithContentDescription(text(R.string.crash_close)).performClick()

        compose.onNodeWithText(text(R.string.crash_report_title)).assertDoesNotExist()
        assertThat(reports.pending.value).isNull()
    }

    @Test
    fun copyPutsTheReportOnTheClipboard() {
        show(offer(CrashKind.JAVA to "copy me"))
        compose.onNodeWithText(text(R.string.crash_show)).performClick()

        compose.onNodeWithContentDescription(text(R.string.crash_copy)).performClick()
        compose.waitForIdle()

        var clip: String? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            clip = context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()
        }
        assertThat(clip).contains("copy me")
    }

    private companion object {
        const val TIME = 1_793_000_000_000L
    }
}
