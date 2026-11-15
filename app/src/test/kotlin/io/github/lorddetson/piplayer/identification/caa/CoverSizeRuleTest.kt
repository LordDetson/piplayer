package io.github.lorddetson.piplayer.identification.caa

import io.github.lorddetson.piplayer.library.details.PictureProbe
import io.github.lorddetson.piplayer.library.details.PictureSize
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** TI §11.1 "Cover size rule", with the cases where the current cover's size is not known. */
class CoverSizeRuleTest {

    /** Answers [embedded] for every file and counts the header reads. */
    private class FakeProbe(private val embedded: PictureSize?) : PictureProbe {
        val reads = mutableListOf<String>()
        override suspend fun embedded(uri: String): PictureSize? = embedded.also { reads += uri }
        override suspend fun hasThumbnail(uri: String): Boolean = error("The size rule never asks for a thumbnail")
    }

    @Test
    fun `smaller means a shorter short side`() {
        assertThat(CoverSizeRule.smaller(PictureSize(500, 500), PictureSize(600, 600))).isTrue()
        assertThat(CoverSizeRule.smaller(PictureSize(2000, 500), PictureSize(600, 600))).isTrue()
        assertThat(CoverSizeRule.smaller(PictureSize(1200, 1000), PictureSize(1000, 1200))).isFalse()
        assertThat(CoverSizeRule.smaller(PictureSize(1500, 1400), PictureSize(1400, 3000))).isFalse()
        assertThat(CoverSizeRule.smaller(PictureSize(1200, 1200), PictureSize(500, 500))).isFalse()
    }

    @Test
    fun `a proposed cover shorter than a known current one starts unticked, an equal or larger one ticked`() = runTest {
        val probe = FakeProbe(null)
        val current = CurrentCover.Known(PictureSize(1000, 800))

        assertThat(CoverSizeRule.startsTicked(PictureSize(500, 500), current, probe)).isFalse()
        assertThat(CoverSizeRule.startsTicked(PictureSize(800, 900), current, probe)).isTrue()
        assertThat(CoverSizeRule.startsTicked(PictureSize(1200, 1200), current, probe)).isTrue()
        assertThat(probe.reads).isEmpty()
    }

    @Test
    fun `with no cover now the proposed cover starts ticked`() = runTest {
        assertThat(CoverSizeRule.startsTicked(PictureSize(250, 250), CurrentCover.None, FakeProbe(null))).isTrue()
    }

    @Test
    fun `a system thumbnail of unknown size counts as ticked`() = runTest {
        val probe = FakeProbe(PictureSize(3000, 3000))
        assertThat(CoverSizeRule.startsTicked(PictureSize(250, 250), CurrentCover.SystemThumbnail, probe)).isTrue()
        assertThat(probe.reads).isEmpty()
    }

    @Test
    fun `an embedded picture of unknown size has its header read on demand`() = runTest {
        val probe = FakeProbe(PictureSize(600, 600))
        val current = CurrentCover.Embedded("content://media/external/audio/media/7")

        assertThat(CoverSizeRule.startsTicked(PictureSize(500, 500), current, probe)).isFalse()
        assertThat(CoverSizeRule.startsTicked(PictureSize(1200, 1200), current, probe)).isTrue()
        assertThat(probe.reads).containsExactly(current.uri, current.uri)
    }

    @Test
    fun `an embedded picture whose header cannot be read counts as no cover`() = runTest {
        val probe = FakeProbe(null)
        val current = CurrentCover.Embedded("content://media/external/audio/media/8")

        assertThat(CoverSizeRule.startsTicked(PictureSize(250, 250), current, probe)).isTrue()
        assertThat(probe.reads).containsExactly(current.uri)
    }

    @Test
    fun `a proposed cover of unknown size starts unticked over a cover of known size and ticked over none`() = runTest {
        val probe = FakeProbe(PictureSize(100, 100))

        assertThat(CoverSizeRule.startsTicked(null, CurrentCover.Known(PictureSize(100, 100)), probe)).isFalse()
        assertThat(CoverSizeRule.startsTicked(null, CurrentCover.None, probe)).isTrue()
        assertThat(probe.reads).isEmpty()
    }

    @Test
    fun `a proposed cover of unknown size starts ticked over a system thumbnail, whose size is not known either`() = runTest {
        val probe = FakeProbe(PictureSize(3000, 3000))

        assertThat(CoverSizeRule.startsTicked(null, CurrentCover.SystemThumbnail, probe)).isTrue()
        assertThat(probe.reads).isEmpty()
    }

    @Test
    fun `a proposed cover of unknown size has the embedded header read, unticked over a readable picture`() = runTest {
        val probe = FakeProbe(PictureSize(100, 100))
        val current = CurrentCover.Embedded("content://media/external/audio/media/9")

        assertThat(CoverSizeRule.startsTicked(null, current, probe)).isFalse()
        assertThat(probe.reads).containsExactly(current.uri)
    }

    @Test
    fun `a proposed cover of unknown size starts ticked over an embedded picture whose header cannot be read`() = runTest {
        val probe = FakeProbe(null)
        val current = CurrentCover.Embedded("content://media/external/audio/media/10")

        assertThat(CoverSizeRule.startsTicked(null, current, probe)).isTrue()
        assertThat(probe.reads).containsExactly(current.uri)
    }
}
