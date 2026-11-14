package io.github.lorddetson.piplayer.playback

import io.github.lorddetson.piplayer.library.db.FolderKey
import io.github.lorddetson.piplayer.library.lists.GenreKeys
import io.github.lorddetson.piplayer.library.lists.ListKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The media IDs of the library tree's nodes (docs/playback.md §10.1, §10.2). */
class TreeNodeTest {

    /** Keys with every kind of character the library can give: separators, percent signs, Cyrillic, emoji. */
    private val keys = listOf(
        "ann|first|music/ann/first/",
        "a b&c=d#e/f!g%h+i?j",
        "Ёжик в тумане",
        "🎵 tunes",
        "!",
        "%41",
        "rel:5f2c-11",
    )

    @Test
    fun theFixedNodesHaveShortIds() {
        assertThat(TreeNode.Root.mediaId).isEqualTo("root")
        assertThat(TreeNode.Songs.mediaId).isEqualTo("songs")
        assertThat(TreeNode.Albums.mediaId).isEqualTo("albums")
        assertThat(TreeNode.Artists.mediaId).isEqualTo("artists")
        assertThat(TreeNode.Folders.mediaId).isEqualTo("folders")
        for (node in listOf(TreeNode.Root, TreeNode.Songs, TreeNode.Albums, TreeNode.Artists, TreeNode.Folders)) {
            assertThat(TreeNode.parse(node.mediaId)).isEqualTo(node)
        }
    }

    @Test
    fun albumsArtistsAndFoldersRoundTripWithAnyKey() {
        for (key in keys) {
            for (node in listOf(TreeNode.Album(key), TreeNode.Artist(key))) {
                val id = node.mediaId
                assertThat(id.substringAfter('/')).describedAs(id).matches("[A-Za-z0-9.*_%-]+")
                assertThat(TreeNode.parse(id)).describedAs(id).isEqualTo(node)
            }
        }
        assertThat(TreeNode.Album("ann").mediaId).isEqualTo("album/ann")
        assertThat(TreeNode.Artist("a b").mediaId).isEqualTo("artist/a%20b")

        for (folder in listOf(FolderKey("external_primary", ""), FolderKey("external_primary", "Music/Ёж & Co!/"), FolderKey("1234-ABCD", "a/b/"))) {
            val node = TreeNode.Folder(folder)
            assertThat(node.mediaId).startsWith("folder/")
            assertThat(TreeNode.parse(node.mediaId)).isEqualTo(node)
        }
        assertThat(TreeNode.Folder(FolderKey("external_primary", "Music/")).mediaId).isEqualTo("folder/external_primary%2FMusic%2F")
    }

    @Test
    fun bucketsNameTheirNodeTheSectionAndThePartFromTheSecondOn() {
        assertThat(TreeNode.Bucket(TreeNode.Songs, "A").mediaId).isEqualTo("songs!A")
        assertThat(TreeNode.Bucket(TreeNode.Songs, "A", 2).mediaId).isEqualTo("songs!A!2")
        assertThat(TreeNode.Bucket(TreeNode.Songs, "", 1).mediaId).isEqualTo("songs!")
        assertThat(TreeNode.Bucket(TreeNode.Songs, "", 3).mediaId).isEqualTo("songs!!3")
        assertThat(TreeNode.Bucket(TreeNode.Albums, "#").mediaId).isEqualTo("albums!%23")

        val nodes = listOf(TreeNode.Songs, TreeNode.Albums, TreeNode.Artists, TreeNode.Folders) +
            keys.flatMap { listOf(TreeNode.Album(it), TreeNode.Artist(it)) } + TreeNode.Folder(FolderKey("external_primary", "Music/!/"))
        for (parent in nodes) {
            for (bucket in listOf(
                TreeNode.Bucket(parent, "A"), TreeNode.Bucket(parent, "…", 4), TreeNode.Bucket(parent, "!"),
                TreeNode.Bucket(parent, "1999"), TreeNode.Bucket(parent, "3:00", 2), TreeNode.Bucket(parent, "", 7),
                TreeNode.Bucket(parent, "﷐A"),
            )) {
                assertThat(TreeNode.parse(bucket.mediaId)).describedAs(bucket.mediaId).isEqualTo(bucket)
            }
        }
    }

    @Test
    fun idsThatNameNoNodeAreRefused() {
        val malformed = listOf(
            "", "Root", "Songs", "song", "album", "album/", "artist/", "folder/", "album/a+b", "album/%ZZ", "album/%E0%A4",
            // Not the canonical form: "A" encoded, a space as "+".
            "album/%41", "artist/a%2Bb%2", "folder/external_primary", "folder/external_primary%2FMusic",
            "folder/%2FMusic%2F", "root!A", "songs!A!1", "songs!A!0", "songs!A!-2", "songs!A!x", "songs!A!2!3",
            "songs!A!B", "songs!!", "songs!!1", "songs!A!02", "albums!A!2147483648", "songs!a b",
            "q2/songs?key=&sort=title&rev=0&q=&start=1&index=0", "123", "123/a4", "content://media/external/audio/media/5",
        )
        for (id in malformed) {
            assertThat(TreeNode.parse(id)).describedAs(id).isNull()
        }
        // A bucket of a bucket is no node either.
        assertThat(TreeNode.parse("songs!A!2!B")).isNull()
    }

    @Test
    fun moreHoldsPlaylistsGenresAndFoldersWhoseIdsAreShort() {
        assertThat(TreeNode.More.mediaId).isEqualTo("more")
        assertThat(TreeNode.Playlists.mediaId).isEqualTo("playlists")
        assertThat(TreeNode.Genres.mediaId).isEqualTo("genres")
        for (node in listOf(TreeNode.More, TreeNode.Playlists, TreeNode.Genres, TreeNode.Folders)) {
            assertThat(TreeNode.parse(node.mediaId)).describedAs(node.mediaId).isEqualTo(node)
        }
    }

    @Test
    fun genresPlaylistsAndSmartListsRoundTrip() {
        for (key in keys + listOf(GenreKeys.NONE, "hip hop", "электроника")) {
            val node = TreeNode.Genre(key)
            assertThat(node.mediaId).startsWith("genre/")
            assertThat(node.mediaId.substringAfter('/')).describedAs(node.mediaId).matches("[A-Za-z0-9.*_%-]+")
            assertThat(TreeNode.parse(node.mediaId)).describedAs(node.mediaId).isEqualTo(node)
        }
        assertThat(TreeNode.Genre(GenreKeys.NONE).mediaId).isEqualTo("genre/%21none")
        for (id in listOf(0L, 1L, 42L, Long.MAX_VALUE)) {
            assertThat(TreeNode.Playlist(id).mediaId).isEqualTo("playlist/$id")
            assertThat(TreeNode.parse("playlist/$id")).isEqualTo(TreeNode.Playlist(id))
        }
        assertThat(TreeNode.TREE_SMART).containsExactly(ListKind.FAVORITES, ListKind.RECENTLY_ADDED, ListKind.RECENTLY_PLAYED, ListKind.MOST_PLAYED)
        for (kind in TreeNode.TREE_SMART) {
            assertThat(TreeNode.parse("smart/${kind.token}")).isEqualTo(TreeNode.SmartList(kind))
        }
        assertThat(TreeNode.SmartList(ListKind.FAVORITES).mediaId).isEqualTo("smart/favorites")
        assertThat(TreeNode.SmartList(ListKind.MOST_PLAYED).mediaId).isEqualTo("smart/most")
        // They have buckets like every list.
        for (parent in listOf(TreeNode.Genres, TreeNode.Playlists, TreeNode.Genre("rock"), TreeNode.Genre(GenreKeys.NONE), TreeNode.Playlist(7), TreeNode.SmartList(ListKind.FAVORITES))) {
            for (bucket in listOf(TreeNode.Bucket(parent, "R"), TreeNode.Bucket(parent, "", 3))) {
                assertThat(TreeNode.parse(bucket.mediaId)).describedAs(bucket.mediaId).isEqualTo(bucket)
            }
        }
    }

    @Test
    fun malformedGenresPlaylistsAndSmartListsAreRefused() {
        val malformed = listOf(
            "More", "genre", "genre/", "genre/a+b", "genre/%41", "playlist", "playlist/", "playlist/abc", "playlist/-1",
            "playlist/007", "playlist/1.5", "playlist/ 1", "playlist/99999999999999999999", "smart", "smart/", "smart/missing",
            "smart/songs", "smart/genre", "smart/Favorites", "smart/playlist",
        )
        for (id in malformed) {
            assertThat(TreeNode.parse(id)).describedAs(id).isNull()
        }
    }
}
