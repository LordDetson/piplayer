package io.github.lorddetson.piplayer.tagging.write

import android.content.Context
import android.content.IntentSender
import android.os.storage.StorageManager
import io.github.lorddetson.piplayer.library.db.LibraryAccess
import io.github.lorddetson.piplayer.tagging.Tagger
import io.github.lorddetson.piplayer.tagging.TaggerClient
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
    tagger: TaggerClient = Tagger.client(context),
    staging: File = File(context.applicationContext.noBackupFilesDir, "tag-writes"),
) {
    private val app = context.applicationContext
    private val queue = TagApplyQueue(database)
    private val plans = TagApplyPlans(library, database)
    private val planning = Mutex()
    private val access = MediaStoreWriteAccess(app)
    private val work = TagApplyWork(
        queue,
        TagWriteSession(
            directory = staging,
            tagger = tagger,
            journal = TagWriteJournal(database),
            access = access,
            openForRead = { uri -> app.contentResolver.openFileDescriptor(uri, "r") ?: throw FileNotFoundException(uri.toString()) },
            availableBytes = { path ->
                val storage = app.getSystemService(StorageManager::class.java)
                storage?.getAllocatableBytes(storage.getUuidForPath(path)) ?: path.usableSpace
            },
        ),
        target = plans::target,
        playback = playbackTagWriteGate,
        diff = plans::diff,
    )

    suspend fun nextGroup(batchUuid: String): TagApplyGroup = planning.withLock {
        if (queue.results(batchUuid).isEmpty()) queue.save(plans.forBatch(batchUuid))
        work.nextGroup(batchUuid)
    }

    fun request(group: TagApplyGroup): IntentSender? = access.requestAccess(group.files.values.toList())

    suspend fun apply(group: TagApplyGroup) = work.apply(group, consent = true)

    suspend fun results(batchUuid: String): Map<String, Int> = queue.results(batchUuid)
}
