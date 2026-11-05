#include <jni.h>
#include <string>
#include <sys/stat.h>

#include <apefooter.h>
#include <apetag.h>
#include <id3v2header.h>
#include <id3v2tag.h>
#include <mpegfile.h>
#include <mpegheader.h>
#include <mpegproperties.h>
#include <xingheader.h>

#include "fd_stream.h"
#include "jni_bridge.h"

namespace piplayer {
namespace {

// Status codes shared with TagReader.kt.
constexpr jint OK = 0;
constexpr jint INVALID_FD = 1;
constexpr jint NATIVE_ERROR = 4;
constexpr jint IO_ERROR = 5;

/** The size of an ID3v1 tag, which TagLib only tells by its presence. */
constexpr TagLib::offset_t ID3V1_BYTES = 128;

/** How far after a leading ID3v2 tag MPEG frames are looked for: a file of another format is not read to its end. */
constexpr TagLib::offset_t MPEG_PROBE_BYTES = 1 << 20;
constexpr unsigned int MPEG_PROBE_BLOCK_BYTES = 4096;

jmethodID sinkMpeg = nullptr;

/** Where a tag at the very start of [file] ends, 0 without one. */
TagLib::offset_t leadingTagEnd(TagLib::MPEG::File &file) {
  if (!file.hasID3v2Tag()) return 0;
  file.seek(0);
  if (file.readBlock(3) != TagLib::ID3v2::Header::fileIdentifier()) return 0;
  return file.ID3v2Tag()->header()->completeTagSize();
}

/**
 * Whether three frames in a row follow the leading tag closely. TagLib's own search for a first frame reads to the end
 * of a file that has none, and one frame followed by a look-alike is not much of a proof.
 */
bool hasMpegFrames(TagLib::MPEG::File &file) {
  const TagLib::offset_t begin = leadingTagEnd(file);
  for (TagLib::offset_t block = begin; block < begin + MPEG_PROBE_BYTES; block += MPEG_PROBE_BLOCK_BYTES) {
    file.seek(block);
    // One byte more, for a sync that crosses into the next block.
    const TagLib::ByteVector bytes = file.readBlock(MPEG_PROBE_BLOCK_BYTES + 1);
    if (bytes.size() < 2) return false;
    for (unsigned int i = 0; i + 1 < bytes.size(); ++i) {
      const auto first = static_cast<unsigned char>(bytes[i]);
      const auto second = static_cast<unsigned char>(bytes[i + 1]);
      if (first != 0xFF || second == 0xFF || (second & 0xE0) != 0xE0) continue;
      const TagLib::offset_t at = block + i;
      const TagLib::MPEG::Header header(&file, at, true);
      if (!header.isValid()) continue;
      if (TagLib::MPEG::Header(&file, at + header.frameLength(), true).isValid()) return true;
    }
  }
  return false;
}

/**
 * Sends where the audio of an MPEG file is, for the content key and the duration rules (TI §3.1). TagLib's
 * lastFrameOffset() is where the last frame starts: a frame only counts when another one follows it, and the search
 * returns the end of the last frame that counts.
 */
void sendMpegLayout(JNIEnv *env, jobject sink, TagLib::MPEG::File &file) {
  const TagLib::offset_t first = file.firstFrameOffset();
  const TagLib::offset_t last = first < 0 ? -1 : file.lastFrameOffset();
  jint firstLength = 0;
  jint firstBitrate = 0;
  jboolean service = JNI_FALSE;
  jboolean constantBitrate = JNI_FALSE;
  if (first >= 0) {
    const TagLib::MPEG::Header header(&file, first, false);
    firstLength = header.frameLength();
    firstBitrate = header.bitrate();
    file.seek(first);
    const TagLib::ByteVector frame = file.readBlock(header.frameLength());
    const bool xing = frame.find("Xing") >= 0;
    const bool info = !xing && frame.find("Info") >= 0;
    service = xing || info || frame.find("VBRI") >= 0 ? JNI_TRUE : JNI_FALSE;
    constantBitrate = info ? JNI_TRUE : JNI_FALSE;
  }
  jint lastLength = 0;
  if (last >= 0) lastLength = TagLib::MPEG::Header(&file, last, false).frameLength();

  TagLib::offset_t audioEnd = file.length();
  if (file.hasID3v1Tag()) audioEnd -= ID3V1_BYTES;
  if (file.hasAPETag()) audioEnd -= file.APETag()->footer()->completeTagSize();

  const TagLib::MPEG::Properties properties(&file, TagLib::AudioProperties::Average);
  const TagLib::MPEG::XingHeader *count = properties.xingHeader();
  const bool hasCount = count != nullptr && count->isValid();
  env->CallVoidMethod(sink, sinkMpeg, properties.isADTS() ? JNI_TRUE : JNI_FALSE, static_cast<jlong>(first),
                      firstLength, service, static_cast<jlong>(last), lastLength, static_cast<jlong>(audioEnd),
                      constantBitrate, hasCount ? static_cast<jlong>(count->totalFrames()) : -1L,
                      hasCount ? static_cast<jlong>(count->totalSize()) : -1L, firstBitrate);
}

/** Looks at the file as MPEG, whatever its name; the sink gets a layout when it is. */
jint mpegLayout(JNIEnv *env, jclass, jint fd, jobject sink) {
  struct stat info {};
  if (fd < 0 || fstat(fd, &info) != 0 || !S_ISREG(info.st_mode)) return INVALID_FD;
  // No C++ exception may cross the JNI boundary.
  try {
    FdStream stream(fd, std::string());
    // The properties are read later, because they read to the end of a file without frames.
    TagLib::MPEG::File file(&stream, false);
    if (hasMpegFrames(file)) sendMpegLayout(env, sink, file);
    if (env->ExceptionCheck()) return NATIVE_ERROR;
    return stream.error() != 0 ? IO_ERROR : OK;
  } catch (...) {
    return NATIVE_ERROR;
  }
}

}  // namespace

bool registerMpegLayout(JNIEnv *env) {
  jclass reader = env->FindClass("io/github/lorddetson/piplayer/nativelib/taglib/TagReader");
  jclass sink = env->FindClass("io/github/lorddetson/piplayer/nativelib/taglib/TagSink");
  if (reader == nullptr || sink == nullptr) return false;
  sinkMpeg = env->GetMethodID(sink, "mpeg", "(ZJIZJIJZJJI)V");
  if (sinkMpeg == nullptr) return false;
  static const JNINativeMethod methods[] = {
      {"nativeMpegLayout", "(ILio/github/lorddetson/piplayer/nativelib/taglib/TagSink;)I",
       reinterpret_cast<void *>(mpegLayout)},
  };
  return env->RegisterNatives(reader, methods, 1) == JNI_OK;
}

}  // namespace piplayer
