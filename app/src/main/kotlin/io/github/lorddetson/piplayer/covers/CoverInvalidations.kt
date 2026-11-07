package io.github.lorddetson.piplayer.covers

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.scan

/**
 * When the loader's memory may no longer hold what the library would give (docs/library.md §13.3): a sync changed the
 * library (a new revision after the first one seen), or a run of the tag pass ended, which may have found pictures and
 * chosen other songs to stand for albums; or an attach pass wrote identities, so that an own cover may follow its identity
 * to another file. Not at every batch of a pass, so that scrolling back during a pass finds its covers in memory.
 */
object CoverInvalidations {

    fun of(revisions: Flow<Long>, passRunning: Flow<Boolean>, identityChanges: Flow<Long> = emptyFlow()): Flow<Unit> = merge(
        revisions.distinctUntilChanged().drop(1).map { },
        passRunning.distinctUntilChanged()
            .scan(false to false) { (_, was), now -> was to now }
            .filter { (was, now) -> was && !now }
            .map { },
        identityChanges.distinctUntilChanged().drop(1).map { },
    )
}
