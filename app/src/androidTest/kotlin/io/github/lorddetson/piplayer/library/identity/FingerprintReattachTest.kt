package io.github.lorddetson.piplayer.library.identity

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.db.LibraryDatabase
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Step 4 of re-attachment on real fingerprints (docs/library.md §8.3): the platform's decoders and Chromaprint, and the
 * cache of the library database.
 */
@RunWith(AndroidJUnit4::class)
class FingerprintReattachTest {

    private val context = TestFiles.targetContext
    private val library = LibraryDatabase.open(context, file = null)
    private val made = mutableListOf<File>()

    @After
    fun close() {
        library.close()
        made.forEach { it.delete() }
    }

    /** 12 s of a tune: a new random note every quarter second, mono 16-bit at 22 050 Hz. */
    private fun tune(seed: Long): ShortArray {
        val rate = 22_050
        val random = Random(seed)
        val samples = ShortArray(rate * 12)
        var frequency = 440.0
        for (i in samples.indices) {
            if (i % (rate / 4) == 0) frequency = 200.0 + random.nextInt(1_800)
            samples[i] = (sin(2 * PI * frequency * i / rate) * 12_000).toInt().toShort()
        }
        return samples
    }

    private fun wav(name: String, samples: ShortArray): File {
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { data.putShort(it) }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data.capacity()); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(22_050); putInt(44_100)
            putShort(2); putShort(16)
            put("data".toByteArray()); putInt(data.capacity())
        }
        return File(context.cacheDir, name).apply { writeBytes(header.array() + data.array()) }.also { made += it }
    }

    private fun keyed(id: Long, key: String, file: File, folder: String) = KeyedFile(
        fileId = id, contentKey = key, place = Place("external_primary", folder, file.name), size = file.length(),
        durationMs = 12_000, settled = true, recordingMbid = null, format = "wav",
    )

    @Test
    fun aCopyWithOneChangedSampleAtAnotherPlaceTakesOverTheMissingIdentity() = runBlocking<Unit> {
        val original = tune(1)
        val first = wav("fp-original.wav", original)
        val touched = wav("fp-touched.wav", original.copyOf().also { it[it.size / 3] = (it[it.size / 3] + 1).toShort() })
        val other = wav("fp-other.wav", tune(2))
        val files = mapOf(1L to first, 2L to touched, 3L to other)
        val source = LibraryFingerprints(context, library, uriOf = { Uri.fromFile(files.getValue(it.fileId)) })

        // The cache holds the fingerprint of the old file, made while it was in the library.
        assertThat(source.compute(keyed(1, "old", first, "Music/Old/"))).isNotNull()
        val now = 1_800_000_000_000L
        val missing = Identity(7, "old", 12_000, null, Place("external_primary", "Music/Old/", first.name), 1, now - 9_000, now - 5_000)
        val candidates = listOf(keyed(2, "new", touched, "Music/New/"), keyed(3, "else", other, "Music/Else/"))
        val identities = listOf(
            missing,
            Identity(8, "new", 12_000, null, candidates[0].place, 1, now - 1_000, null),
            Identity(9, "else", 12_000, null, candidates[1].place, 1, now - 1_000, null),
        )

        val takeovers = FingerprintStep(source).takeovers(candidates, identities, holdingData = setOf(7L))

        assertThat(takeovers).containsExactly(Takeover(missingId = 7, file = candidates[0], replacedId = 8))
        assertThat(library.library().fingerprintKeys()).containsExactlyInAnyOrder("old", "new", "else")
    }
}
