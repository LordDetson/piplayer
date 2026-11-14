package io.github.lorddetson.piplayer.ui.playlists

import android.content.Context
import android.net.Uri
import android.util.Log
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.lists.LibraryLists
import io.github.lorddetson.piplayer.library.playlists.ImportResult
import io.github.lorddetson.piplayer.library.playlists.PlaylistFiles
import io.github.lorddetson.piplayer.settings.LibrarySettings
import io.github.lorddetson.piplayer.ui.player.QueueTarget
import io.github.lorddetson.piplayer.userdata.PlaylistEntryEntity
import io.github.lorddetson.piplayer.userdata.UserData
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext

/** What the screens do with playlists (docs/library.md §16.3), off the main thread; null or false when it failed. */
class PlaylistChoices(context: Context) {

    private val app = context.applicationContext
    private val playlists get() = UserData.playlists(app)

    suspend fun create(name: String): Long? = safely(null) { playlists.create(name) }

    suspend fun rename(id: Long, name: String): Boolean = safely(false) { playlists.rename(id, name) }

    suspend fun delete(id: Long): Boolean = safely(false) { playlists.delete(id) }

    suspend fun name(id: Long): String? = safely(null) { playlists.playlist(id)?.name }

    /** Adds the songs of [target] to the end of [playlistId], in the order "Add to queue" takes them; how many. */
    suspend fun add(playlistId: Long, target: QueueTarget): Int? = safely(null) {
        val ids = withContext(Dispatchers.IO) { fileIdsOf(target) }
        Library.playlists(app).addFiles(playlistId, ids)
    }

    suspend fun remove(entryId: Long): PlaylistEntryEntity? = safely(null) { playlists.remove(entryId) }

    suspend fun restore(entry: PlaylistEntryEntity): Boolean = safely(false) {
        playlists.restore(entry)
        true
    }

    suspend fun move(entryId: Long, to: Int): Boolean = safely(false) { playlists.move(entryId, to) }

    suspend fun import(uri: Uri): ImportResult? = safely(null) { PlaylistFiles(app).import(uri) }

    suspend fun export(playlistId: Long, uri: Uri): Int? = safely(null) { PlaylistFiles(app).export(playlistId, uri) }

    private suspend fun fileIdsOf(target: QueueTarget): List<Long> {
        if (target is QueueTarget.Songs) return target.ids
        if (!Library.mayRead(app)) return emptyList()
        val query = QueueTarget.queryOf(app, target)
        if (target is QueueTarget.Playlist) return Library.playlists(app).songs(target.id).map { it.id }
        val hidden = LibrarySettings(app).readHiddenFiles()
        return Library.read(app) { LibraryLists(it, hidden).songs(query).map { song -> song.id } }
    }

    private suspend fun <T> safely(fallback: T, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "A playlist could not be changed", e)
        fallback
    }

    companion object {
        private const val TAG = "PlaylistChoices"

        /** Changes run here, not in a screen's scope, so that a rotation does not cut one short. */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}
