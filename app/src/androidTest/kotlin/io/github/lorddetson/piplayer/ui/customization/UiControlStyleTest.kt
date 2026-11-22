package io.github.lorddetson.piplayer.ui.customization

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.settings.UI_ICON_MAX_PAYLOAD
import io.github.lorddetson.piplayer.settings.UiControlMotion
import io.github.lorddetson.piplayer.settings.UiIconFormat
import java.io.File
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiControlStyleTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun motionDoesNotSwallowClicks() {
        val selected = mutableStateOf(UiControlMotion.NONE)
        var clicks = 0
        compose.setContent {
            val motion by selected
            Button(onClick = { clicks++ }, modifier = Modifier.uiControlMotion(motion).testTag("animated_control")) {
                Text("Control")
            }
        }
        for (motion in UiControlMotion.entries) {
            compose.runOnIdle { selected.value = motion }
            compose.onNodeWithTag("animated_control").performTouchInput {
                down(center)
                advanceEventTime(150)
                up()
            }
        }
        compose.runOnIdle { assertThat(clicks).isEqualTo(UiControlMotion.entries.size) }
    }

    @Test
    fun pngAndWebpImportByContentAndRetainTheirFormat() = runBlocking {
        val bitmap = Bitmap.createBitmap(256, 128, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xff235faf.toInt())
        try {
            for ((compression, expected) in listOf(
                Bitmap.CompressFormat.PNG to UiIconFormat.PNG,
                Bitmap.CompressFormat.WEBP_LOSSLESS to UiIconFormat.WEBP,
            )) {
                withIconFile { file ->
                    file.outputStream().use { assertThat(bitmap.compress(compression, 100, it)).isTrue() }
                    val actual = checkNotNull(readUiIcon(TestFiles.targetContext, Uri.fromFile(file)))
                    assertThat(actual.format).isEqualTo(expected)
                    assertThat(actual.payload.length).isLessThanOrEqualTo(UI_ICON_MAX_PAYLOAD)
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun standaloneSvgImportsAndNonImageTextIsRejected() = runBlocking {
        withIconFile { file ->
            file.writeText("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path d="M2 4h20L12 20Z"/></svg>""")
            assertThat(readUiIcon(TestFiles.targetContext, Uri.fromFile(file))?.format).isEqualTo(UiIconFormat.SVG)
            file.writeText("<html>Not an image</html>")
            assertThat(readUiIcon(TestFiles.targetContext, Uri.fromFile(file))).isNull()
        }
    }

    private suspend fun withIconFile(check: suspend (File) -> Unit) {
        val file = File.createTempFile("ui-icon-", ".image", TestFiles.targetContext.cacheDir)
        try {
            check(file)
        } finally {
            file.delete()
        }
    }
}
