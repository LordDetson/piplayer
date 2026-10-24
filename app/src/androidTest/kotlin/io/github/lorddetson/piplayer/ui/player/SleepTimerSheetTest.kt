package io.github.lorddetson.piplayer.ui.player

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertRangeInfoEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.playback.PlaybackExtras
import io.github.lorddetson.piplayer.ui.theme.PiPlayerTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The sleep timer's sheet (docs/playback.md §6.2, §6.7). */
@RunWith(AndroidJUnit4::class)
class SleepTimerSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = TestFiles.targetContext
    private fun text(id: Int) = context.getString(id)

    private val starts = mutableListOf<Pair<Long, Boolean>>()
    private var cancelled = false

    private val presets = listOf("1:30", "1:00", "0:45", "0:30", "0:15")

    private fun show(extras: PlaybackExtras = PlaybackExtras.NONE, nowMs: Long = NOW) = compose.setContent {
        PiPlayerTheme {
            SleepTimerContent(extras, nowMs, onStart = { duration, finish -> starts += duration to finish }, onCancel = { cancelled = true })
        }
    }

    private fun hours() = compose.onNodeWithContentDescription(text(R.string.timer_hours))
    private fun minutes() = compose.onNodeWithContentDescription(text(R.string.timer_minutes))

    private fun SemanticsNodeInteraction.turnTo(value: Int) =
        performSemanticsAction(SemanticsActions.SetProgress) { it(value.toFloat()) }

    @Test
    fun theReadyMadeTimesRunFromTheLongestToTheShortest() {
        show()

        val lefts = presets.map { compose.onNodeWithText(it).getBoundsInRoot().left }

        assertThat(lefts).isSorted()
        assertThat(lefts.toSet()).hasSize(presets.size)
    }

    @Test
    fun thereAreWheelsForHoursAndMinutesOnly() {
        show()

        hours().assertIsDisplayed().assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription))
        minutes().assertIsDisplayed().assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription))
        hours().turnTo(1)
        minutes().turnTo(20)
        hours().assertRangeInfoEquals(ProgressBarRangeInfo(1f, 0f..23f, steps = 22))
        minutes().assertRangeInfoEquals(ProgressBarRangeInfo(20f, 0f..59f, steps = 58))
        compose.onNodeWithText("Seconds", ignoreCase = true).assertDoesNotExist()
    }

    @Test
    fun aChipSetsTheWheelsAndIsOutlined() {
        show()

        compose.onNodeWithText("0:45").performClick()

        hours().assertRangeInfoEquals(ProgressBarRangeInfo(0f, 0f..23f, steps = 22))
        minutes().assertRangeInfoEquals(ProgressBarRangeInfo(45f, 0f..59f, steps = 58))
        compose.onNodeWithText("0:45").assertIsSelected()
        presets.filter { it != "0:45" }.forEach { compose.onNodeWithText(it).assertIsNotSelected() }
    }

    @Test
    fun wheelsTurnedToAReadyMadeTimeOutlineItsChip() {
        show()

        hours().turnTo(1)
        minutes().turnTo(30)
        compose.onNodeWithText("1:30").assertIsSelected()

        minutes().turnTo(20)
        presets.forEach { compose.onNodeWithText(it).assertIsNotSelected() }
    }

    @Test
    fun startIsDisabledAtZero() {
        show()

        hours().turnTo(0)
        minutes().turnTo(0)
        compose.onNodeWithText(text(R.string.timer_start)).assertIsNotEnabled()

        minutes().turnTo(5)
        compose.onNodeWithText(text(R.string.timer_start)).assertIsEnabled()
    }

    @Test
    fun startSendsTheTimeAndWhetherToFinishTheTrack() {
        show()
        hours().turnTo(1)
        minutes().turnTo(30)

        compose.onNodeWithText(text(R.string.timer_finish_track)).performClick()
        compose.onNodeWithText(text(R.string.timer_finish_track)).assertIsOn()
        compose.onNodeWithText(text(R.string.timer_start)).performClick()

        assertThat(starts).containsExactly(90 * 60_000L to true)
    }

    @Test
    fun aRunningTimerShowsItsTimeLeftWithoutSecondsAndCancels() {
        show(PlaybackExtras(sleepDeadlineMs = NOW + 41 * 60_000L + 10_000, finishTrack = true))

        compose.onNodeWithText("0:42").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.timer_will_finish_track)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.timer_start)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.timer_cancel)).performClick()

        assertThat(cancelled).isTrue()
    }

    private companion object {
        const val NOW = 1_000_000L
    }
}
