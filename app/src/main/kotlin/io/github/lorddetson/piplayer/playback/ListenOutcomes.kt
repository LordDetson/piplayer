package io.github.lorddetson.piplayer.playback

import androidx.media3.common.Player
import io.github.lorddetson.piplayer.userdata.ListenOutcome

/** How a playback ended, as far as its outcome goes (docs/playback.md §12.2, §12.3). */
enum class ListenEnd {
    /** It played into the end of its song: an automatic or a repeat transition, the end of the queue. */
    REACHED_END,

    /** The user moved on: next, previous to another song, another entry, a new queue of another song, a removal. */
    MOVED_ON,

    /** Neither: the service went, the queue emptied, the same song chosen anew. */
    NONE,
}

/** The outcome of a listening from how its playback ended (docs/playback.md §12). */
object ListenOutcomes {

    /** Less listened time than this at a skip makes it an early one. */
    const val EARLY_SKIP_LIMIT_MS = 30_000L

    /**
     * The outcome of a playback that ended as [end] after [listenedMs] of listened time, at [endPositionMs] of a song of
     * [durationMs] (each null when not known), or null for none: no end that tells, an error since it last played, or no
     * listened time.
     */
    fun of(end: ListenEnd, listenedMs: Long, endPositionMs: Long?, durationMs: Long?, failed: Boolean): ListenOutcome? = when {
        failed || listenedMs <= 0 -> null
        end == ListenEnd.REACHED_END -> ListenOutcome.FINISHED
        end == ListenEnd.NONE -> null
        // Moved on in the fade-out, or from the end where stop after current track paused it (§4.3.1, §6.4).
        endPositionMs != null && QueueRestore.isAtEnd(endPositionMs, durationMs) -> ListenOutcome.FINISHED
        listenedMs < EARLY_SKIP_LIMIT_MS -> ListenOutcome.EARLY_SKIP
        else -> ListenOutcome.LATE_SKIP
    }

    /**
     * How the playback of [fromMediaId] ends at a transition for [reason] to [toMediaId] (null: to no item): Media3's
     * `MEDIA_ITEM_TRANSITION_REASON_*`.
     */
    fun endOf(reason: Int, fromMediaId: String?, toMediaId: String?): ListenEnd = when {
        fromMediaId == null -> ListenEnd.NONE
        reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> ListenEnd.REACHED_END
        reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> ListenEnd.MOVED_ON
        // A new queue that starts with the same song asks for it again; one with no item is no listening's end.
        reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED && toMediaId != null && toMediaId != fromMediaId -> ListenEnd.MOVED_ON
        else -> ListenEnd.NONE
    }

    /** The share of a song of [durationMs] that [listenedMs] covers, at most 1; null when the length is not known. */
    fun share(listenedMs: Long, durationMs: Long?): Double? {
        if (durationMs == null || durationMs <= 0) return null
        return (listenedMs.coerceAtLeast(0).toDouble() / durationMs).coerceAtMost(1.0)
    }
}
