package io.github.lorddetson.piplayer.covers

import io.github.lorddetson.piplayer.identification.musicbrainz.MusicBrainzClient

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

    /**
     * The decided covers of the album [albumKey]: those of its songs that are not empty, by disc, track and file name,
     * each once.
     */
    suspend fun albumDecidedCovers(albumKey: String): List<Long> = emptyList()

    /** The releases of the album [albumKey]: those of its songs that have one, by disc, track and file name, each once. */
    suspend fun albumReleases(albumKey: String): List<CoverRelease> = emptyList()
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
            ) + song(file, saved, online)
        }.orEmpty()
        is CoverRequest.Album -> album(request.key, facts, own, saved, online)
        is CoverRequest.Artist -> artist(request.key, facts, own, saved, online)
    }

    /**
     * An artist's album by own cover, decided cover, embedded picture, else the first, with the next of these choices
     * after it in case its sources give no picture (the facts cannot see a decided cover's missing file or a folder
     * image), each album once; then, while online, the Cover Art Archive step of the first album with a release MBID,
     * so that it never replaces a local picture of another album.
     */
    private suspend fun artist(key: String, facts: CoverFacts, own: OwnCoverIndex, saved: SavedCoverIndex, online: Boolean): List<CoverStep> {
        val albums = facts.artistAlbums(key)
        if (albums.isEmpty()) return facts.artistFile(key)?.let { song(it, saved, online) }.orEmpty()
        val chosen = listOfNotNull(
            albums.firstOrNull { own.album(it.key) != null },
            albums.firstOrNull { it.decided },
            albums.firstOrNull { it.embedded },
            albums.first(),
        ).distinct()
        val local = chosen.flatMap { albumLocal(it.key, facts, own, saved) }.distinct()
        // The releases are read only while they can be used, and only until one is an MBID.
        val release = if (online) albums.filter { it.release }.firstNotNullOfOrNull { firstCaa(facts.albumReleases(it.key)) } else null
        return local + listOfNotNull(release)
    }

    /**
     * An album's own cover, its decided covers, the pictures of the song that stands for it, then its release on Cover
     * Art Archive; a song's own cover never stands for it.
     */
    private suspend fun album(key: String, facts: CoverFacts, own: OwnCoverIndex, saved: SavedCoverIndex, online: Boolean): List<CoverStep> =
        albumLocal(key, facts, own, saved) +
            // The releases are read only while they can be used.
            listOfNotNull(if (online) firstCaa(facts.albumReleases(key)) else null)

    /** An album's own cover, each of its decided covers in turn, then the pictures of the song that stands for it. */
    private suspend fun albumLocal(key: String, facts: CoverFacts, own: OwnCoverIndex, saved: SavedCoverIndex): List<CoverStep> =
        listOfNotNull(own.album(key)?.let(CoverStep::Own)) +
            facts.albumDecidedCovers(key).mapNotNull { saved(it, saved) } +
            facts.albumFile(key)?.let(::fromFile).orEmpty()

    /** The Cover Art Archive step of the first of [releases] that is an MBID. */
    private fun firstCaa(releases: List<CoverRelease>): CoverStep? = releases.firstNotNullOfOrNull(::caa)

    /** A song's steps after its own covers: its decided cover, its file's pictures, then its release on Cover Art Archive. */
    private suspend fun song(file: CoverFile, saved: SavedCoverIndex, online: Boolean): List<CoverStep> =
        listOfNotNull(saved(file.decidedCover, saved)) + fromFile(file) + listOfNotNull(if (online) caa(file.release) else null)

    /** The decided cover [id] while its row names a file. */
    private suspend fun saved(id: Long?, saved: SavedCoverIndex): CoverStep? =
        id?.let { saved.fileName(it) }?.let { CoverStep.Saved(id, it) }

    /** The lazy Cover Art Archive step of [release], with plain MBIDs; none for a release ID that is not an MBID. */
    private fun caa(release: CoverRelease?): CoverStep? {
        val id = release?.release?.let(MusicBrainzClient::plainMbid) ?: return null
        return CoverStep.Caa(CoverRelease(id, release.releaseGroup?.let(MusicBrainzClient::plainMbid)))
    }

    /** The embedded picture, unless the tag pass found none, then the system thumbnail. */
    private fun fromFile(file: CoverFile): List<CoverStep> =
        if (file.embedded == false) listOf(CoverStep.Thumbnail(file)) else listOf(CoverStep.Embedded(file), CoverStep.Thumbnail(file))
}
