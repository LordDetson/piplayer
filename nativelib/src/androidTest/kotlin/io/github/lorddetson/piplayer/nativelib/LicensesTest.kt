package io.github.lorddetson.piplayer.nativelib

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** Every APK that carries the native libraries also carries their licence texts and copyright notices. */
@RunWith(AndroidJUnit4::class)
class LicensesTest {

    private val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets

    @Test
    fun theLicenceTextsShipWithTheLibrary() {
        val expected = listOf(
            "THIRD_PARTY_NOTICES.md",
            "taglib-COPYING.LGPL",
            "utfcpp-LICENSE",
            "chromaprint-LICENSE.md",
            "kissfft-COPYING",
            "kissfft-BSD-3-Clause",
            "libc++-LICENSE.txt",
            "speexdsp-COPYING",
            "speexdsp-resampler-NOTICE.txt",
        )
        assertThat(assets.list("licenses")).contains(*expected.toTypedArray())
        expected.forEach { name ->
            val text = assets.open("licenses/$name").bufferedReader().readText()
            assertThat(text).describedAs(name).isNotBlank()
        }
    }

    @Test
    fun theSpeexdspNoticeCarriesTheWholeLicenceOfEachCompiledFile() {
        val notice = assets.open("licenses/speexdsp-resampler-NOTICE.txt").bufferedReader().readText()
        val files = listOf("libspeexdsp/resample.c", "include/speex/speex_resampler.h", "libspeexdsp/arch.h")
        files.forEach { file ->
            val section = notice.substringAfter("===== $file =====").substringBefore("=====")
            assertThat(section).describedAs(file).contains("Copyright", "Redistributions in binary form", "SUCH DAMAGE")
        }
    }
}
