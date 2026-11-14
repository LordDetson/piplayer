package io.github.lorddetson.piplayer.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.userdata.UserData
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Where counted plays go (docs/library.md §15.5); the service writes them into the user database. */
interface PlayRecorder {
    /** The playback of [mediaId] that started at [startedAt] (wall clock) has just counted. */
    fun counted(mediaId: String, startedAt: Long, listenedMs: Long)

    /** A playback that counted has ended after [listenedMs]. */
    fun ended(mediaId: String, startedAt: Long, listenedMs: Long)
}

/**
 * Watches the service's player and counts plays from the time actually listened (docs/playback.md §11). It lives on the
 * main thread with the player; [clock] is monotonic, [wallClock] dates the playbacks.
 */
class PlayTracker(
    private val player: Player,
    private val recorder: PlayRecorder,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val wallClock: () -> Long = System::currentTimeMillis,
    thresholdOf: (Long?) -> Long? = PlayCounter::thresholdMs,
) : Player.Listener {

    private val handler = Handler(Looper.getMainLooper())
    private val counter = PlayCounter(clock, thresholdOf)
    private val check = Runnable { checkNow() }
    private var started = false

    /** The progress a restored item goes on from once it becomes the current playback. */
    private var pending: Pair<String, ListenProgress>? = null

    /** Starts watching, with the current item as a new playback. */
    fun start() {
        if (started) return
        started = true
        player.addListener(this)
        player.currentMediaItem?.let(::begin)
    }

    /** Ends the current playback and stops watching. */
    fun release() {
        if (!started) return
        started = false
        player.removeListener(this)
        finish()
    }

    /** The restored item [mediaId] goes on from [progress] when it becomes the current playback (§11.3). */
    fun resume(mediaId: String, progress: ListenProgress) {
        pending = mediaId to progress
    }

    /** The current playback's progress, if it is of the player's current item, for the snapshot. */
    fun progress(): ListenProgress? = player.currentMediaItem?.mediaId?.let(counter::progress)

    /** Checks the threshold now; the service checks at the time it will be reached. */
    fun checkNow() {
        if (counter.check()) {
            counter.mediaId?.let { mediaId ->
                counter.progress(mediaId)?.let { recorder.counted(mediaId, it.startedAt, it.listenedMs) }
            }
        }
        schedule()
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        // A change of the queue that keeps the item current goes on with its playback (§11.1).
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED && mediaItem?.mediaId == counter.mediaId) return
        finish()
        mediaItem?.let(::begin)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        counter.setPlaying(isPlaying)
        checkNow()
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        knownDuration()?.let(counter::setDuration)
        checkNow()
    }

    private fun begin(item: MediaItem) {
        val resume = pending?.takeIf { it.first == item.mediaId }?.second
        pending = null
        counter.start(item.mediaId, knownDuration() ?: item.mediaMetadata.durationMs, wallClock(), player.isPlaying, resume)
        checkNow()
    }

    private fun finish() {
        handler.removeCallbacks(check)
        val ended = counter.end() ?: return
        if (ended.counted) recorder.ended(ended.mediaId, ended.startedAt, ended.listenedMs)
    }

    /** The player's length of its current item, once it knows it. */
    private fun knownDuration(): Long? = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }

    private fun schedule() {
        handler.removeCallbacks(check)
        if (!started) return
        // A moment later than due, so that the clock has surely passed the threshold when it runs.
        counter.msUntilCount()?.let { handler.postDelayed(check, it + 1) }
    }
}

/**
 * Writes the plays of library songs into the user database (docs/library.md §15.5), off the main thread and in order:
 * a song plays by its track identity, found when it counts. Opened files and songs without an identity are not recorded.
 */
class LibraryPlayRecorder(context: Context) : PlayRecorder {

    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One write after another, also across their suspensions: an end never overtakes its count. */
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (write in writes) {
                try {
                    write()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "A play could not be recorded", e)
                }
            }
        }
    }

    /** The identity each counted playback was recorded under, by its media ID and start. */
    private val recorded = ConcurrentHashMap<Pair<String, Long>, Long>()

    override fun counted(mediaId: String, startedAt: Long, listenedMs: Long) {
        val fileId = QueueIds.fileId(mediaId) ?: return
        write {
            val identity = identityOf(fileId) ?: return@write
            recorded[mediaId to startedAt] = identity
            UserData.plays(app).record(identity, startedAt, listenedMs)
        }
    }

    override fun ended(mediaId: String, startedAt: Long, listenedMs: Long) {
        write {
            // A playback resumed after the process died counted in the process before.
            val identity = recorded.remove(mediaId to startedAt) ?: QueueIds.fileId(mediaId)?.let(::identityOf) ?: return@write
            UserData.plays(app).setListened(identity, startedAt, listenedMs)
        }
    }

    /** Waits until every write asked for so far is done; for tests. */
    @androidx.annotation.VisibleForTesting
    suspend fun flush() {
        val done = CompletableDeferred<Unit>()
        writes.send { done.complete(Unit) }
        done.await()
    }

    private fun identityOf(fileId: Long): Long? =
        if (Library.mayRead(app)) Library.trackRefs(app).refOf(fileId)?.identityId else null

    /** A failed write is logged; playback must not go down for it. */
    private fun write(block: suspend () -> Unit) {
        writes.trySend(block)
    }

    private companion object {
        const val TAG = "LibraryPlayRecorder"
    }
}
