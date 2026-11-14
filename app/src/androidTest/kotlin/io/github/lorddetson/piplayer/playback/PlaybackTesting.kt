package io.github.lorddetson.piplayer.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lorddetson.piplayer.TestFiles
import java.io.InterruptedIOException
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

    /**
     * How long a test that opts in stands still where a slow runner loses time: the instrumentation argument
     * `slowRunnerMs`, 0 by default. It reproduces on a fast device what an overloaded CI emulator does to a test.
     */
    val slowRunnerMs: Long = InstrumentationRegistry.getArguments().getString("slowRunnerMs")?.toLongOrNull() ?: 0

    /** Stands still for [slowRunnerMs], as the test thread of a slow runner does after a slow step. */
    fun slowRunnerPause() {
        if (slowRunnerMs > 0) Thread.sleep(slowRunnerMs)
    }

    /**
     * Media sources for a test's own player that open every file [slowRunnerMs] late, as a slow runner's player does;
     * the usual sources when no delay is asked for.
     */
    @androidx.annotation.OptIn(UnstableApi::class)
    fun slowRunnerSources(context: Context): MediaSource.Factory {
        val upstream = DefaultDataSource.Factory(context)
        if (slowRunnerMs <= 0) return DefaultMediaSourceFactory(upstream)
        return DefaultMediaSourceFactory(
            DataSource.Factory {
                val source = upstream.createDataSource()
                object : DataSource by source {
                    override fun open(dataSpec: DataSpec): Long {
                        try {
                            Thread.sleep(slowRunnerMs)
                        } catch (e: InterruptedException) {
                            // The player cancels a load by interrupting its thread.
                            Thread.currentThread().interrupt()
                            throw InterruptedIOException("Cancelled while standing still")
                        }
                        return source.open(dataSpec)
                    }
                }
            },
        )
    }

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

    /** Like [throughout], with [condition] evaluated on the main thread. */
    fun <P> P.holdsThroughout(what: String, duration: Duration, condition: P.() -> Boolean) =
        throughout(what, duration) { onMain { condition() } }

    /**
     * How long a wait for a reported event lasts before the test gives up. Only a last resort against a hang: the wait
     * ends as soon as the player reports the state or an error, so a slow runner passes and a broken player fails at once.
     */
    val SAFETY_TIMEOUT = 60.seconds

    /**
     * Starts listening for a state of this player in which [condition] holds, before the action that leads to it; the
     * returned [Expectation] gives what [capture] read at that moment. The state may be gone by the time the test thread
     * looks (a short song on a slow runner), and the wait has no deadline of its own but [SAFETY_TIMEOUT].
     */
    fun <P : Player, T> P.expect(what: String, capture: P.() -> T, condition: P.() -> Boolean): Expectation<P, T> =
        Expectation(this, what, capture, condition)

    /** Like the other [expect], when only reaching the state matters. */
    fun <P : Player> P.expect(what: String, condition: P.() -> Boolean): Expectation<P, Unit> =
        Expectation(this, what, {}, condition)

    /** Waits until this player reports a state in which [condition] holds, or fails at once on a playback error. */
    fun <P : Player> P.awaitState(what: String, condition: P.() -> Boolean) = expect(what, condition).await()

    private fun token(): SessionToken {
        val context = TestFiles.targetContext
        return SessionToken(context, ComponentName(context, PlaybackService::class.java))
    }

    /** A controller connected to the playback service the way the app connects. */
    fun connectController(listener: MediaController.Listener? = null): MediaController {
        val future = onMain {
            MediaController.Builder(TestFiles.targetContext, token())
                .apply { listener?.let(::setListener) }
                .buildAsync()
        }
        return future.get(10, TimeUnit.SECONDS)
    }

    /** The media item the song list sends for a tap (docs/playback.md §2.3). */
    fun compactItem(request: QueueRequest, title: String? = null): MediaItem = MediaItem.Builder()
        .setMediaId(request.mediaId)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
        .build()

    /** Taps a song of the list. */
    fun MediaController.tap(request: QueueRequest, title: String? = null) = onMain {
        setMediaItem(compactItem(request, title))
        prepare()
        play()
    }

    /** A browser connected to the playback service's library. */
    fun connectBrowser(): MediaBrowser {
        val future = onMain { MediaBrowser.Builder(TestFiles.targetContext, token()).buildAsync() }
        return future.get(10, TimeUnit.SECONDS)
    }

    /**
     * Stops playback, empties the queue, leaves repeat and shuffle off and disconnects: the service's player outlives the
     * test.
     */
    fun MediaController.stopAndRelease() = onMain {
        // A timer or "stop after current" left by a test would pause a later one (docs/playback.md §6).
        if (isSessionCommandAvailable(PlaybackSessionCallback.SLEEP_TIMER_COMMAND)) {
            sendCustomCommand(PlaybackSessionCallback.SLEEP_TIMER_COMMAND, timerArgs(durationMs = 0))
        }
        if (isSessionCommandAvailable(PlaybackSessionCallback.STOP_AFTER_CURRENT_COMMAND)) {
            sendCustomCommand(PlaybackSessionCallback.STOP_AFTER_CURRENT_COMMAND, stopAfterArgs(enabled = false))
        }
        volume = 1f
        repeatMode = Player.REPEAT_MODE_OFF
        shuffleModeEnabled = false
        pause()
        stop()
        clearMediaItems()
        release()
    }

    /** The arguments of a sleep timer command. */
    fun timerArgs(durationMs: Long, finishTrack: Boolean = false) = Bundle().apply {
        putLong(PlaybackSessionCallback.EXTRA_DURATION_MS, durationMs)
        putBoolean(PlaybackSessionCallback.EXTRA_FINISH_TRACK, finishTrack)
    }

    /** The arguments of a "stop after current track" command. */
    fun stopAfterArgs(enabled: Boolean) = Bundle().apply { putBoolean(PlaybackSessionCallback.EXTRA_ENABLED, enabled) }

    /** Runs a shell [command] as the shell user and returns its output. */
    fun shell(command: String): String {
        val output = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(output).bufferedReader().use { it.readText() }
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
