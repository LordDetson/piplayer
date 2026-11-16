package io.github.lorddetson.piplayer.ui.identification

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.MainActivity
import io.github.lorddetson.piplayer.PiPlayerApplication
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.FakeMediaIndex
import io.github.lorddetson.piplayer.library.FakeMediaIndex.Companion.row
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.LibraryRule
import io.github.lorddetson.piplayer.net.OnlineConsent
import io.github.lorddetson.piplayer.settings.IdentificationSettings
import io.github.lorddetson.piplayer.settings.SettingsFiles
import io.github.lorddetson.piplayer.ui.GrantAudioPermission
import io.github.lorddetson.piplayer.ui.songs.SongsTags
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * "Identify" in the whole app (TI §1, §6 review screen A): the app's navigation hands it to every entry, so the song
 * menu of the list opens the review's route. Online lookups are off for the test (no answer stored, as on a new
 * install), so the review shows the disclosure and nothing is read or sent.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class IdentifyInTheAppTest {

    private val app = TestFiles.targetContext.applicationContext as PiPlayerApplication
    private val index = FakeMediaIndex().apply { volume().rows = listOf(row(1, "Alpha")) }

    /** No answer to the disclosure while the test runs; the answer stored before comes back afterwards. */
    private val noConsent = object : ExternalResource() {
        private var kept: Preferences? = null

        override fun before() = runBlocking {
            val file = SettingsFiles.settings(app)
            kept = file.data.first()
            file.edit {
                it.remove(IdentificationSettings.ENABLED)
                it.remove(IdentificationSettings.VERSION)
            }
            withTimeout(5_000) { app.onlineGate.consent.first { it == OnlineConsent.NONE } }
            Library.sync(app).awaitIdle()
        }

        override fun after() = runBlocking<Unit> {
            val kept = kept ?: return@runBlocking
            SettingsFiles.settings(app).edit { preferences ->
                kept[IdentificationSettings.ENABLED]?.let { preferences[IdentificationSettings.ENABLED] = it }
                kept[IdentificationSettings.VERSION]?.let { preferences[IdentificationSettings.VERSION] = it }
            }
        }
    }

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(GrantAudioPermission).around(LibraryRule { index }).around(noConsent).around(compose)

    private fun text(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun tap(matcher: SemanticsMatcher) {
        compose.waitUntilAtLeastOneExists(matcher, timeoutMillis = 10_000)
        compose.onAllNodes(matcher)[0].performClick()
    }

    @Test
    fun identifyInTheSongMenuOfTheListOpensTheReview() {
        tap(hasContentDescription(text(R.string.song_menu, "Alpha")) and hasAnyAncestor(hasTestTag(SongsTags.LIST)))
        tap(hasText(text(R.string.identify)))

        val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
        compose.waitUntilAtLeastOneExists(hasText(text(R.string.identify)) and heading, timeoutMillis = 10_000)
        compose.waitUntilAtLeastOneExists(hasText(text(R.string.identification_disclosure_title)), timeoutMillis = 10_000)
    }
}
