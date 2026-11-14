package io.github.lorddetson.piplayer.playback

/** How far the current playback got, saved with the snapshot (docs/playback.md §11.3). */
data class ListenProgress(val listenedMs: Long, val counted: Boolean, val startedAt: Long)

/** A playback that ended: what its event needs (docs/library.md §15.5). */
data class EndedPlayback(val mediaId: String, val startedAt: Long, val listenedMs: Long, val counted: Boolean)

/**
 * Counts plays from listened time (docs/playback.md §11): the time of [clock], a monotonic clock in milliseconds, that
 * passes while the player plays. A playback counts once, when its listened time reaches half of the song or 4 minutes,
 * whichever is less; a song shorter than 30 s never counts. Not thread-safe: the service calls it on the main thread.
 */
class PlayCounter(private val clock: () -> Long, private val thresholdOf: (Long?) -> Long? = { thresholdMs(it) }) {

    private class Playback(val mediaId: String, var durationMs: Long?, val startedAt: Long, var listenedMs: Long, var counted: Boolean)

    private var current: Playback? = null

    /** When the player last started playing the current playback; null while it does not play. */
    private var playingSince: Long? = null

    /** The media ID of the current playback, or null between playbacks. */
    val mediaId: String? get() = current?.mediaId

    /** Starts a playback of [mediaId], which started at [startedAt] by the wall clock, going on from [resume] if given. */
    fun start(mediaId: String, durationMs: Long?, startedAt: Long, playing: Boolean, resume: ListenProgress? = null) {
        current = Playback(
            mediaId,
            durationMs,
            startedAt = resume?.startedAt ?: startedAt,
            listenedMs = resume?.listenedMs ?: 0,
            counted = resume?.counted ?: false,
        )
        playingSince = if (playing) clock() else null
    }

    fun setPlaying(playing: Boolean) {
        val playback = current ?: return
        if (playing) {
            if (playingSince == null) playingSince = clock()
        } else {
            playback.listenedMs = listenedMs()
            playingSince = null
        }
    }

    /** The song's length, once the player knows it. */
    fun setDuration(durationMs: Long?) {
        if (durationMs != null) current?.durationMs = durationMs
    }

    /** The listened time of the current playback now. */
    fun listenedMs(): Long {
        val playback = current ?: return 0
        val since = playingSince ?: return playback.listenedMs
        return playback.listenedMs + (clock() - since).coerceAtLeast(0)
    }

    /** True once per playback: when its listened time has just reached the threshold. */
    fun check(): Boolean {
        val playback = current ?: return false
        if (playback.counted) return false
        val threshold = thresholdOf(playback.durationMs) ?: return false
        if (listenedMs() < threshold) return false
        playback.counted = true
        return true
    }

    /** How long until the current playback counts if it keeps playing; null when it will not count from here. */
    fun msUntilCount(): Long? {
        val playback = current ?: return null
        if (playback.counted || playingSince == null) return null
        val threshold = thresholdOf(playback.durationMs) ?: return null
        return (threshold - listenedMs()).coerceAtLeast(0)
    }

    /** The current playback's progress when it is of [mediaId], for the snapshot. */
    fun progress(mediaId: String): ListenProgress? {
        val playback = current?.takeIf { it.mediaId == mediaId } ?: return null
        return ListenProgress(listenedMs(), playback.counted, playback.startedAt)
    }

    /** Ends the current playback. */
    fun end(): EndedPlayback? {
        val playback = current ?: return null
        val ended = EndedPlayback(playback.mediaId, playback.startedAt, listenedMs(), playback.counted)
        current = null
        playingSince = null
        return ended
    }

    companion object {
        /** Songs shorter than this never count. */
        const val MIN_LENGTH_MS = 30_000L

        /** A play counts at half of the song, at most after this. */
        const val MAX_THRESHOLD_MS = 240_000L

        /** The listened time at which a song of [durationMs] counts, or null when it never does; unknown counts at the most. */
        fun thresholdMs(durationMs: Long?): Long? = when {
            durationMs == null || durationMs <= 0 -> MAX_THRESHOLD_MS
            durationMs < MIN_LENGTH_MS -> null
            else -> minOf(durationMs / 2, MAX_THRESHOLD_MS)
        }
    }
}
