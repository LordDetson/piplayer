package io.github.lorddetson.piplayer.nativelib.taglib

import android.app.Application
import io.github.lorddetson.piplayer.nativelib.NativeLibrary
import java.io.IOException

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

/**
 * Where TagLib found the audio of an MPEG file, MP3 or raw AAC ([adts]). Offsets are -1 when no frame was found:
 * TagLib only accepts a frame followed by another frame of the same kind, so a file needs at least two.
 */
data class MpegLayout(
    val adts: Boolean,
    val firstFrame: Long,
    val firstFrameLength: Int,
    /** The first frame holds the ASCII string Xing, Info or VBRI: it describes the stream and carries no audio. */
    val serviceFrame: Boolean,
    val lastFrame: Long,
    val lastFrameLength: Int,
    /** Where the ID3v1 and APE tags at the end of the file start; the file's size without such tags. */
    val audioEnd: Long,
    val frameCount: FrameCountHeader?,
    val firstFrameBitrateKbps: Int,
)

/** A valid Xing, Info or VBRI header; [constantBitrate] means its name is Info, which encoders write for CBR files. */
data class FrameCountHeader(val constantBitrate: Boolean, val frames: Long, val bytes: Long)

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
     * Where the audio of an MPEG file is, whatever its name: null without three frames in a row within 1 MiB after a
     * leading ID3v2 tag. Throws IOException when reading fails and IllegalStateException outside the `:tagger` process.
     */
    fun mpegLayout(fd: Int): MpegLayout? {
        check(anyProcess || Application.getProcessName().endsWith(TAGGER_SUFFIX)) {
            "TagReader reads only in the $TAGGER_SUFFIX process"
        }
        val sink = TagSink()
        if (nativeMpegLayout(fd, sink) != 0) throw IOException("The MPEG frames of the file could not be read")
        return sink.mpegLayout
    }

    /** Status: 0 done (the sink has a layout when frames were found), anything else a failure. */
    @JvmStatic
    private external fun nativeMpegLayout(fd: Int, sink: TagSink): Int

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

    var mpegLayout: MpegLayout? = null
        private set

    /** [headerFrames] is negative without a frame count header. */
    fun mpeg(
        adts: Boolean,
        firstFrame: Long,
        firstFrameLength: Int,
        serviceFrame: Boolean,
        lastFrame: Long,
        lastFrameLength: Int,
        audioEnd: Long,
        constantBitrate: Boolean,
        headerFrames: Long,
        headerBytes: Long,
        firstFrameBitrateKbps: Int,
    ) {
        val frameCount = if (headerFrames < 0) null else FrameCountHeader(constantBitrate, headerFrames, headerBytes)
        mpegLayout = MpegLayout(
            adts, firstFrame, firstFrameLength, serviceFrame, lastFrame, lastFrameLength, audioEnd, frameCount,
            firstFrameBitrateKbps,
        )
    }

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

    fun result() = TagReadResult.Success(properties.toMap(), audio, pictures.toList())
}
