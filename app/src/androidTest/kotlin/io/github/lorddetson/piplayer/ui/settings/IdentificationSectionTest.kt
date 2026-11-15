package io.github.lorddetson.piplayer.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.identification.provider.StopReason
import io.github.lorddetson.piplayer.settings.ProtectedField
import io.github.lorddetson.piplayer.ui.identification.PrivacyLinks
import io.github.lorddetson.piplayer.ui.theme.PiPlayerTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Settings, "Identification and privacy" (TI §9): the online-lookup switch and the disclosure it opens, the services
 * and their terms, the protected-fields list and the providers' status, with labels and actions for TalkBack.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class IdentificationSectionTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = TestFiles.targetContext
    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    private var state by mutableStateOf(IdentificationPrivacyState())
    private val calls = mutableListOf<String>()
    private val opened = mutableListOf<String>()

    private val isSwitch = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
    private val isCheckbox = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)
    private val isButton = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    private fun show() = compose.setContent {
        CompositionLocalProvider(
            LocalUriHandler provides object : UriHandler {
                override fun openUri(uri: String) {
                    opened += uri
                }
            },
        ) {
            PiPlayerTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    IdentificationSection(
                        state = state,
                        onEnable = { calls += "enable" },
                        onNotNow = { calls += "not now" },
                        onTurnOff = { calls += "off" },
                        onProtect = { field, protected -> calls += "${field.stored}=$protected" },
                    )
                }
            }
        }
    }

    private fun switch() = compose.onNode(hasText(text(R.string.identification_online_lookups)) and isSwitch).performScrollTo()

    private val disclosureTitle get() = hasText(text(R.string.identification_disclosure_title)) and isHeading()

    @Test
    fun theSectionHasAHeadingAndTheSettingIsALabelledSwitchThatIsOffByDefault() {
        show()

        compose.onNode(hasText(text(R.string.identification_settings_section)) and isHeading()).performScrollTo().assertExists()
        switch().assertHasClickAction().assertIsOff()

        state = state.copy(lookupsOn = true, needsDisclosure = false)
        switch().assertIsOn()
    }

    @Test
    fun turningTheSettingOnWithTheDisclosureUnansweredShowsTheDisclosureFirst() {
        show()

        switch().performClick()

        compose.waitUntilExactlyOneExists(disclosureTitle, timeoutMillis = 5_000)
        assertThat(calls).isEmpty()
        compose.onNode(hasText(text(R.string.identification_enable)) and isButton).assertIsDisplayed().performClick()

        assertThat(calls).containsExactly("enable")
        compose.onNode(disclosureTitle).assertDoesNotExist()
    }

    @Test
    fun notNowInTheDisclosureLeavesTheSettingOff() {
        show()

        switch().performClick()
        compose.waitUntilExactlyOneExists(disclosureTitle, timeoutMillis = 5_000)
        compose.onNode(hasText(text(R.string.identification_not_now)) and isButton).assertIsDisplayed().performClick()

        assertThat(calls).containsExactly("not now")
        compose.onNode(disclosureTitle).assertDoesNotExist()
    }

    @Test
    fun turningTheSettingOnAfterTheCurrentDisclosureWasAnsweredNeedsNoDisclosure() {
        state = IdentificationPrivacyState(lookupsOn = false, needsDisclosure = false)
        show()

        switch().performClick()

        assertThat(calls).containsExactly("enable")
        compose.onNode(disclosureTitle).assertDoesNotExist()
    }

    @Test
    fun turningTheSettingOffNeedsNoConfirmation() {
        state = IdentificationPrivacyState(lookupsOn = true, needsDisclosure = false)
        show()

        switch().performClick()

        assertThat(calls).containsExactly("off")
        compose.onNode(disclosureTitle).assertDoesNotExist()
    }

    @Test
    fun theSectionListsWhatEachServiceReceivesWithLinksToTheirTerms() {
        show()

        for (id in listOf(
            R.string.identification_sends_acoustid,
            R.string.identification_sends_musicbrainz,
            R.string.identification_sends_caa,
            R.string.identification_sends_all,
        )) {
            compose.onNodeWithText(text(id)).performScrollTo().assertExists()
        }
        val links = mapOf(
            R.string.identification_terms_acoustid to PrivacyLinks.ACOUSTID_TERMS,
            R.string.identification_terms_musicbrainz to PrivacyLinks.MUSICBRAINZ_TERMS,
            R.string.identification_terms_caa to PrivacyLinks.CAA_TERMS,
            R.string.identification_privacy_policy to PrivacyLinks.PRIVACY_POLICY,
            R.string.identification_third_party_services to PrivacyLinks.THIRD_PARTY_SERVICES,
        )
        for ((label, url) in links) {
            compose.onNode(hasText(text(label)) and isButton).performScrollTo().assertHasClickAction().performClick()
            assertThat(opened.last()).isEqualTo(url)
        }
        assertThat(calls).isEmpty()
    }

    @Test
    fun theProtectedFieldsAreChangedWithLabelledCheckboxes() {
        show()

        compose.onNodeWithText(text(R.string.identification_protected_fields_none)).performScrollTo().assertExists()
        compose.onNodeWithText(text(R.string.identification_protected_fields)).performScrollTo().assertHasClickAction().performClick()
        compose.waitUntilExactlyOneExists(hasText(text(R.string.identification_protected_fields_explained)), timeoutMillis = 5_000)

        compose.onNode(hasText(text(R.string.identification_field_title)) and isCheckbox).performScrollTo()
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
            .performClick()
        assertThat(calls).containsExactly("title=true")

        state = state.copy(protectedFields = setOf(ProtectedField.TITLE, ProtectedField.COVER))
        compose.onNode(hasText(text(R.string.identification_field_title)) and isCheckbox).performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
            .performClick()
        assertThat(calls).containsExactly("title=true", "title=false")
        for (field in ProtectedField.entries) {
            compose.onNode(hasText(text(protectedFieldLabel(field))) and isCheckbox).performScrollTo().assertHasClickAction()
        }

        compose.onNode(hasText(text(R.string.identification_done)) and isButton).performClick()
        compose.onNodeWithText(text(R.string.identification_protected_fields_explained)).assertDoesNotExist()
        compose.onNodeWithText(
            listOf(R.string.identification_field_title, R.string.identification_field_cover).joinToString(", ") { text(it) },
        ).performScrollTo().assertExists()
    }

    @Test
    fun eachProviderShowsWhetherItIsReadyStoppedOrMissingFromTheBuild() {
        show()
        val acoustId = "AcoustID"
        val musicBrainz = "MusicBrainz"
        val caa = "Cover Art Archive"

        compose.onNode(hasText(text(R.string.identification_status_section)) and isHeading()).performScrollTo().assertExists()
        for (name in listOf(acoustId, musicBrainz, caa)) {
            compose.onNodeWithText(text(R.string.identification_status_ready, name)).performScrollTo().assertExists()
        }

        state = state.copy(
            acoustId = ProviderState.Stopped(StopReason.INVALID_API_KEY),
            musicBrainz = ProviderState.Stopped(StopReason.USER_AGENT_REJECTED),
        )
        compose.onNodeWithText(text(R.string.identification_status_stopped_key, acoustId)).performScrollTo().assertExists()
        compose.onNodeWithText(text(R.string.identification_status_stopped_user_agent, musicBrainz)).performScrollTo().assertExists()
        compose.onNodeWithText(text(R.string.identification_status_ready, caa)).performScrollTo().assertExists()

        state = state.copy(acoustId = ProviderState.NoKey)
        compose.onNodeWithText(text(R.string.identification_status_no_key, acoustId)).performScrollTo().assertExists()
    }
}
