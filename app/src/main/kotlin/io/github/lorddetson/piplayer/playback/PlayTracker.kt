package io.github.lorddetson.piplayer.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.identity.Hex
import io.github.lorddetson.piplayer.library.identity.TrackRef
import io.github.lorddetson.piplayer.userdata.ListenOutcome
import io.github.lorddetson.piplayer.userdata.UserData
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Where counted plays go (docs/library.md §15.5); the service writes them into the user database. */
interface PlayRecorder {
    /** The playback of [mediaId] that started at [startedAt] (wall clock) has just counted. */
    fun counted(mediaId: String, startedAt: Long, listenedMs: Long)

    /** A playback that counted has ended after [listenedMs]. */
    fun ended(mediaId: String, startedAt: Long, listenedMs: Long)

    /**
     * The playback of [mediaId] that started at [startedAt] has ended with [outcome] after [listenedMs], its song [durationMs]
     * long (null: not known); docs/playback.md §12.
     */
    fun listened(mediaId: String, startedAt: Long, listenedMs: Long, durationMs: Long?, outcome: ListenOutcome)
}

/**
 * Watches the service's player, counts plays from the time actually listened (docs/playback.md §11) and tells how every
 * listening ended (§12). It lives on the main thread with the player; [clock] is monotonic, [wallClock] dates the
 * playbacks.
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

    /** Where the current playback's song was when the player left it for another item, as Media3 reported it (§12.2). */
    private var endPositionMs: Long? = null

    /** Whether the player failed since the current playback last played: its end is not the user's (§12.3). */
    private var failed = false

    /** Whether the current playback's outcome is written already: it keeps its first (§12.3). */
    private var outcomeWritten = false

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
        // The service goes: the playback is neither skipped nor finished, and a restore decides (§12.3, §12.5).
        finish(ListenEnd.NONE)
    }

    /** The restored item [mediaId] goes on from [progress] when it becomes the current playback (§11.3). */
    fun resume(mediaId: String, progress: ListenProgress) {
        pending = mediaId to progress
    }

    /** The saved playback [progress] of [mediaId], which a restore does not resume, has ended (§11.3). */
    fun endSaved(mediaId: String, progress: ListenProgress) {
        // Only a counted playback has an event to finish; one not counted never becomes a play.
        if (progress.counted) recorder.ended(mediaId, progress.startedAt, progress.listenedMs)
    }

    /**
     * The saved playback [progress] of [mediaId], which a restore moved off the end of its song [durationMs] long, was
     * listened to the end (§12.5).
     */
    fun finishSaved(mediaId: String, progress: ListenProgress, durationMs: Long?) {
        ListenOutcomes.of(ListenEnd.REACHED_END, progress.listenedMs, endPositionMs = null, durationMs, failed = false)
            ?.let { recorder.listened(mediaId, progress.startedAt, progress.listenedMs, durationMs, it) }
    }

    /**
     * Ends the current playback and starts a new one of the same item (§4.3.1): the restored position was at the end of
     * the song, so the playback that ends was listened to the end (§12.5).
     */
    fun restart() {
        if (!started) return
        pending = null
        finish(ListenEnd.REACHED_END)
        player.currentMediaItem?.let(::begin)
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
        // Every reported change of the current item is a new playback, also a new queue that starts with the same song
        // (§11.1). Changes that keep the entry (an insertion, a removal elsewhere, shuffle, new values for the entry in
        // place) are reported as no transition.
        finish(ListenOutcomes.endOf(reason, counter.mediaId, mediaItem?.mediaId))
        mediaItem?.let(::begin)
    }

    @OptIn(UnstableApi::class)
    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        // Reported before the transition it comes with, while the old position is still the playback's song: where it
        // was left decides whether moving on was a skip or came at the end (§12.2, §6.4: stop after current track pauses
        // at the end and then seeks to the next song). A jump inside the song (a seek, skipped silence) is no end.
        val mediaId = counter.mediaId ?: return
        if (oldPosition.mediaItem?.mediaId != mediaId) return
        val insideTheSong = oldPosition.mediaItemIndex == newPosition.mediaItemIndex && newPosition.mediaItem?.mediaId == mediaId
        endPositionMs = if (insideTheSong) null else oldPosition.positionMs
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        counter.setPlaying(isPlaying)
        // Playing again after an error, as a retry does, makes its end the user's again (§12.3).
        if (isPlaying) failed = false
        checkNow()
    }

    override fun onPlayerError(error: PlaybackException) {
        failed = true
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        // The queue ended (§11.2): nothing may end this playback before the process goes, so a counted one gets its
        // listened time now. It stays the current playback; should it go on, its end writes the time again.
        if (playbackState != Player.STATE_ENDED) return
        val mediaId = counter.mediaId ?: return
        val progress = counter.progress(mediaId) ?: return
        if (progress.counted) recorder.ended(mediaId, progress.startedAt, progress.listenedMs)
        // It played into its end, which is its outcome; going on after the end writes no other (§12.3).
        writeOutcome(mediaId, progress.startedAt, progress.listenedMs, ListenEnd.REACHED_END)
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        // A new queue's timeline comes before its transition: its length is not the ending playback's.
        if (player.currentMediaItem?.mediaId == counter.mediaId) knownDuration()?.let(counter::setDuration)
        checkNow()
    }

    private fun begin(item: MediaItem) {
        val resume = pending?.takeIf { it.first == item.mediaId }?.second
        pending = null
        counter.start(item.mediaId, knownDuration() ?: item.mediaMetadata.durationMs, wallClock(), player.isPlaying, resume)
        endPositionMs = null
        failed = false
        outcomeWritten = false
        checkNow()
    }

    /** Ends the current playback, which ended as [end]. */
    private fun finish(end: ListenEnd) {
        handler.removeCallbacks(check)
        val durationMs = counter.durationMs
        val ended = counter.end() ?: return
        if (ended.counted) recorder.ended(ended.mediaId, ended.startedAt, ended.listenedMs)
        writeOutcome(ended.mediaId, ended.startedAt, ended.listenedMs, end, durationMs)
        endPositionMs = null
    }

    /** The outcome of the current playback, once: the first one it gets is kept (§12.3). */
    private fun writeOutcome(mediaId: String, startedAt: Long, listenedMs: Long, end: ListenEnd, durationMs: Long? = counter.durationMs) {
        if (outcomeWritten) return
        val outcome = ListenOutcomes.of(end, listenedMs, endPositionMs, durationMs, failed) ?: return
        outcomeWritten = true
        recorder.listened(mediaId, startedAt, listenedMs, durationMs, outcome)
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
 * Writes the plays of library songs and the outcomes of their listenings into the user database (docs/library.md §15.5,
 * §15.7), off the main thread and in order: a song plays by its track identity, found when it counts or its listening
 * ends. Opened files and songs without an identity are not recorded.
 */
class LibraryPlayRecorder(context: Context) : PlayRecorder {

    private val app = context.applicationContext

    /** One write after another, also across their suspensions: an end never overtakes its count. */
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    /** The identity each counted playback was recorded under, by its media ID and start. */
    private val recorded = ConcurrentHashMap<Pair<String, Long>, Long>()

    /** Takes the writes in order until [close]; then it ends, and nothing of the recorder stays behind. */
    private val writer = CoroutineScope(Dispatchers.IO).launch {
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

    override fun counted(mediaId: String, startedAt: Long, listenedMs: Long) {
        val fileId = QueueIds.fileId(mediaId) ?: return
        write {
            val ref = refOf(fileId) ?: return@write
            val identity = ref.identityId ?: return@write
            // Checked again in the write transaction: a takeover may have deleted it since (docs/library.md §8.2).
            recorded[mediaId to startedAt] =
                UserData.plays(app).record(identity, startedAt, listenedMs, ref.contentKey?.let(Hex::bytes)) ?: return@write
        }
    }

    override fun ended(mediaId: String, startedAt: Long, listenedMs: Long) {
        write {
            // A playback resumed after the process died counted in the process before.
            val identity = recorded.remove(mediaId to startedAt) ?: QueueIds.fileId(mediaId)?.let(::identityOf) ?: return@write
            UserData.plays(app).setListened(identity, startedAt, listenedMs)
        }
    }

    override fun listened(mediaId: String, startedAt: Long, listenedMs: Long, durationMs: Long?, outcome: ListenOutcome) {
        val fileId = QueueIds.fileId(mediaId) ?: return
        write {
            val ref = refOf(fileId) ?: return@write
            val identity = ref.identityId ?: return@write
            // Checked again in the write transaction, as a play is (docs/library.md §8.1, §15.7).
            UserData.listens(app).record(identity, startedAt, listenedMs, durationMs, outcome, ref.contentKey?.let(Hex::bytes))
        }
    }

    /** Takes no more writes; those asked for so far are still done, and then the writer ends. */
    fun close() {
        writes.close()
    }

    /** Waits until the writer has ended after [close]; for tests. */
    @androidx.annotation.VisibleForTesting
    suspend fun awaitClosed() = writer.join()

    /** Waits until every write asked for so far is done; for tests. */
    @androidx.annotation.VisibleForTesting
    suspend fun flush() {
        val done = CompletableDeferred<Unit>()
        writes.send { done.complete(Unit) }
        done.await()
    }

    private fun refOf(fileId: Long): TrackRef? = if (Library.mayRead(app)) Library.trackRefs(app).refOf(fileId) else null

    private fun identityOf(fileId: Long): Long? = refOf(fileId)?.identityId

    /** A failed write is logged; playback must not go down for it. */
    private fun write(block: suspend () -> Unit) {
        writes.trySend(block)
    }

    private companion object {
        const val TAG = "LibraryPlayRecorder"
    }
}
