package io.github.lorddetson.piplayer.library

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop

/**
 * The changes of a counter that a Room query reads, after its first value. Room runs the query again on every write to
 * its table, also when the counter stays the same (a favorite or a play writes `user_meta` too, docs/library.md §3.1).
 */
fun Flow<Long>.counterChanges(): Flow<Long> = distinctUntilChanged().drop(1)
