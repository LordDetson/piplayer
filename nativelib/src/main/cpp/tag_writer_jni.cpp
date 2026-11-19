#include <string>
#include <vector>

#include <fcntl.h>
#include <jni.h>
#include <sys/stat.h>

#include <fileref.h>
#include <flacfile.h>
#include <id3v2header.h>
#include <id3v2tag.h>
#include <infotag.h>
#include <mp4file.h>
#include <mpegfile.h>
#include <opusfile.h>
#include <tfilestream.h>
#include <tpropertymap.h>
#include <vorbisfile.h>
#include <wavfile.h>

#include "jni_bridge.h"

namespace piplayer {
namespace {

TagLib::String fromJava(JNIEnv *env, jstring value) {
  const jsize size = env->GetStringLength(value);
  if (size == 0) return {};
  std::vector<jchar> units(size);
  env->GetStringRegion(value, 0, size, units.data());
  return TagLib::String(std::wstring(units.begin(), units.end()));
}

bool readChanges(JNIEnv *env, jobjectArray keys, jobjectArray values, TagLib::PropertyMap &changes) {
  const jsize count = env->GetArrayLength(keys);
  if (count != env->GetArrayLength(values)) return false;
  for (jsize i = 0; i < count; i++) {
    auto key = static_cast<jstring>(env->GetObjectArrayElement(keys, i));
    if (key == nullptr) return false;
    auto name = fromJava(env, key);
    env->DeleteLocalRef(key);
    if (env->ExceptionCheck()) return false;

    auto items = static_cast<jobjectArray>(env->GetObjectArrayElement(values, i));
    if (items == nullptr) return false;
    TagLib::StringList strings;
    const jsize size = env->GetArrayLength(items);
    for (jsize j = 0; j < size; j++) {
      auto item = static_cast<jstring>(env->GetObjectArrayElement(items, j));
      if (item == nullptr) return false;
      strings.append(fromJava(env, item));
      env->DeleteLocalRef(item);
      if (env->ExceptionCheck()) return false;
    }
    env->DeleteLocalRef(items);
    if (!changes.replace(name, strings)) return false;
  }
  return true;
}

TagLib::PropertyMap merge(TagLib::PropertyMap properties, const TagLib::PropertyMap &changes) {
  for (const auto &[key, values] : changes) {
    if (values.isEmpty()) properties.erase(key);
    else properties.replace(key, values);
  }
  return properties;
}

TagLib::ID3v2::Version versionOf(TagLib::ID3v2::Tag *tag, bool exists) {
  return exists && tag->header()->majorVersion() < 4 ? TagLib::ID3v2::v3 : TagLib::ID3v2::v4;
}

bool save(TagLib::File *file, const TagLib::PropertyMap &changes) {
  if (auto *mpeg = dynamic_cast<TagLib::MPEG::File *>(file)) {
    const bool hasId3 = mpeg->hasID3v2Tag();
    auto *tag = mpeg->ID3v2Tag(true);
    const auto version = versionOf(tag, hasId3);
    if (!tag->setProperties(merge(tag->properties(), changes)).isEmpty()) return false;
    return mpeg->save(TagLib::MPEG::File::ID3v2, TagLib::File::StripNone, version,
                      TagLib::File::DoNotDuplicate);
  }
  if (auto *wav = dynamic_cast<TagLib::RIFF::WAV::File *>(file)) {
    auto *tag = wav->ID3v2Tag();
    const auto version = versionOf(tag, wav->hasID3v2Tag());
    if (!tag->setProperties(merge(tag->properties(), changes)).isEmpty()) return false;
    using Wav = TagLib::RIFF::WAV::File;
    auto tags = Wav::ID3v2;
    if (wav->hasInfoTag()) {
      auto *info = wav->InfoTag();
      auto updated = merge(info->properties(), changes);
      info->setProperties(updated);
      tags = static_cast<Wav::TagTypes>(tags | Wav::Info);
    }
    return wav->save(tags, TagLib::File::StripNone, version);
  }
  if (dynamic_cast<TagLib::FLAC::File *>(file) || dynamic_cast<TagLib::MP4::File *>(file) ||
      dynamic_cast<TagLib::Ogg::Vorbis::File *>(file) || dynamic_cast<TagLib::Ogg::Opus::File *>(file)) {
    return file->setProperties(merge(file->properties(), changes)).isEmpty() && file->save();
  }
  return false;
}

jboolean write(JNIEnv *env, jclass, jint fd, jobjectArray keys, jobjectArray values) {
  struct stat info {};
  const int flags = fcntl(fd, F_GETFL);
  if (flags < 0 || (flags & O_ACCMODE) != O_RDWR || fstat(fd, &info) != 0 ||
      !S_ISREG(info.st_mode) || info.st_size == 0) return JNI_FALSE;

  try {
    TagLib::PropertyMap changes;
    if (!readChanges(env, keys, values, changes)) return JNI_FALSE;
    if (changes.isEmpty()) return JNI_TRUE;
    // FileStream opens its own descriptor. It never closes or moves the caller's descriptor.
    const auto path = "/proc/self/fd/" + std::to_string(fd);
    TagLib::FileStream stream(path.c_str());
    if (!stream.isOpen() || stream.readOnly()) return JNI_FALSE;
    TagLib::FileRef file(&stream, false);
    return !file.isNull() && save(file.file(), changes) ? JNI_TRUE : JNI_FALSE;
  } catch (...) {
    return JNI_FALSE;
  }
}

}  // namespace

bool registerTagWriter(JNIEnv *env) {
  const auto writer = env->FindClass("io/github/lorddetson/piplayer/nativelib/taglib/TagWriter");
  if (writer == nullptr) return false;
  static const JNINativeMethod methods[] = {
      {"nativeWrite", "(I[Ljava/lang/String;[[Ljava/lang/String;)Z", reinterpret_cast<void *>(write)},
  };
  return env->RegisterNatives(writer, methods, 1) == JNI_OK;
}

}  // namespace piplayer
