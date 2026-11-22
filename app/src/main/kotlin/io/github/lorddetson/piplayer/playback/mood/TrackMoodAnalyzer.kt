package io.github.lorddetson.piplayer.playback.mood

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import io.github.lorddetson.piplayer.identification.fingerprint.MediaCodecPcmSource
import io.github.lorddetson.piplayer.identification.fingerprint.PcmConverter
import java.util.concurrent.Executors
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext

/** Infers track energy from a short PCM excerpt on a separate lane from playback and fingerprinting. */
internal object TrackMoodAnalyzer {
    suspend fun analyze(context: Context, uri: Uri): TrackMood? {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT && uri.scheme != ContentResolver.SCHEME_FILE) return null
        return withContext(lane) {
            val job = coroutineContext.job
            val deadline = SystemClock.elapsedRealtime() + DECODE_BUDGET_MS
            val mood = PcmMoodAccumulator()
            fun expired() = SystemClock.elapsedRealtime() >= deadline
            try {
                val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return@withContext null
                descriptor.use {
                    val fd = it.fileDescriptor
                    // The fingerprint reader can copy a pipe. Mood inference skips pipes so it never fetches
                    // or copies an entire track through a provider.
                    if (!OsConstants.S_ISREG(Os.fstat(fd).st_mode)) return@withContext null
                    MediaCodecPcmSource(context, fd, cancelled = { !job.isActive || expired() }, checkError = it::checkError)
                        .decode { pcm, format ->
                            job.ensureActive()
                            !expired() && mood.add(PcmConverter.toFramePower(pcm, format), format.sampleRate)
                        }
                }
                mood.result()
            } catch (e: CancellationException) {
                job.ensureActive()
                if (expired()) mood.result() else throw e
            } catch (_: Exception) {
                // Missing grants, unavailable files and unsupported formats leave the background neutral.
                null
            }
        }
    }

    private const val DECODE_BUDGET_MS = 2_500L

    private val lane = Executors.newSingleThreadExecutor { task ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            task.run()
        }, "track-mood").apply { isDaemon = true }
    }.asCoroutineDispatcher()
}
