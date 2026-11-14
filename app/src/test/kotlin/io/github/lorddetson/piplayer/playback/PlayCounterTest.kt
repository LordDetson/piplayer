package io.github.lorddetson.piplayer.playback

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Counting plays from listened time (docs/playback.md §11). */
class PlayCounterTest {

    private var now = 1_000L
    private val counter = PlayCounter(clock = { now })

    private fun play(ms: Long) {
        now += ms
    }

    private fun checks(): Int {
        var count = 0
        repeat(3) { if (counter.check()) count++ }
        return count
    }

    @Test
    fun `a three-minute song counts once half of it is listened`() {
        counter.start("1", durationMs = 180_000, startedAt = 5, playing = true)

        play(89_999)
        assertThat(counter.check()).isFalse()
        assertThat(counter.msUntilCount()).isEqualTo(1)
        play(1)
        assertThat(counter.check()).isTrue()
        assertThat(counter.listenedMs()).isEqualTo(90_000)
    }

    @Test
    fun `a long song counts after four minutes`() {
        counter.start("1", durationMs = 20 * 60_000, startedAt = 5, playing = true)

        assertThat(counter.msUntilCount()).isEqualTo(240_000)
        play(239_999)
        assertThat(counter.check()).isFalse()
        play(1)
        assertThat(counter.check()).isTrue()
    }

    @Test
    fun `a song shorter than thirty seconds never counts`() {
        counter.start("1", durationMs = 29_999, startedAt = 5, playing = true)

        play(60_000)
        assertThat(counter.check()).isFalse()
        assertThat(counter.msUntilCount()).isNull()
        assertThat(PlayCounter.thresholdMs(29_999)).isNull()
        assertThat(PlayCounter.thresholdMs(30_000)).isEqualTo(15_000)
    }

    @Test
    fun `a song of unknown length counts at four minutes, and its length may come later`() {
        counter.start("1", durationMs = null, startedAt = 5, playing = true)
        assertThat(PlayCounter.thresholdMs(null)).isEqualTo(240_000)

        play(100_000)
        assertThat(counter.check()).isFalse()
        counter.setDuration(150_000)
        play(1)
        // Half of 150 s is 75 s, already passed.
        assertThat(counter.check()).isTrue()
    }

    @Test
    fun `pauses add nothing`() {
        counter.start("1", durationMs = 60_000, startedAt = 5, playing = true)

        play(20_000)
        counter.setPlaying(false)
        play(600_000)
        assertThat(counter.check()).isFalse()
        assertThat(counter.listenedMs()).isEqualTo(20_000)
        assertThat(counter.msUntilCount()).isNull()
        counter.setPlaying(true)
        assertThat(counter.msUntilCount()).isEqualTo(10_000)
        play(10_000)
        assertThat(counter.check()).isTrue()
    }

    @Test
    fun `a seek adds nothing because only playing time counts`() {
        counter.start("1", durationMs = 200_000, startedAt = 5, playing = true)

        play(10_000)
        // A seek to near the end: the player buffers, then plays the last seconds.
        counter.setPlaying(false)
        play(500)
        counter.setPlaying(true)
        play(5_000)
        counter.setPlaying(false)

        assertThat(counter.listenedMs()).isEqualTo(15_000)
        assertThat(counter.check()).isFalse()
    }

    @Test
    fun `a playback counts once, and a new playback of the same song counts again`() {
        counter.start("1", durationMs = 60_000, startedAt = 5, playing = true)
        play(60_000)

        assertThat(checks()).isEqualTo(1)
        assertThat(counter.msUntilCount()).isNull()
        val ended = counter.end()
        assertThat(ended).isEqualTo(EndedPlayback("1", startedAt = 5, listenedMs = 60_000, counted = true))

        // Repeat one: the same item again.
        counter.start("1", durationMs = 60_000, startedAt = 70, playing = true)
        play(30_000)
        assertThat(checks()).isEqualTo(1)
    }

    @Test
    fun `an ended playback that did not count says so, and nothing is current after it`() {
        counter.start("1", durationMs = 60_000, startedAt = 5, playing = true)
        play(10_000)

        assertThat(counter.end()).isEqualTo(EndedPlayback("1", 5, 10_000, counted = false))
        assertThat(counter.mediaId).isNull()
        assertThat(counter.end()).isNull()
        assertThat(counter.check()).isFalse()
    }

    @Test
    fun `progress is given only for the current item`() {
        counter.start("7", durationMs = 60_000, startedAt = 5, playing = true)
        play(12_000)

        assertThat(counter.progress("7")).isEqualTo(ListenProgress(12_000, counted = false, startedAt = 5))
        assertThat(counter.progress("8")).isNull()
    }

    @Test
    fun `a resumed playback goes on from its listened time and keeps its start`() {
        counter.start("1", durationMs = 100_000, startedAt = 99, playing = false, resume = ListenProgress(40_000, false, 5))

        assertThat(counter.listenedMs()).isEqualTo(40_000)
        counter.setPlaying(true)
        play(10_000)
        assertThat(counter.check()).isTrue()
        assertThat(counter.end()?.startedAt).isEqualTo(5)
    }

    @Test
    fun `a resumed playback that already counted does not count again`() {
        counter.start("1", durationMs = 100_000, startedAt = 99, playing = true, resume = ListenProgress(60_000, true, 5))

        play(100_000)
        assertThat(counter.check()).isFalse()
        assertThat(counter.end()).isEqualTo(EndedPlayback("1", 5, 160_000, counted = true))
    }
}
