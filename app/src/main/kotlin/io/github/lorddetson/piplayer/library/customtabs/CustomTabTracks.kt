package io.github.lorddetson.piplayer.library.customtabs

import android.content.Context
import android.util.Log
import androidx.room3.withReadTransaction
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.db.FolderKey
import io.github.lorddetson.piplayer.library.lists.LibraryLists
import io.github.lorddetson.piplayer.library.lists.SongQuery
import io.github.lorddetson.piplayer.library.lists.SongSort
import io.github.lorddetson.piplayer.library.playlists.PlaylistItem
import io.github.lorddetson.piplayer.library.songs.Song
import io.github.lorddetson.piplayer.library.songs.SongList
import io.github.lorddetson.piplayer.settings.LibrarySettings
import io.github.lorddetson.piplayer.settings.ListOrder
import io.github.lorddetson.piplayer.userdata.UserData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking

internal data class CustomTabTrack(val occurrence: Long, val song: Song)

internal sealed interface CustomTabTracks {
    data object Loading : CustomTabTracks
    data object Failed : CustomTabTracks
    data object Unbound : CustomTabTracks
    data object Missing : CustomTabTracks
    data class Loaded(val tracks: List<CustomTabTrack>) : CustomTabTracks
}

/** Reads only the active tab. Playlists retain their own row IDs even when they resolve to the same file. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun watchCustomTab(
    context: Context,
    record: CustomTabRecord,
    order: ListOrder,
    search: String,
): Flow<CustomTabTracks> {
    val source = record.resolvedSource ?: return flowOf(CustomTabTracks.Unbound)
    val base = source.existingSongQuery() ?: SongQuery.SONGS
    val query = base.copy(sort = SongSort.of(order.field) ?: base.sort, reversed = order.reversed, search = search.trim())
    val revisions = combine(SongList.changes(context, query), Library.decisionVersion(context), SongList.batches, ::Triple)
    val tracks = if (source is CustomTabSource.Playlist) {
        val playlists = UserData.playlists(context)
        combine(revisions, playlists.playlistFlow(source.id), playlists.entryRowsFlow(source.id), ::Triple)
            .mapLatest {
                withContext(Dispatchers.IO) {
                    val rows = UserData.database(context).withReadTransaction {
                        val playlist = playlists.playlist(source.id)
                        if (playlist == null || playlist.createdAt != record.playlistCreatedAt) null
                        else playlists.entryRows(source.id)
                    }
                    if (rows == null) CustomTabTracks.Missing else {
                        CustomTabTracks.Loaded(playlistTracks(context, Library.playlists(context).itemsOf(rows), query))
                    }
                }
            }
    } else {
        revisions.mapLatest {
            withContext(Dispatchers.IO) {
                when (source) {
                    is CustomTabSource.Collection -> loaded(SongList.load(context, query = query))
                    is CustomTabSource.Folder -> {
                        val hidden = LibrarySettings(context).readHiddenFiles()
                        Library.read(context) { database ->
                            val folders = runBlocking { database.library().folders() }
                            if (folders.none { it.volume == source.key.volume && it.relativePath.startsWith(source.key.relativePath) }) {
                                CustomTabTracks.Missing
                            } else {
                                loaded(LibraryLists(database, hidden).songs(query))
                            }
                        }
                    }
                    is CustomTabSource.Rules -> {
                        val hidden = LibrarySettings(context).readHiddenFiles()
                        loaded(Library.read(context) { LibraryLists(it, hidden).songsMatchingRules(query, source.rules) })
                    }
                    is CustomTabSource.Playlist -> error("Playlists use the joint library and playlist observation")
                }
            }
        }
    }
    return tracks.catch { failure ->
        Log.w("CustomTabTracks", "Unable to read a custom library tab", failure)
        emit(CustomTabTracks.Failed)
    }
}

private fun loaded(songs: List<Song>) = CustomTabTracks.Loaded(songs.map { CustomTabTrack(it.id, it) })

private fun playlistTracks(context: Context, items: List<PlaylistItem>, query: SongQuery): List<CustomTabTrack> {
    val tracks = items.mapNotNull { item -> item.song?.let { CustomTabTrack(item.entryId, it) } }
    val ids = tracks.mapTo(LinkedHashSet()) { it.song.id }
    val hidden = LibrarySettings(context).readHiddenFiles()
    val visible = Library.read(context) { LibraryLists(it, hidden).visibleSongs(ids, query) }
    return if (query.sort == SongSort.POSITION) {
        val byId = visible.associateBy(Song::id)
        val inPositionOrder = tracks.mapNotNull { row -> byId[row.song.id]?.let { row.copy(song = it) } }
        if (query.reversed) inPositionOrder.asReversed() else inPositionOrder
    } else {
        val occurrences = tracks.groupBy { it.song.id }
        visible.flatMap { song -> occurrences[song.id].orEmpty().map { it.copy(song = song) } }
    }
}

/** Includes each volume root and each parent that can be selected independently of its children. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun watchCustomTabFolders(context: Context): Flow<List<FolderKey>> =
    SongList.changes(context).mapLatest {
        withContext(Dispatchers.IO) {
            val folders = Library.read(context) { runBlocking { it.library().folders() } }
            buildSet {
                for (folder in folders) {
                    add(FolderKey(folder.volume, ""))
                    folder.relativePath.forEachIndexed { index, char ->
                        if (char == '/') add(FolderKey(folder.volume, folder.relativePath.substring(0, index + 1)))
                    }
                }
            }.sortedWith(compareBy({ it.volume }, { it.relativePath }))
        }
    }
