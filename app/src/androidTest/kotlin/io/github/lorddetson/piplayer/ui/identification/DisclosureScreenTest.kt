package io.github.lorddetson.piplayer.ui.identification

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.ui.theme.PiPlayerTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The disclosure before the first online lookup (TI §9): every service with what it receives, the background cover
 * loads stated plainly, Enable and Not now, the links, and their labels and actions for TalkBack.
 */
@RunWith(AndroidJUnit4::class)
class DisclosureScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = TestFiles.targetContext
    private fun text(id: Int) = context.getString(id)

    private val answers = mutableListOf<String>()
    private val opened = mutableListOf<String>()

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
                DisclosureScreen(onEnable = { answers += "enable" }, onNotNow = { answers += "not now" })
            }
        }
    }

    @Test
    fun theDisclosureNamesEveryServiceAndWhatItReceivesUnderAHeading() {
        show()

        compose.onNode(hasText(text(R.string.identification_disclosure_title)) and isHeading()).assertExists()
        for (id in listOf(
            R.string.identification_disclosure_intro,
            R.string.identification_sends_acoustid,
            R.string.identification_sends_musicbrainz,
            R.string.identification_sends_caa,
            R.string.identification_sends_all,
            R.string.identification_disclosure_local,
        )) {
            compose.onNodeWithText(text(id)).performScrollTo().assertExists()
        }
    }

    @Test
    fun theBackgroundCoverLoadsAreStatedPlainly() {
        // The words are the point here: a reader must learn that covers load without a tap, from lists.
        assertThat(text(R.string.identification_sends_caa))
            .contains("Cover Art Archive")
            .contains("archive.org")
            .containsIgnoringCase("background")
            .containsIgnoringCase("lists")
        assertThat(text(R.string.identification_sends_acoustid)).contains("AcoustID").containsIgnoringCase("fingerprint")
        assertThat(text(R.string.identification_sends_musicbrainz)).contains("MusicBrainz").containsIgnoringCase("tags")
            .containsIgnoringCase("file names")
        assertThat(text(R.string.identification_sends_all)).containsIgnoringCase("IP address").containsIgnoringCase("user agent")

        val russian = context.createConfigurationContext(
            android.content.res.Configuration(context.resources.configuration).apply { setLocale(java.util.Locale.forLanguageTag("ru")) },
        )
        assertThat(russian.getString(R.string.identification_sends_caa))
            .contains("Cover Art Archive")
            .contains("archive.org")
            .containsIgnoringCase("фон")
            .containsIgnoringCase("списк")
    }

    @Test
    fun enableAndNotNowAreLabelledButtonsThatAnswer() {
        show()

        compose.onNode(hasText(text(R.string.identification_enable)) and isButton).assertIsDisplayed().assertHasClickAction()
        compose.onNode(hasText(text(R.string.identification_not_now)) and isButton).assertIsDisplayed().assertHasClickAction()

        compose.onNode(hasText(text(R.string.identification_not_now)) and isButton).performClick()
        compose.onNode(hasText(text(R.string.identification_enable)) and isButton).performClick()

        assertThat(answers).containsExactly("not now", "enable")
    }

    @Test
    fun theLinksAreLabelledButtonsThatOpenThePolicyTheServiceListAndEachServicesTerms() {
        show()

        val links = mapOf(
            R.string.identification_privacy_policy to PrivacyLinks.PRIVACY_POLICY,
            R.string.identification_third_party_services to PrivacyLinks.THIRD_PARTY_SERVICES,
            R.string.identification_terms_acoustid to PrivacyLinks.ACOUSTID_TERMS,
            R.string.identification_terms_musicbrainz to PrivacyLinks.MUSICBRAINZ_TERMS,
            R.string.identification_terms_caa to PrivacyLinks.CAA_TERMS,
        )
        for ((label, url) in links) {
            compose.onNode(hasText(text(label)) and isButton).performScrollTo().assertHasClickAction().performClick()
            assertThat(opened.last()).isEqualTo(url)
        }
        assertThat(opened).hasSize(links.size)
        assertThat(PrivacyLinks.PRIVACY_POLICY).endsWith("/docs/PRIVACY.md").startsWith("https://")
        assertThat(PrivacyLinks.THIRD_PARTY_SERVICES).endsWith("/docs/THIRD_PARTY_SERVICES.md").startsWith("https://")
        assertThat(answers).isEmpty()
    }
}
