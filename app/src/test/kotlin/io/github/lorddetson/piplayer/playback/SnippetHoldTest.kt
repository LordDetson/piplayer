package io.github.lorddetson.piplayer.playback

import io.github.lorddetson.piplayer.playback.SnippetHold.End
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The playback service's hold of the main player for a snippet of the review card (TI §6). */
class SnippetHoldTest {

    private val hold = SnippetHold()

    @Test
    fun `without a hold an end and a swipe change nothing`() {
        assertThat(hold.held).isFalse()
        assertThat(hold.taskRemoved()).describedAs("the service stays for a hold").isFalse()
        assertThat(hold.end(resume = true)).isEqualTo(End.NONE)
        assertThat(hold.end(resume = false)).isEqualTo(End.NONE)
    }

    @Test
    fun `a hold ends once with a resume only when asked`() {
        hold.hold()
        assertThat(hold.held).isTrue()
        assertThat(hold.end(resume = true)).isEqualTo(End.RESUME)
        assertThat(hold.held).isFalse()
        assertThat(hold.end(resume = true)).describedAs("a second end").isEqualTo(End.NONE)

        hold.hold()
        assertThat(hold.end(resume = false)).isEqualTo(End.STAY_PAUSED)
    }

    @Test
    fun `a sleep timer that stopped playback during the hold keeps the main player paused`() {
        hold.hold()
        hold.timerStopped()

        assertThat(hold.end(resume = true)).isEqualTo(End.STAY_PAUSED)
    }

    @Test
    fun `a timer that stopped before the hold does not count for it`() {
        hold.timerStopped()
        hold.hold()

        assertThat(hold.end(resume = true)).isEqualTo(End.RESUME)
    }

    @Test
    fun `a swipe from recents during the hold keeps the service until the hold ends without a resume`() {
        hold.hold()
        assertThat(hold.taskRemoved()).isTrue()
        assertThat(hold.end(resume = true)).isEqualTo(End.RESUME)

        hold.hold()
        assertThat(hold.taskRemoved()).isTrue()
        assertThat(hold.end(resume = false)).isEqualTo(End.STOP_SERVICE)

        hold.hold()
        hold.taskRemoved()
        hold.timerStopped()
        assertThat(hold.end(resume = true)).describedAs("the timer stopped it after the swipe").isEqualTo(End.STOP_SERVICE)
    }

    @Test
    fun `a new hold starts clean`() {
        hold.hold()
        hold.taskRemoved()
        hold.timerStopped()
        hold.end(resume = false)

        hold.hold()
        assertThat(hold.end(resume = true)).isEqualTo(End.RESUME)
        hold.hold()
        assertThat(hold.end(resume = false)).isEqualTo(End.STAY_PAUSED)
    }

    @Test
    fun `a second hold request keeps what the first one learned`() {
        hold.hold()
        hold.timerStopped()
        hold.hold()

        assertThat(hold.end(resume = true)).isEqualTo(End.STAY_PAUSED)
    }

    @Test
    fun `the main player playing by other means ends the hold`() {
        hold.hold()
        hold.taskRemoved()

        hold.playedOtherwise()

        assertThat(hold.held).isFalse()
        assertThat(hold.end(resume = false)).describedAs("the snippet's late end").isEqualTo(End.NONE)
        assertThat(hold.taskRemoved()).isFalse()
    }
}
