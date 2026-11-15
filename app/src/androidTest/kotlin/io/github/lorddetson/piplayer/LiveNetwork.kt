package io.github.lorddetson.piplayer

import android.os.Bundle

/**
 * The opt-in for tests that call a real web service (TI §11.2): they run only with `-e liveNetwork true` on
 * `am instrument`, which tools/instrumented/run_instrumented.py never passes by default. A key in local.properties
 * alone is not enough, because every worktree copies that file.
 */
object LiveNetwork {

    const val ARGUMENT = "liveNetwork"

    /** Whether the instrumentation [arguments] ask for live calls: the exact value "true". */
    fun allowed(arguments: Bundle): Boolean = arguments.getString(ARGUMENT) == "true"
}
