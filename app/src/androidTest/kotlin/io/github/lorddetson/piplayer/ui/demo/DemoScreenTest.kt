package io.github.lorddetson.piplayer.ui.demo

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.media3.session.MediaController
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.MainActivity
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.playback.PlaybackTesting
import io.github.lorddetson.piplayer.playback.PlaybackTesting.awaitState
import io.github.lorddetson.piplayer.playback.PlaybackTesting.expect
import io.github.lorddetson.piplayer.playback.PlaybackTesting.onMain
import io.github.lorddetson.piplayer.playback.PlaybackTesting.stopAndRelease
import io.github.lorddetson.piplayer.playback.PlaybackTesting.tap
import io.github.lorddetson.piplayer.playback.QueueRequest
import io.github.lorddetson.piplayer.playback.SongSourceRule
import io.github.lorddetson.piplayer.settings.AppearanceSettings
import io.github.lorddetson.piplayer.ui.GrantAudioPermission
import io.github.lorddetson.piplayer.ui.player.PlayerTags
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

internal val HasDemoPlaybackProgress = hasTestTag(PlayerTags.SEEK_BAR) and SemanticsMatcher("playback position advanced") {
    (it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)?.current ?: 0f) > 0f
}

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class DemoScreenTest {
    private val context = TestFiles.targetContext
    private val compose = createAndroidComposeRule<MainActivity>()
    private val songs = SongSourceRule {
        listOf(SongSourceRule.song(1, "Regular library track", PlaybackTesting.LONG_FIXTURE))
    }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(GrantAudioPermission).around(songs).around(compose)

    @Test
    fun samplePlaybackAndTagLessonLeaveSettingsAndLibraryAlone() {
        val controller = PlaybackTesting.connectController()
        try {
            val started = controller.expect("the regular track plays") {
                isPlaying && mediaMetadata.title?.toString() == "Regular library track"
            }
            controller.tap(QueueRequest(1, 0), title = "Regular library track")
            started.await()
            onMain { controller.pause() }
            controller.awaitState("the regular queue is paused") { !playWhenReady && mediaItemCount == 1 }
            val positioned = controller.expect("the paused queue moves to the chosen position") {
                !playWhenReady && currentPosition == 1_000L
            }
            onMain { controller.seekTo(1_000) }
            positioned.await()
            val originalQueue = queueOf(controller)
            exerciseDemo()
            assertThat(queueOf(controller)).isEqualTo(originalQueue)
        } finally {
            controller.stopAndRelease()
        }
    }

    private fun exerciseDemo() {
        val originalAppearance = AppearanceSettings(context).read()
        openDemo()
        click("demo-theme-warm_light")
        compose.onNodeWithTag("demo-theme-warm_light").assertIsSelected()
        click("demo-tag-preview")
        await("demo-tag-example")
        click("demo-tag-apply")
        await("demo-tag-applied")

        click("demo-play-morning-steps")
        await(PlayerTags.NOW_PLAYING)
        compose.waitUntilExactlyOneExists(hasText(text(R.string.demo_morning_steps)), timeoutMillis = 5_000)
        compose.waitUntilExactlyOneExists(HasDemoPlaybackProgress, timeoutMillis = 5_000)
        compose.onNodeWithContentDescription(text(R.string.player_close_now_playing)).performClick()
        await("demo-screen")

        click("demo-tag-undo")
        await("demo-tag-preview")
        click("demo-play-quiet-orbit")
        await(PlayerTags.NOW_PLAYING)
        compose.waitUntilExactlyOneExists(hasText(text(R.string.demo_quiet_orbit)), timeoutMillis = 5_000)
        compose.waitUntilExactlyOneExists(HasDemoPlaybackProgress, timeoutMillis = 5_000)
        compose.onNodeWithContentDescription(text(R.string.player_close_now_playing)).performClick()
        compose.onNodeWithTag("demo-exit").performClick()
        compose.waitUntilExactlyOneExists(hasText(text(R.string.settings_title)), timeoutMillis = 10_000)
        assertThat(AppearanceSettings(context).read()).isEqualTo(originalAppearance)

        compose.onNodeWithText(text(R.string.demo_start)).performScrollTo().performClick()
        await("demo-tag-preview")
        compose.onNodeWithTag("demo-exit").performClick()
        compose.waitUntilExactlyOneExists(hasText(text(R.string.settings_title)), timeoutMillis = 10_000)
    }

    private fun queueOf(controller: MediaController) = onMain {
        Triple(
            List(controller.mediaItemCount) { controller.getMediaItemAt(it).mediaId },
            controller.currentMediaItemIndex,
            controller.currentPosition,
        )
    }

    private fun openDemo() {
        compose.waitUntilExactlyOneExists(hasContentDescription(text(R.string.settings_open)), timeoutMillis = 10_000)
        compose.onNodeWithContentDescription(text(R.string.settings_open)).performClick()
        compose.onNodeWithText(text(R.string.demo_start)).performScrollTo().performClick()
        await("demo-screen")
    }

    private fun click(tag: String) {
        await(tag)
        compose.onNodeWithTag(tag).performScrollTo().performClick()
    }

    private fun await(tag: String) = compose.waitUntilExactlyOneExists(hasTestTag(tag), timeoutMillis = 10_000)

    private fun text(id: Int) = context.getString(id)
}
