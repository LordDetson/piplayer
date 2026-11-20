package io.github.lorddetson.piplayer.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lorddetson.piplayer.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RadioInstallSettingTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun radioExplainsTheModuleAndRequiresConfirmationBeforeRequestingIt() {
        var requests = 0
        var opens = 0
        lateinit var onUpdate: (RadioInstallState) -> Unit
        val installer = object : RadioInstaller {
            override val installed = false
            override fun start(update: (RadioInstallState) -> Unit) {
                requests++
                onUpdate = update
            }
            override fun cancel() = Unit
        }
        val controller = RadioInstallController(installer)
        compose.setContent {
            MaterialTheme {
                RadioInstallEntry(controller, onOpenRadio = { opens++ }, onStoreConfirmation = {})
            }
        }

        node(R.string.radio_install_entry).performClick()
        node(R.string.radio_install_explanation).assertExists()
        compose.runOnIdle { assertEquals(0, requests) }
        node(R.string.radio_install_later).performClick()
        node(R.string.radio_install_explanation).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, requests) }

        node(R.string.radio_install_entry).performClick()
        node(R.string.radio_install_confirm).performClick()
        node(R.string.radio_install_progress).assertExists()
        compose.runOnIdle {
            assertEquals(1, requests)
            onUpdate(RadioInstallState.OpenRadio)
        }
        compose.runOnIdle { assertEquals(1, opens) }
    }

    private fun node(resource: Int) = compose.onNodeWithText(context.getString(resource))
}
