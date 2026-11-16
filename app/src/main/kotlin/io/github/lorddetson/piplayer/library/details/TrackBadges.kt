package io.github.lorddetson.piplayer.library.details

import io.github.lorddetson.piplayer.library.identity.Hex
import io.github.lorddetson.piplayer.library.identity.LibraryTrackRefs
import io.github.lorddetson.piplayer.userdata.Decisions
import io.github.lorddetson.piplayer.userdata.UserDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * The metadata badge of a song of the library for Now Playing (TI §6, docs/library.md §18.7): the display row's
 * `badge_state`, read through the library's lock, with the provenance of the identification that wins from [user].
 */
class TrackBadges(private val access: LibraryTrackRefs.Access, private val user: UserDatabase) {

    /** The badge of the file [fileId]; null without a display row. */
    suspend fun of(fileId: Long): Badge? = withContext(Dispatchers.IO) {
        val (state, key) = access.read { database ->
            runBlocking {
                val dao = database.library()
                dao.badgeState(fileId) to dao.file(fileId)?.contentKey
            }
        }
        if (state == null) return@withContext null
        // Only an identified song names a provider and a confidence, which only the user database holds.
        if (BadgeState.ofCode(state) != BadgeState.IDENTIFIED) return@withContext DetailBadges.of(state, emptyList())
        val identity = key?.let { user.identities().idOfKey(Hex.bytes(it)) }
        DetailBadges.of(state, identity?.let { Decisions(user).storedOf(it).map(DecisionRecord::of) }.orEmpty())
    }
}
