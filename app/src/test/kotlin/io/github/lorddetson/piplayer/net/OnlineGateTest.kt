package io.github.lorddetson.piplayer.net

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OnlineGateTest {

    @Test
    fun `a new gate is closed`() {
        assertThat(InMemoryOnlineGate().isOpen()).isFalse()
    }

    @Test
    fun `the gate opens only with the toggle on and the current disclosure accepted`() {
        val gate = InMemoryOnlineGate()
        val current = OnlineConsent.CURRENT_DISCLOSURE_VERSION

        gate.set(OnlineConsent(enabled = true, disclosureVersion = current))
        assertThat(gate.isOpen()).isTrue()

        gate.set(OnlineConsent(enabled = false, disclosureVersion = current))
        assertThat(gate.isOpen()).isFalse()

        gate.set(OnlineConsent(enabled = true, disclosureVersion = current - 1))
        assertThat(gate.isOpen()).isFalse()
    }

    @Test
    fun `a newer disclosure closes the gate until the user has read it`() {
        val gate = InMemoryOnlineGate(OnlineConsent(enabled = true, disclosureVersion = 1), requiredDisclosureVersion = 2)
        assertThat(gate.isOpen()).isFalse()
        gate.set(OnlineConsent(enabled = true, disclosureVersion = 2))
        assertThat(gate.isOpen()).isTrue()
    }
}
