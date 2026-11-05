package io.github.lorddetson.piplayer.tagging.contentkey

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * Golden keys of real files in the formats whose walks need no TagLib; MP3 and ADTS are in the instrumented test.
 * Every expected key comes from scripts/content_key_reference.py. A change here means every library must be re-keyed
 * (TI §3.1), so never update one of these to make a test pass.
 */
class ContentKeyGoldenTest {

    private fun tags(name: String) = File("../nativelib/src/androidTest/assets/tags/$name")

    private fun asset(name: String) = File("src/androidTest/assets/$name")

    private fun check(file: File, token: String, length: Long, expected: String) {
        val bytes = file.readBytes()
        val (key, located) = keyOf(bytes, PayloadLocator.sniff(ArraySource(bytes)))

        assertThat(located.payload.format.token).isEqualTo(token)
        assertThat(located.payload.length).isEqualTo(length)
        assertThat(key).isEqualTo(expected)
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        "aac.m4a, mp4, 8188, acc5a1802f5c4ba4dbd1b4242adab806df4a0d6ebeb8538c95cf8914c257ef75",
        "alac.m4a, mp4, 17024, c3a167b5121ede23ff9906542df1fd32856ee232fcf6a0ed08b62f7c952a39ca",
        "cyrillic.flac, flac, 15548, f014373708077fd0b79021ed0b4ee3cf704515ae5102eb3d664f21be4c5f461f",
        "vorbis-comment.flac, flac, 15548, f014373708077fd0b79021ed0b4ee3cf704515ae5102eb3d664f21be4c5f461f",
        "opus.opus, opus, 48312, 042eefebf7d973f2d0bbe675f1cc7be392adebddc5e16df8af0dc8f89e5fb76e",
        "vorbis.ogg, ogg-vorbis, 44100, 3e55fe7e437ddd0ba480f9b2f8fabd81417f70f8b1484db0ccd8660943488cdc",
        "riff-info.wav, wav, 176400, 21546741e6ca832e1886cecf7eddd25a44f8e991e40d02d3905488f3018b44b8",
    )
    fun `tag fixtures`(name: String, token: String, length: Long, expected: String) {
        check(tags(name), token, length, expected)
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        "contentkey/long.flac, flac, 375950, 6213a2f8f017e5e5fd65c7b41d245abf4ec1836af60933352e09b7275f29ac42",
        "contentkey/moov-first.m4a, mp4, 240887, 52a12c98f017d1be450f676ac23ae455ea2c04d09f6bb5353013f69b5a968b24",
        "contentkey/moov-last.m4a, mp4, 240887, 52a12c98f017d1be450f676ac23ae455ea2c04d09f6bb5353013f69b5a968b24",
        "contentkey/long.ogg, ogg-vorbis, 960000, 6f79a40e1cd7022253f3690627e43a4f48a6c3458ce23ca0b6c97c8e189d574d",
        "contentkey/long-comments-short.ogg, ogg-vorbis, 960000, 6f79a40e1cd7022253f3690627e43a4f48a6c3458ce23ca0b6c97c8e189d574d",
        "contentkey/long-comments-small-pages.ogg, ogg-vorbis, 960000, 6f79a40e1cd7022253f3690627e43a4f48a6c3458ce23ca0b6c97c8e189d574d",
        "contentkey/long-comments-long.ogg, ogg-vorbis, 960000, 6f79a40e1cd7022253f3690627e43a4f48a6c3458ce23ca0b6c97c8e189d574d",
        "contentkey/long-cover.ogg, ogg-vorbis, 960000, 6f79a40e1cd7022253f3690627e43a4f48a6c3458ce23ca0b6c97c8e189d574d",
        "contentkey/long.opus, opus, 960312, 22fc3f792be4d4b4bd1d64eb4297b0527210ba277e1f8adb6ec403a9ec19cd30",
        "contentkey/long-comments-short.opus, opus, 960312, 22fc3f792be4d4b4bd1d64eb4297b0527210ba277e1f8adb6ec403a9ec19cd30",
        "contentkey/long-comments-small-pages.opus, opus, 960312, 22fc3f792be4d4b4bd1d64eb4297b0527210ba277e1f8adb6ec403a9ec19cd30",
        "contentkey/long-comments-long.opus, opus, 960312, 22fc3f792be4d4b4bd1d64eb4297b0527210ba277e1f8adb6ec403a9ec19cd30",
        "contentkey/long-cover.opus, opus, 960312, 22fc3f792be4d4b4bd1d64eb4297b0527210ba277e1f8adb6ec403a9ec19cd30",
        "contentkey/chained.ogg, file, 189174, 27b453503465b68a150fd0037ce3d45e58abc7f96d7f96fcd444f8d925adf9c0",
        "playback/opus-48000-stereo.webm, file, 37959, d6d532ade1f5f53c05975c2d5e18bcacf18bff012698f605ebf5725e6941bd42",
        "playback/amr-nb-8000-mono.amr, file, 3238, ccd76fd068bb757dc36d5f736c8340588debfa33464091a6ff64980e110c784f",
        "decoding/flac-48000-16bit-5.1.flac, flac, 258610, 42d86276d62e4a0d90543e0077f2894b4b59d99fc8efcdfc0a4dda588ca7f859",
        "decoding/flac-44100-16bit-mono-truncated.flac, flac, 40204, 2fd0762421283523753b900f3e60ae128b3a786118b76a64c47130b35ba2be94",
        "decoding/wav-44100-16bit-stereo.wav, wav, 352800, 6f32e7112234aa9da3bc2c026242fcdb9e088409f53d247bdca98337a621e8cd",
    )
    fun `test assets`(name: String, token: String, length: Long, expected: String) {
        check(asset(name), token, length, expected)
    }
}
