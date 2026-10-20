package io.github.lorddetson.piplayer.playback

import android.content.ComponentName
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lorddetson.piplayer.TestFiles
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Helpers for driving the playback service from a test thread. Media3 controllers and players built here belong to the
 * main looper, so every call to them goes through [onMain].
 */
object PlaybackTesting {

    /** Long enough that nothing ends while a test watches it (docs/playback.md §1.7). */
    const val LONG_FIXTURE = "playback/seek-mp3-cbr-no-header.mp3"

    /** A 2 s tone. */
    const val SHORT_FIXTURE = "decoding/mp3-44100-stereo.mp3"

    /** Runs [block] on the main thread and returns what it returns, or rethrows what it throws. */
    fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    /** Polls [condition] on the calling thread until it holds; fails naming [what] after [timeout]. */
    fun eventually(what: String, timeout: Duration = 10.seconds, condition: () -> Boolean) {
        val deadline = TimeSource.Monotonic.markNow() + timeout
        while (!condition()) {
            if (deadline.hasPassedNow()) throw AssertionError("Timed out after $timeout waiting until $what")
            Thread.sleep(20)
        }
    }

    /** Polls [condition] on the calling thread for [duration]; fails naming [what] as soon as it does not hold. */
    fun throughout(what: String, duration: Duration, condition: () -> Boolean) {
        val end = TimeSource.Monotonic.markNow() + duration
        while (!end.hasPassedNow()) {
            if (!condition()) throw AssertionError("Stopped holding: $what")
            Thread.sleep(20)
        }
    }

    /** Like [eventually], with [condition] evaluated on the main thread. */
    fun <P> P.waitUntil(what: String, timeout: Duration = 10.seconds, condition: P.() -> Boolean) =
        eventually(what, timeout) { onMain { condition() } }

    private fun token(): SessionToken {
        val context = TestFiles.targetContext
        return SessionToken(context, ComponentName(context, PlaybackService::class.java))
    }

    /** A controller connected to the playback service the way the app connects. */
    fun connectController(): MediaController {
        val future = onMain { MediaController.Builder(TestFiles.targetContext, token()).buildAsync() }
        return future.get(10, TimeUnit.SECONDS)
    }

    /** A browser connected to the playback service's library. */
    fun connectBrowser(): MediaBrowser {
        val future = onMain { MediaBrowser.Builder(TestFiles.targetContext, token()).buildAsync() }
        return future.get(10, TimeUnit.SECONDS)
    }

    /** Stops playback, empties the queue and disconnects, so that the service can stop. */
    fun MediaController.stopAndRelease() = onMain {
        stop()
        clearMediaItems()
        release()
    }

    /** Whether `dumpsys power` lists ExoPlayer's wake lock for this app as held and not disabled. */
    fun holdsWakeLock(): Boolean {
        val output = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("dumpsys power")
        val text = ParcelFileDescriptor.AutoCloseInputStream(output).bufferedReader().use { it.readText() }
        val locks = text.substringAfter("Wake Locks: size=", "").substringBefore("\n\n")
        return locks.lineSequence().any { line ->
            "'ExoPlayer:WakeLockManager'" in line && "uid=${Process.myUid()}" in line && "DISABLED" !in line
        }
    }

    /** A test asset copied into the app's files, as a media item. */
    fun fileItem(assetPath: String): MediaItem = MediaItem.fromUri(Uri.fromFile(TestFiles.copy(assetPath)))

    /** Plays [assetPath] from the start and waits until it is playing. */
    fun MediaController.play(assetPath: String) {
        val item = fileItem(assetPath)
        onMain {
            setMediaItem(item)
            prepare()
            play()
        }
        waitUntil("$assetPath plays") { isPlaying }
    }
}
