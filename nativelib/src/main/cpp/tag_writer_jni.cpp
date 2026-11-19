#include <algorithm>
#include <string>
#include <vector>

#include <fcntl.h>
#include <jni.h>
#include <sys/stat.h>

#include <fileref.h>
#include <flacfile.h>
#include <id3v1genres.h>
#include <id3v2header.h>
#include <id3v2tag.h>
#include <infotag.h>
#include <mp4file.h>
#include <mpegfile.h>
#include <opusfile.h>
#include <tfilestream.h>
#include <tpropertymap.h>
#include <tvariant.h>
#include <vorbisfile.h>
#include <wavfile.h>

#include "jni_bridge.h"

namespace piplayer {
namespace {

enum class Id3v1Policy { Update = 0, Remove = 1, Leave = 2 };
using PictureList = TagLib::List<TagLib::VariantMap>;

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

bool readFrontCovers(JNIEnv *env, jobjectArray mimes, jobjectArray descriptions, jobjectArray bytes,
                     PictureList &covers) {
  if (mimes == nullptr || descriptions == nullptr) return false;
  const auto count = env->GetArrayLength(bytes);
  if (count != env->GetArrayLength(mimes) || count != env->GetArrayLength(descriptions)) return false;
  for (jsize i = 0; i < count; i++) {
    auto mime = static_cast<jstring>(env->GetObjectArrayElement(mimes, i));
    auto description = static_cast<jstring>(env->GetObjectArrayElement(descriptions, i));
    auto picture = static_cast<jbyteArray>(env->GetObjectArrayElement(bytes, i));
    if (mime == nullptr || description == nullptr || picture == nullptr) return false;
    const auto size = env->GetArrayLength(picture);
    if (size == 0) return false;
    TagLib::ByteVector data(size, '\0');
    env->GetByteArrayRegion(picture, 0, size, reinterpret_cast<jbyte *>(data.data()));
    TagLib::VariantMap properties;
    properties.insert("data", data);
    properties.insert("mimeType", fromJava(env, mime));
    properties.insert("description", fromJava(env, description));
    properties.insert("pictureType", "Front Cover");
    covers.append(properties);
    env->DeleteLocalRef(mime);
    env->DeleteLocalRef(description);
    env->DeleteLocalRef(picture);
    if (env->ExceptionCheck()) return false;
  }
  return true;
}

template <typename Metadata>
bool replaceFrontCovers(Metadata *metadata, const PictureList &fronts, bool firstIsFront = false) {
  const PictureList current = metadata->complexProperties("PICTURE");
  PictureList updated;
  bool first = true;
  bool inserted = false;
  for (const auto &picture : current) {
    const bool front = firstIsFront ? first : picture.value("pictureType").value<TagLib::String>() == "Front Cover";
    first = false;
    if (!front) updated.append(picture);
    else if (!inserted) {
      updated.append(fronts);
      inserted = true;
    }
  }
  if (!inserted) updated.append(fronts);
  return metadata->setComplexProperties("PICTURE", updated);
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

void updateId3v1Text(TagLib::ByteVector &tag, const TagLib::String &value, size_t offset, size_t width) {
  if (!value.isLatin1()) return;
  const auto bytes = value.data(TagLib::String::Latin1);
  if (bytes.size() > width) return;
  std::fill_n(tag.begin() + offset, width, '\0');
  std::copy(bytes.begin(), bytes.end(), tag.begin() + offset);
}

bool updateExistingId3v1(TagLib::MPEG::File *file, const TagLib::PropertyMap &changes) {
  // Edit only the bytes of selected fields. Re-rendering the frame can change the padding or the old comment.
  constexpr int size = 128;
  file->seek(-size, TagLib::File::End);
  auto tag = file->readBlock(size);
  if (tag.size() != size || !tag.startsWith(TagLib::ByteVector::fromCString("TAG"))) return false;
  const auto before = tag;
  for (const auto &[key, values] : changes) {
    if (values.size() > 1) continue;
    const auto value = values.isEmpty() ? TagLib::String() : values.front();
    if (key == "TITLE") updateId3v1Text(tag, value, 3, 30);
    else if (key == "ARTIST") updateId3v1Text(tag, value, 33, 30);
    else if (key == "ALBUM") updateId3v1Text(tag, value, 63, 30);
    else if (key == "DATE" && (value.isEmpty() || (value.size() == 4 && value.toInt() >= 1000))) {
      updateId3v1Text(tag, value, 93, 4);
    } else if (key == "COMMENT") {
      updateId3v1Text(tag, value, 97, tag[125] == 0 && tag[126] != 0 ? 28 : 30);
    } else if (key == "TRACKNUMBER" && tag[125] == 0) {
      bool numeric = false;
      const auto track = value.toInt(&numeric);
      if (value.isEmpty() || (numeric && track > 0 && track < 256)) tag[126] = static_cast<char>(track);
    } else if (key == "GENRE") {
      const auto genre = TagLib::ID3v1::genreIndex(value);
      if (value.isEmpty() || (genre >= 0 && genre < 255)) tag[127] = static_cast<char>(value.isEmpty() ? 255 : genre);
    }
  }
  if (tag != before) {
    file->seek(-size, TagLib::File::End);
    file->writeBlock(tag);
  }
  return true;
}

bool save(TagLib::File *file, const TagLib::PropertyMap &changes, Id3v1Policy policy, const PictureList *fronts) {
  const bool writePrimary = !changes.isEmpty() || fronts != nullptr;
  if (auto *mpeg = dynamic_cast<TagLib::MPEG::File *>(file)) {
    if (policy == Id3v1Policy::Remove && mpeg->hasID3v1Tag() && !mpeg->strip(TagLib::MPEG::File::ID3v1)) return false;
    if (!writePrimary) return true;
    const bool hasId3 = mpeg->hasID3v2Tag();
    auto *tag = mpeg->ID3v2Tag(true);
    const auto version = versionOf(tag, hasId3);
    if (!changes.isEmpty() && !tag->setProperties(merge(tag->properties(), changes)).isEmpty()) return false;
    if (fronts && !replaceFrontCovers(tag, *fronts)) return false;
    if (!mpeg->save(TagLib::MPEG::File::ID3v2, TagLib::File::StripNone, version,
                    TagLib::File::DoNotDuplicate)) return false;
    return policy != Id3v1Policy::Update || !mpeg->hasID3v1Tag() || updateExistingId3v1(mpeg, changes);
  }
  if (!writePrimary) return true;
  if (auto *wav = dynamic_cast<TagLib::RIFF::WAV::File *>(file)) {
    auto *tag = wav->ID3v2Tag();
    const auto version = versionOf(tag, wav->hasID3v2Tag());
    if (!changes.isEmpty() && !tag->setProperties(merge(tag->properties(), changes)).isEmpty()) return false;
    if (fronts && !replaceFrontCovers(tag, *fronts)) return false;
    using Wav = TagLib::RIFF::WAV::File;
    auto tags = Wav::ID3v2;
    if (wav->hasInfoTag() && !changes.isEmpty()) {
      auto *info = wav->InfoTag();
      auto updated = merge(info->properties(), changes);
      info->setProperties(updated);
      tags = static_cast<Wav::TagTypes>(tags | Wav::Info);
    }
    return wav->save(tags, TagLib::File::StripNone, version);
  }
  if (dynamic_cast<TagLib::FLAC::File *>(file) || dynamic_cast<TagLib::MP4::File *>(file) ||
      dynamic_cast<TagLib::Ogg::Vorbis::File *>(file) || dynamic_cast<TagLib::Ogg::Opus::File *>(file)) {
    if (!changes.isEmpty() && !file->setProperties(merge(file->properties(), changes)).isEmpty()) return false;
    if (fronts && !replaceFrontCovers(file, *fronts, dynamic_cast<TagLib::MP4::File *>(file) != nullptr)) return false;
    return file->save();
  }
  return false;
}

jboolean write(JNIEnv *env, jclass, jint fd, jobjectArray keys, jobjectArray values, jint id3v1Policy,
               jobjectArray pictureMimes, jobjectArray pictureDescriptions, jobjectArray pictureBytes) {
  struct stat info {};
  const int flags = fcntl(fd, F_GETFL);
  if (flags < 0 || (flags & O_ACCMODE) != O_RDWR || fstat(fd, &info) != 0 ||
      !S_ISREG(info.st_mode) || info.st_size == 0) return JNI_FALSE;

  try {
    if (id3v1Policy < static_cast<int>(Id3v1Policy::Update) || id3v1Policy > static_cast<int>(Id3v1Policy::Leave)) {
      return JNI_FALSE;
    }
    const auto policy = static_cast<Id3v1Policy>(id3v1Policy);
    TagLib::PropertyMap changes;
    if (!readChanges(env, keys, values, changes)) return JNI_FALSE;
    PictureList fronts;
    if (pictureBytes && !readFrontCovers(env, pictureMimes, pictureDescriptions, pictureBytes, fronts)) return JNI_FALSE;
    if (changes.isEmpty() && !pictureBytes && policy != Id3v1Policy::Remove) return JNI_TRUE;
    // FileStream opens its own descriptor. It never closes or moves the caller's descriptor.
    const auto path = "/proc/self/fd/" + std::to_string(fd);
    TagLib::FileStream stream(path.c_str());
    if (!stream.isOpen() || stream.readOnly()) return JNI_FALSE;
    TagLib::FileRef file(&stream, false);
    return !file.isNull() && save(file.file(), changes, policy, pictureBytes ? &fronts : nullptr) ? JNI_TRUE : JNI_FALSE;
  } catch (...) {
    return JNI_FALSE;
  }
}

}  // namespace

bool registerTagWriter(JNIEnv *env) {
  const auto writer = env->FindClass("io/github/lorddetson/piplayer/nativelib/taglib/TagWriter");
  if (writer == nullptr) return false;
  static const JNINativeMethod methods[] = {
      {"nativeWrite", "(I[Ljava/lang/String;[[Ljava/lang/String;I[Ljava/lang/String;[Ljava/lang/String;[[B)Z",
       reinterpret_cast<void *>(write)},
  };
  return env->RegisterNatives(writer, methods, 1) == JNI_OK;
}

}  // namespace piplayer
