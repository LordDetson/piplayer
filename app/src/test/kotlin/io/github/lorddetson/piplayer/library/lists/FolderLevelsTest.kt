package io.github.lorddetson.piplayer.library.lists

import io.github.lorddetson.piplayer.library.sort.SortKeys
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Locale

/** The levels of the Folders tab and the folder pages (docs/library.md §6.1, §6.3, §6.4). */
class FolderLevelsTest {

    private val primary = "external_primary"
    private val card = "1234-5678"

    private val levels = FolderLevels(
        listOf(
            FolderSongs(primary, "Music/Rock/Album B/", 3),
            FolderSongs(primary, "Music/Rock/album a/CD1/", 2),
            FolderSongs(primary, "Music/Rock/album a/CD2/", 2),
            FolderSongs(primary, "Music/Ёлки/", 1),
            FolderSongs(primary, "Music/", 4),
            FolderSongs(primary, "Download/", 1),
            FolderSongs(primary, "", 1),
        ),
    )

    private fun List<FolderEntry>.paths() = map { it.relativePath }

    @Test
    fun `with one volume the top level is its root`() {
        val top = levels.top()

        assertThat(top.volumeLevel).isFalse()
        assertThat(top.folder).isEqualTo(primary to "")
        assertThat(top.entries.paths()).containsExactly("Download/", "Music/")
    }

    @Test
    fun `with more volumes the top level lists them, the primary one first`() {
        val two = FolderLevels(listOf(FolderSongs(card, "Music/", 2), FolderSongs(primary, "Music/", 1)))

        val top = two.top()

        assertThat(top.volumeLevel).isTrue()
        assertThat(top.folder).isNull()
        assertThat(top.entries.map { it.volume to it.relativePath }).containsExactly(primary to "", card to "")
        assertThat(top.entries.map { it.songs }).containsExactly(1, 2)
    }

    @Test
    fun `a folder lists its subfolders, also those without songs of their own, with all the songs below them`() {
        val rock = levels.children(primary, "Music/Rock/")

        assertThat(rock.paths()).containsExactly("Music/Rock/album a/", "Music/Rock/Album B/")
        assertThat(rock.map { it.name }).containsExactly("album a", "Album B")
        assertThat(rock.map { it.songs }).containsExactly(4, 3)
        assertThat(levels.children(primary, "Music/").map { it.songs }).containsExactly(7, 1)
    }

    @Test
    fun `names sort by their normalized form, and reverse turns the level round`() {
        assertThat(levels.children(primary, "Music/").paths()).containsExactly("Music/Rock/", "Music/Ёлки/")
        assertThat(levels.children(primary, "Music/", reversed = true).paths()).containsExactly("Music/Ёлки/", "Music/Rock/")
    }

    @Test
    fun `a folder exists while any song lies in it or below it`() {
        assertThat(levels.contains(primary, "Music/Rock/album a/")).isTrue()
        assertThat(levels.contains(primary, "")).isTrue()
        assertThat(levels.contains(primary, "Music/Pop/")).isFalse()
        assertThat(levels.contains(card, "")).isFalse()
    }

    @Test
    fun `a tree plays the folder first, then each subfolder in name order, each with its own songs first`() {
        assertThat(levels.treeOrder(primary, "Music/")).containsExactly(
            "Music/",
            "Music/Rock/",
            "Music/Rock/album a/",
            "Music/Rock/album a/CD1/",
            "Music/Rock/album a/CD2/",
            "Music/Rock/Album B/",
            "Music/Ёлки/",
        )
        assertThat(levels.treeOrder(primary, "").first()).isEqualTo("")
        assertThat(levels.treeOrder(primary, "Music/Pop/")).isEmpty()
    }

    @Test
    fun `a search finds every folder whose name holds all the words, at any depth`() {
        assertThat(levels.search("ALBUM").paths()).containsExactly("Music/Rock/album a/", "Music/Rock/Album B/")
        assertThat(levels.search("cd 3").paths()).isEmpty()
        assertThat(levels.search("cd2").paths()).containsExactly("Music/Rock/album a/CD2/")
        assertThat(levels.search("елки").paths()).containsExactly("Music/Ёлки/")
        assertThat(levels.search("  ").paths()).isEmpty()
    }

    @Test
    fun `subfolders sort by the collator of the app's language and carry their letters`() {
        val names = listOf("Жуки", "Ёлка", "Abba", "Аквариум", "10 Years", "2 Unlimited")
        val mixed = FolderLevels(names.map { FolderSongs(primary, "Music/$it/", 1) })
        try {
            SortKeys.use(listOf(Locale.forLanguageTag("ru")))

            val children = mixed.children(primary, "Music/")

            assertThat(children.map { it.name }).containsExactly("2 Unlimited", "10 Years", "Аквариум", "Ёлка", "Жуки", "Abba")
            assertThat(children.map { it.section }).containsExactly("#", "#", "А", "Е", "Ж", "A")
        } finally {
            SortKeys.use(listOf(Locale.ENGLISH))
        }
    }
}
