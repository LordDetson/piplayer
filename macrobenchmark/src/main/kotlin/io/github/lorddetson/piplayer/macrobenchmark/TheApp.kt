package io.github.lorddetson.piplayer.macrobenchmark

import android.os.Build
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

/** The app under test, as the release build ships it (docs/library.md §2.2). */
object TheApp {

    /** The benchmark build, a package of its own next to the debug build (docs/library.md §2.1). */
    const val PACKAGE = "io.github.lorddetson.piplayer.benchmark"

    /** The test tags of the app, which it exposes as resource IDs. */
    const val SONGS_LIST = "songs_list"
    const val MINI_PLAYER = "mini_player"
    const val NOW_PLAYING = "now_playing"
    const val CRASH_PROMPT = "crash_prompt"

    /** The generated library of docs/library.md §1.6. */
    const val GENERATED_ROOT = "Music/PiPlayerTest20k/"
    const val GENERATED_FILES = 20_000

    private val audioPermission =
        if (Build.VERSION.SDK_INT >= 33) "android.permission.READ_MEDIA_AUDIO" else "android.permission.READ_EXTERNAL_STORAGE"

    fun grantAccess(device: UiDevice) {
        device.executeShellCommand("pm grant $PACKAGE $audioPermission")
    }

    /**
     * Waits for the Songs list and returns it. A crash report from an earlier run opens a dialog over it, which would
     * take the next taps and flings; that fails here, under its own name.
     */
    fun songsList(device: UiDevice): UiObject2 {
        val list = checkNotNull(device.wait(Until.findObject(By.res(SONGS_LIST)), 30_000)) { "The Songs list did not appear" }
        check(!device.hasObject(By.res(CRASH_PROMPT))) { "PiPlayer reports a crash of an earlier run; see its crash report" }
        return list
    }

    /**
     * Waits for the whole list to follow its first page of 50, which it does within a second on the phone: a tap or a
     * fling during the swap may miss its row or measure the swap.
     */
    fun waitForTheWholeList(device: UiDevice) {
        Thread.sleep(WHOLE_LIST_MS)
        device.waitForIdle()
    }

    private const val WHOLE_LIST_MS = 3_000L

    /**
     * How many songs of the generated library the media index holds. The shell command is split at spaces without
     * quoting, so the rows are counted here rather than selected by the index.
     */
    fun generatedFiles(device: UiDevice): Int = device.executeShellCommand(
        "content query --uri content://media/external/audio/media --projection relative_path",
    ).lines().count { "relative_path=$GENERATED_ROOT" in it }

    /** Whether the system's media sessions list PiPlayer's as playing. */
    fun isPlaying(device: UiDevice): Boolean {
        val dump = device.executeShellCommand("dumpsys media_session")
        val own = dump.substringAfter("package=$PACKAGE", missingDelimiterValue = "")
        return Regex("""state=PlaybackState \{state=(?:[A-Z_]+\()?3\b""").containsMatchIn(own.take(4_000))
    }
}
