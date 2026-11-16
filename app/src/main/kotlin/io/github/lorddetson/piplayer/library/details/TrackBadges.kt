package io.github.lorddetson.piplayer.library.details

import io.github.lorddetson.piplayer.library.identity.LibraryTrackRefs
import io.github.lorddetson.piplayer.userdata.UserDatabase

/**
 * The metadata badge of a song of the library for Now Playing (TI §6, docs/library.md §18.7): the display row's
 * `badge_state`, read through the library's lock, with the provenance of the identification that wins from [user].
 */
class TrackBadges(private val access: LibraryTrackRefs.Access, private val user: UserDatabase) {

    /** The badge of the file [fileId]; null without a display row. */
    suspend fun of(fileId: Long): Badge? = null
}
