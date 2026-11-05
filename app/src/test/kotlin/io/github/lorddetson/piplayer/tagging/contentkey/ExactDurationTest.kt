package io.github.lorddetson.piplayer.tagging.contentkey

import io.github.lorddetson.piplayer.nativelib.taglib.FrameCountHeader
import io.github.lorddetson.piplayer.nativelib.taglib.MpegLayout
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** When a header's length is trusted and when the frames are walked (TI §3.1, §11.1). */
class ExactDurationTest {

    private fun located(format: KeyFormat, length: Long = 1_000_000, cut: Boolean = false, ogg: OggLength? = null) =
        Located(Payload(format, length, listOf(ByteSpan(0, length)), emptyList()), declaredBeyondEnd = cut, ogg = ogg)

    @Nested
    inner class Mp3 {
        /** 1 MB of frames plus a service frame of 500 bytes. */
        private fun layout(header: FrameCountHeader?) = MpegLayout(
            adts = false,
            firstFrame = 0,
            firstFrameLength = 500,
            serviceFrame = header != null,
            lastFrame = 1_000_100,
            lastFrameLength = 400,
            audioEnd = 1_000_500,
            frameCount = header,
            firstFrameBitrateKbps = 128,
        )

        private fun plan(header: FrameCountHeader?, taglibMs: Int?) =
            DurationRules.plan(AudioContainer.MPEG, layout(header), taglibMs, located(KeyFormat.MP3))

        @Test
        fun `no frame count header is always walked, whatever TagLib estimates`() {
            assertThat(plan(null, 62_500)).isEqualTo(DurationPlan(null, DurationCheck.WALK))
        }

        @Test
        fun `a Xing byte count within 2 percent is trusted`() {
            val within = FrameCountHeader(false, 2_000, 1_019_000)

            assertThat(plan(within, 52_245)).isEqualTo(DurationPlan(52_245, DurationCheck.NONE))
        }

        @Test
        fun `a Xing byte count off by more than 2 percent is walked`() {
            val over = FrameCountHeader(false, 2_000, 1_021_000)
            val under = FrameCountHeader(false, 2_000, 979_000)

            assertThat(plan(over, 52_245).check).isEqualTo(DurationCheck.WALK)
            assertThat(plan(under, 52_245).check).isEqualTo(DurationCheck.WALK)
            assertThat(plan(over, 52_245).headerMs).isNull()
        }

        @Test
        fun `a byte count that leaves out or counts the service frame is trusted on a tiny file`() {
            val tiny = layout(FrameCountHeader(false, 20, 8_000)).copy(firstFrameLength = 400, lastFrame = 8_000, audioEnd = 8_400)
            val noFrame = DurationRules.plan(AudioContainer.MPEG, tiny, 500, located(KeyFormat.MP3, 8_000))

            assertThat(noFrame).isEqualTo(DurationPlan(500, DurationCheck.NONE))
        }

        @Test
        fun `a CBR header agreeing with the payload is trusted`() {
            // 1 MB at 128 kbit/s is 62 500 ms.
            val info = FrameCountHeader(true, 2_393, 1_000_500)

            assertThat(plan(info, 62_510)).isEqualTo(DurationPlan(62_510, DurationCheck.NONE))
            assertThat(plan(info, 64_499)).isEqualTo(DurationPlan(64_499, DurationCheck.NONE))
        }

        @Test
        fun `a CBR header off by more than 2 s is walked`() {
            val info = FrameCountHeader(true, 2_393, 1_000_500)

            assertThat(plan(info, 64_501).check).isEqualTo(DurationCheck.WALK)
            assertThat(plan(info, 60_499).check).isEqualTo(DurationCheck.WALK)
        }

        @Test
        fun `the 2 s rule is only for CBR headers`() {
            val xing = FrameCountHeader(false, 2_393, 1_000_500)

            assertThat(plan(xing, 90_000)).isEqualTo(DurationPlan(90_000, DurationCheck.NONE))
        }

        @Test
        fun `a header with no length is walked`() {
            assertThat(plan(FrameCountHeader(false, 2_393, 1_000_500), 0).check).isEqualTo(DurationCheck.WALK)
            assertThat(plan(FrameCountHeader(false, 2_393, 1_000_500), null).check).isEqualTo(DurationCheck.WALK)
        }
    }

    @Test
    fun `raw ADTS is always walked`() {
        val adts = MpegLayout(true, 0, 300, false, 900, 100, 1_000, null, 64)

        assertThat(DurationRules.plan(AudioContainer.MPEG, adts, 2_000, located(KeyFormat.ADTS)))
            .isEqualTo(DurationPlan(null, DurationCheck.WALK))
    }

    @Test
    fun `FLAC trusts STREAMINFO and checks the end of its last frame`() {
        assertThat(DurationRules.plan(AudioContainer.FLAC, null, 180_000, located(KeyFormat.FLAC)))
            .isEqualTo(DurationPlan(180_000, DurationCheck.FLAC_END))
    }

    @Test
    fun `FLAC without a sample count is walked`() {
        assertThat(DurationRules.plan(AudioContainer.FLAC, null, 0, located(KeyFormat.FLAC)))
            .isEqualTo(DurationPlan(null, DurationCheck.WALK))
    }

    @Test
    fun `MP4 and WAV trust their headers unless the payload cannot support them`() {
        assertThat(DurationRules.plan(AudioContainer.MP4, null, 5_000, located(KeyFormat.MP4)))
            .isEqualTo(DurationPlan(5_000, DurationCheck.NONE))
        assertThat(DurationRules.plan(AudioContainer.WAV, null, 5_000, located(KeyFormat.WAV)))
            .isEqualTo(DurationPlan(5_000, DurationCheck.NONE))
        assertThat(DurationRules.plan(AudioContainer.MP4, null, 5_000, located(KeyFormat.MP4, cut = true)))
            .isEqualTo(DurationPlan(null, DurationCheck.WALK))
        assertThat(DurationRules.plan(AudioContainer.WAV, null, 5_000, located(KeyFormat.WAV, cut = true)))
            .isEqualTo(DurationPlan(null, DurationCheck.WALK))
    }

    @Test
    fun `Ogg takes the final granule, less the Opus pre-skip, and is never walked`() {
        val vorbis = located(KeyFormat.OGG_VORBIS, 441_000, ogg = OggLength(441_000, 44_100, 0))
        val opus = located(KeyFormat.OPUS, 960_312, ogg = OggLength(960_312, 48_000, 312))

        assertThat(DurationRules.plan(AudioContainer.OGG, null, 1, vorbis)).isEqualTo(DurationPlan(10_000, DurationCheck.NONE))
        assertThat(DurationRules.plan(AudioContainer.OGG, null, 1, opus)).isEqualTo(DurationPlan(20_000, DurationCheck.NONE))
    }

    @Test
    fun `the Ogg length is rounded to the nearest millisecond and survives a huge granule`() {
        val ogg = located(KeyFormat.OPUS, 72, ogg = OggLength(72, 48_000, 0))
        val huge = located(KeyFormat.OGG_VORBIS, Long.MAX_VALUE, ogg = OggLength(Long.MAX_VALUE, 48_000, 0))

        assertThat(DurationRules.plan(AudioContainer.OGG, null, null, ogg).headerMs).isEqualTo(2)
        assertThat(DurationRules.plan(AudioContainer.OGG, null, null, huge).headerMs).isGreaterThan(0)
    }

    @Test
    fun `an Ogg file the walk gave up on, chained for example, takes TagLib's length`() {
        assertThat(DurationRules.plan(AudioContainer.OGG, null, 7_000, located(KeyFormat.FILE)))
            .isEqualTo(DurationPlan(7_000, DurationCheck.NONE))
    }

    @Test
    fun `any other format takes TagLib's length, or a walk when it has none`() {
        assertThat(DurationRules.plan(AudioContainer.OTHER, null, 3_000, located(KeyFormat.FILE)))
            .isEqualTo(DurationPlan(3_000, DurationCheck.NONE))
        assertThat(DurationRules.plan(AudioContainer.OTHER, null, null, located(KeyFormat.FILE)))
            .isEqualTo(DurationPlan(null, DurationCheck.WALK))
    }

    @Nested
    inner class Clock {
        @Test
        fun `no samples, no end`() {
            assertThat(SampleClock().endUs).isNull()
        }

        @Test
        fun `the end is the last time plus a frame`() {
            val clock = SampleClock()
            listOf(0L, 26_122, 52_244, 78_366).forEach(clock::observe)

            assertThat(clock.endUs).isEqualTo(78_366 + 26_122)
        }

        @Test
        fun `one sample ends where it starts`() {
            val clock = SampleClock().apply { observe(0) }

            assertThat(clock.endUs).isEqualTo(0)
        }

        @Test
        fun `a repeated last frame changes nothing`() {
            val clock = SampleClock()
            listOf(0L, 100, 200, 200).forEach(clock::observe)

            assertThat(clock.endUs).isEqualTo(300)
        }
    }
}
