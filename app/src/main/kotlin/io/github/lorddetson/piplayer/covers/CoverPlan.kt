package io.github.lorddetson.piplayer.covers

/** What a view shows a cover of (docs/library.md §13.3): a song by its file ID, an album or an artist by its key. */
sealed interface CoverRequest {
    data class Track(val fileId: Long) : CoverRequest

    data class Album(val key: String) : CoverRequest

    data class Artist(val key: String) : CoverRequest
}

/**
 * A file of the library as covers need it: what opens it, what changes when it changes, and [embedded], the tag pass's
 * answer whether it holds a picture (null while unread). [identityId] is its track identity, [albumKey] its album.
 */
data class CoverFile(
    val fileId: Long,
    val uri: String,
    val size: Long,
    val mtime: Long,
    val embedded: Boolean?,
    val identityId: Long? = null,
    val albumKey: String? = null,
)

/** An album of an artist, in the artist page's order, and whether one of its songs has an embedded picture. */
data class ArtistAlbum(val key: String, val embedded: Boolean)

/** One source of a cover (docs/library.md §13.1); [key] names the picture it gives, for the caches. */
sealed interface CoverStep {
    val key: String

    /** An own cover: its copy in the covers folder. */
    data class Own(val fileName: String) : CoverStep {
        override val key: String get() = "own/$fileName"
    }

    /** The picture embedded in [file], read by Media3's extractors. */
    data class Embedded(val file: CoverFile) : CoverStep {
        override val key: String get() = "embedded/${file.fileId}/${file.size}/${file.mtime}"
    }

    /** The system thumbnail of [file]. */
    data class Thumbnail(val file: CoverFile) : CoverStep {
        override val key: String get() = "thumbnail/${file.fileId}/${file.size}/${file.mtime}"
    }
}

/** What the library database knows for covers. */
interface CoverFacts {
    suspend fun file(fileId: Long): CoverFile?

    /** The song that stands for the album [albumKey] (docs/library.md §13.1), or null when the album has none. */
    suspend fun albumFile(albumKey: String): CoverFile?

    /** The albums of the artist [artistKey] in the artist page's order. */
    suspend fun artistAlbums(artistKey: String): List<ArtistAlbum>

    /** The artist's first song, for an artist without albums. */
    suspend fun artistFile(artistKey: String): CoverFile?
}

/** The own covers the user chose (docs/library.md §13.2): the name of each copy. */
interface OwnCoverIndex {
    fun track(identityId: Long): String?

    fun album(albumKey: String): String?
}

/** The sources of a cover in their order (docs/library.md §13.1). */
object CoverPlan {

    suspend fun of(request: CoverRequest, facts: CoverFacts, own: OwnCoverIndex): List<CoverStep> = TODO()
}
