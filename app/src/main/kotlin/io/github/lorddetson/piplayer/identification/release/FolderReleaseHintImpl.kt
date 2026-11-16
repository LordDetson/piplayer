package io.github.lorddetson.piplayer.identification.release

import io.github.lorddetson.piplayer.library.db.FolderKey
import io.github.lorddetson.piplayer.library.db.FolderReleaseRow
import io.github.lorddetson.piplayer.library.db.LibraryAccess
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * The folder hint of the app (TI §4.2 key 1): the releases the files of a folder show as their release MBID, decided at
 * level 1 or 2 or else from their tags, read from the library database through [library] (`decided_mirror` and the
 * tags of `track`). A last folder named like a disc counts with its parent folder, as album keys do (docs/library.md
 * §6.2).
 */
class LibraryFolderReleaseHint(
    private val library: LibraryAccess,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : FolderReleaseHint {

    override suspend fun confirmedReleaseIds(folder: String?): Set<String> = TODO("M3 step 16")

    companion object {
        /** The releases of [rows] that lie in [folder]'s album folder, in their usual form. */
        fun confirmed(folder: FolderKey, rows: List<FolderReleaseRow>): Set<String> = TODO("M3 step 16")
    }
}
