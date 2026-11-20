package io.github.lorddetson.piplayer.radio

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private val PLAYLIST_EXTENSIONS = setOf("m3u8", "m3u", "pls", "xspf", "asx", "wpl")

/** Accepts absolute, direct HTTPS stream links with a host and no user credentials. */
internal fun parseRadioStreamUrl(value: String): HttpUrl? {
    val address = value.trim()
    if (!address.startsWith("https://", ignoreCase = true)) return null
    if (address.any { it.isISOControl() || it == '\\' }) return null
    val authority = address.substringAfter("://").takeWhile { it !in "/?#" }
    if (authority.isEmpty() || authority.any { it == '@' || it.isWhitespace() }) return null
    val url = address.toHttpUrlOrNull() ?: return null
    val filename = url.pathSegments.lastOrNull(String::isNotEmpty).orEmpty()
    if (filename.substringAfterLast('.', "").lowercase() in PLAYLIST_EXTENSIONS) return null
    return url.newBuilder().fragment(null).build()
}
