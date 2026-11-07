package io.github.lorddetson.piplayer.ui.onboarding

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** When the first launch shows onboarding (docs/library.md §14.1). */
class OnboardingTest {

    @Test
    fun itShowsWithoutThePermissionBeforeItIsDone() {
        assertThat(Onboarding.shows(granted = false, done = false)).isTrue()
    }

    @Test
    fun itNeverShowsWithThePermission() {
        assertThat(Onboarding.shows(granted = true, done = false)).isFalse()
        assertThat(Onboarding.shows(granted = true, done = true)).isFalse()
    }

    @Test
    fun onceDoneItStaysAwayEvenWithoutThePermission() {
        assertThat(Onboarding.shows(granted = false, done = true)).isFalse()
    }
}
