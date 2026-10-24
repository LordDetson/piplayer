package io.github.lorddetson.piplayer.crash

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.system.Os
import android.system.OsConstants

/**
 * Debug builds only: crashes its own process with a Kotlin exception shortly after it is bound, once the test has
 * unbound it (docs/playback.md §7.4).
 */
class JavaCrashService : Service() {

    override fun onBind(intent: Intent): IBinder {
        Handler(mainLooper).postDelayed({ throw IllegalStateException(MESSAGE) }, DELAY_MS)
        return Binder()
    }

    companion object {
        const val MESSAGE = "A crash on purpose for CrashEndToEndTest"
        const val DELAY_MS = 500L
    }
}

/** Debug builds only: ends its own process with SIGABRT, as a native crash does, shortly after it is bound. */
class NativeCrashService : Service() {

    override fun onBind(intent: Intent): IBinder {
        Handler(mainLooper).postDelayed({ Os.kill(Os.getpid(), OsConstants.SIGABRT) }, JavaCrashService.DELAY_MS)
        return Binder()
    }
}
