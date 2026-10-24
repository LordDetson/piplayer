package io.github.lorddetson.piplayer.crash

import androidx.core.content.FileProvider

/** PiPlayer's own FileProvider for shared crash reports, so that no library's provider serves them (§7.3). */
class CrashReportProvider : FileProvider()
