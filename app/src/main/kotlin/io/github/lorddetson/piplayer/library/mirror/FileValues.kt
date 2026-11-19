package io.github.lorddetson.piplayer.library.mirror

import io.github.lorddetson.piplayer.library.db.LibraryDao
import io.github.lorddetson.piplayer.library.db.MirrorDao
import io.github.lorddetson.piplayer.library.db.ReadFileRow
import io.github.lorddetson.piplayer.library.db.TrackEntity
import io.github.lorddetson.piplayer.library.values.DecisionContent
import io.github.lorddetson.piplayer.userdata.Decisions

/**
 * The values files hold at their places, as the references of TI §7.1 compare them: every part of each field that the
 * tag pass stored, from the file's track row. The tag pass's comparison, the references of a decision and "Keep
 * PiPlayer's value" in track details all take them from here, so that no part differs only because one of them read
 * fewer columns.
 */
object FileValues {

    /** The values of a file at its place from its first [track], which is null while the tag pass has not read it. */
    fun at(volume: String, relativePath: String, displayName: String, track: TrackEntity?): Decisions.LocationValues =
        Decisions.LocationValues(volume, relativePath, displayName, DecisionContent.ofTags(track?.toTags()))

    /** The values of each of the files [fileIds] that the tag pass has read, by file ID in the order the database gives. */
    suspend fun ofRead(dao: LibraryDao, fileIds: Collection<Long>): Map<Long, Decisions.LocationValues> =
        readLocations(dao, fileIds).mapValues { it.value.values }

    internal class ReadAtLocation(val file: ReadFileRow, val track: TrackEntity?) {
        val values get() = at(file.volume, file.relativePath, file.displayName, track)
    }

    internal suspend fun readLocations(dao: LibraryDao, fileIds: Collection<Long>): Map<Long, ReadAtLocation> {
        val values = LinkedHashMap<Long, ReadAtLocation>()
        for (chunk in fileIds.distinct().chunked(MirrorDao.CHUNK)) {
            // The rows come ordered by their start, so the first of each file is the one its file reads as.
            val tracks = dao.tracksOf(chunk).groupBy { it.fileId }
            for (read in dao.readFilesOf(chunk)) {
                values.getOrPut(read.fileId) { ReadAtLocation(read, tracks[read.fileId]?.first()) }
            }
        }
        return values
    }
}
