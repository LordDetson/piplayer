package io.github.lorddetson.piplayer.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.values.LegacyEncoding
import io.github.lorddetson.piplayer.library.values.LegacyEncodings
import io.github.lorddetson.piplayer.library.values.LegacyScript
import io.github.lorddetson.piplayer.ui.theme.PiPlayerTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The legacy tag encoding in the settings: its row, its dialog, their labels and actions (docs/library.md §5.7). */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class LegacyEncodingChoiceTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = TestFiles.targetContext
    private fun text(id: Int) = context.getString(id)

    private val chosen = mutableListOf<LegacyEncoding?>()
    private var selected by mutableStateOf<LegacyEncoding?>(null)

    private fun encoding(name: String) = LegacyEncodings.ALL.single { it.name == name }

    private fun label(encoding: LegacyEncoding): String {
        val id = when (encoding.script) {
            LegacyScript.CYRILLIC -> R.string.legacy_script_cyrillic
            LegacyScript.CENTRAL_EUROPEAN -> R.string.legacy_script_central_european
            LegacyScript.WESTERN -> R.string.legacy_script_western
            LegacyScript.GREEK -> R.string.legacy_script_greek
            LegacyScript.TURKISH -> R.string.legacy_script_turkish
            LegacyScript.HEBREW -> R.string.legacy_script_hebrew
            LegacyScript.ARABIC -> R.string.legacy_script_arabic
            LegacyScript.BALTIC -> R.string.legacy_script_baltic
            LegacyScript.VIETNAMESE -> R.string.legacy_script_vietnamese
            LegacyScript.THAI -> R.string.legacy_script_thai
            LegacyScript.JAPANESE -> R.string.legacy_script_japanese
            LegacyScript.SIMPLIFIED_CHINESE -> R.string.legacy_script_simplified_chinese
            LegacyScript.TRADITIONAL_CHINESE -> R.string.legacy_script_traditional_chinese
            LegacyScript.KOREAN -> R.string.legacy_script_korean
        }
        return context.getString(id, encoding.label)
    }

    private fun show(available: List<LegacyEncoding> = LegacyEncodings.ALL) = compose.setContent {
        PiPlayerTheme {
            LegacyEncodingChoice(selected, available) {
                chosen += it
                selected = it
            }
        }
    }

    private val isRadioButton = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

    private fun openDialog() {
        compose.onNodeWithText(text(R.string.settings_legacy_encoding)).performClick()
        compose.waitUntilExactlyOneExists(hasText(text(R.string.settings_legacy_encoding_automatic_summary)), timeoutMillis = 5_000)
    }

    @Test
    fun theRowShowsAutomaticByDefaultAndOpensTheDialog() {
        show()

        compose.onNodeWithText(text(R.string.settings_legacy_encoding)).assertHasClickAction()
        compose.onNodeWithText(text(R.string.settings_legacy_encoding_automatic)).assertExists()

        openDialog()

        compose.onNodeWithText(text(R.string.settings_legacy_encoding_explained)).assertExists()
        compose.onNode(hasText(text(R.string.settings_legacy_encoding_automatic_summary)), useUnmergedTree = true).assertExists()
    }

    @Test
    fun theDialogListsAutomaticAndEveryCodePageAsRadioButtons() {
        show()
        openDialog()

        compose.onNode(hasText(text(R.string.settings_legacy_encoding_automatic)) and isRadioButton).assertIsSelected()
        for (encoding in LegacyEncodings.ALL) {
            compose.onNode(hasText(label(encoding)) and isRadioButton).performScrollTo().assertIsNotSelected().assertHasClickAction()
        }
    }

    @Test
    fun onlyTheCodePagesTheDeviceSupportsAreListed() {
        show(available = LegacyEncodings.ALL.filter { it.name != "tis-620" })
        openDialog()

        compose.onNodeWithText(label(encoding("tis-620"))).assertDoesNotExist()
        compose.onNodeWithText(label(encoding("euc-kr"))).performScrollTo().assertExists()
    }

    @Test
    fun aTappedCodePageIsChosenAndShownAndAutomaticUndoesIt() {
        show()
        openDialog()

        compose.onNode(hasText(label(encoding("koi8-r"))) and isRadioButton).performScrollTo().performClick()

        assertThat(chosen).containsExactly(encoding("koi8-r"))
        compose.onNodeWithText(text(R.string.settings_legacy_encoding_explained)).assertDoesNotExist()
        compose.onNodeWithText(label(encoding("koi8-r"))).assertExists()

        openDialog()
        compose.onNode(hasText(label(encoding("koi8-r"))) and isRadioButton).performScrollTo().assertIsSelected()
        compose.onNode(hasText(text(R.string.settings_legacy_encoding_automatic)) and isRadioButton).performScrollTo().performClick()

        assertThat(chosen).containsExactly(encoding("koi8-r"), null)
        compose.onNodeWithText(text(R.string.settings_legacy_encoding_automatic)).assertExists()
        compose.onNodeWithText(label(encoding("koi8-r"))).assertDoesNotExist()
    }

    @Test
    fun cancelChangesNothing() {
        show()
        openDialog()

        compose.onNodeWithText(text(R.string.settings_dialog_cancel)).performClick()

        assertThat(chosen).isEmpty()
        compose.onNodeWithText(text(R.string.settings_legacy_encoding_explained)).assertDoesNotExist()
    }
}
