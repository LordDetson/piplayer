package io.github.lorddetson.piplayer.playback

import androidx.media3.common.Player
import io.github.lorddetson.piplayer.userdata.ListenOutcome
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

/** The outcome of a listening from how its playback ended (docs/playback.md §12). */
class ListenOutcomesTest {

    private fun outcome(
        end: ListenEnd,
        listenedMs: Long,
        endPositionMs: Long? = null,
        durationMs: Long? = 180_000,
        failed: Boolean = false,
    ) = ListenOutcomes.of(end, listenedMs, endPositionMs, durationMs, failed)

    @Test
    fun `a skip after less than thirty seconds is early and after thirty seconds late`() {
        assertThat(outcome(ListenEnd.MOVED_ON, listenedMs = 29_900, endPositionMs = 29_900)).isEqualTo(ListenOutcome.EARLY_SKIP)
        assertThat(outcome(ListenEnd.MOVED_ON, listenedMs = 29_999, endPositionMs = 29_999)).isEqualTo(ListenOutcome.EARLY_SKIP)
        assertThat(outcome(ListenEnd.MOVED_ON, listenedMs = 30_000, endPositionMs = 30_000)).isEqualTo(ListenOutcome.LATE_SKIP)
        assertThat(outcome(ListenEnd.MOVED_ON, listenedMs = 1, endPositionMs = 1)).isEqualTo(ListenOutcome.EARLY_SKIP)
        assertThat(ListenOutcomes.EARLY_SKIP_LIMIT_MS).isEqualTo(30_000)
    }

    @Test
    fun `the listened time decides a skip, not the position a seek reached`() {
        // Sought to two minutes and moved on 10 s later: an early skip; listened for 40 s from a seek near the start: late.
        assertThat(outcome(ListenEnd.MOVED_ON, listenedMs = 10_000, endPositionMs = 130_000)).isEqualTo(ListenOutcome.EARLY_SKIP)
        assertThat(outcome(ListenEnd.MOVED_ON, listenedMs = 40_000, endPositionMs = 5_000)).isEqualTo(ListenOutcome.LATE_SKIP)
    }

    @Test
    fun `a late skip has the share of the song listened, unknown without a length`() {
        assertThat(outcome(ListenEnd.MOVED_ON, listenedMs = 90_000, endPositionMs = 90_000)).isEqualTo(ListenOutcome.LATE_SKIP)
        assertThat(ListenOutcomes.share(90_000, 180_000)).isCloseTo(0.5, within(1e-9))
        assertThat(ListenOutcomes.share(45_000, 180_000)).isCloseTo(0.25, within(1e-9))
        // Paused and resumed, or listened past a seek back: never more than the whole song.
        assertThat(ListenOutcomes.share(200_000, 180_000)).isEqualTo(1.0)
        assertThat(ListenOutcomes.share(90_000, null)).isNull()
        assertThat(ListenOutcomes.share(90_000, 0)).isNull()
        // A skip of a song of unknown length is still late after 30 s.
        assertThat(outcome(ListenEnd.MOVED_ON, listenedMs = 30_000, durationMs = null)).isEqualTo(ListenOutcome.LATE_SKIP)
    }

    @Test
    fun `a playback that played into its end was listened to the end whatever its listened time`() {
        assertThat(outcome(ListenEnd.REACHED_END, listenedMs = 180_000)).isEqualTo(ListenOutcome.FINISHED)
        // A seek near the end that let the song end: the stored listened time tells it apart.
        assertThat(outcome(ListenEnd.REACHED_END, listenedMs = 2_000)).isEqualTo(ListenOutcome.FINISHED)
        assertThat(outcome(ListenEnd.REACHED_END, listenedMs = 20_000, durationMs = 25_000)).isEqualTo(ListenOutcome.FINISHED)
        assertThat(outcome(ListenEnd.REACHED_END, listenedMs = 20_000, durationMs = null)).isEqualTo(ListenOutcome.FINISHED)
    }

    @Test
    fun `moving on within the end of the song is listened to the end, just before it a skip`() {
        // The end of a 3-minute song is its last 3.6 s (§4.3.1); of a 10-minute one its last 5 s.
        assertThat(outcome(ListenEnd.MOVED_ON, listenedMs = 176_400, endPositionMs = 176_400)).isEqualTo(ListenOutcome.FINISHED)
        assertThat(outcome(ListenEnd.MOVED_ON, listenedMs = 176_399, endPositionMs = 176_399)).isEqualTo(ListenOutcome.LATE_SKIP)
        assertThat(outcome(ListenEnd.MOVED_ON, listenedMs = 595_000, endPositionMs = 595_000, durationMs = 600_000))
            .isEqualTo(ListenOutcome.FINISHED)
        assertThat(outcome(ListenEnd.MOVED_ON, listenedMs = 594_999, endPositionMs = 594_999, durationMs = 600_000))
            .isEqualTo(ListenOutcome.LATE_SKIP)
        // Within the end of a short song after little listening (sought there): listened to the end too.
        assertThat(outcome(ListenEnd.MOVED_ON, listenedMs = 3_000, endPositionMs = 179_000)).isEqualTo(ListenOutcome.FINISHED)
        // Without a length or a position the end is not known, and the listened time decides.
        assertThat(outcome(ListenEnd.MOVED_ON, listenedMs = 179_000, endPositionMs = 179_000, durationMs = null))
            .isEqualTo(ListenOutcome.LATE_SKIP)
        assertThat(outcome(ListenEnd.MOVED_ON, listenedMs = 179_000, endPositionMs = null)).isEqualTo(ListenOutcome.LATE_SKIP)
    }

    @Test
    fun `no outcome without an end that tells, after an error or without listened time`() {
        assertThat(outcome(ListenEnd.NONE, listenedMs = 100_000, endPositionMs = 100_000)).isNull()
        assertThat(outcome(ListenEnd.NONE, listenedMs = 180_000, endPositionMs = 179_999)).isNull()
        assertThat(outcome(ListenEnd.MOVED_ON, listenedMs = 10_000, endPositionMs = 10_000, failed = true)).isNull()
        assertThat(outcome(ListenEnd.REACHED_END, listenedMs = 180_000, failed = true)).isNull()
        assertThat(outcome(ListenEnd.MOVED_ON, listenedMs = 0, endPositionMs = 0)).isNull()
        assertThat(outcome(ListenEnd.REACHED_END, listenedMs = 0)).isNull()
    }

    @Test
    fun `an automatic transition and a repeat-one replay reach the end`() {
        assertThat(ListenOutcomes.endOf(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO, "1", "2")).isEqualTo(ListenEnd.REACHED_END)
        // Repeat all wraps to the first song automatically too.
        assertThat(ListenOutcomes.endOf(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO, "9", "1")).isEqualTo(ListenEnd.REACHED_END)
        assertThat(ListenOutcomes.endOf(Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT, "1", "1")).isEqualTo(ListenEnd.REACHED_END)
    }

    @Test
    fun `a seek to another entry moves on`() {
        assertThat(ListenOutcomes.endOf(Player.MEDIA_ITEM_TRANSITION_REASON_SEEK, "1", "2")).isEqualTo(ListenEnd.MOVED_ON)
        // Another entry of the same file (an entry an edit added) is another listening that the user chose.
        assertThat(ListenOutcomes.endOf(Player.MEDIA_ITEM_TRANSITION_REASON_SEEK, "1", "1/a3")).isEqualTo(ListenEnd.MOVED_ON)
    }

    @Test
    fun `a queue change moves on to another song, never to the same one or to nothing`() {
        assertThat(ListenOutcomes.endOf(Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED, "1", "2")).isEqualTo(ListenEnd.MOVED_ON)
        assertThat(ListenOutcomes.endOf(Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED, "1", "1")).isEqualTo(ListenEnd.NONE)
        assertThat(ListenOutcomes.endOf(Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED, "1", null)).isEqualTo(ListenEnd.NONE)
    }

    @Test
    fun `a transition without a playback before it ends nothing`() {
        assertThat(ListenOutcomes.endOf(Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED, null, "1")).isEqualTo(ListenEnd.NONE)
        assertThat(ListenOutcomes.endOf(Player.MEDIA_ITEM_TRANSITION_REASON_SEEK, null, "1")).isEqualTo(ListenEnd.NONE)
    }

    @Test
    fun `the stored codes stay as they are`() {
        assertThat(ListenOutcome.entries.map { it.code }).containsExactly(1, 2, 3)
        assertThat(ListenOutcome.of(2)).isEqualTo(ListenOutcome.LATE_SKIP)
        assertThat(ListenOutcome.of(4)).isNull()
    }
}
