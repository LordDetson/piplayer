package io.github.lorddetson.piplayer.ui.details

import androidx.compose.ui.graphics.ImageBitmap
import io.github.lorddetson.piplayer.library.details.TrackDetails
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

/** What the details screen shows: still reading, the song gone, a read that failed, or its details. */
sealed interface DetailsState {
    data object Loading : DetailsState

    data object Gone : DetailsState

    data object Failed : DetailsState

    /** The [details] and, for a file opened from another app, its embedded [picture] for the header. */
    data class Found(
        val details: TrackDetails,
        val picture: ImageBitmap? = null,
        val tagRecoveryMessage: Int? = null,
    ) : DetailsState
}

/** How the details screen reads (docs/library.md §18.1, §18.5). */
@OptIn(ExperimentalCoroutinesApi::class)
object DetailsStates {

    /** How long the reads go on after the screen stops watching, so that a turn of the device does not read again. */
    const val STOP_TIMEOUT_MS = 5_000L

    /**
     * The states of the details read by [read] on each of [changes], in [scope]. [once] reads a single time for the life
     * of [scope] (a file opened from another app, whose read costs a `:tagger` call); otherwise the reads stop shortly
     * after the screen stops watching and start again when it is shown. A read that throws goes to [onError] and keeps
     * the details shown, also when it is the first read after the screen is shown again, or says that they could not be
     * read when none are, and the next change reads again.
     */
    fun of(
        scope: CoroutineScope,
        changes: Flow<Unit>,
        once: Boolean,
        onError: (Exception) -> Unit = {},
        read: suspend () -> DetailsState.Found?,
    ): StateFlow<DetailsState> {
        // Outlives each watch, so that a failed first read after the screen is shown again keeps what it showed before.
        val shown = AtomicReference<DetailsState.Found?>(null)
        val states = flow {
            changes
                .mapLatest {
                    try {
                        read() ?: DetailsState.Gone
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        onError(e)
                        null
                    }
                }
                .collect { state ->
                    if (state != null) shown.set(state as? DetailsState.Found)
                    emit(state ?: shown.get() ?: DetailsState.Failed)
                }
        }
        val started = if (once) SharingStarted.Lazily else SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS)
        return states.stateIn(scope, started, DetailsState.Loading)
    }
}
