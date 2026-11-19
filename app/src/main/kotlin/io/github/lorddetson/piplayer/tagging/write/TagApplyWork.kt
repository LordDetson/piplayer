package io.github.lorddetson.piplayer.tagging.write

import android.net.Uri
import io.github.lorddetson.piplayer.settings.TagWritingOptions
import io.github.lorddetson.piplayer.tagging.TagReport
import io.github.lorddetson.piplayer.userdata.TagApplyItem
import io.github.lorddetson.piplayer.userdata.TagApplyQueue
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class TagApplyGroup(
    val planId: String,
    val files: Map<Long, Uri>,
    val options: TagWritingOptions = TagWritingOptions(),
)

internal class TagApplyWork(
    private val queue: TagApplyQueue,
    private val session: TagWriteSession,
    private val target: suspend (TagApplyItem) -> TagWriteTarget?,
    private val playback: TagWriteGate? = null,
    private val diff: suspend (TagApplyItem, TagWritingOptions) -> ((TagReport) -> TagDiff)?,
) {
    private val writing = Mutex()

    suspend fun applyWhenAvailable(group: TagApplyGroup, onWaiting: (Set<String>) -> Unit = {}) {
        try {
            while (true) {
                val blocked = apply(group, consent = true)
                if (blocked.isEmpty()) return
                onWaiting(blocked)
                checkNotNull(playback).awaitAnyWritable(blocked)
            }
        } finally {
            onWaiting(emptySet())
        }
    }

    /** Builds a request from the persisted selection. Unavailable files remain queued for a later session. */
    suspend fun nextGroup(planId: String, options: TagWritingOptions = TagWritingOptions()): TagApplyGroup {
        val files = LinkedHashMap<Long, Uri>()
        val seen = HashSet<Uri>()
        for (item in queue.remaining(planId).sortedBy { it.writeId == null }) {
            val uri = resolve(item)?.uri ?: continue
            if (seen.add(uri)) files[item.fileId] = uri
            if (files.size == MEDIASTORE_WRITE_REQUEST_LIMIT) break
        }
        return TagApplyGroup(planId, files, options)
    }

    /** Only the URIs in the approved group can be opened for writing, even if the library changed meanwhile. */
    suspend fun apply(group: TagApplyGroup, consent: Boolean): Set<String> = writing.withLock {
        if (!consent || group.files.isEmpty()) return@withLock emptySet()
        val blocked = HashSet<String>()
        for (item in queue.remaining(group.planId)) {
            val approved = group.files[item.fileId] ?: continue
            val file = resolve(item)?.takeIf { it.uri == approved } ?: continue
            val uri = file.uri.toString()
            val lease = playback?.tryWrite(uri)
            if (playback != null && lease == null) {
                blocked += uri
                continue
            }
            val result = lease.use {
                val id = item.writeId ?: prepare(item, file, group.options)
                id?.let { session.write(it, file, consent = true) }
            }
            when (result) {
                null, WriteBackResult.VERIFIED, WriteBackResult.JOURNAL_CHANGED -> Unit
                WriteBackResult.NEEDS_CONSENT, WriteBackResult.INTERRUPTED -> return@withLock emptySet()
                WriteBackResult.INVALID_COPY, WriteBackResult.FAILED, WriteBackResult.SOURCE_CHANGED -> queue.finish(item, "failed")
            }
        }
        blocked
    }

    private suspend fun resolve(item: TagApplyItem): TagWriteTarget? = target(item)?.takeIf { file ->
        file.decisionId == item.decisionId && file.contentKey == item.contentKey &&
            file.volume == item.volumeName && file.relativePath == item.relativePath && file.displayName == item.displayName
    }

    private suspend fun prepare(item: TagApplyItem, file: TagWriteTarget, options: TagWritingOptions): Long? {
        val changes = diff(item, options)
        if (changes == null) {
            queue.finish(item, "skipped")
            return null
        }
        return when (val result = session.prepareForApply(file, item, options.id3v1, changes)) {
            is QueuedTagWrite.Ready -> result.id
            is QueuedTagWrite.Skipped -> {
                when (result.reason) {
                    PreparationFailure.NO_CHANGES, PreparationFailure.NO_SPACE -> queue.finish(item, "skipped")
                    PreparationFailure.CONTENT_CHANGED, PreparationFailure.UNVERIFIED -> queue.finish(item, "failed")
                    PreparationFailure.QUEUE_CHANGED -> Unit
                }
                null
            }
        }
    }
}
