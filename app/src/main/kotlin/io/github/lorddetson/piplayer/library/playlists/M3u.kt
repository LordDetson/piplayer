package io.github.lorddetson.piplayer.library.playlists

import android.annotation.SuppressLint
import io.github.lorddetson.piplayer.library.identity.Place
import io.github.lorddetson.piplayer.library.values.LegacyEncoding
import io.github.lorddetson.piplayer.library.values.LegacyText
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Locale

/** A line of a playlist file with what its `#EXTINF` said. */
data class M3uEntry(val location: String, val durationSeconds: Long?, val artist: String?, val title: String?)

/** A playlist file: the name its `#PLAYLIST:` line gives, and its lines in order. */
data class M3uPlaylist(val name: String?, val entries: List<M3uEntry>)

/** M3U8 files (docs/library.md §16.6). */
object M3u {

    /** The type an export is created with. */
    const val MIME_TYPE = "audio/x-mpegurl"

    /** The types the import picker offers: the playlist types, and the generic ones providers give such files. */
    val OPEN_TYPES = arrayOf(
        "audio/x-mpegurl", "audio/mpegurl", "application/x-mpegurl", "application/vnd.apple.mpegurl",
        "application/octet-stream", "text/plain",
    )

    const val EXTENSION = ".m3u8"

    /** U+FEFF, written by its code so that no tool takes it for the file's own byte order mark. */
    private val BOM = Char(0xFEFF)

    fun parse(text: String): M3uPlaylist {
        var name: String? = null
        var pending: M3uEntry? = null
        val entries = ArrayList<M3uEntry>()
        for (raw in text.removePrefix(BOM.toString()).lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#")) {
                when {
                    line.startsWith(EXTINF, ignoreCase = true) -> pending = extinf(line.substring(EXTINF.length))
                    line.startsWith(PLAYLIST, ignoreCase = true) -> name = line.substring(PLAYLIST.length).trim().ifEmpty { null }
                }
                continue
            }
            entries += pending?.copy(location = line) ?: M3uEntry(line, null, null, null)
            pending = null
        }
        return M3uPlaylist(name, entries)
    }

    /** `<seconds>[ attributes],<artist> - <title>`; a negative length is unknown. */
    private fun extinf(rest: String): M3uEntry {
        val comma = rest.indexOf(',')
        val head = if (comma >= 0) rest.substring(0, comma) else rest
        val seconds = head.trim().takeWhile { !it.isWhitespace() }.toDoubleOrNull()?.takeIf { it >= 0 }?.let { Math.round(it) }
        val info = if (comma >= 0) rest.substring(comma + 1).trim() else ""
        val dash = info.indexOf(" - ")
        return if (dash > 0) {
            M3uEntry("", seconds, info.substring(0, dash).trim(), info.substring(dash + 3).trim().ifEmpty { null })
        } else {
            M3uEntry("", seconds, null, info.ifEmpty { null })
        }
    }

    fun write(entries: List<M3uEntry>): String = buildString {
        append("#EXTM3U\n")
        for (entry in entries) {
            val artist = entry.artist?.let(::oneLine)
            val title = entry.title?.let(::oneLine)
            val info = if (artist != null && title != null) "$artist - $title" else title ?: artist ?: ""
            append(EXTINF).append(entry.durationSeconds ?: -1).append(',').append(info).append('\n')
            append(oneLine(entry.location)).append('\n')
        }
    }

    private fun oneLine(text: String) = text.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ')

    /** A length in whole seconds, rounded to the nearest. */
    fun seconds(durationMs: Long?): Long? = durationMs?.let { (it + 500) / 1_000 }

    /**
     * The text of a playlist file: UTF-8 with or without a byte order mark, and otherwise a legacy encoding chosen as for
     * tags (docs/library.md §5.7): [manual] when chosen, else Windows-1251 for Cyrillic text under a
     * [cyrillicLanguage], else Latin-1.
     */
    fun decode(bytes: ByteArray, cyrillicLanguage: Boolean, manual: LegacyEncoding? = null): String {
        val start = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
        try {
            return Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, start, bytes.size - start))
                .toString()
        } catch (_: CharacterCodingException) {
            // Below: a legacy encoding.
        }
        // The tags' reader marks each byte above ASCII; the same choice then decodes the whole file.
        val marked = buildString(bytes.size) {
            for (byte in bytes) {
                val value = byte.toInt() and 0xFF
                append(if (value < 0x80) value.toChar() else (LegacyText.MARK_BASE + value).toChar())
            }
        }
        return LegacyText.decode(mapOf(FIELD to listOf(marked)), cyrillicLanguage, manual).properties.getValue(FIELD).single()
    }

    private const val EXTINF = "#EXTINF:"
    private const val PLAYLIST = "#PLAYLIST:"
    private const val FIELD = "playlist"
}

/** Where a line points: its volume and folder when the line says them, and its name. */
data class LinePlace(val volume: String?, val relativePath: String?, val displayName: String) {
    fun hint(durationMs: Long?) = EntryHint(volume, relativePath, displayName, durationMs)
}

/** The folder of a playlist document, when its provider names one. */
data class DocumentFolder(val volume: String, val relativePath: String)

/**
 * Paths of playlist lines and the places of the library (docs/library.md §16.6). [volumes] gives the directory of each
 * attached volume by its media index name.
 */
class M3uPaths(private val volumes: Map<String, String>) {

    private val byDirectory = volumes.entries.sortedByDescending { it.value.length }

    /** The place [location] names, from the playlist's [folder] when it is relative; null for a stream or nothing. */
    fun placeOf(location: String, folder: DocumentFolder? = null): LinePlace? {
        var path = location.trim()
        if (path.isEmpty()) return null
        if (path.startsWith(FILE_SCHEME, ignoreCase = true)) {
            path = path.substring(FILE_SCHEME.length)
            if (path.startsWith("//")) path = path.substring(2).let { rest -> if (rest.startsWith("/")) rest else "/" + rest.substringAfter('/') }
            path = percentDecoded(path)
        } else if (SCHEME.containsMatchIn(path)) {
            return null
        }
        path = path.replace('\\', '/')
        return when {
            DRIVE.containsMatchIn(path) -> loose(path.substring(3).split('/'))
            path.startsWith("/") -> absolute(segments(path.split('/')))
            folder != null -> placed(folder.volume, segments(folder.relativePath.split('/') + path.split('/')))
            else -> loose(path.split('/'))
        }
    }

    /** The absolute path of [place]: its volume's directory, or `/storage/<volume>` for one that is not attached. */
    fun pathOf(place: Place): String = (volumes[place.volume] ?: "/storage/${place.volume}") + "/" + place.relativePath + place.displayName

    private fun absolute(parts: List<String>): LinePlace? {
        val path = "/" + parts.joinToString("/")
        for ((volume, directory) in byDirectory) {
            if (path.startsWith("$directory/", ignoreCase = true)) return placed(volume, segments(path.substring(directory.length + 1).split('/')))
        }
        for (alias in PRIMARY_ALIASES) {
            if (path.startsWith(alias, ignoreCase = true)) return placed(PRIMARY, segments(path.substring(alias.length).split('/')))
        }
        if (parts.size >= 3 && parts[0] == "storage") return placed(parts[1].lowercase(Locale.ROOT), parts.drop(2))
        return loose(parts)
    }

    private fun placed(volume: String, parts: List<String>): LinePlace? {
        val name = parts.lastOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        val folders = parts.dropLast(1)
        return LinePlace(volume, if (folders.isEmpty()) "" else folders.joinToString("/", postfix = "/"), name)
    }

    /** A place without a volume: its folders, as the line gives them, only help to choose among files of one name. */
    private fun loose(raw: List<String>): LinePlace? {
        val parts = segments(raw)
        val name = parts.lastOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        val folders = parts.dropLast(1)
        return LinePlace(null, if (folders.isEmpty()) null else folders.joinToString("/", postfix = "/"), name)
    }

    companion object {
        /** The media index's name of the primary volume. */
        const val PRIMARY = "external_primary"

        private const val STORAGE_PROVIDER = "com.android.externalstorage.documents"
        private const val FILE_SCHEME = "file:"
        private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
        private val DRIVE = Regex("^[a-zA-Z]:/")
        // Paths other devices and apps wrote, read only to find the primary volume in them.
        @SuppressLint("SdCardPath")
        private val PRIMARY_ALIASES = listOf("/storage/emulated/0/", "/sdcard/", "/mnt/sdcard/", "/storage/self/primary/")

        /**
         * The folder of a document of the system's storage provider (`primary:Music/Lists/x.m3u8`); null for other
         * providers, whose document IDs say nothing of a place.
         */
        fun folderOf(authority: String?, documentId: String?): DocumentFolder? {
            if (authority != STORAGE_PROVIDER || documentId == null) return null
            val colon = documentId.indexOf(':')
            if (colon <= 0) return null
            val root = documentId.substring(0, colon)
            val path = documentId.substring(colon + 1)
            val volume = if (root == "primary") PRIMARY else root.lowercase(Locale.ROOT)
            val folder = path.substringBeforeLast('/', "")
            return DocumentFolder(volume, if (folder.isEmpty()) "" else "$folder/")
        }

        /** [parts] without empty parts and `.`, each `..` taking away the folder before it. */
        private fun segments(parts: List<String>): List<String> {
            val result = ArrayList<String>(parts.size)
            for (part in parts) {
                when (part) {
                    "", "." -> Unit
                    ".." -> if (result.isNotEmpty()) result.removeAt(result.lastIndex)
                    else -> result += part
                }
            }
            return result
        }

        /** `%XX` escapes as UTF-8 bytes; a `+` stays a plus, as in a URI's path. */
        private fun percentDecoded(text: String): String {
            if ('%' !in text) return text
            val bytes = ByteArrayOutputStream(text.length)
            var i = 0
            while (i < text.length) {
                val char = text[i]
                val hex = if (char == '%' && i + 2 <= text.lastIndex) text.substring(i + 1, i + 3).toIntOrNull(16) else null
                if (hex != null) {
                    bytes.write(hex)
                    i += 3
                } else {
                    bytes.write(char.toString().toByteArray(Charsets.UTF_8))
                    i++
                }
            }
            return bytes.toString(Charsets.UTF_8.name())
        }
    }
}
