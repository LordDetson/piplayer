package io.github.lorddetson.piplayer.ui

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** The place a batch notification asks the app to open (TI §8, PIM-21): asked for once and taken once. */
class PlacesTest {

    @AfterEach
    fun forget() {
        Places.take(Places.FIX)
    }

    @Test
    fun `a place asked for is the one the app opens, once`() {
        Places.request(Places.FIX)

        assertThat(Places.requested.value).isEqualTo(Places.FIX)
        assertThat(Places.take(Places.FIX)).isTrue()
        assertThat(Places.requested.value).isNull()
        assertThat(Places.take(Places.FIX)).isFalse()
    }

    @Test
    fun `a place asked for twice stays one request`() {
        Places.request(Places.FIX)
        Places.request(Places.FIX)

        assertThat(Places.take(Places.FIX)).isTrue()
        assertThat(Places.requested.value).isNull()
    }
}
