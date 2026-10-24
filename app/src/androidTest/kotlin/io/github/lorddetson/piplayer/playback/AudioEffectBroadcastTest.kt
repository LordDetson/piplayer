package io.github.lorddetson.piplayer.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.audiofx.AudioEffect
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.session.MediaController
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.playback.PlaybackTesting.LONG_FIXTURE
import io.github.lorddetson.piplayer.playback.PlaybackTesting.connectController
import io.github.lorddetson.piplayer.playback.PlaybackTesting.eventually
import io.github.lorddetson.piplayer.playback.PlaybackTesting.onMain
import io.github.lorddetson.piplayer.playback.PlaybackTesting.play
import io.github.lorddetson.piplayer.playback.PlaybackTesting.stopAndRelease
import io.github.lorddetson.piplayer.playback.PlaybackTesting.waitUntil
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** PiPlayer's audio session for effect apps (docs/playback.md §6.6). */
@RunWith(AndroidJUnit4::class)
class AudioEffectBroadcastTest {

    private val context = TestFiles.targetContext

    /** (action, session ID, package, content type) of every announcement. */
    private val announcements = CopyOnWriteArrayList<List<Any?>>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            announcements += listOf(
                intent.action,
                intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, C.AUDIO_SESSION_ID_UNSET),
                intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME),
                intent.getIntExtra(AudioEffect.EXTRA_CONTENT_TYPE, -1),
            )
        }
    }

    @Before
    fun listen() {
        stopService()
        val filter = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    @After
    fun stopListening() {
        context.unregisterReceiver(receiver)
    }

    private fun stopService() {
        context.stopService(Intent(context, PlaybackService::class.java))
        eventually("the service is not running", timeout = 10.seconds) { !PlaybackService.isRunningForTests }
    }

    @Test
    fun theSessionIsAnnouncedWhenPlaybackStartsAndClosedWithTheService() {
        val controller: MediaController = connectController()
        val sessionId = try {
            controller.play(LONG_FIXTURE)
            controller.waitUntil("the player has its session") { audioSessionId != C.AUDIO_SESSION_ID_UNSET }
            val sessionId = onMain { controller.audioSessionId }
            eventually("the session is announced") {
                announcements.any { it == listOf(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION, sessionId, context.packageName, AudioEffect.CONTENT_TYPE_MUSIC) }
            }
            sessionId
        } finally {
            controller.stopAndRelease()
        }
        stopService()

        eventually("the session is closed") {
            announcements.any { it.take(3) == listOf(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION, sessionId, context.packageName) }
        }
        assertThat(announcements.map { it[1] }).doesNotContain(C.AUDIO_SESSION_ID_UNSET)
    }
}
