package io.github.lorddetson.piplayer.library.identity

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.identification.fingerprint.DecodingException
import io.github.lorddetson.piplayer.identification.fingerprint.PipeTestProvider
import io.github.lorddetson.piplayer.identification.fingerprint.PipeTestProvider.Mode
import io.github.lorddetson.piplayer.library.db.LibraryDatabase
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The fingerprint cache with its own decoding source (TI §3.2): it opens the file through the content resolver, here a
 * test document provider of the debug build in a process of its own that hands out a reliable pipe. A pipe whose
 * writer fails after half a WAV file reads like a shorter file; only its descriptor tells, so neither lane may decode
 * it or cache the shortened fingerprint.
 */
@RunWith(AndroidJUnit4::class)
class ProviderPipeFingerprintTest {

    private val context = TestFiles.targetContext
    private val library = LibraryDatabase.open(context, file = null)

    @After
    fun close() = library.close()

    private fun fingerprints(mode: Mode) =
        LibraryFingerprints(context, library, uriOf = { PipeTestProvider.uri(context, mode) })

    private fun keyed(key: String) = KeyedFile(
        fileId = 1, contentKey = key, place = Place("external_primary", "Music/", "pipe.wav"), size = 529_244,
        durationMs = 12_000, settled = true, recordingMbid = null, format = "wav",
    )

    /** The control: the same provider's pipe that ends cleanly is copied, decoded and cached. */
    @Test
    fun aProviderPipeThatEndsCleanlyIsFingerprintedAndCached() = runBlocking<Unit> {
        val uri = PipeTestProvider.uri(context, Mode.COMPLETE)

        val result = fingerprints(Mode.COMPLETE).compute(uri, "key-user", "wav", LibraryFingerprints.Lane.USER)
        val reattached = fingerprints(Mode.COMPLETE).compute(keyed("key-reattach"))

        assertThat(result.fingerprint.raw).isNotEmpty()
        assertThat(result.reachedEnd).isTrue()
        assertThat(library.library().fingerprint("key-user")).isNotNull()
        assertThat(reattached).isEqualTo(result.fingerprint.raw)
        assertThat(library.library().fingerprint("key-reattach")).isNotNull()
    }

    @Test
    fun aUserRequestOnAProviderPipeWhoseWriterFailsFailsWithoutACacheRow() = runBlocking<Unit> {
        val uri = PipeTestProvider.uri(context, Mode.FAILS)

        assertThatThrownBy {
            runBlocking { fingerprints(Mode.FAILS).compute(uri, "key-user", "wav", LibraryFingerprints.Lane.USER) }
        }.isInstanceOfSatisfying(DecodingException.Failed::class.java) {
            assertThat(it).hasMessageContaining(PipeTestProvider.ERROR)
        }
        assertThat(library.library().fingerprint("key-user")).isNull()
    }

    @Test
    fun reattachmentOnAProviderPipeWhoseWriterFailsGivesNoFingerprintAndNoCacheRow() = runBlocking<Unit> {
        val fingerprint = fingerprints(Mode.FAILS).compute(keyed("key-reattach"))

        assertThat(fingerprint).isNull()
        assertThat(library.library().fingerprint("key-reattach")).isNull()
    }
}
