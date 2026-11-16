package io.github.lorddetson.piplayer.ui.details

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.performClick
import io.github.lorddetson.piplayer.ui.identification.IdentifyTarget
import io.github.lorddetson.piplayer.ui.identification.LocalIdentify
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.details.DetailField
import io.github.lorddetson.piplayer.ui.player.PlayerTags
import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The details screen of a file opened from another app (docs/library.md §18.1, §18.5). */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class OpenedFileDetailsScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val copies = mutableListOf<File>()

    @After
    fun delete() {
        copies.forEach(File::delete)
    }

    private fun show(file: File) {
        compose.setContent { TrackDetailsScreen(fileId = null, uri = Uri.fromFile(file).toString(), SnackbarHostState(), onBack = {}) }
        compose.waitUntilAtLeastOneExists(hasTestTag(DetailsTags.LIST), timeoutMillis = 30_000)
    }

    @Test
    fun theHeaderShowsThePictureEmbeddedInTheFile() {
        show(TestFiles.copy("playback/cover-front.mp3").also { copies += it })

        compose.waitUntilAtLeastOneExists(hasTestTag(PlayerTags.COVER) and hasAnyAncestor(hasTestTag(DetailsTags.HEADER)), timeoutMillis = 10_000)
    }

    @Test
    fun theTopBarsIdentifyActionOpensTheReviewOfTheFile() {
        val gone = File(TestFiles.targetContext.cacheDir, "gone.mp3").apply { delete() }
        val uri = Uri.fromFile(gone).toString()
        val opened = mutableListOf<IdentifyTarget>()
        compose.setContent {
            CompositionLocalProvider(LocalIdentify provides { target: IdentifyTarget -> opened += target }) {
                TrackDetailsScreen(fileId = null, uri = uri, SnackbarHostState(), onBack = {})
            }
        }

        compose.onNode(hasContentDescription(compose.activity.getString(R.string.identify))).performClick()

        assertThat(opened).containsExactly(IdentifyTarget.Opened(uri))
    }

    @Test
    fun aFileThatCanNoLongerBeOpenedSaysThatItCouldNotBeRead() {
        val gone = File(TestFiles.targetContext.cacheDir, "gone.mp3").apply { delete() }

        show(gone)

        val inDetails = hasAnyAncestor(hasTestTag(DetailsTags.LIST))
        compose.onNode(hasTestTag(DetailsTags.LIST)).performScrollToNode(hasTestTag(DetailsTags.row(DetailField.TAGS)))
        val couldNotBeRead = compose.activity.getString(R.string.details_tags_cannot_read)
        compose.waitUntilAtLeastOneExists(hasTestTag(DetailsTags.row(DetailField.TAGS)) and inDetails and hasText(couldNotBeRead), timeoutMillis = 10_000)
        assertThat(compose.onAllNodes(hasText(compose.activity.getString(R.string.details_not_found))).fetchSemanticsNodes()).isEmpty()
    }
}
