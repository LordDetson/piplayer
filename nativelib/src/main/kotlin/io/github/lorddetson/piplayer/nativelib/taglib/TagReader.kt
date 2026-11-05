package io.github.lorddetson.piplayer.nativelib.taglib

import android.app.Application
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

/**
 * An embedded picture; [pictureType] uses the ID3v2 names, such as "Front Cover". [data] is null when only the
 * pictures' sizes were asked for.
 */
class Picture(
    val data: ByteArray?,
    val sizeBytes: Int,
    val mimeType: String,
    val pictureType: String,
    val description: String,
)

/** What a read gives of the embedded pictures. */
enum class Pictures {
    NONE,

    /** Each picture's type, MIME type, description and size, without its bytes. */
    SIZES,

    /** The pictures with their bytes. */
    DATA,
}

sealed interface TagReadResult {

    /**
     * [properties] is TagLib's property map: upper-case keys such as TITLE, each with its list of values. [container]
     * names what holds the audio, such as `mpeg`, `adts` or `ogg-opus` (docs/library.md §4.2).
     */
    data class Success(
        val properties: Map<String, List<String>>,
        val audio: AudioProperties?,
        val pictures: List<Picture>,
        val container: String? = null,
    ) : TagReadResult

    data class Failure(val error: TagReadError) : TagReadResult
}

/**
 * Reads tags through TagLib. The caller keeps ownership of [fd] (for example a ParcelFileDescriptor it closes itself);
 * the native code only reads from it and never closes it.
 *
 * TagLib may crash or hang on a malformed file, so it reads only in the app's `:tagger` process (docs/library.md §4.1).
 */
object TagReader {

    /** Lets this module's own instrumented tests read in their test process. */
    @Volatile
    internal var anyProcess = false

    private const val TAGGER_SUFFIX = ":tagger"

    init {
        NativeLibrary.load()
    }

    /**
     * [fileName] helps TagLib pick the format by extension; without it the format is detected from the content.
     * Throws IllegalStateException outside the `:tagger` process.
     */
    fun read(fd: Int, fileName: String? = null, pictures: Pictures = Pictures.NONE): TagReadResult {
        check(anyProcess || Application.getProcessName().endsWith(TAGGER_SUFFIX)) {
            "TagReader reads only in the $TAGGER_SUFFIX process"
        }
        if (fd < 0) return TagReadResult.Failure(TagReadError.INVALID_FD)
        val sink = TagSink()
        return when (nativeRead(fd, fileName, pictures.ordinal, sink)) {
            0 -> sink.result()
            1 -> TagReadResult.Failure(TagReadError.INVALID_FD)
            2 -> TagReadResult.Failure(TagReadError.NOT_SEEKABLE)
            3 -> TagReadResult.Failure(TagReadError.UNSUPPORTED_FORMAT)
            5 -> TagReadResult.Failure(TagReadError.IO_ERROR)
            else -> TagReadResult.Failure(TagReadError.NATIVE_ERROR)
        }
    }

    /**
     * [pictures] is the ordinal of [Pictures]. Status: 0 read, 1 invalid descriptor, 2 not a regular file, 3 unknown
     * format, 4 native error, 5 read error.
     */
    @JvmStatic
    private external fun nativeRead(fd: Int, fileName: String?, pictures: Int, sink: TagSink): Int
}

/** Collects what the native reader finds; its methods are called from native code by name. */
internal class TagSink {

    private val properties = LinkedHashMap<String, List<String>>()
    private var audio: AudioProperties? = null
    private val pictures = mutableListOf<Picture>()
    private var container: String? = null

    fun property(key: String, values: Array<String>) {
        properties[key] = values.toList()
    }

    fun audio(lengthMs: Int, bitrateKbps: Int, sampleRate: Int, channels: Int) {
        audio = AudioProperties(lengthMs, bitrateKbps, sampleRate, channels)
    }

    /** [data] is null when only the size was asked for. */
    fun picture(data: ByteArray?, sizeBytes: Int, mimeType: String, pictureType: String, description: String) {
        pictures += Picture(data, sizeBytes, mimeType, pictureType, description)
    }

    fun container(name: String) {
        container = name
    }

    fun result() = TagReadResult.Success(properties.toMap(), audio, pictures.toList(), container)
}
