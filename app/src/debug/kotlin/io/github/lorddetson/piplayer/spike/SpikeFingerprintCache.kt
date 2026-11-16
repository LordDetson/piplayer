package io.github.lorddetson.piplayer.spike

import io.github.lorddetson.piplayer.identification.pipeline.CachedFingerprint
import io.github.lorddetson.piplayer.identification.pipeline.ComputedFingerprint
import io.github.lorddetson.piplayer.identification.pipeline.FingerprintCache
import io.github.lorddetson.piplayer.identification.pipeline.LocalFile
import java.util.concurrent.ConcurrentHashMap

/**
 * The debug spike's fingerprint cache (TI §3 "One run"): [decode] decodes a file as the app's cache does, and what it
 * finds stays in memory with the AcoustID track ID of its lookup. A spike run sends behind its own gate, not the user's
 * choice, so it writes nothing to the library database, whose cache the app's own runs fill.
 */
class SpikeFingerprintCache(private val decode: suspend (LocalFile) -> ComputedFingerprint) : FingerprintCache {

    private val fingerprints = ConcurrentHashMap<String, IntArray>()
    private val tracks = ConcurrentHashMap<String, String>()

    override suspend fun cached(contentKey: String): CachedFingerprint? = fingerprints[contentKey]?.let(::CachedFingerprint)

    override suspend fun compute(file: LocalFile): ComputedFingerprint =
        decode(file).also { fingerprints[file.target.contentKey] = it.raw }

    override suspend fun keepAcoustIdTrack(contentKey: String, trackId: String) {
        tracks[contentKey] = trackId
    }

    /** The AcoustID track ID kept for [contentKey], or null. */
    fun acoustIdTrack(contentKey: String): String? = tracks[contentKey]
}
