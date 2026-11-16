package io.github.lorddetson.piplayer.identification.pipeline

import io.github.lorddetson.piplayer.tagging.contentkey.DurationPlan
import java.io.IOException
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred

// The fakes of stage 0, the fingerprint cache and the encoder that the pipeline's tests share.

/** Stage 0 as a fake: [file] is what it read, [walked] what a walk finds. */
class FakeLocal(@Volatile var file: LocalFile?) : LocalStage {
    val reads: MutableList<FileRef> = Collections.synchronizedList(mutableListOf())
    val walks: MutableList<DurationPlan> = Collections.synchronizedList(mutableListOf())

    @Volatile
    var walked: Long? = null

    @Volatile
    var failure: RuntimeException? = null

    override suspend fun read(file: FileRef): LocalFile? {
        reads += file
        failure?.let { throw it }
        return this.file
    }

    override suspend fun walk(file: LocalFile, plan: DurationPlan): Long? {
        walks += plan
        return walked
    }
}

/** The fingerprint cache as a fake: decoding gives [RAW] with the session's string [SESSION]. */
class FakeFingerprints : FingerprintCache {
    val stored: MutableMap<String, CachedFingerprint> = Collections.synchronizedMap(mutableMapOf())
    val kept: MutableMap<String, String> = Collections.synchronizedMap(mutableMapOf())

    @Volatile
    var computes = 0

    @Volatile
    var failure: IOException? = null

    @Volatile
    var whileComputing: () -> Unit = {}

    @Volatile
    var suspendUntil: CompletableDeferred<Unit>? = null

    @Volatile
    var cacheFailure: RuntimeException? = null

    @Volatile
    var runtimeFailure: RuntimeException? = null

    override suspend fun cached(contentKey: String): CachedFingerprint? {
        cacheFailure?.let { throw it }
        return stored[contentKey]
    }

    override suspend fun compute(file: LocalFile): ComputedFingerprint {
        computes++
        whileComputing()
        suspendUntil?.await()
        failure?.let { throw it }
        runtimeFailure?.let { throw it }
        stored[file.target.contentKey] = CachedFingerprint(RAW)
        return ComputedFingerprint(RAW, SESSION)
    }

    override suspend fun keepAcoustIdTrack(contentKey: String, trackId: String) {
        kept[contentKey] = trackId
    }

    companion object {
        /** What decoding gives: the raw items and the string of the session that computed them. */
        val RAW = IntArray(948) { it * 7 }
        const val SESSION = "AQAAsession"
    }
}

/** An encoder whose string names the number of items it was given. */
class FakeEncoder : FingerprintEncoder {
    val calls: MutableList<IntArray> = Collections.synchronizedList(mutableListOf())

    override fun encode(raw: IntArray): String {
        calls += raw
        return encoded(raw)
    }

    companion object {
        fun encoded(raw: IntArray) = "AQAAcached${raw.size}"
    }
}
