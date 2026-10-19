#include <algorithm>
#include <cstdint>
#include <memory>
#include <new>
#include <vector>

#include <jni.h>

#include <speex_resampler.h>

#include "jni_bridge.h"

// speexdsp's resampler, built as plain C with float samples (OUTSIDE_SPEEX, FLOATING_POINT). No C++ exception may
// cross the JNI boundary: std::bad_alloc becomes an OutOfMemoryError for the caller, anything else a null result.

namespace piplayer {
namespace {

/** Samples handed to speexdsp per call, so a long input is never copied out of the Java heap at once. */
constexpr jint FEED_CHUNK = 16 * 1024;

struct Resampler {
  SpeexResamplerState *state = nullptr;
  uint32_t inputRate = 0;
  uint32_t outputRate = 0;
  /** Input samples taken and output samples returned so far, to trim the flushed tail to the exact length. */
  uint64_t consumed = 0;
  uint64_t produced = 0;
};

Resampler *resampler(jlong handle) { return reinterpret_cast<Resampler *>(handle); }

void throwOutOfMemory(JNIEnv *env) {
  if (env->ExceptionCheck()) return;
  jclass error = env->FindClass("java/lang/OutOfMemoryError");
  if (error != nullptr) env->ThrowNew(error, "The resampler ran out of memory");
}

jfloatArray toJava(JNIEnv *env, const std::vector<float> &samples) {
  jfloatArray result = env->NewFloatArray(static_cast<jsize>(samples.size()));
  if (result != nullptr && !samples.empty()) {
    env->SetFloatArrayRegion(result, 0, static_cast<jsize>(samples.size()), samples.data());
  }
  return result;
}

/**
 * Runs [length] samples (zeros when [input] is null) through the filter and appends the output to [out]. speexdsp
 * takes its input in pieces of its own choosing, so it is called until everything is consumed.
 */
bool run(Resampler *r, const float *input, uint32_t length, std::vector<float> &out) {
  while (length > 0) {
    // Output for this input plus what the filter still holds; speexdsp says how much it really wrote.
    const uint64_t room = static_cast<uint64_t>(length) * r->outputRate / r->inputRate + 64;
    const size_t start = out.size();
    out.resize(start + room);
    spx_uint32_t inLength = length;
    spx_uint32_t outLength = static_cast<spx_uint32_t>(room);
    if (speex_resampler_process_float(r->state, 0, input, &inLength, out.data() + start, &outLength) !=
        RESAMPLER_ERR_SUCCESS) {
      return false;
    }
    out.resize(start + outLength);
    if (inLength == 0 && outLength == 0) return false;  // no progress: never loop forever
    if (input != nullptr) input += inLength;
    length -= inLength;
    r->consumed += inLength;
  }
  return true;
}

jlong create(JNIEnv *env, jclass, jint inputRate, jint outputRate, jint quality) {
  try {
    // Allocated first, so that a failure cannot leave speexdsp's state behind.
    auto r = std::make_unique<Resampler>();
    int error = RESAMPLER_ERR_SUCCESS;
    r->state = speex_resampler_init(1, static_cast<spx_uint32_t>(inputRate), static_cast<spx_uint32_t>(outputRate),
                                    quality, &error);
    if (r->state == nullptr || error != RESAMPLER_ERR_SUCCESS) {
      if (r->state != nullptr) speex_resampler_destroy(r->state);
      return 0;
    }
    // The output starts with the first input sample instead of half a filter length of silence.
    speex_resampler_skip_zeros(r->state);
    r->inputRate = static_cast<uint32_t>(inputRate);
    r->outputRate = static_cast<uint32_t>(outputRate);
    return reinterpret_cast<jlong>(r.release());
  } catch (const std::bad_alloc &) {
    throwOutOfMemory(env);
  } catch (...) {
  }
  return 0;
}

/** The Kotlin side has checked the range. */
jfloatArray process(JNIEnv *env, jclass, jlong handle, jfloatArray input, jint offset, jint length) {
  try {
    Resampler *r = resampler(handle);
    std::vector<float> out;
    out.reserve(static_cast<size_t>(static_cast<uint64_t>(length) * r->outputRate / r->inputRate + 64));
    std::vector<float> chunk(static_cast<size_t>(std::min(length, FEED_CHUNK)));
    for (jint done = 0; done < length;) {
      const jint count = std::min(FEED_CHUNK, length - done);
      env->GetFloatArrayRegion(input, offset + done, count, chunk.data());
      if (env->ExceptionCheck()) return nullptr;
      if (!run(r, chunk.data(), static_cast<uint32_t>(count), out)) return nullptr;
      done += count;
    }
    r->produced += out.size();
    return toJava(env, out);
  } catch (const std::bad_alloc &) {
    throwOutOfMemory(env);
  } catch (...) {
  }
  return nullptr;
}

/**
 * Pushes the filter's tail out with as many zeros as its input latency, then trims the total output to the input's
 * length at the output rate, rounded to the nearest sample.
 */
jfloatArray flush(JNIEnv *env, jclass, jlong handle) {
  try {
    Resampler *r = resampler(handle);
    const uint64_t expected = (r->consumed * r->outputRate + r->inputRate / 2) / r->inputRate;
    std::vector<float> out;
    const int latency = speex_resampler_get_input_latency(r->state);
    const uint64_t consumed = r->consumed;
    if (latency > 0 && !run(r, nullptr, static_cast<uint32_t>(latency), out)) return nullptr;
    r->consumed = consumed;  // the zeros are not input
    const uint64_t wanted = expected > r->produced ? expected - r->produced : 0;
    if (out.size() > wanted) out.resize(static_cast<size_t>(wanted));
    r->produced += out.size();
    return toJava(env, out);
  } catch (const std::bad_alloc &) {
    throwOutOfMemory(env);
  } catch (...) {
  }
  return nullptr;
}

void release(JNIEnv *, jclass, jlong handle) {
  Resampler *r = resampler(handle);
  if (r == nullptr) return;
  speex_resampler_destroy(r->state);
  delete r;
}

}  // namespace

bool registerResampler(JNIEnv *env) {
  jclass resampler = env->FindClass("io/github/lorddetson/piplayer/nativelib/resample/Resampler");
  if (resampler == nullptr) return false;
  static const JNINativeMethod methods[] = {
      {"nativeNew", "(III)J", reinterpret_cast<void *>(create)},
      {"nativeProcess", "(J[FII)[F", reinterpret_cast<void *>(process)},
      {"nativeFlush", "(J)[F", reinterpret_cast<void *>(flush)},
      {"nativeFree", "(J)V", reinterpret_cast<void *>(release)},
  };
  return env->RegisterNatives(resampler, methods, sizeof(methods) / sizeof(methods[0])) == JNI_OK;
}

}  // namespace piplayer
