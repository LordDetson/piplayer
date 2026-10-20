package io.github.lorddetson.piplayer.ui.player

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** Decoding embedded covers for the screens (§3.4). */
@RunWith(AndroidJUnit4::class)
class CoversTest {

    private fun Bitmap.pixel(x: Int, y: Int) = copy(Bitmap.Config.ARGB_8888, false).getPixel(x, y)

    @Test
    fun aSmallCoverKeepsItsSize() {
        val cover = Covers.decode(TestImages.png(64), maxSizePx = 1_080)!!

        assertThat(cover.width to cover.height).isEqualTo(64 to 64)
        assertThat(cover.pixel(32, 32)).isEqualTo(TestImages.BLUE)
    }

    @Test
    fun aLargeCoverIsScaledDownToTheLimit() {
        val cover = Covers.decode(TestImages.png(3_000), maxSizePx = 1_080)!!

        assertThat(cover.width to cover.height).isEqualTo(1_080 to 1_080)
        assertThat(cover.pixel(540, 540)).isEqualTo(TestImages.BLUE)
    }

    @Test
    fun anOblongCoverKeepsItsShape() {
        val cover = Covers.decode(TestImages.png(2_000, 1_000), maxSizePx = 1_080)!!

        assertThat(cover.width to cover.height).isEqualTo(1_080 to 540)
    }

    @Test
    fun bytesThatAreNoImageGiveNoCover() {
        assertThat(Covers.decode(ByteArray(1_000) { it.toByte() }, maxSizePx = 1_080)).isNull()
        assertThat(Covers.decode(ByteArray(0), maxSizePx = 1_080)).isNull()
    }
}
