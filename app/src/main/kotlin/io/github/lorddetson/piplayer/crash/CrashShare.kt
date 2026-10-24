package io.github.lorddetson.piplayer.crash

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import io.github.lorddetson.piplayer.R
import java.io.File
import java.io.IOException

/**
 * The share sheet for reports (docs/playback.md §7.3): the text, cut to what an intent can carry, and all of it as a
 * file that only the chosen app may read, for that share.
 */
object CrashShare {

    const val FILE_NAME = "piplayer-crash-report.txt"
    private const val TAG = "CrashShare"

    /** Writes [text] to the shared file and returns the share sheet's intent. Disk work. */
    fun intent(context: Context, text: String): Intent {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.crash_share_subject))
            .putExtra(Intent.EXTRA_TEXT, CrashText.shareText(text))
        try {
            val uri = FileProvider.getUriForFile(context, context.packageName + ".crashreports", write(context, text))
            send.putExtra(Intent.EXTRA_STREAM, uri)
            // The clip carries the read grant to the chosen app; a URI alone, so that the sheet shows the file.
            send.clipData = ClipData.newRawUri(null, uri)
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: IOException) {
            Log.w(TAG, "Sharing the report as text only", e)
        }
        return Intent.createChooser(send, context.getString(R.string.crash_share_title))
    }

    private fun write(context: Context, text: String): File {
        val dir = File(CrashReports.dir(context), "shared")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot make $dir")
        return File(dir, FILE_NAME).apply { writeText(text) }
    }
}
