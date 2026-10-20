package io.github.lorddetson.piplayer.ui

import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.songs.AudioPermission
import org.assertj.core.api.Assertions.assertThat
import org.junit.rules.ExternalResource

/** Grants the audio permission before the activity starts; a grant cannot be undone within a test run. */
object GrantAudioPermission : ExternalResource() {
    override fun before() {
        val context = TestFiles.targetContext
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, AudioPermission.name)
        assertThat(context.checkSelfPermission(AudioPermission.name)).isEqualTo(PackageManager.PERMISSION_GRANTED)
    }
}
