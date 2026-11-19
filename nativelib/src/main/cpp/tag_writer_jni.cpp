#include <algorithm>
#include <optional>
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
constexpr jsize ID3V1_SIZE = 128;

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

bool updateId3v1Text(TagLib::ByteVector &tag, const TagLib::String &value, size_t offset, size_t width) {
  if (!value.isLatin1()) return false;
  const auto bytes = value.data(TagLib::String::Latin1);
  if (bytes.size() > width) return false;
  std::fill_n(tag.begin() + offset, width, '\0');
  std::copy(bytes.begin(), bytes.end(), tag.begin() + offset);
  return true;
}

// A missing result means the property has no ID3v1 field. A rejected value keeps the existing bytes.
std::optional<bool> updateId3v1Field(TagLib::ByteVector &tag, const TagLib::String &key,
                                   const TagLib::StringList &values) {
  const bool single = values.size() <= 1;
  const auto value = values.isEmpty() ? TagLib::String() : values.front();
  if (key == "TITLE") return single && updateId3v1Text(tag, value, 3, 30);
  if (key == "ARTIST") return single && updateId3v1Text(tag, value, 33, 30);
  if (key == "ALBUM") return single && updateId3v1Text(tag, value, 63, 30);
  if (key == "DATE") {
    return single && (value.isEmpty() || (value.size() == 4 && value.toInt() >= 1000)) &&
           updateId3v1Text(tag, value, 93, 4);
  }
  if (key == "COMMENT") {
    return single && updateId3v1Text(tag, value, 97, tag[125] == 0 && tag[126] != 0 ? 28 : 30);
  }
  if (key == "TRACKNUMBER") {
    if (!single || tag[125] != 0) return false;
    bool numeric = false;
    const auto track = value.toInt(&numeric);
    if (!value.isEmpty() && (!numeric || track <= 0 || track >= 256)) return false;
    tag[126] = static_cast<char>(track);
    return true;
  }
  if (key == "GENRE") {
    if (!single) return false;
    const auto genre = TagLib::ID3v1::genreIndex(value);
    if (!value.isEmpty() && (genre < 0 || genre >= 255)) return false;
    tag[127] = static_cast<char>(value.isEmpty() ? 255 : genre);
    return true;
  }
  return std::nullopt;
}

bool validId3v1(const TagLib::ByteVector &tag) {
  return tag.size() == ID3V1_SIZE && tag.startsWith(TagLib::ByteVector::fromCString("TAG"));
}

void updateId3v1(TagLib::ByteVector &tag, const TagLib::PropertyMap &changes, std::vector<std::string> *kept = nullptr) {
  for (const auto &[key, values] : changes) {
    const auto updated = updateId3v1Field(tag, key, values);
    if (kept && updated == false) kept->push_back(key.to8Bit());
  }
}

bool updateExistingId3v1(TagLib::MPEG::File *file, const TagLib::PropertyMap &changes) {
  // Edit only the bytes of selected fields. Re-rendering the frame can change the padding or the old comment.
  file->seek(-ID3V1_SIZE, TagLib::File::End);
  auto tag = file->readBlock(ID3V1_SIZE);
  if (!validId3v1(tag)) return false;
  const auto before = tag;
  updateId3v1(tag, changes);
  if (tag != before) {
    file->seek(-ID3V1_SIZE, TagLib::File::End);
    file->writeBlock(tag);
  }
  return true;
}

jobjectArray previewId3v1(JNIEnv *env, jclass, jbyteArray rawTag, jobjectArray keys, jobjectArray values) {
  if (env->GetArrayLength(rawTag) != ID3V1_SIZE) return nullptr;
  try {
    TagLib::ByteVector tag(ID3V1_SIZE, '\0');
    env->GetByteArrayRegion(rawTag, 0, ID3V1_SIZE, reinterpret_cast<jbyte *>(tag.data()));
    TagLib::PropertyMap changes;
    if (env->ExceptionCheck() || !validId3v1(tag) || !readChanges(env, keys, values, changes)) return nullptr;

    std::vector<std::string> kept;
    updateId3v1(tag, changes, &kept);
    const auto stringClass = env->FindClass("java/lang/String");
    if (stringClass == nullptr) return nullptr;
    const auto result = env->NewObjectArray(static_cast<jsize>(kept.size()), stringClass, nullptr);
    if (result == nullptr) return nullptr;
    for (jsize i = 0; i < static_cast<jsize>(kept.size()); i++) {
      const auto key = env->NewStringUTF(kept[i].c_str());
      if (key == nullptr) return nullptr;
      env->SetObjectArrayElement(result, i, key);
      env->DeleteLocalRef(key);
      if (env->ExceptionCheck()) return nullptr;
    }
    return result;
  } catch (...) {
    return nullptr;
  }
}

bool restoreId3v1Tag(TagLib::MPEG::File *file, const TagLib::ByteVector &tag) {
  if (tag.isEmpty()) return !file->hasID3v1Tag() || file->strip(TagLib::MPEG::File::ID3v1);
  file->seek(file->hasID3v1Tag() ? -ID3V1_SIZE : 0, TagLib::File::End);
  file->writeBlock(tag);
  return true;
}

bool save(TagLib::File *file, const TagLib::PropertyMap &changes, Id3v1Policy policy, const PictureList *fronts,
          const TagLib::ByteVector *restoredId3v1) {
  const bool writePrimary = !changes.isEmpty() || fronts != nullptr;
  if (auto *mpeg = dynamic_cast<TagLib::MPEG::File *>(file)) {
    if (!restoredId3v1 && policy == Id3v1Policy::Remove &&
        mpeg->hasID3v1Tag() && !mpeg->strip(TagLib::MPEG::File::ID3v1)) return false;
    if (!writePrimary) return !restoredId3v1 || restoreId3v1Tag(mpeg, *restoredId3v1);
    const bool hasId3 = mpeg->hasID3v2Tag();
    auto *tag = mpeg->ID3v2Tag(true);
    const auto version = versionOf(tag, hasId3);
    if (!changes.isEmpty() && !tag->setProperties(merge(tag->properties(), changes)).isEmpty()) return false;
    if (fronts && !replaceFrontCovers(tag, *fronts)) return false;
    if (!mpeg->save(TagLib::MPEG::File::ID3v2, TagLib::File::StripNone, version,
                    TagLib::File::DoNotDuplicate)) return false;
    if (restoredId3v1) return restoreId3v1Tag(mpeg, *restoredId3v1);
    return policy != Id3v1Policy::Update || !mpeg->hasID3v1Tag() || updateExistingId3v1(mpeg, changes);
  }
  if (restoredId3v1) return false;
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
               jobjectArray pictureMimes, jobjectArray pictureDescriptions, jobjectArray pictureBytes,
               jbyteArray restoreId3v1) {
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
    TagLib::ByteVector legacy;
    if (restoreId3v1) {
      const auto size = env->GetArrayLength(restoreId3v1);
      if (size != 0 && size != ID3V1_SIZE) return JNI_FALSE;
      if (size == ID3V1_SIZE) {
        legacy.resize(size);
        env->GetByteArrayRegion(restoreId3v1, 0, size, reinterpret_cast<jbyte *>(legacy.data()));
        if (env->ExceptionCheck() || !validId3v1(legacy)) return JNI_FALSE;
      }
    }
    if (changes.isEmpty() && !pictureBytes && !restoreId3v1 && policy != Id3v1Policy::Remove) return JNI_TRUE;
    // FileStream opens its own descriptor. It never closes or moves the caller's descriptor.
    const auto path = "/proc/self/fd/" + std::to_string(fd);
    TagLib::FileStream stream(path.c_str());
    if (!stream.isOpen() || stream.readOnly()) return JNI_FALSE;
    TagLib::FileRef file(&stream, false);
    return !file.isNull() && save(file.file(), changes, policy, pictureBytes ? &fronts : nullptr, restoreId3v1 ? &legacy : nullptr)
               ? JNI_TRUE : JNI_FALSE;
  } catch (...) {
    return JNI_FALSE;
  }
}

}  // namespace

bool registerTagWriter(JNIEnv *env) {
  const auto writer = env->FindClass("io/github/lorddetson/piplayer/nativelib/taglib/TagWriter");
  if (writer == nullptr) return false;
  static const JNINativeMethod methods[] = {
      {"nativeWrite", "(I[Ljava/lang/String;[[Ljava/lang/String;I[Ljava/lang/String;[Ljava/lang/String;[[B[B)Z",
       reinterpret_cast<void *>(write)},
      {"nativePreviewId3v1", "([B[Ljava/lang/String;[[Ljava/lang/String;)[Ljava/lang/String;",
       reinterpret_cast<void *>(previewId3v1)},
  };
  return env->RegisterNatives(writer, methods, sizeof(methods) / sizeof(methods[0])) == JNI_OK;
}

}  // namespace piplayer
