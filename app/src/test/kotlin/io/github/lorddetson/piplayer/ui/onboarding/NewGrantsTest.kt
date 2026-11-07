package io.github.lorddetson.piplayer.ui.onboarding

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Only a grant the app had not seen starts the library (docs/library.md §14.1). */
class NewGrantsTest {

    @Test
    fun aPermissionHeldFromTheStartIsNoNewGrant() {
        val grants = NewGrants(grantedAtStart = true)

        assertThat(List(3) { grants.newlyGranted(true) }).containsOnly(false)
    }

    @Test
    fun aGrantAfterTheStartCountsOnceHoweverOftenItIsSeen() {
        val grants = NewGrants(grantedAtStart = false)

        assertThat(grants.newlyGranted(false)).isFalse()
        assertThat(grants.newlyGranted(true)).isTrue()
        assertThat(grants.newlyGranted(true)).isFalse()
        assertThat(grants.newlyGranted(true)).isFalse()
    }

    @Test
    fun aRevokeAndAGrantAgainCountsAgain() {
        val grants = NewGrants(grantedAtStart = true)

        assertThat(grants.newlyGranted(false)).isFalse()
        assertThat(grants.newlyGranted(true)).isTrue()
    }
}
