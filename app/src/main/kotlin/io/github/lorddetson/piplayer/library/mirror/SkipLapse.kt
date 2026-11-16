package io.github.lorddetson.piplayer.library.mirror

internal object SkipLapse {

    data class Skip(val decisionId: Long, val identityId: Long, val size: Long?, val mtimeMs: Long?)

    data class FileStamp(val size: Long, val mtimeSeconds: Long)

    fun lapses(skip: Skip, files: Collection<FileStamp>): Boolean = false

    fun mtimeMs(mtimeSeconds: Long): Long = mtimeSeconds * 1_000
}
