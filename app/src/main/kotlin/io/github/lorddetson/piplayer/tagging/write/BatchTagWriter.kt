package io.github.lorddetson.piplayer.tagging.write

import android.content.Context
import android.os.storage.StorageManager
import io.github.lorddetson.piplayer.library.db.LibraryAccess
import io.github.lorddetson.piplayer.tagging.Tagger
import io.github.lorddetson.piplayer.userdata.TagApplyQueue
import io.github.lorddetson.piplayer.userdata.TagWriteJournal
import io.github.lorddetson.piplayer.userdata.UserData
import io.github.lorddetson.piplayer.userdata.UserDatabase
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class BatchTagWriter(
    context: Context,
    library: LibraryAccess = LibraryAccess.app(context),
    database: UserDatabase = UserData.database(context),
) {
    private val app = context.applicationContext
    private val queue = TagApplyQueue(database)
    private val plans = TagApplyPlans(library, database)
    private val planning = Mutex()
    private val work = TagApplyWork(
        queue,
        TagWriteSession(
            directory = File(app.noBackupFilesDir, "tag-writes"),
            tagger = Tagger.client(app),
            journal = TagWriteJournal(database),
            access = MediaStoreWriteAccess(app),
            openForRead = { uri -> app.contentResolver.openFileDescriptor(uri, "r") ?: throw FileNotFoundException(uri.toString()) },
            availableBytes = { path ->
                val storage = app.getSystemService(StorageManager::class.java)
                storage?.getAllocatableBytes(storage.getUuidForPath(path)) ?: path.usableSpace
            },
        ),
        target = plans::target,
        diff = plans::diff,
    )

    suspend fun nextGroup(batchUuid: String): TagApplyGroup = planning.withLock {
        if (queue.results(batchUuid).isEmpty()) queue.save(plans.forBatch(batchUuid))
        work.nextGroup(batchUuid)
    }
}
