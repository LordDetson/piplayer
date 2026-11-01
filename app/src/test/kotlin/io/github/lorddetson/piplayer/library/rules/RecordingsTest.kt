package io.github.lorddetson.piplayer.library.rules

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RecordingsTest {

    @Test
    fun `the index's own flag decides where it has one`() {
        assertThat(Recordings.isRecording(true, "Music/")).isTrue()
        assertThat(Recordings.isRecording(false, "Recordings/")).isFalse()
    }

    @Test
    fun `without a flag, a Recordings folder at any level decides, ignoring case`() {
        assertThat(Recordings.isRecording(null, "Recordings/")).isTrue()
        assertThat(Recordings.isRecording(null, "Music/Call recordings/")).isFalse()
        assertThat(Recordings.isRecording(null, "Documents/RECORDINGS/2026/")).isTrue()
        assertThat(Recordings.isRecording(null, "Music/")).isFalse()
        assertThat(Recordings.isRecording(null, "")).isFalse()
    }
}
