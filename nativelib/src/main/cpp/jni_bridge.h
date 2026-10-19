#pragma once

#include <jni.h>

namespace piplayer {

/** Registers the TagReader natives and caches the TagSink callbacks; returns false on failure. */
bool registerTagReader(JNIEnv *env);

/** Registers the ChromaprintSession natives; returns false on failure. */
bool registerChromaprint(JNIEnv *env);

/** Registers the Resampler natives; returns false on failure. */
bool registerResampler(JNIEnv *env);

}  // namespace piplayer
