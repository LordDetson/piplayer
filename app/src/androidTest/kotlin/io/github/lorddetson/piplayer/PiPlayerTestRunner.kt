package io.github.lorddetson.piplayer

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import io.github.lorddetson.piplayer.crash.CrashReports
import io.github.lorddetson.piplayer.library.Library

/**
 * The instrumented tests' runner: no crash dialog, which reports left by earlier runs would put over the screens, and no
 * tag pass over the device's real library under the tests; a test that wants one turns it on.
 */
class PiPlayerTestRunner : AndroidJUnitRunner() {

    override fun onCreate(arguments: Bundle?) {
        CrashReports.promptEnabled = false
        Library.tagPassEnabled = false
        super.onCreate(arguments)
    }
}
