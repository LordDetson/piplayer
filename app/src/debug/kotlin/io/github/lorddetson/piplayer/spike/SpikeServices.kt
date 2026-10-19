package io.github.lorddetson.piplayer.spike

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import io.github.lorddetson.piplayer.BuildConfig
import io.github.lorddetson.piplayer.identification.acoustid.AcoustIdClient
import io.github.lorddetson.piplayer.identification.fingerprint.MediaCodecPcmSource
import io.github.lorddetson.piplayer.identification.fingerprint.NativeFingerprinting
import io.github.lorddetson.piplayer.identification.fingerprint.PcmSource
import io.github.lorddetson.piplayer.identification.musicbrainz.MusicBrainzClient
import io.github.lorddetson.piplayer.identification.musicbrainz.toCandidate
import io.github.lorddetson.piplayer.identification.text.RecordingSearch
import io.github.lorddetson.piplayer.identification.text.TextSearch
import io.github.lorddetson.piplayer.nativelib.taglib.TagReadResult
import io.github.lorddetson.piplayer.nativelib.taglib.TagReader
import io.github.lorddetson.piplayer.net.HttpClients
import io.github.lorddetson.piplayer.net.RateLimiter
import io.github.lorddetson.piplayer.net.RetryPolicy
import io.github.lorddetson.piplayer.net.UserAgent
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import kotlin.time.Duration.Companion.milliseconds

/** The spike's network clients, with one rate limiter per host for the whole process (TI §9). */
object SpikeServices {

    private val http by lazy { HttpClients.create(UserAgent.of(BuildConfig.VERSION_NAME)) }
    private val musicBrainzLimiter = RateLimiter(1_100.milliseconds)
    private val acoustIdLimiter = RateLimiter(AcoustIdClient.MIN_INTERVAL)

    /** True when the build carries an AcoustID application key (local.properties, never git). */
    val hasAcoustIdKey: Boolean get() = BuildConfig.ACOUSTID_API_KEY.isNotBlank()

    fun spike(): IdentificationSpike {
        val musicBrainz = MusicBrainzClient(http, musicBrainzLimiter, RetryPolicy(maxRetries = 3))
        val search = RecordingSearch { query ->
            musicBrainz.searchRecordings(query.query, query.limit).recordings.map { it.toCandidate() }
        }
        val acoustId = BuildConfig.ACOUSTID_API_KEY.takeIf { it.isNotBlank() }?.let { key ->
            val client = AcoustIdClient(http, acoustIdLimiter, RetryPolicy(maxRetries = 3), key)
            AcoustIdLookup(client::lookup)
        }
        val calculator = NativeFingerprinting.calculator()
        return IdentificationSpike({ source -> calculator.fingerprint(source) }, acoustId, TextSearch(search))
    }
}

/**
 * A file opened read-only for the spike. A descriptor that is not a regular file (some document providers hand out a
 * pipe) cannot be read at random positions, so such a file is first copied into the cache.
 */
class OpenedFile private constructor(
    private val context: Context,
    override val displayName: String?,
    override val sizeBytes: Long?,
    private val pfd: ParcelFileDescriptor,
    private val copy: File?,
) : SpikeFile, Closeable {

    override fun readTags(): TagReadResult = TagReader.read(pfd.fd, displayName)

    override fun pcmSource(): PcmSource = MediaCodecPcmSource(context, pfd.fileDescriptor)

    override fun close() {
        try {
            pfd.close()
        } finally {
            copy?.delete()
        }
    }

    companion object {

        fun open(context: Context, uri: Uri): OpenedFile {
            val resolver = context.contentResolver
            var name: String? = null
            var size: Long? = null
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        name = if (cursor.isNull(0)) null else cursor.getString(0)
                        size = if (cursor.isNull(1)) null else cursor.getLong(1)
                    }
                }
            val pfd = resolver.openFileDescriptor(uri, "r") ?: throw FileNotFoundException("Cannot open $uri")
            if (isRegularFile(pfd)) return OpenedFile(context, name, size, pfd, null)

            val copy = try {
                File.createTempFile("spike", null, context.cacheDir)
            } catch (e: Exception) {
                pfd.close()
                throw e
            }
            try {
                ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                    copy.outputStream().use { input.copyTo(it) }
                }
                return OpenedFile(context, name, copy.length(), ParcelFileDescriptor.open(copy, MODE), copy)
            } catch (e: Exception) {
                copy.delete()
                throw e
            }
        }

        /** Opens a file of the app's own storage, as the own-files corpus run reads them. */
        fun open(context: Context, file: File): OpenedFile =
            OpenedFile(context, file.name, file.length(), ParcelFileDescriptor.open(file, MODE), null)

        private const val MODE = ParcelFileDescriptor.MODE_READ_ONLY

        private fun isRegularFile(pfd: ParcelFileDescriptor): Boolean = try {
            OsConstants.S_ISREG(Os.fstat(pfd.fileDescriptor).st_mode)
        } catch (_: ErrnoException) {
            false
        }
    }
}
