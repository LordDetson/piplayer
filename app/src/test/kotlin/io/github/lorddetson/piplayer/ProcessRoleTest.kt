package io.github.lorddetson.piplayer

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ProcessRoleTest {

    private val packageName = "io.github.lorddetson.piplayer"

    @Test
    fun `the process named after the package is the main one`() {
        assertThat(ProcessRole.of(packageName, packageName)).isEqualTo(ProcessRole.MAIN)
    }

    @Test
    fun `the tagger process runs native tag calls only`() {
        assertThat(ProcessRole.of("$packageName:tagger", packageName)).isEqualTo(ProcessRole.TAGGER)
    }

    @Test
    fun `any other process is neither`() {
        assertThat(ProcessRole.of("$packageName:other", packageName)).isEqualTo(ProcessRole.OTHER)
        assertThat(ProcessRole.of("$packageName.tagger", packageName)).isEqualTo(ProcessRole.OTHER)
        assertThat(ProcessRole.of("com.example.app", packageName)).isEqualTo(ProcessRole.OTHER)
    }
}
