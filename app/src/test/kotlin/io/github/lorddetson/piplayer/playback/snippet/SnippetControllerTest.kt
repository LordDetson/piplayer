package io.github.lorddetson.piplayer.playback.snippet

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The snippet's rules (TI §6, §11.1) on fakes that log every call in order. */
class SnippetControllerTest {

    private val log = mutableListOf<String>()

    private val main = object : MainPlayback {
        override var isPlaying = true

        override fun pause() {
            log += "main.pause"
            isPlaying = false
        }

        override fun play() {
            log += "main.play"
            isPlaying = true
        }
    }

    private var granted = true
    private val focusListeners = mutableListOf<(FocusChange) -> Unit>()
    private val focus = object : SnippetFocus {
        override fun request(onChange: (FocusChange) -> Unit): Boolean {
            log += "focus.request"
            if (granted) focusListeners += onChange
            return granted
        }

        override fun abandon() {
            log += "focus.abandon"
        }
    }

    private val outputEvents = mutableListOf<SnippetOutput.Events>()
    private val output = object : SnippetOutput<String> {
        override var positionMs = 0L

        override fun start(source: String, window: SnippetWindow, events: SnippetOutput.Events) {
            log += "output.start $source ${window.startMs}..${window.endMs}"
            outputEvents += events
            positionMs = window.startMs
        }

        override fun setVolume(volume: Float) {
            log += "output.volume $volume"
        }

        override fun stop() {
            log += "output.stop"
            positionMs = 0
        }
    }

    private val snippet = SnippetController(main, focus, output)

    private fun playing() = log.clear()

    @Test
    fun `the window starts at one third from 45 s up and at the start below 45 s or without a length`() {
        assertThat(SnippetWindow.of(60_000)).isEqualTo(SnippetWindow(20_000, 35_000))
        assertThat(SnippetWindow.of(45_000)).isEqualTo(SnippetWindow(15_000, 30_000))
        assertThat(SnippetWindow.of(300_001)).isEqualTo(SnippetWindow(100_000, 115_000))
        assertThat(SnippetWindow.of(44_999)).isEqualTo(SnippetWindow(0, 15_000))
        assertThat(SnippetWindow.of(2_000)).isEqualTo(SnippetWindow(0, 15_000))
        assertThat(SnippetWindow.of(null)).isEqualTo(SnippetWindow(0, 15_000))
        assertThat(SnippetWindow.of(0)).isEqualTo(SnippetWindow(0, 15_000))
        assertThat(SnippetWindow.of(-1)).isEqualTo(SnippetWindow(0, 15_000))
    }

    @Test
    fun `a playing main player is paused before the focus request and the snippet then plays its window`() {
        snippet.play("song", 60_000)

        assertThat(log).containsExactly("main.pause", "focus.request", "output.start song 20000..35000")
        assertThat(snippet.state.value).isEqualTo(SnippetState.Playing(20_000, 35_000))
        assertThat(snippet.positionMs).isEqualTo(20_000)
    }

    @Test
    fun `a main player that does not play is left alone`() {
        main.isPlaying = false

        snippet.play("song", 30_000)

        assertThat(log).containsExactly("focus.request", "output.start song 0..15000")
    }

    @Test
    fun `the end abandons the focus and then resumes the main player`() {
        snippet.play("song", 60_000)
        playing()

        outputEvents.single().ended()

        assertThat(log).containsExactly("output.stop", "focus.abandon", "main.play")
        assertThat(snippet.state.value).isEqualTo(SnippetState.Ended(SnippetEnd.FINISHED))
        assertThat(snippet.positionMs).isZero()
    }

    @Test
    fun `closing the card ends the snippet as its end does`() {
        snippet.play("song", 60_000)
        playing()

        snippet.stop()

        assertThat(log).containsExactly("output.stop", "focus.abandon", "main.play")
        assertThat(snippet.state.value).isEqualTo(SnippetState.Ended(SnippetEnd.CLOSED))
    }

    @Test
    fun `a file the snippet player cannot play ends the snippet and resumes the main player`() {
        snippet.play("song", 60_000)
        playing()

        outputEvents.single().failed()

        assertThat(log).containsExactly("output.stop", "focus.abandon", "main.play")
        assertThat(snippet.state.value).isEqualTo(SnippetState.Ended(SnippetEnd.FAILED))
    }

    @Test
    fun `a main player paused before the snippet stays paused after it`() {
        main.isPlaying = false
        snippet.play("song", 60_000)
        playing()

        outputEvents.single().ended()

        assertThat(log).containsExactly("output.stop", "focus.abandon")
    }

    @Test
    fun `a focus loss for good or for a call stops the snippet without resuming the main player`() {
        for (change in listOf(FocusChange.LOSS, FocusChange.LOSS_TRANSIENT)) {
            main.isPlaying = true
            focusListeners.clear()
            snippet.play("song", 60_000)
            playing()

            focusListeners.single()(change)

            assertThat(log).describedAs(change.name).containsExactly("output.stop", "focus.abandon")
            assertThat(snippet.state.value).isEqualTo(SnippetState.Ended(SnippetEnd.FOCUS_LOST))
        }
    }

    @Test
    fun `unplugged headphones stop the snippet without resuming the main player`() {
        snippet.play("song", 60_000)
        playing()

        outputEvents.single().becameNoisy()

        assertThat(log).containsExactly("output.stop", "focus.abandon")
        assertThat(snippet.state.value).isEqualTo(SnippetState.Ended(SnippetEnd.BECAME_NOISY))
    }

    @Test
    fun `a refused focus request plays nothing and resumes a main player that played`() {
        granted = false

        snippet.play("song", 60_000)

        assertThat(log).containsExactly("main.pause", "focus.request", "main.play")
        assertThat(snippet.state.value).isEqualTo(SnippetState.Ended(SnippetEnd.FOCUS_REFUSED))
        assertThat(snippet.positionMs).isZero()

        log.clear()
        main.isPlaying = false
        snippet.play("song", 60_000)
        assertThat(log).containsExactly("focus.request")
    }

    @Test
    fun `a second snippet replaces the first and keeps its memory of the main player`() {
        snippet.play("first", 60_000)
        playing()

        snippet.play("second", 90_000)

        assertThat(log).containsExactly("output.stop", "output.start second 30000..45000")
        assertThat(snippet.state.value).isEqualTo(SnippetState.Playing(30_000, 45_000))

        log.clear()
        outputEvents.last().ended()
        assertThat(log).containsExactly("output.stop", "focus.abandon", "main.play")
    }

    @Test
    fun `a duckable loss lowers the snippet and a gain restores it`() {
        snippet.play("song", 60_000)
        playing()

        focusListeners.single()(FocusChange.DUCK)
        focusListeners.single()(FocusChange.GAIN)

        assertThat(log).containsExactly("output.volume ${SnippetController.DUCK_VOLUME}", "output.volume 1.0")
        assertThat(snippet.state.value).isEqualTo(SnippetState.Playing(20_000, 35_000))
    }

    @Test
    fun `reports of an ended snippet change nothing`() {
        snippet.play("first", 60_000)
        val firstEvents = outputEvents.single()
        snippet.play("second", 60_000)
        playing()

        // The first player's last words, after the second took over.
        firstEvents.ended()
        firstEvents.failed()
        assertThat(log).isEmpty()
        assertThat(snippet.state.value).isEqualTo(SnippetState.Playing(20_000, 35_000))

        outputEvents.last().ended()
        log.clear()
        outputEvents.last().ended()
        outputEvents.last().becameNoisy()
        focusListeners.single()(FocusChange.LOSS)
        focusListeners.single()(FocusChange.DUCK)
        snippet.stop()
        assertThat(log).isEmpty()
        assertThat(snippet.state.value).isEqualTo(SnippetState.Ended(SnippetEnd.FINISHED))
    }

    @Test
    fun `stop without a snippet does nothing`() {
        snippet.stop()

        assertThat(log).isEmpty()
        assertThat(snippet.state.value).isEqualTo(SnippetState.Idle)
    }
}
