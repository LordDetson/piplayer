#include <algorithm>
#include <cctype>
#include <limits>
#include <string>
#include <vector>

#include <jni.h>
#include <sys/stat.h>

#include <fileref.h>
#include <mpegfile.h>
#include <tpropertymap.h>
#include <tvariant.h>

#include "fd_stream.h"
#include "jni_bridge.h"

namespace piplayer {
namespace {

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

bool sendPictures(JNIEnv *env, jobject sink, TagLib::FileRef &file) {
  for (const auto &picture : file.complexProperties("PICTURE")) {
    TagLib::ByteVector data = picture.value("data").toByteVector();
    if (data.size() > MAX_PICTURE_BYTES) continue;
    jbyteArray jData = env->NewByteArray(static_cast<jsize>(data.size()));
    if (jData == nullptr) return false;
    env->SetByteArrayRegion(jData, 0, static_cast<jsize>(data.size()), reinterpret_cast<const jbyte *>(data.data()));
    jstring mimeType = toJava(env, picture.value("mimeType").toString());
    if (mimeType == nullptr) return false;
    jstring pictureType = toJava(env, picture.value("pictureType").toString());
    if (pictureType == nullptr) return false;
    jstring description = toJava(env, picture.value("description").toString());
    if (description == nullptr) return false;
    env->CallVoidMethod(sink, sinkPicture, jData, mimeType, pictureType, description);
    env->DeleteLocalRef(jData);
    env->DeleteLocalRef(mimeType);
    env->DeleteLocalRef(pictureType);
    env->DeleteLocalRef(description);
    if (env->ExceptionCheck()) return false;
  }
  return true;
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

jint read(JNIEnv *env, jclass, jint fd, jstring jFileName, jboolean readPictures, jobject sink) {
  if (fd < 0) return INVALID_FD;
  struct stat info {};
  if (fstat(fd, &info) != 0) return INVALID_FD;
  if (!S_ISREG(info.st_mode)) return NOT_SEEKABLE;
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
    FdStream stream(fd, nameForDetection(fd, fileName));
    TagLib::FileRef file(&stream, true, TagLib::AudioProperties::Average);
    if (stream.error() != 0) return IO_ERROR;
    if (file.isNull()) return UNSUPPORTED_FORMAT;

    if (!sendProperties(env, sink, file.properties())) return NATIVE_ERROR;
    if (const TagLib::AudioProperties *audio = file.audioProperties()) {
      env->CallVoidMethod(sink, sinkAudio, audio->lengthInMilliseconds(), audio->bitrate(), audio->sampleRate(),
                          audio->channels());
      if (env->ExceptionCheck()) return NATIVE_ERROR;
    }
    if (readPictures && !sendPictures(env, sink, file)) return NATIVE_ERROR;
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
  sinkPicture = env->GetMethodID(sink, "picture", "([BLjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
  if (sinkProperty == nullptr || sinkAudio == nullptr || sinkPicture == nullptr) return false;

  static const JNINativeMethod methods[] = {
      {"nativeRead", "(ILjava/lang/String;ZLio/github/lorddetson/piplayer/nativelib/taglib/TagSink;)I",
       reinterpret_cast<void *>(read)},
  };
  return env->RegisterNatives(reader, methods, 1) == JNI_OK;
}

}  // namespace piplayer
