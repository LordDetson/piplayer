package io.github.lorddetson.piplayer.tagging.write

import android.content.ContentUris
import android.content.Context
import android.content.res.Resources
import androidx.room3.withWriteTransaction
import io.github.lorddetson.piplayer.library.db.FileState
import io.github.lorddetson.piplayer.library.db.LibraryAccess
import io.github.lorddetson.piplayer.library.db.SongRow
import io.github.lorddetson.piplayer.library.sync.TrackDisplayWriter
import io.github.lorddetson.piplayer.library.tagpass.TagPass
import io.github.lorddetson.piplayer.library.tagpass.TagPassStorage
import io.github.lorddetson.piplayer.library.values.LegacyText
import io.github.lorddetson.piplayer.library.values.TextForms
import io.github.lorddetson.piplayer.settings.LibrarySettings
import io.github.lorddetson.piplayer.tagging.TagTarget
import io.github.lorddetson.piplayer.userdata.TagWriteJournal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** The library's transaction follows the journal's verification transaction. The journal retains the work until it commits. */
internal class TagWriteLibrary(
    private val app: Context,
    private val library: LibraryAccess,
    private val journal: TagWriteJournal,
    private val onStored: (List<SongRow>) -> Unit,
) {
    suspend fun store(id: Long, target: TagWriteTarget, read: VerifiedTagRead): Unit = withContext(Dispatchers.IO) {
        val file = target.taggerTarget as? TagTarget.LibraryFile ?: return@withContext
        val encoding = LibrarySettings(app).readLegacyEncoding()
        val rows: List<SongRow> = library.use { database ->
            runBlocking {
                database.withWriteTransaction<List<SongRow>?> {
                    val dao = database.library()
                    val current = dao.file(file.fileId)?.takeIf {
                        it.volumeName == target.volume && it.relativePath == target.relativePath && it.displayName == target.displayName &&
                            it.mediaStoreId == ContentUris.parseId(target.uri) &&
                            (it.contentKey == null || it.contentKey == target.contentKey)
                    } ?: return@withWriteTransaction null
                    val storage = TagPassStorage(dao)
                    val tags = storage.parse(read.report, ::isCyrillicLanguage, encoding) ?: return@withWriteTransaction null
                    val state = FileState(current.id, current.size, current.mtime, current.volumeName,
                        current.relativePath, current.durationMs, current.durationSource, current.tagPassAt)
                    dao.setVerifiedWriteStamp(current.id, read.stamp.size, Math.floorDiv(read.stamp.modifiedMs, 1_000))
                    storage.store(current.id, tags, state, TagPass.READER_VERSION)
                    val ids = listOf(current.id)
                    val inputs = TrackDisplayWriter(database).inputsOf(ids)
                    with(TrackDisplayWriter) { write(inputs.map { it.fileId }, TextForms().let { forms -> inputs.map { it.resolve(forms) } }) }
                    dao.songRowsOf(ids)
                }
            }
        } ?: return@withContext
        journal.libraryRefreshed(id)
        if (rows.isNotEmpty()) onStored(rows)
    }

    private fun isCyrillicLanguage(): Boolean {
        val locales = listOf(Resources.getSystem().configuration.locales, app.resources.configuration.locales)
        return LegacyText.isCyrillicLanguage(locales.flatMap { list -> List(list.size()) { list[it].language } })
    }
}
