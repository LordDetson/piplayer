package io.github.lorddetson.piplayer.identification.batch

/**
 * The album grouping of a batch (TI §8): one group per album card, so that one release lookup serves the whole album
 * and the cards match the albums the user sees. Files without an album stand alone and merge with the rest later,
 * when their recordings resolve to the same release.
 */

/** One file of a batch with what groups it (TI §8): its ID and the album the library groups it under, if any. */
data class BatchFile(
    /** The file's ID in the library database; the caller joins its place and content key by it (TI §10.1). */
    val fileId: Long,
    /** The album's release MBID (TI §10.1), non-empty when known. */
    val releaseMbid: String?,
    /** The library album entity's key (TI §10.1), null when the file has no album. */
    val albumKey: String?,
    /** The album's title (TI §10.1), non-empty for a library album. */
    val title: String?,
)

/** A group of a batch (TI §8). */
sealed interface BatchGroup {
    /** The files of the group, in the order they were given. */
    val files: List<BatchFile>

    /**
     * An album group, one card per album (TI §8): files whose album has a release MBID or a non-empty title. [files]
     * never empty.
     */
    data class Album(
        /** The album's release MBID, null when the album has none. */
        val releaseMbid: String?,
        /** The library album's key, null only when a release MBID groups files with no album entity behind them. */
        val albumKey: String?,
        val title: String?,
        override val files: List<BatchFile>,
    ) : BatchGroup

    /** One file without an album, looked up alone and merged later by its release (TI §8). */
    data class Single(val file: BatchFile) : BatchGroup {
        override val files: List<BatchFile> get() = listOf(file)
    }
}

/**
 * Groups [files] by album (TI §8): a file keys by its album's release MBID when non-empty, else by the album's key, so
 * files of one album, whatever their title, stay together and one release lookup serves the group, while a file
 * without an album stands alone. Album groups come in the order of their first file, then the files without an album,
 * in their order; within a group the files keep their order.
 */
fun groupByAlbum(files: List<BatchFile>): List<BatchGroup> {
    val albums = LinkedHashMap<String, AlbumBuilder>()
    val singles = mutableListOf<BatchFile>()
    for (file in files) {
        val key = file.releaseMbid?.takeIf(String::isNotBlank) ?: file.albumKey?.takeIf(String::isNotBlank)
        if (key == null) {
            singles += file
        } else {
            albums.getOrPut(key) {
                AlbumBuilder(file.releaseMbid?.takeIf(String::isNotBlank), file.albumKey?.takeIf(String::isNotBlank), file.title)
            }.files += file
        }
    }
    return albums.values.map(AlbumBuilder::build) + singles.map(BatchGroup::Single)
}

/** The files of one album as they accumulate. */
private class AlbumBuilder(val releaseMbid: String?, val albumKey: String?, val title: String?) {
    val files = mutableListOf<BatchFile>()

    fun build() = BatchGroup.Album(releaseMbid, albumKey, title, files.toList())
}