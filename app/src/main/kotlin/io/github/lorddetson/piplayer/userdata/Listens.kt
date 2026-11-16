package io.github.lorddetson.piplayer.userdata

import androidx.room3.withWriteTransaction

/**
 * The outcome of every listening (docs/library.md §15.7, docs/playback.md §12): one event per listening for 90 days,
 * older ones folded into the listening counts of `play_stats` with the plays (PlayStats.fold). Nothing of it is
 * mirrored, so no write here raises the change counter, and none of it keeps an identity (§8.1).
 */
class Listens(private val database: UserDatabase) {

    /**
     * The [outcome] of a listening of [identityId], read with the file's [contentKey], that started at [startedAt]. The
     * identity is checked in the write transaction ([TrackIdentityDao.current]); the one recorded, or null for none. A
     * second outcome of the same listening is ignored.
     */
    suspend fun record(
        identityId: Long,
        startedAt: Long,
        listenedMs: Long,
        durationMs: Long?,
        outcome: ListenOutcome,
        contentKey: ByteArray? = null,
    ): Long? = database.withWriteTransaction {
        val identity = database.identities().current(identityId, contentKey) ?: return@withWriteTransaction null
        database.listens().add(startedAt, identity, listenedMs, durationMs, outcome.code)
        identity
    }

    /** Forgets every listening, the folded counts too; the plays stay. Tests start so. */
    suspend fun clear() = database.withWriteTransaction {
        usePrepared("DELETE FROM listen_event") { it.step() }
        usePrepared("DELETE FROM play_stats WHERE play_count = 0") { it.step() }
        usePrepared("UPDATE play_stats SET early_skips = 0, late_skips = 0, finished = 0") { it.step() }
    }
}
