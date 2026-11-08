package io.github.lorddetson.piplayer.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.db.FolderKey
import io.github.lorddetson.piplayer.library.lists.AlbumCredit
import io.github.lorddetson.piplayer.library.lists.AlbumGroup
import io.github.lorddetson.piplayer.library.lists.AlbumSort
import io.github.lorddetson.piplayer.library.lists.ArtistGroup
import io.github.lorddetson.piplayer.library.lists.ArtistSort
import io.github.lorddetson.piplayer.library.lists.FolderEntry
import io.github.lorddetson.piplayer.library.lists.FolderLevels
import io.github.lorddetson.piplayer.library.lists.LibraryLists
import io.github.lorddetson.piplayer.library.lists.ListKind
import io.github.lorddetson.piplayer.library.lists.SongOutline
import io.github.lorddetson.piplayer.library.lists.SongQuery
import io.github.lorddetson.piplayer.library.lists.SongSort
import io.github.lorddetson.piplayer.library.search.SearchTerms
import io.github.lorddetson.piplayer.library.songs.Song
import io.github.lorddetson.piplayer.library.songs.SongList
import io.github.lorddetson.piplayer.library.sort.Sections
import io.github.lorddetson.piplayer.settings.ListName
import io.github.lorddetson.piplayer.settings.ListOrder
import io.github.lorddetson.piplayer.settings.ListSorts
import io.github.lorddetson.piplayer.settings.LibrarySettings
import io.github.lorddetson.piplayer.identification.text.TextNormalizer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** What a cover is asked for: a song by its file ID, an album or an artist by their keys (docs/playback.md §10.1). */
sealed interface ArtworkSubject {
    data class Song(val fileId: Long) : ArtworkSubject

    data class Album(val key: String) : ArtworkSubject

    data class Artist(val key: String) : ArtworkSubject
}

/** The covers of the tree's items as content URIs, from the cover provider (docs/library.md §13.8) or none. */
fun interface TreeArtwork {
    fun uriOf(subject: ArtworkSubject): Uri?

    /** The length of [uriOf]'s URI, 0 without one; the tree weighs every song by it (§10.2), so it may avoid building it. */
    fun uriLength(subject: ArtworkSubject): Int = uriOf(subject)?.toString()?.length ?: 0

    /** Changes whenever [uriOf] may give other URIs; items kept with URIs (search results) are built anew then. */
    val version: Long get() = 0

    companion object {
        val NONE = TreeArtwork { null }
    }
}

/**
 * The library tree for controllers (docs/playback.md §10): its nodes, their children, search, and the queue requests that
 * play them. Every call but [root] reads the library and blocks; make them off the main thread.
 */
class LibraryTree(context: Context, private val artwork: TreeArtwork = TreeArtwork.NONE) {

    private val app = context.applicationContext

    /**
     * The service asks from its browsing thread and from the thread that expands queues; the lists and caches here are
     * read by one of them at a time (their folder levels compute what they need lazily, for one thread).
     */
    private val lock = Any()

    /** The root, with exactly four browsable children. */
    fun root(): MediaItem = folder(TreeNode.Root, "", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, playable = false)

    /** Page [page] of [pageSize] of the children of [mediaId], or null when it names no node of the tree. */
    fun children(mediaId: String, page: Int, pageSize: Int): List<MediaItem>? {
        synchronized(lock) {
            val node = TreeNode.parse(mediaId) ?: return null
            val listing = listing(node) ?: return null
            return listing.items(TreeBuckets.page(listing.size, page, pageSize))
        }
    }

    /** The item [mediaId] names, or null when it names none. */
    fun item(mediaId: String): MediaItem? {
        synchronized(lock) {
            TreeNode.parse(mediaId)?.let { return nodeItem(it) }
            val fileId = QueueRequest.parse(mediaId)?.startSongId ?: QueueIds.fileId(mediaId) ?: return null
            val song = songById(fileId) ?: return null
            return songItem(song, mediaId)
        }
    }

    /** What the app's search finds for [query]: artists, albums, then songs (§10.3). */
    fun search(query: String): List<MediaItem> {
        synchronized(lock) {
            val text = query.trim()
            if (SearchTerms.of(text).isEmpty()) return emptyList()
            val songsQuery = songsQuery().copy(search = text)
            // The cover version too: the kept results carry cover URIs (docs/library.md §13.8.2).
            val stamp = listOf(text, stamp(), order(ListName.ARTISTS), order(ListName.ALBUMS), songsQuery, artwork.version)
            lastSearch?.takeIf { it.first == stamp }?.let { return it.second }
            val all = read { lists ->
                lists.artists(artistSort(), order(ListName.ARTISTS).reversed, text).take(SEARCH_ARTISTS).map(::artistItem) +
                    lists.albums(albumSort(), order(ListName.ALBUMS).reversed, text).take(SEARCH_ALBUMS).map(::albumItem) +
                    lists.songs(songsQuery, SEARCH_SONGS).mapIndexed { row, song -> songItem(song, QueueRequest(song.id, row, songsQuery).mediaId) }
            }.orEmpty()
            // What a legacy browser can take in one answer (§10.2).
            var bytes = 0L
            val found = all.takeWhile { item -> (bytes + weightOf(item) <= TreeBuckets.MAX_BYTES).also { if (it) bytes += weightOf(item) } }
            lastSearch = stamp to found
            return found
        }
    }

    /** The request that plays [mediaId] (a playable node of the tree), or null when it names none that plays. */
    fun playRequest(mediaId: String): QueueRequest? {
        synchronized(lock) {
            val node = TreeNode.parse(mediaId) ?: return null
            val query = queryOf(node)
            if (query != null) return QueueRequest(startSongId = NO_SONG, startRow = 0, list = query)
            val bucket = node as? TreeNode.Bucket ?: return null
            val entries = entries(bucket.parent) ?: return null
            val found = find(entries, bucket) ?: return null
            val songsQuery = entries.query ?: return null
            if (found.from < entries.nodes.size) return null
            val row = found.from - entries.nodes.size
            return QueueRequest(entries.outline.ids[row], row, songsQuery)
        }
    }

    /** The request a voice [query] plays (§10.3), or null when nothing matches. */
    fun voiceRequest(query: String): QueueRequest? {
        synchronized(lock) {
            val text = query.trim()
            if (TextNormalizer.normalize(text).isEmpty()) return QueueRequest(NO_SONG, 0, songsQuery())
            val songsQuery = songsQuery().copy(search = text)
            return read { lists ->
                val songs = lists.songs(songsQuery)
                when (val match = VoiceMatch.choose(text, lists.artists(artistSort(), order(ListName.ARTISTS).reversed, text), lists.albums(albumSort(), order(ListName.ALBUMS).reversed, text), songs)) {
                    VoiceMatch.AllSongs -> QueueRequest(NO_SONG, 0, songsQuery())
                    is VoiceMatch.Artist -> QueueRequest(NO_SONG, 0, artistQuery(match.key))
                    is VoiceMatch.Album -> QueueRequest(NO_SONG, 0, albumQuery(match.key))
                    is VoiceMatch.FoundSongs -> QueueRequest(match.fileId, songs.indexOfFirst { it.id == match.fileId }.coerceAtLeast(0), songsQuery)
                    null -> null
                }
            }
        }
    }

    /**
     * The songs an add of [item] adds (§10.3): a song of the tree or a queue item its song, a playable node its list, a
     * bucket its songs, a search query what its play would queue. Null when the item names nothing of the library.
     */
    fun songsToAdd(item: MediaItem): List<Song>? {
        synchronized(lock) {
            val id = item.mediaId
            if (id.isEmpty()) {
                val query = item.requestMetadata.searchQuery ?: return null
                val request = voiceRequest(query) ?: return null
                val songs = songsOf(request.list)
                return songs.drop(QueueRequest.startIndex(songs, request).coerceAtLeast(0))
            }
            val fileId = QueueRequest.parse(id)?.startSongId ?: QueueIds.fileId(id)
            if (fileId != null) return listOfNotNull(songById(fileId))
            val node = TreeNode.parse(id) ?: return null
            if (node is TreeNode.Bucket) {
                val entries = entries(node.parent) ?: return null
                val found = find(entries, node) ?: return null
                if (found.from < entries.nodes.size) return null
                return entries.songs(found.from - entries.nodes.size, found.to - entries.nodes.size)
            }
            return queryOf(node)?.let(::songsOf)
        }
    }

    /** The children of [node] before paging: its entries, or the buckets they split into. */
    private fun listing(node: TreeNode): Listing? {
        if (node == TreeNode.Root) {
            val tabs = listOf(TreeNode.Songs, TreeNode.Albums, TreeNode.Artists, TreeNode.Folders).map(::fixedItem)
            return Listing(tabs.size) { rows -> rows.map(tabs::get) }
        }
        if (node is TreeNode.Bucket) {
            val entries = entries(node.parent) ?: return null
            val found = find(entries, node) ?: return null
            return Listing(found.to - found.from) { rows -> entries.items(rows.first + found.from..rows.last + found.from) }
        }
        val entries = entries(node) ?: return null
        val buckets = buckets(entries)
        if (buckets.isEmpty()) return Listing(entries.size, entries::items)
        return Listing(buckets.size) { rows -> rows.map { bucketItem(node, entries, buckets[it]) } }
    }

    /**
     * The entries of a list node in its order: [nodes] first (albums, artists, folders), then the songs of [query] as its
     * [outline] gives them: only the songs of the rows shown are read (§10.5). Null for a node that is gone or no list.
     */
    private inner class Entries(val nodes: List<NodeEntry>, val outline: SongOutline, val query: SongQuery?) {
        val size: Int get() = nodes.size + outline.size

        /** The length of a song's media ID without its file ID and row, which differ by song. */
        private val idBase = query?.let { QueueRequest(0, 0, it).mediaId.length - 2 } ?: 0

        fun section(index: Int): String? = if (index < nodes.size) nodes[index].section else outline.sections[index - nodes.size]

        /** The items of [indices], the songs among them read in one go; a song gone since the outline is left out. */
        fun items(indices: IntRange): List<MediaItem> {
            if (indices.isEmpty()) return emptyList()
            val firstRow = maxOf(indices.first, nodes.size) - nodes.size
            val songs = if (indices.last >= nodes.size) songsById(outline.ids, firstRow, indices.last - nodes.size + 1) else emptyMap()
            return indices.mapNotNull { index ->
                if (index < nodes.size) return@mapNotNull nodes[index].item
                val row = index - nodes.size
                songs[outline.ids[row]]?.let { songItem(it, QueueRequest(it.id, row, query!!).mediaId) }
            }
        }

        /** The songs of rows [from] until [to] of the outline, in order. */
        fun songs(from: Int, to: Int): List<Song> {
            val songs = songsById(outline.ids, from, to)
            return (from until to).mapNotNull { songs[outline.ids[it]] }
        }

        /** What the entry's item weighs in a legacy browser's list (§10.2), without building it or reading its song. */
        fun weight(index: Int): Int {
            if (index < nodes.size) return nodes[index].weight
            val row = index - nodes.size
            val id = outline.ids[row]
            val uriLength = artwork.uriLength(ArtworkSubject.Song(id))
            return TreeBuckets.weightOfBytes(
                idBase + digits(id) + digits(row.toLong()), outline.titleBytes[row], outline.artistBytes[row], outline.albumBytes[row], uriLength,
            )
        }

        /** What the buckets of these entries depend on; their layout is kept by it (§10.5). */
        val bucketKey: BucketKey get() = BucketKey(outline, nodes.map { it.weight }, nodes.map { it.section }, artwork.version)
    }

    private class NodeEntry(val section: String?, val type: Int, val item: MediaItem) {
        val weight = weightOf(item)
    }

    /** The outline itself counts by identity: a new read is a new layout. */
    private data class BucketKey(val outline: SongOutline, val weights: List<Int>, val sections: List<String?>, val artwork: Long)

    private class Listing(val size: Int, val items: (IntRange) -> List<MediaItem>)

    private fun entries(node: TreeNode): Entries? = when (node) {
        TreeNode.Songs -> songsQuery().let { Entries(emptyList(), outlineOf(it), it) }
        TreeNode.Albums -> Entries(albums().map { NodeEntry(it.section, MediaMetadata.MEDIA_TYPE_ALBUM, albumItem(it)) }, SongOutline.EMPTY, null)
        TreeNode.Artists -> Entries(artists().map { NodeEntry(it.section, MediaMetadata.MEDIA_TYPE_ARTIST, artistItem(it)) }, SongOutline.EMPTY, null)
        TreeNode.Folders -> {
            val levels = levels()
            val top = levels.top()
            val folder = top.folder
            if (folder == null) {
                val volumes = top.entries.let { if (order(ListName.FOLDERS).reversed) it.reversed() else it }
                Entries(volumes.map(::folderEntry), SongOutline.EMPTY, null)
            } else {
                folderEntries(levels, FolderKey(folder.first, folder.second))
            }
        }
        is TreeNode.Album -> if (read { it.album(node.key) } == null) null else albumQuery(node.key).let { Entries(emptyList(), outlineOf(it), it) }
        is TreeNode.Artist -> read { lists ->
            lists.artist(node.key)?.let { lists.artistAlbums(node.key) }
        }?.let { albums ->
            val query = artistQuery(node.key)
            Entries(albums.map { NodeEntry(Sections.year(it.year), MediaMetadata.MEDIA_TYPE_ALBUM, albumItem(it)) }, outlineOf(query), query)
        }
        is TreeNode.Folder -> levels().let { levels -> if (levels.contains(node.folder.volume, node.folder.relativePath)) folderEntries(levels, node.folder) else null }
        TreeNode.Root, is TreeNode.Bucket -> null
    }

    /** A folder's subfolders by name (reversed with the order), then its own songs in the folder order. */
    private fun folderEntries(levels: FolderLevels, folder: FolderKey): Entries {
        val order = order(ListName.FOLDERS)
        val subfolders = levels.children(folder.volume, folder.relativePath, order.reversed)
        val query = SongQuery.folder(folder.volume, folder.relativePath).copy(sort = SongSort.of(order.field) ?: SongSort.FILE_NAME, reversed = order.reversed)
        return Entries(subfolders.map(::folderEntry), outlineOf(query), query)
    }

    private fun folderEntry(entry: FolderEntry) =
        NodeEntry(entry.section, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, folderItem(FolderKey(entry.volume, entry.relativePath)))

    private fun buckets(entries: Entries): List<TreeBucket> {
        val key = entries.bucketKey
        bucketLayouts[key]?.let { return it }
        return TreeBuckets.of(entries.size, entries::section, entries::weight, boundary = entries.nodes.size).also { bucketLayouts[key] = it }
    }

    private fun find(entries: Entries, bucket: TreeNode.Bucket): TreeBucket? =
        buckets(entries).firstOrNull { it.section == bucket.section && it.part == bucket.part }

    /** The list a node plays from its start, or null for one that does not play (§10.3). */
    private fun queryOf(node: TreeNode): SongQuery? = when (node) {
        TreeNode.Songs -> songsQuery()
        is TreeNode.Album -> albumQuery(node.key).takeIf { read { lists -> lists.album(node.key) } != null }
        is TreeNode.Artist -> artistQuery(node.key).takeIf { read { lists -> lists.artist(node.key) } != null }
        is TreeNode.Folder -> {
            val order = order(ListName.FOLDERS)
            SongQuery.tree(node.folder.volume, node.folder.relativePath)
                .copy(sort = SongSort.of(order.field) ?: SongSort.FILE_NAME, reversed = order.reversed)
                .takeIf { levels().contains(node.folder.volume, node.folder.relativePath) }
        }
        else -> null
    }

    private fun nodeItem(node: TreeNode): MediaItem? = when (node) {
        TreeNode.Root -> root()
        TreeNode.Songs, TreeNode.Albums, TreeNode.Artists, TreeNode.Folders -> fixedItem(node)
        is TreeNode.Album -> read { it.album(node.key) }?.let(::albumItem)
        is TreeNode.Artist -> read { it.artist(node.key) }?.let(::artistItem)
        is TreeNode.Folder -> node.folder.takeIf { levels().contains(it.volume, it.relativePath) }?.let(::folderItem)
        is TreeNode.Bucket -> entries(node.parent)?.let { entries -> find(entries, node)?.let { bucketItem(node.parent, entries, it) } }
    }

    private fun fixedItem(node: TreeNode): MediaItem = when (node) {
        TreeNode.Songs -> folder(node, app.getString(R.string.tab_songs), MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, playable = true)
        TreeNode.Albums -> folder(node, app.getString(R.string.tab_albums), MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS, playable = false)
        TreeNode.Artists -> folder(node, app.getString(R.string.tab_artists), MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS, playable = false)
        else -> folder(node, app.getString(R.string.tab_folders), MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, playable = false)
    }

    private fun bucketItem(parent: TreeNode, entries: Entries, bucket: TreeBucket): MediaItem {
        val songsOnly = bucket.from >= entries.nodes.size
        val types = (bucket.from until minOf(bucket.to, entries.nodes.size)).mapTo(HashSet()) { entries.nodes[it].type }
        val type = when {
            songsOnly -> MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
            bucket.to > entries.nodes.size || types.size != 1 -> MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
            types.single() == MediaMetadata.MEDIA_TYPE_ALBUM -> MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS
            types.single() == MediaMetadata.MEDIA_TYPE_ARTIST -> MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS
            else -> MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
        }
        return folder(TreeNode.Bucket(parent, bucket.section, bucket.part), bucket.title, type, playable = songsOnly)
    }

    private fun folder(node: TreeNode, title: String, type: Int, playable: Boolean, build: MediaMetadata.Builder.() -> Unit = {}) =
        MediaItem.Builder()
            .setMediaId(node.mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(playable)
                    .setMediaType(type)
                    .apply(build)
                    .build(),
            )
            .build()

    private fun albumItem(album: AlbumGroup) = folder(TreeNode.Album(album.key), album.title, MediaMetadata.MEDIA_TYPE_ALBUM, playable = true) {
        setArtist(
            when (val credit = album.credit) {
                is AlbumCredit.Named -> credit.name
                AlbumCredit.Various -> app.getString(R.string.various_artists)
                AlbumCredit.Unknown -> app.getString(R.string.songs_unknown_artist)
            },
        )
        setReleaseYear(album.year)
        artwork.uriOf(ArtworkSubject.Album(album.key))?.let(::setArtworkUri)
    }

    private fun artistItem(artist: ArtistGroup) = folder(TreeNode.Artist(artist.key), artist.name, MediaMetadata.MEDIA_TYPE_ARTIST, playable = true) {
        artwork.uriOf(ArtworkSubject.Artist(artist.key))?.let(::setArtworkUri)
    }

    private fun folderItem(folder: FolderKey): MediaItem {
        val title = when {
            folder.relativePath.isNotEmpty() -> folder.relativePath.dropLast(1).substringAfterLast('/')
            folder.volume == FolderLevels.PRIMARY -> app.getString(R.string.internal_storage)
            else -> folder.volume
        }
        return folder(TreeNode.Folder(folder), title, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, playable = true)
    }

    /** A song as the tree shows it: what a queue item shows, under [mediaId]; never artwork data (§10.1). */
    private fun songItem(song: Song, mediaId: String): MediaItem {
        val metadata = SessionItemRefresher.songMetadata(song)
        val uri = artwork.uriOf(ArtworkSubject.Song(song.id))
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(if (uri == null) metadata else metadata.buildUpon().setArtworkUri(uri).build())
            .build()
    }

    // The lists, in the orders the app keeps (docs/library.md §6.3).

    private fun order(list: ListName): ListOrder = runBlocking { ListSorts(app).order(list).first() }

    private fun songsQuery(): SongQuery = order(ListName.SONGS).let { SongQuery(ListKind.SONGS, sort = SongSort.of(it.field) ?: SongSort.TITLE, reversed = it.reversed) }

    private fun albumQuery(key: String): SongQuery = order(ListName.ALBUM_PAGE).let { SongQuery.album(key).copy(sort = SongSort.of(it.field) ?: SongSort.TRACK, reversed = it.reversed) }

    private fun artistQuery(key: String): SongQuery = order(ListName.ARTIST_PAGE).let { SongQuery.artist(key).copy(sort = SongSort.of(it.field) ?: SongSort.YEAR, reversed = it.reversed) }

    private fun albumSort() = AlbumSort.entries.firstOrNull { it.name.equals(order(ListName.ALBUMS).field, ignoreCase = true) } ?: AlbumSort.TITLE

    private fun artistSort() = if (order(ListName.ARTISTS).field == "songs") ArtistSort.SONGS else ArtistSort.NAME

    /** A songs list: the one the app or the tree read last, while it holds, else read now and kept (docs/library.md §6.4). */
    private fun songsOf(query: SongQuery): List<Song> {
        // Without access the shared list keeps an empty list that no revision makes stale; the tree does not take it.
        if (!Library.mayRead(app)) return emptyList()
        return SongList.current(query) ?: SongList.load(app, query = query)
    }

    private fun songById(fileId: Long): Song? = SongList.songsById(app, listOf(fileId))[fileId]

    /** The songs of [ids] from [from] until [to], by file ID. */
    private fun songsById(ids: LongArray, from: Int, to: Int): Map<Long, Song> =
        if (from >= to) emptyMap() else SongList.songsById(app, (from until to).map { ids[it] })

    /**
     * The outline of [query] (§10.5): kept for the last few lists while the library's revision, the hidden files, the
     * collation, the tag pass and its batches stay; without access to music it is empty.
     */
    private fun outlineOf(query: SongQuery): SongOutline {
        if (!Library.mayRead(app)) return SongOutline.EMPTY
        val stamp = stamp() + SongList.batches.value
        outlines[query]?.takeIf { it.first == stamp }?.let { return it.second }
        val outline = read { it.outline(query) } ?: SongOutline.EMPTY
        outlines[query] = stamp to outline
        return outline
    }

    private val outlines = object : LinkedHashMap<SongQuery, Pair<List<Any?>, SongOutline>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<SongQuery, Pair<List<Any?>, SongOutline>>) = size > KEPT_LISTS
    }

    private val bucketLayouts = object : LinkedHashMap<BucketKey, List<TreeBucket>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<BucketKey, List<TreeBucket>>) = size > KEPT_LISTS
    }

    private fun albums(): List<AlbumGroup> {
        val order = order(ListName.ALBUMS)
        return cached("albums", order, emptyList()) { lists -> lists.albums(albumSort(), order.reversed) }
    }

    private fun artists(): List<ArtistGroup> {
        val order = order(ListName.ARTISTS)
        return cached("artists", order, emptyList()) { lists -> lists.artists(artistSort(), order.reversed) }
    }

    private fun levels(): FolderLevels = cached("folders", null, FolderLevels(emptyList())) { lists -> lists.folders() }

    private class Cached(val stamp: List<Any?>, val value: Any)

    private val cache = HashMap<String, Cached>()

    @Volatile
    private var lastSearch: Pair<List<Any?>, List<MediaItem>>? = null

    /**
     * [compute]'s value for [name], read again only when the library's revision, the hidden files, the collation, a pass
     * of the tag pass or [order] changed (§10.5). Without access to music it is [empty].
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> cached(name: String, order: ListOrder?, empty: T, compute: (LibraryLists) -> T): T = synchronized(cache) {
        val stamp = listOf(order) + stamp()
        cache[name]?.takeIf { it.stamp == stamp }?.let { return it.value as T }
        val value = read(compute) ?: empty
        cache[name] = Cached(stamp, value)
        value
    }

    /** What the lists depend on besides their order. */
    private fun stamp(): List<Any?> {
        if (Library.mayRead(app)) Library.awaitReady(app, READY_TIMEOUT_MS)
        return listOf(Library.mayRead(app), Library.revision(), LibrarySettings.latest ?: LibrarySettings(app).readHiddenFiles(), Library.collation(), SongList.changingPasses)
    }

    /** [block] on the lists of the library, or null without access to music. */
    private fun <T> read(block: (LibraryLists) -> T): T? {
        if (!Library.mayRead(app)) return null
        Library.awaitReady(app, READY_TIMEOUT_MS)
        val hidden = LibrarySettings(app).readHiddenFiles()
        return Library.read(app) { database -> block(LibraryLists(database, hidden)) }
    }

    companion object {
        /** No node has more children (§10.2). */
        const val MAX_CHILDREN = TreeBuckets.MAX_CHILDREN

        /** What [item] weighs in a legacy browser's list: its media ID and the texts it shows (§10.2). */
        private fun weightOf(item: MediaItem): Int = item.mediaMetadata.let {
            TreeBuckets.weight(item.mediaId.length, it.title, it.artist, it.albumTitle, it.artworkUri?.toString()?.length ?: 0)
        }

        /** The number of characters of [value] in decimal. */
        private fun digits(value: Long): Int {
            if (value < 0) return digits(-value) + 1
            var count = 1
            var rest = value
            while (rest >= 10) {
                rest /= 10
                count++
            }
            return count
        }

        /** How many lists' outlines and bucket layouts are kept: the Songs list and the nodes browsed last. */
        private const val KEPT_LISTS = 4

        /** A node plays from its first song: no song of this ID exists, so the request starts at row 0. */
        private const val NO_SONG = -1L

        private const val SEARCH_ARTISTS = 100
        private const val SEARCH_ALBUMS = 100
        private const val SEARCH_SONGS = 300

        /** As long as a list read waits for the first sync (docs/library.md §1.4). */
        private const val READY_TIMEOUT_MS = 30_000L
    }
}
