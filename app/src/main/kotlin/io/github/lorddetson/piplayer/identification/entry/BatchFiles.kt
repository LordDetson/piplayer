package io.github.lorddetson.piplayer.identification.entry

import android.content.Context
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.db.DecidedMirrorDao
import io.github.lorddetson.piplayer.library.lists.MissingInfoDecision
import io.github.lorddetson.piplayer.library.lists.SongQuery
import io.github.lorddetson.piplayer.library.songs.SongList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * The files a [BatchRequest] names, read from the library off the main thread (TI §1): a selection's songs as the entry
 * point holds them, a folder's tracks and those of the folders below it, and Songs missing information as the list shows
 * it, without its Keep as-is and Skip tracks. The reads follow what the screens show (the folder's tree, the reasons the
 * list was filtered by), so a batch holds the songs the user was looking at.
 */
class BatchFiles(private val context: Context) {

    /** The files of [request] in the order its entry point shows them, each once. */
    suspend fun of(request: BatchRequest): List<Long> = withContext(Dispatchers.IO) {
        when (request) {
            is BatchRequest.Selection -> BatchPicks.files(request.fileIds)
            is BatchRequest.Folder -> BatchPicks.files(
                SongList.load(context, query = SongQuery.tree(request.volume, request.relativePath)).map { it.id },
            )
            is BatchRequest.MissingInfo -> BatchPicks.missingInfo(missingInfoTracks(request.reasons))
        }
    }

    /** The tracks of Songs missing information with [reasons], each with its Keep as-is or Skip (TI §1). */
    private fun missingInfoTracks(reasons: Int): List<BatchTrack> {
        val songs = SongList.load(context, query = SongQuery.missingInfo(reasons))
        if (songs.isEmpty()) return emptyList()
        val app = context.applicationContext
        val decisions = Library.read(app) { database ->
            runBlocking {
                songs.map { it.id }.chunked(DecidedMirrorDao.CHUNK).flatMap { database.decided().missingInfoOf(it) }
            }
        }.associate { it.fileId to MissingInfoDecision.ofValue(it.value) }
        return songs.map { BatchTrack(fileId = it.id, missingInfo = decisions[it.id] ?: MissingInfoDecision.NONE) }
    }
}
