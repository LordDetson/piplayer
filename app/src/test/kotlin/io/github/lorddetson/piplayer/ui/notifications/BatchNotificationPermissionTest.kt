package io.github.lorddetson.piplayer.ui.notifications

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** When a batch asks for the notification permission (TI §8, PIM-21), as a table of API levels and answers. */
class BatchNotificationPermissionTest {

    @Test
    fun `the permission is asked for from Android 13 on`() {
        assertThat(BatchNotificationPermission.asks(30)).isFalse()
        assertThat(BatchNotificationPermission.asks(32)).isFalse()
        assertThat(BatchNotificationPermission.asks(33)).isTrue()
        assertThat(BatchNotificationPermission.asks(36)).isTrue()
    }

    @Test
    fun `the explanation comes first once, and only while the permission is missing`() {
        assertThat(BatchNotificationPermission.explains(sdk = 36, granted = false, asked = false)).isTrue()
        // Asked once: a no is an answer, and the batch runs without the notification from then on.
        assertThat(BatchNotificationPermission.explains(sdk = 36, granted = false, asked = true)).isFalse()
        assertThat(BatchNotificationPermission.explains(sdk = 36, granted = true, asked = false)).isFalse()
        // Before Android 13 there is nothing to ask or explain.
        assertThat(BatchNotificationPermission.explains(sdk = 32, granted = false, asked = false)).isFalse()
    }
}
