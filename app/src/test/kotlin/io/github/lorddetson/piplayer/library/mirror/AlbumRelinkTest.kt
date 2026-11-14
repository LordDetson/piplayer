package io.github.lorddetson.piplayer.library.mirror

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** References of an album follow its tracks to a new key (docs/library.md §15.3). */
class AlbumRelinkTest {

    @Test
    fun `an album whose tracks all moved takes its references to the new key`() {
        val moves = AlbumRelink.moves(
            referenced = setOf("old"),
            present = setOf("new"),
            members = mapOf("old" to setOf(1L, 2L, 3L)),
            albumsOf = mapOf(1L to setOf("new"), 2L to setOf("new"), 3L to setOf("new")),
        )

        assertThat(moves).isEqualTo(mapOf("old" to "new"))
    }

    @Test
    fun `more than half of the present members is enough, and members that are gone count for nothing`() {
        val moves = AlbumRelink.moves(
            referenced = setOf("old"),
            present = setOf("new", "other"),
            // 4 and 5 are gone: of the three present, two are on "new".
            members = mapOf("old" to setOf(1L, 2L, 3L, 4L, 5L)),
            albumsOf = mapOf(1L to setOf("new"), 2L to setOf("new"), 3L to setOf("other")),
        )

        assertThat(moves).isEqualTo(mapOf("old" to "new"))
    }

    @Test
    fun `a split in two halves moves nothing`() {
        val moves = AlbumRelink.moves(
            referenced = setOf("old"),
            present = setOf("a", "b"),
            members = mapOf("old" to setOf(1L, 2L, 3L, 4L)),
            albumsOf = mapOf(1L to setOf("a"), 2L to setOf("a"), 3L to setOf("b"), 4L to setOf("b")),
        )

        assertThat(moves).isEmpty()
    }

    @Test
    fun `a split in three without a majority moves nothing`() {
        val moves = AlbumRelink.moves(
            referenced = setOf("old"),
            present = setOf("a", "b", "c"),
            members = mapOf("old" to setOf(1L, 2L, 3L, 4L, 5L)),
            albumsOf = mapOf(1L to setOf("a"), 2L to setOf("a"), 3L to setOf("b"), 4L to setOf("b"), 5L to setOf("c")),
        )

        assertThat(moves).isEmpty()
    }

    @Test
    fun `an album that still names some tracks keeps its references`() {
        val moves = AlbumRelink.moves(
            referenced = setOf("old"),
            present = setOf("old", "new"),
            members = mapOf("old" to setOf(1L, 2L, 3L)),
            albumsOf = mapOf(1L to setOf("new"), 2L to setOf("new"), 3L to setOf("old")),
        )

        assertThat(moves).isEmpty()
    }

    @Test
    fun `an album whose key did not change moves nothing`() {
        val moves = AlbumRelink.moves(
            referenced = setOf("same"),
            present = setOf("same"),
            members = mapOf("same" to setOf(1L, 2L)),
            albumsOf = mapOf(1L to setOf("same"), 2L to setOf("same")),
        )

        assertThat(moves).isEmpty()
    }

    @Test
    fun `an album without present members waits`() {
        val moves = AlbumRelink.moves(
            referenced = setOf("old", "unknown"),
            present = setOf("new"),
            members = mapOf("old" to setOf(1L, 2L)),
            albumsOf = emptyMap(),
        )

        assertThat(moves).isEmpty()
    }

    @Test
    fun `two albums that became one both move to it`() {
        val moves = AlbumRelink.moves(
            referenced = setOf("cd1", "cd2"),
            present = setOf("mbid:x"),
            members = mapOf("cd1" to setOf(1L, 2L), "cd2" to setOf(3L, 4L)),
            albumsOf = mapOf(1L to setOf("mbid:x"), 2L to setOf("mbid:x"), 3L to setOf("mbid:x"), 4L to setOf("mbid:x")),
        )

        assertThat(moves).isEqualTo(mapOf("cd1" to "mbid:x", "cd2" to "mbid:x"))
    }

    @Test
    fun `a copy in two albums counts for each`() {
        val moves = AlbumRelink.moves(
            referenced = setOf("old"),
            present = setOf("new", "elsewhere"),
            members = mapOf("old" to setOf(1L, 2L)),
            albumsOf = mapOf(1L to setOf("new", "elsewhere"), 2L to setOf("new")),
        )

        assertThat(moves).isEqualTo(mapOf("old" to "new"))
    }
}
