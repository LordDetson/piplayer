package io.github.lorddetson.piplayer.playback

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

/**
 * The files an open-with intent carries (docs/playback.md §5.1). Only `content://` is accepted: a `file://` URI from
 * another app could name any file PiPlayer can read, while a content provider grants exactly the file it chose. The
 * manifest advertises only `ACTION_VIEW`; the send actions are read too when an explicit intent carries one.
 */
object OpenWith {

    fun uris(intent: Intent): List<Uri> = buildList {
        when (intent.action) {
            Intent.ACTION_VIEW, Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> Unit
            else -> return emptyList()
        }
        intent.data?.let(::add)
        IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(::add)
        IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.forEach(::add)
        intent.clipData?.let { clip -> repeat(clip.itemCount) { add(clip.getItemAt(it).uri) } }
    }.filter { uri -> uri.scheme == ContentResolver.SCHEME_CONTENT && uri.toString().isNotBlank() }.distinct()
}
