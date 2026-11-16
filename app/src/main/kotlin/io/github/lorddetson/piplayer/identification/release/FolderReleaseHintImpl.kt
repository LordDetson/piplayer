package io.github.lorddetson.piplayer.identification.release

import io.github.lorddetson.piplayer.library.db.FolderKey
import io.github.lorddetson.piplayer.library.db.FolderReleaseRow
import io.github.lorddetson.piplayer.library.db.LibraryAccess
import io.github.lorddetson.piplayer.library.identity.Hex
import io.github.lorddetson.piplayer.library.values.AlbumKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * The folder hint of the app (TI §4.2 key 1): the releases the files of a folder show as their release MBID, decided at
 * level 1 or 2 or else from their tags, read from the library database through [library] (`decided_mirror` and the
 * tags of `track`). A last folder named like a disc counts with its parent folder, as album keys do (docs/library.md
 * §6.2). The file asked about counts too: the hint knows only the folder.
 */
class LibraryFolderReleaseHint(
    private val library: LibraryAccess,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : FolderReleaseHint {

    override suspend fun confirmedReleaseIds(folder: String?): Set<String> {
        val key = folder?.let(FolderKey::parse) ?: return emptySet()
        // Every folder below the album folder, in one range of the index on volume and path; the rest is filtered here.
        val from = AlbumKey.albumFolder(key.relativePath)
        val rows = withContext(ioDispatcher) {
            // The block runs under the library's lock, which is held by its thread: the read blocks that thread.
            library.use { database -> runBlocking { database.decided().folderReleases(key.volume, from, from + LAST_CHARACTER) } }
        }
        return confirmed(key, rows)
    }

    companion object {
        /** U+10FFFF, after every character a path may hold, so that the range ends after the folder's last path. */
        private const val LAST_CHARACTER = "􏿿"

        /** The releases of [rows] that lie in [folder]'s album folder, in their usual form. */
        fun confirmed(folder: FolderKey, rows: List<FolderReleaseRow>): Set<String> {
            val albumFolder = AlbumKey.folderKey(folder.relativePath)
            return rows.asSequence()
                .filter { AlbumKey.folderKey(it.relativePath) == albumFolder }
                // A decided value wins, also an empty one (TI §7.1).
                .mapNotNull { Hex.mbidText(Hex.mbidBytes(if (it.decided) it.decidedRelease else it.tagRelease)) }
                .toSet()
        }
    }
}
