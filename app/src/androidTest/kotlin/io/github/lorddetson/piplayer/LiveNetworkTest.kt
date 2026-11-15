package io.github.lorddetson.piplayer

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** Tests that call a real web service run only with `-e liveNetwork true`, never by default (TI §11.2). */
@RunWith(AndroidJUnit4::class)
class LiveNetworkTest {

    private fun arguments(value: String?) = Bundle().apply { if (value != null) putString(LiveNetwork.ARGUMENT, value) }

    @Test
    fun withoutTheArgumentNoTestCallsAService() {
        assertThat(LiveNetwork.allowed(Bundle())).isFalse()
    }

    @Test
    fun onlyTheExactValueTrueAllowsTheCalls() {
        assertThat(LiveNetwork.allowed(arguments("true"))).isTrue()
        for (value in listOf("false", "", "1", "yes", "TRUE", " true")) {
            assertThat(LiveNetwork.allowed(arguments(value))).describedAs("'$value'").isFalse()
        }
    }

    @Test
    fun anotherArgumentDoesNotAllowThem() {
        assertThat(LiveNetwork.allowed(Bundle().apply { putString("crashTests", "true") })).isFalse()
    }
}
