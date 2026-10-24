package io.github.lorddetson.piplayer.playback

import io.github.lorddetson.piplayer.TestFiles
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin
import org.json.JSONArray
import org.json.JSONObject

/**
 * An album of the gapless fixture (docs/playback.md §8.2), made by scripts/generate_gapless_fixtures.py: one continuous
 * piece cut into songs of [frames] samples at [rate]. [late] says how many samples late each song ends (the AAC that
 * ffmpeg writes, a known limitation); [md5] is the piece's own audio, which a lossless album reproduces.
 */
class GaplessAlbum(
    val name: String,
    val rate: Int,
    val files: List<String>,
    val frames: List<Int>,
    val md5: String?,
    val late: List<Int>,
) {

    /** The piece, in samples per channel. */
    val length: Int get() = frames.sum()

    override fun toString() = name

    companion object {
        fun all(): List<GaplessAlbum> {
            val fixture = JSONObject(TestFiles.assets.open("gapless/fixture.json").reader().use { it.readText() })
            check(fixture.getDouble("seconds") == GaplessSignal.SECONDS) { "The fixture and GaplessSignal differ" }
            val albums = fixture.getJSONObject("albums")
            return albums.keys().asSequence().map { name ->
                val album = albums.getJSONObject(name)
                GaplessAlbum(
                    name = name,
                    rate = album.getInt("rate"),
                    files = album.getJSONArray("files").strings(),
                    frames = album.getJSONArray("frames").ints(),
                    md5 = album.optString("md5").ifEmpty { null },
                    late = album.optJSONArray("late")?.ints().orEmpty(),
                )
            }.toList()
        }

        private fun JSONArray.strings() = List(length()) { getString(it) }

        private fun JSONArray.ints() = List(length()) { getInt(it) }
    }
}

/** The piece's left channel, in 16-bit units, as scripts/generate_gapless_fixtures.py computes it; keep them in step. */
object GaplessSignal {

    const val SECONDS = 12.0

    fun left(t: Double): Double =
        (0.25 * logSweep(t, 80.0, 6000.0) + 0.10 * sin(2 * PI * 443.7 * t) + 0.10 * chirp(t)) * 32767

    private fun logSweep(t: Double, from: Double, to: Double): Double {
        val k = ln(to / from) / SECONDS
        return sin(2 * PI * from * (exp(k * t) - 1) / k)
    }

    /** 2 kHz to 12 kHz each half second, its phase continuous. */
    private fun chirp(t: Double): Double {
        val period = 0.5
        val cycles = floor(t / period)
        val rest = t / period - cycles
        return sin(2 * PI * (2000 * t + 10000 * period * (cycles + rest * rest) / 2))
    }
}
