package io.github.lorddetson.piplayer.spike

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import io.github.lorddetson.piplayer.BuildConfig
import io.github.lorddetson.piplayer.identification.FingerprintStage
import io.github.lorddetson.piplayer.identification.IdentificationServices
import io.github.lorddetson.piplayer.identification.ProviderBaseUrls
import io.github.lorddetson.piplayer.identification.fingerprint.FingerprintResult
import io.github.lorddetson.piplayer.identification.fingerprint.MediaCodecPcmSource
import io.github.lorddetson.piplayer.identification.fingerprint.NativeFingerprinting
import io.github.lorddetson.piplayer.identification.fingerprint.PcmSource
import io.github.lorddetson.piplayer.identification.musicbrainz.toCandidate
import io.github.lorddetson.piplayer.identification.text.RecordingSearch
import io.github.lorddetson.piplayer.identification.text.TextSearch
import io.github.lorddetson.piplayer.nativelib.taglib.TagReadResult
import io.github.lorddetson.piplayer.net.AndroidConnectivity
import io.github.lorddetson.piplayer.net.Connectivity
import io.github.lorddetson.piplayer.net.UserAgent
import io.github.lorddetson.piplayer.tagging.TagRead
import io.github.lorddetson.piplayer.tagging.TagReadGuard
import io.github.lorddetson.piplayer.tagging.TagTarget
import io.github.lorddetson.piplayer.tagging.Tagger
import io.github.lorddetson.piplayer.tagging.TaggerResult
import io.github.lorddetson.piplayer.tagging.outsideTarget
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import kotlin.time.TimeSource

/**
 * The spike's network side: services of its own, with one rate limiter per provider (TI §8, §9), behind [SpikeGate],
 * which is open only while a spike run goes on. The app's services stay dormant behind their closed gate, so in a
 * debug process only these ever send.
 */
object SpikeServices {

    @Volatile
    private var services: IdentificationServices? = null

    /** True when the spike's services can look fingerprints up: the build carries an AcoustID application key. */
    fun hasAcoustIdKey(context: Context): Boolean = services(context).fingerprintStage is FingerprintStage.Available

    fun services(context: Context): IdentificationServices = services ?: synchronized(this) {
        services ?: create(UserAgent.forBuild(), AndroidConnectivity(context), BuildConfig.ACOUSTID_API_KEY)
            .also { services = it }
    }

    /**
     * The spike's services at [baseUrls], with the application key [acoustIdApiKey] (empty for none); the wiring test
     * points them at the fakes.
     */
    fun create(
        userAgent: String,
        connectivity: Connectivity,
        acoustIdApiKey: String,
        baseUrls: ProviderBaseUrls = ProviderBaseUrls.PRODUCTION,
        timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
    ): IdentificationServices = IdentificationServices.create(
        userAgent = userAgent,
        gate = SpikeGate,
        connectivity = connectivity,
        baseUrls = baseUrls,
        timeSource = timeSource,
        acoustIdApiKey = acoustIdApiKey,
    )

    /** Runs the spike on [file], with [SpikeGate] open for the run only. */
    suspend fun run(context: Context, file: SpikeFile, onStage: (SpikeStage) -> Unit): SpikeReport =
        SpikeGate.open().use { spike(context).run(file, onStage) }

    /** The spike over the app's build: its services and the native fingerprinting. Open [SpikeGate] to run it. */
    fun spike(context: Context): IdentificationSpike {
        val calculator = NativeFingerprinting.calculator()
        return spike(services(context)) { source -> calculator.fingerprint(source) }
    }

    /**
     * The spike over [services]. Its AcoustID stage follows [IdentificationServices.fingerprintStage], as stage 3 of the
     * app will: skipped when the services have no key.
     */
    fun spike(
        services: IdentificationServices,
        fingerprinter: suspend (PcmSource) -> FingerprintResult,
    ): IdentificationSpike {
        val musicBrainz = services.musicBrainz
        val search = RecordingSearch { query ->
            musicBrainz.searchRecordings(query.query, query.limit).recordings.map { it.toCandidate() }
        }
        val acoustId = when (val stage = services.fingerprintStage) {
            is FingerprintStage.Available -> AcoustIdLookup(stage.acoustId::lookup)
            is FingerprintStage.Unavailable -> null
        }
        return IdentificationSpike(fingerprinter, acoustId, TextSearch(search))
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
    private val target: TagTarget,
) : SpikeFile, Closeable {

    /** Through `:tagger`, counted against the file like every read (docs/library.md §4.5). */
    override suspend fun readTags(): TagReadResult = Tagger.client(context).read(target, pfd, displayName).tagsOrThrow()

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
            if (isRegularFile(pfd)) return OpenedFile(context, name, size, pfd, null, outsideTarget(uri.toString(), pfd))

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
                // The copy is new each time; the provider's URI and the size name the file.
                val target = TagTarget.Outside(uri.toString(), copy.length(), mtimeMs = 0)
                return OpenedFile(context, name, copy.length(), ParcelFileDescriptor.open(copy, MODE), copy, target)
            } catch (e: Exception) {
                copy.delete()
                throw e
            }
        }

        /** Opens a file of the app's own storage, as the own-files corpus run reads them. */
        fun open(context: Context, file: File): OpenedFile {
            val pfd = ParcelFileDescriptor.open(file, MODE)
            return OpenedFile(context, file.name, file.length(), pfd, null, outsideTarget(Uri.fromFile(file).toString(), pfd))
        }

        private const val MODE = ParcelFileDescriptor.MODE_READ_ONLY

        private fun isRegularFile(pfd: ParcelFileDescriptor): Boolean = try {
            OsConstants.S_ISREG(Os.fstat(pfd.fileDescriptor).st_mode)
        } catch (_: ErrnoException) {
            false
        }
    }
}

/** The reader's result of a `:tagger` call; a crash, a timeout or an unsafe file fails the tag stage. */
fun TaggerResult<TagRead>.tagsOrThrow(): TagReadResult = when (this) {
    is TaggerResult.Replied -> value.report.toResult()
    TaggerResult.Crashed -> throw TaggerFailure("the tag reader process crashed on this file")
    TaggerResult.Unavailable -> throw TaggerFailure("the tag reader could not be reached")
    TaggerResult.TimedOut ->
        throw TaggerFailure("the tag reader did not answer within ${TagReadGuard.DEFAULT_TIMEOUT.inWholeSeconds} s")
    TaggerResult.Unsafe -> throw TaggerFailure("the file is marked unsafe: it crashed or hung the tag reader twice")
}

class TaggerFailure(message: String) : Exception(message)
