#include <algorithm>
#include <cstdint>
#include <new>

#include <jni.h>

#include <chromaprint.h>

#include "jni_bridge.h"

// No C++ exception may cross the JNI boundary: every function catches them. std::bad_alloc becomes an
// OutOfMemoryError for the caller; anything else a failure result.

namespace piplayer {
namespace {

/** Samples copied from the Java array per chromaprint_feed call, so a long track is never duplicated natively. */
constexpr jint FEED_CHUNK = 16 * 1024;

ChromaprintContext *context(jlong handle) { return reinterpret_cast<ChromaprintContext *>(handle); }

void throwOutOfMemory(JNIEnv *env) {
  if (env->ExceptionCheck()) return;
  jclass error = env->FindClass("java/lang/OutOfMemoryError");
  if (error != nullptr) env->ThrowNew(error, "Chromaprint ran out of memory");
}

jlong create(JNIEnv *env, jclass, jint algorithm) {
  // Chromaprint silently falls back to another configuration for an unknown algorithm; refuse it instead.
  if (algorithm < CHROMAPRINT_ALGORITHM_TEST1 || algorithm > CHROMAPRINT_ALGORITHM_TEST5) return 0;
  try {
    return reinterpret_cast<jlong>(chromaprint_new(algorithm));
  } catch (const std::bad_alloc &) {
    throwOutOfMemory(env);
  } catch (...) {
  }
  return 0;
}

jboolean start(JNIEnv *env, jclass, jlong handle, jint sampleRate, jint channels) {
  if (channels <= 0) return JNI_FALSE;
  try {
    return chromaprint_start(context(handle), sampleRate, channels) == 1 ? JNI_TRUE : JNI_FALSE;
  } catch (const std::bad_alloc &) {
    throwOutOfMemory(env);
  } catch (...) {
  }
  return JNI_FALSE;
}

/** The Kotlin side has checked the range; Chromaprint only asserts on bad input in debug builds. */
jboolean feed(JNIEnv *env, jclass, jlong handle, jshortArray samples, jint offset, jint length) {
  try {
    int16_t buffer[FEED_CHUNK];
    for (jint done = 0; done < length;) {
      const jint count = std::min(FEED_CHUNK, length - done);
      env->GetShortArrayRegion(samples, offset + done, count, reinterpret_cast<jshort *>(buffer));
      if (env->ExceptionCheck()) return JNI_FALSE;
      if (chromaprint_feed(context(handle), buffer, count) != 1) return JNI_FALSE;
      done += count;
    }
    return JNI_TRUE;
  } catch (const std::bad_alloc &) {
    throwOutOfMemory(env);
  } catch (...) {
  }
  return JNI_FALSE;
}

jboolean finish(JNIEnv *env, jclass, jlong handle) {
  try {
    return chromaprint_finish(context(handle)) == 1 ? JNI_TRUE : JNI_FALSE;
  } catch (const std::bad_alloc &) {
    throwOutOfMemory(env);
  } catch (...) {
  }
  return JNI_FALSE;
}

/** Raw items; audio shorter than about 2.7 s gives none, which is an empty array, not an error. */
jintArray rawFingerprint(JNIEnv *env, jclass, jlong handle) {
  try {
    int size = 0;
    if (chromaprint_get_raw_fingerprint_size(context(handle), &size) != 1 || size <= 0) return env->NewIntArray(0);
    uint32_t *items = nullptr;
    if (chromaprint_get_raw_fingerprint(context(handle), &items, &size) != 1 || items == nullptr) {
      return env->NewIntArray(0);
    }
    jintArray result = env->NewIntArray(size);
    if (result != nullptr) env->SetIntArrayRegion(result, 0, size, reinterpret_cast<const jint *>(items));
    chromaprint_dealloc(items);
    return result;
  } catch (const std::bad_alloc &) {
    throwOutOfMemory(env);
  } catch (...) {
  }
  return nullptr;
}

/** The compressed, base64-encoded fingerprint that AcoustID takes; empty when there are no items. */
jstring encodedFingerprint(JNIEnv *env, jclass, jlong handle) {
  try {
    int size = 0;
    if (chromaprint_get_raw_fingerprint_size(context(handle), &size) != 1 || size <= 0) return env->NewStringUTF("");
    char *encoded = nullptr;
    if (chromaprint_get_fingerprint(context(handle), &encoded) != 1 || encoded == nullptr) {
      return env->NewStringUTF("");
    }
    jstring result = env->NewStringUTF(encoded);  // base64 is plain ASCII
    chromaprint_dealloc(encoded);
    return result;
  } catch (const std::bad_alloc &) {
    throwOutOfMemory(env);
  } catch (...) {
  }
  return nullptr;
}

void release(JNIEnv *, jclass, jlong handle) {
  try {
    chromaprint_free(context(handle));
  } catch (...) {
  }
}

}  // namespace

bool registerChromaprint(JNIEnv *env) {
  jclass session = env->FindClass("io/github/lorddetson/piplayer/nativelib/chromaprint/ChromaprintSession");
  if (session == nullptr) return false;
  static const JNINativeMethod methods[] = {
      {"nativeNew", "(I)J", reinterpret_cast<void *>(create)},
      {"nativeStart", "(JII)Z", reinterpret_cast<void *>(start)},
      {"nativeFeed", "(J[SII)Z", reinterpret_cast<void *>(feed)},
      {"nativeFinish", "(J)Z", reinterpret_cast<void *>(finish)},
      {"nativeRawFingerprint", "(J)[I", reinterpret_cast<void *>(rawFingerprint)},
      {"nativeEncodedFingerprint", "(J)Ljava/lang/String;", reinterpret_cast<void *>(encodedFingerprint)},
      {"nativeFree", "(J)V", reinterpret_cast<void *>(release)},
  };
  return env->RegisterNatives(session, methods, sizeof(methods) / sizeof(methods[0])) == JNI_OK;
}

}  // namespace piplayer
