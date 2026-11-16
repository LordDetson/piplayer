package io.github.lorddetson.piplayer.covers

/** What a view shows a cover of (docs/library.md §13.3): a song by its file ID, an album or an artist by its key. */
sealed interface CoverRequest {
    data class Track(val fileId: Long) : CoverRequest

    data class Album(val key: String) : CoverRequest

    data class Artist(val key: String) : CoverRequest
}

/** A release by its MBID, with its release group's when known: what the lazy Cover Art Archive step asks for (TI §5.2). */
data class CoverRelease(val release: String, val releaseGroup: String? = null)

/**
 * A file of the library as covers need it: what opens it, what changes when it changes, and [embedded], the tag pass's
 * answer whether it holds a picture (null while unread). [identityId] is its track identity, [albumKey] its album.
 * [decidedCover] is the `saved_cover` row of its decided cover (TI §7.1), null without one or for an empty one, and
 * [release] its release, the decided one before the tags' (docs/library.md §13.1).
 */
data class CoverFile(
    val fileId: Long,
    val uri: String,
    val size: Long,
    val mtime: Long,
    val embedded: Boolean?,
    val identityId: Long? = null,
    val albumKey: String? = null,
    /** The file's content key in hex, by which [identityId] was read. */
    val contentKey: String? = null,
    val decidedCover: Long? = null,
    val release: CoverRelease? = null,
)

/**
 * An album of an artist, in the artist page's order, and whether one of its songs has an embedded picture, a decided
 * cover or a release MBID (docs/library.md §13.1).
 */
data class ArtistAlbum(val key: String, val embedded: Boolean, val decided: Boolean = false, val release: Boolean = false)

/** One source of a cover (docs/library.md §13.1); [key] names the picture it gives, for the caches. */
sealed interface CoverStep {
    val key: String

    /** Whether the step loads over the network; the loader runs it after giving its place back (§13.3). */
    val remote: Boolean get() = false

    /** An own cover: its copy in the covers folder. */
    data class Own(val fileName: String) : CoverStep {
        override val key: String get() = "own/$fileName"
    }

    /**
     * A decided cover saved in PiPlayer (TI §5.2): the `saved_cover` row [id] and its file's name, the SHA-256 of its
     * bytes, which names the picture for the caches.
     */
    data class Saved(val id: Long, val fileName: String) : CoverStep {
        override val key: String get() = "saved/$fileName"
    }

    /** The picture embedded in [file], read by Media3's extractors. */
    data class Embedded(val file: CoverFile) : CoverStep {
        override val key: String get() = "embedded/${file.fileId}/${file.size}/${file.mtime}"
    }

    /** The system thumbnail of [file]. */
    data class Thumbnail(val file: CoverFile) : CoverStep {
        override val key: String get() = "thumbnail/${file.fileId}/${file.size}/${file.mtime}"
    }

    /** The lazy Cover Art Archive cover of [release] (docs/library.md §13.1 step 6), with plain MBIDs. */
    data class Caa(val release: CoverRelease) : CoverStep {
        override val key: String get() = "caa/${release.release}/${release.releaseGroup ?: "-"}"
        override val remote: Boolean get() = true
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

    /** The decided cover of the album [albumKey]: that of its first song with one by disc, track and file name. */
    suspend fun albumDecidedCover(albumKey: String): Long? = null

    /** The release of the album [albumKey]: that of its first song with one by disc, track and file name. */
    suspend fun albumRelease(albumKey: String): CoverRelease? = null
}

/** The own covers the user chose (docs/library.md §13.2): the name of each copy. */
interface OwnCoverIndex {
    fun track(identityId: Long): String?

    fun album(albumKey: String): String?
}

/** The saved covers (TI §5.2): the name of each one's file by its row ID, null when there is no such row. */
fun interface SavedCoverIndex {
    suspend fun fileName(id: Long): String?

    companion object {
        /** No saved covers. */
        val NONE = SavedCoverIndex { null }
    }
}

/** The sources of a cover in their order (docs/library.md §13.1). */
object CoverPlan {

    /**
     * The steps of [request]'s cover. [saved] names the files of decided covers; [online] is whether online lookups are
     * on, without which no step loads from Cover Art Archive.
     */
    suspend fun of(
        request: CoverRequest,
        facts: CoverFacts,
        own: OwnCoverIndex,
        saved: SavedCoverIndex = SavedCoverIndex.NONE,
        online: Boolean = false,
    ): List<CoverStep> = when (request) {
        is CoverRequest.Track -> facts.file(request.fileId)?.let { file ->
            listOfNotNull(
                file.identityId?.let(own::track)?.let(CoverStep::Own),
                file.albumKey?.let(own::album)?.let(CoverStep::Own),
            ) + fromFile(file)
        }.orEmpty()
        is CoverRequest.Album -> album(request.key, facts, own)
        is CoverRequest.Artist -> {
            val albums = facts.artistAlbums(request.key)
            val chosen = albums.firstOrNull { own.album(it.key) != null } ?: albums.firstOrNull { it.embedded } ?: albums.firstOrNull()
            if (chosen != null) album(chosen.key, facts, own) else facts.artistFile(request.key)?.let(::fromFile).orEmpty()
        }
    }

    /** An album's own cover, then the pictures of the song that stands for it; a song's own cover never does. */
    private suspend fun album(key: String, facts: CoverFacts, own: OwnCoverIndex): List<CoverStep> =
        listOfNotNull(own.album(key)?.let(CoverStep::Own)) + facts.albumFile(key)?.let(::fromFile).orEmpty()

    /** The embedded picture, unless the tag pass found none, then the system thumbnail. */
    private fun fromFile(file: CoverFile): List<CoverStep> =
        if (file.embedded == false) listOf(CoverStep.Thumbnail(file)) else listOf(CoverStep.Embedded(file), CoverStep.Thumbnail(file))
}
