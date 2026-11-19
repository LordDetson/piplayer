package io.github.lorddetson.piplayer.tagging.write

internal const val MEDIASTORE_WRITE_REQUEST_LIMIT = 2_000

internal fun <T> writeRequestGroups(files: Iterable<T>, maxUris: Int = MEDIASTORE_WRITE_REQUEST_LIMIT): List<List<T>> =
    files.distinct().chunked(maxUris)
