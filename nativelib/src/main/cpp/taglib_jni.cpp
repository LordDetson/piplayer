#include <algorithm>
#include <cctype>
#include <limits>
#include <string>
#include <vector>

#include <jni.h>
#include <sys/stat.h>

#ifdef PIPLAYER_TEST_HOOKS
#include <cstdlib>
#include <cstring>
#include <unistd.h>
#endif

#include <aifffile.h>
#include <aiffproperties.h>
#include <apefile.h>
#include <apeproperties.h>
#include <apetag.h>
#include <asffile.h>
#include <asfproperties.h>
#include <dsdifffile.h>
#include <dsffile.h>
#include <fileref.h>
#include <flacfile.h>
#include <flacproperties.h>
#include <id3v1tag.h>
#include <id3v2tag.h>
#include <infotag.h>
#include <matroskafile.h>
#include <mp4file.h>
#include <mp4properties.h>
#include <mpcfile.h>
#include <mpegfile.h>
#include <mpegproperties.h>
#include <flacproperties.h>
#include <shortenproperties.h>
#include <trueaudioproperties.h>
#include <oggflacfile.h>
#include <opusfile.h>
#include <shortenfile.h>
#include <speexfile.h>
#include <tpropertymap.h>
#include <trueaudiofile.h>
#include <tvariant.h>
#include <vorbisfile.h>
#include <wavfile.h>
#include <wavpackfile.h>
#include <wavpackproperties.h>
#include <wavproperties.h>

#include "fd_stream.h"
#include "jni_bridge.h"

namespace piplayer {
namespace {

// Picture modes, the ordinals of Pictures in TagReader.kt.
constexpr jint PICTURES_SIZES = 1;
constexpr jint PICTURES_DATA = 2;

// Status codes shared with TagReader.kt.
constexpr jint OK = 0;
constexpr jint INVALID_FD = 1;
constexpr jint NOT_SEEKABLE = 2;
constexpr jint UNSUPPORTED_FORMAT = 3;
constexpr jint NATIVE_ERROR = 4;
constexpr jint IO_ERROR = 5;

/** Embedded pictures larger than this are left out rather than copied into the Java heap. */
constexpr unsigned int MAX_PICTURE_BYTES = 64u * 1024 * 1024;

jclass stringClass = nullptr;
jmethodID sinkProperty = nullptr;
jmethodID sinkAudio = nullptr;
jmethodID sinkPicture = nullptr;
jmethodID sinkContainer = nullptr;
jmethodID sinkTechnical = nullptr;

/** A byte at or above 0x80 of a legacy field arrives in Java as this plus the byte (LegacyText.MARK_BASE). */
constexpr wchar_t MARK_BASE = 0xE000;

/** The bytes up to the first NUL, each one at or above 0x80 marked. */
TagLib::String markedLegacy(const TagLib::ByteVector &data) {
  std::wstring text;
  text.reserve(data.size());
  for (unsigned int i = 0; i < data.size(); i++) {
    auto byte = static_cast<unsigned char>(data[i]);
    if (byte == 0) break;
    text.push_back(byte < 0x80 ? static_cast<wchar_t>(byte) : static_cast<wchar_t>(MARK_BASE + byte));
  }
  return TagLib::String(text);
}

class MarkingId3v2Handler : public TagLib::ID3v2::Latin1StringHandler {
 public:
  TagLib::String parse(const TagLib::ByteVector &data) const override { return markedLegacy(data); }
};

class MarkingId3v1Handler : public TagLib::ID3v1::StringHandler {
 public:
  // TagLib's own handler trims the padding of the fixed fields too.
  TagLib::String parse(const TagLib::ByteVector &data) const override { return markedLegacy(data).stripWhiteSpace(); }
};

class MarkingRiffHandler : public TagLib::RIFF::Info::StringHandler {
 public:
  TagLib::String parse(const TagLib::ByteVector &data) const override { return markedLegacy(data); }
};

/**
 * Marks legacy text for one read. TagLib holds its string handlers per process, and `:tagger` runs one call at a time;
 * no later read or write sees them, so that a write never stores marked text (TI §7.5).
 */
class LegacyMarking {
 public:
  explicit LegacyMarking(bool enabled) : enabled_(enabled) {
    if (!enabled_) return;
    TagLib::ID3v2::Tag::setLatin1StringHandler(&id3v2);
    TagLib::ID3v1::Tag::setStringHandler(&id3v1);
    TagLib::RIFF::Info::Tag::setStringHandler(&riff);
  }
  ~LegacyMarking() {
    if (!enabled_) return;
    TagLib::ID3v2::Tag::setLatin1StringHandler(nullptr);
    TagLib::ID3v1::Tag::setStringHandler(nullptr);
    TagLib::RIFF::Info::Tag::setStringHandler(nullptr);
  }
  LegacyMarking(const LegacyMarking &) = delete;
  LegacyMarking &operator=(const LegacyMarking &) = delete;

 private:
  bool enabled_;
  const MarkingId3v2Handler id3v2;
  const MarkingId3v1Handler id3v1;
  const MarkingRiffHandler riff;
};

/**
 * Builds a Java string from TagLib's UTF-16 code units. Going through UTF-8 would lose a whole value that holds an
 * unpaired surrogate and would cut it at an embedded NUL. Returns null with an exception pending on failure.
 */
jstring toJava(JNIEnv *env, const TagLib::String &value) {
  std::wstring wide = value.toWString();
  std::vector<jchar> units(wide.begin(), wide.end());
  return env->NewString(units.data(), static_cast<jsize>(units.size()));
}

/**
 * Calls back into the sink. Every JNI result is checked before the next call: once Java has thrown (for example
 * OutOfMemoryError), no further JNI call is allowed, so the function returns false and leaves the exception pending.
 */
bool sendProperties(JNIEnv *env, jobject sink, const TagLib::PropertyMap &properties) {
  for (const auto &[key, values] : properties) {
    jstring jKey = toJava(env, key);
    if (jKey == nullptr) return false;
    jobjectArray jValues = env->NewObjectArray(static_cast<jsize>(values.size()), stringClass, nullptr);
    if (jValues == nullptr) return false;
    jsize index = 0;
    for (const auto &value : values) {
      jstring jValue = toJava(env, value);
      if (jValue == nullptr) return false;
      env->SetObjectArrayElement(jValues, index++, jValue);
      env->DeleteLocalRef(jValue);
    }
    env->CallVoidMethod(sink, sinkProperty, jKey, jValues);
    env->DeleteLocalRef(jKey);
    env->DeleteLocalRef(jValues);
    if (env->ExceptionCheck()) return false;
  }
  return true;
}

/** Sends each picture with its bytes, or with its size only unless [withData]. */
bool sendPictures(JNIEnv *env, jobject sink, TagLib::FileRef &file, bool withData) {
  for (const auto &picture : file.complexProperties("PICTURE")) {
    TagLib::ByteVector data = picture.value("data").toByteVector();
    jbyteArray jData = nullptr;
    if (withData) {
      if (data.size() > MAX_PICTURE_BYTES) continue;
      jData = env->NewByteArray(static_cast<jsize>(data.size()));
      if (jData == nullptr) return false;
      env->SetByteArrayRegion(jData, 0, static_cast<jsize>(data.size()), reinterpret_cast<const jbyte *>(data.data()));
    }
    auto size = static_cast<jint>(std::min<unsigned int>(data.size(), std::numeric_limits<jint>::max()));
    jstring mimeType = toJava(env, picture.value("mimeType").toString());
    if (mimeType == nullptr) return false;
    jstring pictureType = toJava(env, picture.value("pictureType").toString());
    if (pictureType == nullptr) return false;
    jstring description = toJava(env, picture.value("description").toString());
    if (description == nullptr) return false;
    env->CallVoidMethod(sink, sinkPicture, jData, size, mimeType, pictureType, description);
    if (jData != nullptr) env->DeleteLocalRef(jData);
    env->DeleteLocalRef(mimeType);
    env->DeleteLocalRef(pictureType);
    env->DeleteLocalRef(description);
    if (env->ExceptionCheck()) return false;
  }
  return true;
}

/**
 * What holds the audio. TagLib opens an AAC stream without a container as an MPEG file, so the two are told apart by
 * its audio properties. Null for the formats nothing in the library cares about, such as tracker modules.
 */
const char *containerOf(TagLib::File *file) {
  if (auto *mpeg = dynamic_cast<TagLib::MPEG::File *>(file)) {
    const TagLib::MPEG::Properties *audio = mpeg->audioProperties();
    return audio != nullptr && audio->isADTS() ? "adts" : "mpeg";
  }
  if (dynamic_cast<TagLib::FLAC::File *>(file) != nullptr) return "flac";
  if (dynamic_cast<TagLib::MP4::File *>(file) != nullptr) return "mp4";
  if (dynamic_cast<TagLib::Ogg::Vorbis::File *>(file) != nullptr) return "ogg-vorbis";
  if (dynamic_cast<TagLib::Ogg::Opus::File *>(file) != nullptr) return "ogg-opus";
  if (dynamic_cast<TagLib::Ogg::FLAC::File *>(file) != nullptr) return "ogg-flac";
  if (dynamic_cast<TagLib::Ogg::Speex::File *>(file) != nullptr) return "ogg-speex";
  if (dynamic_cast<TagLib::RIFF::WAV::File *>(file) != nullptr) return "wav";
  if (dynamic_cast<TagLib::RIFF::AIFF::File *>(file) != nullptr) return "aiff";
  if (dynamic_cast<TagLib::ASF::File *>(file) != nullptr) return "asf";
  if (dynamic_cast<TagLib::APE::File *>(file) != nullptr) return "ape";
  if (dynamic_cast<TagLib::WavPack::File *>(file) != nullptr) return "wavpack";
  if (dynamic_cast<TagLib::MPC::File *>(file) != nullptr) return "mpc";
  if (dynamic_cast<TagLib::TrueAudio::File *>(file) != nullptr) return "tta";
  if (dynamic_cast<TagLib::DSF::File *>(file) != nullptr) return "dsf";
  if (dynamic_cast<TagLib::DSDIFF::File *>(file) != nullptr) return "dsdiff";
  if (dynamic_cast<TagLib::Shorten::File *>(file) != nullptr) return "shorten";
  if (dynamic_cast<TagLib::Matroska::File *>(file) != nullptr) return "matroska";
  return nullptr;
}

bool sendContainer(JNIEnv *env, jobject sink, TagLib::File *file) {
  const char *name = containerOf(file);
  if (name == nullptr) return true;
  jstring jName = env->NewStringUTF(name);
  if (jName == nullptr) return false;
  env->CallVoidMethod(sink, sinkContainer, jName);
  env->DeleteLocalRef(jName);
  return !env->ExceptionCheck();
}

/** The coding of the audio; null where TagLib cannot say. */
const char *codecOf(TagLib::File *file) {
  if (auto *mpeg = dynamic_cast<TagLib::MPEG::File *>(file)) {
    const TagLib::MPEG::Properties *audio = mpeg->audioProperties();
    if (audio == nullptr) return nullptr;
    if (audio->isADTS()) return "aac";
    switch (audio->layer()) {
      case 1: return "mp1";
      case 2: return "mp2";
      case 3: return "mp3";
      default: return nullptr;
    }
  }
  if (auto *mp4 = dynamic_cast<TagLib::MP4::File *>(file)) {
    const TagLib::MP4::Properties *audio = mp4->audioProperties();
    if (audio == nullptr) return nullptr;
    switch (audio->codec()) {
      case TagLib::MP4::Properties::AAC: return "aac";
      case TagLib::MP4::Properties::ALAC: return "alac";
      default: return nullptr;
    }
  }
  if (auto *wav = dynamic_cast<TagLib::RIFF::WAV::File *>(file)) {
    const TagLib::RIFF::WAV::Properties *audio = wav->audioProperties();
    return audio != nullptr && audio->format() == 1 ? "pcm" : nullptr;
  }
  if (dynamic_cast<TagLib::FLAC::File *>(file) != nullptr) return "flac";
  if (dynamic_cast<TagLib::Ogg::FLAC::File *>(file) != nullptr) return "flac";
  if (dynamic_cast<TagLib::Ogg::Vorbis::File *>(file) != nullptr) return "vorbis";
  if (dynamic_cast<TagLib::Ogg::Opus::File *>(file) != nullptr) return "opus";
  if (dynamic_cast<TagLib::Ogg::Speex::File *>(file) != nullptr) return "speex";
  if (dynamic_cast<TagLib::RIFF::AIFF::File *>(file) != nullptr) return "pcm";
  if (dynamic_cast<TagLib::ASF::File *>(file) != nullptr) return "wma";
  if (dynamic_cast<TagLib::APE::File *>(file) != nullptr) return "ape";
  if (dynamic_cast<TagLib::WavPack::File *>(file) != nullptr) return "wavpack";
  if (dynamic_cast<TagLib::MPC::File *>(file) != nullptr) return "mpc";
  if (dynamic_cast<TagLib::TrueAudio::File *>(file) != nullptr) return "tta";
  if (dynamic_cast<TagLib::DSF::File *>(file) != nullptr) return "dsd";
  if (dynamic_cast<TagLib::DSDIFF::File *>(file) != nullptr) return "dsd";
  if (dynamic_cast<TagLib::Shorten::File *>(file) != nullptr) return "shorten";
  return nullptr;
}

/** The sample size of a lossless coding, else 0: a lossy file has none, whatever its decoder would give. */
int bitDepthOf(TagLib::File *file) {
  if (auto *flac = dynamic_cast<TagLib::FLAC::File *>(file)) {
    return flac->audioProperties() != nullptr ? flac->audioProperties()->bitsPerSample() : 0;
  }
  if (auto *mp4 = dynamic_cast<TagLib::MP4::File *>(file)) {
    const TagLib::MP4::Properties *audio = mp4->audioProperties();
    return audio != nullptr && audio->codec() == TagLib::MP4::Properties::ALAC ? audio->bitsPerSample() : 0;
  }
  if (auto *wav = dynamic_cast<TagLib::RIFF::WAV::File *>(file)) {
    const TagLib::RIFF::WAV::Properties *audio = wav->audioProperties();
    if (audio == nullptr) return 0;
    // PCM and IEEE float; anything else there, such as ADPCM, is lossy.
    return audio->format() == 1 || audio->format() == 3 ? audio->bitsPerSample() : 0;
  }
  if (auto *aiff = dynamic_cast<TagLib::RIFF::AIFF::File *>(file)) {
    return aiff->audioProperties() != nullptr ? aiff->audioProperties()->bitsPerSample() : 0;
  }
  if (auto *ape = dynamic_cast<TagLib::APE::File *>(file)) {
    return ape->audioProperties() != nullptr ? ape->audioProperties()->bitsPerSample() : 0;
  }
  if (auto *wavPack = dynamic_cast<TagLib::WavPack::File *>(file)) {
    return wavPack->audioProperties() != nullptr ? wavPack->audioProperties()->bitsPerSample() : 0;
  }
  if (auto *trueAudio = dynamic_cast<TagLib::TrueAudio::File *>(file)) {
    return trueAudio->audioProperties() != nullptr ? trueAudio->audioProperties()->bitsPerSample() : 0;
  }
  if (auto *shorten = dynamic_cast<TagLib::Shorten::File *>(file)) {
    return shorten->audioProperties() != nullptr ? shorten->audioProperties()->bitsPerSample() : 0;
  }
  if (auto *oggFlac = dynamic_cast<TagLib::Ogg::FLAC::File *>(file)) {
    return oggFlac->audioProperties() != nullptr ? oggFlac->audioProperties()->bitsPerSample() : 0;
  }
  return 0;
}

bool sendTechnical(JNIEnv *env, jobject sink, TagLib::File *file) {
  const char *codec = codecOf(file);
  jstring jCodec = nullptr;
  if (codec != nullptr) {
    jCodec = env->NewStringUTF(codec);
    if (jCodec == nullptr) return false;
  }
  env->CallVoidMethod(sink, sinkTechnical, jCodec, bitDepthOf(file));
  if (jCodec != nullptr) env->DeleteLocalRef(jCodec);
  return !env->ExceptionCheck();
}

/** Whether a field says anything: a frame may hold only its encoding byte. */
bool saysSomething(const TagLib::StringList &values) {
  for (const auto &value : values) {
    if (!value.stripWhiteSpace().isEmpty()) return true;
  }
  return false;
}

void fillMissing(TagLib::PropertyMap &properties, const TagLib::Tag *tag) {
  if (tag == nullptr) return;
  for (const auto &[key, values] : tag->properties()) {
    if (!saysSomething(values)) continue;
    if (!properties.contains(key) || !saysSomething(properties[key])) properties.replace(key, values);
  }
}

/**
 * The file's properties. TagLib gives only those of its first tag that holds any, so a field only an older tag has would
 * be lost: the next tags fill in what is missing, ID3v2 before APE before ID3v1 (TI §3.6).
 */
TagLib::PropertyMap propertiesOf(TagLib::FileRef &ref) {
  TagLib::PropertyMap properties = ref.properties();
  TagLib::File *file = ref.file();
  if (auto *mpeg = dynamic_cast<TagLib::MPEG::File *>(file)) {
    fillMissing(properties, mpeg->ID3v2Tag());
    fillMissing(properties, mpeg->APETag());
    fillMissing(properties, mpeg->ID3v1Tag());
  } else if (auto *flac = dynamic_cast<TagLib::FLAC::File *>(file)) {
    fillMissing(properties, flac->ID3v2Tag());
    fillMissing(properties, flac->ID3v1Tag());
  } else if (auto *wav = dynamic_cast<TagLib::RIFF::WAV::File *>(file)) {
    fillMissing(properties, wav->ID3v2Tag());
    fillMissing(properties, wav->InfoTag());
  } else if (auto *ape = dynamic_cast<TagLib::APE::File *>(file)) {
    fillMissing(properties, ape->APETag());
    fillMissing(properties, ape->ID3v1Tag());
  } else if (auto *wavPack = dynamic_cast<TagLib::WavPack::File *>(file)) {
    fillMissing(properties, wavPack->APETag());
    fillMissing(properties, wavPack->ID3v1Tag());
  } else if (auto *mpc = dynamic_cast<TagLib::MPC::File *>(file)) {
    fillMissing(properties, mpc->APETag());
    fillMissing(properties, mpc->ID3v1Tag());
  } else if (auto *trueAudio = dynamic_cast<TagLib::TrueAudio::File *>(file)) {
    fillMissing(properties, trueAudio->ID3v2Tag());
    fillMissing(properties, trueAudio->ID3v1Tag());
  }
  return properties;
}

bool hasMpegExtension(const std::string &fileName) {
  std::string::size_type dot = fileName.rfind('.');
  if (dot == std::string::npos) return false;
  std::string extension = fileName.substr(dot + 1);
  std::transform(extension.begin(), extension.end(), extension.begin(),
                 [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
  return extension == "mp3" || extension == "mp2" || extension == "aac";
}

/**
 * The name to give TagLib. An MPEG file chosen by its .mp3/.mp2/.aac extension never reports itself invalid, so a
 * misnamed file would be read as an empty MP3; such a name is dropped unless the content looks like MPEG, and
 * TagLib then detects the format from the content.
 */
std::string nameForDetection(int fd, const std::string &fileName) {
  if (!hasMpegExtension(fileName)) return fileName;
  FdStream probe(fd, fileName);
  return TagLib::MPEG::File::isSupported(&probe) ? fileName : std::string();
}

#ifdef PIPLAYER_TEST_HOOKS
/**
 * Debug builds only: a file that starts with one of these markers aborts or hangs the reader, as a malformed file
 * might, for the tests of the `:tagger` process (docs/library.md §4.5).
 */
void runTestHooks(int fd) {
  static constexpr char ABORT_MARKER[] = "PIPLAYER-TEST-ABORT";
  static constexpr char HANG_MARKER[] = "PIPLAYER-TEST-HANG";
  char head[32] = {};
  ssize_t count = pread(fd, head, sizeof(head), 0);
  auto startsWith = [&](const char *marker) {
    size_t length = strlen(marker);
    return count >= static_cast<ssize_t>(length) && memcmp(head, marker, length) == 0;
  };
  if (startsWith(ABORT_MARKER)) abort();
  if (startsWith(HANG_MARKER)) {
    for (;;) sleep(60);
  }
}
#endif

jint read(JNIEnv *env, jclass, jint fd, jstring jFileName, jint pictures, jboolean markLegacy, jobject sink) {
  if (fd < 0) return INVALID_FD;
  struct stat info {};
  if (fstat(fd, &info) != 0) return INVALID_FD;
  if (!S_ISREG(info.st_mode)) return NOT_SEEKABLE;
#ifdef PIPLAYER_TEST_HOOKS
  runTestHooks(fd);
#endif
  // TagLib takes an empty file named *.mp3 for a valid MPEG file without frames.
  if (info.st_size == 0) return UNSUPPORTED_FORMAT;

  std::string fileName;
  if (jFileName != nullptr) {
    const char *chars = env->GetStringUTFChars(jFileName, nullptr);
    if (chars == nullptr) return NATIVE_ERROR;
    fileName = chars;
    env->ReleaseStringUTFChars(jFileName, chars);
  }

  // No C++ exception may cross the JNI boundary.
  try {
    LegacyMarking marking(markLegacy == JNI_TRUE);
    FdStream stream(fd, nameForDetection(fd, fileName));
    TagLib::FileRef file(&stream, true, TagLib::AudioProperties::Average);
    if (stream.error() != 0) return IO_ERROR;
    if (file.isNull()) return UNSUPPORTED_FORMAT;

    if (!sendContainer(env, sink, file.file())) return NATIVE_ERROR;
    if (!sendTechnical(env, sink, file.file())) return NATIVE_ERROR;
    if (!sendProperties(env, sink, propertiesOf(file))) return NATIVE_ERROR;
    if (const TagLib::AudioProperties *audio = file.audioProperties()) {
      env->CallVoidMethod(sink, sinkAudio, audio->lengthInMilliseconds(), audio->bitrate(), audio->sampleRate(),
                          audio->channels());
      if (env->ExceptionCheck()) return NATIVE_ERROR;
    }
    if ((pictures == PICTURES_SIZES || pictures == PICTURES_DATA) &&
        !sendPictures(env, sink, file, pictures == PICTURES_DATA)) {
      return NATIVE_ERROR;
    }
    return stream.error() != 0 ? IO_ERROR : OK;
  } catch (...) {
    return NATIVE_ERROR;
  }
}

}  // namespace

bool registerTagReader(JNIEnv *env) {
  jclass reader = env->FindClass("io/github/lorddetson/piplayer/nativelib/taglib/TagReader");
  jclass sink = env->FindClass("io/github/lorddetson/piplayer/nativelib/taglib/TagSink");
  jclass string = env->FindClass("java/lang/String");
  if (reader == nullptr || sink == nullptr || string == nullptr) return false;
  stringClass = static_cast<jclass>(env->NewGlobalRef(string));
  sinkProperty = env->GetMethodID(sink, "property", "(Ljava/lang/String;[Ljava/lang/String;)V");
  sinkAudio = env->GetMethodID(sink, "audio", "(IIII)V");
  sinkPicture = env->GetMethodID(sink, "picture", "([BILjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
  sinkContainer = env->GetMethodID(sink, "container", "(Ljava/lang/String;)V");
  sinkTechnical = env->GetMethodID(sink, "technical", "(Ljava/lang/String;I)V");
  if (sinkProperty == nullptr || sinkAudio == nullptr || sinkPicture == nullptr || sinkContainer == nullptr ||
      sinkTechnical == nullptr) {
    return false;
  }

  static const JNINativeMethod methods[] = {
      {"nativeRead", "(ILjava/lang/String;IZLio/github/lorddetson/piplayer/nativelib/taglib/TagSink;)I",
       reinterpret_cast<void *>(read)},
  };
  return env->RegisterNatives(reader, methods, 1) == JNI_OK;
}

}  // namespace piplayer
