package io.github.lorddetson.piplayer.identification

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import io.github.lorddetson.piplayer.identification.provider.ProviderId
import io.github.lorddetson.piplayer.net.RateLimitPersistence
import io.github.lorddetson.piplayer.settings.SettingsFiles
import java.io.IOException
import kotlinx.coroutines.flow.first

/**
 * The rate limiters' last-request times in the `device_state` file (TI §8, §10), one key per provider, so the shortest
 * gap between two requests of a provider holds across processes. A batch that survives the death of its process does
 * not reset the limit: the next process reads the stored time before its first request.
 */
class RateLimitTimes(private val file: DataStore<Preferences>) {

    /** The persisted state of one provider's limiter; all providers share the file but each has its own key. */
    fun forProvider(provider: ProviderId): RateLimitPersistence = DataStoreRateLimitPersistence(file, keyOf(provider))

    companion object {

        /** The stored key names, one per provider, in the order of [ProviderId] (TI §10). */
        fun keyNames(): List<String> = ProviderId.entries.map { keyOf(it).name }
    }

    /**
     * One key of the `device_state` file. A write that fails is dropped and logged like any other settings write
     * ([SettingsFiles.edit]), so it never fails the request whose time it stores; a file that cannot be read counts as
     * no stored request, so the first request of the process is never blocked by a broken file.
     */
    private class DataStoreRateLimitPersistence(
        private val file: DataStore<Preferences>,
        private val key: Preferences.Key<Long>,
    ) : RateLimitPersistence {

        override suspend fun restore(): Long? = try {
            file.data.first()[key]
        } catch (e: IOException) {
            null
        }

        override suspend fun persist(lastRequestMillis: Long) {
            SettingsFiles.edit(file) { it[key] = lastRequestMillis }
        }
    }
}

private fun keyOf(provider: ProviderId) = longPreferencesKey(
    when (provider) {
        ProviderId.ACOUSTID -> "rate_limit_acoustid_last_request"
        ProviderId.MUSICBRAINZ -> "rate_limit_musicbrainz_last_request"
        ProviderId.COVER_ART_ARCHIVE -> "rate_limit_cover_art_archive_last_request"
    },
)