package io.github.lorddetson.piplayer.covers

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Who may open covers (docs/library.md §13.8.3). */
class CoverAccessTest {

    private val own = 10_123
    private val musicApp = 10_200
    private val trustedApp = 10_300
    private val other = 10_400

    private fun allows(caller: CoverAccess.Caller) = CoverAccess.allows(
        caller,
        own,
        holdsAudio = { it == musicApp },
        trusted = { it.uid == trustedApp && it.packageName == "com.example.car" },
    )

    @Test
    fun piPlayerItselfMay() {
        assertThat(allows(CoverAccess.Caller(own, 1, "io.github.lorddetson.piplayer"))).isTrue()
        assertThat(allows(CoverAccess.Caller(own, 1, null))).isTrue()
    }

    @Test
    fun anAppWhoseUidMayReadMusicMay() {
        assertThat(allows(CoverAccess.Caller(musicApp, 2, "com.example.music"))).isTrue()
        assertThat(allows(CoverAccess.Caller(musicApp, 2, null))).isTrue()
    }

    @Test
    fun aCallerAndroidTrustsMay() {
        assertThat(allows(CoverAccess.Caller(trustedApp, 3, "com.example.car"))).isTrue()
    }

    @Test
    fun anyoneElseMayNotEvenWithAClaimedName() {
        assertThat(allows(CoverAccess.Caller(other, 4, "com.example.remote"))).isFalse()
        assertThat(allows(CoverAccess.Caller(other, 4, null))).isFalse()
        assertThat(allows(CoverAccess.Caller(other, 4, "io.github.lorddetson.piplayer"))).isFalse()
        assertThat(allows(CoverAccess.Caller(other, 4, "com.example.car"))).isFalse()
    }
}
