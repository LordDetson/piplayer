package io.github.lorddetson.piplayer.userdata

import androidx.room3.withWriteTransaction

/** Counted plays (docs/library.md §15.5): one event per play for 90 days, older ones folded into `play_stats`. */
class PlayStats(private val database: UserDatabase) {

    private val dao get() = database.plays()

    /**
     * A play of [identityId], read with the file's [contentKey], that counted; raises the change counter, since the play
     * count is mirrored. The identity is checked in the write transaction ([current]); the one recorded, or null for none.
     */
    suspend fun record(identityId: Long, startedAt: Long, listenedMs: Long, contentKey: ByteArray? = null): Long? =
        database.withWriteTransaction {
            val identity = database.identities().current(identityId, contentKey) ?: return@withWriteTransaction null
            dao.addEvent(identity, startedAt, listenedMs)
            database.user().countChange()
            identity
        }

    /** The final listened time of a play that counted; nothing of it is mirrored, so the counter stays. */
    suspend fun setListened(identityId: Long, startedAt: Long, listenedMs: Long) =
        dao.setListened(identityId, startedAt, listenedMs)

    /**
     * Folds the events that started before [now] minus 90 days into `play_stats`, and the listening outcomes as old into
     * `listen_stats` (docs/library.md §15.7), in one transaction; the mirrored values stay the same.
     */
    suspend fun fold(now: Long) = database.withWriteTransaction {
        val before = now - KEEP_EVENTS_MS
        dao.foldInto(before)
        dao.deleteEventsBefore(before)
        database.listens().foldInto(before)
        database.listens().deleteEventsBefore(before)
    }

    /** Forgets every play; tests start so. */
    suspend fun clear() = database.withWriteTransaction {
        usePrepared("DELETE FROM play_event") { it.step() }
        usePrepared("DELETE FROM play_stats") { it.step() }
        database.user().countChange()
    }

    companion object {
        /** How long events are kept one by one. */
        const val KEEP_EVENTS_MS = 90L * 24 * 60 * 60 * 1_000
    }
}
