package io.github.lorddetson.piplayer.library.sync

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class IndexPathsTest {

    @Test
    fun `the root of a volume is the empty path, however the index gives it`() {
        // MediaStore gives "/" for a file in a volume's root.
        assertThat(IndexPaths.relativePath("/")).isEqualTo("")
        assertThat(IndexPaths.relativePath(null)).isEqualTo("")
        assertThat(IndexPaths.relativePath("")).isEqualTo("")
    }

    @Test
    fun `a folder keeps its path`() {
        assertThat(IndexPaths.relativePath("Music/Rock/")).isEqualTo("Music/Rock/")
    }
}
