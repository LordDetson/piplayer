package io.github.lorddetson.piplayer.ui.songs

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AudioPermissionStateTest {

    @Test
    fun `access is denied for good only once the user denied it before and the system stopped explaining`() {
        assertThat(AudioPermissionState.deniedForGood(granted = false, rationale = false, deniedBefore = true)).isTrue()
    }

    @Test
    fun `a dialog closed without an answer is not a denial`() {
        assertThat(AudioPermissionState.deniedForGood(granted = false, rationale = false, deniedBefore = false)).isFalse()
    }

    @Test
    fun `after one denial the system still asks`() {
        assertThat(AudioPermissionState.deniedForGood(granted = false, rationale = true, deniedBefore = true)).isFalse()
    }

    @Test
    fun `granted access is never denied`() {
        assertThat(AudioPermissionState.deniedForGood(granted = true, rationale = false, deniedBefore = true)).isFalse()
    }
}
