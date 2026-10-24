package io.github.lorddetson.piplayer

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import io.github.lorddetson.piplayer.crash.CrashReports

/** The instrumented tests' runner: no crash dialog, which reports left by earlier runs would put over the screens. */
class PiPlayerTestRunner : AndroidJUnitRunner() {

    override fun onCreate(arguments: Bundle?) {
        CrashReports.promptEnabled = false
        super.onCreate(arguments)
    }
}
