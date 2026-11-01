package io.github.lorddetson.piplayer.ui.settings

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FolderTreeTest {

    private fun flat(nodes: List<FolderNode>, depth: Int = 0): List<String> =
        nodes.flatMap { listOf("  ".repeat(depth) + it.name + " " + it.relativePath) + flat(it.children, depth + 1) }

    @Test
    fun `folders with audio come with their parents, sorted by name`() {
        val tree = FolderTree.of(listOf("Music/Б/CD2/", "Music/Б/CD1/", "Music/A/", "Podcasts/", "Music/Б/"))

        assertThat(flat(tree)).containsExactly(
            "Music Music/",
            "  A Music/A/",
            "  Б Music/Б/",
            "    CD1 Music/Б/CD1/",
            "    CD2 Music/Б/CD2/",
            "Podcasts Podcasts/",
        )
    }

    @Test
    fun `files in the volume's root make no folder of their own`() {
        assertThat(flat(FolderTree.of(listOf("", "Music/")))).containsExactly("Music Music/")
    }
}
