package io.github.lorddetson.piplayer.playback

import io.github.lorddetson.piplayer.library.lists.ListKind
import io.github.lorddetson.piplayer.library.lists.SongQuery
import io.github.lorddetson.piplayer.library.songs.Song

/** The order passed by the visible collection list, with only the first copy of a file kept. */
internal fun collectionSongs(queries: List<SongQuery>, load: (SongQuery) -> List<Song>): List<Song> {
    val seen = HashSet<Long>()
    return buildList {
        for (query in queries) for (song in load(query)) {
            if (seen.add(song.id)) add(song)
        }
    }
}

internal fun collectionRequests(requests: List<String>?): List<SongQuery>? {
    if (requests.isNullOrEmpty()) return null
    return requests.map { value ->
        val query = QueueRequest.parse(value)?.list ?: return null
        if (!query.kind.smart && query.kind !in collectionKinds) return null
        query
    }
}

private val collectionKinds = setOf(ListKind.ARTIST, ListKind.PLAYLIST, ListKind.TREE)
