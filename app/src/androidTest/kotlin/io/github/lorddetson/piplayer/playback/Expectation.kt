package io.github.lorddetson.piplayer.playback

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import io.github.lorddetson.piplayer.playback.PlaybackTesting.SAFETY_TIMEOUT
import io.github.lorddetson.piplayer.playback.PlaybackTesting.onMain
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A state of [player] that a test waits for, made with [PlaybackTesting.expect] before the action that leads to it. It
 * checks the condition each time the player reports a change to its listeners, on the main thread, so it catches a state
 * that lasts only a moment and needs no polling. A playback error ends the wait at once, and a timeout says what the
 * player reported meanwhile.
 */
class Expectation<P : Player, T> internal constructor(
    private val player: P,
    private val what: String,
    private val capture: P.() -> T,
    private val condition: P.() -> Boolean,
) {
    private val reached = CountDownLatch(1)

    @Volatile
    private var outcome: Result<T>? = null

    /** The latest states the player reported, oldest first; touched on the main thread only. */
    private val reports = ArrayDeque<String>()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = check()

        override fun onPlayerError(error: PlaybackException) = finish(
            Result.failure(
                AssertionError("A playback error came while waiting until $what: ${error.errorCodeName}; ${history()}", error),
            ),
        )
    }

    init {
        onMain {
            player.addListener(listener)
            check()
        }
    }

    /** What [capture] read when the state came; fails when an error came first or nothing came within [timeout]. */
    fun await(timeout: Duration = SAFETY_TIMEOUT): T {
        if (!reached.await(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
            val seen = onMain {
                player.removeListener(listener)
                history()
            }
            throw AssertionError("Timed out after $timeout waiting until $what; $seen")
        }
        return requireNotNull(outcome).getOrThrow()
    }

    private fun check() {
        if (outcome != null) return
        reports.addLast(describe())
        if (reports.size > MAX_REPORTS) reports.removeFirst()
        if (player.condition()) finish(Result.success(player.capture()))
    }

    private fun finish(result: Result<T>) {
        if (outcome != null) return
        outcome = result
        player.removeListener(listener)
        reached.countDown()
    }

    private fun describe(): String = with(player) {
        val state = when (playbackState) {
            Player.STATE_IDLE -> "idle"
            Player.STATE_BUFFERING -> "buffering"
            Player.STATE_READY -> "ready"
            else -> "ended"
        }
        val id = currentMediaItem?.mediaId?.substringAfterLast('/')
        "${currentMediaItemIndex + 1}/$mediaItemCount $id $state${if (isPlaying) " playing" else ""}"
    }

    private fun history() = "the player reported: ${reports.joinToString(" | ")}"

    private companion object {
        const val MAX_REPORTS = 12
    }
}

/**
 * Every state [store] saves from the moment this is made, so that a test sees one that a later write replaces soon (the
 * queue while a short song plays, on a runner too slow to read the file in time). Close it after use.
 */
class SavedStates(store: PlaybackStateStore) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val seen = MutableStateFlow<List<PlaybackSnapshot?>?>(null)

    init {
        scope.launch { store.saved.collect { state -> seen.update { it.orEmpty() + state } } }
        // The state on disk comes first; after it, no write can be missed.
        runBlocking {
            withTimeoutOrNull(SAFETY_TIMEOUT) { seen.first { it != null } }
                ?: throw AssertionError("The saved state never came").also { close() }
        }
    }

    /** Waits until a saved state, the one on disk included, satisfies [condition]. */
    fun await(what: String, timeout: Duration = SAFETY_TIMEOUT, condition: (PlaybackSnapshot?) -> Boolean) {
        runBlocking {
            withTimeoutOrNull(timeout) { seen.first { states -> states.orEmpty().any(condition) } }
                ?: throw AssertionError("Timed out after $timeout waiting until $what; the last saved: ${seen.value?.lastOrNull()}")
        }
    }

    override fun close() = scope.cancel()
}
