package io.github.lorddetson.piplayer.nativelib.taglib

import io.github.lorddetson.piplayer.nativelib.NativeLibrary

/** Why a file's tags could not be read. */
enum class TagReadError {
    /** The descriptor is negative or closed. */
    INVALID_FD,

    /** The descriptor is not a regular file (a pipe or a socket), so it cannot be read at random positions. */
    NOT_SEEKABLE,

    /** TagLib does not recognise the content as any format it reads. */
    UNSUPPORTED_FORMAT,

    /** Reading from the descriptor failed (for example it was opened for writing only). */
    IO_ERROR,

    /** Anything else that went wrong inside the native code. */
    NATIVE_ERROR,
}

/** Audio stream details as TagLib reports them. */
data class AudioProperties(val lengthMs: Int, val bitrateKbps: Int, val sampleRate: Int, val channels: Int)

/** An embedded picture; [pictureType] uses the ID3v2 names, such as "Front Cover". */
class Picture(val data: ByteArray, val mimeType: String, val pictureType: String, val description: String)

sealed interface TagReadResult {

    /** [properties] is TagLib's property map: upper-case keys such as TITLE, each with its list of values. */
    data class Success(
        val properties: Map<String, List<String>>,
        val audio: AudioProperties?,
        val pictures: List<Picture>,
    ) : TagReadResult

    data class Failure(val error: TagReadError) : TagReadResult
}

/**
 * Reads tags through TagLib. The caller keeps ownership of [fd] (for example a ParcelFileDescriptor it closes itself);
 * the native code only reads from it and never closes it.
 */
object TagReader {

    init {
        NativeLibrary.load()
    }

    /** [fileName] helps TagLib pick the format by extension; without it the format is detected from the content. */
    fun read(fd: Int, fileName: String? = null, readPictures: Boolean = false): TagReadResult {
        if (fd < 0) return TagReadResult.Failure(TagReadError.INVALID_FD)
        val sink = TagSink()
        return when (nativeRead(fd, fileName, readPictures, sink)) {
            0 -> sink.result()
            1 -> TagReadResult.Failure(TagReadError.INVALID_FD)
            2 -> TagReadResult.Failure(TagReadError.NOT_SEEKABLE)
            3 -> TagReadResult.Failure(TagReadError.UNSUPPORTED_FORMAT)
            5 -> TagReadResult.Failure(TagReadError.IO_ERROR)
            else -> TagReadResult.Failure(TagReadError.NATIVE_ERROR)
        }
    }

    /** Status: 0 read, 1 invalid descriptor, 2 not a regular file, 3 unknown format, 4 native error, 5 read error. */
    @JvmStatic
    private external fun nativeRead(fd: Int, fileName: String?, readPictures: Boolean, sink: TagSink): Int
}

/** Collects what the native reader finds; its methods are called from native code by name. */
internal class TagSink {

    private val properties = LinkedHashMap<String, List<String>>()
    private var audio: AudioProperties? = null
    private val pictures = mutableListOf<Picture>()

    fun property(key: String, values: Array<String>) {
        properties[key] = values.toList()
    }

    fun audio(lengthMs: Int, bitrateKbps: Int, sampleRate: Int, channels: Int) {
        audio = AudioProperties(lengthMs, bitrateKbps, sampleRate, channels)
    }

    fun picture(data: ByteArray, mimeType: String, pictureType: String, description: String) {
        pictures += Picture(data, mimeType, pictureType, description)
    }

    fun result() = TagReadResult.Success(properties.toMap(), audio, pictures.toList())
}
