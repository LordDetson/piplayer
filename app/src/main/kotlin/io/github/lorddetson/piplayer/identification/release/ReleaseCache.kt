package io.github.lorddetson.piplayer.identification.release

import io.github.lorddetson.piplayer.identification.musicbrainz.ReleaseDto

/**
 * Looked-up releases by release MBID, so one lookup serves every track of an album (TI §5.1). In memory for now; the
 * library database's `release_cache` takes over in M3 step 9.
 */
interface ReleaseCache {
    suspend fun get(releaseId: String): ReleaseDto?

    /**
     * Keeps [release] under its own ID and, when it differs, under [requestedId], the ID it was looked up by (a merged
     * release answers with the ID it was merged into, TI §5.1).
     */
    suspend fun put(release: ReleaseDto, requestedId: String = release.id)
}

/** The [maxEntries] most recently used releases of this process. */
class InMemoryReleaseCache(private val maxEntries: Int = DEFAULT_MAX_ENTRIES) : ReleaseCache {

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    private val entries = object : LinkedHashMap<String, ReleaseDto>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ReleaseDto>): Boolean = size > maxEntries
    }

    override suspend fun get(releaseId: String): ReleaseDto? = synchronized(entries) { entries[releaseId] }

    override suspend fun put(release: ReleaseDto, requestedId: String) {
        synchronized(entries) {
            entries[release.id] = release
            if (requestedId != release.id) entries[requestedId] = release
        }
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 64
    }
}
