package io.github.lorddetson.piplayer.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(UnstableApi::class)
class PlaybackResponseHeadersTest {
    @Test
    fun `the playback source preserves upstream ICY response headers`() {
        val headers = mapOf("icy-metaint" to listOf("8192"), "icy-name" to listOf("Pi Jazz"))
        val source: DataSource = PlaybackTagDataSource(IcySource(headers))

        assertEquals(headers, source.responseHeaders)
    }

    private class IcySource(private val headers: Map<String, List<String>>) : DataSource {
        override fun getResponseHeaders(): Map<String, List<String>> = headers
        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun open(dataSpec: DataSpec): Long = C.LENGTH_UNSET.toLong()
        override fun getUri(): Uri? = null
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = C.RESULT_END_OF_INPUT
        override fun close() = Unit
    }
}
