package io.github.lorddetson.piplayer.ui.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The sleep timer sheet's last choice in the app's settings (docs/playback.md §6.2). */
@RunWith(AndroidJUnit4::class)
class TimerChoiceStoreTest {

    private val context = TestFiles.targetContext

    @Before
    @After
    fun forget() = TimerChoiceStore.clearForTests(context)

    @Test
    fun theFirstChoiceIsHalfAnHourWithoutTheCheckbox() {
        assertThat(TimerChoiceStore(context).read()).isEqualTo(TimerChoice(hours = 0, minutes = 30, finishTrack = false))
    }

    @Test
    fun aSavedChoiceOutlastsTheStore() {
        TimerChoiceStore(context).save(TimerChoice(hours = 1, minutes = 15, finishTrack = true))

        assertThat(TimerChoiceStore(context).read()).isEqualTo(TimerChoice(hours = 1, minutes = 15, finishTrack = true))
    }
}
