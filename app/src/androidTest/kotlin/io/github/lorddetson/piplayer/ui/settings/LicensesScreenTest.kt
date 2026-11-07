package io.github.lorddetson.piplayer.ui.settings

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.BuildConfig
import io.github.lorddetson.piplayer.MainActivity
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.settings.OpenSourceLicenses
import io.github.lorddetson.piplayer.ui.GrantAudioPermission
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** Settings > About and the open-source licences screen (docs/library.md §14.3). */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class LicensesScreenTest {

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(GrantAudioPermission).around(compose)

    private fun text(id: Int) = compose.activity.getString(id)

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntilAtLeastOneExists(matcher, timeoutMillis = 10_000)

    private fun openLicences() {
        val settings = hasContentDescription(text(R.string.settings_open))
        waitFor(settings)
        compose.onNode(settings).performClick()
        val row = hasText(text(R.string.settings_licenses))
        waitFor(row)
        compose.onNode(row).performScrollTo().performClick()
        waitFor(hasTestTag(LicensesTags.LIST))
    }

    @Test
    fun aboutShowsTheVersion() {
        val settings = hasContentDescription(text(R.string.settings_open))
        waitFor(settings)
        compose.onNode(settings).performClick()

        val version = hasText(compose.activity.getString(R.string.settings_version, BuildConfig.VERSION_NAME))
        waitFor(version)
        compose.onNode(version).performScrollTo()
    }

    @Test
    fun theLicencesListTheMavenModulesAndTheNativeCode() {
        openLicences()

        val media3 = hasText("androidx.media3:media3-exoplayer")
        compose.onNodeWithTag(LicensesTags.LIST).performScrollToNode(media3)
        val taglib = hasText("TagLib")
        compose.onNodeWithTag(LicensesTags.LIST).performScrollToNode(taglib)
        compose.onNodeWithTag(LicensesTags.LIST).performScrollToNode(hasText("Public Suffix List (in OkHttp)"))
    }

    @Test
    fun aRowOpensItsLicence() {
        openLicences()
        val media3 = hasText("androidx.media3:media3-exoplayer")
        compose.onNodeWithTag(LicensesTags.LIST).performScrollToNode(media3)

        compose.onNode(media3).performClick()

        waitFor(hasTestTag(LicensesTags.TEXT))
        // The row's own "Apache License 2.0" stays on screen while the page slides in: only the page's text counts.
        waitFor(hasText("TERMS AND CONDITIONS FOR USE", substring = true) and hasAnyAncestor(hasTestTag(LicensesTags.TEXT)))
    }

    @Test
    fun aNativeRowOpensItsOwnLicenceFiles() {
        openLicences()
        val taglib = hasText("TagLib")
        compose.onNodeWithTag(LicensesTags.LIST).performScrollToNode(taglib)

        compose.onNode(taglib).performClick()

        waitFor(hasText("GNU LESSER GENERAL PUBLIC LICENSE", substring = true) and hasAnyAncestor(hasTestTag(LicensesTags.TEXT)))
    }
}

/** The texts the list names are all in the APK (docs/library.md §14.4). */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class LicenseTextsTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun everyTextTheListNamesOpensFromTheAssets() {
        val assets = TestFiles.targetContext.assets
        val list = OpenSourceLicenses.parse(assets.open(OpenSourceLicenses.LIST).use { it.reader().readText() })
        val texts = list.kinds.map { it.text } + list.native.flatMap { it.texts }

        assertThat(list.libraries).isNotEmpty()
        assertThat(list.native).isNotEmpty()
        texts.forEach { path ->
            val size = assets.open(path).use { it.readBytes().size }
            assertThat(size).describedAs(path).isPositive()
        }
    }

    @Test
    fun aModuleWithANoticeShowsItAboveTheText() {
        compose.setContent {
            LicenseTextScreen(
                title = "checker-qual",
                notice = "Copyright 2004-present by the Checker Framework developers",
                texts = listOf("licenses/kinds/MIT.txt"),
                onBack = {},
            )
        }

        compose.waitUntilAtLeastOneExists(hasText("Copyright 2004-present by the Checker Framework developers"), 10_000)
        compose.waitUntilAtLeastOneExists(hasText("MIT License", substring = true), 10_000)
    }
}
